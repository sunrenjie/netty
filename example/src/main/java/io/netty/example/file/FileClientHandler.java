package io.netty.example.file;


import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.internal.OneTimeTask;

import java.util.concurrent.TimeUnit;

/*

The file client is created to experiment with the autoRead feature. Here keeps the notes.

This feature is implemented in terms of adding/removing NIO's SelectionKey's OP_READ bit to the interest set.
One example of using this feature is in transport/io.netty.bootstrap.ServerBootstrap#exceptionCaught() (we copied the
code below).

To experiment with it, we create a text file that is about 1800 bytes, a bit above the threshold at which point the
client will disable the autoRead for several seconds and pause the data transmission. The file is crafted such that it
is easy to tell the offset of any given excerpt, and is generated by the python script:

import six
with open("out.txt", "w") as f:
  for i in six.moves.xrange(0, 79):
    f.write("0x%016x\n" % i)

==========================================================
About io.netty.channel.DefaultChannelConfig#setAutoRead():

1) When setting the flag from false to true, it simply issue a channel.read().
Turns out that when issuing a read,
transport:io.netty.channel.nio.AbstractNioChannel#doBeginRead() will set
OP_READ in the interest set if it is not already set on.

2) When setting this flag from true to false,
io.netty.channel.nio.AbstractNioChannel.AbstractNioUnsafe#removeReadOp() is
finally called to remove OP_READ.

========================================
calling stuff while writing a FileRegion

io.netty.handler.stream.ChunkedWriteHandler#write()
  it merely does queue.add() the writing instruction to be poll()'ed by
  #doFlush(), which executes it by calling ctx.write(), which will finally
  have the next one called.

io.netty.channel.ChannelOutboundBuffer#addMessage()
  it also delay the writing instruction until io.netty.channel.DefaultChannelPipeline$HeadContext#flush()
  (which is called when the flush operation reaches the pipeline end) calls
  unsafe.flush() (this unsafe is part of the underlying io.netty.channel.Channel),
  which then calls the channel's doWrite(outboundBuffer).

io.netty.channel.socket.nio.NioSocketChannel#doWrite()
In our setup, what's
  called will be io.netty.channel.socket.nio.NioSocketChannel#doWrite(), which
  will call super.doWrite() with the ChannelOutboundBuffer (since the underlying
  message is a FileRegion instead of ByteBuf, the nioBufferCnt is zero).

io.netty.channel.nio.AbstractNioByteChannel#doWrite()
Here it will repeatedly call ChannelOutboundBuffer#current() to fetch next
message and, after recognizing the message as a FileRegion, calls doWriteFileRegion().

io.netty.channel.socket.nio.NioSocketChannel#doWriteFileRegion()
This is the real place the file is written.

 */

public class FileClientHandler extends SimpleChannelInboundHandler<Object> {
    private int numBytesRead = 0;
    private int receivedWithPeriod = 0;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            int len = buf.readableBytes();
            numBytesRead += len;
            receivedWithPeriod += len;
            System.out.printf("FileClient received %d bytes (%d total thus far):\n", len, numBytesRead);
            System.out.print("The first few bytes are:");
            int bytesToWrite = len > 16 ? 16 : len;
            for (int i = 0; i < bytesToWrite; i++) {
                byte b = buf.getByte(i);
                System.out.printf(" %02x", b);
            }
            System.out.print(" ...\n");
            // borrowed from transport/io.netty.bootstrap.ServerBootstrap#exceptionCaught()
            if (receivedWithPeriod > 1000) {
                System.out.println("#Warning: temporarily received too much data; will delay a bit ...");
                final ChannelConfig config = ctx.channel().config();
                if (config.isAutoRead()) {
                    config.setAutoRead(false);
                    ctx.channel().eventLoop().schedule(new OneTimeTask() {
                        @Override
                        public void run() {
                            config.setAutoRead(true);
                        }
                    }, 5, TimeUnit.SECONDS);
                }
                receivedWithPeriod = 0;
            }
        }
    }
}
