package io.netty.example.https;


import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.util.CharsetUtil;

public class HttpsServerHandler extends ChannelInboundHandlerAdapter {
    private static final String htmlTemplate = "<!DOCTYPE html><html>" +
            "<head><meta http-equiv='content-type' content='text/html;charset=utf-8' /></head>" +
            "<body>%s</body></html>";
    private static final String message = "Hello, world! 你好，世界！";

    private static final AsciiString CONTENT_TYPE = new AsciiString("Content-Type");
    private static final AsciiString CONTENT_LENGTH = new AsciiString("Content-Length");
    private static final AsciiString CONNECTION = new AsciiString("Connection");
    private static final AsciiString KEEP_ALIVE = new AsciiString("keep-alive");

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            HttpRequest req = (HttpRequest) msg;

            if (HttpUtil.is100ContinueExpected(req)) {
                ctx.write(new DefaultFullHttpResponse(HTTP_1_1, CONTINUE));
            }
            boolean keepAlive = HttpUtil.isKeepAlive(req);
            String host = req.headers().get("Host", "<unknown>").split(":")[0];
            String body = String.format("<h1>From host '%s':</h1><p>%s</p>", host, message);
            byte[] outMessage = String.format(htmlTemplate, body).getBytes(CharsetUtil.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(outMessage));
            response.headers().set(CONTENT_TYPE, "text/html");
            response.headers().setInt(CONTENT_LENGTH, response.content().readableBytes());

            if (!keepAlive) {
                ctx.write(response).addListener(ChannelFutureListener.CLOSE);
            } else {
                response.headers().set(CONNECTION, KEEP_ALIVE);
                ctx.write(response);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
