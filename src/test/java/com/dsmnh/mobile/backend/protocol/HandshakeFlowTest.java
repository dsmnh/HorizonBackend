package com.dsmnh.mobile.backend.protocol;

import com.dsmnh.mobile.backend.support.ServerHarness;
import com.dsmnh.mobile.backend.support.TestClient;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** 握手与鉴权流程（契约 §3/§4）：版本、平面、签名、时钟、限流、跨平面凭证。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HandshakeFlowTest {
    private static ServerHarness harness;
    private static String token;

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
        // 注册一个账号取得会话 token，供跨平面/正常鉴权用例使用
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "TestApp/1.0");
            ObjectNode p = TestClient.obj("name", "tester", "pass", "password-123");
            var res = c.req("account.register", p);
            assertTrue(res.path("ok").asBoolean(), res.toString());
            token = res.path("b").path("token").asText();
        }
    }

    @AfterAll
    static void tearDown() {
        harness.close();
    }

    @Test
    @Order(1)
    void versionMismatch() throws IOException {
        try (TestClient c = new TestClient(harness.appPort)) {
            c.helloRaw("app", "TestApp/1.0", 2);
            var err = c.expectAny(com.dsmnh.mobile.backend.net.FrameType.ERR);
            assertEquals("VERSION_MISMATCH:server=1", err.path("m").asText());
        }
    }

    @Test
    @Order(2)
    void planeMismatch() throws IOException {
        try (TestClient c = new TestClient(harness.appPort)) {
            c.helloRaw("node", "TestMod/1.0", 1);
            var err = c.expectAny(com.dsmnh.mobile.backend.net.FrameType.ERR);
            assertEquals("BAD_PLANE", err.path("m").asText());
        }
    }

    @Test
    @Order(3)
    void capsAnnounced() throws IOException {
        try (TestClient c = new TestClient(harness.appPort)) {
            var ack = c.hello("app", "TestApp/1.0");
            assertTrue(ack.path("caps").toString().contains("account"));
            assertTrue(ack.path("caps").toString().contains("team"));
            assertEquals(1, ack.path("v").asInt());
            assertEquals(11, ack.path("nonce").asText().length());
        }
    }

    @Test
    @Order(4)
    void authRequiredBeforeAuth() throws IOException {
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "TestApp/1.0");
            var res = c.req("team.list", null);
            assertFalse(res.path("ok").asBoolean());
            assertEquals("AUTH_REQUIRED", res.path("m").asText());
        }
    }

    @Test
    @Order(5)
    void wrongSignature() throws IOException {
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "TestApp/1.0");
            assertEquals("AUTH_FAILED", c.authWith(token, "bm90LXRoZS1zaWc", System.currentTimeMillis() / 1000));
        }
    }

    @Test
    @Order(6)
    void unknownKid() throws IOException {
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "TestApp/1.0");
            assertEquals("AUTH_FAILED", c.auth("ZZZZZZZZ-unknown-token"));
        }
    }

    @Test
    @Order(7)
    void clockSkewRejected() throws IOException {
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "TestApp/1.0");
            long bad = System.currentTimeMillis() / 1000 - 3600;
            String fail = c.authWith(token, TestClient.sign(token, c.lastNonce, bad), bad);
            assertEquals("CLOCK", fail);
        }
    }

    @Test
    @Order(8)
    void authOkAndRequestsWork() throws IOException {
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "TestApp/1.0");
            assertNull(c.auth(token));
            var res = c.req("account.uuids", null);
            assertTrue(res.path("ok").asBoolean(), res.toString());
        }
    }

    @Test
    @Order(9)
    void crossPlaneTokenRejected() throws IOException {
        try (TestClient c = new TestClient(harness.nodePort)) {
            c.hello("node", "TestMod/1.0");
            assertEquals("AUTH_FAILED", c.auth(token));
        }
    }

    @Test
    @Order(10)
    void fiveFailuresCloseConnection() throws IOException {
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "TestApp/1.0");
            for (int i = 0; i < 5; i++) {
                assertEquals("AUTH_FAILED", c.authWith(token, "bm90LXRoZS1zaWc", System.currentTimeMillis() / 1000));
            }
            // 第 5 次失败后服务端已断开，继续读应得到 EOF
            assertThrows(IOException.class, () -> c.expectAny(
                    com.dsmnh.mobile.backend.net.FrameType.AUTH_FAIL));
        }
    }

    @Test
    @Order(11)
    void ipBanAfterTwentyFailures() throws IOException {
        harness.ctx.rateLimiter.reset();
        // 每连接 5 次失败后断开，4 条连接凑满 20 次
        for (int round = 0; round < 4; round++) {
            try (TestClient c = new TestClient(harness.appPort)) {
                c.hello("app", "TestApp/1.0");
                for (int i = 0; i < 5; i++) {
                    c.authWith(token, "bm90LXRoZS1zaWc", System.currentTimeMillis() / 1000);
                }
            } catch (IOException ignored) {
                // 服务端主动断开属预期
            }
        }
        // 封禁期内：AUTH → BANNED；预鉴权 REQ → RATE_LIMITED:auth
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "TestApp/1.0");
            assertEquals("BANNED", c.auth(token));
        }
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "TestApp/1.0");
            var res = c.req("account.login", TestClient.obj("name", "tester", "pass", "x"));
            assertEquals("RATE_LIMITED:auth", res.path("m").asText());
        }
        harness.ctx.rateLimiter.reset();
    }
}
