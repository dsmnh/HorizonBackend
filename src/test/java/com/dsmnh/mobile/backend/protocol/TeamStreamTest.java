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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** team 订阅流（登记表 §5、契约 §7.2）：全量、增量、sid 续传、退订。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TeamStreamTest {
    private static ServerHarness harness;
    private static String aliceUuid;
    private static String bobUuid;

    @BeforeAll
    static void setup() throws Exception {
        harness = new ServerHarness();
        registerAndBind("alice");
        registerAndBind("bob");
        registerAndBind("eve");
        aliceUuid = uuidOf("alice");
        bobUuid = uuidOf("bob");
    }

    @AfterAll
    static void tearDown() {
        harness.close();
    }

    /** 注册确定性命名账号（st-<名>）并绑定对应 UUID。 */
    private static void registerAndBind(String name) throws IOException {
        String uuid = UUID.nameUUIDFromBytes(("st-" + name).getBytes()).toString();
        try (TestClient c = new TestClient(harness.appPort)) {
            c.hello("app", "TestApp/1.0");
            var reg = c.req("account.register",
                    TestClient.obj("name", "st-" + name, "pass", "password-123"));
            assertTrue(reg.path("ok").asBoolean(), reg.toString());
            assertNull(c.auth(reg.path("b").path("token").asText()));
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

    /** 注册一次性新账号并鉴权（用于出码等辅助连接）。 */
    private static TestClient authedApp(String tag) throws IOException {
        TestClient c = new TestClient(harness.appPort);
        c.hello("app", "TestApp/1.0");
        String token = c.req("account.register",
                TestClient.obj("name", "stx-" + tag + "-" + (++nameSeq), "pass", "password-123"))
                .path("b").path("token").asText();
        assertNull(c.auth(token));
        return c;
    }

    private static long nameSeq;

    /** 以既有账号（st-<名>）登录并鉴权。 */
    private static TestClient authedAs(String name) throws IOException {
        TestClient c = new TestClient(harness.appPort);
        c.hello("app", "TestApp/1.0");
        String token = c.req("account.login",
                TestClient.obj("name", "st-" + name, "pass", "password-123"))
                .path("b").path("token").asText();
        assertNull(c.auth(token));
        return c;
    }

    private static String uuidOf(String name) {
        return UUID.nameUUIDFromBytes(("st-" + name).getBytes()).toString();
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
    void subscribeFullFrame() throws IOException {
        try (TestClient sub = authedAs("bob")) {
            JsonNode ok = sub.sub("team", TestClient.obj("uuid", bobUuid));
            assertEquals(1, ok.path("s").asLong());
            assertFalse(ok.path("sid").asText().isEmpty());
            JsonNode f = sub.readPush();
            assertEquals("f", f.path("t").asText());
            assertEquals(bobUuid, f.path("uuid").asText());
            assertTrue(f.path("team").isNull());
            assertEquals(0, f.path("invites").size());
            assertEquals(0, f.path("applies").size());
        }
    }

    @Test
    @Order(2)
    void inviteDeltaAndResumeBySid() throws IOException {
        try (TestClient alice = authedAs("alice")) {
            long teamId = op(alice, aliceUuid, "team.create", "name", "stream-alpha")
                    .path("b").path("teamId").asLong();

            TestClient sub = authedAs("bob");
            String sid = sub.sub("team", TestClient.obj("uuid", bobUuid)).path("sid").asText();
            sub.readPush(); // 全量

            var invited = op(alice, aliceUuid, "team.invite", "target", bobUuid);
            assertTrue(invited.path("ok").asBoolean(), invited.toString());
            JsonNode delta = sub.readPush();
            assertEquals("d", delta.path("t").asText());
            assertEquals("inviteReceived", delta.path("events").get(0).path("k").asText());
            assertEquals(teamId, delta.path("events").get(0).path("teamId").asLong());

            // 断线重连：带 sid 续传，返回同一 sid；游标处无新事件则暂无推送
            sub.close();
            TestClient reconnected = authedAs("bob");
            assertEquals(sid, reconnected.sub("team", TestClient.obj("uuid", bobUuid, "sid", sid))
                    .path("sid").asText());

            // bob 接受邀请（同连接上 PUSH 先于 RES，req 已将其缓存）：先 inviteResolved 后 memberJoined
            var acceptBob = op(reconnected, bobUuid, "team.acceptInvite",
                    "inviteId", invited.path("b").path("inviteId").asLong());
            assertTrue(acceptBob.path("ok").asBoolean(), acceptBob.toString());
            assertEquals("inviteResolved",
                    reconnected.readPush().path("events").get(0).path("k").asText());
            assertEquals("memberJoined",
                    reconnected.readPush().path("events").get(0).path("k").asText());

            // carol 入队：成员变更送达已是成员的 bob
            String carolUuid = uuidOf("carol");
            registerAndBind("carol");
            try (TestClient carol = authedAs("carol")) {
                var inv = op(alice, aliceUuid, "team.invite", "target", carolUuid);
                assertTrue(inv.path("ok").asBoolean(), inv.toString());
                var accept = op(carol, carolUuid, "team.acceptInvite",
                        "inviteId", inv.path("b").path("inviteId").asLong());
                assertTrue(accept.path("ok").asBoolean(), accept.toString());
            }
            JsonNode carolJoined = reconnected.readPush();
            assertEquals("memberJoined", carolJoined.path("events").get(0).path("k").asText());
            assertEquals(carolUuid, carolJoined.path("events").get(0).path("uuid").asText());
            reconnected.close();
        }
    }

    @Test
    @Order(3)
    void unknownSidRebuildsFull() throws IOException {
        try (TestClient sub = authedAs("bob")) {
            JsonNode ok = sub.sub("team", TestClient.obj("uuid", bobUuid, "sid", "bogusSid123"));
            assertFalse(ok.path("sid").asText().isEmpty());
            assertNotEquals("bogusSid123", ok.path("sid").asText());
            assertEquals("f", sub.readPush().path("t").asText());
        }
    }

    @Test
    @Order(4)
    void unsubscribeStopsPush() throws IOException {
        try (TestClient sub = authedAs("eve")) {
            long s = sub.sub("team", TestClient.obj("uuid", uuidOf("eve"))).path("s").asLong();
            sub.readPush(); // 全量
            ObjectNode unsub = JsonNodeFactory.instance.objectNode();
            unsub.put("s", s);
            sub.send(FrameType.UNSUB, unsub);

            try (TestClient alice = authedAs("alice")) {
                var invited = op(alice, aliceUuid, "team.invite", "target", uuidOf("eve"));
                assertTrue(invited.path("ok").asBoolean(), invited.toString());
            }
            sub.setReadTimeout(500);
            assertThrows(IOException.class, sub::readPush, "退订后不应再收到推送");
        }
    }
}
