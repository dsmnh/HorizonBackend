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

/** 账号域流程（契约 §6、登记表 §4.1）：注册/登录/锁定/会话/绑定码/恢复码。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountFlowTest {
    private static ServerHarness harness;

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
    }

    @AfterAll
    static void tearDown() {
        harness.close();
    }

    private static TestClient app() throws IOException {
        TestClient c = new TestClient(harness.appPort);
        c.hello("app", "TestApp/1.0");
        return c;
    }

    private JsonNode register(TestClient c, String name, String pass) throws IOException {
        return c.req("account.register", TestClient.obj("name", name, "pass", pass));
    }

    private JsonNode login(TestClient c, String name, String pass) throws IOException {
        return c.req("account.login", TestClient.obj("name", name, "pass", pass));
    }

    private String gameUuid(String tag) {
        return UUID.nameUUIDFromBytes(tag.getBytes()).toString();
    }

    /** 经节点平面为 UUID 签发绑定码（模拟游戏内出码）。 */
    private String issueBindCode(String uuid) throws IOException {
        try (TestClient n = node()) {
            var res = n.req("node.bindCode.create", TestClient.obj("actor", uuid));
            assertTrue(res.path("ok").asBoolean(), res.toString());
            return res.path("b").path("code").asText();
        }
    }

    private static TestClient node() throws IOException {
        TestClient c = new TestClient(harness.nodePort);
        c.hello("node", "TestMod/1.0");
        assertNull(c.auth(harness.nodeToken));
        return c;
    }

    @Test
    @Order(1)
    void registerAndLogin() throws IOException {
        try (TestClient c = app()) {
            var res = register(c, "alice", "password-123");
            assertTrue(res.path("ok").asBoolean(), res.toString());
            assertEquals(43, res.path("b").path("token").asText().length());
        }
        try (TestClient c = app()) {
            var login = login(c, "alice", "password-123");
            assertTrue(login.path("ok").asBoolean(), login.toString());
            assertTrue(login.path("b").path("uuids").isArray());
        }
    }

    @Test
    @Order(2)
    void registerValidation() throws IOException {
        try (TestClient c = app()) {
            assertEquals("NAME_TAKEN", register(c, "alice", "password-123").path("m").asText());
            assertEquals("BAD_NAME", register(c, "a", "password-123").path("m").asText());
            assertEquals("BAD_NAME", register(c, "has space", "password-123").path("m").asText());
            assertEquals("BAD_TEXT", register(c, "bob", "short").path("m").asText());
            assertEquals("BAD_PARAMS:name",
                    c.req("account.register", TestClient.obj("pass", "password-123")).path("m").asText());
        }
    }

    @Test
    @Order(3)
    void loginLockoutAfterConsecutiveFailures() throws IOException {
        try (TestClient c = app()) {
            register(c, "carol", "password-123");
            for (int i = 0; i < 5; i++) {
                assertEquals("BAD_CREDENTIALS", login(c, "carol", "wrong-password").path("m").asText());
            }
            JsonNode locked = login(c, "carol", "password-123");
            assertTrue(locked.path("m").asText().startsWith("LOGIN_LOCKED:"),
                    "应为 LOGIN_LOCKED:<秒>，实际 " + locked);
        }
    }

    @Test
    @Order(4)
    void bindUUIDFlow() throws IOException {
        String uuid = gameUuid("bind-flow");
        try (TestClient c = app()) {
            String token = login(c, "alice", "password-123").path("b").path("token").asText();
            try (TestClient a = app()) {
                assertNull(a.auth(token));
                String code = issueBindCode(uuid);
                var bound = a.req("account.bindUUID", TestClient.obj("code", code));
                assertTrue(bound.path("ok").asBoolean(), bound.toString());
                assertEquals(uuid, bound.path("b").path("uuid").asText());

                // 码一次性：重复使用 → BIND_CODE_USED
                assertEquals("BIND_CODE_USED",
                        a.req("account.bindUUID", TestClient.obj("code", code)).path("m").asText());
                // 已绑到本账号，重复绑定幂等成功
                String code2 = issueBindCode(uuid);
                assertTrue(a.req("account.bindUUID", TestClient.obj("code", code2))
                        .path("ok").asBoolean());
                // 已绑定他账号的 UUID → UUID_BOUND
                try (TestClient b = app()) {
                    String erinToken = register(b, "erin", "password-123")
                            .path("b").path("token").asText();
                    assertNull(b.auth(erinToken));
                    String code3 = issueBindCode(uuid);
                    assertEquals("UUID_BOUND",
                            b.req("account.bindUUID", TestClient.obj("code", code3)).path("m").asText());
                }
                // 无效码
                assertEquals("BIND_CODE_INVALID",
                        a.req("account.bindUUID", TestClient.obj("code", "ZZZZ9999")).path("m").asText());
                var uuids = a.req("account.uuids", null);
                assertEquals(uuid, uuids.path("b").path("uuids").get(0).path("uuid").asText());
            }
        }
    }

    @Test
    @Order(5)
    void sessionsAndRevoke() throws IOException {
        String token1;
        try (TestClient c = app()) {
            token1 = login(c, "alice", "password-123").path("b").path("token").asText();
        }
        try (TestClient a = app()) {
            assertNull(a.auth(token1));
            String token2 = login(a, "alice", "password-123").path("b").path("token").asText();
            try (TestClient a3 = app()) {
                assertNull(a3.auth(token2));
                var sessions = a.req("account.sessions", null);
                JsonNode list = sessions.path("b").path("sessions");
                assertTrue(list.size() >= 2, list.toString());
                String otherId = null;
                for (JsonNode s : list) {
                    if (!s.path("current").asBoolean()) {
                        otherId = s.path("id").asText();
                    }
                }
                assertNotNull(otherId);
                assertTrue(a.req("account.revokeSession", TestClient.obj("id", otherId))
                        .path("ok").asBoolean());
                // 被注销会话的连接已被服务端断开
                assertThrows(IOException.class, () -> a3.req("account.uuids", null));
                // 注销不存在/他账号会话 → FORBIDDEN
                assertEquals("FORBIDDEN", a.req("account.revokeSession",
                        TestClient.obj("id", "no-such-session")).path("m").asText());
            }
        }
    }

    @Test
    @Order(6)
    void recoverCodeResetsPasswordAndRevokesSessions() throws IOException {
        String uuid = gameUuid("recover-flow");
        String tokenBefore;
        try (TestClient c = app()) {
            tokenBefore = login(c, "alice", "password-123").path("b").path("token").asText();
            try (TestClient a = app()) {
                assertNull(a.auth(tokenBefore));
                String code = issueBindCode(uuid);
                assertTrue(a.req("account.bindUUID", TestClient.obj("code", code)).path("ok").asBoolean());
            }
        }
        String recoverCode;
        try (TestClient n = node()) {
            var rec = n.req("node.recoverCode.create", TestClient.obj("actor", uuid));
            assertTrue(rec.path("ok").asBoolean(), rec.toString());
            recoverCode = rec.path("b").path("code").asText();
        }
        try (TestClient pre = app()) {
            assertEquals("RECOVER_CODE_INVALID", pre.req("account.resetPassword",
                    TestClient.obj("recoverCode", "ZZZZ9999", "newPassword", "newPassword-456")).path("m").asText());
            assertEquals("BAD_TEXT", pre.req("account.resetPassword",
                    TestClient.obj("recoverCode", recoverCode, "newPassword", "short")).path("m").asText());
            var reset = pre.req("account.resetPassword",
                    TestClient.obj("recoverCode", recoverCode, "newPassword", "newPassword-456"));
            assertTrue(reset.path("ok").asBoolean(), reset.toString());
            assertEquals("RECOVER_CODE_USED", pre.req("account.resetPassword",
                    TestClient.obj("recoverCode", recoverCode, "newPassword", "newPassword-456")).path("m").asText());
        }
        // 旧密码失效
        try (TestClient old = app()) {
            assertEquals("BAD_CREDENTIALS", login(old, "alice", "password-123").path("m").asText());
        }
        // 重置前签发的会话全部注销
        try (TestClient stale = app()) {
            assertEquals("SESSION_EXPIRED", stale.auth(tokenBefore));
        }
        try (TestClient c = app()) {
            String token = login(c, "alice", "newPassword-456").path("b").path("token").asText();
            try (TestClient a = app()) {
                assertNull(a.auth(token));
                assertTrue(a.req("account.uuids", null).path("ok").asBoolean());
            }
        }
    }

    @Test
    @Order(7)
    void recoverCodeRequiresBoundUuid() throws IOException {
        String unbound = gameUuid("unbound-recover");
        try (TestClient n = node()) {
            assertEquals("UUID_NOT_BOUND", n.req("node.recoverCode.create",
                    TestClient.obj("actor", unbound)).path("m").asText());
        }
    }
}
