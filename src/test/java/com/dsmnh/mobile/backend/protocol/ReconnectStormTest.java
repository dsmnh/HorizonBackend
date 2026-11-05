package com.dsmnh.mobile.backend.protocol;

import com.dsmnh.mobile.backend.support.ServerHarness;
import com.dsmnh.mobile.backend.support.TestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** 重连风暴限流：App 客户端反复连接/断开/鉴权失败时，同 IP 限流保护服务端（契约 §4.3）。 */
class ReconnectStormTest {
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
    void authStormTriggersIpBanThenRecovers() throws Exception {
        harness.ctx.rateLimiter.reset();
        // 注册账号取得真 token（风暴用假 token，恢复验证用真 token）
        String realToken;
        try (TestClient reg = new TestClient(harness.appPort)) {
            reg.hello("app", "Storm/1.0");
            realToken = reg.req("account.register",
                    TestClient.obj("name", "storm-user", "pass", "password-123"))
                    .path("b").path("token").asText();
        }
        // 4 条连接 × 5 次失败 = 20 次失败触发封禁
        for (int round = 0; round < 4; round++) {
            try (TestClient c = new TestClient(harness.appPort)) {
                c.hello("app", "Storm/1.0");
                for (int i = 0; i < 5; i++) {
                    c.authWith("BOGUSKEY1", "bogus", System.currentTimeMillis() / 1000);
                }
            } catch (IOException ignored) {
            }
        }
        // 封禁期内：新连接的 AUTH 应被拒
        boolean banned = false;
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "Storm/1.0");
            banned = "BANNED".equals(c.auth("tok-12345678-abcdefgh-ijkl"));
        } catch (IOException ignored) {
        }
        assertTrue(banned, "风暴后应进入 IP 封禁");
        harness.ctx.rateLimiter.reset();
        // 清除后恢复正常鉴权
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "Storm/1.0");
            assertNull(c.auth(realToken));
        }
    }

    @Test
    void rapidReconnectCyclesDoNotLeakConnections() throws Exception {
        harness.ctx.rateLimiter.reset();
        // 30 次快速连接-断开循环：服务端应无连接泄漏（都正常关闭）
        for (int i = 0; i < 30; i++) {
            TestClient c = new TestClient(harness.appPort);
            c.hello("app", "Storm/1.0");
            c.close();
        }
        // 服务端连接表为空（通过再注册一个正常连接验证服务存活）
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "Storm/1.0");
            var res = c.req("account.register",
                    TestClient.obj("name", "storm-ok", "pass", "password-123"));
            assertTrue(res.path("ok").asBoolean(), "循环后服务应正常");
        }
    }
}
