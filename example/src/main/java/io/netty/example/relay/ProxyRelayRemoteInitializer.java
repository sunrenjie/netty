/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.example.relay;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;

import javax.net.ssl.SSLEngine;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;


public class ProxyRelayRemoteInitializer extends ChannelInitializer<SocketChannel> {
    private final ProxyRelayRemoteHandler remoteHandler;
    private final String host;
    private final int port;

    public ProxyRelayRemoteInitializer(ProxyRelayRemoteHandler remoteHandler, String host, int port) {
        this.remoteHandler = remoteHandler;
        this.host = host;
        this.port = port;
    }

    private static Set<String> sessionIdSet(Enumeration<byte[]> sessionIds) {
        Set<String> idSet = new HashSet<String>();
        byte[] id;
        while (sessionIds.hasMoreElements()) {
            id = sessionIds.nextElement();
            idSet.add(ByteBufUtil.hexDump(Unpooled.wrappedBuffer(id)));
        }
        return idSet;
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        SslContext sslContext = Utility.clientSslContext();
        ChannelPipeline pipeline = ch.pipeline();
        // Add two LoggingHandler, one in front of the ssl handler and another behind it, with different level,
        // to see more clearly the ssl processing in the log.
        System.err.println("Known sessions: " + sessionIdSet(sslContext.sessionContext().getIds()).toString());
        SSLEngine engine = sslContext.newEngine(ch.alloc(), host, port);

        pipeline.addLast("sslHandler", new SslHandler(engine));
        ch.pipeline().addLast("loggingHandlerBehindSSLHandler", new ConciseLoggingHandler(LogLevel.WARN));
        pipeline.addLast("proxyAuthenticationHelper", new HttpClientCodec(
                // Use the full constructor to set parseHttpAfterConnectRequest to true
                4096, 8192, 8192, false, true, true));
        //ch.pipeline().addLast("loggingHandlerBehindProxyAuth", new LoggingHandler(LogLevel.WARN));
        pipeline.addLast("coreHandler", remoteHandler);
    }
}
