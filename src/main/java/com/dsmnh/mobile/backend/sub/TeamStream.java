package com.dsmnh.mobile.backend.sub;

import com.dsmnh.mobile.backend.api.TeamOps;
import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.Connection;
import com.dsmnh.mobile.backend.net.Frame;
import com.dsmnh.mobile.backend.net.FrameType;
import com.dsmnh.mobile.backend.util.Ids;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * team 订阅流（登记表 §5）：全量/增量/心跳，sid + 服务端游标续传（契约 §7.2）。
 * 事件日志驻留内存，重启即清空——重启后 sid 不被识别，客户端按全量重建。
 */
public final class TeamStream {
    private static final int LOG_CAPACITY = 4096;
    private static final int HEARTBEAT_SEC = 25;

    public record StreamEvent(long seq, Set<String> recipients, ObjectNode event) {}

    static final class Subscription {
        final String sid;
        final String uuid;
        long cursor;          // 已投递的最大事件 seq
        long lastPushMs;
        long s;               // 连接内流序号（UNSUB/PUSH 用）
        Connection conn;

        Subscription(String sid, String uuid, long s, Connection conn) {
            this.sid = sid;
            this.uuid = uuid;
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
    private TeamOps ops;
    private ScheduledExecutorService heartbeat;

    public TeamStream(ServerContext ctx) {
        this.ctx = ctx;
    }

    /** 装配完成后由入口调用：提供全量帧构建方并启动心跳。 */
    public void bind(TeamOps ops) {
        this.ops = ops;
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "team-heartbeat");
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

    public void subscribe(Connection conn, long reqId, String uuid) {
        synchronized (lock) {
            Subscription sub = new Subscription(Ids.sid(), uuid, conn.nextStreamId(), conn);
            subsBySid.put(sub.sid, sub);
            sendSubOk(conn, reqId, sub);
            pushFull(sub, latest());
        }
    }

    /** 带 sid 重订：识别则续传，否则回全量重建（契约 §7.2）。 */
    public void resume(Connection conn, long reqId, String uuid, String sidParam) {
        synchronized (lock) {
            Subscription sub = subsBySid.get(sidParam);
            if (sub == null || !sub.uuid.equals(uuid)) {
                subscribe(conn, reqId, uuid);
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
                if (event.seq > sub.cursor && event.recipients().contains(uuid)) {
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

    /** 追加事件并投递给收件人的全部订阅。 */
    public void publish(Set<String> recipients, String k, ObjectNode extra) {
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        long seq;
        synchronized (lock) {
            seq = ++nextSeq;
            event.put("seq", seq);
            event.put("k", k);
            event.setAll(extra);
            log.addLast(new StreamEvent(seq, Set.copyOf(recipients), event));
            while (log.size() > LOG_CAPACITY) {
                log.removeFirst();
            }
            for (Subscription sub : subsBySid.values()) {
                if (recipients.contains(sub.uuid) && sub.conn.channel().channel().isActive()) {
                    deliver(sub, event, seq);
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
        f.put("uuid", sub.uuid);
        ArrayNode events = f.putArray("events");
        events.add(event);
        sendPush(sub, f);
        sub.cursor = seq;
    }

    private void pushFull(Subscription sub, long seq) {
        ObjectNode f = ops.fullFrame(sub.uuid);
        f.put("boot", boot);
        f.put("seq", seq);
        sendPush(sub, f);
        sub.cursor = seq;
    }

    private void pushDelta(Subscription sub, ArrayDeque<StreamEvent> events) {
        ObjectNode f = JsonNodeFactory.instance.objectNode();
        f.put("t", "d");
        f.put("boot", boot);
        long last = events.isEmpty() ? sub.cursor : events.getLast().seq();
        f.put("seq", last);
        f.put("ts", System.currentTimeMillis() / 1000);
        f.put("uuid", sub.uuid);
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
