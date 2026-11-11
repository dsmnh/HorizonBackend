package com.dsmnh.mobile.backend.api;

import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.Connection;
import com.dsmnh.mobile.backend.net.Plane;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 节点侧 1.5.0 / 1.6.0 操作：ME 设备、监控帧与群组榜单、BQ 覆盖同步、玩家名上报
 * （契约 1.6.0 §8.4-§8.7）。
 */
public final class NodeOps150 {
    private static final int BQ_SNAPSHOT_TTL_SEC = 7 * 24 * 3600;
    private static final int METRICS_HISTORY_FRAMES = 360;
    private static final int METRICS_HISTORY_WINDOW_SEC = 900;

    private final ServerContext ctx;

    public NodeOps150(ServerContext ctx) {
        this.ctx = ctx;
    }

    public Map<String, Dispatcher.OpHandler> nodeOps() {
        Map<String, Dispatcher.OpHandler> ops = new HashMap<>();
        ops.put("me.register", this::meRegister);
        ops.put("me.unregister", this::meUnregister);
        ops.put("metrics.report", this::metricsReport);
        ops.put("rankings.report", this::rankingsReport);
        ops.put("bq.snapshot.get", this::bqSnapshotGet);
        ops.put("bq.snapshot.put", this::bqSnapshotPut);
        ops.put("player.names.report", this::playerNamesReport);
        return ops;
    }

    public Map<String, Dispatcher.OpHandler> appOps() {
        Map<String, Dispatcher.OpHandler> ops = new HashMap<>();
        ops.put("bq.sync.start", this::bqSyncStart);
        ops.put("me.list", this::meList);
        ops.put("me.share", this::meShare);
        ops.put("me.unshare", this::meUnshare);
        ops.put("me.query", this::meQuery);
        ops.put("metrics.network", this::metricsNetwork);
        ops.put("rank.sync", this::rankSync);
        return ops;
    }

    // ---------- 节点侧 ----------

    public ObjectNode meRegister(Connection conn, JsonNode p) {
        String deviceId = Dispatcher.text(p, "deviceId");
        String owner = Dispatcher.text(p, "owner");
        String kind = p.hasNonNull("kind") ? p.get("kind").asText() : "monitor";
        ctx.db.update("INSERT OR REPLACE INTO me_devices(device_id, node_id, owner, kind, created_at) "
                + "VALUES(?,?,?,?,?)", deviceId, conn.nodeId(), owner, kind, now());
        return JsonNodeFactory.instance.objectNode();
    }

    public ObjectNode meUnregister(Connection conn, JsonNode p) {
        String deviceId = Dispatcher.text(p, "deviceId");
        ctx.db.update("DELETE FROM me_devices WHERE device_id = ? AND node_id = ?", deviceId, conn.nodeId());
        ctx.db.update("DELETE FROM me_shares WHERE device_id = ?", deviceId);
        return JsonNodeFactory.instance.objectNode();
    }

    /** 监控帧落库（企划书 07）：5 秒一帧，按节点保留最近 360 帧供历史摘要查询。 */
    public ObjectNode metricsReport(Connection conn, JsonNode p) {
        ObjectNode frame = JsonNodeFactory.instance.objectNode();
        frame.put("nodeId", conn.nodeId());
        frame.put("mspt", p.path("mspt").asDouble());
        frame.put("max", p.path("max").asDouble());
        frame.put("tps", p.path("tps").asDouble());
        frame.put("players", p.path("players").asInt());
        frame.set("dims", p.path("dims"));
        frame.put("at", now());
        ctx.db.update("INSERT INTO metrics_frames(node_id, frame, at) VALUES(?,?,?)",
                conn.nodeId(), frame.toString(), now());
        ctx.db.update("DELETE FROM metrics_frames WHERE node_id = ? AND id NOT IN "
                + "(SELECT id FROM metrics_frames WHERE node_id = ? ORDER BY id DESC LIMIT "
                + METRICS_HISTORY_FRAMES + ")", conn.nodeId(), conn.nodeId());
        return JsonNodeFactory.instance.objectNode();
    }

    /** 榜单上报落库，供每日聚合生成群组快照。 */
    public ObjectNode rankingsReport(Connection conn, JsonNode p) {
        JsonNode boards = p.get("boards");
        if (boards == null || !boards.isArray()) {
            throw new OpException(ErrorCodes.BAD_PARAMS, "boards");
        }
        ctx.rankings.report(conn.nodeId(), boards);
        return JsonNodeFactory.instance.objectNode();
    }

    /** 目标节点拉取已导出快照；仅参与同步的节点可读，过期与未导出均拒绝。 */
    public ObjectNode bqSnapshotGet(Connection conn, JsonNode p) {
        String snapshotId = Dispatcher.text(p, "snapshotId");
        Map<String, Object> row = ctx.db.queryOne(
                "SELECT source_node, target_node, payload, status, expires_at FROM bq_snapshots "
                        + "WHERE snapshot_id = ?", snapshotId)
                .orElseThrow(() -> new OpException(ErrorCodes.BAD_PARAMS, "snapshotId"));
        requireSnapshotParticipation(conn, row);
        String status = String.valueOf(row.get("status"));
        if (!"exported".equals(status) && !"applied".equals(status)) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("payload", parse((String) row.get("payload")));
        return out;
    }

    /** 源节点回传导出结果：写入 payload、流转为 exported，并向目标节点下发 import。 */
    public ObjectNode bqSnapshotPut(Connection conn, JsonNode p) {
        String snapshotId = Dispatcher.text(p, "snapshotId");
        String batchId = Dispatcher.text(p, "batchId");
        JsonNode payload = p.get("payload");
        if (payload == null) {
            throw new OpException(ErrorCodes.BAD_PARAMS, "payload");
        }
        Map<String, Object> row = ctx.db.queryOne(
                "SELECT team_id, source_node, target_node, batch_id, status, expires_at "
                        + "FROM bq_snapshots WHERE snapshot_id = ?", snapshotId)
                .orElseThrow(() -> new OpException(ErrorCodes.BAD_PARAMS, "snapshotId"));
        requireSnapshotParticipation(conn, row);
        if (((Number) row.get("source_node")).longValue() != conn.nodeId()) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
        if (!batchId.equals(String.valueOf(row.get("batch_id")))
                || !"pending".equals(String.valueOf(row.get("status")))) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
        Object targetRaw = row.get("target_node");
        if (targetRaw == null) {
            throw new OpException(ErrorCodes.BAD_PARAMS, "snapshotId");
        }
        long targetNode = ((Number) targetRaw).longValue();
        int updated = ctx.db.update("UPDATE bq_snapshots SET payload = ?, status = 'exported' "
                + "WHERE snapshot_id = ? AND status = 'pending'", payload.toString(), snapshotId);
        if (updated != 1) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
        ObjectNode pImport = JsonNodeFactory.instance.objectNode();
        pImport.put("teamId", ((Number) row.get("team_id")).longValue());
        pImport.put("sourceNode", conn.nodeId());
        pImport.put("targetNode", targetNode);
        pImport.put("batchId", batchId);
        pImport.put("snapshotId", snapshotId);
        pImport.put("phase", "import");
        String importOpId = ctx.opsStream.enqueue(targetNode, "bq.apply", pImport);
        ctx.db.update("UPDATE bq_snapshots SET import_op_id = ? WHERE snapshot_id = ?",
                importOpId, snapshotId);
        return JsonNodeFactory.instance.objectNode();
    }

    /** 在线玩家名上报（契约 §8.7）：持久化后供 account.uuids 与 team.info 联表补全。 */
    public ObjectNode playerNamesReport(Connection conn, JsonNode p) {
        if (conn.serverPlane() != Plane.NODE || conn.nodeId() < 0) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
        JsonNode players = p.get("players");
        if (players == null || !players.isArray()) {
            throw new OpException(ErrorCodes.BAD_PARAMS, "players");
        }
        long at = now();
        for (JsonNode item : players) {
            String uuid = item.path("uuid").asText("");
            String name = item.path("name").asText("");
            if (uuid.isEmpty() || name.isEmpty()) {
                continue;
            }
            ctx.db.update("INSERT OR REPLACE INTO player_names(uuid, name, updated_at) VALUES(?,?,?)",
                    uuid, name, at);
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("accepted", true);
        return out;
    }

    // ---------- App 侧 ----------

    public ObjectNode bqSyncStart(Connection conn, JsonNode p) {
        requireSuper(conn);
        long teamId = Dispatcher.longValue(p, "teamId");
        long source = Dispatcher.longValue(p, "sourceNode");
        long target = Dispatcher.longValue(p, "targetNode");
        requireNode(source);
        requireNode(target);
        String batchId = UUID.randomUUID().toString();
        String snapshotId = "bq-" + batchId;
        long at = now();
        ctx.db.update("INSERT INTO bq_snapshots(snapshot_id, team_id, source_node, target_node, "
                        + "batch_id, status, payload, created_at, expires_at) "
                        + "VALUES(?,?,?,?,?,'pending','',?,?)",
                snapshotId, teamId, source, target, batchId, at, at + BQ_SNAPSHOT_TTL_SEC);
        ObjectNode pSource = JsonNodeFactory.instance.objectNode();
        pSource.put("teamId", teamId);
        pSource.put("sourceNode", source);
        pSource.put("targetNode", target);
        pSource.put("batchId", batchId);
        pSource.put("snapshotId", snapshotId);
        pSource.put("phase", "export");
        ctx.opsStream.enqueue(source, "bq.apply", pSource);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("batchId", batchId);
        return out;
    }

    public ObjectNode meList(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        ArrayNode devices = JsonNodeFactory.instance.objectNode().putArray("devices");
        for (Map<String, Object> row : ctx.db.query(
                "SELECT d.device_id, d.node_id, d.owner, d.kind FROM me_devices d "
                        + "WHERE d.owner = ? OR EXISTS (SELECT 1 FROM me_shares s "
                        + "WHERE s.device_id = d.device_id AND s.target = ?) ORDER BY d.created_at",
                uuid, uuid)) {
            ObjectNode item = devices.addObject();
            item.put("deviceId", (String) row.get("device_id"));
            item.put("nodeId", ((Number) row.get("node_id")).longValue());
            item.put("owner", (String) row.get("owner"));
            item.put("kind", (String) row.get("kind"));
            ArrayNode shared = item.putArray("shared");
            for (Map<String, Object> s : ctx.db.query(
                    "SELECT target, level FROM me_shares WHERE device_id = ?",
                    (String) row.get("device_id"))) {
                ObjectNode sh = shared.addObject();
                sh.put("uuid", (String) s.get("target"));
                sh.put("level", (String) s.get("level"));
            }
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("devices", devices);
        return out;
    }

    public ObjectNode meShare(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        String deviceId = Dispatcher.text(p, "deviceId");
        String target = Dispatcher.text(p, "target");
        String level = p.hasNonNull("level") ? p.get("level").asText() : "control";
        requireDeviceOwnerOrLeader(deviceId, uuid);
        ctx.db.update("INSERT OR REPLACE INTO me_shares(device_id, target, level, granted_by, created_at) "
                + "VALUES(?,?,?,?,?)", deviceId, target, level, uuid, now());
        return JsonNodeFactory.instance.objectNode();
    }

    public ObjectNode meUnshare(Connection conn, JsonNode p) {
        String uuid = identity(conn, p);
        String deviceId = Dispatcher.text(p, "deviceId");
        String target = Dispatcher.text(p, "target");
        requireDeviceOwnerOrLeader(deviceId, uuid);
        ctx.db.update("DELETE FROM me_shares WHERE device_id = ? AND target = ?", deviceId, target);
        return JsonNodeFactory.instance.objectNode();
    }

    /** me.query：投递到设备所在节点（经下行操作）；节点离线 NODE_OFFLINE。带 queryId 时轮询重查结果。 */
    public ObjectNode meQuery(Connection conn, JsonNode p) {
        JsonNode queryIdNode = p.get("queryId");
        if (queryIdNode != null && queryIdNode.isTextual()) {
            String qid = queryIdNode.asText();
            Map<String, Object> qr = ctx.db.queryOne(
                    "SELECT result, completed_at FROM me_query_results WHERE query_id = ?", qid)
                    .orElseThrow(() -> new OpException(ErrorCodes.BAD_PARAMS, "queryId"));
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            if (qr.get("completed_at") != null) {
                out.set("items", parse((String) qr.get("result")));
                out.put("pending", false);
            } else {
                out.put("pending", true);
                out.put("queryId", qid);
            }
            return out;
        }
        String uuid = identity(conn, p);
        String deviceId = Dispatcher.text(p, "deviceId");
        Map<String, Object> device = ctx.db.queryOne(
                "SELECT node_id, owner FROM me_devices WHERE device_id = ?", deviceId)
                .orElseThrow(() -> new OpException(ErrorCodes.BAD_PARAMS, "deviceId"));
        if (!uuid.equals(device.get("owner")) && !hasShare(deviceId, uuid)) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
        long nodeId = ((Number) device.get("node_id")).longValue();
        if (!ctx.opsStream.hasSubscriber(nodeId)) {
            throw new OpException(ErrorCodes.NODE_OFFLINE);
        }
        String queryId = UUID.randomUUID().toString();
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("deviceId", deviceId);
        payload.put("queryId", queryId);
        ctx.opsStream.enqueue(nodeId, "me.query", payload);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("queryId", queryId);
        out.put("pending", true);
        return out;
    }

    /** 聚合快照：每节点最近一帧 + 历史摘要（内部表查询，不新增契约 op）。 */
    public ObjectNode metricsNetwork(Connection conn, JsonNode p) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ArrayNode nodes = out.putArray("nodes");
        long now = now();
        for (Map<String, Object> row : ctx.db.query(
                "SELECT id, name FROM nodes WHERE status = 'active' ORDER BY id")) {
            long nodeId = ((Number) row.get("id")).longValue();
            ObjectNode item = nodes.addObject();
            item.put("nodeId", nodeId);
            item.put("name", row.get("name") == null ? "" : (String) row.get("name"));
            var latest = ctx.db.queryOne(
                    "SELECT frame, at FROM metrics_frames WHERE node_id = ? ORDER BY id DESC LIMIT 1", nodeId);
            if (latest.isEmpty()) {
                item.put("offline", true);
            } else {
                long at = ((Number) latest.get().get("at")).longValue();
                if (now - at > 30) {
                    item.put("offline", true);
                } else {
                    item.set("frame", parse((String) latest.get().get("frame")));
                }
            }
            appendHistory(item, nodeId, now);
        }
        return out;
    }

    /** rank.sync：每日每 UUID 一次，向指定节点下发完整 boards 快照（契约 1.6.0 §8.6）。 */
    public ObjectNode rankSync(Connection conn, JsonNode p) {
        String uuid = Dispatcher.text(p, "uuid");
        long nodeId = Dispatcher.longValue(p, "nodeId");
        boolean self = ctx.auth.isUuidBoundToAccount(uuid, conn.accountId());
        String role = ctx.auth.roleOf(conn.accountId());
        if (!self && !"owner".equals(role) && !"super".equals(role)) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
        requireNode(nodeId);
        long day = java.time.LocalDate.now().toEpochDay();
        int inserted = ctx.db.update("INSERT OR IGNORE INTO rank_sync_day(uuid, day) VALUES(?,?)", uuid, day);
        if (inserted == 0) {
            throw new OpException(ErrorCodes.RATE_LIMITED, "rank-sync-daily");
        }
        ctx.db.update("INSERT OR REPLACE INTO rank_home(uuid, node_id, updated_at) VALUES(?,?,?)",
                uuid, nodeId, now());
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.set("boards", ctx.rankings.latestBoards(nodeId));
        ctx.opsStream.enqueue(nodeId, "rankings.apply", payload);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("queued", true);
        return out;
    }

    // ---------- 辅助 ----------

    private void appendHistory(ObjectNode item, long nodeId, long now) {
        List<Map<String, Object>> frames = ctx.db.query(
                "SELECT frame FROM metrics_frames WHERE node_id = ? AND at >= ? ORDER BY id DESC LIMIT 180",
                nodeId, now - METRICS_HISTORY_WINDOW_SEC);
        double msptSum = 0;
        double msptPeak = 0;
        double tpsMin = Double.POSITIVE_INFINITY;
        for (Map<String, Object> row : frames) {
            JsonNode f = parse((String) row.get("frame"));
            msptSum += f.path("mspt").asDouble();
            msptPeak = Math.max(msptPeak, f.path("max").asDouble());
            tpsMin = Math.min(tpsMin, f.path("tps").asDouble());
        }
        ObjectNode history = item.putObject("history");
        history.put("samples", frames.size());
        history.put("msptAvg", frames.isEmpty() ? 0 : round(msptSum / frames.size()));
        history.put("msptPeak", round(msptPeak));
        history.put("tpsMin", Double.isInfinite(tpsMin) ? 0 : round(tpsMin));
    }

    /** 快照归属与过期校验：连接节点须为源或目标节点，且快照未过 7 天有效期。 */
    private void requireSnapshotParticipation(Connection conn, Map<String, Object> row) {
        long expiresAt = ((Number) row.get("expires_at")).longValue();
        if (expiresAt <= now()) {
            throw new OpException(ErrorCodes.BAD_PARAMS, "snapshotId");
        }
        long nodeId = conn.nodeId();
        long source = ((Number) row.get("source_node")).longValue();
        Object targetRaw = row.get("target_node");
        long target = targetRaw == null ? -1 : ((Number) targetRaw).longValue();
        if (nodeId != source && nodeId != target) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private long now() {
        return System.currentTimeMillis() / 1000;
    }

    private String identity(Connection conn, JsonNode p) {
        if (conn.serverPlane() == Plane.APP) {
            return Dispatcher.text(p, "uuid");
        }
        return Dispatcher.text(p, "actor");
    }

    private void requireSuper(Connection conn) {
        String role = ctx.auth.roleOf(conn.accountId());
        if (!"owner".equals(role) && !"super".equals(role)) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
    }

    private void requireNode(long nodeId) {
        Map<String, Object> node = ctx.db.queryOne("SELECT status FROM nodes WHERE id = ?", nodeId)
                .orElseThrow(() -> new OpException(ErrorCodes.NODE_UNKNOWN));
        if (!"active".equals(node.get("status"))) {
            throw new OpException(ErrorCodes.NODE_UNKNOWN);
        }
    }

    /** 设备所有者，或与所有者同队的队长/副队长（企划书 06）。 */
    private void requireDeviceOwnerOrLeader(String deviceId, String uuid) {
        Map<String, Object> device = ctx.db.queryOne(
                "SELECT owner, node_id FROM me_devices WHERE device_id = ?", deviceId)
                .orElseThrow(() -> new OpException(ErrorCodes.BAD_PARAMS, "deviceId"));
        String owner = (String) device.get("owner");
        if (uuid.equals(owner)) {
            return;
        }
        for (Map<String, Object> t : ctx.db.query(
                "SELECT team_id, role FROM team_members WHERE uuid = ?", uuid)) {
            String role = String.valueOf(t.get("role"));
            if (!"leader".equals(role) && !"vice".equals(role)) {
                continue;
            }
            long teamId = ((Number) t.get("team_id")).longValue();
            if (!ctx.db.query("SELECT uuid FROM team_members WHERE team_id = ? AND uuid = ?",
                    teamId, owner).isEmpty()) {
                return;
            }
        }
        throw new OpException(ErrorCodes.FORBIDDEN);
    }

    private boolean hasShare(String deviceId, String uuid) {
        return !ctx.db.queryOne("SELECT target FROM me_shares WHERE device_id = ? AND target = ?",
                deviceId, uuid).isEmpty();
    }

    private JsonNode parse(String payload) {
        try {
            return ctx.mapper.readTree(payload);
        } catch (Exception e) {
            return JsonNodeFactory.instance.objectNode();
        }
    }
}
