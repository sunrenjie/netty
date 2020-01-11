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
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

public class ProxyRelayClientHandler extends ChannelInboundHandlerAdapter {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ProxyRelayClientHandler.class);

    private final String id;
    private Channel clientChannel;
    private Channel remoteChannel;
    private ProxyRelayRemoteHandler remoteHandler;
    private boolean issueRemoteRequestDone;
    private String uri;
    private static final int defaultProxyServerPort = 443;
    private static Server proxyServer = loadRemoteServer("remote.server");
    private static final Server[] proxyServerPool = loadRemoteServers("remote.servers");
    private static final Pattern[] blackLists = loadBlackLists("proxy.blacklist");

    private static Server[] loadRemoteServers(String key) {
        return Server.loadServers(ConfigPropertyLoader.getProperty(key, null), defaultProxyServerPort);
    }

    private static Server loadRemoteServer(String key) {
        Server[] ss = loadRemoteServers(key);
        return ss == null ? null : ss[0];
    }

    public static void ensureRemoteIsAvailable(EventLoop loop) throws Exception {
        // TODO re-select best server when the last active connection has gone away for so long.
        if (proxyServer != null) {
            return;
        }
        if (proxyServerPool == null) {
            throw new RuntimeException("no remote server is configured");
        }
        proxyServer = RemoteConnectionPoolTester.startAndSelectTheBestServer(loop, proxyServerPool, 10000);
    }

    private static Pattern[] loadBlackLists(String key) {
        String ps = ConfigPropertyLoader.getProperty(key, null);
        if (ps == null || ps.length() == 0) {
            return null;
        }
        String[] ss = Utility.trimStrings(ps.split(","));
        Pattern[] ret = new Pattern[ss.length];
        for (int i = 0; i < ss.length; i++) {
            // In order for the pattern to actually match in anywhere in the request string, ".*" is added to the
            // beginning and ending of the pattern.
            ret[i] = Pattern.compile(".*" + ss[i] + ".*", Pattern.CASE_INSENSITIVE);
        }
        return ret;
    }

    private static boolean isInBlackList(String uri) {
        try {
            if (!uri.contains("://")) { // For HTTPS tunnel request, URI has no "https://" prefix.
                uri = "https://" + uri;
            }
            URL url = new URL(uri);
            String host = url.getHost();
            // Check black list
            if (blackLists != null) {
                for (Pattern p: blackLists) {
                    if (p.matcher(host).matches()) {
                        logger.info(String.format("Requested URI %s matches successfully the pattern %s in blacklist",
                                uri, p.toString()));
                        return true;
                    }
                }
            }
            // Check for local IPs.
            char c = host.charAt(0);
            if (c >= '0' && c <= '9') { // literal IP, for which InetAddress.getByName() is offline.
                InetAddress a = InetAddress.getByName(host);
                if (a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isLoopbackAddress() ||
                        a.isSiteLocalAddress()) {
                    logger.info(String.format("The requested URI %s is a local address.", uri));
                    return true;
                }
            }
            return false;
        } catch (MalformedURLException ex) {
            // Caused by new URL().
            logger.debug(String.format("The requested URI %s causes %s", uri, ex.toString()));
            return true;
        } catch (UnknownHostException ex) {
            // Caused by InetAddress.getByName() while doing DNS lookup; only literal IPs are
            logger.debug(String.format("The requested URI %s causes %s", uri, ex.toString()));
            return true;
        }
    }

    public ProxyRelayClientHandler(String id) {
        this.id = id;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        clientChannel = ctx.channel();
    }

    private void issueRemoteRequest(String uri, Object msg, ChannelFutureListener remoteChannelFutureListener) {
        clientChannel.config().setAutoRead(false); // disable AutoRead until remote connection is ready
        try {
            ensureRemoteIsAvailable(clientChannel.eventLoop());
        } catch (Exception ex) {
            System.err.println("NO remote server(s) are available; cannot continue:");
            System.err.println(ex.toString());
            if (remoteChannelFutureListener != null) {
                ChannelPromise promise = clientChannel.newPromise();
                promise.addListener(remoteChannelFutureListener);
                promise.tryFailure(ex);
            }
        }
        Request req = new Request(id, clientChannel, uri, msg);
        remoteHandler = new ProxyRelayRemoteHandler(req);

        Bootstrap b = new Bootstrap();
        b.group(clientChannel.eventLoop()) // use the same EventLoop
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                /* To get as bean, the instance will get its fields injected properly. */
                // To make things simpler, one ProxyRelayRemoteInitializer is created for one bootstrap and pipeline.
                .handler(new ProxyRelayRemoteInitializer(remoteHandler, proxyServer.getHost(), proxyServer.getPort()));
        ChannelFuture f = b.connect(proxyServer.getHost(), proxyServer.getPort());
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
            if (isInBlackList(this.uri)) {
                ReferenceCountUtil.release(req);
                DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN);
                ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
                return;
            }
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
