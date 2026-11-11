package com.dsmnh.mobile.backend.protocol;

import com.dsmnh.mobile.backend.api.AccountOps;
import com.dsmnh.mobile.backend.api.Dispatcher;
import com.dsmnh.mobile.backend.api.NodeOps;
import com.dsmnh.mobile.backend.api.TeamOps;
import com.dsmnh.mobile.backend.config.Config;
import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.Frame;
import com.dsmnh.mobile.backend.net.FrameType;
import com.dsmnh.mobile.backend.net.Plane;
import com.dsmnh.mobile.backend.net.ProtocolServer;
import com.dsmnh.mobile.backend.net.TlsBinding;
import com.dsmnh.mobile.backend.net.TlsSupport;
import com.dsmnh.mobile.backend.support.ServerHarness;
import com.dsmnh.mobile.backend.support.TestClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.junit.jupiter.api.Assertions.*;

/** 契约 1.6.0 §9.1：TLS 可选外层、自签证书指纹与 flags 0x02 逐帧绑定。 */
class TlsBindingFlowTest {
    private static Path workDir;
    private static Path storePath;
    private static Config config;
    private static ServerContext ctx;
    private static ProtocolServer tlsServer;
    private static ServerHarness plainHarness;
    private static int tlsPort;
    private static String fingerprint;

    @BeforeAll
    static void setup() throws Exception {
        workDir = Files.createTempDirectory("horizon-tls-test-");
        storePath = workDir.resolve("horizon-tls.p12");
        config = new Config();
        config.dbPath = workDir.resolve("horizon.db").toString();
        config.tlsStorePath = storePath.toString();
        config.tlsStorePassword = "test-store-password";
        config.appTlsEnabled = true;
        ctx = new ServerContext(config);
        TeamOps team = new TeamOps(ctx);
        ctx.dispatcher = new Dispatcher(ctx, new AccountOps(ctx), new NodeOps(ctx), team);
        ctx.stream.bind(team);
        ctx.opsStream.start();
        TlsSupport.Material material = TlsSupport.loadOrCreate(config);
        fingerprint = material.fingerprint();
        tlsServer = new ProtocolServer(ctx, Plane.APP, material.context());
        tlsPort = tlsServer.start(0);
        plainHarness = new ServerHarness();
    }

    @AfterAll
    static void tearDown() {
        if (tlsServer != null) {
            tlsServer.stop();
        }
        if (ctx != null) {
            ctx.close();
        }
        if (plainHarness != null) {
            plainHarness.close();
        }
        for (String name : new String[]{"horizon-tls.p12", "horizon.db", "horizon.db-wal", "horizon.db-shm"}) {
            try {
                Files.deleteIfExists(workDir.resolve(name));
            } catch (IOException ignored) {
                // 临时文件清理失败不影响测试结果
            }
        }
        try {
            Files.deleteIfExists(workDir);
        } catch (IOException ignored) {
            // 同上
        }
    }

    private static SSLSocket trustedSocket() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(storePath)) {
            keyStore.load(in, config.tlsStorePassword.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        SSLSocket socket = (SSLSocket) ssl.getSocketFactory().createSocket("127.0.0.1", tlsPort);
        socket.setSoTimeout(5000);
        socket.startHandshake();
        return socket;
    }

    @Test
    void certificateIsReusedAndFingerprintIsSha256() throws Exception {
        assertNotNull(fingerprint);
        assertEquals(95, fingerprint.length(), "SHA-256 指纹应为 32 字节冒号分隔形式");
        assertTrue(fingerprint.matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"));
        // 再次加载应复用同一存储，指纹不变（轮换由运维替换文件触发）
        assertEquals(fingerprint, TlsSupport.loadOrCreate(config).fingerprint());
    }

    @Test
    void bindingRulesFollowTlsState() {
        assertTrue(TlsBinding.consistent(Frame.FLAG_ENCRYPTED, true));
        assertTrue(TlsBinding.consistent(0, false));
        assertFalse(TlsBinding.consistent(0, true));
        assertFalse(TlsBinding.consistent(Frame.FLAG_ENCRYPTED, false));
        Frame plain = Frame.of(FrameType.PING);
        assertEquals(Frame.FLAG_ENCRYPTED, TlsBinding.bind(plain, true).flags());
        assertEquals(0, TlsBinding.bind(plain, false).flags());
        Frame marked = new Frame(FrameType.PING, Frame.FLAG_ENCRYPTED, new byte[0]);
        assertSame(marked, TlsBinding.bind(marked, true));
        assertEquals(0, TlsBinding.bind(marked, false).flags());
    }

    @Test
    void plainConnectionRejectsEncryptedFlag() throws IOException {
        try (TestClient c = new TestClient(plainHarness.appPort)) {
            c.setReadTimeout(3000);
            c.send(FrameType.HELLO, TestClient.obj("v", 1, "app", "TestApp/1.0", "plane", "app"),
                    Frame.FLAG_ENCRYPTED);
            assertThrows(IOException.class, () -> c.expect(FrameType.HELLO_ACK));
        }
    }

    @Test
    void tlsConnectionRejectsMissingEncryptedFlag() throws Exception {
        try (SSLSocket socket = trustedSocket(); TestClient c = new TestClient(socket)) {
            c.setReadTimeout(3000);
            c.send(FrameType.HELLO, TestClient.obj("v", 1, "app", "TestApp/1.0", "plane", "app"));
            assertThrows(IOException.class, () -> c.expect(FrameType.HELLO_ACK));
        }
    }

    @Test
    void tlsHandshakeBindsEveryFrame() throws Exception {
        try (SSLSocket socket = trustedSocket(); TestClient c = new TestClient(socket)) {
            c.defaultFlags(Frame.FLAG_ENCRYPTED);
            c.hello("app", "TestApp/1.0");
            assertEquals(Frame.FLAG_ENCRYPTED, c.lastFlags, "HELLO_ACK 应带 0x02");
            assertNotNull(c.lastNonce);

            String name = "tls-user-" + System.nanoTime();
            JsonNode reg = c.req("account.register", TestClient.obj("name", name, "pass", "password-123"));
            assertTrue(reg.path("ok").asBoolean(), reg.toString());
            assertEquals(Frame.FLAG_ENCRYPTED, c.lastFlags, "预鉴权 RES 应带 0x02");

            String token = reg.path("b").path("token").asText();
            assertNull(c.auth(token));
            assertEquals(Frame.FLAG_ENCRYPTED, c.lastFlags, "AUTH_OK 应带 0x02");

            JsonNode uuids = c.req("account.uuids", TestClient.obj());
            assertTrue(uuids.path("ok").asBoolean());
            assertEquals(Frame.FLAG_ENCRYPTED, c.lastFlags, "鉴权后 RES 应带 0x02");

            c.send(FrameType.PING, null);
            c.expect(FrameType.PONG);
            assertEquals(Frame.FLAG_ENCRYPTED, c.lastFlags, "PONG 应带 0x02");
        }
    }
}
