package com.dsmnh.mobile.backend.net;

import com.dsmnh.mobile.backend.api.Dispatcher;
import com.dsmnh.mobile.backend.api.ErrorCodes;
import com.dsmnh.mobile.backend.auth.AuthService;
import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.util.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;

/**
 * 协议状态机：AWAIT_HELLO → AWAIT_AUTH → ACTIVE（契约 §3/§4）。
 * 除 PING 外的帧处理均转 biz 线程，I/O 线程只做解析与分发。
 */
public final class ProtocolHandler extends SimpleChannelInboundHandler<Frame> {
    public static final int PROTOCOL_VERSION = 1;
    private static final Logger LOG = LoggerFactory.getLogger(ProtocolHandler.class);

    private final ServerContext ctx;
    private final Plane serverPlane;
    private final java.util.concurrent.atomic.AtomicInteger activeCount;
    private Connection conn;

    public ProtocolHandler(ServerContext ctx, Plane serverPlane,
                           java.util.concurrent.atomic.AtomicInteger activeCount) {
        this.ctx = ctx;
        this.serverPlane = serverPlane;
        this.activeCount = activeCount;
    }

    @Override
    public void channelActive(ChannelHandlerContext c) {
        if (activeCount.incrementAndGet() > ctx.config.maxConnectionsPerPlane) {
            activeCount.decrementAndGet();
            c.close();
            return;
        }
        String ip = c.channel().remoteAddress() instanceof java.net.InetSocketAddress addr
                ? addr.getAddress().getHostAddress() : "unknown";
        conn = new Connection(c, serverPlane, ip);
        c.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext c) {
        if (conn != null) {
            ctx.auth.unregisterLive(conn);
        }
        activeCount.decrementAndGet();
        c.fireChannelInactive();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext c, Frame frame) {
        switch (frame.type()) {
            case HELLO -> handleHello(frame);
            case AUTH -> submit(() -> handleAuth(frame));
            case REQ -> submit(() -> handleReq(frame));
            case SUB -> submit(() -> handleSub(frame));
            case UNSUB -> submit(() -> handleUnsub(frame));
            case PING -> conn.send(Frame.of(FrameType.PONG));
            case PONG -> { /* 客户端保活应答，忽略 */ }
            default -> errAndClose("BAD_PARAMS:state");
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext c, Object evt) {
        if (evt instanceof IdleStateEvent) {
            LOG.debug("连接空闲超时 {} {}", conn != null ? conn.ip : "?", serverPlane);
            c.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext c, Throwable cause) {
        LOG.warn("连接异常 {}: {}", conn != null ? conn.ip : "?", cause.toString());
        c.close();
    }

    // ---------- 握手 ----------

    private void handleHello(Frame frame) {
        if (conn.state() != Connection.State.AWAIT_HELLO) {
            errAndClose("BAD_PARAMS:state");
            return;
        }
        JsonNode hello = parse(frame);
        if (hello == null) {
            return;
        }
        if (!hello.hasNonNull("v") || hello.get("v").asInt() != PROTOCOL_VERSION) {
            errAndClose("VERSION_MISMATCH:server=" + PROTOCOL_VERSION);
            return;
        }
        Plane declared = hello.hasNonNull("plane") ? Plane.fromWire(hello.get("plane").asText()) : null;
        if (declared != serverPlane) {
            errAndClose("BAD_PLANE");
            return;
        }
        conn.app(hello.hasNonNull("app") ? hello.get("app").asText() : "");
        String nonce = Ids.nonce();
        conn.nonce(nonce);
        conn.state(Connection.State.AWAIT_AUTH);

        ObjectNode ack = JsonNodeFactory.instance.objectNode();
        ack.put("v", PROTOCOL_VERSION);
        ack.put("name", ctx.config.networkName);
        ack.put("ver", ctx.config.backendVersion);
        ack.put("time", System.currentTimeMillis() / 1000);
        ack.put("nonce", nonce);
        ArrayNode caps = ack.putArray("caps");
        if (serverPlane == Plane.APP) {
            caps.add("account");
            caps.add("admin");
        } else {
            caps.add("ops");
        }
        caps.add("team");
        ack.put("plane", serverPlane.wire);
        conn.send(Frame.of(FrameType.HELLO_ACK, json(ack)));
    }

    private void handleAuth(Frame frame) {
        if (conn.state() != Connection.State.AWAIT_AUTH) {
            errAndClose("BAD_PARAMS:state");
            return;
        }
        if (ctx.rateLimiter.isBanned(conn.ip)) {
            authFail("BANNED", false);
            return;
        }
        JsonNode auth = parse(frame);
        if (auth == null) {
            return;
        }
        String kid = auth.hasNonNull("kid") ? auth.get("kid").asText() : null;
        long ts = auth.hasNonNull("ts") && auth.get("ts").canConvertToLong()
                ? auth.get("ts").asLong() : Long.MIN_VALUE;
        String sig = auth.hasNonNull("sig") ? auth.get("sig").asText() : null;
        if (kid == null || kid.isEmpty() || sig == null || sig.isEmpty() || ts == Long.MIN_VALUE) {
            authFail("AUTH_FAILED", true);
            return;
        }
        try {
            var record = ctx.auth.verify(conn, kid, ts, sig);
            LOG.info("鉴权成功：plane={} kind={} id={}", serverPlane.wire,
                    record.kind(), record.kind() == com.dsmnh.mobile.backend.auth.TokenRecord.Kind.NODE
                            ? record.nodeId() : record.accountId());
            conn.bind(record);
            conn.state(Connection.State.ACTIVE);
            ctx.auth.registerLive(conn);
            ObjectNode ok = JsonNodeFactory.instance.objectNode();
            ok.put("role", ctx.auth.roleOf(record.accountId()));
            conn.send(Frame.of(FrameType.AUTH_OK, json(ok)));
        } catch (AuthService.AuthError e) {
            LOG.info("鉴权失败：{}（plane={}，kid={}）", e.code, serverPlane.wire, kid);
            boolean counted = "AUTH_FAILED".equals(e.code);
            authFail(e.code, counted);
        }
    }

    private void authFail(String code, boolean countFailure) {
        if (countFailure && ctx.rateLimiter.failure(conn.ip)) {
            code = "BANNED";
        }
        if (countFailure && conn.authFailed()) {
            conn.send(Frame.of(FrameType.AUTH_FAIL, json(authFailNode(code))));
            conn.close();
            return;
        }
        conn.send(Frame.of(FrameType.AUTH_FAIL, json(authFailNode(code))));
    }

    private ObjectNode authFailNode(String code) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("code", code);
        node.put("time", System.currentTimeMillis() / 1000);
        return node;
    }

    // ---------- 请求与订阅 ----------

    private void handleReq(Frame frame) {
        JsonNode req = parse(frame);
        if (req == null) {
            return;
        }
        if (!req.hasNonNull("id") || !req.get("id").canConvertToLong()
                || !req.hasNonNull("op") || !req.get("op").isTextual()) {
            errAndClose("BAD_PARAMS:id");
            return;
        }
        long id = req.get("id").asLong();
        String op = req.get("op").asText();
        JsonNode p = req.get("p");
        if (conn.state() != Connection.State.ACTIVE) {
            if (ctx.rateLimiter.isBanned(conn.ip) && Dispatcher.isPreauth(op)) {
                respondError(id, ErrorCodes.RATE_LIMITED + ":auth");
                return;
            }
            if (!Dispatcher.isPreauth(op)) {
                respondError(id, ErrorCodes.AUTH_REQUIRED);
                return;
            }
        }
        ctx.dispatcher.dispatch(conn, id, op, p);
    }

    private void handleSub(Frame frame) {
        JsonNode req = parse(frame);
        if (req == null) {
            return;
        }
        if (!req.hasNonNull("id") || !req.get("id").canConvertToLong()
                || !req.hasNonNull("op") || !req.get("op").isTextual()) {
            errAndClose("BAD_PARAMS:id");
            return;
        }
        long id = req.get("id").asLong();
        if (conn.state() != Connection.State.ACTIVE) {
            respondError(id, ErrorCodes.AUTH_REQUIRED);
            return;
        }
        String op = req.get("op").asText();
        JsonNode p = req.get("p");
        if ("ops".equals(op)) {
            // 节点下行操作流（1.2.0）：按当前连接的节点身份订阅
            if (serverPlane != Plane.NODE || conn.nodeId() < 0) {
                respondError(id, ErrorCodes.UNKNOWN_OP);
                return;
            }
            String resumeSid = p != null && p.hasNonNull("sid") ? p.get("sid").asText() : null;
            if (resumeSid != null && !resumeSid.isEmpty()) {
                ctx.opsStream.resume(conn, id, conn.nodeId(), resumeSid);
            } else {
                ctx.opsStream.subscribe(conn, id, conn.nodeId());
            }
            return;
        }
        if (!"team".equals(op)) {
            respondError(id, ErrorCodes.UNKNOWN_OP);
            return;
        }
        if (serverPlane != Plane.APP) {
            respondError(id, ErrorCodes.UNKNOWN_OP);
            return;
        }
        String uuid;
        try {
            uuid = ctx.dispatcher.identity(conn, p);
        } catch (com.dsmnh.mobile.backend.api.OpException e) {
            respondError(id, e.wire());
            return;
        }
        String resumeSid = p != null && p.hasNonNull("sid") ? p.get("sid").asText() : null;
        if (resumeSid != null && !resumeSid.isEmpty()) {
            ctx.stream.resume(conn, id, uuid, resumeSid);
        } else {
            ctx.stream.subscribe(conn, id, uuid);
        }
    }

    private void handleUnsub(Frame frame) {
        JsonNode req = parse(frame);
        if (req == null) {
            return;
        }
        if (req.hasNonNull("s") && req.get("s").canConvertToLong()) {
            long s = req.get("s").asLong();
            ctx.stream.unsubscribe(conn, s);
            ctx.opsStream.unsubscribe(conn, s);
        }
    }

    private void respondError(long id, String message) {
        ObjectNode res = JsonNodeFactory.instance.objectNode();
        res.put("id", id);
        res.put("ok", false);
        res.putNull("b");
        res.put("m", message);
        res.put("more", false);
        conn.send(Frame.of(FrameType.RES, json(res)));
    }

    // ---------- 辅助 ----------

    private void errAndClose(String message) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("m", message);
        conn.send(Frame.of(FrameType.ERR, json(node)));
        conn.close();
    }

    private JsonNode parse(Frame frame) {
        try {
            if (frame.payload().length == 0) {
                return JsonNodeFactory.instance.objectNode();
            }
            return ctx.mapper.readTree(frame.payload());
        } catch (Exception e) {
            errAndClose("BAD_PARAMS:payload");
            return null;
        }
    }

    private byte[] json(ObjectNode node) {
        try {
            return ctx.mapper.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private void submit(Runnable task) {
        try {
            ctx.biz().execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    LOG.warn("帧处理异常 {}", e.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            conn.close();
        }
    }
}
