package com.dsmnh.mobile.backend.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** 帧编解码与边界：roundtrip、上限 16MiB、len&lt;2 断开。 */
class FrameCodecTest {

    @Test
    void roundtrip() {
        EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder(), new FrameEncoder());
        byte[] payload = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

        ch.writeOutbound(Frame.of(FrameType.REQ, payload));
        ByteBuf out = ch.readOutbound();
        byte[] wire = new byte[out.readableBytes()];
        out.readBytes(wire);

        // 线上帧 = 4B len + 1B type + 1B flags + payload
        assertEquals(4 + 2 + payload.length, wire.length);
        assertEquals(FrameType.REQ.value, wire[4]);
        assertEquals(0, wire[5]);

        ch.writeInbound(Unpooled.wrappedBuffer(wire));
        Frame in = ch.readInbound();
        assertEquals(FrameType.REQ, in.type());
        assertArrayEquals(payload, in.payload());
        ch.finishAndReleaseAll();
    }

    @Test
    void rejectsOversizedFrame() {
        EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
        ByteBuf header = Unpooled.buffer();
        header.writeInt(FrameDecoder.MAX_FRAME_BYTES + 1);
        ch.writeInbound(header);
        assertFalse(ch.isOpen());
    }

    @Test
    void rejectsShortLength() {
        EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
        ByteBuf header = Unpooled.buffer();
        header.writeInt(1);
        ch.writeInbound(header);
        assertFalse(ch.isOpen());
    }

    @Test
    void rejectsUnknownFrameType() {
        EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
        ByteBuf frame = Unpooled.buffer();
        frame.writeInt(2);
        frame.writeByte(0x7F);
        frame.writeByte(0);
        ch.writeInbound(frame);
        assertFalse(ch.isOpen());
    }
}
