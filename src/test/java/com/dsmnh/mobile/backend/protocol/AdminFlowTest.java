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

/** 管理域流程（契约 1.1.0、登记表 §4.4）：服主认领/转移、超管管理、玩家面板、队伍代管。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminFlowTest {
    private static ServerHarness harness;
    private static M alice;
    private static M bob;
    private static M carol;

    private record M(TestClient c, String uuid, String name) {}

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
        alice = bind("alice");
        bob = bind("bob");
        carol = bind("carol");
    }

    @AfterAll
    static void tearDown() {
        harness.close();
    }

    private static M bind(String name) throws IOException {
        String uuid = UUID.nameUUIDFromBytes(("af-" + name).getBytes()).toString();
        TestClient c = new TestClient(harness.appPort);
        c.hello("app", "TestApp/1.0");
        String token = c.req("account.register",
                TestClient.obj("name", "af-" + name, "pass", "password-123"))
                .path("b").path("token").asText();
        assertNull(c.auth(token));
        try (TestClient n = node()) {
            String code = n.req("node.bindCode.create", TestClient.obj("actor", uuid))
                    .path("b").path("code").asText();
            assertTrue(c.req("account.bindUUID", TestClient.obj("code", code)).path("ok").asBoolean());
        }
        return new M(c, uuid, "af-" + name);
    }

    private static TestClient node() throws IOException {
        TestClient n = new TestClient(harness.nodePort);
        n.hello("node", "TestMod/1.0");
        assertNull(n.auth(harness.nodeToken));
        return n;
    }

    private JsonNode op(M m, String name, Object... kv) throws IOException {
        Object[] args = new Object[kv.length + 2];
        args[0] = "uuid";
        args[1] = m.uuid();
        System.arraycopy(kv, 0, args, 2, kv.length);
        return m.c().req(name, TestClient.obj(args));
    }

    private TestClient relogin(M m) throws IOException {
        TestClient c = new TestClient(harness.appPort);
        c.hello("app", "TestApp/1.0");
        String token = c.req("account.login",
                TestClient.obj("name", m.name(), "pass", "password-123"))
                .path("b").path("token").asText();
        JsonNode ok = c.authNode(token);
        assertFalse(ok.has("code"), ok.toString());
        return c;
    }

    @Test
    @Order(1)
    void authOkCarriesRole() throws IOException {
        try (TestClient c = relogin(alice)) {
            assertEquals("user", c.lastAuthOk.path("role").asText());
        }
    }

    @Test
    @Order(2)
    void ownerClaimFlow() throws IOException {
        String claim = harness.ctx.auth.issueOwnerCode("claim", 900);
        // 未绑定 UUID 的账号不能认领：注册一个不绑定的账号
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "TestApp/1.0");
            String token = c.req("account.register",
                    TestClient.obj("name", "af-nobind", "pass", "password-123"))
                    .path("b").path("token").asText();
            assertNull(c.auth(token));
            assertEquals("FORBIDDEN", c.req("owner.claim", TestClient.obj("code", claim))
                    .path("m").asText());
        }
        // 错码
        assertEquals("CLAIM_CODE_INVALID", op(alice, "owner.claim", "code", "ZZZZ9999").path("m").asText());
        // alice 认领成功
        assertEquals("owner", op(alice, "owner.claim", "code", claim).path("b").path("role").asText());
        // 角色即时生效：重连后 AUTH_OK 携带 owner
        try (TestClient c = relogin(alice)) {
            assertEquals("owner", c.lastAuthOk.path("role").asText());
            assertTrue(c.req("admin.uuids", null).path("ok").asBoolean());
        }
        // 已有服主 → OWNER_EXISTS；同码再用 → CLAIM_CODE_USED
        assertEquals("OWNER_EXISTS", op(bob, "owner.claim",
                "code", harness.ctx.auth.issueOwnerCode("claim", 900)).path("m").asText());
        assertEquals("CLAIM_CODE_USED", op(bob, "owner.claim", "code", claim).path("m").asText());
    }

    @Test
    @Order(3)
    void superManagement() throws IOException {
        // 非服主不能授予
        assertEquals("FORBIDDEN", op(bob, "admin.grantSuper", "uuid", carol.uuid()).path("m").asText());
        // 服主授予 bob
        assertEquals("super", op(alice, "admin.grantSuper", "uuid", bob.uuid())
                .path("b").path("role").asText());
        // 幂等授予
        assertEquals("super", op(alice, "admin.grantSuper", "uuid", bob.uuid())
                .path("b").path("role").asText());
        // 未知 uuid
        assertEquals("BAD_PARAMS:uuid", op(alice, "admin.grantSuper",
                "uuid", UUID.randomUUID().toString()).path("m").asText());
        // super 不能动服主
        assertEquals("FORBIDDEN", op(bob, "admin.revokeSuper", "uuid", alice.uuid()).path("m").asText());
        // super 可用玩家面板
        assertTrue(op(bob, "admin.uuids").path("ok").asBoolean());
        // 服主撤销 super
        assertEquals("user", op(alice, "admin.revokeSuper", "uuid", bob.uuid())
                .path("b").path("role").asText());
        assertEquals("FORBIDDEN", op(bob, "admin.uuids").path("m").asText());
    }

    @Test
    @Order(4)
    void ownerRecoverTransfers() throws IOException {
        String recover = harness.ctx.auth.issueOwnerCode("recover", 900);
        assertEquals("owner", op(bob, "owner.recover", "code", recover).path("b").path("role").asText());
        // 原服主降为 user 且失去管理权
        assertEquals("FORBIDDEN", op(alice, "admin.uuids").path("m").asText());
        // 新服主把原服主提为 super，保持管理能力可供后续用例
        assertEquals("super", op(bob, "admin.grantSuper", "uuid", alice.uuid())
                .path("b").path("role").asText());
    }

    @Test
    @Order(5)
    void playerPanelAndTeamOverride() throws IOException {
        long teamId = op(carol, "team.create", "name", "admin-e2e队").path("b").path("teamId").asLong();
        // alice（super）面板解绑 carol 的 UUID
        var bindings = op(alice, "admin.uuids").path("b").path("bindings");
        assertTrue(bindings.size() >= 3, bindings.toString());
        assertTrue(op(alice, "admin.unbindUUID", "uuid", carol.uuid()).path("ok").asBoolean());
        // 解绑后 carol 的身份失效
        assertEquals("FORBIDDEN", op(carol, "team.info").path("m").asText());
        // super 代管：以自己身份对别人队伍踢人（carol 已不在队，改用 transfer 验证）
        // 重新绑定 carol 后继续
        try (TestClient n = node()) {
            String code = n.req("node.bindCode.create", TestClient.obj("actor", carol.uuid()))
                    .path("b").path("code").asText();
            assertTrue(carol.c().req("account.bindUUID", TestClient.obj("code", code)).path("ok").asBoolean());
        }
        // carol 回到队里？解绑不清队伍成员关系——team.info 按队查询确认 carol 仍是队长
        JsonNode team = carol.c().req("team.info", TestClient.obj("teamId", teamId))
                .path("b").path("team");
        assertEquals(carol.uuid(), team.path("members").get(0).path("uuid").asText());
        // super 代管解散别人队伍（p.teamId 指定目标队；owner bob 亦可）
        assertTrue(op(alice, "team.disband", "teamId", teamId).path("ok").asBoolean());
        assertEquals("TEAM_NOT_FOUND", carol.c().req("team.info",
                TestClient.obj("teamId", teamId)).path("m").asText());
    }
}
