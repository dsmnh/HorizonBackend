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

/** 队伍域全流程（登记表 §4.2）：创建/邀请/申请/角色/转让/解散与权限边界。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TeamFlowTest {
    private static ServerHarness harness;
    private static M alice;
    private static M bob;
    private static M carol;
    private static M dave;

    private record M(TestClient c, String uuid) {}

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
        alice = bind("alice");
        bob = bind("bob");
        carol = bind("carol");
        dave = bind("dave");
    }

    @AfterAll
    static void tearDown() {
        harness.close();
    }

    /** 注册账号、绑定 UUID，返回一条已鉴权连接。 */
    private static M bind(String name) throws IOException {
        String uuid = UUID.nameUUIDFromBytes(("tf-" + name).getBytes()).toString();
        TestClient c = new TestClient(harness.appPort);
        c.hello("app", "TestApp/1.0");
        String token = c.req("account.register",
                TestClient.obj("name", "tf-" + name, "pass", "password-123"))
                .path("b").path("token").asText();
        assertNull(c.auth(token));
        try (TestClient n = node()) {
            String code = n.req("node.bindCode.create", TestClient.obj("actor", uuid))
                    .path("b").path("code").asText();
            assertTrue(c.req("account.bindUUID", TestClient.obj("code", code)).path("ok").asBoolean());
        }
        return new M(c, uuid);
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

    private long createTeam(M m, String name) throws IOException {
        var res = op(m, "team.create", "name", name);
        assertTrue(res.path("ok").asBoolean(), res.toString());
        return res.path("b").path("teamId").asLong();
    }

    private long teamIdOf(M m) throws IOException {
        var res = op(m, "team.info");
        assertTrue(res.path("ok").asBoolean(), res.toString());
        return res.path("b").path("team").path("teamId").asLong();
    }

    @Test
    @Order(1)
    void createInfoList() throws IOException {
        long alpha = createTeam(alice, "alpha");
        assertEquals("TEAM_NAME_TAKEN", op(bob, "team.create", "name", "alpha").path("m").asText());
        long beta = createTeam(bob, "beta");
        assertNotEquals(alpha, beta);
        // 在队者不能再创建
        assertEquals("ALREADY_IN_TEAM", op(bob, "team.create", "name", "gamma").path("m").asText());

        var info = alice.c().req("team.info", TestClient.obj("teamId", alpha));
        JsonNode team = info.path("b").path("team");
        assertEquals("alpha", team.path("name").asText());
        assertEquals("invite", team.path("joinMode").asText());
        assertEquals(alice.uuid(), team.path("members").get(0).path("uuid").asText());
        assertEquals("leader", team.path("members").get(0).path("role").asText());

        var byUuid = op(bob, "team.info");
        assertEquals(beta, byUuid.path("b").path("team").path("teamId").asLong());

        var list = op(carol, "team.list");
        assertTrue(list.path("b").path("teams").size() >= 2, list.toString());
        // 冒用他人身份 → FORBIDDEN
        assertEquals("FORBIDDEN", alice.c().req("team.create",
                TestClient.obj("uuid", bob.uuid(), "name", "steal")).path("m").asText());
        // 队名含格式符 → BAD_NAME
        assertEquals("BAD_NAME", op(alice, "team.create", "name", "bad§name").path("m").asText());
        // 缺参数
        assertEquals("BAD_PARAMS:name", op(alice, "team.create").path("m").asText());
    }

    @Test
    @Order(2)
    void inviteAndAccept() throws IOException {
        long alpha = teamIdOf(alice);
        // 邀请已在队目标 → ALREADY_IN_TEAM
        assertEquals("ALREADY_IN_TEAM", op(alice, "team.invite", "target", bob.uuid()).path("m").asText());
        // 邀请目标格式非法
        assertEquals("BAD_PARAMS:target", op(alice, "team.invite", "target", "bad-format").path("m").asText());
        // 非队员发起邀请
        assertEquals("NOT_IN_TEAM", op(dave, "team.invite", "target", carol.uuid()).path("m").asText());

        var inv = op(alice, "team.invite", "target", carol.uuid());
        assertTrue(inv.path("ok").asBoolean(), inv.toString());
        long inviteId = inv.path("b").path("inviteId").asLong();
        // 幂等：重复邀请返回原邀请
        assertEquals(inviteId, op(alice, "team.invite", "target", carol.uuid())
                .path("b").path("inviteId").asLong());

        assertTrue(op(carol, "team.acceptInvite", "inviteId", inviteId).path("ok").asBoolean());
        // 已接受后再接受 → NO_INVITE
        assertEquals("NO_INVITE", op(carol, "team.acceptInvite", "inviteId", inviteId).path("m").asText());
        assertEquals(alpha, teamIdOf(carol));

        // 持有效邀请后先行入队他队，再接受 → ALREADY_IN_TEAM
        var invDave = op(alice, "team.invite", "target", dave.uuid());
        assertTrue(invDave.path("ok").asBoolean());
        assertTrue(op(bob, "team.setJoinMode", "mode", "open").path("ok").asBoolean());
        var applied = op(dave, "team.apply", "teamId", teamIdOf(bob));
        assertTrue(applied.path("ok").asBoolean());
        assertTrue(op(bob, "team.approveApply",
                "applyId", applied.path("b").path("applyId").asLong()).path("ok").asBoolean());
        assertEquals("ALREADY_IN_TEAM", op(dave, "team.acceptInvite",
                "inviteId", invDave.path("b").path("inviteId").asLong()).path("m").asText());
        // dave 离开 beta，恢复原状
        assertTrue(op(dave, "team.leave").path("ok").asBoolean());
        // dave 拒绝 alpha 的旧邀请
        assertTrue(op(dave, "team.declineInvite",
                "inviteId", invDave.path("b").path("inviteId").asLong()).path("ok").asBoolean());
        assertEquals("NO_INVITE", op(dave, "team.declineInvite",
                "inviteId", invDave.path("b").path("inviteId").asLong()).path("m").asText());
    }

    @Test
    @Order(3)
    void applyFlow() throws IOException {
        long alpha = teamIdOf(alice);
        // 邀请制队伍不接受申请
        assertEquals("TEAM_NOT_OPEN", op(dave, "team.apply", "teamId", alpha).path("m").asText());
        assertTrue(op(alice, "team.setJoinMode", "mode", "open").path("ok").asBoolean());
        var applied = op(dave, "team.apply", "teamId", alpha);
        assertTrue(applied.path("ok").asBoolean(), applied.toString());
        long applyId = applied.path("b").path("applyId").asLong();
        // 幂等申请
        assertEquals(applyId, op(dave, "team.apply", "teamId", alpha)
                .path("b").path("applyId").asLong());
        // 不存在的队伍
        assertEquals("TEAM_NOT_FOUND", op(dave, "team.apply", "teamId", 999999).path("m").asText());
        // 他队队长无法审批本队申请（applyId 不属其队 → NO_APPLY）
        assertEquals("NO_APPLY", op(bob, "team.approveApply", "applyId", applyId).path("m").asText());
        // 普通成员无审批权（carol 此时仍是成员）
        assertEquals("FORBIDDEN", op(carol, "team.approveApply", "applyId", applyId).path("m").asText());
        assertTrue(op(alice, "team.approveApply", "applyId", applyId).path("ok").asBoolean());
        assertEquals(alpha, teamIdOf(dave));
        // 审批后再审 → NO_APPLY
        assertEquals("NO_APPLY", op(alice, "team.approveApply", "applyId", applyId).path("m").asText());
    }

    @Test
    @Order(4)
    void approveTerminatesWhenApplicantJoinedElsewhere() throws IOException {
        long alpha = teamIdOf(alice);
        long beta = teamIdOf(bob);
        // dave 离开 alpha，向 beta 申请；期间受邀加入 alpha
        assertTrue(op(dave, "team.leave").path("ok").asBoolean());
        var applied = op(dave, "team.apply", "teamId", beta);
        assertTrue(applied.path("ok").asBoolean());
        long applyId = applied.path("b").path("applyId").asLong();
        var inv = op(alice, "team.invite", "target", dave.uuid());
        assertTrue(inv.path("ok").asBoolean());
        assertTrue(op(dave, "team.acceptInvite",
                "inviteId", inv.path("b").path("inviteId").asLong()).path("ok").asBoolean());
        // 审批时申请人已在别队 → ALREADY_IN_TEAM 且申请终结
        assertEquals("ALREADY_IN_TEAM",
                op(bob, "team.approveApply", "applyId", applyId).path("m").asText());
        assertEquals(alpha, teamIdOf(dave));
        assertEquals("NO_APPLY", op(bob, "team.approveApply", "applyId", applyId).path("m").asText());
    }

    @Test
    @Order(5)
    void rolesKickAndBoundaries() throws IOException {
        // bob（beta 队长）无法操作 alpha 的成员
        assertEquals("NOT_IN_TEAM", op(bob, "team.setRole",
                "target", carol.uuid(), "role", "vice").path("m").asText());
        // alice 提升 carol 为副队长
        assertTrue(op(alice, "team.setRole", "target", carol.uuid(), "role", "vice").path("ok").asBoolean());
        // 副队长可以移出成员 dave
        assertTrue(op(carol, "team.kick", "target", dave.uuid()).path("ok").asBoolean());
        // 副队长不能动队长
        assertEquals("FORBIDDEN", op(carol, "team.kick", "target", alice.uuid()).path("m").asText());
        assertEquals("FORBIDDEN", op(carol, "team.setRole",
                "target", alice.uuid(), "role", "member").path("m").asText());
        // 非法角色值
        assertEquals("BAD_PARAMS:role", op(alice, "team.setRole",
                "target", carol.uuid(), "role", "leader").path("m").asText());
        // 移出不在队者
        assertEquals("NOT_IN_TEAM", op(alice, "team.kick", "target", dave.uuid()).path("m").asText());
        // 重新邀请 dave 入队备后续用例
        var inv = op(alice, "team.invite", "target", dave.uuid());
        assertTrue(op(dave, "team.acceptInvite",
                "inviteId", inv.path("b").path("inviteId").asLong()).path("ok").asBoolean());
    }

    @Test
    @Order(6)
    void leaveTransferDisband() throws IOException {
        long alpha = teamIdOf(alice);
        // 成员可以离队
        assertTrue(op(dave, "team.leave").path("ok").asBoolean());
        // 队长不能直接离队
        assertEquals("FORBIDDEN", op(alice, "team.leave").path("m").asText());
        // 副队长不能解散/转让
        assertEquals("FORBIDDEN", op(carol, "team.disband").path("m").asText());
        assertEquals("FORBIDDEN", op(carol, "team.transfer", "target", dave.uuid()).path("m").asText());
        // 转让给不在队者
        assertEquals("NOT_IN_TEAM", op(alice, "team.transfer", "target", dave.uuid()).path("m").asText());
        // bob 先解散 beta，再受邀加入 alpha
        long beta = teamIdOf(bob);
        assertTrue(op(bob, "team.disband").path("ok").asBoolean());
        assertEquals("NOT_IN_TEAM", op(bob, "team.info").path("m").asText());
        assertEquals("TEAM_NOT_FOUND", bob.c().req("team.info",
                TestClient.obj("teamId", beta)).path("m").asText());
        var invBob = op(alice, "team.invite", "target", bob.uuid());
        assertTrue(invBob.path("ok").asBoolean(), invBob.toString());
        assertTrue(op(bob, "team.acceptInvite",
                "inviteId", invBob.path("b").path("inviteId").asLong()).path("ok").asBoolean());
        // 转让队长
        assertTrue(op(alice, "team.transfer", "target", bob.uuid()).path("ok").asBoolean());
        var info = op(alice, "team.info");
        assertEquals("member", info.path("b").path("team").path("members").get(0).path("role").asText());
        // 原队长可离队
        assertTrue(op(alice, "team.leave").path("ok").asBoolean());
        // 新队长解散
        assertTrue(op(bob, "team.disband").path("ok").asBoolean());
        assertEquals("NOT_IN_TEAM", op(carol, "team.info").path("m").asText());
        assertEquals("TEAM_NOT_FOUND", bob.c().req("team.info",
                TestClient.obj("teamId", alpha)).path("m").asText());
    }
}
