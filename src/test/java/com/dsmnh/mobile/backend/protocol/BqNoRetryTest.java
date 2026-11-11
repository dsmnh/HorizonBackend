package com.dsmnh.mobile.backend.protocol;

import com.dsmnh.mobile.backend.support.ServerHarness;
import com.dsmnh.mobile.backend.support.TestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 契约 1.6.0 §8.4：BQ 覆盖失败后不自动重试，其余下行类型仍按退避重发。 */
class BqNoRetryTest {

    private static ObjectNode payload() {
        return JsonNodeFactory.instance.objectNode()
                .put("snapshotId", "bq-no-retry")
                .put("phase", "import");
    }

    @Test
    void bqFailureIsNotRetriedWhileOtherTypesStillAre() throws Exception {
        try (ServerHarness harness = new ServerHarness()) {
            TestClient node = new TestClient(harness.nodePort);
            node.hello("node", "TestMod/1.0");
            assertNull(node.auth(harness.nodeToken));
            node.sub("ops", null);
            node.readPush();

            String bqOpId = harness.ctx.opsStream.enqueue(1, "bq.apply", payload());
            String opSetId = harness.ctx.opsStream.enqueue(1, "op.set",
                    JsonNodeFactory.instance.objectNode().put("player", "tester").put("grant", true));

            JsonNode failBq = node.req("node.op.fail",
                    TestClient.obj("opId", bqOpId, "reason", "backup failed"));
            assertTrue(failBq.path("ok").asBoolean(), failBq.toString());
            JsonNode failOp = node.req("node.op.fail",
                    TestClient.obj("opId", opSetId, "reason", "player offline"));
            assertTrue(failOp.path("ok").asBoolean(), failOp.toString());

            harness.ctx.db.update("UPDATE node_ops SET next_retry_at = 0 "
                    + "WHERE op_id IN (?, ?)", bqOpId, opSetId);
            harness.ctx.opsStream.sweep();

            List<Map<String, Object>> bqRows = harness.ctx.db.query(
                    "SELECT op_id, status FROM node_ops WHERE type = 'bq.apply'");
            assertEquals(1, bqRows.size(), "BQ 失败不应生成新实例");
            assertEquals("failed", bqRows.get(0).get("status"));
            assertEquals(bqOpId, bqRows.get(0).get("op_id"));

            List<Map<String, Object>> opSetRows = harness.ctx.db.query(
                    "SELECT op_id, status FROM node_ops WHERE type = 'op.set' AND status = 'pending'");
            assertEquals(1, opSetRows.size(), "普通下行仍应生成重试实例");
            assertNotEquals(opSetId, opSetRows.get(0).get("op_id"));
            node.close();
        }
    }
}
