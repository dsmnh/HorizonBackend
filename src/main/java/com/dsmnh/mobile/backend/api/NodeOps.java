package com.dsmnh.mobile.backend.api;

import com.dsmnh.mobile.backend.auth.TokenRecord;
import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.Connection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

    /** 下行操作成功终结：仅目标节点连接可回报（契约 1.2.0 §8.1）。 */
    public ObjectNode opAck(Connection conn, JsonNode p) {
        String opId = Dispatcher.text(p, "opId");
        requireTargetOp(conn, opId);
        ctx.db.update("UPDATE node_ops SET status = 'done' WHERE op_id = ?", opId);
        // import 阶段下行实例被目标节点确认后，批次流转为 applied。
        ctx.db.update("UPDATE bq_snapshots SET status = 'applied' "
                + "WHERE import_op_id = ? AND status = 'exported'", opId);
        return JsonNodeFactory.instance.objectNode();
    }

    /** 下行操作失败：终结当前实例并按退避安排重试（新实例换新 opId，由清扫器下发）。 */
    public ObjectNode opFail(Connection conn, JsonNode p) {
        String opId = Dispatcher.text(p, "opId");
        String reason = p.hasNonNull("reason") ? p.get("reason").asText() : "unspecified";
        requireTargetOp(conn, opId);
        int nextAttempts = ctx.db.queryOne("SELECT attempts FROM node_ops WHERE op_id = ?", opId)
                .map(row -> ((Number) row.get("attempts")).intValue() + 1).orElse(1);
        long now = System.currentTimeMillis() / 1000;
        long backoff = Math.min(60L << Math.min(nextAttempts, 6), 3600);
        ctx.db.update("UPDATE node_ops SET status = 'failed', fail_reason = ?, attempts = ?, next_retry_at = ? "
                + "WHERE op_id = ?", reason, nextAttempts, now + backoff, opId);
        // 重发交给 BackendTasks 的 30 秒清扫器：在回报路径里同步 sweep 会让每次失败都触发全表清扫，
        // 失败一多就形成重试风暴（实测把 node_ops 撑到 7 万行并拖住数据库线程）。
        return JsonNodeFactory.instance.objectNode();
    }

    /** 队伍对账上报（契约 1.3.0）：中央单向权威——按中央全量生成 team.apply；并按映射补发 eu.join。 */
    public ObjectNode teamReport(Connection conn, JsonNode p) {
        JsonNode teams = p.path("teams");
        if (!teams.isArray()) {
            throw new OpException(ErrorCodes.BAD_PARAMS, "teams");
        }
        java.util.Set<String> centralNames = new java.util.HashSet<>();
        for (Map<String, Object> row : ctx.db.query(
                "SELECT t.id, t.name, t.join_mode, t.created_at FROM teams t")) {
            long teamId = ((Number) row.get("id")).longValue();
            centralNames.add((String) row.get("name"));
            ObjectNode team = JsonNodeFactory.instance.objectNode();
            team.put("teamId", teamId);
            team.put("name", (String) row.get("name"));
            team.put("joinMode", (String) row.get("join_mode"));
            team.put("createdAt", ((Number) row.get("created_at")).longValue());
            ArrayNode members = team.putArray("members");
            for (Map<String, Object> m : ctx.db.query(
                    "SELECT tm.uuid, tm.role, p.name FROM team_members tm "
                            + "LEFT JOIN player_names p ON p.uuid = tm.uuid "
                            + "WHERE tm.team_id = ? ORDER BY tm.joined_at", teamId)) {
                ObjectNode member = members.addObject();
                member.put("uuid", (String) m.get("uuid"));
                member.put("role", (String) m.get("role"));
                if (m.get("name") != null) {
                    member.put("name", (String) m.get("name"));
                }
            }
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.set("team", team);
            ObjectNode relations = payload.putObject("relations");
            relations.set("allies", JsonNodeFactory.instance.arrayNode());
            relations.set("enemies", JsonNodeFactory.instance.arrayNode());
            ctx.opsStream.enqueue(conn.nodeId(), "team.apply", payload);
        }
        // 差异对账（契约 §8.2）：本服还留着但中央已没有的队伍，发一条空 members 的 team.apply
        // 让适配器删掉，否则玩家在游戏内仍能看到已解散的组。
        for (JsonNode reported : teams) {
            String name = reported.path("name").asText("");
            if (name.isEmpty() || centralNames.contains(name)) {
                continue;
            }
            ObjectNode team = JsonNodeFactory.instance.objectNode();
            team.put("teamId", 0L);
            team.put("name", name);
            team.putArray("members");
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.set("team", team);
            ObjectNode relations = payload.putObject("relations");
            relations.set("allies", JsonNodeFactory.instance.arrayNode());
            relations.set("enemies", JsonNodeFactory.instance.arrayNode());
            ctx.opsStream.enqueue(conn.nodeId(), "team.apply", payload);
        }
        for (Map<String, Object> row : ctx.db.query(
                "SELECT player, team_name FROM eu_bindings WHERE node_id = ?", conn.nodeId())) {
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("player", (String) row.get("player"));
            payload.put("teamName", (String) row.get("team_name"));
            ctx.opsStream.enqueue(conn.nodeId(), "eu.join", payload);
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("accepted", true);
        return out;
    }

    /** 记录无线 EU 映射（1.3.0）。 */
    public void bindEu(long nodeId, String player, String teamName) {
        ctx.db.update("INSERT OR REPLACE INTO eu_bindings(node_id, player, team_name, bound_at) VALUES(?,?,?,?)",
                nodeId, player, teamName, System.currentTimeMillis() / 1000);
    }

    /**
     * me.query 结果回填（节点执行完成）：写结果表 + 通知挂起等待。
     * 登记表载荷为 {queryId, items, error?}；兼容旧实现的 {queryId, result} 包装。
     */
    public ObjectNode meQueryResult(Connection conn, JsonNode p) {
        String queryId = Dispatcher.text(p, "queryId");
        JsonNode result;
        if (p.hasNonNull("result")) {
            result = p.get("result");
        } else {
            ObjectNode wire = JsonNodeFactory.instance.objectNode();
            JsonNode items = p.get("items");
            wire.set("items", items == null ? JsonNodeFactory.instance.arrayNode() : items);
            if (p.hasNonNull("error")) {
                wire.put("error", p.get("error").asText());
            }
            result = wire;
        }
        long now = System.currentTimeMillis() / 1000;
        ctx.db.update("INSERT OR REPLACE INTO me_query_results(query_id, node_id, result, completed_at) "
                + "VALUES(?,?,?,?)", queryId, conn.nodeId(), result.toString(), now);
        ctx.opsStream.deliverQueryResult(queryId, result, conn.nodeId());
        return JsonNodeFactory.instance.objectNode();
    }

    private void requireTargetOp(Connection conn, String opId) {
        Map<String, Object> row = ctx.db.queryOne(
                "SELECT node_id, status FROM node_ops WHERE op_id = ?", opId)
                .orElseThrow(() -> new OpException(ErrorCodes.BAD_PARAMS, "opId"));
        if (((Number) row.get("node_id")).longValue() != conn.nodeId()
                || "done".equals(row.get("status")) || "expired".equals(row.get("status"))) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
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
