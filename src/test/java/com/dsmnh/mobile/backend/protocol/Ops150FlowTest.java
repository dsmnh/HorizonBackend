package com.dsmnh.mobile.backend.protocol;

import com.dsmnh.mobile.backend.support.ServerHarness;
import com.dsmnh.mobile.backend.support.TestClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 契约 1.5.0（阶段 4/5/6）：ME 设备域、metrics 聚合、rank.sync 限次、BQ 同步发起。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ops150FlowTest {
    private static ServerHarness harness;
    private static String ownerUuid;
    private static String superUuid;
    private static String superToken;

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
        registerAndBind("owner");
        registerAndBind("super");
        ownerUuid = uuidOf("owner");
        superUuid = uuidOf("super");
        // owner 认领服主并授予 super
        try (TestClient owner = authedAs("owner")) {
            String claim = harness.ctx.auth.issueOwnerCode("claim", 900);
            assertTrue(op(owner, ownerUuid, "owner.claim", "code", claim)
                    .path("b").path("role").asText().equals("owner"));
            assertTrue(op(owner, ownerUuid, "admin.grantSuper", "uuid", superUuid)
                    .path("b").path("role").asText().equals("super"));
        }
    }

    @AfterAll
    static void tearDown() {
        harness.close();
    }

    private static void registerAndBind(String name) throws IOException {
        String uuid = UUID.nameUUIDFromBytes(("o15-" + name).getBytes()).toString();
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "TestApp/1.0");
            var reg = c.req("account.register",
                    TestClient.obj("name", "o15-" + name, "pass", "password-123"));
            assertTrue(reg.path("ok").asBoolean(), reg.toString());
            assertNull(c.auth(reg.path("b").path("token").asText()));
            try (TestClient n = node()) {
                String code = n.req("node.bindCode.create", TestClient.obj("actor", uuid))
                        .path("b").path("code").asText();
                assertTrue(c.req("account.bindUUID", TestClient.obj("code", code)).path("ok").asBoolean());
            }
        }
    }

    private static TestClient node() throws IOException {
        TestClient n = new TestClient(harness.nodePort);
        n.hello("node", "TestMod/1.0");
        assertNull(n.auth(harness.nodeToken));
        return n;
    }

    private static TestClient authedAs(String name) throws IOException {
        TestClient c = new TestClient(harness.appPort);
        c.hello("app", "TestApp/1.0");
        String token = c.req("account.login",
                TestClient.obj("name", "o15-" + name, "pass", "password-123"))
                .path("b").path("token").asText();
        assertNull(c.auth(token));
        return c;
    }

    private static String uuidOf(String name) {
        return UUID.nameUUIDFromBytes(("o15-" + name).getBytes()).toString();
    }

    private static JsonNode op(TestClient c, String uuid, String name, Object... kv) throws IOException {
        Object[] args = new Object[kv.length + 2];
        args[0] = "uuid";
        args[1] = uuid;
        System.arraycopy(kv, 0, args, 2, kv.length);
        return c.req(name, TestClient.obj(args));
    }

    @Test
    @Order(1)
    void meRegisterListShareQuery() throws IOException {
        String deviceId = UUID.randomUUID().toString();
        // 节点保持在线并订阅 ops 流（mod 启动即订阅；me.query 依赖）
        TestClient persistentNode = node();
        persistentNode.sub("ops", null);
        persistentNode.readPush();
        try (TestClient reg = node()) {
            assertTrue(reg.req("me.register", TestClient.obj(
                    "deviceId", deviceId, "owner", ownerUuid, "kind", "monitor")).path("ok").asBoolean());
        }
        // owner me.list：能看到自己设备
        try (TestClient owner = authedAs("owner")) {
            var list = op(owner, ownerUuid, "me.list");
            assertTrue(list.path("ok").asBoolean(), list.toString());
            assertEquals(1, list.path("b").path("devices").size());
            // owner 授权 super
            assertTrue(op(owner, ownerUuid, "me.share", "deviceId", deviceId,
                    "target", superUuid, "level", "control").path("ok").asBoolean());
            // super 查询（有共享）→ 挂起标记
            try (TestClient superC = authedAs("super")) {
                var q = op(superC, superUuid, "me.query", "deviceId", deviceId);
                assertTrue(q.path("ok").asBoolean(), q.toString());
                assertTrue(q.path("b").path("pending").asBoolean());
            }
            // 无关账号查询 → FORBIDDEN
            try (TestClient stranger = freshStranger()) {
                String strangerUuid = UUID.randomUUID().toString();
                assertEquals("FORBIDDEN", op(stranger, strangerUuid, "me.query",
                        "deviceId", deviceId).path("m").asText());
            }
            // unshare 后 super 查询 FORBIDDEN
            assertTrue(op(owner, ownerUuid, "me.unshare", "deviceId", deviceId,
                    "target", superUuid).path("ok").asBoolean());
            try (TestClient superC = authedAs("super")) {
                assertEquals("FORBIDDEN", op(superC, superUuid, "me.query",
                        "deviceId", deviceId).path("m").asText());
            }
        }
        try (TestClient reg = node()) {
            assertTrue(reg.req("me.unregister", TestClient.obj("deviceId", deviceId)).path("ok").asBoolean());
        }
        persistentNode.close();
    }

    private TestClient freshStranger() throws IOException {
        String name = "o15-stranger-" + (System.currentTimeMillis() % 1000000);
        TestClient c = new TestClient(harness.appPort);
        c.hello("app", "TestApp/1.0");
        var reg = c.req("account.register",
                TestClient.obj("name", name, "pass", "password-123"));
        assertTrue(reg.path("ok").asBoolean(), reg.toString());
        assertNull(c.auth(reg.path("b").path("token").asText()));
        return c;
    }

    @Test
    @Order(2)
    void metricsReportAndNetworkSnapshot() throws IOException {
        try (TestClient n = node()) {
            assertTrue(n.req("metrics.report", TestClient.obj(
                    "mspt", 12, "max", 30, "tps", 20, "players", 3)).path("ok").asBoolean());
        }
        try (TestClient superC = authedAs("super")) {
            var snap = op(superC, superUuid, "metrics.network");
            assertTrue(snap.path("ok").asBoolean(), snap.toString());
            JsonNode node0 = snap.path("b").path("nodes").get(0);
            assertEquals(1, node0.path("nodeId").asLong());
            assertFalse(node0.path("offline").asBoolean());
            assertEquals(12, node0.path("frame").path("mspt").asInt(), 0.001);
        }
    }

    @Test
    @Order(3)
    void rankSyncDailyLimit() throws IOException {
        try (TestClient superC = authedAs("super")) {
            var first = op(superC, superUuid, "rank.sync", "nodeId", 1);
            assertTrue(first.path("ok").asBoolean(), first.toString());
            assertTrue(first.path("b").path("queued").asBoolean());
            // 当天第二次 → RATE_LIMITED
            assertEquals("RATE_LIMITED", op(superC, superUuid, "rank.sync", "nodeId", 1)
                    .path("m").asText().split(":")[0]);
        }
        // 普通账号替别人发起 → FORBIDDEN
        try (TestClient owner = authedAs("owner")) {
            // owner 现为 user（未重置）——本人对自己 sync 允许
            var own = op(owner, ownerUuid, "rank.sync", "nodeId", 1);
            assertTrue(own.path("ok").asBoolean(), own.toString());
        }
    }

    @Test
    @Order(4)
    void bqSyncStartQueuesBothSides() throws IOException {
        // 中央建一支队伍（owner）
        try (TestClient owner = authedAs("owner")) {
            assertTrue(op(owner, ownerUuid, "team.create", "name", "bq队").path("ok").asBoolean());
        }
        long teamId = 1;
        try (TestClient superC = authedAs("super")) {
            var start = op(superC, superUuid, "bq.sync.start",
                    "teamId", teamId, "sourceNode", 1, "targetNode", 1);
            assertTrue(start.path("ok").asBoolean(), start.toString());
            assertFalse(start.path("b").path("batchId").asText().isEmpty());
            // 快照已建
            assertTrue(!harness.ctx.db.query("SELECT snapshot_id FROM bq_snapshots").isEmpty());

        }
    }
}
