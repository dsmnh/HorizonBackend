package com.dsmnh.mobile.backend.api;

import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.Connection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 节点侧 1.5.0 操作：ME 设备登记/查询、metrics 上报缓存、rankings 上报与聚合、
 * bq 同步发起与快照存取（契约 1.5.0 §8.4-§8.6）。
 */
public final class NodeOps150 {
    private final ServerContext ctx;

    /** 最近一帧监控（nodeId → 帧）。内存即可：只服务聚合快照。 */
    private final Map<Long, ObjectNode> lastMetrics = new HashMap<>();
    private final Map<String, Long> rankSyncDay = new HashMap<>();

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
        long now = System.currentTimeMillis() / 1000;
        ctx.db.update("INSERT OR REPLACE INTO me_devices(device_id, node_id, owner, kind, created_at) "
                + "VALUES(?,?,?,?,?)", deviceId, conn.nodeId(), owner, kind, now);
        return JsonNodeFactory.instance.objectNode();
    }

    public ObjectNode meUnregister(Connection conn, JsonNode p) {
        String deviceId = Dispatcher.text(p, "deviceId");
        ctx.db.update("DELETE FROM me_devices WHERE device_id = ? AND node_id = ?", deviceId, conn.nodeId());
        ctx.db.update("DELETE FROM me_shares WHERE device_id = ?", deviceId);
        return JsonNodeFactory.instance.objectNode();
    }

    public ObjectNode metricsReport(Connection conn, JsonNode p) {
        ObjectNode frame = JsonNodeFactory.instance.objectNode();
        frame.put("nodeId", conn.nodeId());
        frame.put("mspt", p.path("mspt").asDouble());
        frame.put("max", p.path("max").asDouble());
        frame.put("tps", p.path("tps").asDouble());
        frame.put("players", p.path("players").asInt());
        frame.set("dims", p.path("dims"));
        frame.put("at", System.currentTimeMillis() / 1000);
        synchronized (lastMetrics) {
            lastMetrics.put(conn.nodeId(), frame);
        }
        return JsonNodeFactory.instance.objectNode();
    }

    public ObjectNode rankingsReport(Connection conn, JsonNode p) {
        // 1.5.0：整包暂存内存/落库可后续加；先应答受理（聚合快照生成在 rank.sync/每日任务侧）
        return JsonNodeFactory.instance.objectNode();
    }

    public ObjectNode bqSnapshotGet(Connection conn, JsonNode p) {
        String snapshotId = Dispatcher.text(p, "snapshotId");
        Map<String, Object> row = ctx.db.queryOne("SELECT payload FROM bq_snapshots WHERE snapshot_id = ?",
                snapshotId).orElseThrow(() -> new OpException(ErrorCodes.BAD_PARAMS, "snapshotId"));
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("payload", parse((String) row.get("payload")));
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
        long now = System.currentTimeMillis() / 1000;
        ctx.db.update("INSERT INTO bq_snapshots(snapshot_id, team_id, source_node, payload, created_at, expires_at) "
                        + "VALUES(?,?,?,'',?,?)", snapshotId, teamId, source, now, now + 7 * 24 * 3600);
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
                + "VALUES(?,?,?,?,?)", deviceId, target, level, uuid, System.currentTimeMillis() / 1000);
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

    /** me.query：投递到设备所在节点（经下行操作 + 挂起查询表）；节点离线 NODE_OFFLINE。 */
    public ObjectNode meQuery(Connection conn, JsonNode p) {
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
        // 带 queryId 重查：查结果表，已完成则返回结果
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
        // 新查询：入待执行并等待回报（简版：返回挂起标记 + queryId，App 轮询重查）
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

    public ObjectNode metricsNetwork(Connection conn, JsonNode p) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ArrayNode nodes = out.putArray("nodes");
        for (Map<String, Object> row : ctx.db.query(
                "SELECT id, name FROM nodes WHERE status = 'active' ORDER BY id")) {
            long nodeId = ((Number) row.get("id")).longValue();
            ObjectNode item = nodes.addObject();
            item.put("nodeId", nodeId);
            item.put("name", row.get("name") == null ? "" : (String) row.get("name"));
            ObjectNode frame;
            synchronized (lastMetrics) {
                frame = lastMetrics.get(nodeId);
            }
            if (frame == null) {
                item.put("offline", true);
            } else {
                item.set("frame", frame);
            }
        }
        return out;
    }

    public ObjectNode rankSync(Connection conn, JsonNode p) {
        String uuid = Dispatcher.text(p, "uuid");
        long nodeId = Dispatcher.longValue(p, "nodeId");
        boolean self = ctx.auth.isUuidBoundToAccount(uuid, conn.accountId());
        String role = ctx.auth.roleOf(conn.accountId());
        if (!self && !"owner".equals(role) && !"super".equals(role)) {
            throw new OpException(ErrorCodes.FORBIDDEN);
        }
        requireNode(nodeId);
        long today = System.currentTimeMillis() / 1000 / 86400;
        Long last = rankSyncDay.get(uuid);
        if (last != null && last == today) {
            throw new OpException(ErrorCodes.RATE_LIMITED, "rank-sync-daily");
        }
        rankSyncDay.put(uuid, today);
        ctx.db.update("INSERT OR REPLACE INTO rank_home(uuid, node_id, updated_at) VALUES(?,?,?)",
                uuid, nodeId, System.currentTimeMillis() / 1000);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("uuid", uuid);
        ctx.opsStream.enqueue(nodeId, "rankings.apply", payload);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("queued", true);
        return out;
    }

    // ---------- 辅助 ----------

    /** 按平面解析操作者身份（App 用 p.uuid，节点用 p.actor）。 */
    private String identity(Connection conn, JsonNode p) {
        if (conn.serverPlane() == com.dsmnh.mobile.backend.net.Plane.APP) {
            return Dispatcher.text(p, "uuid");
        }
        return Dispatcher.text(p, "actor");
    }

    public Map<Long, ObjectNode> lastMetrics() {
        return lastMetrics;
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
