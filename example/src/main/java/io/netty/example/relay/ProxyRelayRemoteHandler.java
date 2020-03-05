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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;


import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.buffer.Unpooled.copiedBuffer;

/**
 * Design
 * This handler handles HTTP and HTTPS requests differently:
 * For HTTP requests, only one pipeline (and handler) is created that will queue and serve all of them.
 * This is to re-use existing connections as much as possible.
 * For HTTPS requests, one pipeline (and handler) has to be created to serve one request, and then gets destroyed.
 * This is because HTTPS tunneling cannot be re-used inherently.
 *
 * 1. Whether this handler is for HTTP? this.req == null in constructor. The requests shall be enqued via the
 * #enqueNewRequest() method.
 * 2. How are the different handlers behavior differently? At #userEventTriggered() when ssl handshake finishes
 * successfully, the one-and-only HTTPS request will be served immediately, the HTTP requests in the queue will be
 * served by a periodic job run by ctx.executor().scheduleAtFixedRate().
 */

public class ProxyRelayRemoteHandler extends ChannelInboundHandlerAdapter {
    public static final String proxyAuthHelper = "proxyAuthHelper";
    private ChannelHandlerContext ctx;
    private static String proxyCred;
    private Request req;
    // A HttpClientCodec named proxyAuthHelper is installed in ProxyRelayRemoteInitializer; so defaults to true here.
    private boolean proxyAuthHelperIsInstalled = true;
    // The ssl session is available to new requests after ssl handshake is completed and before a response to HTTP
    // CONNECT method is received (after HTTP tunneling is set up, the connection is not available to other requests).
    private Boolean sslSessionAvailable;
    private static final AtomicInteger numActiveChannels = new AtomicInteger(0);
    private static final int numActiveChannelsMax = 25;

    private final InternalLogger logger;

    static {
        // TODO parameterize the credential externally.
        String proxyUsername = ConfigPropertyLoader.getPropertyNotNull("remote.username");
        String proxyPassword = ConfigPropertyLoader.getPropertyNotNull("remote.password");
        // Since java.util.Base64 is only available since Java 1.8, we shall not use that here.
        ByteBuf tmp = Base64.encode(copiedBuffer(proxyUsername + ":" + proxyPassword,
                CharsetUtil.US_ASCII));
        byte[] bs = new byte[tmp.readableBytes()];
        tmp.getBytes(0, bs);
        proxyCred = new String(bs);
    }

    /**
     *
     * @return the HttpClientCodec responsible for parsing responses from remote servers.
     */
    public static HttpClientCodec getProxyAuthHelper() {
        return new HttpClientCodec(
                // TODO figure out whether it is still necessary to set parseHttpAfterConnectRequest to true.
                // Use the full constructor to set parseHttpAfterConnectRequest to true
                4096, 8192, 8192, false, true, false);
    }

    ProxyRelayRemoteHandler(Request req) {
        this.req = req;
        logger = InternalLoggerFactory.getInstance(getClass());
    }

    void markRequestFulFilled(Channel clientChannel) {
        if (req == null || req.clientChannel != clientChannel) {
            logger.error("A request finish confirmation is received, but it is invalid already.");
            return;
        }
        logger.debug("Marking the request fulfilled: " + req);
        req.fulfilled = true;
        ctx.executor().execute(new Runnable() {
            @Override
            public void run() {
                ctx.channel().close();
            }
        });
    }

    private void startNewRequest(ChannelHandlerContext ctx, Request req) {
        logger.error("Serving the new request: " + req);
        // Create HTTP tunnel via a CONNECT method. Here we send auth info to get a 200 OK response.
        // TODO send the initial request without auth info to get a "HTTP/1.1 407 Proxy Authentication Required".
        DefaultHttpRequest request;
        if (req.isSSL()) {
            request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, req.uri);
            request.headers().set(HttpHeaderNames.HOST, req.uri);
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        } else {
            request = (DefaultHttpRequest) req.msg;
        }
        request.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, "Basic " + proxyCred);

        // Always install a new ClientHttpCodec for a new request, as the codec cannot be re-used across requests,
        // because of internal states. For one example of internal status inconsistency with one ClientHttpCodec when
        // used across two HTTP requests, @see {@link HttpObjectEncoder#encode(ChannelHandlerContext, Object,
        // List<Object>)}.
        // TODO maybe we could find a way to reset the statue value and hence re-use the codec.
        if (proxyAuthHelperIsInstalled) {
            ctx.pipeline().remove(proxyAuthHelper);
        } else {
            proxyAuthHelperIsInstalled = true;
        }
        ctx.pipeline().addBefore("coreHandler", proxyAuthHelper, getProxyAuthHelper());
        ctx.pipeline().writeAndFlush(request);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        int n = numActiveChannels.getAndIncrement();
        if (n > numActiveChannelsMax) {
            logger.error("Number of remote connection reaches " + n);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof DefaultHttpResponse) {
            DefaultHttpResponse resp = (DefaultHttpResponse) msg;
            // TODO strip of proxy-related info from resp.
            if (req.isSSL()) {
                // The "HTTP/1.1 200 Connection Established\r\n\r\n" is returned immediately upon CONNECT method
                // request in child handler, so this 200 OK response from remote server is not forwarded.
                if (resp.status().equals(HttpResponseStatus.OK)) { // CONNECT tunnel request returns 200 OK
                    // Now switch to tunnel mode, initialize the original user request.
                    sslSessionAvailable = false; // mark as inaccessible to other requests
                    // No need to remove the client http codec here; if further communications are not HTTP.
                    ctx.pipeline().remove(proxyAuthHelper);
                    proxyAuthHelperIsInstalled = false;
                    // Forward the client HTTP request to remote. For HTTPS it is ClientHello;
                    // for HTTP it is the original request amended with the HTTP header Proxy-Authorization.
                    ctx.writeAndFlush(req.msg);
                } else {
                    ReferenceCountUtil.release(req.msg);
                    req.clientChannel.close();
                }
            } else {
                req.clientChannel.writeAndFlush(resp);
            }
            req.clientChannel.config().setAutoRead(true);
            // For plain HTTP, nothing more to do.
        } else if (msg instanceof ByteBuf || msg instanceof DefaultHttpContent) {
            if (msg instanceof ByteBuf) {
                ByteBuf in = (ByteBuf) msg;
                if (in.getInt(0) == 0x15030300) {
                    // SSL close notification in the form of encrypted alert.
                    ReferenceCountUtil.release(in);
                    logger.error("SSL close notification is received from proxy.");
                    return;
                }
            }
            if (Request.isRequestValid(req)) {
                req.clientChannel.writeAndFlush(msg); // just forward the raw ByteBuf to the client
            } else {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (Request.isRequestValid(req)) {
            flushAndClose(req.clientChannel);
        }
        numActiveChannels.decrementAndGet();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
        logger.error("exceptionCaught while serving the request: " + (req == null ? "(NA)" : req.toString()), e);
        if (Request.isRequestValid(req)) {
            flushAndClose(req.clientChannel);
        }
        flushAndClose(ctx.channel());
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof SslHandshakeCompletionEvent) {
            SslHandshakeCompletionEvent event = (SslHandshakeCompletionEvent) evt;
            if (event.cause() != null) {
                logger.error("Received failed event: " + event);
                event.cause().printStackTrace();
                flushAndClose(ctx.channel());
            } else {
                sslSessionAvailable = true;
                startNewRequest(ctx, this.req);
            }
        }
    }

    private void flushAndClose(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
