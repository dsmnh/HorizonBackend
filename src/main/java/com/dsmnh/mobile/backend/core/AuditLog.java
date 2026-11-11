package com.dsmnh.mobile.backend.core;

import com.dsmnh.mobile.backend.net.Connection;

/** 管理操作审计（企划书 03）：记录操作者、动作与来源，留存 30 天。 */
public final class AuditLog {
    private static final int RETENTION_SEC = 30 * 24 * 3600;

    private final ServerContext ctx;

    public AuditLog(ServerContext ctx) {
        this.ctx = ctx;
    }

    /** 记录协议侧的管理操作。 */
    public void record(Connection conn, String action, String detail) {
        write(actor(conn), action, detail, "protocol");
    }

    /** 记录控制台签发与管理操作。 */
    public void recordConsole(String action, String detail) {
        write("console", action, detail, "console");
    }

    private void write(String actor, String action, String detail, String source) {
        long now = System.currentTimeMillis() / 1000;
        ctx.db.update("DELETE FROM audit_log WHERE at < ?", now - RETENTION_SEC);
        ctx.db.update("INSERT INTO audit_log(at, actor, action, detail, source) VALUES(?,?,?,?,?)",
                now, actor, action, detail, source);
    }

    private String actor(Connection conn) {
        return conn.nodeId() >= 0 ? "node:" + conn.nodeId() : "account:" + conn.accountId();
    }
}
