package com.dsmnh.mobile.backend.sub;

import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.Connection;
import com.dsmnh.mobile.backend.net.Frame;
import com.dsmnh.mobile.backend.net.FrameType;
import com.dsmnh.mobile.backend.util.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 节点下行操作流（契约 1.2.0 §8.1）：按节点推送待执行操作，
 * 续传机制与 TeamStream 同源（sid/seq/boot，服务端游标）；payload 为 ops 信封。
 */
public final class NodeOpsStream {
    private static final int LOG_CAPACITY = 4096;
    private static final int HEARTBEAT_SEC = 25;

    public record StreamEvent(long seq, long nodeId, ObjectNode event) {}

    static final class Subscription {
        final String sid;
        final long nodeId;
        long cursor;
        long lastPushMs;
        long s;
        Connection conn;

        Subscription(String sid, long nodeId, long s, Connection conn) {
            this.sid = sid;
            this.nodeId = nodeId;
            this.s = s;
            this.conn = conn;
        }
    }

    private final ServerContext ctx;
    private final long boot = System.currentTimeMillis() / 1000;
    private final Object lock = new Object();
    private final ArrayDeque<StreamEvent> log = new ArrayDeque<>();
    private final Map<String, Subscription> subsBySid = new HashMap<>();
    private long nextSeq;
    private ScheduledExecutorService heartbeat;

    public NodeOpsStream(ServerContext ctx) {
        this.ctx = ctx;
    }

    public void start() {
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ops-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(this::beat, HEARTBEAT_SEC, HEARTBEAT_SEC, TimeUnit.SECONDS);
    }

    public void close() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
    }

    public void subscribe(Connection conn, long reqId, long nodeId) {
        synchronized (lock) {
            Subscription sub = new Subscription(Ids.sid(), nodeId, conn.nextStreamId(), conn);
            subsBySid.put(sub.sid, sub);
            sendSubOk(conn, reqId, sub);
            pushFull(sub, latest());
        }
    }

    public void resume(Connection conn, long reqId, long nodeId, String sidParam) {
        synchronized (lock) {
            Subscription sub = subsBySid.get(sidParam);
            if (sub == null || sub.nodeId != nodeId) {
                subscribe(conn, reqId, nodeId);
                return;
            }
            sub.s = conn.nextStreamId();
            sub.conn = conn;
            sendSubOk(conn, reqId, sub);
            if (sub.cursor < oldestSeq() - 1) {
                pushFull(sub, latest());
                return;
            }
            ArrayDeque<StreamEvent> pending = new ArrayDeque<>();
            for (StreamEvent event : log) {
                if (event.seq() > sub.cursor && event.nodeId() == nodeId) {
                    pending.add(event);
                }
            }
            if (pending.isEmpty()) {
                if (sub.cursor < latest()) {
                    pushFull(sub, latest());
                }
                return;
            }
            pushDelta(sub, pending);
        }
    }

    public void unsubscribe(Connection conn, long s) {
        synchronized (lock) {
            subsBySid.values().removeIf(sub -> sub.conn == conn && sub.s == s);
        }
    }

    /** 该节点当前是否有活跃订阅（admin.opSet 的 queued 标志用）。 */
    /** 挂起查询：App 的 me.query → 节点执行 → me.query.result 回填。 */
    public void registerPendingQuery(String queryId, java.util.function.Consumer<JsonNode> onResult) {
        pendingQueries.put(queryId, onResult);
    }

    public void deliverQueryResult(String queryId, JsonNode result, long nodeId) {
        java.util.function.Consumer<JsonNode> cb = pendingQueries.remove(queryId);
        if (cb != null) {
            cb.accept(result);
        }
    }

    private final Map<String, java.util.function.Consumer<JsonNode>> pendingQueries = new HashMap<>();

    public boolean hasSubscriber(long nodeId) {
        synchronized (lock) {
            for (Subscription sub : subsBySid.values()) {
                if (sub.nodeId == nodeId && sub.conn.channel().channel().isActive()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 追加一条下行操作：持久化 pending 并向该节点的活跃订阅推送增量。返回 opId。 */
    public String enqueue(long nodeId, String type, ObjectNode p) {
        String opId = Ids.sid() + Ids.sid();
        long now = System.currentTimeMillis() / 1000;
        ObjectNode payload = p == null ? JsonNodeFactory.instance.objectNode() : p;
        ctx.db.update("INSERT INTO node_ops(op_id, node_id, type, payload, status, created_at, expires_at) "
                        + "VALUES(?,?,?,?,'pending',?,?)",
                opId, nodeId, type, payload.toString(), now, now + 7 * 24 * 3600);
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        event.put("k", "op");
        event.put("opId", opId);
        event.put("type", type);
        event.set("p", payload);
        event.put("createdAt", now);
        synchronized (lock) {
            long seq = ++nextSeq;
            log.addLast(new StreamEvent(seq, nodeId, event));
            while (log.size() > LOG_CAPACITY) {
                log.removeFirst();
            }
            for (Subscription sub : subsBySid.values()) {
                if (sub.nodeId == nodeId && sub.conn.channel().channel().isActive()) {
                    deliver(sub, event, seq);
                }
            }
        }
        return opId;
    }

    /** 退避清扫：到期重试的 failed 操作换新 opId 重新入队；过期操作终结。由定时器与测试调用。 */
    public void sweep() {
        long now = System.currentTimeMillis() / 1000;
        for (Map<String, Object> row : ctx.db.query(
                "SELECT op_id, node_id, type, payload, attempts, expires_at FROM node_ops "
                        + "WHERE status = 'failed' AND next_retry_at <= ?", now)) {
            long nodeId = ((Number) row.get("node_id")).longValue();
            int attempts = ((Number) row.get("attempts")).intValue();
            try {
                ObjectNode p = (ObjectNode) ctx.mapper.readTree((String) row.get("payload"));
                ctx.db.update("UPDATE node_ops SET status = 'abandoned' WHERE op_id = ?",
                        (String) row.get("op_id"));
                enqueue(nodeId, (String) row.get("type"), p);
            } catch (Exception e) {
                ctx.db.update("UPDATE node_ops SET status = 'expired' WHERE op_id = ?",
                        (String) row.get("op_id"));
            }
        }
        for (Map<String, Object> row : ctx.db.query(
                "SELECT op_id FROM node_ops WHERE status IN ('pending','failed') AND expires_at <= ?", now)) {
            ctx.db.update("UPDATE node_ops SET status = 'expired' WHERE op_id = ?",
                    (String) row.get("op_id"));
        }
        // 失败重试的退避：fail 时按 attempts 置 next_retry_at
        for (Map<String, Object> row : ctx.db.query(
                "SELECT op_id, attempts FROM node_ops WHERE status = 'failed' AND next_retry_at IS NULL")) {
            int attempts = ((Number) row.get("attempts")).intValue();
            long backoff = Math.min(60L << Math.min(attempts, 6), 3600);
            ctx.db.update("UPDATE node_ops SET next_retry_at = ? WHERE op_id = ?",
                    now + backoff, (String) row.get("op_id"));
        }
    }

    private void beat() {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long latest = latest();
            for (Subscription sub : subsBySid.values()) {
                if (sub.conn.channel().channel().isActive()
                        && now - sub.lastPushMs > HEARTBEAT_SEC * 1000L) {
                    ObjectNode f = JsonNodeFactory.instance.objectNode();
                    f.put("t", "k");
                    f.put("boot", boot);
                    f.put("seq", latest);
                    sendPush(sub, f);
                } else if (!sub.conn.channel().channel().isActive()
                        && now - sub.lastPushMs > 300_000) {
                    subsBySid.remove(sub.sid);
                }
            }
        }
    }

    private void deliver(Subscription sub, ObjectNode event, long seq) {
        ObjectNode f = JsonNodeFactory.instance.objectNode();
        f.put("t", "d");
        f.put("boot", boot);
        f.put("seq", seq);
        f.put("ts", System.currentTimeMillis() / 1000);
        ArrayNode events = f.putArray("events");
        events.add(event);
        sendPush(sub, f);
        sub.cursor = seq;
    }

    private void pushFull(Subscription sub, long seq) {
        ObjectNode f = JsonNodeFactory.instance.objectNode();
        f.put("t", "f");
        ArrayNode ops = f.putArray("ops");
        for (Map<String, Object> row : ctx.db.query(
                "SELECT op_id, type, payload, created_at FROM node_ops "
                        + "WHERE node_id = ? AND status = 'pending' ORDER BY created_at",
                sub.nodeId)) {
            ObjectNode item = ops.addObject();
            item.put("opId", (String) row.get("op_id"));
            item.put("type", (String) row.get("type"));
            item.set("p", parse((String) row.get("payload")));
            item.put("createdAt", ((Number) row.get("created_at")).longValue());
        }
        f.put("boot", boot);
        f.put("seq", seq);
        f.put("ts", System.currentTimeMillis() / 1000);
        sendPush(sub, f);
        sub.cursor = seq;
    }

    private JsonNode parse(String payload) {
        try {
            return ctx.mapper.readTree(payload);
        } catch (Exception e) {
            return JsonNodeFactory.instance.objectNode();
        }
    }

    private void pushDelta(Subscription sub, ArrayDeque<StreamEvent> events) {
        ObjectNode f = JsonNodeFactory.instance.objectNode();
        f.put("t", "d");
        f.put("boot", boot);
        long last = events.isEmpty() ? sub.cursor : events.getLast().seq();
        f.put("seq", last);
        f.put("ts", System.currentTimeMillis() / 1000);
        ArrayNode arr = f.putArray("events");
        for (StreamEvent event : events) {
            arr.add(event.event());
            sub.cursor = event.seq();
        }
        sendPush(sub, f);
    }

    private void sendSubOk(Connection conn, long reqId, Subscription sub) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", reqId);
        node.put("s", sub.s);
        node.put("sid", sub.sid);
        conn.send(Frame.of(FrameType.SUB_OK, json(node)));
    }

    private void sendPush(Subscription sub, ObjectNode f) {
        ObjectNode push = JsonNodeFactory.instance.objectNode();
        push.put("s", sub.s);
        push.set("f", f);
        sub.conn.send(Frame.of(FrameType.PUSH, json(push)));
        sub.lastPushMs = System.currentTimeMillis();
    }

    private long oldestSeq() {
        return log.isEmpty() ? nextSeq + 1 : log.getFirst().seq();
    }

    private long latest() {
        return nextSeq;
    }

    private byte[] json(ObjectNode node) {
        try {
            return ctx.mapper.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
