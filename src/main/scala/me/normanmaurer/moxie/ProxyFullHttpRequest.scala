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

import io.netty.handler.codec.http._
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.DecoderResult
import java.io.File

class ProxyFullHttpRequest(req: FullHttpRequest, file: File) extends FullHttpRequest {
  def refCnt(): Int = req.refCnt

  def release(): Boolean = req.release()

  def release(p1: Int): Boolean = req.release(p1)

  override def copy(): FullHttpRequest = new ProxyFullHttpRequest(req.copy(), file)

  def retain(p1: Int): FullHttpRequest = {
    req.retain(p1)
    this
  }

  def retain(): FullHttpRequest = {
    req.retain()
    this
  }

  def setProtocolVersion(p1: HttpVersion): FullHttpRequest = {
    req.setProtocolVersion(p1)
    this
  }

  def setMethod(p1: HttpMethod): FullHttpRequest = {
    req.setMethod(p1)
    this
  }

  def setUri(p1: String): FullHttpRequest = {
    req.setUri(p1)
    this
  }

  def getDecoderResult: DecoderResult = req.getDecoderResult

  def setDecoderResult(p1: DecoderResult): Unit = req.setDecoderResult(p1)

  def trailingHeaders(): HttpHeaders = req.trailingHeaders

  def content(): ByteBuf = req.content

  def getProtocolVersion: HttpVersion = req.getProtocolVersion

  def headers(): HttpHeaders = req.headers

  def duplicate(): FullHttpRequest = new ProxyFullHttpRequest(req.duplicate().asInstanceOf[FullHttpRequest], file)

  def getMethod: HttpMethod = req.getMethod

  def getUri: String = req.getUri

  def getFile: File = file

  override def toString = req.toString
}
