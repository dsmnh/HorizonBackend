package com.dsmnh.mobile.backend.protocol;

import com.dsmnh.mobile.backend.support.ServerHarness;
import com.dsmnh.mobile.backend.support.TestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 节点域流程（契约 §8、登记表 §4.3）：注册码兑换、恢复、代调用出码。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NodeFlowTest {
    private static ServerHarness harness;

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
    }

    @AfterAll
    static void tearDown() {
        harness.close();
    }

    @Test
    @Order(1)
    void registerOnlyOnNodePlane() throws IOException {
        String code = harness.ctx.auth.issueNodeCode("new", null, 900);
        try (TestClient app = new TestClient(harness.appPort)) {
            app.hello("app", "TestApp/1.0");
            // node.register 未在 App 平面登记 → UNKNOWN_OP
            assertEquals("UNKNOWN_OP", app.req("node.register",
                    TestClient.obj("code", code)).path("m").asText());
        }
    }

    @Test
    @Order(2)
    void registerWithInvalidAndUsedCode() throws IOException {
        try (TestClient n = new TestClient(harness.nodePort)) {
            n.hello("node", "TestMod/1.0");
            assertEquals("NODE_CODE_INVALID", n.req("node.register",
                    TestClient.obj("code", "ZZZZ9999")).path("m").asText());
            String code = harness.ctx.auth.issueNodeCode("new", null, 900);
            var ok = n.req("node.register", TestClient.obj("code", code, "nodeName", "二服"));
            assertTrue(ok.path("ok").asBoolean(), ok.toString());
            long nodeId = ok.path("b").path("nodeId").asLong();
            assertTrue(nodeId > 0);
            assertEquals("NODE_CODE_USED", n.req("node.register",
                    TestClient.obj("code", code)).path("m").asText());
            // 新节点凭证可用
            try (TestClient fresh = new TestClient(harness.nodePort)) {
                fresh.hello("node", "TestMod/1.0");
                assertNull(fresh.auth(ok.path("b").path("token").asText()));
                assertTrue(fresh.req("team.list", null).path("ok").asBoolean());
            }
        }
    }

    @Test
    @Order(3)
    void restoreRevokedNodeKeepsId() throws IOException {
        // 注册第二个节点再撤销
        String code = harness.ctx.auth.issueNodeCode("new", null, 900);
        long nodeId;
        String nodeToken;
        try (TestClient n = new TestClient(harness.nodePort)) {
            n.hello("node", "TestMod/1.0");
            var ok = n.req("node.register", TestClient.obj("code", code, "nodeName", "三服"));
            assertTrue(ok.path("ok").asBoolean());
            nodeId = ok.path("b").path("nodeId").asLong();
            nodeToken = ok.path("b").path("token").asText();
        }
        harness.ctx.auth.revokeNode(nodeId);
        // 撤销后凭证失效
        try (TestClient stale = new TestClient(harness.nodePort)) {
            stale.hello("node", "TestMod/1.0");
            assertEquals("NODE_REVOKED", stale.auth(nodeToken));
        }
        // 恢复码换新凭证，节点 ID 与数据不变
        String restoreCode = harness.ctx.auth.issueNodeCode("restore", nodeId, 900);
        try (TestClient n = new TestClient(harness.nodePort)) {
            n.hello("node", "TestMod/1.0");
            var ok = n.req("node.register", TestClient.obj("code", restoreCode));
            assertTrue(ok.path("ok").asBoolean(), ok.toString());
            assertEquals(nodeId, ok.path("b").path("nodeId").asLong());
            try (TestClient fresh = new TestClient(harness.nodePort)) {
                fresh.hello("node", "TestMod/1.0");
                assertNull(fresh.auth(ok.path("b").path("token").asText()));
            }
        }
    }

    @Test
    @Order(4)
    void gameCodeIssuanceInvalidatesOldCodes() throws IOException {
        String uuid = UUID.nameUUIDFromBytes("node-code-flow".getBytes()).toString();
        try (TestClient n = new TestClient(harness.nodePort)) {
            n.hello("node", "TestMod/1.0");
            assertNull(n.auth(harness.nodeToken));
            var first = n.req("node.bindCode.create", TestClient.obj("actor", uuid));
            assertTrue(first.path("ok").asBoolean());
            var second = n.req("node.bindCode.create", TestClient.obj("actor", uuid));
            assertTrue(second.path("ok").asBoolean());
            // 旧码被注销，新码可用
            try (TestClient app = new TestClient(harness.appPort)) {
                app.hello("app", "TestApp/1.0");
                String token = app.req("account.register",
                        TestClient.obj("name", "frank", "pass", "password-123"))
                        .path("b").path("token").asText();
                try (TestClient a = new TestClient(harness.appPort)) {
                    a.hello("app", "TestApp/1.0");
                    assertNull(a.auth(token));
                    assertEquals("BIND_CODE_INVALID", a.req("account.bindUUID",
                            TestClient.obj("code", first.path("b").path("code").asText())).path("m").asText());
                    assertTrue(a.req("account.bindUUID",
                            TestClient.obj("code", second.path("b").path("code").asText()))
                            .path("ok").asBoolean());
                }
            }
        }
    }

    @Test
    @Order(5)
    void accountOpsUnknownOnNodePlane() throws IOException {
        try (TestClient n = new TestClient(harness.nodePort)) {
            n.hello("node", "TestMod/1.0");
            assertNull(n.auth(harness.nodeToken));
            assertEquals("UNKNOWN_OP", n.req("account.uuids", null).path("m").asText());
            assertEquals("UNKNOWN_OP", n.req("account.login", null).path("m").asText());
        }
    }
}
