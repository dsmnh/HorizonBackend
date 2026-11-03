package com.dsmnh.mobile.backend.api;

import com.dsmnh.mobile.backend.auth.TokenRecord;
import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.Connection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Optional;

/** 节点域操作（登记表 §4.3）：注册码兑换、游戏内出码。 */
public final class NodeOps {
    private final ServerContext ctx;

    public NodeOps(ServerContext ctx) {
        this.ctx = ctx;
    }

    /** 预鉴权：注册码兑换节点凭证（契约 §8）。 */
    public ObjectNode register(Connection conn, JsonNode p) {
        String code = Dispatcher.text(p, "code");
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> codeRow = ctx.db.queryOne(
                "SELECT purpose, restore_node_id, expires_at, used_at FROM node_codes WHERE code = ?", code)
                .orElseThrow(() -> new OpException(ErrorCodes.NODE_CODE_INVALID));
        if (codeRow.get("used_at") != null) {
            throw new OpException(ErrorCodes.NODE_CODE_USED);
        }
        if (((Number) codeRow.get("expires_at")).longValue() <= now) {
            throw new OpException(ErrorCodes.NODE_CODE_INVALID);
        }
        String purpose = (String) codeRow.get("purpose");
        long nodeId;
        String nodeName = p.hasNonNull("nodeName") ? p.get("nodeName").asText() : null;
        if ("new".equals(purpose)) {
            nodeId = ctx.db.insert(
                    "INSERT INTO nodes(name, token, kid, status, created_at, last_seen) "
                            + "VALUES(?, '', '', 'active', ?, ?)", nodeName, now, now);
        } else {
            long restoreId = ((Number) codeRow.get("restore_node_id")).longValue();
            Map<String, Object> node = ctx.db.queryOne(
                    "SELECT id, status FROM nodes WHERE id = ?", restoreId)
                    .orElseThrow(() -> new OpException(ErrorCodes.NODE_CODE_INVALID));
            if (!"revoked".equals(node.get("status"))) {
                throw new OpException(ErrorCodes.NODE_CODE_INVALID);
            }
            if (nodeName != null) {
                ctx.db.update("UPDATE nodes SET name = ?, status = 'active', revoked_at = NULL, "
                        + "last_seen = ? WHERE id = ?", nodeName, now, restoreId);
            } else {
                ctx.db.update("UPDATE nodes SET status = 'active', revoked_at = NULL, last_seen = ? "
                        + "WHERE id = ?", now, restoreId);
            }
            nodeId = restoreId;
        }
        TokenRecord record = ctx.auth.issueNode(nodeId);
        ctx.db.update("UPDATE node_codes SET used_at = ?, used_by = ? WHERE code = ?", now, nodeId, code);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("nodeId", nodeId);
        out.put("token", record.token());
        out.put("kid", record.kid());
        return out;
    }

    /** 游戏内出绑定码：签发即注销该 UUID 未过期的旧码（契约 §8）。 */
    public ObjectNode bindCodeCreate(Connection conn, JsonNode p) {
        String actor = Dispatcher.text(p, "actor");
        return issueGameCode(conn, actor, "bind");
    }

    /** 游戏内出恢复码：要求 UUID 已绑定某 App 账号（登记表 §4.3）。 */
    public ObjectNode recoverCodeCreate(Connection conn, JsonNode p) {
        String actor = Dispatcher.text(p, "actor");
        Optional<Map<String, Object>> binding = ctx.db.queryOne(
                "SELECT account_id FROM uuid_bindings WHERE uuid = ?", actor);
        if (binding.isEmpty()) {
            throw new OpException(ErrorCodes.UUID_NOT_BOUND);
        }
        return issueGameCode(conn, actor, "recover");
    }

    private ObjectNode issueGameCode(Connection conn, String uuid, String type) {
        long now = System.currentTimeMillis() / 1000;
        touchNode(conn.nodeId(), now);
        // 新码签发即注销同 UUID 未过期的旧码
        ctx.db.update("UPDATE codes SET expires_at = ? WHERE uuid = ? AND type = ? "
                + "AND used_at IS NULL AND expires_at > ?", now, uuid, type, now);
        String code = "bind".equals(type)
                ? ctx.auth.issueBindCode(uuid, ctx.config.bindCodeTtlSec)
                : ctx.auth.issueRecoverCode(uuid, ctx.config.bindCodeTtlSec);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("code", code);
        out.put("expiresAt", now + ctx.config.bindCodeTtlSec);
        return out;
    }

    private void touchNode(long nodeId, long now) {
        ctx.db.update("UPDATE nodes SET last_seen = ? WHERE id = ?", now, nodeId);
    }
}
