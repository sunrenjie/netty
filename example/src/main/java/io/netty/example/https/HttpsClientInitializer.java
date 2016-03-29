package io.netty.example.https;


import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;

public class HttpsClientInitializer extends ChannelInitializer<SocketChannel> {
    static String host;
    private final SslContext sslCtx;
    private final int maxContentLength;

    public HttpsClientInitializer(String h, SslContext sslCtx, int maxContentLength) {
        host = h;
        this.sslCtx = sslCtx;
        this.maxContentLength = maxContentLength;
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(sslCtx.newHandler(ch.alloc()));
        pipeline.addLast("request-encoder", new HttpRequestEncoder());
        pipeline.addLast("response-decoder", new HttpResponseDecoder());
        pipeline.addLast(new HttpObjectAggregator(1024 * 100));
        pipeline.addLast(new HttpsClientHandler());
        pipeline.addLast(new UserEventLogger());
    }

    /**
     * Class that logs any User Events triggered on this channel.
     */
    private static class UserEventLogger extends ChannelInboundHandlerAdapter {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            System.out.println("User Event Triggered: " + evt);
            ctx.fireUserEventTriggered(evt);
        }
    }
}
