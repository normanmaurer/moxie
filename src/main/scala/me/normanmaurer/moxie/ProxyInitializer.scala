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

import io.netty.channel.{Channel, ChannelInitializer}
import io.netty.handler.codec.http.{HttpObjectAggregator, HttpRequestDecoder, HttpResponseEncoder}
import java.io.File
import java.net.URI


class ProxyInitializer(directory: File, remotes: Seq[URI], ssl: Boolean) extends ChannelInitializer[Channel] {
  def initChannel(ch: Channel): Unit = {
    // TODO: Handle ssl
    val pipeline = ch.pipeline
    pipeline.addLast(new HttpResponseEncoder)
    pipeline.addLast(new HttpRequestDecoder)
    pipeline.addLast(new HttpObjectAggregator(Integer.MAX_VALUE))
    pipeline.addLast(new ProxyHandler(remotes, directory))
  }
}
