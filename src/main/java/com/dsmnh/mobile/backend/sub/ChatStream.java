package com.dsmnh.mobile.backend.sub;

import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.Connection;
import com.dsmnh.mobile.backend.net.Frame;
import com.dsmnh.mobile.backend.net.FrameType;
import com.dsmnh.mobile.backend.util.Ids;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * chat 订阅流（契约 1.4.0）：按 UUID 推送公聊/私聊消息与好友事件。
 * 续传机制与 TeamStream 同源；消息历史另有 REQ 分页（ChatOps），流内不补历史。
 */
public final class ChatStream {
    private final ServerContext ctx;
    private final long boot = System.currentTimeMillis() / 1000;
    private final Object lock = new Object();
    private final Map<String, Subscription> subsBySid = new HashMap<>();

    static final class Subscription {
        final String sid;
        final String uuid;
        long s;
        Connection conn;

        Subscription(String sid, String uuid, long s, Connection conn) {
            this.sid = sid;
            this.uuid = uuid;
            this.s = s;
            this.conn = conn;
        }
    }

    public ChatStream(ServerContext ctx) {
        this.ctx = ctx;
    }

    public void subscribe(Connection conn, long reqId, String uuid) {
        synchronized (lock) {
            Subscription sub = new Subscription(Ids.sid(), uuid, conn.nextStreamId(), conn);
            subsBySid.put(sub.sid, sub);
            ObjectNode ok = JsonNodeFactory.instance.objectNode();
            ok.put("id", reqId);
            ok.put("s", sub.s);
            ok.put("sid", sub.sid);
            conn.send(Frame.of(FrameType.SUB_OK, json(ok)));
        }
    }

    /** 带 sid 重订：识别则重绑返回同 sid，否则新订阅（chat 流无历史补发，历史走 chat.history）。 */
    public void resume(Connection conn, long reqId, String uuid, String sidParam) {
        synchronized (lock) {
            Subscription sub = subsBySid.get(sidParam);
            if (sub != null && sub.uuid.equals(uuid)) {
                sub.s = conn.nextStreamId();
                sub.conn = conn;
                ObjectNode ok = JsonNodeFactory.instance.objectNode();
                ok.put("id", reqId);
                ok.put("s", sub.s);
                ok.put("sid", sub.sid);
                conn.send(Frame.of(FrameType.SUB_OK, json(ok)));
                return;
            }
            subscribe(conn, reqId, uuid);
        }
    }

    public void unsubscribe(Connection conn, long s) {
        synchronized (lock) {
            subsBySid.values().removeIf(sub -> sub.conn == conn && sub.s == s);
        }
    }

    /** 向收件人集合的全部订阅推送事件（k 已由调用方填好）。 */
    public void publish(Set<String> recipients, ObjectNode event) {
        synchronized (lock) {
            for (Subscription sub : subsBySid.values()) {
                if (recipients.contains(sub.uuid) && sub.conn.channel().channel().isActive()) {
                    sendDelta(sub, event);
                }
            }
        }
    }

    /** 广播：推给全部 chat 订阅者（公聊语义）。 */
    public void publishAll(ObjectNode event) {
        synchronized (lock) {
            for (Subscription sub : subsBySid.values()) {
                if (sub.conn.channel().channel().isActive()) {
                    sendDelta(sub, event);
                }
            }
        }
    }

    private void sendDelta(Subscription sub, ObjectNode event) {
                {
                    ObjectNode f = JsonNodeFactory.instance.objectNode();
                    f.put("t", "d");
                    f.put("boot", boot);
                    f.put("seq", 0);
                    f.put("ts", System.currentTimeMillis() / 1000);
                    f.put("uuid", sub.uuid);
                    f.putArray("events").add(event);
                    ObjectNode push = JsonNodeFactory.instance.objectNode();
                    push.put("s", sub.s);
                    push.set("f", f);
                    sub.conn.send(Frame.of(FrameType.PUSH, json(push)));
                }
    }

    private byte[] json(ObjectNode node) {
        try {
            return ctx.mapper.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
