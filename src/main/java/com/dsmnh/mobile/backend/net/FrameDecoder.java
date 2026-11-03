package com.dsmnh.mobile.backend.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/** 帧解码：4B 大端 len（= type+flags+payload）+ 1B type + 1B flags + payload；违规长度直接断开。 */
public class FrameDecoder extends ByteToMessageDecoder {
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) {
            return;
        }
        in.markReaderIndex();
        long len = in.readUnsignedInt();
        if (len < 2 || len > MAX_FRAME_BYTES) {
            ctx.close();
            return;
        }
        if (in.readableBytes() < len) {
            in.resetReaderIndex();
            return;
        }
        byte type = in.readByte();
        byte flags = in.readByte();
        byte[] payload = new byte[(int) (len - 2)];
        in.readBytes(payload);
        FrameType.of(type).ifPresentOrElse(
                t -> out.add(new Frame(t, flags & 0xFF, payload)),
                () -> ctx.close());
    }
}
