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

/** 聊天域（契约 1.4.0）：公聊广播/私聊定向/屏蔽/好友/历史分页/冷却/relay。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatFlowTest {
    private static ServerHarness harness;
    private static String aliceUuid;
    private static long nameSeq;

    private static long ctx_chatCooldownMs() {
        return harness.ctx.config.chatCooldownMs;
    }
    private static String bobUuid;

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
        registerAndBind("alice");
        registerAndBind("bob");
        aliceUuid = uuidOf("alice");
        bobUuid = uuidOf("bob");
    }

    @AfterAll
    static void tearDown() {
        harness.close();
    }

    private static void registerAndBind(String name) throws IOException {
        String uuid = UUID.nameUUIDFromBytes(("cf-" + name).getBytes()).toString();
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "TestApp/1.0");
            var reg = c.req("account.register",
                    TestClient.obj("name", "cf-" + name, "pass", "password-123"));
            assertTrue(reg.path("ok").asBoolean(), "register " + name + ": " + reg);
            assertNull(c.auth(reg.path("b").path("token").asText()), "auth fail");
            try (TestClient n = node()) {
                String code = n.req("node.bindCode.create", TestClient.obj("actor", uuid))
                        .path("b").path("code").asText();
                assertTrue(c.req("account.bindUUID", TestClient.obj("code", code)).path("ok").asBoolean());
            }
        }
    }

    private static TestClient node() throws IOException {
        TestClient n = new TestClient(harness.nodePort);
        n.hello("node", "TestMod/1.0");
        assertNull(n.auth(harness.nodeToken));
        return n;
    }

    /** 新注册账号（一次性）并鉴权。 */
    private static TestClient authedApp(String tag) throws IOException {
        TestClient c = new TestClient(harness.appPort);
        c.hello("app", "TestApp/1.0");
        String token = c.req("account.register",
                TestClient.obj("name", "cfa-" + tag + "-" + (++nameSeq), "pass", "password-123"))
                .path("b").path("token").asText();
        assertNull(c.auth(token));
        return c;
    }

    private static TestClient freshApp(String tag) throws IOException {
        return authedApp(tag);
    }

    /** 以既有账号登录（cf-<名>）。 */
    private static TestClient authedAs(String name) throws IOException {
        TestClient c = new TestClient(harness.appPort);
        c.hello("app", "TestApp/1.0");
        String token = c.req("account.login",
                TestClient.obj("name", "cf-" + name, "pass", "password-123"))
                .path("b").path("token").asText();
        assertNull(c.auth(token));
        return c;
    }

    private static String uuidOf(String name) {
        return UUID.nameUUIDFromBytes(("cf-" + name).getBytes()).toString();
    }

    private JsonNode op(TestClient c, String uuid, String name, Object... kv) throws IOException {
        Object[] args = new Object[kv.length + 2];
        args[0] = "uuid";
        args[1] = uuid;
        System.arraycopy(kv, 0, args, 2, kv.length);
        return c.req(name, TestClient.obj(args));
    }

    @Test
    @Order(1)
    void publicSendBroadcastAndHistory() throws IOException {
        TestClient alice = authedAs("alice");
        try (TestClient bobSub = authedAs("bob")) {
            bobSub.sub("chat", TestClient.obj("uuid", bobUuid));
            // 订阅确认 SUB_OK 无推送；公聊广播
            var sent = op(alice, aliceUuid, "chat.public.send", "text", "大家好");
            assertTrue(sent.path("ok").asBoolean(), sent.toString());
            JsonNode push = bobSub.readPush();
            assertEquals("d", push.path("t").asText());
            JsonNode msg = push.path("events").get(0);
            assertEquals("msg", msg.path("k").asText());
            assertEquals("大家好", msg.path("text").asText());
            // 历史分页
            var hist = op(bobSub, bobUuid, "chat.history", "scope", "public", "limit", 10);
            assertTrue(hist.path("ok").asBoolean());
            assertEquals(1, hist.path("b").path("messages").size());
            assertFalse(hist.path("b").path("more").asBoolean());
        }
    }

    @Test
    @Order(2)
    void privateSendDirectedAndBlocked() throws IOException {
        TestClient alice = authedAs("alice");
        try (TestClient bobSub = authedAs("bob")) {
            bobSub.sub("chat", TestClient.obj("uuid", bobUuid));
            var sent = op(alice, aliceUuid, "chat.private.send", "to", bobUuid, "text", "悄悄话");
            assertTrue(sent.path("ok").asBoolean(), sent.toString());
            JsonNode f = bobSub.readPush();
            System.out.println("[dbg2] f=" + f);
            JsonNode msg = f.path("events").get(0);
            assertEquals("msg", msg.path("k").asText());
            assertTrue(msg.path("private").asBoolean());
            assertEquals("悄悄话", msg.path("text").asText());
            // bob 屏蔽 alice 后：alice → bob 私聊被 BLOCKED
            assertTrue(op(bobSub, bobUuid, "block.set", "target", aliceUuid, "blocked", true)
                    .path("ok").asBoolean(), "block.set: ");
            var blockedRes = op(alice, aliceUuid, "chat.private.send",
                    "to", bobUuid, "text", "再试试");
            assertTrue(blockedRes.path("m").asText().startsWith("BLOCKED"), "被屏蔽后应 BLOCKED: " + blockedRes);
            // 解除屏蔽恢复
            assertTrue(op(bobSub, bobUuid, "block.set", "target", aliceUuid, "blocked", false)
                    .path("ok").asBoolean());
            assertTrue(op(alice, aliceUuid, "chat.private.send",
                    "to", bobUuid, "text", "恢复了").path("ok").asBoolean());
            bobSub.readPush(); // 消费恢复后的推送
        }
    }

    @Test
    @Order(3)
    void friendFlow() throws IOException {
        TestClient alice = authedAs("alice");
        try (TestClient bobSub = authedAs("bob")) {
            bobSub.sub("chat", TestClient.obj("uuid", bobUuid));
            var req = op(alice, aliceUuid, "friend.request", "to", bobUuid);
            assertTrue(req.path("ok").asBoolean(), req.toString());
            long requestId = req.path("b").path("requestId").asLong();
            assertTrue(requestId > 0);
            // 幂等：重复请求返回原 id
            assertEquals(requestId, op(alice, aliceUuid, "friend.request", "to", bobUuid)
                    .path("b").path("requestId").asLong());
            // bob 收到 friend request 事件
            JsonNode push = bobSub.readPush();
            
            assertEquals("request", push.path("events").get(0).path("op").asText());
            // 接受
            assertTrue(op(bobSub, bobUuid, "friend.accept", "requestId", requestId).path("ok").asBoolean());
            // 删除好友
            assertTrue(op(bobSub, bobUuid, "friend.remove", "target", aliceUuid).path("ok").asBoolean());
        }
    }

    @Test
    @Order(4)
    void cooldownAndRelay() throws IOException {
        TestClient alice = authedAs("alice");
        // 跨用例的公聊冷却仍在生效窗口：等待冷却过期后再验证
        try {
            Thread.sleep(ctx_chatCooldownMs() + 200);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        // 冷却：连发两条第二条 CHAT_RATE_LIMITED
        assertTrue(op(alice, aliceUuid, "chat.public.send", "text", "第一条").path("ok").asBoolean(),
                "第一条应成功");
        assertTrue(op(alice, aliceUuid, "chat.public.send", "text", "第二条")
                .path("m").asText().startsWith("CHAT_RATE_LIMITED"), "应触发冷却");
        // relay：节点投递公屏 → 入历史
        try (TestClient n = node()) {
            var relay = n.req("chat.relay", TestClient.obj(
                    "channel", "public", "from", uuidOf("bob"), "text", "游戏内发言", "srv", "测试服"));
            assertTrue(relay.path("ok").asBoolean(), "relay: " + relay);
            System.out.println("[dbg4] relay ok, 查历史");
        }
        var hist = op(alice, aliceUuid, "chat.history", "scope", "public", "limit", 50);
        StringBuilder texts = new StringBuilder();
        boolean found = false;
        System.out.println("[dbg4] hist=" + hist);
        for (JsonNode m : hist.path("b").path("messages")) {
            texts.append(m.path("text").asText()).append('|');
            if ("游戏内发言".equals(m.path("text").asText())) {
                found = true;
            }
        }
        assertTrue(found, "relay 的公屏消息应入历史，实际=" + texts + " more=" + hist.path("b").path("more").asBoolean());
    }
}
