package io.netty.example.file;


import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.GenericFutureListener;

import java.io.InputStreamReader;
import java.io.BufferedReader;

public class FileClient {
    private static final String HOST = System.getProperty("host", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getProperty("port", "8023"));

    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new StringEncoder(CharsetUtil.UTF_8));
                            p.addLast(new FileClientHandler());
                        }
                    });
            Channel ch = b.connect(HOST, PORT).sync().channel();
            System.out.println(String.format("Type a file path from %s:%d to fetch its content:", HOST, PORT));
            ChannelFuture lastWriteFuture = null;
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            for (;;) {
                String line = in.readLine();
                if (line != null) {
                    line = line.trim();
                }
                if (line == null || "quit".equalsIgnoreCase(line)) { // EOF or 'quit'
                    ch.close().sync();
                    break;
                } else if (line.isEmpty()){
                    continue;
                }
                // Here "\r\n" is required to end the input line since the file server's pipeline uses
                // LineBasedFrameDecoder.
                lastWriteFuture = ch.writeAndFlush(line + "\r\n");
                lastWriteFuture.addListener(new GenericFutureListener<ChannelFuture>() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            System.err.println("Write failed: ");
                            future.cause().printStackTrace(System.err);
                        }
                    }
                });
            }

            // wait until the last write finishes if there is any
            if (lastWriteFuture != null) {
                lastWriteFuture.sync();
            }

        } finally {
            // shutdown the group on non-daemon threads, otherwise the whole app won't exit after main() exits.
            group.shutdownGracefully();
        }
    }
}
