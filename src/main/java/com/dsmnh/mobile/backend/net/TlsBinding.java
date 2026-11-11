package com.dsmnh.mobile.backend.net;

/** 契约 §9.1：flags 0x02 逐帧绑定 TLS 连接，仅作降级剥离防护，不参与密钥派生。 */
public final class TlsBinding {
    private TlsBinding() {}

    /** 接收校验：连接为 TLS 与帧带 0x02 必须一致，否则断开。 */
    public static boolean consistent(int flags, boolean tls) {
        boolean marked = (flags & Frame.FLAG_ENCRYPTED) != 0;
        return marked == tls;
    }

    /** 发送规范化：TLS 连接所有帧置 0x02，明文连接清除该位。 */
    public static Frame bind(Frame frame, boolean tls) {
        int flags = frame.flags();
        int bound = tls ? (flags | Frame.FLAG_ENCRYPTED) : (flags & ~Frame.FLAG_ENCRYPTED);
        return bound == flags ? frame : new Frame(frame.type(), bound, frame.payload());
    }
}
