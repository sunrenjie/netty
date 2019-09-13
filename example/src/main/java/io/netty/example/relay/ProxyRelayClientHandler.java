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

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.util.ReferenceCountUtil;

public class ProxyRelayClientHandler extends ChannelInboundHandlerAdapter {
    private final String id;
    private Channel clientChannel;
    private Channel remoteChannel;
    private ProxyRelayRemoteHandler remoteHandler;
    private boolean issueRemoteRequestDone;
    private String uri;
    private static final String proxyHost = ConfigPropertyLoader.getPropertyNotNull("remote.host");
    private static final int proxyPort = Integer.parseInt(ConfigPropertyLoader.getProperty("remote.port", "443"));

    public ProxyRelayClientHandler(String id) {
        this.id = id;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        clientChannel = ctx.channel();
    }

    private void issueRemoteRequest(String uri, Object msg, ChannelFutureListener remoteChannelFutureListener) {
        clientChannel.config().setAutoRead(false); // disable AutoRead until remote connection is ready
        Request req = new Request(id, clientChannel, uri, msg);
        remoteHandler = new ProxyRelayRemoteHandler(req);

        Bootstrap b = new Bootstrap();
        b.group(clientChannel.eventLoop()) // use the same EventLoop
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                /* To get as bean, the instance will get its fields injected properly. */
                // To make things simpler, one ProxyRelayRemoteInitializer is created for one bootstrap and pipeline.
                .handler(new ProxyRelayRemoteInitializer(remoteHandler, proxyHost, proxyPort));
        ChannelFuture f = b.connect(proxyHost, proxyPort);
        remoteChannel = f.channel();
        if (remoteChannelFutureListener != null) {
            f.addListener(remoteChannelFutureListener);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof DefaultHttpRequest) {
            // Client initial HTTP request
            DefaultHttpRequest req = (DefaultHttpRequest) msg;
            this.uri = req.uri();
            if (req.method() == HttpMethod.CONNECT) { // For TLS carrying HTTP (HTTPS) or whatever TCP protocol.
                ReferenceCountUtil.release(req);
                DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                ctx.writeAndFlush(resp);
                ctx.pipeline().remove("proxyFrontEndHelper");
            } else { // plain HTTP
                issueRemoteRequestDone = true;
                issueRemoteRequest(uri, msg, null);
            }
        } else if (msg instanceof ByteBuf || msg instanceof DefaultLastHttpContent) {
            // Whatever client packet with unknown protocol.
            if (!issueRemoteRequestDone) { // The first one; for HTTPS request, this is ClientHello.
                // TODO translate this into a standard HTTP error code
                if (uri == null) {
                    throw new RuntimeException("The requested URI is not obtained in advance.");
                }
                issueRemoteRequest(uri, msg, new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) {
                        if (!future.isSuccess()) {
                            if (msg instanceof ByteBuf) {
                                ((ByteBuf) msg).release();
                            }
                            clientChannel.close();
                        }
                    }
                });
                issueRemoteRequestDone = true;
            } else { // just forward.
                // If the remoteChannel is inactive, let the exception populate.
                remoteChannel.pipeline().writeAndFlush(msg);
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (remoteChannel != null && remoteChannel.isActive()) {
            remoteHandler.markRequestFulFilled(clientChannel);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
        System.err.println(id + " shit happens: " + e.toString());
        flushAndClose(clientChannel);
        if (remoteChannel.isActive()) {
            remoteHandler.markRequestFulFilled(clientChannel);
        }
    }

    private void flushAndClose(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
