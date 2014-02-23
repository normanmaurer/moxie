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
package me.normanmaurer.moxie

import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.socket.nio.NioServerSocketChannel
import java.net.{URI, InetSocketAddress}
import java.io.{FileInputStream, File}
import java.util.logging.{Level, Logger}

class Moxie {}
object Moxie {
  val logger = Logger.getLogger(classOf[Moxie].getName)

  def main(args: Array[String]): Unit = {
    val stream = {
      Option(System.getProperty("moxie.config")) match {
        case Some(file: String) => new FileInputStream(file)
        case None => getClass.getClassLoader.getResourceAsStream("moxie.properties")
      }
    }
    val properties = {
      val props = System.getProperties
      props.load(stream)
      props
    }

    val repositories = {
      Option(properties.getProperty("repositories")) match {
        case Some(repos: String) => repos.split(",").map(m => URI.create(m))
        case None => throw new IllegalStateException("No repositories configured in config file!")
      }
    }
    val localRepository = {
      Option(properties.getProperty("localrepository")) match {
        case Some(local: String) => new File(local)
        case None => throw new IllegalStateException("No localrepository configured in config file!")
      }
    }
    Option(properties.getProperty("listen")).map(uri => URI.create(uri)) match {
      case Some(listenUri:URI) => {
        val addr = new InetSocketAddress(listenUri.getHost, ProxyHandler.port(listenUri))
        val group = new NioEventLoopGroup
        try {
          val bootstrap = new ServerBootstrap()
          bootstrap.group(group).channel(classOf[NioServerSocketChannel])
          bootstrap.childOption[java.lang.Boolean](ChannelOption.SO_REUSEADDR, true)
          bootstrap.childHandler(new ProxyInitializer(localRepository, repositories, ProxyHandler.isHttps(listenUri)))
          val ch = bootstrap.bind(addr).syncUninterruptibly().channel
          if (logger.isLoggable(Level.INFO)) {
            logger.info(classOf[Moxie].getName + " listing on " + ch.localAddress)
          }
          ch.closeFuture.syncUninterruptibly
        } finally {
          group.shutdownGracefully().syncUninterruptibly()
        }
      }
      case None => throw new IllegalStateException("No listen uri configured in config file!")

    }
  }
}
