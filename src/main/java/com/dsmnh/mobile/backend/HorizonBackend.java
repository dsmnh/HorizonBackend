package com.dsmnh.mobile.backend;

import com.dsmnh.mobile.backend.api.AccountOps;
import com.dsmnh.mobile.backend.api.Dispatcher;
import com.dsmnh.mobile.backend.api.NodeOps;
import com.dsmnh.mobile.backend.api.TeamOps;
import com.dsmnh.mobile.backend.config.Config;
import com.dsmnh.mobile.backend.console.Console;
import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.Plane;
import com.dsmnh.mobile.backend.net.ProtocolServer;

import java.nio.file.Path;

/** 后端入口：装配双平面服务器与控制台；--init 非交互生成默认配置。 */
public final class HorizonBackend {
    private HorizonBackend() {}

    public static void main(String[] args) throws Exception {
        boolean nonInteractive = false;
        Path configPath = Path.of("config.json");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--init" -> nonInteractive = true;
                case "--config" -> {
                    if (i + 1 < args.length) {
                        configPath = Path.of(args[++i]);
                    }
                }
                default -> {
                    System.err.println("用法：HorizonBackend [--init] [--config <路径>]");
                    return;
                }
            }
        }
        Config config = Config.loadOrCreate(configPath, nonInteractive);
        TeamOps team = null;
        try (ServerContext ctx = new ServerContext(config)) {
            AccountOps account = new AccountOps(ctx);
            NodeOps node = new NodeOps(ctx);
            team = new TeamOps(ctx);
            Dispatcher dispatcher = new Dispatcher(ctx, account, node, team);
            ctx.dispatcher = dispatcher;
            ctx.stream.bind(team);
            ctx.opsStream.start();

            ProtocolServer appServer = new ProtocolServer(ctx, Plane.APP);
            ProtocolServer nodeServer = new ProtocolServer(ctx, Plane.NODE);
            int appPort = appServer.start(config.appPort);
            int nodePort = nodeServer.start(config.nodePort);
            System.out.println("Horizon 后端已启动：App 平面 :" + appPort + "，节点平面 :" + nodePort);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                appServer.stop();
                nodeServer.stop();
            }));

            new Console(ctx, appServer, nodeServer).run();
            appServer.stop();
            nodeServer.stop();
            team.close();
        }
        System.out.println("Horizon 后端已停止。");
    }
}
