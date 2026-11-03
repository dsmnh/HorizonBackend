package com.dsmnh.mobile.backend.auth;

import com.dsmnh.mobile.backend.config.Config;
import com.dsmnh.mobile.backend.net.Connection;
import com.dsmnh.mobile.backend.net.Plane;
import com.dsmnh.mobile.backend.store.Database;
import com.dsmnh.mobile.backend.util.Ids;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Predicate;

/**
 * 凭证与握手校验：token 不上网线，kid 定位唯一凭证，HMAC-SHA256 验签（契约 §4）。
 * 会话与节点凭证同表管理、按 kid 直查数据库（两表 kid 均唯一索引），撤销后仍可区分失效原因。
 */
public final class AuthService {
    public static final long CLOCK_SKEW_SEC = 600;

    /** 验签失败原因：code 映射 AUTH_FAIL.code。 */
    public static final class AuthError extends RuntimeException {
        public final String code;

        public AuthError(String code) {
            super(code);
            this.code = code;
        }
    }

    private final Config config;
    private final Database db;
    private final Map<String, CopyOnWriteArraySet<Connection>> liveByToken = new ConcurrentHashMap<>();

    public AuthService(Config config, Database db) {
        this.config = config;
        this.db = db;
    }

    /** 签发 App 会话并持久化。 */
    public TokenRecord issueSession(long accountId, String app) {
        String token = Ids.token();
        String kid = token.substring(0, 8);
        String sessionId = java.util.UUID.randomUUID().toString();
        long now = System.currentTimeMillis() / 1000;
        db.update("INSERT INTO sessions(id, account_id, token, kid, app, created_at, last_seen) "
                + "VALUES(?,?,?,?,?,?,?)", sessionId, accountId, token, kid, app, now, now);
        return TokenRecord.session(token, kid, accountId, sessionId);
    }

    /** 为新建或恢复的节点签发节点凭证并持久化。 */
    public TokenRecord issueNode(long nodeId) {
        String token = Ids.token();
        String kid = token.substring(0, 8);
        db.update("UPDATE nodes SET token = ?, kid = ? WHERE id = ?", token, kid, nodeId);
        return TokenRecord.node(token, kid, nodeId);
    }

    public String issueBindCode(String uuid, int ttlSec) {
        return insertCode("bind", uuid, ttlSec);
    }

    public String issueRecoverCode(String uuid, int ttlSec) {
        return insertCode("recover", uuid, ttlSec);
    }

    private String insertCode(String type, String uuid, int ttlSec) {
        long now = System.currentTimeMillis() / 1000;
        String code = Ids.code();
        db.update("INSERT INTO codes(code, type, uuid, created_at, expires_at) VALUES(?,?,?,?,?)",
                code, type, uuid, now, now + ttlSec);
        return code;
    }

    public String issueNodeCode(String purpose, Long restoreNodeId, int ttlSec) {
        long now = System.currentTimeMillis() / 1000;
        String code = Ids.code();
        db.update("INSERT INTO node_codes(code, purpose, restore_node_id, created_at, expires_at) "
                + "VALUES(?,?,?,?,?)", code, purpose, restoreNodeId, now, now + ttlSec);
        return code;
    }

    /**
     * 验签并返回凭证记录；失败时抛 AuthError，code 映射 AUTH_FAIL.code。
     * 会话凭证只能在 App 平面使用，节点凭证只能在节点平面使用，跨平面按 AUTH_FAILED 处理。
     */
    public TokenRecord verify(Connection conn, String kid, long ts, String sig) {
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ts) > CLOCK_SKEW_SEC) {
            throw new AuthError("CLOCK");
        }
        String canonical = "HORIZON\n" + ts + "\n" + conn.nonce();

        Map<String, Object> session = kid == null ? null : db.queryOne(
                "SELECT id, account_id, token, revoked_at FROM sessions WHERE kid = ?", kid).orElse(null);
        if (session != null) {
            verifySignature((String) session.get("token"), canonical, sig);
            if (session.get("revoked_at") != null) {
                throw new AuthError("SESSION_EXPIRED");
            }
            requirePlane(TokenRecord.Kind.SESSION, conn.serverPlane());
            return TokenRecord.session((String) session.get("token"), kid,
                    ((Number) session.get("account_id")).longValue(), String.valueOf(session.get("id")));
        }

        Map<String, Object> node = kid == null ? null : db.queryOne(
                "SELECT id, token, status FROM nodes WHERE kid = ?", kid).orElse(null);
        if (node != null) {
            verifySignature((String) node.get("token"), canonical, sig);
            if (!"active".equals(node.get("status"))) {
                throw new AuthError("NODE_REVOKED");
            }
            requirePlane(TokenRecord.Kind.NODE, conn.serverPlane());
            return TokenRecord.node((String) node.get("token"), kid,
                    ((Number) node.get("id")).longValue());
        }

        throw new AuthError("AUTH_FAILED");
    }

    private void verifySignature(String token, String canonical, String sig) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] actual;
            try {
                actual = Base64.getUrlDecoder().decode(sig);
            } catch (IllegalArgumentException e) {
                throw new AuthError("AUTH_FAILED");
            }
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new AuthError("AUTH_FAILED");
            }
        } catch (AuthError e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    private void requirePlane(TokenRecord.Kind kind, Plane serverPlane) {
        boolean ok = kind == TokenRecord.Kind.SESSION
                ? serverPlane == Plane.APP
                : serverPlane == Plane.NODE;
        if (!ok) {
            throw new AuthError("AUTH_FAILED");
        }
    }

    public void registerLive(Connection conn) {
        TokenRecord record = conn.token();
        liveByToken.computeIfAbsent(record.token(), k -> new CopyOnWriteArraySet<>()).add(conn);
    }

    public void unregisterLive(Connection conn) {
        TokenRecord record = conn.token();
        if (record != null) {
            CopyOnWriteArraySet<Connection> set = liveByToken.get(record.token());
            if (set != null) {
                set.remove(conn);
            }
        }
    }

    /** 注销会话：凭证失效、除调用方连接外的在线连接立即断开；返回调用方连接是否也属于该会话。 */
    public boolean revokeSession(String sessionId, Connection caller) {
        db.update("UPDATE sessions SET revoked_at = ? WHERE id = ?",
                System.currentTimeMillis() / 1000, sessionId);
        return disconnectIf(tokenRecord -> tokenRecord.kind() == TokenRecord.Kind.SESSION
                && sessionId.equals(tokenRecord.sessionId()), caller);
    }

    /** 注销账号全部会话（重设密码），含调用方所在会话。 */
    public void revokeAllSessions(long accountId) {
        db.update("UPDATE sessions SET revoked_at = ? WHERE account_id = ? AND revoked_at IS NULL",
                System.currentTimeMillis() / 1000, accountId);
        disconnectIf(tokenRecord -> tokenRecord.kind() == TokenRecord.Kind.SESSION
                && tokenRecord.accountId() == accountId, null);
    }

    /** 撤销节点：凭证失效并断开该节点的在线连接。 */
    public void revokeNode(long nodeId) {
        db.update("UPDATE nodes SET status = 'revoked', revoked_at = ? WHERE id = ?",
                System.currentTimeMillis() / 1000, nodeId);
        disconnectIf(tokenRecord -> tokenRecord.kind() == TokenRecord.Kind.NODE
                && tokenRecord.nodeId() == nodeId, null);
    }

    private boolean disconnectIf(Predicate<TokenRecord> match, Connection except) {
        boolean[] callerAffected = {false};
        for (CopyOnWriteArraySet<Connection> set : liveByToken.values()) {
            for (Connection conn : set) {
                if (conn.token() != null && match.test(conn.token())) {
                    if (conn == except) {
                        callerAffected[0] = true;
                    } else {
                        conn.close();
                    }
                    set.remove(conn);
                }
            }
        }
        return callerAffected[0];
    }

    public boolean isUuidBoundToAccount(String uuid, long accountId) {
        return db.queryOne("SELECT account_id FROM uuid_bindings WHERE uuid = ?", uuid)
                .map(row -> ((Number) row.get("account_id")).longValue() == accountId)
                .orElse(false);
    }
}
