package com.dsmnh.mobile.backend.api;

import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.Connection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** App 平面账号域操作（登记表 §4.1）。 */
public final class AccountOps {
    private final ServerContext ctx;

    public AccountOps(ServerContext ctx) {
        this.ctx = ctx;
    }

    public ObjectNode register(Connection conn, JsonNode p) {
        String name = validName(Dispatcher.text(p, "name"));
        String pass = validPassword(Dispatcher.text(p, "pass"));
        long now = System.currentTimeMillis() / 1000;
        Optional<Map<String, Object>> existing = ctx.db.queryOne(
                "SELECT id FROM accounts WHERE name = ?", name);
        if (existing.isPresent()) {
            throw new OpException(ErrorCodes.NAME_TAKEN);
        }
        long accountId = ctx.db.insert(
                "INSERT INTO accounts(name, pass_hash, created_at) VALUES(?,?,?)",
                name, com.dsmnh.mobile.backend.util.Passwords.hash(pass), now);
        return sessionResult(ctx.auth.issueSession(accountId, appOf(conn)), accountId);
    }

    public ObjectNode login(Connection conn, JsonNode p) {
        String name = Dispatcher.text(p, "name");
        String pass = Dispatcher.text(p, "pass");
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> account = ctx.db.queryOne(
                "SELECT id, pass_hash, failed_count, locked_until FROM accounts WHERE name = ?", name)
                .orElseThrow(() -> new OpException(ErrorCodes.BAD_CREDENTIALS));
        long lockedUntil = ((Number) account.get("locked_until")).longValue();
        if (lockedUntil > now) {
            throw new OpException(ErrorCodes.LOGIN_LOCKED, String.valueOf(lockedUntil - now));
        }
        long accountId = ((Number) account.get("id")).longValue();
        if (!com.dsmnh.mobile.backend.util.Passwords.verify(pass, (String) account.get("pass_hash"))) {
            int failed = ((Number) account.get("failed_count")).intValue() + 1;
            long lockUntil = 0;
            if (failed >= ctx.config.loginLockThreshold) {
                long duration = ctx.config.loginLockBaseSec << Math.min(failed - ctx.config.loginLockThreshold, 20);
                lockUntil = now + Math.min(duration, ctx.config.loginLockMaxSec);
            }
            ctx.db.update("UPDATE accounts SET failed_count = ?, locked_until = ? WHERE id = ?",
                    failed, lockUntil, accountId);
            throw new OpException(ErrorCodes.BAD_CREDENTIALS);
        }
        ctx.db.update("UPDATE accounts SET failed_count = 0, locked_until = 0 WHERE id = ?", accountId);
        return sessionResult(ctx.auth.issueSession(accountId, appOf(conn)), accountId);
    }

    public ObjectNode resetPassword(Connection conn, JsonNode p) {
        String code = Dispatcher.text(p, "recoverCode");
        String newPassword = validPassword(Dispatcher.text(p, "newPassword"));
        Map<String, Object> row = validCode(code, "recover");
        String uuid = (String) row.get("uuid");
        Map<String, Object> binding = ctx.db.queryOne(
                "SELECT account_id FROM uuid_bindings WHERE uuid = ?", uuid)
                .orElseThrow(() -> new OpException(ErrorCodes.RECOVER_CODE_INVALID));
        long accountId = ((Number) binding.get("account_id")).longValue();
        ctx.db.update("UPDATE accounts SET pass_hash = ? WHERE id = ?",
                com.dsmnh.mobile.backend.util.Passwords.hash(newPassword), accountId);
        ctx.db.update("UPDATE codes SET used_at = ? WHERE code = ?",
                System.currentTimeMillis() / 1000, code);
        ctx.auth.revokeAllSessions(accountId);
        return JsonNodeFactory.instance.objectNode();
    }

    public ObjectNode bindUUID(Connection conn, JsonNode p) {
        String code = Dispatcher.text(p, "code");
        Map<String, Object> row = validCode(code, "bind");
        String uuid = (String) row.get("uuid");
        long now = System.currentTimeMillis() / 1000;
        Optional<Map<String, Object>> binding = ctx.db.queryOne(
                "SELECT account_id FROM uuid_bindings WHERE uuid = ?", uuid);
        if (binding.isPresent()) {
            long boundAccount = ((Number) binding.get().get("account_id")).longValue();
            if (boundAccount != conn.accountId()) {
                throw new OpException(ErrorCodes.UUID_BOUND);
            }
        } else {
            ctx.db.update("INSERT INTO uuid_bindings(uuid, account_id, bound_at) VALUES(?,?,?)",
                    uuid, conn.accountId(), now);
        }
        ctx.db.update("UPDATE codes SET used_at = ? WHERE code = ?", now, code);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("uuid", uuid);
        return out;
    }

    public ObjectNode uuids(Connection conn, JsonNode p) {
        ArrayNode uuids = JsonNodeFactory.instance.objectNode().putArray("uuids");
        for (Map<String, Object> row : ctx.db.query(
                "SELECT uuid FROM uuid_bindings WHERE account_id = ? ORDER BY bound_at", conn.accountId())) {
            ObjectNode item = uuids.addObject();
            item.put("uuid", (String) row.get("uuid"));
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("uuids", uuids);
        return out;
    }

    public ObjectNode sessions(Connection conn, JsonNode p) {
        ArrayNode sessions = JsonNodeFactory.instance.objectNode().putArray("sessions");
        for (Map<String, Object> row : ctx.db.query(
                "SELECT id, app, last_seen FROM sessions WHERE account_id = ? AND revoked_at IS NULL "
                        + "ORDER BY created_at", conn.accountId())) {
            String sessionId = String.valueOf(row.get("id"));
            ObjectNode item = sessions.addObject();
            item.put("id", sessionId);
            item.put("app", (String) row.get("app"));
            item.put("lastSeen", ((Number) row.get("last_seen")).longValue());
            item.put("current", sessionId.equals(conn.sessionId()));
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("sessions", sessions);
        return out;
    }

    public ObjectNode revokeSession(Connection conn, JsonNode p) {
        String sessionId = Dispatcher.text(p, "id");
        Map<String, Object> row = ctx.db.queryOne(
                "SELECT account_id, revoked_at FROM sessions WHERE id = ?", sessionId)
                .orElseThrow(() -> new OpException(ErrorCodes.FORBIDDEN));
        long owner = ((Number) row.get("account_id")).longValue();
        if (owner != conn.accountId() || row.get("revoked_at") != null) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
        boolean callerAffected = ctx.auth.revokeSession(sessionId, conn);
        if (callerAffected) {
            conn.runAfterResponse(conn::close);
        }
        return JsonNodeFactory.instance.objectNode();
    }

    // ---------- 辅助 ----------

    private ObjectNode sessionResult(com.dsmnh.mobile.backend.auth.TokenRecord record, long accountId) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("token", record.token());
        out.put("kid", record.kid());
        ArrayNode uuids = out.putArray("uuids");
        for (Map<String, Object> row : ctx.db.query(
                "SELECT uuid FROM uuid_bindings WHERE account_id = ? ORDER BY bound_at", accountId)) {
            ObjectNode item = uuids.addObject();
            item.put("uuid", (String) row.get("uuid"));
        }
        return out;
    }

    /** 校验一次性码：不存在或已过期 → INVALID；已使用 → USED。 */
    private Map<String, Object> validCode(String code, String type) {
        Map<String, Object> row = ctx.db.queryOne(
                "SELECT uuid, expires_at, used_at FROM codes WHERE code = ? AND type = ?", code, type)
                .orElseThrow(() -> new OpException(invalidCodeOf(type)));
        if (row.get("used_at") != null) {
            throw new OpException("recover".equals(type)
                    ? ErrorCodes.RECOVER_CODE_USED : ErrorCodes.BIND_CODE_USED);
        }
        if (((Number) row.get("expires_at")).longValue() <= System.currentTimeMillis() / 1000) {
            throw new OpException(invalidCodeOf(type));
        }
        return row;
    }

    private String invalidCodeOf(String type) {
        return "recover".equals(type) ? ErrorCodes.RECOVER_CODE_INVALID : ErrorCodes.BIND_CODE_INVALID;
    }

    private String validName(String name) {
        if (name.length() < ctx.config.minNameLength || name.length() > ctx.config.maxNameLength
                || name.indexOf('§') >= 0
                || name.chars().anyMatch(c -> c < 0x20 || Character.isWhitespace(c))) {
            throw new OpException(ErrorCodes.BAD_NAME);
        }
        return name;
    }

    private String validPassword(String pass) {
        if (pass.length() < ctx.config.minPasswordLength || pass.chars().anyMatch(c -> c < 0x20)) {
            throw new OpException(ErrorCodes.BAD_TEXT);
        }
        return pass;
    }

    private String appOf(Connection conn) {
        return conn.app().isEmpty() ? "unknown" : conn.app();
    }
}
