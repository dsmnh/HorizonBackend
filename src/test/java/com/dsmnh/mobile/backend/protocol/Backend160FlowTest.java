package com.dsmnh.mobile.backend.protocol;

import com.dsmnh.mobile.backend.support.ServerHarness;
import com.dsmnh.mobile.backend.support.TestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 契约 1.6.0 后端实现：BQ 快照回传、玩家名上报、榜单聚合、监控帧落库、
 * 管理审计与下行类型对账；含过期、权限、重放与节点离线边界。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Backend160FlowTest {
    private static final Set<String> REGISTERED_DOWN_TYPES = Set.of(
            "op.set", "team.apply", "eu.join", "bq.apply", "rankings.apply", "me.query");

    private static ServerHarness harness;
    private static TestClient opsNode;
    private static String ownerUuid;
    private static String deviceId;

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
        ownerUuid = UUID.nameUUIDFromBytes("b160-owner".getBytes()).toString();
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "TestApp/1.0");
            var reg = c.req("account.register",
                    TestClient.obj("name", "b160-owner", "pass", "password-123"));
            assertTrue(reg.path("ok").asBoolean());
            assertNull(c.auth(reg.path("b").path("token").asText()));
            try (TestClient n = node()) {
                String code = n.req("node.bindCode.create", TestClient.obj("actor", ownerUuid))
                        .path("b").path("code").asText();
                assertTrue(c.req("account.bindUUID", TestClient.obj("code", code)).path("ok").asBoolean());
            }
            String claim = harness.ctx.auth.issueOwnerCode("claim", 900);
            assertTrue(c.req("owner.claim", TestClient.obj("code", claim)).path("ok").asBoolean());
            assertTrue(c.req("team.create", TestClient.obj("uuid", ownerUuid, "name", "b160队"))
                    .path("ok").asBoolean());
        }
        deviceId = "b160-device-" + UUID.randomUUID();
        try (TestClient n = node()) {
            assertTrue(n.req("me.register", TestClient.obj(
                    "deviceId", deviceId, "owner", ownerUuid, "kind", "monitor")).path("ok").asBoolean());
        }
        opsNode = node();
        JsonNode full = opsNode.sub("ops", null);
        assertFalse(full.path("sid").asText().isEmpty());
        JsonNode boot = opsNode.readPush();
        assertEquals("f", boot.path("t").asText());
    }

    @AfterAll
    static void tearDown() {
        if (opsNode != null) {
            try {
                opsNode.close();
            } catch (IOException ignored) {
                // 关闭失败不影响后续清理
            }
        }
        harness.close();
    }

    private static TestClient node() throws IOException {
        TestClient n = new TestClient(harness.nodePort);
        n.hello("node", "TestMod/1.0");
        assertNull(n.auth(harness.nodeToken));
        return n;
    }

    private static TestClient owner() throws IOException {
        TestClient c = new TestClient(harness.appPort);
        c.hello("app", "TestApp/1.0");
        String token = c.req("account.login",
                TestClient.obj("name", "b160-owner", "pass", "password-123"))
                .path("b").path("token").asText();
        assertNull(c.auth(token));
        return c;
    }

    private static JsonNode nextOp() throws IOException {
        return opsNode.readPush();
    }

    private static String opIdOf(JsonNode push, String type) {
        if ("f".equals(push.path("t").asText())) {
            for (JsonNode o : push.path("ops")) {
                if (type.equals(o.path("type").asText())) {
                    return o.path("opId").asText();
                }
            }
        }
        for (JsonNode e : push.path("events")) {
            if (type.equals(e.path("type").asText())) {
                return e.path("opId").asText();
            }
        }
        return null;
    }

    private static ObjectNode bqStart(long teamId) throws IOException {
        try (TestClient c = owner()) {
            return (ObjectNode) c.req("bq.sync.start", TestClient.obj(
                    "uuid", ownerUuid, "teamId", teamId, "sourceNode", 1, "targetNode", 1))
                    .path("b");
        }
    }

    private static ObjectNode players(String... pairs) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ArrayNode arr = out.putArray("players");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            arr.addObject().put("uuid", pairs[i]).put("name", pairs[i + 1]);
        }
        return out;
    }

    private static ObjectNode boards(double value) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ObjectNode board = out.putArray("boards").addObject();
        board.put("id", "playtime").put("name", "在线时长");
        board.putArray("rows").addObject().put("uuid", ownerUuid).put("value", value);
        return out;
    }

    @Test
    @Order(1)
    void playerNamesReportComplementsUuidsAndTeamInfo() throws IOException {
        try (TestClient n = node(); TestClient c = owner()) {
            var res = n.req("player.names.report", players(ownerUuid, "测试玩家"));
            assertTrue(res.path("ok").asBoolean(), res.toString());
            assertTrue(res.path("b").path("accepted").asBoolean());
            assertEquals(1, harness.ctx.db.query("SELECT uuid FROM player_names").size());

            JsonNode uuids = c.req("account.uuids", TestClient.obj());
            assertEquals("测试玩家", uuids.path("b").path("uuids").get(0).path("name").asText());
            JsonNode info = c.req("team.info", TestClient.obj("uuid", ownerUuid));
            assertEquals("测试玩家", info.path("b").path("team").path("members").get(0)
                    .path("name").asText(), info.toString());
        }
    }

    @Test
    @Order(2)
    void playerNamesReportRejectsBadPayloadAndWrongPlane() throws IOException {
        try (TestClient n = node(); TestClient c = owner()) {
            assertEquals("BAD_PARAMS", n.req("player.names.report",
                    TestClient.obj("players", "not-an-array")).path("m").asText().split(":")[0]);
            assertEquals("UNKNOWN_OP", c.req("player.names.report", players(ownerUuid, "改名"))
                    .path("m").asText());
        }
        // 空名条目被忽略，不改已有记录
        try (TestClient n = node()) {
            ObjectNode bad = JsonNodeFactory.instance.objectNode();
            bad.putArray("players").addObject().put("uuid", ownerUuid).put("name", "");
            assertTrue(n.req("player.names.report", bad).path("ok").asBoolean());
        }
        assertEquals("测试玩家", harness.ctx.db.queryOne(
                "SELECT name FROM player_names WHERE uuid = ?", ownerUuid).orElseThrow().get("name"));
    }

    @Test
    @Order(3)
    void bqSnapshotPutRoundTrip() throws IOException {
        ObjectNode start = bqStart(1);
        String batchId = start.path("batchId").asText();
        String snapshotId = "bq-" + batchId;
        JsonNode export = nextOp();
        assertEquals("bq.apply", export.path("events").get(0).path("type").asText());
        JsonNode exportP = export.path("events").get(0).path("p");
        assertEquals("export", exportP.path("phase").asText());
        assertEquals(snapshotId, exportP.path("snapshotId").asText());
        assertNotNull(opIdOf(export, "bq.apply"));

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("cells", 3);
        payload.putArray("items").addObject().put("id", "cell-1").put("count", 12);
        ObjectNode put = JsonNodeFactory.instance.objectNode();
        put.put("snapshotId", snapshotId).put("batchId", batchId);
        put.set("payload", payload);
        try (TestClient n = node()) {
            assertTrue(n.req("bq.snapshot.put", put).path("ok").asBoolean());
            Map<String, Object> row = harness.ctx.db.queryOne(
                    "SELECT status, import_op_id FROM bq_snapshots WHERE snapshot_id = ?", snapshotId)
                    .orElseThrow();
            assertEquals("exported", row.get("status"));
            assertNotNull(row.get("import_op_id"));
            JsonNode got = n.req("bq.snapshot.get", TestClient.obj("snapshotId", snapshotId));
            assertTrue(got.path("ok").asBoolean(), got.toString());
            assertEquals(3, got.path("b").path("payload").path("cells").asInt());
        }

        JsonNode importPush = nextOp();
        JsonNode importP = importPush.path("events").get(0).path("p");
        assertEquals("import", importP.path("phase").asText(), importPush.toString());
        assertEquals(snapshotId, importP.path("snapshotId").asText());
        String importOpId = opIdOf(importPush, "bq.apply");
        assertNotNull(importOpId);
        try (TestClient n = node()) {
            assertTrue(n.req("node.op.ack", TestClient.obj("opId", importOpId)).path("ok").asBoolean());
        }
        assertEquals("applied", harness.ctx.db.queryOne(
                "SELECT status FROM bq_snapshots WHERE snapshot_id = ?", snapshotId)
                .orElseThrow().get("status"));
    }

    @Test
    @Order(4)
    void bqSnapshotPutRejectsWrongBatchReplayAndForeigner() throws IOException {
        ObjectNode start = bqStart(1);
        String batchId = start.path("batchId").asText();
        String snapshotId = "bq-" + batchId;
        JsonNode export = nextOp();
        assertEquals("bq.apply", export.path("events").get(0).path("type").asText());

        ObjectNode put = JsonNodeFactory.instance.objectNode();
        put.put("snapshotId", snapshotId).put("batchId", batchId).put("payload", 1);
        try (TestClient n = node()) {
            // 未导出前目标节点不可读
            assertEquals("FORBIDDEN", n.req("bq.snapshot.get",
                    TestClient.obj("snapshotId", snapshotId)).path("m").asText());
            // 批次号不符
            ObjectNode wrong = put.deepCopy();
            wrong.put("batchId", "other-batch");
            assertEquals("FORBIDDEN", n.req("bq.snapshot.put", wrong).path("m").asText());
            // 非参与节点：临时改写源节点后原节点不再具备归属
            harness.ctx.db.update("UPDATE bq_snapshots SET source_node = 999 WHERE snapshot_id = ?",
                    snapshotId);
            assertEquals("FORBIDDEN", n.req("bq.snapshot.put", put).path("m").asText());
            harness.ctx.db.update("UPDATE bq_snapshots SET source_node = 1 WHERE snapshot_id = ?",
                    snapshotId);
            // 正确回传与重放
            assertTrue(n.req("bq.snapshot.put", put).path("ok").asBoolean());
            assertEquals("FORBIDDEN", n.req("bq.snapshot.put", put).path("m").asText());
        }
        JsonNode importPush = nextOp();
        String importOpId = opIdOf(importPush, "bq.apply");
        assertNotNull(importOpId);
        try (TestClient n = node()) {
            assertTrue(n.req("node.op.ack", TestClient.obj("opId", importOpId)).path("ok").asBoolean());
        }
    }

    @Test
    @Order(5)
    void bqSnapshotExpiryAndSweep() throws IOException {
        ObjectNode start = bqStart(1);
        String snapshotId = "bq-" + start.path("batchId").asText();
        JsonNode export = nextOp();
        String exportOpId = opIdOf(export, "bq.apply");
        assertNotNull(exportOpId);
        harness.ctx.db.update("UPDATE bq_snapshots SET expires_at = ? WHERE snapshot_id = ?",
                1, snapshotId);
        try (TestClient n = node()) {
            assertEquals("BAD_PARAMS", n.req("bq.snapshot.get",
                    TestClient.obj("snapshotId", snapshotId)).path("m").asText().split(":")[0]);
        }
        harness.ctx.tasks.sweepExpired();
        assertEquals("expired", harness.ctx.db.queryOne(
                "SELECT status FROM bq_snapshots WHERE snapshot_id = ?", snapshotId)
                .orElseThrow().get("status"));
        assertEquals("", harness.ctx.db.queryOne(
                "SELECT payload FROM bq_snapshots WHERE snapshot_id = ?", snapshotId)
                .orElseThrow().get("payload"));
    }

    @Test
    @Order(6)
    void rankingsReportAndRankSyncDeliverFullBoards() throws IOException {
        try (TestClient n = node()) {
            assertTrue(n.req("rankings.report", boards(12.5)).path("ok").asBoolean());
            assertEquals("BAD_PARAMS", n.req("rankings.report",
                    TestClient.obj("boards", "bad")).path("m").asText().split(":")[0]);
        }
        assertEquals(1, harness.ctx.db.query("SELECT node_id FROM rank_reports").size());
        try (TestClient c = owner()) {
            var sync = c.req("rank.sync", TestClient.obj("uuid", ownerUuid, "nodeId", 1));
            assertTrue(sync.path("ok").asBoolean(), sync.toString());
            assertTrue(sync.path("b").path("queued").asBoolean());
            // 每日限次落库而非内存计数
            assertEquals(1, harness.ctx.db.query("SELECT uuid FROM rank_sync_day").size());
            assertEquals("RATE_LIMITED", c.req("rank.sync",
                    TestClient.obj("uuid", ownerUuid, "nodeId", 1)).path("m").asText().split(":")[0]);
            // 缺少 uuid 时保持契约参数要求，不迁就错误调用
            assertEquals("BAD_PARAMS", c.req("rank.sync",
                    TestClient.obj("nodeId", 1)).path("m").asText().split(":")[0]);
        }
        JsonNode push = nextOp();
        JsonNode apply = push.path("events").get(0);
        assertEquals("rankings.apply", apply.path("type").asText());
        JsonNode boards = apply.path("p").path("boards");
        assertTrue(boards.isArray(), push.toString());
        assertEquals("playtime", boards.get(0).path("id").asText());
        assertTrue(apply.path("p").path("uuid").isMissingNode(), "载荷不得回退为单 uuid");
        String opId = opIdOf(push, "rankings.apply");
        assertNotNull(opId);
        try (TestClient n = node()) {
            assertTrue(n.req("node.op.ack", TestClient.obj("opId", opId)).path("ok").asBoolean());
        }
    }

    @Test
    @Order(7)
    void dailyAggregationDistributesAndKeepsSevenSnapshots() throws IOException {
        for (int i = 0; i < 9; i++) {
            assertEquals(1, harness.ctx.tasks.dailyRankings());
        }
        List<Map<String, Object>> snapshots = harness.ctx.db.query(
                "SELECT boards FROM rankings_snapshots ORDER BY id DESC");
        assertEquals(7, snapshots.size());
        JsonNode latest = harness.ctx.mapper.readTree((String) snapshots.get(0).get("boards"));
        assertEquals("playtime", latest.get(0).path("id").asText());
        assertEquals(12.5, latest.get(0).path("rows").get(0).path("value").asDouble(), 0.001);

        int seen = 0;
        while (seen < 9) {
            JsonNode push = nextOp();
            for (JsonNode e : push.path("events")) {
                if ("rankings.apply".equals(e.path("type").asText())) {
                    seen++;
                    try (TestClient n = node()) {
                        assertTrue(n.req("node.op.ack",
                                TestClient.obj("opId", e.path("opId").asText())).path("ok").asBoolean());
                    }
                }
            }
        }
    }

    @Test
    @Order(8)
    void metricsFramesPersistHistoryAndSweep() throws IOException {
        try (TestClient n = node()) {
            for (int i = 0; i < 3; i++) {
                assertTrue(n.req("metrics.report", TestClient.obj(
                        "mspt", 10 + i, "max", 30 + i, "tps", 20 - i, "players", 3))
                        .path("ok").asBoolean());
            }
        }
        assertEquals(3, harness.ctx.db.query("SELECT id FROM metrics_frames").size());
        try (TestClient c = owner()) {
            JsonNode snap = c.req("metrics.network", TestClient.obj("uuid", ownerUuid));
            assertTrue(snap.path("ok").asBoolean(), snap.toString());
            JsonNode node0 = snap.path("b").path("nodes").get(0);
            JsonNode history = node0.path("history");
            assertEquals(3, history.path("samples").asInt());
            assertEquals(11, history.path("msptAvg").asDouble(), 0.01);
            assertEquals(32, history.path("msptPeak").asDouble(), 0.01);
            assertEquals(18, history.path("tpsMin").asDouble(), 0.01);
        }
        harness.ctx.db.update("UPDATE metrics_frames SET at = ?", 1);
        harness.ctx.tasks.sweepExpired();
        assertEquals(0, harness.ctx.db.query("SELECT id FROM metrics_frames").size());
    }

    @Test
    @Order(9)
    void auditLogRecordsAdminActionsAndRetention() throws IOException {
        String outsider = UUID.nameUUIDFromBytes("b160-outsider".getBytes()).toString();
        try (TestClient n = node(); TestClient c = owner()) {
            String code = n.req("node.bindCode.create", TestClient.obj("actor", outsider))
                    .path("b").path("code").asText();
            // 绑定给另一账号后由服主解绑，产生审计记录
            try (TestClient other = new TestClient(harness.appPort)) {
                other.hello("app", "TestApp/1.0");
                var reg = other.req("account.register",
                        TestClient.obj("name", "b160-other", "pass", "password-123"));
                assertNull(other.auth(reg.path("b").path("token").asText()));
                assertTrue(other.req("account.bindUUID", TestClient.obj("code", code))
                        .path("ok").asBoolean());
            }
            assertTrue(c.req("admin.unbindUUID", TestClient.obj("uuid", outsider))
                    .path("ok").asBoolean());
        }
        List<Map<String, Object>> rows = harness.ctx.db.query("SELECT action FROM audit_log");
        assertTrue(rows.stream().anyMatch(r -> "admin.unbindUUID".equals(r.get("action"))));
        assertTrue(rows.stream().anyMatch(r -> "owner.claim".equals(r.get("action"))));
        harness.ctx.db.update("UPDATE audit_log SET at = ?", 1);
        harness.ctx.tasks.sweepExpired();
        assertTrue(harness.ctx.db.query("SELECT id FROM audit_log").isEmpty());
    }

    @Test
    @Order(10)
    void downstreamTypesStayWithinRegistryAndOfflineIsRejected() throws IOException {
        for (Map<String, Object> row : harness.ctx.db.query("SELECT DISTINCT type FROM node_ops")) {
            assertTrue(REGISTERED_DOWN_TYPES.contains(String.valueOf(row.get("type"))),
                    "未登记的下行类型：" + row.get("type"));
        }
        opsNode.close();
        try (TestClient c = owner()) {
            assertEquals("NODE_OFFLINE", c.req("me.query",
                    TestClient.obj("uuid", ownerUuid, "deviceId", deviceId))
                    .path("m").asText());
        }
        opsNode = null;
    }
}
