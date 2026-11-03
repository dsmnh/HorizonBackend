package com.dsmnh.mobile.backend.net;

/** 一条协议帧：type + flags + payload（UTF-8 JSON，心跳为空）。 */
public record Frame(FrameType type, int flags, byte[] payload) {
    public static final int FLAG_GZIP = 0x01;
    public static final int FLAG_ENCRYPTED = 0x02;

    public static Frame of(FrameType type) {
        return new Frame(type, 0, new byte[0]);
    }

    public static Frame of(FrameType type, byte[] payload) {
        return new Frame(type, 0, payload == null ? new byte[0] : payload);
    }
}
