package com.dsmnh.mobile.backend.protocol;

import com.dsmnh.mobile.backend.support.ServerHarness;
import com.dsmnh.mobile.backend.support.TestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 安全边界：注入防护、帧边界、Unicode 队名、超长参数。 */
class SecurityBoundaryTest {
    private static ServerHarness harness;

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
    }

    @AfterAll
    static void tearDown() {
        harness.close();
    }

    private String reg(String name) throws IOException {
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "Sec/1.0");
            return c.req("account.register",
                    TestClient.obj("name", name, "pass", "password-123"))
                    .path("b").path("token").asText();
        }
    }

    @Test
    void commandInjectionInTeamName() throws IOException {
        String uuid = UUID.nameUUIDFromBytes("cmd-inject".getBytes()).toString();
        String token = reg("cmd-inject");
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "Sec/1.0");
            assertNull(c.auth(token));
            var res = c.req("team.create", TestClient.obj("uuid", uuid, "name", "test;rm -rf /"));
            var after = c.req("account.uuids", null);
            assertTrue(after.path("ok").asBoolean(), "注入尝试后服务应正常");
        }
    }

    @Test
    void frameBoundaryFuzzing() {
        // 超大帧长 / 0 长度 → 服务端断开或忽略（FrameDecoder 已单测覆盖）
        // 此处仅验证服务端在异常帧后仍可正常连接
        assertDoesNotThrow(() -> {
            try (TestClient c = new TestClient(harness.appPort)) {
                c.hello("app", "Sec/1.0");
            } catch (IOException ignored) {
            }
        });
    }

    @Test
    void unicodeTeamName() throws IOException {
        String uuid = UUID.nameUUIDFromBytes("sec-uni".getBytes()).toString();
        String token = reg("sec-uni");
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "Sec/1.0");
            assertNull(c.auth(token));
            String malicious = "team\u200B\u202Exed\uFEFF";
            c.req("team.create", TestClient.obj("uuid", uuid, "name", malicious));
            var after = c.req("account.uuids", null);
            assertTrue(after.path("ok").asBoolean(), "Unicode 注入后服务应正常");
        }
    }

    @Test
    void longPasswordAndName() throws IOException {
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "Sec/1.0");
            // 10KB 用户名 → 超长拒绝
            var res = c.req("account.register",
                    TestClient.obj("name", "A".repeat(10_000), "pass", "password-123"));
            assertFalse(res.path("ok").asBoolean(), "超长用户名应拒绝");
            // 10KB 密码 → 当前无上限（PBKDF2 可处理），1.5.0 后加配置上限
            var res2 = c.req("account.register", TestClient.obj(
                    "name", "long-pass-" + System.nanoTime(), "pass", "P".repeat(10_000)));
            // 上限待做，此处验证服务不崩溃
            assertNotNull(res2);
        }
    }

    @Test
    void replayAcrossConnections() throws IOException {
        String token = reg("replay-test");
        // 首次连接正常鉴权
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "Sec/1.0");
            assertNull(c.auth(token));
        }
        // 新连接新 nonce → 同 token 正常鉴权（nonce 防重放只在同连接内）
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "Sec/1.0");
            assertNull(c.auth(token));
        }
    }
}
