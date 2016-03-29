package io.netty.example.https;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import static io.netty.handler.codec.http2.Http2SecurityUtil.CIPHERS;

public class HttpsServer {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("parameters: port");
            System.exit(-1);
        }
        final int port = Integer.parseInt(args[0]);

        SelfSignedCertificate ssc = new SelfSignedCertificate();
        SslContext sslCtx;
        sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey(), null)
                .ciphers(CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                .build();
        EventLoopGroup group = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.group(group).option(ChannelOption.SO_BACKLOG, 1024).channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new HttpsServerInitializer(sslCtx));

        //.childHandler(new HttpsServerInitializer(sslCtx));
        Channel ch = b.bind(port).sync().channel();
    }
}
