package com.dsmnh.mobile.backend.api;

import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.Connection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 聊天域操作（契约 1.4.0）：公聊/私聊/历史/好友/屏蔽；chat 流事件经 ChatStream 分发。 */
public final class ChatOps {
    /** 公聊冷却（毫秒，企划书 05「每 UUID 公聊冷却可由服主配置」）。 */
    private final ConcurrentHashMap<String, Long> lastPublicSend = new ConcurrentHashMap<String, Long>();

    /** 30 天历史保留（企划书 05：公聊历史由后端保存 30 天）。 */
    private static final long HISTORY_RETENTION_SEC = 30L * 24 * 3600;

    /** 好友请求过期（7 天，企划书 05）。 */
    private static final long FRIEND_REQUEST_TTL_SEC = 7L * 24 * 3600;

    /** 定时清扫器：每小时清一次过期消息和请求。 */

    public static long historyRetentionSec() {
        return HISTORY_RETENTION_SEC;
    }

    public static long friendRequestTtlSec() {
        return FRIEND_REQUEST_TTL_SEC;
    }

    private final ServerContext ctx;

    public ChatOps(ServerContext ctx) {
        this.ctx = ctx;
        sweeper = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chat-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(this::sweepExpired, 1, 1, java.util.concurrent.TimeUnit.HOURS);
    }

    private final java.util.concurrent.ScheduledExecutorService sweeper;

    /** 每小时清扫：删除 30 天前的消息，将过期好友请求标记并推事件通知双方。 */
    private void sweepExpired() {
        try {
            long cutoff = System.currentTimeMillis() / 1000 - HISTORY_RETENTION_SEC;
            ctx.db.update("DELETE FROM chat_messages WHERE ts < ?", cutoff);
            // 好友请求过期：先标记再推事件（双方都收到 removed 通知）
            List<Map<String, Object>> expired = ctx.db.query(
                    "SELECT id, sender, recipient FROM chat_friend_requests "
                            + "WHERE status = 'pending' AND expires_at <= ?");
            for (Map<String, Object> row : expired) {
                long reqId = ((Number) row.get("id")).longValue();
                String sender = (String) row.get("sender");
                String recipient = (String) row.get("recipient");
                ctx.db.update("UPDATE chat_friend_requests SET status = 'expired' WHERE id = ?", reqId);
                ObjectNode event = JsonNodeFactory.instance.objectNode();
                event.put("k", "friend");
                event.put("op", "removed");
                event.put("uuid", sender);
                Set<String> recipients = new HashSet<>();
                recipients.add(sender);
                recipients.add(recipient);
                ctx.chatStream.publish(recipients, event);
            }
        } catch (Exception e) {
            // 清扫失败不影响服务，下个周期重试
        }
    }

    public Map<String, Dispatcher.OpHandler> ops() {
        return Map.ofEntries(
                Map.entry("chat.public.send", this::publicSend),
                Map.entry("chat.private.send", this::privateSend),
                Map.entry("chat.history", this::history),
                Map.entry("friend.request", this::friendRequest),
                Map.entry("friend.accept", this::friendAccept),
                Map.entry("friend.remove", this::friendRemove),
                Map.entry("block.set", this::blockSet),
                Map.entry("chat.relay", this::relay));
    }

    /** 公聊：入 30 天历史并广播给全部 chat 订阅者（公聊不受屏蔽影响，企划书 05）。 */
    public ObjectNode publicSend(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        String text = text(p, "text");
        cooldown(uuid);
        long ts = insert("public", uuid, null, text, "");
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        event.put("k", "msg");
        event.put("from", uuid);
        event.put("text", text);
        event.put("ts", ts);
        event.put("srv", "");
        ctx.chatStream.publishAll(event);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("ts", ts);
        return out;
    }

    /** 私聊：屏蔽检查 → 双方各入历史 → 定向推送（private:true）。 */
    public ObjectNode privateSend(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        String to = Dispatcher.text(p, "to");
        String text = text(p, "text");
        // 冷却仅限公聊（企划书 05：「每 UUID 公聊冷却」）
        if (isBlocked(to, uuid)) {
            throw new OpException(ErrorCodes.BLOCKED);
        }
        long ts = insert("private", uuid, to, text, "");
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        event.put("k", "msg");
        event.put("from", uuid);
        event.put("to", to);
        event.put("private", true);
        event.put("text", text);
        event.put("ts", ts);
        Set<String> recipients = new HashSet<String>();
        recipients.add(to);
        recipients.add(uuid);
        ctx.chatStream.publish(recipients, event);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("ts", ts);
        return out;
    }

    /** 30 天历史分页：public 全部 / private 按 peer 会话；before（消息 id）向前翻页。 */
    public ObjectNode history(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        String scope = Dispatcher.text(p, "scope");
        if (!"public".equals(scope) && !"private".equals(scope)) {
            throw new OpException(ErrorCodes.BAD_PARAMS, "scope");
        }
        long before = p.hasNonNull("before") && p.get("before").canConvertToLong()
                ? p.get("before").asLong() : Long.MAX_VALUE;
        int limit = p.hasNonNull("limit") && p.get("limit").canConvertToInt()
                ? Math.min(p.get("limit").asInt(), 100) : 50;
        ArrayNode messages = JsonNodeFactory.instance.objectNode().putArray("messages");
        List<Map<String, Object>> rows;
        if ("public".equals(scope)) {
            rows = ctx.db.query("SELECT id, sender, text, srv, ts FROM chat_messages "
                    + "WHERE scope = 'public' AND id < ? ORDER BY id DESC LIMIT ?",
                    before, limit + 1);
        } else {
            String peer = Dispatcher.text(p, "peer");
            rows = ctx.db.query("SELECT id, sender, recipient, text, srv, ts FROM chat_messages "
                    + "WHERE scope = 'private' AND id < ? AND ((sender = ? AND recipient = ?) "
                    + "OR (sender = ? AND recipient = ?)) ORDER BY id DESC LIMIT ?",
                    before, uuid, peer, peer, uuid, limit + 1);
        }
        boolean more = rows.size() > limit;
        int end = Math.min(rows.size(), limit);
        for (int i = 0; i < end; i++) {
            Map<String, Object> row = rows.get(i);
            ObjectNode item = messages.addObject();
            item.put("id", ((Number) row.get("id")).longValue());
            item.put("from", (String) row.get("sender"));
            if (row.get("recipient") != null) {
                item.put("to", (String) row.get("recipient"));
            }
            item.put("text", (String) row.get("text"));
            item.put("srv", row.get("srv") == null ? "" : (String) row.get("srv"));
            item.put("ts", ((Number) row.get("ts")).longValue());
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("messages", messages);
        out.put("more", more);
        return out;
    }

    /** 好友请求：屏蔽检查、已是好友幂等、未决请求幂等；7 天过期。 */
    public ObjectNode friendRequest(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        String to = Dispatcher.text(p, "to");
        if (to.equals(uuid)) {
            throw new OpException(ErrorCodes.BAD_PARAMS, "to");
        }
        if (isFriends(uuid, to)) {
            return requestResult(-1, 0);
        }
        if (isBlocked(to, uuid)) {
            throw new OpException(ErrorCodes.BLOCKED);
        }
        long now = System.currentTimeMillis() / 1000;
        Optional<Map<String, Object>> existing = ctx.db.queryOne(
                "SELECT id, expires_at FROM chat_friend_requests "
                        + "WHERE sender = ? AND recipient = ? AND status = 'pending' AND expires_at > ?",
                uuid, to, now);
        if (existing.isPresent()) {
            return requestResult(((Number) existing.get().get("id")).longValue(),
                    ((Number) existing.get().get("expires_at")).longValue());
        }
        long id = ctx.db.insert("INSERT INTO chat_friend_requests(sender, recipient, status, created_at, expires_at) "
                + "VALUES(?,?,'pending',?,?)", uuid, to, now, now + FRIEND_REQUEST_TTL_SEC);
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        event.put("k", "friend");
        event.put("op", "request");
        event.put("uuid", uuid);
        event.put("requestId", id);
        ctx.chatStream.publish(Set.of(to), event);
        return requestResult(id, now + 7 * 24 * 3600);
    }

    /** 接受好友请求：建立无序好友对并通知双方。 */
    public ObjectNode friendAccept(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        long requestId = Dispatcher.longValue(p, "requestId");
        Map<String, Object> row = ctx.db.queryOne(
                "SELECT sender, recipient, status, expires_at FROM chat_friend_requests WHERE id = ?", requestId)
                .orElseThrow(() -> new OpException(ErrorCodes.NO_INVITE));
        if (!uuid.equals(row.get("recipient")) || !"pending".equals(row.get("status"))) {
            throw new OpException(ErrorCodes.NO_INVITE);
        }
        if (((Number) row.get("expires_at")).longValue() <= System.currentTimeMillis() / 1000) {
            ctx.db.update("UPDATE chat_friend_requests SET status = 'expired' WHERE id = ?", requestId);
            throw new OpException(ErrorCodes.FRIEND_REQUEST_EXPIRED);
        }
        ctx.db.update("UPDATE chat_friend_requests SET status = 'accepted' WHERE id = ?", requestId);
        String a = min((String) row.get("sender"), uuid);
        String b = max((String) row.get("sender"), uuid);
        if (!isFriends(a, b)) {
            ctx.db.update("INSERT OR IGNORE INTO chat_friends(a, b, created_at) VALUES(?,?,?)",
                    a, b, System.currentTimeMillis() / 1000);
        }
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        event.put("k", "friend");
        event.put("op", "accepted");
        event.put("uuid", (String) row.get("sender"));
        ctx.chatStream.publish(Set.of((String) row.get("sender"), uuid), event);
        return JsonNodeFactory.instance.objectNode();
    }

    public ObjectNode friendRemove(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        String target = Dispatcher.text(p, "target");
        ctx.db.update("DELETE FROM chat_friends WHERE (a = ? AND b = ?) OR (a = ? AND b = ?)",
                min(uuid, target), max(uuid, target), max(uuid, target), min(uuid, target));
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        event.put("k", "friend");
        event.put("op", "removed");
        event.put("uuid", uuid);
        ctx.chatStream.publish(Set.of(target), event);
        return JsonNodeFactory.instance.objectNode();
    }

    public ObjectNode blockSet(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        String target = Dispatcher.text(p, "target");
        boolean blocked = p.hasNonNull("blocked") && p.get("blocked").asBoolean();
        if (blocked) {
            ctx.db.update("INSERT OR IGNORE INTO chat_blocks(blocker, blocked, created_at) VALUES(?,?,?)",
                    uuid, target, System.currentTimeMillis() / 1000);
        } else {
            ctx.db.update("DELETE FROM chat_blocks WHERE blocker = ? AND blocked = ?", uuid, target);
        }
        return JsonNodeFactory.instance.objectNode();
    }

    /** 节点公屏投递（HorizonChat 对接）：入历史并广播。 */
    public ObjectNode relay(Connection conn, JsonNode p) {
        String channel = Dispatcher.text(p, "channel");
        if (!"public".equals(channel)) {
            throw new OpException(ErrorCodes.BAD_PARAMS, "channel");
        }
        String from = Dispatcher.text(p, "from");
        String text = text(p, "text");
        String srv = p.hasNonNull("srv") ? p.get("srv").asText() : "";
        long ts = insert("public", from, null, text, srv);
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        event.put("k", "msg");
        event.put("from", from);
        event.put("text", text);
        event.put("ts", ts);
        event.put("srv", srv);
        ctx.chatStream.publishAll(event);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("accepted", true);
        return out;
    }

    // ---------- 辅助 ----------

    private String identity(Connection conn, JsonNode p) {
        return conn.serverPlane() == com.dsmnh.mobile.backend.net.Plane.APP
                ? Dispatcher.text(p, "uuid")
                : Dispatcher.text(p, "actor");
    }

    private String text(JsonNode p, String field) {
        String text = Dispatcher.text(p, field);
        if (text.length() > 500 || text.indexOf('§') >= 0 || text.chars().anyMatch(c -> c < 0x20)) {
            throw new OpException(ErrorCodes.BAD_TEXT);
        }
        return text;
    }

    private long insert(String scope, String sender, String recipient, String text, String srv) {
        long ts = System.currentTimeMillis() / 1000;
        ctx.db.update("INSERT INTO chat_messages(scope, sender, recipient, text, srv, ts) VALUES(?,?,?,?,?,?)",
                scope, sender, recipient, text, srv, ts);
        return ts;
    }

    private void cooldown(String uuid) {
        long now = System.currentTimeMillis();
        Long last = lastPublicSend.put(uuid, now);
        if (last != null && now - last < ctx.config.chatCooldownMs) {
            throw new OpException(ErrorCodes.CHAT_RATE_LIMITED,
                    String.valueOf(ctx.config.chatCooldownMs - (now - last)));
        }
    }

    private boolean isBlocked(String blocker, String blocked) {
        return !ctx.db.queryOne("SELECT blocker FROM chat_blocks WHERE blocker = ? AND blocked = ?",
                blocker, blocked).isEmpty();
    }

    private boolean isFriends(String a, String b) {
        return !ctx.db.queryOne("SELECT a FROM chat_friends WHERE (a = ? AND b = ?) OR (a = ? AND b = ?)",
                min(a, b), max(a, b), max(a, b), min(a, b)).isEmpty();
    }

    private String min(String a, String b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private String max(String a, String b) {
        return a.compareTo(b) <= 0 ? b : a;
    }

    private ObjectNode requestResult(long id, long expiresAt) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("requestId", id);
        out.put("expiresAt", expiresAt);
        return out;
    }
}
