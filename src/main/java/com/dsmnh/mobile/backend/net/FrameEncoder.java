package com.dsmnh.mobile.backend.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/** 帧编码：len 为大端 int32，值 = type + flags + payload 的总字节数。 */
public class FrameEncoder extends MessageToByteEncoder<Frame> {
    @Override
    protected void encode(ChannelHandlerContext ctx, Frame frame, ByteBuf out) {
        out.writeInt(2 + frame.payload().length);
        out.writeByte(frame.type().value);
        out.writeByte(frame.flags());
        out.writeBytes(frame.payload());
    }
}
