package com.dsmnh.mobile.backend.protocol;

import com.dsmnh.mobile.backend.support.ServerHarness;
import com.dsmnh.mobile.backend.support.TestClient;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** me.query 轮询闭环（契约 1.5.0 §8.5）：入队→pending→节点回填→queryId 重查。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MeQueryPollingTest {
    private static ServerHarness harness;
    private static String ownerUuid;
    private static String ownerToken;
    private static String deviceId;

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
        ownerUuid = UUID.nameUUIDFromBytes("meq-owner".getBytes()).toString();
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "TestApp/1.0");
            var reg = c.req("account.register",
                    TestClient.obj("name", "meq-owner", "pass", "password-123"));
            assertTrue(reg.path("ok").asBoolean());
            ownerToken = reg.path("b").path("token").asText();
            assertNull(c.auth(ownerToken));
            try (TestClient n = node()) {
                String code = n.req("node.bindCode.create", TestClient.obj("actor", ownerUuid))
                        .path("b").path("code").asText();
                assertTrue(c.req("account.bindUUID", TestClient.obj("code", code)).path("ok").asBoolean());
            }
        }
        deviceId = UUID.randomUUID().toString();
        try (TestClient n = node()) {
            assertTrue(n.req("me.register", TestClient.obj(
                    "deviceId", deviceId, "owner", ownerUuid, "kind", "monitor")).path("ok").asBoolean());
        }
    }

    @AfterAll
    static void tearDown() {
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
                TestClient.obj("name", "meq-owner", "pass", "password-123"))
                .path("b").path("token").asText();
        assertNull(c.auth(token));
        return c;
    }

    @Test
    @Order(1)
    void meQueryPendingThenResult() throws IOException {
        try (TestClient owner = owner(); TestClient n = node()) {
            n.sub("ops", null);
            n.readPush(); // 全量

            // 1) 新查询 → pending:true + queryId
            var first = owner.req("me.query", TestClient.obj(
                    "uuid", ownerUuid, "deviceId", deviceId));
            assertTrue(first.path("ok").asBoolean(), first.toString());
            assertTrue(first.path("b").path("pending").asBoolean());
            String queryId = first.path("b").path("queryId").asText();
            assertFalse(queryId.isEmpty());

            // 2) 模拟节点回填结果
            ObjectNode resultPayload = harness.ctx.mapper.createObjectNode();
            resultPayload.put("queryId", queryId);
            resultPayload.set("result", harness.ctx.mapper.createObjectNode()
                    .put("items", "mock-data"));
            var resultRes = n.req("me.query.result", resultPayload);
            assertTrue(resultRes.path("ok").asBoolean(), resultRes.toString());

            // 3) 带 queryId 重查 → 返回结果（pending:false）
            var second = owner.req("me.query", TestClient.obj(
                    "deviceId", deviceId, "queryId", queryId));
            assertTrue(second.path("ok").asBoolean(), second.toString());
            assertFalse(second.path("b").path("pending").asBoolean());
            assertTrue(second.path("b").path("items").isObject() || second.path("b").path("items").isTextual(), "items 应有值");
        }
    }

    @Test
    @Order(2)
    void meQueryUnknownQueryId() throws IOException {
        try (TestClient owner = owner()) {
            var res = owner.req("me.query", TestClient.obj("queryId", "nonexistent-query-id"));
            assertEquals("BAD_PARAMS:queryId", res.path("m").asText());
        }
    }

    @Test
    @Order(3)
    void meQueryOfflineNode() throws IOException {
        // me_devices 注册在 node 1——但 node 1 的订阅断开（meRegisterListShareQuery 中的 persistentNode 已关）
        // 直接查询一个未注册设备的 node_id=999 → NODE_OFFLINE（因为 hasSubscriber 检查）
        // 先注册一个设备在 node 1（node 1 离线）
        String fakeDev = UUID.randomUUID().toString();
        harness.ctx.db.update("INSERT OR REPLACE INTO me_devices(device_id, node_id, owner, kind, created_at) "
                + "VALUES(?,?,?,?,0)", fakeDev, 1, ownerUuid, "monitor");
        try (TestClient owner = owner()) {
            var res = owner.req("me.query", TestClient.obj(
                    "uuid", ownerUuid, "deviceId", fakeDev));
            assertEquals("NODE_OFFLINE", res.path("m").asText());
        }
    }
}
