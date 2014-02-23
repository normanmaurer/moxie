/**
 * Licensed to async-maven-proxy developers ('async-maven-proxy') under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * niosmtp licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.normanmaurer.maven.proxy

import io.netty.channel._
import io.netty.handler.codec.http._
import java.io.{FileInputStream, IOException, RandomAccessFile, File}
import javax.activation.MimetypesFileTypeMap
import java.net.URI
import io.netty.bootstrap.Bootstrap
import java.util
import java.nio.file._
import java.nio.ByteBuffer
import io.netty.buffer.{Unpooled, ByteBuf}
import scala.Some
import org.apache.commons.codec.digest.DigestUtils
import scala.io.Source
import java.util.logging.{Level, Logger}
import org.apache.commons.codec.binary.Base64
import io.netty.util.CharsetUtil
import javax.net.ssl.SSLContext
import io.netty.handler.ssl.SslHandler
import io.netty.util.concurrent.{Future, FutureListener}

class ProxyHandler(repositories: Seq[URI], directory: File) extends ChannelDuplexHandler {
  override def channelRead(ctx: ChannelHandlerContext, msg: scala.Any): Unit = {
    msg match {
      case req: FullHttpRequest => {
        val file = new File(directory, req.getUri)

        if (file.exists() && !file.isDirectory) {
          // Check if the sha1 matches and if not delete all cached files and fetch it again. This includes the sha1 and
          // jar.
          if (file.getName.endsWith(".jar")) {
            val sha1 = file.getName.substring(0, file.getName.length - 4) + ".sha1"
            val sha1File = new File(file.getParent, sha1)
            if (sha1File.exists()) {
              if (ProxyHandler.logger.isLoggable(Level.FINE)) {
                ProxyHandler.logger.fine("Check sha1 for " + file.getName)
              }

              val calcSha1 = DigestUtils.sha1Hex(new FileInputStream(file))
              val readSha1 = Source.fromFile(sha1File).getLines().next()
              if (!calcSha1.equals(readSha1)) {
                if (ProxyHandler.logger.isLoggable(Level.INFO)) {
                  ProxyHandler.logger.fine("Serve " + req.getUri + " via cache")
                  ProxyHandler.logger.info(
                    "Calculated sha1(" + calcSha1 + ") not match (" + readSha1 + ") for " + file.getName)

                }
                file.delete()
                sha1File.delete()
                forward(ctx, req, file)
                return
              }
            }
          }
          if (ProxyHandler.logger.isLoggable(Level.FINE)) {
            ProxyHandler.logger.fine("Serve " + req.getUri + " via cache")
          }

          val len = file.length
          val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
          HttpHeaders.setContentLength(response, len)
          response.headers().set(ProxyHandler.contentType, ProxyHandler.mimeTypesMap.getContentType(file.getPath))

          ctx.write(response)
          ctx.write(new DefaultFileRegion(new RandomAccessFile(file, ProxyHandler.readOnly).getChannel, 0, len))
          val future = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
          if (!HttpHeaders.isKeepAlive(req)) {
            future.addListener(ChannelFutureListener.CLOSE)
          }
          req.release()
        } else {
          forward(ctx, req, file)
        }
      }
    }
  }

  private [this] def forward(ctx: ChannelHandlerContext, req: FullHttpRequest, file: File) {
    ctx.channel.config.setAutoRead(false)

    val bootstrap = new Bootstrap()
    bootstrap.channel(ctx.channel.getClass).group(ctx.channel.eventLoop)
    connect(repositories.tail, ctx.channel, bootstrap, repositories.head, new ProxyFullHttpRequest(req, file))
  }

  private[this] def connect(repositories: Seq[URI], remoteChannel: Channel, bootstrap: Bootstrap, remote: URI, req: ProxyFullHttpRequest): Unit = {
    val https =  ProxyHandler.isHttps(remote)

    bootstrap.handler(new ChannelInitializer[Channel] {
      def initChannel(ch: Channel): Unit = {
        val pipeline = ch.pipeline
        if (https) {
          val engine = ProxyHandler.sslContext.createSSLEngine()
          engine.setUseClientMode(true)
          pipeline.addLast(new SslHandler(engine))
        }
        pipeline.addLast(new HttpRequestEncoder)
        pipeline.addLast(new HttpResponseDecoder)
        pipeline.addLast(new HttpContentDecompressor)
        pipeline.addLast(new ChannelDuplexHandler {

          val queue = new util.ArrayDeque[File]()
          private[this] var cachedFile:Option[CachedFile] = None

          override def channelRead(ctx: ChannelHandlerContext, msg: scala.Any): Unit = {
            msg match {
              case resp: HttpResponse => {
                val file = queue.poll()
                resp.getStatus.code match {
                  case 200 => {
                    if (!resp.headers().contains(ProxyHandler.contentType, ProxyHandler.textHtml, true)) {
                      cachedFile = Some(new CachedFile(file))
                    }
                    remoteChannel.write(resp)
                  }
                  case _ => {
                    // TODO: Handle 404 and 301 ?
                    remoteChannel.write(resp)
                  }
                }
              }
              case content: HttpContent => {
                val byteBuf = content.content.duplicate
                remoteChannel.write(content)
                try {
                  cachedFile.map(f => f.write(byteBuf))
                } catch {
                  case ex: IOException => {
                    if (ProxyHandler.logger.isLoggable(Level.WARNING)) {
                      cachedFile.map(f => {
                        ProxyHandler.logger.log(Level.WARNING,
                          "Exception during caching file " + f + " on the filesystem path "+ f.tmpFile, ex)
                      })
                    }
                    disposeFile()
                  }
                }

                if (content.isInstanceOf[LastHttpContent]) {
                  cachedFile.map(f => {
                    f.finish()
                    cachedFile = None
                  })
                  // TODO: handle multiple requests per connection ?
                  ctx.close
                }
              }
            }
          }

          override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
            disposeFile()
            deleteFiles(queue, Option(queue.poll()))
            ctx.fireExceptionCaught(cause)
          }

          private[this] def disposeFile() = {
            cachedFile.map(f => {
              f.dispose()
              cachedFile = None
            })
          }

          private[this] def deleteFiles(queue: java.util.Queue[File], f: Option[File]): Unit = {
            f.map(file => {
              file.delete()
              deleteFiles(queue, Option(queue.poll()))
            })
          }

          override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
            remoteChannel.flush()
            ctx.fireChannelReadComplete()
          }

          override def write(ctx: ChannelHandlerContext, msg: scala.Any, promise: ChannelPromise): Unit = {
            msg match {
              case req: ProxyFullHttpRequest => {
                val file = req.getFile
                queue.add(file)
                req.setUri(remote.getPath + ProxyHandler.pathSeparator + req.getUri)
                ctx.write(req)
              }
            }
          }

          private[this] class CachedFile(file: File) {
            val tmpFile = File.createTempFile(ProxyHandler.tmpFilePrefix, ProxyHandler.tmpFileSuffix)
            val channel = Files.newByteChannel(tmpFile.toPath, ProxyHandler.options:_*)

            def write(b: ByteBuf) = {
              b.nioBufferCount match {
                case 1 => write0(b.nioBuffer)
                case _ => b.nioBuffers().foreach(buf => write0(buf))
              }
            }

            def write0(b: ByteBuffer) {
              while (b.hasRemaining) {
                channel.write(b)
              }
            }

            def finish() = {
              channel.close()
              // create directory if necessary
              file.getParentFile.mkdirs()
              Files.move(tmpFile.toPath, file.toPath)
            }

            def dispose() = {
              channel.close()
              tmpFile.delete()
            }
          }
        })
      }
    })

    val port = {
      remote.getPort match {
        case -1 => {
          if (https) {
            443
          } else {
            80
          }
        }
        case port:Int => port
      }
    }

    bootstrap.connect(remote.getHost, port).addListener(new ChannelFutureListener {
      def operationComplete(f: ChannelFuture): Unit = {
        if (!f.isSuccess) {
          if (repositories.isEmpty) {
            req.release()

            val wf = remoteChannel.writeAndFlush(ProxyHandler.error.duplicate())
            if (!HttpHeaders.isKeepAlive(req)) {
              wf.addListener(ChannelFutureListener.CLOSE)
            }
          } else {
            connect(repositories.tail, remoteChannel, bootstrap, repositories.head, req)
          }
        } else {
          if (ProxyHandler.logger.isLoggable(Level.FINE)) {
            ProxyHandler.logger.fine("Serve " + req.getUri + " via " + remote)
          }
          Option(remote.getUserInfo).map(userInfo => {
            val base64 = Base64.encodeBase64String(userInfo.getBytes(CharsetUtil.UTF_8))
            req.headers().set(ProxyHandler.authorization, "Basic " + base64)
          })
          // replace host header with the correct host
          HttpHeaders.setHost(req, remote.getHost + ProxyHandler.hostPortSeparator + remote.getPort)

          // start reading again
          remoteChannel.config.setAutoRead(true)

          Option(f.channel.pipeline.get(classOf[SslHandler])) match {
            case Some(sslHandler: SslHandler) => {
              sslHandler.handshakeFuture().addListener(new FutureListener[Channel] {
                def operationComplete(future: Future[Channel]): Unit = {
                  if (!future.isSuccess) {
                    f.channel.close
                    remoteChannel.pipeline.fireExceptionCaught(f.cause)
                  } else {
                    f.channel.writeAndFlush(req).addListener(new ChannelFutureListener {
                      def operationComplete(f: ChannelFuture): Unit = {
                        if (!f.isSuccess) {
                          f.channel.close
                          remoteChannel.pipeline.fireExceptionCaught(f.cause)
                        }
                      }
                    })
                  }
                }
              })
            }
            case None => {
              f.channel.writeAndFlush(req).addListener(new ChannelFutureListener {
                def operationComplete(f: ChannelFuture): Unit = {
                  if (!f.isSuccess) {
                    f.channel.close
                    remoteChannel.pipeline.fireExceptionCaught(f.cause)
                  }
                }
              })
            }
          }
        }
      }
    })
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    ProxyHandler.logger.log(Level.WARNING, "Exception during processing", cause)
    ctx.close
  }
}

private[this] object ProxyHandler {
  val mimeTypesMap = new MimetypesFileTypeMap()
  val error = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
    HttpResponseStatus.INTERNAL_SERVER_ERROR, Unpooled.EMPTY_BUFFER)
  val options = Seq(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
  val readOnly = "r"
  val pathSeparator = "/"
  val hostPortSeparator = ":"
  val contentType = HttpHeaders.newEntity(HttpHeaders.Names.CONTENT_TYPE)
  val textHtml = HttpHeaders.newEntity("text/html")
  val authorization = HttpHeaders.newEntity(HttpHeaders.Names.AUTHORIZATION)
  val tmpFilePrefix = "asyncmavenproxy-"
  val tmpFileSuffix = ".tmp"
  val logger = Logger.getLogger(classOf[ProxyHandler].getName)
  lazy val sslContext = SSLContext.getDefault
  val httpsScheme = "https"

  def port(uri: URI): Int = {
    uri.getPort match {
      case -1 => {
        if (isHttps(uri)) {
          443
        } else {
          80
        }
      }
      case port:Int => port
    }
  }

  def isHttps(uri: URI) = httpsScheme.equalsIgnoreCase(uri.getScheme)
}
