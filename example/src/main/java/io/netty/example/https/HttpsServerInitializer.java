package io.netty.example.https;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.DomainNameMapping;

public class HttpsServerInitializer extends ChannelInitializer<SocketChannel> {
    private final DomainNameMapping<SslContext> mapping;

    public HttpsServerInitializer(DomainNameMapping<SslContext> mapping) {
        this.mapping = mapping;
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        // has to generate a new one since it's not @Sharable.
        SniHandler sniHandler = new SniHandler(mapping);
        pipeline.addLast(sniHandler);
        pipeline.addLast("decoder", new HttpRequestDecoder());
        pipeline.addLast("encoder", new HttpResponseEncoder());
        pipeline.addLast(new HttpsServerHandler());
    }
}
