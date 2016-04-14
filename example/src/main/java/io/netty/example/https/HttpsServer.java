package io.netty.example.https;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.*;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.DomainMappingBuilder;
import io.netty.util.DomainNameMapping;

import java.io.InputStream;

import static io.netty.handler.codec.http2.Http2SecurityUtil.CIPHERS;

public class HttpsServer {
    public static void main(String[] args) throws Exception {
        int port;
        if (args.length == 1) {
            port = Integer.parseInt(args[0]);
        } else {
            port = 8443;
        }
        System.out.println("Binding to [localhost:" + port + "] ...");
        //SelfSignedCertificate ssc = new SelfSignedCertificate();
        InputStream inCert = HttpsServer.class.getResourceAsStream("v1.private-key.pem.csr.crt");
        InputStream inKey = HttpsServer.class.getResourceAsStream("v1.private-key.pkcs8.pem");
        assert(inCert != null);
        assert(inKey != null);
        SslContext sslCtx;
        sslCtx = SslContextBuilder.forServer(inCert, inKey, null)
                .ciphers(CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                .build();
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        SslContext selfAsignedContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
                .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        "self-assigned-certificate for '*.self-assigned.cn' domain(s)"
                )).build();
        final DomainNameMapping<SslContext> mapping = new DomainMappingBuilder<SslContext>(sslCtx)
                .add("*.self-assigned.cn", selfAsignedContext).build();
        EventLoopGroup group = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.group(group).option(ChannelOption.SO_BACKLOG, 1024).channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new HttpsServerInitializer(mapping));
        Channel ch = b.bind(port).sync().channel();
    }
}
