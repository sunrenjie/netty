package io.netty.example.https;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.*;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

public class HttpsClient {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Parameters: host port");
            System.exit(-1);
        }
        final String host = args[0];
        final int port = Integer.parseInt(args[1]);
        final SslContext sslCtx;
        SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
        sslCtx = SslContextBuilder.forClient()
                .sslProvider(provider)
                .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                .trustManager(InsecureTrustManagerFactory.INSTANCE) // TODO security
                .build();

        EventLoopGroup workgroup = new NioEventLoopGroup();

        try {
            // configure the client
            Bootstrap b = new Bootstrap();
            b.group(workgroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .remoteAddress(host, port)
                    .handler(new HttpsClientInitializer(host, sslCtx, 4096));
            Channel channel = b.connect().syncUninterruptibly().channel();
            System.out.println("Connected to [" + host + ":" + port + "]");
            Thread.sleep(5000);
            channel.close().syncUninterruptibly();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        } finally {
            workgroup.shutdownGracefully();
        }
    }
}
