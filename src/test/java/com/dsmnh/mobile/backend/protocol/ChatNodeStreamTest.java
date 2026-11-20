package com.dsmnh.mobile.backend.protocol;

import com.dsmnh.mobile.backend.net.FrameType;
import com.dsmnh.mobile.backend.support.ServerHarness;
import com.dsmnh.mobile.backend.support.TestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** chat 流的节点订阅（契约 1.6.3）：节点收公聊、来源节点不回推、未鉴权被拒。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatNodeStreamTest {
    private static ServerHarness harness;
    private static String aliceUuid;
    private static TestClient nodeA;
    private static String nodeBToken;
    private static boolean aliceRegistered;

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
        aliceUuid = UUID.nameUUIDFromBytes("cns-alice".getBytes()).toString();
        nodeA = node();
        nodeBToken = registerNode("二服");
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (nodeA != null) {
            nodeA.close();
        }
        harness.close();
    }

    private static TestClient node() throws IOException {
        TestClient n = new TestClient(harness.nodePort);
        n.hello("node", "TestMod/1.0");
        assertNull(n.auth(harness.nodeToken));
        return n;
    }

    /** 以新注册码接入第二个节点，供跨节点投递断言使用。 */
    private static String registerNode(String name) throws IOException {
        String code = harness.ctx.auth.issueNodeCode("new", null, harness.ctx.config.nodeCodeTtlSec);
        try (TestClient n = new TestClient(harness.nodePort)) {
            n.hello("node", "TestMod/1.0");
            JsonNode res = n.req("node.register", TestClient.obj("code", code, "nodeName", name));
            assertTrue(res.path("ok").asBoolean(), "注册节点：" + res);
            return res.path("b").path("token").asText();
        }
    }

    /** 复用同一账号登录，避免重复注册触发 ACCOUNT_EXISTS；首次登录后绑定 aliceUuid。 */
    private static TestClient authedApp() throws IOException {
        TestClient c = new TestClient(harness.appPort);
        c.hello("app", "TestApp/1.0");
        String token;
        if (!aliceRegistered) {
            token = c.req("account.register",
                    TestClient.obj("name", "cns-alice", "pass", "password-123"))
                    .path("b").path("token").asText();
        } else {
            token = c.req("account.login",
                    TestClient.obj("name", "cns-alice", "pass", "password-123"))
                    .path("b").path("token").asText();
        }
        assertNull(c.auth(token), "App 鉴权失败");
        if (!aliceRegistered) {
            aliceRegistered = true;
            // chat 流订阅要求 uuid 已绑定账号，否则 FORBIDDEN
            JsonNode codeRes = nodeA.req("node.bindCode.create", TestClient.obj("actor", aliceUuid));
            String code = codeRes.path("b").path("code").asText();
            JsonNode bindRes = c.req("account.bindUUID", TestClient.obj("code", code));
            assertTrue(bindRes.path("ok").asBoolean(), "绑定 uuid 失败：code=" + codeRes + " bind=" + bindRes);
        }
        return c;
    }

    private static String firstText(JsonNode push) {
        return push.path("events").path(0).path("text").asText("");
    }

    @Test
    @Order(1)
    void nodeSubscribesChatStream() throws IOException {
        JsonNode ok = nodeA.sub("chat", TestClient.obj());
        assertTrue(ok.path("s").asLong() > 0, "节点订阅应返回流编号：" + ok);
        assertFalse(ok.path("sid").asText("").isEmpty(), "节点订阅应返回 sid：" + ok);
    }

    @Test
    @Order(2)
    void appPublicSendReachesNode() throws IOException {
        try (TestClient app = authedApp()) {
            JsonNode res = app.req("chat.public.send",
                    TestClient.obj("uuid", aliceUuid, "text", "App 公聊到节点"));
            assertTrue(res.path("ok").asBoolean(), "public.send：" + res);
        }
        assertEquals("App 公聊到节点", firstText(nodeA.readPush()), "节点应收到 App 公聊");
    }

    @Test
    @Order(3)
    void relaySkipsSourceNodeButReachesPeers() throws IOException {
        try (TestClient nodeB = new TestClient(harness.nodePort)) {
            nodeB.hello("node", "TestMod/1.0");
            assertNull(nodeB.auth(nodeBToken));
            nodeB.sub("chat", TestClient.obj());
            JsonNode relay = nodeA.req("chat.relay", TestClient.obj(
                    "channel", "public", "from", "uuid-二服", "text", "二服公屏", "srv", "测试二服"));
            assertTrue(relay.path("ok").asBoolean(), "relay：" + relay);
            assertEquals("二服公屏", firstText(nodeB.readPush()), "其它节点应收到公屏");
        }
        // 来源节点不回推：读超时内不应有推送
        nodeA.setReadTimeout(1200);
        assertThrows(SocketTimeoutException.class, nodeA::readPush, "来源节点不应收到自己的公屏");
        nodeA.setReadTimeout(TestClient.READ_TIMEOUT_MS);
    }

    @Test
    @Order(4)
    void nodeChatSubRequiresAuth() throws IOException {
        try (TestClient raw = new TestClient(harness.nodePort)) {
            raw.hello("node", "TestMod/1.0");
            ObjectNode req = JsonNodeFactory.instance.objectNode();
            req.put("id", 1);
            req.put("op", "chat");
            req.set("p", JsonNodeFactory.instance.objectNode());
            raw.send(FrameType.SUB, req);
            JsonNode res = raw.expect(FrameType.RES);
            assertEquals("AUTH_REQUIRED", res.path("m").asText(), "未鉴权订阅应被拒：" + res);
        }
    }

    @Test
    @Order(5)
    void appSubscribersStillReceiveNodeRelay() throws IOException {
        try (TestClient app = authedApp()) {
            long s = app.sub("chat", TestClient.obj("uuid", aliceUuid)).path("s").asLong();
            assertTrue(s > 0, "App 订阅 chat 应成功");
            JsonNode relay = nodeA.req("chat.relay", TestClient.obj(
                    "channel", "public", "from", "uuid-二服", "text", "节点到 App", "srv", "测试二服"));
            assertTrue(relay.path("ok").asBoolean(), "relay：" + relay);
            assertEquals("节点到 App", firstText(app.readPush()), "App 订阅者应收到节点公屏");
        }
    }
}
