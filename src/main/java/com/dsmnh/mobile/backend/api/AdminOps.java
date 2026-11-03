package com.dsmnh.mobile.backend.api;

import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.Connection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/** 管理域操作（登记表 §4.4，契约 1.1.0）：服主认领/转移、超管管理、玩家面板。 */
public final class AdminOps {
    private final ServerContext ctx;

    public AdminOps(ServerContext ctx) {
        this.ctx = ctx;
    }

    public ObjectNode ownerClaim(Connection conn, JsonNode p) {
        String code = Dispatcher.text(p, "code");
        Map<String, Object> codeRow = validCode(code, "claim");
        requireBoundUuid(conn);
        if (hasOwner()) {
            throw new OpException(ErrorCodes.OWNER_EXISTS);
        }
        long accountId = conn.accountId();
        consume(code, accountId);
        ctx.db.update("UPDATE accounts SET role = 'owner' WHERE id = ?", accountId);
        return roleResult("owner");
    }

    public ObjectNode ownerRecover(Connection conn, JsonNode p) {
        String code = Dispatcher.text(p, "code");
        Map<String, Object> codeRow = validCode(code, "recover");
        requireBoundUuid(conn);
        long accountId = conn.accountId();
        consume(code, accountId);
        ctx.db.update("UPDATE accounts SET role = 'owner' WHERE id = ?", accountId);
        Map<String, Object> previous = ctx.db.queryOne(
                "SELECT id FROM accounts WHERE role = 'owner' AND id != ?", accountId).orElse(null);
        if (previous != null) {
            ctx.db.update("UPDATE accounts SET role = 'user' WHERE id = ?",
                    ((Number) previous.get("id")).longValue());
        }
        return roleResult("owner");
    }

    public ObjectNode grantSuper(Connection conn, JsonNode p) {
        requireRole(conn, "owner");
        long target = accountOfUuid(Dispatcher.text(p, "uuid"));
        ctx.db.update("UPDATE accounts SET role = 'super' WHERE id = ?", target);
        return roleResult("super");
    }

    public ObjectNode revokeSuper(Connection conn, JsonNode p) {
        requireRole(conn, "owner");
        long target = accountOfUuid(Dispatcher.text(p, "uuid"));
        Map<String, Object> row = ctx.db.queryOne("SELECT role FROM accounts WHERE id = ?", target)
                .orElseThrow(() -> new OpException(ErrorCodes.BAD_PARAMS, "uuid"));
        if ("owner".equals(row.get("role"))) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
        ctx.db.update("UPDATE accounts SET role = 'user' WHERE id = ?", target);
        return roleResult("user");
    }

    public ObjectNode uuids(Connection conn, JsonNode p) {
        requireRole(conn, "super");
        ArrayNode bindings = JsonNodeFactory.instance.objectNode().putArray("bindings");
        for (Map<String, Object> row : ctx.db.query(
                "SELECT uuid, account_id, bound_at FROM uuid_bindings ORDER BY bound_at")) {
            ObjectNode item = bindings.addObject();
            item.put("uuid", (String) row.get("uuid"));
            item.put("accountId", ((Number) row.get("account_id")).longValue());
            item.put("boundAt", ((Number) row.get("bound_at")).longValue());
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("bindings", bindings);
        return out;
    }

    public ObjectNode unbindUUID(Connection conn, JsonNode p) {
        requireRole(conn, "super");
        String uuid = Dispatcher.text(p, "uuid");
        ctx.db.update("DELETE FROM uuid_bindings WHERE uuid = ?", uuid);
        return JsonNodeFactory.instance.objectNode();
    }

    // ---------- 辅助 ----------

    private Map<String, Object> validCode(String code, String purpose) {
        Map<String, Object> row = ctx.db.queryOne(
                "SELECT expires_at, used_at FROM owner_codes WHERE code = ? AND purpose = ?", code, purpose)
                .orElseThrow(() -> new OpException(ErrorCodes.CLAIM_CODE_INVALID));
        if (row.get("used_at") != null) {
            throw new OpException(ErrorCodes.CLAIM_CODE_USED);
        }
        if (((Number) row.get("expires_at")).longValue() <= System.currentTimeMillis() / 1000) {
            throw new OpException(ErrorCodes.CLAIM_CODE_INVALID);
        }
        return row;
    }

    private void consume(String code, long accountId) {
        ctx.db.update("UPDATE owner_codes SET used_at = ?, used_by = ? WHERE code = ?",
                System.currentTimeMillis() / 1000, accountId, code);
    }

    private void requireBoundUuid(Connection conn) {
        if (!ctx.db.queryOne("SELECT uuid FROM uuid_bindings WHERE account_id = ? LIMIT 1",
                conn.accountId()).isPresent()) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
    }

    private boolean hasOwner() {
        return !ctx.db.queryOne("SELECT id FROM accounts WHERE role = 'owner'").isEmpty();
    }

    /** 角色校验：owner = 仅服主；super = 服主或超管。 */
    private void requireRole(Connection conn, String required) {
        String role = ctx.auth.roleOf(conn.accountId());
        boolean allowed = "owner".equals(required)
                ? "owner".equals(role)
                : "owner".equals(role) || "super".equals(role);
        if (!allowed) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
    }

    private long accountOfUuid(String uuid) {
        return ctx.db.queryOne("SELECT account_id FROM uuid_bindings WHERE uuid = ?", uuid)
                .map(row -> ((Number) row.get("account_id")).longValue())
                .orElseThrow(() -> new OpException(ErrorCodes.BAD_PARAMS, "uuid"));
    }

    private ObjectNode roleResult(String role) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("role", role);
        return out;
    }
}
