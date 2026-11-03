package com.dsmnh.mobile.backend.net;

import java.util.Optional;

/** 帧类型登记表（HorizonProtocol 登记表 §1），值一经发布不得改动。 */
public enum FrameType {
    HELLO((byte) 0x01),
    HELLO_ACK((byte) 0x02),
    AUTH((byte) 0x03),
    AUTH_OK((byte) 0x04),
    AUTH_FAIL((byte) 0x05),
    REQ((byte) 0x10),
    RES((byte) 0x11),
    SUB((byte) 0x20),
    SUB_OK((byte) 0x21),
    PUSH((byte) 0x22),
    UNSUB((byte) 0x23),
    PING((byte) 0x30),
    PONG((byte) 0x31),
    ERR((byte) 0x40);

    public final byte value;

    FrameType(byte value) {
        this.value = value;
    }

    public static Optional<FrameType> of(byte value) {
        for (FrameType type : values()) {
            if (type.value == value) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
