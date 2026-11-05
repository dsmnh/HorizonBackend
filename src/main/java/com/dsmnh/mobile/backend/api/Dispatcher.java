package com.dsmnh.mobile.backend.api;

import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.Connection;
import com.dsmnh.mobile.backend.net.Frame;
import com.dsmnh.mobile.backend.net.FrameType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

/**
 * 操作分发：按平面路由到登记的操作实现；业务在 biz 线程执行，RES 原路写回。
 * 预鉴权白名单（契约 §5）由协议处理器据此放行。
 */
public final class Dispatcher {
    public static final Set<String> PREAUTH_OPS = Set.of(
            "account.register", "account.login", "account.resetPassword", "node.register");

    private final ServerContext ctx;
    private final Map<String, OpHandler> appOps = new HashMap<>();
    private final Map<String, OpHandler> nodeOps = new HashMap<>();

    public Dispatcher(ServerContext ctx, AccountOps account, NodeOps node, TeamOps team) {
        this.ctx = ctx;
        AdminOps admin = new AdminOps(ctx);
        ChatOps chat = new ChatOps(ctx);
        for (Map.Entry<String, OpHandler> e : chat.ops().entrySet()) {
            register(appOps, e.getKey(), e.getValue());
            register(nodeOps, e.getKey(), e.getValue());
        }
        register(appOps, "owner.claim", admin::ownerClaim);
        register(appOps, "owner.recover", admin::ownerRecover);
        register(appOps, "admin.grantSuper", admin::grantSuper);
        register(appOps, "admin.revokeSuper", admin::revokeSuper);
        register(appOps, "admin.uuids", admin::uuids);
        register(appOps, "admin.unbindUUID", admin::unbindUUID);
        register(appOps, "account.register", account::register);
        register(appOps, "account.login", account::login);
        register(appOps, "account.resetPassword", account::resetPassword);
        register(appOps, "account.bindUUID", account::bindUUID);
        register(appOps, "account.uuids", account::uuids);
        register(appOps, "account.sessions", account::sessions);
        register(appOps, "account.revokeSession", account::revokeSession);
        register(nodeOps, "node.register", node::register);
        register(nodeOps, "node.bindCode.create", node::bindCodeCreate);
        register(nodeOps, "node.recoverCode.create", node::recoverCodeCreate);
        register(nodeOps, "node.op.ack", node::opAck);
        register(nodeOps, "node.op.fail", node::opFail);
        register(nodeOps, "team.report", node::teamReport);
        register(appOps, "admin.opSet", admin::opSet);
        for (Map.Entry<String, OpHandler> e : team.ops().entrySet()) {
            register(appOps, e.getKey(), e.getValue());
            register(nodeOps, e.getKey(), e.getValue());
        }
    }

    private void register(Map<String, OpHandler> ops, String name, OpHandler handler) {
        ops.put(name, handler);
    }

    public static boolean isPreauth(String op) {
        return PREAUTH_OPS.contains(op);
    }

    public void dispatch(Connection conn, long id, String op, JsonNode p) {
        try {
            ctx.biz().execute(() -> run(conn, id, op, p == null ? JsonNodeFactory.instance.objectNode() : p));
        } catch (RejectedExecutionException e) {
            // 服务器停机中：直接断开，不回包
            conn.close();
        }
    }

    private void run(Connection conn, long id, String op, JsonNode p) {
        try {
            OpHandler handler = opsFor(conn).get(op);
            if (handler == null) {
                respond(conn, id, false, null, ErrorCodes.UNKNOWN_OP);
                return;
            }
            ObjectNode result = handler.handle(conn, p);
            respond(conn, id, true, result, null);
        } catch (OpException e) {
            respond(conn, id, false, null, e.wire());
        } catch (Exception e) {
            respond(conn, id, false, null, ErrorCodes.BAD_PARAMS + ":p");
        } finally {
            conn.afterResponse();
        }
    }

    private void respond(Connection conn, long id, boolean ok, ObjectNode b, String error) {
        ObjectNode res = ctx.mapper.createObjectNode();
        res.put("id", id);
        res.put("ok", ok);
        res.set("b", b);
        res.put("m", error);
        res.put("more", false);
        conn.send(Frame.of(FrameType.RES, json(res)));
    }

    private Map<String, OpHandler> opsFor(Connection conn) {
        return switch (conn.serverPlane()) {
            case APP -> appOps;
            case NODE -> nodeOps;
        };
    }

    private byte[] json(ObjectNode node) {
        try {
            return ctx.mapper.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /** 按平面解析操作者身份：App 用 p.uuid（校验绑定），节点用 p.actor（契约 §6/§8）。 */
    public String identity(Connection conn, JsonNode p) {
        if (conn.serverPlane() == com.dsmnh.mobile.backend.net.Plane.APP) {
            String uuid = text(p, "uuid");
            if (!ctx.auth.isUuidBoundToAccount(uuid, conn.accountId())) {
                throw new OpException(ErrorCodes.FORBIDDEN);
            }
            return uuid;
        }
        return text(p, "actor");
    }

    public static String text(JsonNode p, String field) {
        JsonNode node = p.get(field);
        if (node == null || !node.isTextual() || node.asText().isEmpty()) {
            throw new OpException(ErrorCodes.BAD_PARAMS, field);
        }
        return node.asText();
    }

    public static long longValue(JsonNode p, String field) {
        JsonNode node = p.get(field);
        if (node == null || !node.canConvertToLong()) {
            throw new OpException(ErrorCodes.BAD_PARAMS, field);
        }
        return node.asLong();
    }

    @FunctionalInterface
    public interface OpHandler {
        ObjectNode handle(Connection conn, JsonNode p) throws Exception;
    }
}
