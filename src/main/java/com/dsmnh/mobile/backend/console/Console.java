package com.dsmnh.mobile.backend.console;

import com.dsmnh.mobile.backend.core.ServerContext;
import com.dsmnh.mobile.backend.net.ProtocolServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

/** 服务器控制台（stdin REPL）：节点注册码签发/撤销、节点列表、停机。1.0.0 的管理操作入口。 */
public final class Console implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(Console.class);

    private final ServerContext ctx;
    private final ProtocolServer appServer;
    private final ProtocolServer nodeServer;
    private volatile boolean running = true;

    public Console(ServerContext ctx, ProtocolServer appServer, ProtocolServer nodeServer) {
        this.ctx = ctx;
        this.appServer = appServer;
        this.nodeServer = nodeServer;
    }

    @Override
    public void run() {
        System.out.println("输入 help 查看命令。");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (running) {
                System.out.print("horizon> ");
                System.out.flush();
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                execute(line.trim());
            }
        } catch (Exception e) {
            LOG.info("控制台退出：{}", e.toString());
        }
    }

    private void execute(String line) {
        if (line.isEmpty()) {
            return;
        }
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";
        switch (cmd) {
            case "help" -> help();
            case "nodecode" -> nodeCode(arg.isEmpty() ? null : arg);
            case "noderestore" -> nodeRestore(arg);
            case "nodes" -> nodes();
            case "noderevoke" -> nodeRevoke(arg);
            case "stop" -> running = false;
            default -> System.out.println("未知命令，help 查看命令。");
        }
    }

    private void help() {
        System.out.println("""
                nodecode [名称]        签发新建节点注册码（15 分钟有效，只能注册一个子服）
                noderestore <节点ID>   为已撤销节点签发恢复注册码（保留原节点 ID 与数据）
                nodes                  列出节点
                noderevoke <节点ID>    撤销节点：凭证立即失效并断开其连接
                stop                   停止服务器""");
    }

    private void nodeCode(String name) {
        String code = ctx.auth.issueNodeCode("new", null, ctx.config.nodeCodeTtlSec);
        System.out.println("节点注册码：" + code + "（" + ctx.config.nodeCodeTtlSec + " 秒内有效"
                + (name == null ? "）" : "，子服自报名：" + name + "）"));
    }

    private void nodeRestore(String arg) {
        long nodeId = parseId(arg);
        if (nodeId < 0) {
            return;
        }
        var node = ctx.db.queryOne("SELECT status FROM nodes WHERE id = ?", nodeId);
        if (node.isEmpty()) {
            System.out.println("节点 " + nodeId + " 不存在。");
            return;
        }
        String code = ctx.auth.issueNodeCode("restore", nodeId, ctx.config.nodeCodeTtlSec);
        System.out.println("恢复注册码：" + code + "（用于节点 " + nodeId + "，"
                + ctx.config.nodeCodeTtlSec + " 秒内有效）");
    }

    private void nodes() {
        List<Map<String, Object>> rows = ctx.db.query(
                "SELECT id, name, status, last_seen FROM nodes ORDER BY id");
        if (rows.isEmpty()) {
            System.out.println("暂无节点。");
            return;
        }
        for (Map<String, Object> row : rows) {
            System.out.printf("节点 %s  名称=%s  状态=%s  最近在线=%s%n",
                    row.get("id"), row.get("name") == null ? "-" : row.get("name"),
                    row.get("status"), row.get("last_seen"));
        }
    }

    private void nodeRevoke(String arg) {
        long nodeId = parseId(arg);
        if (nodeId < 0) {
            return;
        }
        ctx.auth.revokeNode(nodeId);
        System.out.println("节点 " + nodeId + " 已撤销，在线连接已断开。");
    }

    private long parseId(String arg) {
        try {
            return Long.parseLong(arg);
        } catch (NumberFormatException e) {
            System.out.println("需要数字节点 ID。");
            return -1;
        }
    }
}
