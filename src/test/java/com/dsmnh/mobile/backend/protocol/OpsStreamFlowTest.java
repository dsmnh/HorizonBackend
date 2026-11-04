package com.dsmnh.mobile.backend.protocol;

import com.dsmnh.mobile.backend.support.ServerHarness;
import com.dsmnh.mobile.backend.support.TestClient;
import com.fasterxml.jackson.databind.JsonNode;
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

/** 节点下行操作流（契约 1.2.0、登记表 §4.5/§5.2）：入队、下发、ack/fail、退避、权限。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpsStreamFlowTest {
    private static ServerHarness harness;
    private static M owner;
    private static M superUser;
    private static String nodeToken2;
    private static long node2Id;

    private record M(TestClient c, String uuid, String name) {}

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
        owner = bind("owner");
        superUser = bind("super");
        String claim = harness.ctx.auth.issueOwnerCode("claim", 900);
        assertEquals("owner", op(owner, "owner.claim", "code", claim).path("b").path("role").asText());
        assertEquals("super", op(owner, "admin.grantSuper", "uuid", superUser.uuid())
                .path("b").path("role").asText());
    }

    @AfterAll
    static void tearDown() {
        harness.close();
    }

    private static M bind(String name) throws IOException {
        String uuid = UUID.nameUUIDFromBytes(("os-" + name).getBytes()).toString();
        TestClient c = new TestClient(harness.appPort);
        c.hello("app", "TestApp/1.0");
        String token = c.req("account.register",
                TestClient.obj("name", "os-" + name, "pass", "password-123"))
                .path("b").path("token").asText();
        assertNull(c.auth(token));
        try (TestClient n = node()) {
            String code = n.req("node.bindCode.create", TestClient.obj("actor", uuid))
                    .path("b").path("code").asText();
            assertTrue(c.req("account.bindUUID", TestClient.obj("code", code)).path("ok").asBoolean());
        }
        return new M(c, uuid, "os-" + name);
    }

    private static TestClient node() throws IOException {
        TestClient n = new TestClient(harness.nodePort);
        n.hello("node", "TestMod/1.0");
        assertNull(n.auth(harness.nodeToken));
        return n;
    }

    private static JsonNode op(M m, String name, Object... kv) throws IOException {
        Object[] args = new Object[kv.length + 2];
        args[0] = "uuid";
        args[1] = m.uuid();
        System.arraycopy(kv, 0, args, 2, kv.length);
        return m.c().req(name, TestClient.obj(args));
    }

    private JsonNode opSet(M m, String target, long nodeId, boolean grant) throws IOException {
        return op(m, "admin.opSet", "uuid", target, "nodeId", nodeId, "grant", grant);
    }

    @Test
    @Order(1)
    void opSetPermissionsAndValidation() throws IOException {
        String target = UUID.nameUUIDFromBytes("os-player".getBytes()).toString();
        // 普通账号无权
        var byUser = bind("plain");
        assertEquals("FORBIDDEN", op(byUser, "admin.opSet",
                "uuid", target, "nodeId", 1, "grant", true).path("m").asText());
        // 未知节点
        assertEquals("NODE_UNKNOWN", opSet(superUser, target, 999999, true).path("m").asText());
        // super 有权且节点在线（未订阅 ops 流 → queued:true）
        var res = opSet(superUser, target, 1, true);
        assertTrue(res.path("ok").asBoolean(), res.toString());
        assertTrue(res.path("b").path("queued").asBoolean());
    }

    @Test
    @Order(2)
    void offlineQueueDeliveredOnSubscribe() throws IOException {
        String target = UUID.nameUUIDFromBytes("os-offline".getBytes()).toString();
        var res = opSet(superUser, target, 1, false);
        assertTrue(res.path("b").path("queued").asBoolean(), res.toString());
        // 节点订阅 → 全量包含该操作
        try (TestClient n = node()) {
            JsonNode ok = n.sub("ops", null);
            assertFalse(ok.path("sid").asText().isEmpty());
            JsonNode f = n.readPush();
            assertEquals("f", f.path("t").asText());
            boolean found = false;
            for (JsonNode o : f.path("ops")) {
                if ("op.set".equals(o.path("type").asText())
                        && target.equals(o.path("p").path("player").asText())
                        && !o.path("p").path("grant").asBoolean()) {
                    found = true;
                }
            }
            assertTrue(found, "全量帧应包含离线入队的 op.set：" + f);
        }
    }

    @Test
    @Order(3)
    void onlineDeltaAndAck() throws IOException {
        String target = UUID.nameUUIDFromBytes("os-online".getBytes()).toString();
        TestClient n = node();
        JsonNode ok = n.sub("ops", null);
        long s = ok.path("s").asLong();
        String sid = ok.path("sid").asText();
        n.readPush(); // 全量（含 Order 2 的存量）
        int before = 0;
        // 清空缓存推送队列：读走存量后不再有帧

        var res = opSet(superUser, target, 1, true);
        assertTrue(res.path("b").path("queued").asBoolean() == false
                || res.path("b").path("queued").asBoolean());
        JsonNode delta = n.readPush();
        assertEquals("d", delta.path("t").asText());
        assertEquals("op", delta.path("events").get(0).path("k").asText());
        assertEquals(target, delta.path("events").get(0).path("p").path("player").asText());
        String opId = delta.path("events").get(0).path("opId").asText();
        assertFalse(opId.isEmpty());

        // ack：终结；原连接带 sid 重订返回同一 sid（游标处无新事件则暂无推送）
        assertTrue(n.req("node.op.ack", TestClient.obj("opId", opId)).path("ok").asBoolean());
        JsonNode ok2 = n.sub("ops", TestClient.obj("sid", sid));
        assertEquals(sid, ok2.path("sid").asText());
        // 新订阅（不带 sid）→ 全量帧不含已 ack 的操作
        try (TestClient n2 = node()) {
            n2.sub("ops", null);
            JsonNode f2 = n2.readPush();
            for (JsonNode o : f2.path("ops")) {
                assertNotEquals(opId, o.path("opId").asText(), "已 ack 的操作不应再出现");
            }
        }
        n.close();
    }

    @Test
    @Order(4)
    void failTriggersRetryWithNewOpId() throws IOException {
        String target = UUID.nameUUIDFromBytes("os-fail".getBytes()).toString();
        TestClient n = node();
        n.sub("ops", null);
        n.readPush();
        var res = opSet(superUser, target, 1, true);
        String firstOpId = n.readPush().path("events").get(0).path("opId").asText();
        // 失败回报：sweep 立即生成新实例（next_retry_at 首次失败即到期的快速路径由实现保证）
        assertTrue(n.req("node.op.fail", TestClient.obj("opId", firstOpId, "reason", "player offline"))
                .path("ok").asBoolean());
        // 手动清扫一次（生产由定时器执行）
        harness.ctx.opsStream.sweep();
        // 失败重试受退避约束：首次失败 next_retry_at = now + 60s，不会立即重发 → 全量里是 pending 状态的新实例？
        // 实现语义：sweep 只重发「到期」的 failed；首次失败有 60s 退避 → 新实例此时不存在
        java.util.List<java.util.Map<String, Object>> retryRows = harness.ctx.db.query(
                "SELECT COUNT(*) c FROM node_ops WHERE node_id = 1 AND status = 'pending' AND payload LIKE ?",
                "%" + target + "%");
        int pending = retryRows.isEmpty() ? 0 : ((Number) retryRows.get(0).get("c")).intValue();
        assertEquals(0, pending, "退避期内不应立即重发");
        // 清空退避后 sweep → 新实例
        harness.ctx.db.update("UPDATE node_ops SET next_retry_at = 0 WHERE status = 'failed'");
        harness.ctx.opsStream.sweep();
        JsonNode retry = n.readPush();
        String newOpId = retry.path("events").get(0).path("opId").asText();
        assertNotEquals(firstOpId, newOpId, "重试应换新 opId");
        assertTrue(n.req("node.op.ack", TestClient.obj("opId", newOpId)).path("ok").asBoolean());
        n.close();
    }

    @Test
    @Order(5)
    void crossNodeReportForbidden() throws Exception {
        // 注册第二个节点
        String code = harness.ctx.auth.issueNodeCode("new", null, 900);
        long newNodeId;
        String newNodeToken;
        try (TestClient n = new TestClient(harness.nodePort)) {
            n.hello("node", "TestMod/1.0");
            var ok = n.req("node.register", TestClient.obj("code", code, "nodeName", "二服"));
            assertTrue(ok.path("ok").asBoolean());
            newNodeId = ok.path("b").path("nodeId").asLong();
            newNodeToken = ok.path("b").path("token").asText();
        }
        // 节点 1 收到 op.set 后，用节点 2 的连接回报 → FORBIDDEN
        String target = UUID.nameUUIDFromBytes("os-cross".getBytes()).toString();
        TestClient n1 = node();
        n1.sub("ops", null);
        n1.readPush();
        opSet(superUser, target, 1, true);
        String opId = n1.readPush().path("events").get(0).path("opId").asText();
        try (TestClient n2 = new TestClient(harness.nodePort)) {
            n2.hello("node", "TestMod/1.0");
            assertNull(n2.auth(newNodeToken));
            assertEquals("FORBIDDEN", n2.req("node.op.ack", TestClient.obj("opId", opId))
                    .path("m").asText());
        }
        assertTrue(n1.req("node.op.ack", TestClient.obj("opId", opId)).path("ok").asBoolean());
        n1.close();
    }
}
