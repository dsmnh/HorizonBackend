package com.dsmnh.mobile.backend.support;

import com.dsmnh.mobile.backend.api.AccountOps;
import com.dsmnh.mobile.backend.api.Dispatcher;
import com.dsmnh.mobile.backend.api.NodeOps;
import com.dsmnh.mobile.backend.api.TeamOps;
import com.dsmnh.mobile.backend.config.Config;
import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.Plane;
import com.dsmnh.mobile.backend.net.ProtocolServer;

import java.nio.file.Files;
import java.nio.file.Path;

/** 测试台：临时库 + 双平面临时端口，预注册一个测试节点；每套独立互不污染。 */
public final class ServerHarness implements AutoCloseable {
    public final ServerContext ctx;
    public final int appPort;
    public final int nodePort;
    public final String nodeToken;
    private final ProtocolServer appServer;
    private final ProtocolServer nodeServer;
    private final Path dbFile;

    public ServerHarness() throws Exception {
        dbFile = Files.createTempFile("horizon-test-", ".db");
        Files.deleteIfExists(dbFile);
        Config config = new Config();
        config.dbPath = dbFile.toString();
        ctx = new ServerContext(config);
        TeamOps team = new TeamOps(ctx);
        Dispatcher dispatcher = new Dispatcher(ctx, new AccountOps(ctx), new NodeOps(ctx), team);
        ctx.dispatcher = dispatcher;
        ctx.stream.bind(team);
        ctx.opsStream.start();
        appServer = new ProtocolServer(ctx, Plane.APP);
        nodeServer = new ProtocolServer(ctx, Plane.NODE);
        appPort = appServer.start(0);
        nodePort = nodeServer.start(0);
        nodeToken = registerNode();
    }

    private String registerNode() throws Exception {
        String code = ctx.auth.issueNodeCode("new", null, ctx.config.nodeCodeTtlSec);
        try (TestClient n = new TestClient(nodePort)) {
            n.hello("node", "TestMod/1.0");
            var res = n.req("node.register", TestClient.obj("code", code, "nodeName", "测试子服"));
            if (!res.path("ok").asBoolean()) {
                throw new IllegalStateException("测试节点注册失败：" + res);
            }
            return res.path("b").path("token").asText();
        }
    }

    @Override
    public void close() {
        appServer.stop();
        nodeServer.stop();
        ctx.close();
        try {
            Files.deleteIfExists(dbFile);
            Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-wal"));
            Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-shm"));
        } catch (Exception ignored) {
            // 临时文件清理失败不影响测试结果
        }
    }
}
