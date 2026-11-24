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
 * 官方队伍投影（契约 1.6.5 §8.8）：能力位 gtnh 的节点上报 GTNHLib 队伍快照，
 * 中央维护只读投影与人工映射；官方模式下不再下发 SU 队伍写入。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OfficialTeamsFlowTest {
    private static ServerHarness harness;
    private static TestClient app;
    private static String ownerUuid;
    private static String mateUuid;
    private static final String OFFICIAL_A = "11111111-1111-1111-1111-111111111111";
    private static final String OFFICIAL_B = "22222222-2222-2222-2222-222222222222";

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
        ownerUuid = UUID.nameUUIDFromBytes("of-owner".getBytes()).toString();
        mateUuid = UUID.nameUUIDFromBytes("of-mate".getBytes()).toString();
        app = new TestClient(harness.appPort);
        app.hello("app", "TestApp/1.0");
        String token = app.req("account.register",
                TestClient.obj("name", "of-owner", "pass", "password-123"))
                .path("b").path("token").asText();
        assertNull(app.auth(token));
        try (TestClient n = node()) {
            String code = n.req("node.bindCode.create", TestClient.obj("actor", ownerUuid))
                    .path("b").path("code").asText();
            assertTrue(app.req("account.bindUUID", TestClient.obj("code", code)).path("ok").asBoolean());
        }
        JsonNode claim = app.req("owner.claim",
                TestClient.obj("code", harness.ctx.auth.issueOwnerCode("claim", 900)));
        assertTrue(claim.path("ok").asBoolean(), claim.toString());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (app != null) {
            app.close();
        }
        harness.close();
    }

    /** 未声明能力位的普通节点（用于绑定码等非队伍操作）。 */
    private static TestClient node() throws IOException {
        TestClient n = new TestClient(harness.nodePort);
        n.hello("node", "TestMod/1.0");
        assertNull(n.auth(harness.nodeToken));
        return n;
    }

    /** 声明 gtnh 能力位的节点：鉴权后后端应把该节点标记为官方队伍模式。 */
    private static TestClient officialNode() throws IOException {
        TestClient n = new TestClient(harness.nodePort);
        n.helloCaps("node", "TestMod/1.0", "gtnh");
        assertNull(n.auth(harness.nodeToken));
        return n;
    }

    private static ObjectNode reportPayload(long gen, long seq, boolean ready) {
        ObjectNode p = harness.ctx.mapper.createObjectNode();
        p.put("worldRef", "minecraft:overworld");
        p.put("gen", gen);
        p.put("seq", seq);
        p.put("ready", ready);
        var teams = p.putArray("teams");
        ObjectNode a = teams.addObject();
        a.put("officialTeamId", OFFICIAL_A);
        a.put("name", "官方甲队");
        a.putArray("owners").add(ownerUuid);
        a.putArray("officers").add(mateUuid);
        a.putArray("members").add(ownerUuid).add(mateUuid);
        ObjectNode b = teams.addObject();
        b.put("officialTeamId", OFFICIAL_B);
        b.put("name", "官方乙队");
        b.putArray("owners").add(mateUuid);
        b.putArray("officers");
        b.putArray("members").add(mateUuid);
        return p;
    }

    @Test
    @Order(1)
    void capsMarksNodeOfficialMode() throws IOException {
        try (TestClient n = officialNode()) {
            var row = harness.ctx.db.queryOne("SELECT caps, official_mode FROM nodes WHERE id = 1");
            assertTrue(row.isPresent(), "节点应存在");
            assertEquals("gtnh", row.get().get("caps"));
            assertEquals(1, ((Number) row.get().get("official_mode")).intValue());
        }
    }

    @Test
    @Order(2)
    void reportProjectionAndStaleGuard() throws IOException {
        try (TestClient n = officialNode()) {
            JsonNode first = n.req("team.official.report", reportPayload(3, 10, true));
            assertTrue(first.path("ok").asBoolean(), first.toString());
            assertTrue(first.path("b").path("accepted").asBoolean(), first.toString());

            // 低代次整帧丢弃：既有投影保留（契约 §8.8）。
            JsonNode stale = n.req("team.official.report", reportPayload(2, 99, true));
            assertTrue(stale.path("ok").asBoolean(), stale.toString());
            assertFalse(stale.path("b").path("accepted").asBoolean());
            assertEquals("stale", stale.path("b").path("reason").asText());
            assertEquals(2, harness.ctx.db.query(
                    "SELECT 1 FROM official_team_snapshots WHERE node_id = 1").size());

            // 同代次低序号同样丢弃。
            JsonNode sameGen = n.req("team.official.report", reportPayload(3, 9, true));
            assertFalse(sameGen.path("b").path("accepted").asBoolean());

            // 未就绪只推进代次与序号，不删投影。
            JsonNode notReady = n.req("team.official.report", reportPayload(4, 1, false));
            assertTrue(notReady.path("b").path("accepted").asBoolean(), notReady.toString());
            assertEquals(2, harness.ctx.db.query(
                    "SELECT 1 FROM official_team_snapshots WHERE node_id = 1").size());

            // 恢复就绪：后续用例依赖可用投影。
            JsonNode ready = n.req("team.official.report", reportPayload(5, 2, true));
            assertTrue(ready.path("b").path("accepted").asBoolean(), ready.toString());
        }
        // 显式降级（空 caps）后不再具备官方队伍能力，上报被拒。
        try (TestClient degraded = new TestClient(harness.nodePort)) {
            degraded.helloCaps("node", "TestMod/1.0");
            assertNull(degraded.auth(harness.nodeToken));
            JsonNode denied = degraded.req("team.official.report", reportPayload(9, 1, true));
            assertFalse(denied.path("ok").asBoolean(), denied.toString());
            assertTrue(denied.path("m").asText().startsWith("FORBIDDEN"), denied.toString());
        }
        // 重新声明能力位，恢复官方模式供后续用例使用。
        try (TestClient n = officialNode()) {
            assertTrue(harness.ctx.db.queryOne("SELECT official_mode FROM nodes WHERE id = 1")
                    .map(row -> ((Number) row.get("official_mode")).intValue() == 1).orElse(false));
        }
    }

    @Test
    @Order(3)
    void officialReadAndCandidates() throws IOException {
        long teamId = createTeam("官方映射队");
        harness.ctx.db.update("INSERT OR REPLACE INTO team_members(team_id, uuid, role, joined_at) "
                + "VALUES(?,?,?,?)", teamId, ownerUuid, "leader", 0);
        JsonNode list = app.req("team.map",
                TestClient.obj("uuid", ownerUuid, "teamId", teamId));
        assertTrue(list.path("ok").asBoolean(), list.toString());
        assertEquals(0, list.path("b").path("links").size());
        boolean overlap = false;
        for (JsonNode c : list.path("b").path("candidates")) {
            if (OFFICIAL_A.equals(c.path("officialTeamId").asText())) {
                assertEquals("uuid-overlap", c.path("reason").asText());
                overlap = true;
            }
        }
        assertTrue(overlap, "候选应包含 UUID 重合的官方队伍：" + list);

        JsonNode set = app.req("team.map.set", TestClient.obj("uuid", ownerUuid,
                "teamId", teamId, "nodeId", 1L, "worldRef", "minecraft:overworld",
                "officialTeamId", OFFICIAL_A));
        assertTrue(set.path("ok").asBoolean(), set.toString());

        JsonNode official = app.req("team.official",
                TestClient.obj("uuid", ownerUuid, "teamId", teamId));
        assertTrue(official.path("ok").asBoolean(), official.toString());
        JsonNode link = official.path("b").path("links").get(0);
        assertEquals(OFFICIAL_A, link.path("officialTeamId").asText());
        assertFalse(link.path("stale").asBoolean(), "刚上报的投影不应过期：" + link);
        JsonNode team = official.path("b").path("teams").get(0);
        assertEquals("官方甲队", team.path("name").asText());
        assertEquals(2, team.path("members").size());
        assertEquals(1, team.path("owners").size());

        // 同一槽位换成另一官方队伍属于冲突。
        JsonNode conflict = app.req("team.map.set", TestClient.obj("uuid", ownerUuid,
                "teamId", teamId, "nodeId", 1L, "worldRef", "minecraft:overworld",
                "officialTeamId", OFFICIAL_B));
        assertFalse(conflict.path("ok").asBoolean(), conflict.toString());
        assertTrue(conflict.path("m").asText().startsWith("MAP_CONFLICT"), conflict.toString());

        // 指向不存在的官方队伍同样拒绝。
        JsonNode unknown = app.req("team.map.set", TestClient.obj("uuid", ownerUuid,
                "teamId", teamId, "nodeId", 1L, "worldRef", "minecraft:overworld",
                "officialTeamId", "33333333-3333-3333-3333-333333333333"));
        assertTrue(unknown.path("m").asText().startsWith("MAP_CONFLICT"), unknown.toString());

        JsonNode clear = app.req("team.map.clear", TestClient.obj("uuid", ownerUuid,
                "teamId", teamId, "nodeId", 1L, "worldRef", "minecraft:overworld"));
        assertTrue(clear.path("ok").asBoolean(), clear.toString());
        assertEquals(0, harness.ctx.db.query(
                "SELECT 1 FROM official_team_links WHERE team_id = ?", teamId).size());
    }

    @Test
    @Order(4)
    void centralWritesRejectedInOfficialMode() throws IOException {
        JsonNode created = app.req("team.create", TestClient.obj("uuid", ownerUuid, "name", "不应建队"));
        assertFalse(created.path("ok").asBoolean(), created.toString());
        assertTrue(created.path("m").asText().startsWith("OFFICIAL_TEAM_MODE"), created.toString());
    }

    @Test
    @Order(5)
    void officialNodeSkipsSuWrites() throws IOException {
        try (TestClient n = officialNode()) {
            ObjectNode reportP = harness.ctx.mapper.createObjectNode();
            reportP.set("teams", harness.ctx.mapper.createArrayNode());
            JsonNode res = n.req("team.report", reportP);
            assertTrue(res.path("ok").asBoolean(), res.toString());
            assertEquals("official", res.path("b").path("mode").asText());
        }
        // 官方模式下不得产生任何 SU 队伍写入与无线 EU 补发。
        assertEquals(0, harness.ctx.db.query(
                "SELECT 1 FROM node_ops WHERE type IN ('team.apply','eu.join')").size());
    }

    private long createTeam(String name) throws IOException {
        // 官方模式下写操作被拒，测试里直接落库造一个中央队伍行，供映射与读取使用。
        harness.ctx.db.update("INSERT INTO teams(name, join_mode, created_at) VALUES(?,?,?)",
                name, "invite", System.currentTimeMillis() / 1000);
        return harness.ctx.db.queryOne("SELECT id FROM teams WHERE name = ?", name)
                .map(row -> ((Number) row.get("id")).longValue()).orElseThrow();
    }
}
