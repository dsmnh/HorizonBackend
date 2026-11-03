package com.dsmnh.mobile.backend.net;

import com.dsmnh.mobile.backend.auth.TokenRecord;
import io.netty.channel.ChannelHandlerContext;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** 一条客户端连接：握手状态、所属凭证、发送队列水位与响应后动作。 */
public final class Connection {
    public enum State { AWAIT_HELLO, AWAIT_AUTH, ACTIVE }

    public static final int MAX_PENDING_FRAMES = 512;
    public static final int MAX_AUTH_FAILURES = 5;

    public final ChannelHandlerContext channel;
    public final Plane serverPlane;
    public final String ip;

    private volatile State state = State.AWAIT_HELLO;
    private volatile String nonce;
    private volatile String app = "";
    private volatile TokenRecord token;
    private volatile long streamSeq;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();
    private final Queue<Runnable> afterResponse = new ConcurrentLinkedQueue<>();

    public Connection(ChannelHandlerContext channel, Plane serverPlane, String ip) {
        this.channel = channel;
        this.serverPlane = serverPlane;
        this.ip = ip;
    }

    public State state() {
        return state;
    }

    public void state(State newState) {
        this.state = newState;
    }

    public String nonce() {
        return nonce;
    }

    public void nonce(String value) {
        this.nonce = value;
    }

    public Plane serverPlane() {
        return serverPlane;
    }

    public ChannelHandlerContext channel() {
        return channel;
    }

    public String app() {
        return app;
    }

    public void app(String value) {
        this.app = value == null ? "" : value;
    }

    /** 连接内递增的订阅流序号（SUB_OK.s / PUSH.s）。 */
    public long nextStreamId() {
        return ++streamSeq;
    }

    public TokenRecord token() {
        return token;
    }

    public void bind(TokenRecord record) {
        this.token = record;
    }

    public long accountId() {
        return token == null ? -1 : token.accountId();
    }

    public String sessionId() {
        return token == null ? null : token.sessionId();
    }

    public long nodeId() {
        return token == null ? -1 : token.nodeId();
    }

    /** 鉴权失败计数；达到上限返回 true（调用方负责断开）。 */
    public boolean authFailed() {
        return failures.incrementAndGet() >= MAX_AUTH_FAILURES;
    }

    public void send(Frame frame) {
        if (!channel.channel().isActive()) {
            return;
        }
        if (pending.incrementAndGet() > MAX_PENDING_FRAMES) {
            channel.close();
            return;
        }
        channel.writeAndFlush(frame).addListener(f -> {
            pending.decrementAndGet();
            if (!f.isSuccess()) {
                channel.close();
            }
        });
    }

    /** 在下一条 RES 写出后执行（如注销自身会话后断开）。 */
    public void runAfterResponse(Runnable action) {
        afterResponse.add(action);
    }

    public void afterResponse() {
        Runnable action;
        while ((action = afterResponse.poll()) != null) {
            action.run();
        }
    }

    public void close() {
        channel.close();
    }
}
