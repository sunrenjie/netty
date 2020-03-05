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
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import javax.net.ssl.SSLEngine;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RemoteConnectionPoolTester extends ChannelInitializer<SocketChannel> {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(RemoteConnectionPoolTester.class);
    private final Server server;
    private final ConcurrentLinkedQueue<Pair<Server, Long>> finishedAt;
    private final DefaultPromise<ConcurrentLinkedQueue<Pair<Server, Long>>> promise;

    // Our private simple position class instead of some Pair.
    // https://stackoverflow.com/questions/156275/what-is-the-equivalent-of-the-c-pairl-r-in-java
    public static final class Pair<L, R> {
        public final L x;
        public final R y;
        public Pair(L x, R y) {
            this.x = x;
            this.y = y;
        }
    }

    public RemoteConnectionPoolTester(Server server,
                                      DefaultPromise<ConcurrentLinkedQueue<Pair<Server, Long>>> promise,
                                      ConcurrentLinkedQueue<Pair<Server, Long>> finishedAt) {
        this.server = server;
        this.promise = promise;
        this.finishedAt = finishedAt;
    }

    public static void flushAndClose(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        SslContext sslContext = Utility.clientSslContext();
        ChannelPipeline pipeline = ch.pipeline();
        SSLEngine engine = sslContext.newEngine(ch.alloc(), server.getHost(), server.getPort());
        pipeline.addLast("sslHandler", new SslHandler(engine));
        pipeline.addLast(new ConnTestChannelHandler());
    }

    public class ConnTestChannelHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            logger.debug("Connection to " + server.toString() + " is inactive.");
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
            logger.debug(String.format("Exception in ssl handshake to %s: %s", server.toString(), e.toString()));
            flushAndClose(ctx.channel());
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof SslHandshakeCompletionEvent) {
                SslHandshakeCompletionEvent event = (SslHandshakeCompletionEvent) evt;
                if (event.cause() == null) {
                    logger.debug("SslHandshakeCompletionEvent (SUCCESS) for " + server.toString() + ".");
                    finishedAt.add(new Pair<Server, Long>(server, System.currentTimeMillis()));
                    promise.trySuccess(finishedAt);
                } else {
                    logger.debug(String.format("SslHandshakeCompletionEvent (FAILURE) for %s: %s.", server.toString(),
                            event.cause().toString()));
                }
                flushAndClose(ctx.channel());
            }
        }
    }

    public static Server startAndSelectTheBestServer(EventLoop loop, Server[] servers, long millisMaxDelay)
            throws Exception {
        ConcurrentLinkedQueue<Pair<Server, Long>> finishedAt = new ConcurrentLinkedQueue<Pair<Server, Long>>();
        logger.info(String.format("To test servers %s with timeout of %.02f seconds", Server.dumpServers(servers),
                millisMaxDelay / 1000.0));
        logger.info("Whoever finishes a full SSL handshake successfully first will be chosen.");
        Long beg = System.currentTimeMillis(), end = beg + millisMaxDelay;
        finishedAt.add(new Pair<Server, Long>(null, beg)); // the first special record, the beginning.
        final DefaultPromise<ConcurrentLinkedQueue<Pair<Server, Long>>> promise =
                new DefaultPromise<ConcurrentLinkedQueue<Pair<Server, Long>>>(loop);
        for (final Server server : servers) {
            String host = server.getHost();
            int port = server.getPort();
            Bootstrap b = new Bootstrap();
            b.group(loop)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new RemoteConnectionPoolTester(server, promise, finishedAt));
            final Channel channel = b.connect(host, port).channel();
            channel.eventLoop().schedule(new Runnable() {
                @Override
                public void run() {
                    if (channel.isActive()) {
                        logger.debug("Time out SSL connection against " + server.toString() + ".");
                        flushAndClose(channel);
                    }
                }
            }, millisMaxDelay, TimeUnit.MILLISECONDS);
        }
        // Sync wait until either time out happens or promise is done.
        while (System.currentTimeMillis() <= end) {
            if (promise.isDone()) {
                break;
            }
            Thread.sleep(100);
        }
        if (!promise.isSuccess()) {
            throw new TimeoutException("None of the servers responded in time.");
        }
        // ConcurrentLinkedQueue<Pair<Server, Long>> finishedAt = promise.getNow();
        Pair<Server, Long> start = finishedAt.poll(), next = finishedAt.poll();
        Server best = next.x;
        while (true) {
            logger.info(String.format("Server %s finished in %.02f seconds.", next.x, (next.y - start.y) / 1000.0));
            if (finishedAt.isEmpty()) {
                break;
            }
            next = finishedAt.poll();
        }
        logger.info(String.format("*** Selected %s for remote proxy server. ***", best.toString()));
        return best;
    }
}
