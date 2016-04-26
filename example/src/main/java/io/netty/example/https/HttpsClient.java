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

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public class HttpsClient {
    public static void main(String[] args) throws Exception {
        String host;
        int port;
        if (args.length == 0) {
            host = "www.self-signed.cn";
            System.out.println("#Info: Before running this, please be sure to add this line to /etc/hosts:");
            System.out.println("'127.0.0.1 www.self-signed.cn'");
        } else {
            host = args[0];
        }
        if (args.length <= 1) {
            port = 8443;
        } else {
            port = Integer.parseInt(args[1]);
        }
        System.out.println("Connecting to [" + host + ":" + port + "] ...");
        final SslContext sslCtx;
        SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
        InputStream is = HttpsClient.class.getResourceAsStream("v1.private-key.pem.csr.crt");
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) certFactory.generateCertificate(is);

        sslCtx = SslContextBuilder.forClient()
                .sslProvider(provider)
                .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                .trustManager(cert) // trust our HttpsServer's self-signed certificate
                //.trustManager(InsecureTrustManagerFactory.INSTANCE) // skip any certificate verification
                // If no trustManager() is called, the JDK built-in certificates will be used to perform
                // certificate verification, in which case our HttpsServer's self-signed certificate will
                // effectively be rejected.
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
