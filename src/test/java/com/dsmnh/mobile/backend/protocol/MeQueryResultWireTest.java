package com.dsmnh.mobile.backend.protocol;

import com.dsmnh.mobile.backend.support.ServerHarness;
import com.dsmnh.mobile.backend.support.TestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 登记表 §4.5：节点以 {queryId, items, error?} 回填，App 凭 queryId 轮询取回。 */
class MeQueryResultWireTest {

    @Test
    void contractWirePayloadRoundTripsIncludingError() throws Exception {
        try (ServerHarness harness = new ServerHarness()) {
            String uuid = UUID.nameUUIDFromBytes("wire-owner".getBytes()).toString();
            String deviceId = "wire-device";

            TestClient node = new TestClient(harness.nodePort);
            node.hello("node", "TestMod/1.0");
            assertNull(node.auth(harness.nodeToken));
            node.sub("ops", null);
            node.readPush();

            TestClient app = new TestClient(harness.appPort);
            app.hello("app", "TestApp/1.0");
            JsonNode reg = app.req("account.register",
                    TestClient.obj("name", "wire-owner", "pass", "password-123"));
            assertNull(app.auth(reg.path("b").path("token").asText()));
            String code = node.req("node.bindCode.create", TestClient.obj("actor", uuid))
                    .path("b").path("code").asText();
            assertTrue(app.req("account.bindUUID", TestClient.obj("code", code)).path("ok").asBoolean());
            assertTrue(node.req("me.register", TestClient.obj(
                    "deviceId", deviceId, "owner", uuid, "kind", "monitor")).path("ok").asBoolean());

            JsonNode first = app.req("me.query", TestClient.obj("uuid", uuid, "deviceId", deviceId));
            assertTrue(first.path("ok").asBoolean(), first.toString());
            String firstId = first.path("b").path("queryId").asText();
            ObjectNode backfill = JsonNodeFactory.instance.objectNode();
            backfill.put("queryId", firstId);
            ArrayNode items = backfill.putArray("items");
            items.addObject().put("item", "minecraft:iron_ingot").put("count", 4);
            assertTrue(node.req("me.query.result", backfill).path("ok").asBoolean());
            JsonNode polled = app.req("me.query", TestClient.obj("queryId", firstId));
            assertFalse(polled.path("b").path("pending").asBoolean());
            assertEquals(4, polled.path("b").path("items").get(0).path("count").asInt());

            JsonNode second = app.req("me.query", TestClient.obj("uuid", uuid, "deviceId", deviceId));
            String secondId = second.path("b").path("queryId").asText();
            ObjectNode failed = JsonNodeFactory.instance.objectNode();
            failed.put("queryId", secondId);
            failed.putArray("items");
            failed.put("error", "AE2 查询未完成");
            assertTrue(node.req("me.query.result", failed).path("ok").asBoolean());
            JsonNode polledFailed = app.req("me.query", TestClient.obj("queryId", secondId));
            assertFalse(polledFailed.path("b").path("pending").asBoolean());
            assertEquals("AE2 查询未完成", polledFailed.path("b").path("error").asText());
            assertTrue(polledFailed.path("b").path("items").isArray());

            app.close();
            node.close();
        }
    }
}
