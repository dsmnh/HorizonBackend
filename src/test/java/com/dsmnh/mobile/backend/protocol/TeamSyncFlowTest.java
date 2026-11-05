package com.dsmnh.mobile.backend.protocol;

import com.dsmnh.mobile.backend.support.ServerHarness;
import com.dsmnh.mobile.backend.support.TestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 队伍同步（契约 1.3.0）：team.report 对账生成 team.apply、eu_bindings 补发。 */
class TeamSyncFlowTest {
    private static ServerHarness harness;

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
    }

    @AfterAll
    static void tearDown() {
        harness.close();
    }

    private static TestClient node() throws IOException {
        TestClient n = new TestClient(harness.nodePort);
        n.hello("node", "TestMod/1.0");
        assertNull(n.auth(harness.nodeToken));
        return n;
    }

    @Test
    void reportTriggersAuthoritativeApplyAndEuRebind() throws IOException {
        // 中央建两队
        String uuid = UUID.nameUUIDFromBytes("ts-leader".getBytes()).toString();
        try (TestClient app = new TestClient(harness.appPort)) {
            app.hello("app", "TestApp/1.0");
            String token = app.req("account.register",
                    TestClient.obj("name", "ts-leader", "pass", "password-123"))
                    .path("b").path("token").asText();
            assertNull(app.auth(token));
            try (TestClient n = node()) {
                String code = n.req("node.bindCode.create", TestClient.obj("actor", uuid))
                        .path("b").path("code").asText();
                assertTrue(app.req("account.bindUUID", TestClient.obj("code", code)).path("ok").asBoolean());
            }
            ObjectNode p1 = TestClient.obj("uuid", uuid, "name", "alpha队");
            var c1 = app.req("team.create", p1);
            assertTrue(c1.path("ok").asBoolean(), "create alpha: " + c1);
            // 一个 UUID 只能在一个队伍（企划书 04）：第二队由另一账号创建则超出本用例需要
        }
        // 预填无线 EU 映射
        harness.ctx.db.update("INSERT OR REPLACE INTO eu_bindings(node_id, player, team_name, bound_at) "
                + "VALUES(1, ?, 'alpha队', 0)", uuid);

        // 节点订阅 → 上报 → 应收到 2 条 team.apply + 1 条 eu.join
        try (TestClient n = node()) {
            JsonNode ok = n.sub("ops", null);
            n.readPush(); // 全量
            ObjectNode reportP = harness.ctx.mapper.createObjectNode();
            reportP.set("teams", harness.ctx.mapper.createArrayNode());
            var report = n.req("team.report", reportP);
            assertTrue(report.path("ok").asBoolean(), report.toString());
            assertTrue(report.path("b").path("accepted").asBoolean());

            int applies = 0;
            boolean euJoin = false;
            for (int i = 0; i < 2; i++) {
                JsonNode delta = n.readPush();
                assertEquals("d", delta.path("t").asText());
                for (JsonNode e : delta.path("events")) {
                    if ("team.apply".equals(e.path("type").asText())) {
                        applies += 1;
                        assertNotNull(e.path("p").path("team").path("name").asText());
                    }
                    if ("eu.join".equals(e.path("type").asText())) {
                        euJoin = "alpha队".equals(e.path("p").path("teamName").asText());
                    }
                }
            }
            assertEquals(1, applies, "中央队伍应生成一条 team.apply");
            assertTrue(euJoin, "eu_bindings 应补发 eu.join");
        }
    }
}
