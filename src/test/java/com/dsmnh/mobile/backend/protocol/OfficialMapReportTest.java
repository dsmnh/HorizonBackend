package com.dsmnh.mobile.backend.protocol;

import com.dsmnh.mobile.backend.api.OfficialMapReport;
import com.dsmnh.mobile.backend.support.ServerHarness;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 官方队伍映射只读报告（开发计划阶段 5）：报告只读取中央队伍、投影与映射，
 * 列成员差异、未映射项、候选建议与投影新鲜度，不得写库或产生下行指令。
 */
class OfficialMapReportTest {
    private static final String OFFICIAL_A = "aaaaaaaa-1111-1111-1111-111111111111";
    private static final String OFFICIAL_B = "bbbbbbbb-2222-2222-2222-222222222222";
    private static final String OWNER = "11111111-0000-0000-0000-000000000001";
    private static final String MATE = "11111111-0000-0000-0000-000000000002";
    private static final String OUTSIDER = "11111111-0000-0000-0000-000000000003";

    private ServerHarness harness;

    @BeforeEach
    void setup() throws Exception {
        // 每例独立临时库：报告断言依赖精确的队伍集合，用例间不能互相看见数据。
        harness = new ServerHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void reportsDifferencesAndCandidates() {
        long mapped = createTeam("已映射队", OWNER, MATE);
        long unmapped = createTeam("未映射队", MATE, OUTSIDER);
        long emptyTeam = createTeam("空队伍");

        insertWorld(1, "minecraft:overworld", 4, 12, true);
        insertOfficial(1, "minecraft:overworld", OFFICIAL_A, "官方甲队", List.of(OWNER), List.of(),
                List.of(OWNER, MATE, OUTSIDER));
        insertOfficial(1, "minecraft:overworld", OFFICIAL_B, "官方乙队", List.of(MATE), List.of(),
                List.of(MATE));
        harness.ctx.db.update("INSERT OR REPLACE INTO official_team_links(team_id, node_id, world_ref, "
                        + "official_team_id, created_by, created_at) VALUES(?,?,?,?,?,?)",
                mapped, 1L, "minecraft:overworld", OFFICIAL_A, "test", 0L);

        JsonNode data = new OfficialMapReport(harness.ctx).build();
        assertEquals(3, data.path("teams").size());
        assertEquals(2, data.path("officials").size());

        JsonNode link = data.path("links").get(0);
        assertEquals(mapped, link.path("teamId").asLong());
        assertEquals("官方甲队", link.path("officialName").asText());
        assertEquals("ok", link.path("state").asText());
        assertEquals(0, link.path("centralOnly").size());
        assertEquals(1, link.path("officialOnly").size(), "官方独有成员应被列出：" + link);
        assertEquals(OUTSIDER, link.path("officialOnly").get(0).asText());

        // 未映射队列入待核对，空队伍不制造噪声。
        assertEquals(1, data.path("unmappedTeams").size());
        assertEquals(unmapped, data.path("unmappedTeams").get(0).path("teamId").asLong());
        assertNotEquals(emptyTeam, data.path("unmappedTeams").get(0).path("teamId").asLong());

        // 未被引用的官方队伍单列。
        assertEquals(1, data.path("orphanOfficials").size());
        assertEquals(OFFICIAL_B, data.path("orphanOfficials").get(0).path("officialTeamId").asText());

        // 未映射队与官方乙队的成员重合（MATE）应作为候选出现。
        boolean found = false;
        for (JsonNode c : data.path("candidates")) {
            if (unmapped == c.path("teamId").asLong() && OFFICIAL_B.equals(c.path("officialTeamId").asText())) {
                assertEquals(1, c.path("overlap").asInt());
                found = true;
            }
        }
        assertTrue(found, "候选应包含未映射队与官方乙队的重合：" + data.path("candidates"));

        JsonNode world = data.path("worlds").get(0);
        assertEquals(4, world.path("gen").asLong());
        assertEquals(12, world.path("seq").asLong());
        assertTrue(world.path("ready").asBoolean());

        String text = new OfficialMapReport(harness.ctx).render();
        assertTrue(text.contains("官方甲队"), text);
        assertTrue(text.contains("未映射的中央队伍"), text);
        assertTrue(text.contains("投影新鲜度"), text);
    }

    @Test
    void reportIsReadOnly() {
        int opsBefore = harness.ctx.db.query("SELECT 1 FROM node_ops").size();
        int linksBefore = harness.ctx.db.query("SELECT 1 FROM official_team_links").size();
        int snapsBefore = harness.ctx.db.query("SELECT 1 FROM official_team_snapshots").size();
        new OfficialMapReport(harness.ctx).render();
        new OfficialMapReport(harness.ctx).build();
        assertEquals(opsBefore, harness.ctx.db.query("SELECT 1 FROM node_ops").size());
        assertEquals(linksBefore, harness.ctx.db.query("SELECT 1 FROM official_team_links").size());
        assertEquals(snapsBefore, harness.ctx.db.query("SELECT 1 FROM official_team_snapshots").size());
    }

    @Test
    void handlesMissingOfficialTeam() {
        long teamId = createTeam("指向已消失队伍的队", OWNER);
        insertWorld(2, "minecraft:overworld", 1, 1, true);
        harness.ctx.db.update("INSERT OR REPLACE INTO official_team_links(team_id, node_id, world_ref, "
                        + "official_team_id, created_by, created_at) VALUES(?,?,?,?,?,?)",
                teamId, 2L, "minecraft:overworld", "cccccccc-3333-3333-3333-333333333333", "test", 0L);
        JsonNode data = new OfficialMapReport(harness.ctx).build();
        boolean missing = false;
        List<String> kinds = new ArrayList<>();
        for (JsonNode issue : data.path("issues")) {
            kinds.add(issue.path("kind").asText());
            if ("映射指向的官方队伍已不存在".equals(issue.path("kind").asText())) {
                missing = true;
            }
        }
        assertTrue(missing, "缺失的官方队伍应列入待核对：" + kinds);
        String text = new OfficialMapReport(harness.ctx).render();
        assertTrue(text.contains("state=missing") || text.contains("官方队伍已不存在"), text);
    }

    private long createTeam(String name, String... members) {
        harness.ctx.db.update("INSERT INTO teams(name, join_mode, created_at) VALUES(?,?,?)",
                name, "invite", 0L);
        long id = harness.ctx.db.queryOne("SELECT id FROM teams WHERE name = ?", name)
                .map(row -> ((Number) row.get("id")).longValue()).orElseThrow();
        for (String uuid : members) {
            harness.ctx.db.update("INSERT OR REPLACE INTO team_members(team_id, uuid, role, joined_at) "
                    + "VALUES(?,?,?,?)", id, uuid, "member", 0L);
        }
        return id;
    }

    private void insertWorld(long nodeId, String worldRef, long gen, long seq, boolean ready) {
        harness.ctx.db.update("INSERT OR REPLACE INTO official_world_state(node_id, world_ref, gen, seq, "
                        + "ready, updated_at) VALUES(?,?,?,?,?,?)",
                nodeId, worldRef, gen, seq, ready ? 1 : 0, System.currentTimeMillis() / 1000);
    }

    private void insertOfficial(long nodeId, String worldRef, String officialTeamId, String name,
                                List<String> owners, List<String> officers, List<String> members) {
        ObjectNode json = harness.ctx.mapper.createObjectNode();
        var arr = json.putArray("v");
        for (String s : members) {
            arr.add(s);
        }
        harness.ctx.db.update("INSERT OR REPLACE INTO official_team_snapshots(node_id, world_ref, "
                        + "official_team_id, name, owners, officers, members, gen, seq, updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                nodeId, worldRef, officialTeamId, name,
                toJsonArray(owners), toJsonArray(officers), toJsonArray(members), 1L, 1L,
                System.currentTimeMillis() / 1000);
        for (String uuid : members) {
            harness.ctx.db.update("INSERT OR REPLACE INTO official_team_members(node_id, world_ref, "
                            + "official_team_id, uuid, role) VALUES(?,?,?,?,?)",
                    nodeId, worldRef, officialTeamId, uuid, "member");
        }
    }

    private String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i)).append('"');
        }
        return sb.append(']').toString();
    }
}
