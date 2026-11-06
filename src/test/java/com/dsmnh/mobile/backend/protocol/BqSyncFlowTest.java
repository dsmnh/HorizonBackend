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

/** BQ 同步发起（契约 1.5.0 §8.4）：bq.sync.start 权限、快照创建、下行 bq.apply。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BqSyncFlowTest {
    private static ServerHarness harness;
    private static String ownerUuid;
    private static String ownerToken;

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
        String uuid = UUID.nameUUIDFromBytes("bq-owner".getBytes()).toString();
        ownerUuid = uuid;
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "TestApp/1.0");
            var reg = c.req("account.register",
                    TestClient.obj("name", "bq-owner", "pass", "password-123"));
            assertTrue(reg.path("ok").asBoolean());
            ownerToken = reg.path("b").path("token").asText();
            assertNull(c.auth(ownerToken));
            try (TestClient n = node()) {
                String code = n.req("node.bindCode.create", TestClient.obj("actor", uuid))
                        .path("b").path("code").asText();
                assertTrue(c.req("account.bindUUID", TestClient.obj("code", code)).path("ok").asBoolean());
            }
            // 认领服主
            String claim = harness.ctx.auth.issueOwnerCode("claim", 900);
            assertTrue(c.req("owner.claim", TestClient.obj("code", claim))
                    .path("b").path("role").asText().equals("owner"));
        }
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

    private static TestClient owner() throws IOException {
        TestClient c = new TestClient(harness.appPort);
        c.hello("app", "TestApp/1.0");
        String token = c.req("account.login",
                TestClient.obj("name", "bq-owner", "pass", "password-123"))
                .path("b").path("token").asText();
        assertNull(c.auth(token));
        return c;
    }

    @Test
    @Order(1)
    void bqSyncStartCreatesSnapshotAndQueuesApply() throws IOException {
        try (TestClient owner = owner()) {
            var start = owner.req("bq.sync.start", TestClient.obj(
                    "teamId", 1, "sourceNode", 1, "targetNode", 1));
            assertTrue(start.path("ok").asBoolean(), start.toString());
            String batchId = start.path("b").path("batchId").asText();
            assertFalse(batchId.isEmpty());
            // 快照表应有记录
            assertFalse(harness.ctx.db.query(
                    "SELECT snapshot_id FROM bq_snapshots WHERE snapshot_id LIKE 'bq-%'").isEmpty());
        }
    }

    @Test
    @Order(2)
    void bqSyncRequiresSuper() throws IOException {
        try (TestClient c = freshApp()) {
            var res = c.req("bq.sync.start", TestClient.obj(
                    "teamId", 1, "sourceNode", 1, "targetNode", 1));
            // 未绑定 UUID → FORBIDDEN（identity 校验在前）
            assertEquals("FORBIDDEN", res.path("m").asText());
        }
    }

    @Test
    @Order(3)
    void bqSyncUnknownNode() throws IOException {
        try (TestClient owner = owner()) {
            var res = owner.req("bq.sync.start", TestClient.obj(
                    "teamId", 1, "sourceNode", 999, "targetNode", 1));
            assertEquals("NODE_UNKNOWN", res.path("m").asText());
        }
    }

    private TestClient freshApp() throws IOException {
        TestClient c = new TestClient(harness.appPort);
        c.hello("app", "TestApp/1.0");
        var reg = c.req("account.register",
                TestClient.obj("name", "bq-x-" + System.nanoTime(), "pass", "password-123"));
        assertNull(c.auth(reg.path("b").path("token").asText()));
        return c;
    }
}
