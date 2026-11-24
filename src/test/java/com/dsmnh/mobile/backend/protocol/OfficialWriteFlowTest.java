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

/**
 * 官方队伍受控写入（契约 1.6.6 §8.9）：后端负责前置校验、意图去重与下发，
 * 真正的名单变更由映射指向的官方节点在主线程调用 GTNHLib 动作完成。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OfficialWriteFlowTest {
    private static ServerHarness harness;
    private static TestClient app;
    private static String ownerUuid;
    private static String mateUuid;
    private static String memberUuid;
    private static final String OFFICIAL_A = "aaaa1111-1111-1111-1111-111111111111";
    private static final String OFFICIAL_B = "bbbb2222-2222-2222-2222-222222222222";
    private static final String WORLD = "minecraft:overworld";
    private static long teamId;

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
        ownerUuid = UUID.nameUUIDFromBytes("ow-owner".getBytes()).toString();
        mateUuid = UUID.nameUUIDFromBytes("ow-mate".getBytes()).toString();
        memberUuid = UUID.nameUUIDFromBytes("ow-member".getBytes()).toString();

        app = new TestClient(harness.appPort);
        app.hello("app", "TestApp/1.0");
        String token = app.req("account.register",
                TestClient.obj("name", "ow-owner", "pass", "password-123"))
                .path("b").path("token").asText();
        assertNull(app.auth(token));
        bindAppUuid(app, ownerUuid);
        // 普通成员也绑定账号：越权用例必须由「本人在官方队伍里但不是负责人」发起。
        try (TestClient member = new TestClient(harness.appPort)) {
            member.hello("app", "TestApp/1.0");
            String memberToken = member.req("account.register",
                    TestClient.obj("name", "ow-member", "pass", "password-123"))
                    .path("b").path("token").asText();
            assertNull(member.auth(memberToken));
            bindAppUuid(member, memberUuid);
        }

        JsonNode claim = app.req("owner.claim",
                TestClient.obj("code", harness.ctx.auth.issueOwnerCode("claim", 900)));
        assertTrue(claim.path("ok").asBoolean(), claim.toString());

        // 官方节点上报投影：甲队 owner=本人、officer=队友、member=普通成员；乙队只有队友一人。
        try (TestClient n = officialNode()) {
            JsonNode r = n.req("team.official.report", reportPayload(7, 3, true));
            assertTrue(r.path("b").path("accepted").asBoolean(), r.toString());
        }
        teamId = createTeam("写入映射队");
        JsonNode set = app.req("team.map.set", TestClient.obj("uuid", ownerUuid, "teamId", teamId,
                "nodeId", 1L, "worldRef", WORLD, "officialTeamId", OFFICIAL_A));
        assertTrue(set.path("ok").asBoolean(), set.toString());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (app != null) {
            app.close();
        }
        harness.close();
    }

    private static void bindAppUuid(TestClient client, String uuid) throws IOException {
        try (TestClient n = node()) {
            String code = n.req("node.bindCode.create", TestClient.obj("actor", uuid))
                    .path("b").path("code").asText();
            assertTrue(client.req("account.bindUUID", TestClient.obj("code", code))
                    .path("ok").asBoolean());
        }
    }

    private static TestClient node() throws IOException {
        TestClient n = new TestClient(harness.nodePort);
        n.hello("node", "TestMod/1.0");
        assertNull(n.auth(harness.nodeToken));
        return n;
    }

    private static TestClient officialNode() throws IOException {
        TestClient n = new TestClient(harness.nodePort);
        n.helloCaps("node", "TestMod/1.0", "gtnh");
        assertNull(n.auth(harness.nodeToken));
        return n;
    }

    private static ObjectNode reportPayload(long gen, long seq, boolean ready) {
        ObjectNode p = harness.ctx.mapper.createObjectNode();
        p.put("worldRef", WORLD);
        p.put("gen", gen);
        p.put("seq", seq);
        p.put("ready", ready);
        var teams = p.putArray("teams");
        ObjectNode a = teams.addObject();
        a.put("officialTeamId", OFFICIAL_A);
        a.put("name", "官方甲队");
        a.putArray("owners").add(ownerUuid);
        a.putArray("officers").add(mateUuid);
        a.putArray("members").add(ownerUuid).add(mateUuid).add(memberUuid);
        ObjectNode b = teams.addObject();
        b.put("officialTeamId", OFFICIAL_B);
        b.put("name", "官方乙队");
        b.putArray("owners").add(mateUuid);
        b.putArray("officers");
        b.putArray("members").add(mateUuid);
        return p;
    }

    private static ObjectNode inviteParams(String uuid, String target, String officialTeamId) {
        return TestClient.obj("uuid", uuid, "teamId", teamId, "nodeId", 1L,
                "worldRef", WORLD, "officialTeamId", officialTeamId, "targetUuid", target);
    }

    private static long createTeam(String name) {
        harness.ctx.db.update("INSERT INTO teams(name, join_mode, created_at) VALUES(?,?,?)",
                name, "invite", System.currentTimeMillis() / 1000);
        long id = harness.ctx.db.queryOne("SELECT id FROM teams WHERE name = ?", name)
                .map(row -> ((Number) row.get("id")).longValue()).orElseThrow();
        for (String[] member : new String[][]{{ownerUuid, "leader"}, {mateUuid, "member"}, {memberUuid, "member"}}) {
            harness.ctx.db.update("INSERT OR REPLACE INTO team_members(team_id, uuid, role, joined_at) "
                    + "VALUES(?,?,?,?)", id, member[0], member[1], 0);
        }
        return id;
    }

    private static long opCount(String type) {
        return harness.ctx.db.query("SELECT 1 FROM node_ops WHERE type = ?", type).size();
    }

    @Test
    @Order(1)
    void inviteEnqueuesOfficialWrite() throws IOException {
        long before = opCount("official.write");
        JsonNode res = app.req("team.official.invite", inviteParams(ownerUuid, memberUuid, OFFICIAL_A));
        assertTrue(res.path("ok").asBoolean(), res.toString());
        String opId = res.path("b").path("opId").asText();
        assertFalse(opId.isEmpty());
        assertFalse(res.path("b").path("deduped").asBoolean());

        var op = harness.ctx.db.queryOne(
                "SELECT node_id, type, payload FROM node_ops WHERE op_id = ?", opId).orElseThrow();
        assertEquals("official.write", op.get("type"));
        assertEquals(1, ((Number) op.get("node_id")).intValue());
        assertTrue(((String) op.get("payload")).contains("\"action\":\"invite\""), (String) op.get("payload"));

        // 同一意图未终结前重复提交：返回原 opId 并标记去重，不重复下发（契约 §8.9）。
        JsonNode again = app.req("team.official.invite", inviteParams(ownerUuid, memberUuid, OFFICIAL_A));
        assertTrue(again.path("ok").asBoolean(), again.toString());
        assertEquals(opId, again.path("b").path("opId").asText());
        assertTrue(again.path("b").path("deduped").asBoolean());
        assertEquals(before + 1, opCount("official.write"));
    }

    @Test
    @Order(2)
    void nonOfficerCannotInvite() throws IOException {
        // 普通成员在官方队伍里只是 members，预检必须拒绝（不产生 official.write）。
        try (TestClient member = new TestClient(harness.appPort)) {
            member.hello("app", "TestApp/1.0");
            String token = member.req("account.login",
                    TestClient.obj("name", "ow-member", "pass", "password-123"))
                    .path("b").path("token").asText();
            assertNull(member.auth(token));
            long before = opCount("official.write");
            JsonNode res = member.req("team.official.invite",
                    inviteParams(memberUuid, mateUuid, OFFICIAL_A));
            assertFalse(res.path("ok").asBoolean(), res.toString());
            assertTrue(res.path("m").asText().startsWith("FORBIDDEN"), res.toString());
            assertEquals(before, opCount("official.write"));
        }
    }

    @Test
    @Order(3)
    void acceptNeedsConfirmWhenMerge() throws IOException {
        // 队友当前独占乙队（memberCount=1）：TeamActions.onAccept 会触发合队，必须先确认。
        try (TestClient mate = new TestClient(harness.appPort)) {
            mate.hello("app", "TestApp/1.0");
            String token = mate.req("account.register",
                    TestClient.obj("name", "ow-mate", "pass", "password-123"))
                    .path("b").path("token").asText();
            assertNull(mate.auth(token));
            bindAppUuid(mate, mateUuid);

            ObjectNode params = TestClient.obj("uuid", mateUuid, "teamId", teamId, "nodeId", 1L,
                    "worldRef", WORLD, "officialTeamId", OFFICIAL_A);
            long before = opCount("official.write");
            JsonNode res = mate.req("team.official.accept", params);
            assertFalse(res.path("ok").asBoolean(), res.toString());
            assertTrue(res.path("m").asText().startsWith("NEEDS_CONFIRM"), res.toString());
            assertEquals(OFFICIAL_B, res.path("b").path("consumed").asText());
            assertEquals(OFFICIAL_A, res.path("b").path("surviving").asText());
            assertEquals(1, res.path("b").path("memberCount").asInt());
            // 未确认时不得下发任何指令。
            assertEquals(before, opCount("official.write"));

            params.put("confirm", true);
            JsonNode confirmed = mate.req("team.official.accept", params);
            assertTrue(confirmed.path("ok").asBoolean(), confirmed.toString());
            String opId = confirmed.path("b").path("opId").asText();
            assertEquals(1, harness.ctx.db.query(
                    "SELECT 1 FROM node_ops WHERE op_id = ? AND status = 'pending'", opId).size());
        }
    }

    @Test
    @Order(4)
    void projectionStaleBlocksWrite() throws IOException {
        harness.ctx.db.update("UPDATE official_world_state SET updated_at = 1 WHERE node_id = 1");
        JsonNode res = app.req("team.official.invite", inviteParams(ownerUuid, memberUuid, OFFICIAL_A));
        assertFalse(res.path("ok").asBoolean(), res.toString());
        assertTrue(res.path("m").asText().startsWith("PROJECTION_STALE"), res.toString());
        // 恢复投影供后续用例使用。
        harness.ctx.db.update("UPDATE official_world_state SET updated_at = ? WHERE node_id = 1",
                System.currentTimeMillis() / 1000);
    }

    @Test
    @Order(5)
    void nodeReportTerminatesWrite() throws IOException {
        JsonNode invite = app.req("team.official.invite", inviteParams(ownerUuid, memberUuid, OFFICIAL_A));
        String okOpId = invite.path("b").path("opId").asText();
        try (TestClient n = officialNode()) {
            JsonNode ack = n.req("official.write.report",
                    TestClient.obj("opId", okOpId, "ok", true, "gen", 7L, "seq", 9L));
            assertTrue(ack.path("ok").asBoolean(), ack.toString());
            assertEquals("done", harness.ctx.db.queryOne(
                    "SELECT state FROM official_writes WHERE op_id = ?", okOpId)
                    .map(row -> (String) row.get("state")).orElseThrow());
            assertEquals("done", harness.ctx.db.queryOne(
                    "SELECT status FROM node_ops WHERE op_id = ?", okOpId)
                    .map(row -> (String) row.get("status")).orElseThrow());

            // 终结性错误：不重试、置终结并入审计（契约 §8.9）。
            String offlineUuid = UUID.nameUUIDFromBytes("ow-offline".getBytes()).toString();
            JsonNode second = app.req("team.official.invite",
                    inviteParams(ownerUuid, offlineUuid, OFFICIAL_A));
            String failOpId = second.path("b").path("opId").asText();
            JsonNode fail = n.req("official.write.report",
                    TestClient.obj("opId", failOpId, "ok", false, "error", "PLAYER_OFFLINE"));
            assertTrue(fail.path("ok").asBoolean(), fail.toString());
            assertEquals("terminal", harness.ctx.db.queryOne(
                    "SELECT state FROM official_writes WHERE op_id = ?", failOpId)
                    .map(row -> (String) row.get("state")).orElseThrow());
            assertEquals("abandoned", harness.ctx.db.queryOne(
                    "SELECT status FROM node_ops WHERE op_id = ?", failOpId)
                    .map(row -> (String) row.get("status")).orElseThrow());
            assertFalse(harness.ctx.db.query("SELECT 1 FROM audit_log WHERE action = ?",
                    "official.write.rejected").isEmpty(), "终结性错误应写审计");
        }
    }

    @Test
    @Order(6)
    void writeRequiresConfirmedMapping() throws IOException {
        JsonNode res = app.req("team.official.invite", inviteParams(ownerUuid, memberUuid, OFFICIAL_B));
        assertFalse(res.path("ok").asBoolean(), res.toString());
        assertTrue(res.path("m").asText().startsWith("MAP_CONFLICT"), res.toString());
    }
}
