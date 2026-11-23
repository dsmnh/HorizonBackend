package com.dsmnh.mobile.backend.protocol;

import com.dsmnh.mobile.backend.sub.BackendTasks;
import com.dsmnh.mobile.backend.support.ServerHarness;
import com.dsmnh.mobile.backend.support.TestClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 下行操作重试由后端定时器驱动：失败的操作在退避到期后自动换新实例重发。 */
class OpsRetrySchedulerTest {

    private static int pendingCount(ServerHarness harness, String marker) {
        var rows = harness.ctx.db.query(
                "SELECT COUNT(*) c FROM node_ops WHERE status = 'pending' AND payload LIKE ?", "%" + marker + "%");
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get("c")).intValue();
    }

    @Test
    void retriesPreserveFailureCountAndOriginalExpiry() throws Exception {
        try (ServerHarness harness = new ServerHarness()) {
            var db = harness.ctx.db;
            long expiry = System.currentTimeMillis() / 1000 + 600;
            String id = harness.ctx.opsStream.enqueue(1, "op.set", TestClient.obj("player", "retry"));
            db.update("UPDATE node_ops SET status='failed', attempts=2, next_retry_at=0, expires_at=? WHERE op_id=?",
                    expiry, id);
            harness.ctx.opsStream.sweep();
            var retry = db.queryOne("SELECT * FROM node_ops WHERE status='pending'").orElseThrow();
            assertEquals(2, ((Number) retry.get("attempts")).intValue());
            assertEquals(expiry, ((Number) retry.get("expires_at")).longValue());
            try (TestClient n = new TestClient(harness.nodePort)) {
                n.hello("node", "TestMod/1.0");
                n.auth(harness.nodeToken);
                assertTrue(n.req("node.op.fail", TestClient.obj("opId", retry.get("op_id"))).path("ok").asBoolean());
            }
            var failed = db.queryOne("SELECT attempts, next_retry_at FROM node_ops WHERE op_id=?", retry.get("op_id")).orElseThrow();
            assertEquals(3, ((Number) failed.get("attempts")).intValue());
            assertTrue(((Number) failed.get("next_retry_at")).longValue() >= expiry - 360);
        }
    }

    @Test
    void expiredAndExhaustedOperationsCannotBeRequeued() throws Exception {
        try (ServerHarness harness = new ServerHarness()) {
            var db = harness.ctx.db;
            for (boolean expired : new boolean[]{true, false}) {
                String id = harness.ctx.opsStream.enqueue(1, "op.set", TestClient.obj("player", "terminal"));
                db.update("UPDATE node_ops SET status='failed', attempts=?, next_retry_at=0 WHERE op_id=?",
                        expired ? 1 : 8, id);
                if (expired) db.update("UPDATE node_ops SET expires_at=0 WHERE op_id=?", id);
            }
            harness.ctx.opsStream.sweep();
            var rows = db.query("SELECT status FROM node_ops");
            assertEquals(2, rows.size());
            assertTrue(rows.stream().allMatch(row -> "expired".equals(row.get("status"))));
        }
    }

    @Test
    void failedOpIsResentBySchedulerWithoutManualSweep() throws Exception {
        try (ServerHarness harness = new ServerHarness()) {
            String marker = "sched-retry-uuid";
            String opId = harness.ctx.opsStream.enqueue(1, "op.set",
                    TestClient.obj("player", marker, "grant", true));
            // 模拟节点回报「玩家不在线」：opFail 置 failed 并按 attempts 安排退避
            try (TestClient n = new TestClient(harness.nodePort)) {
                n.hello("node", "TestMod/1.0");
                n.auth(harness.nodeToken);
                assertTrue(n.req("node.op.fail", TestClient.obj("opId", opId, "reason", "player not online"))
                        .path("ok").asBoolean());
            }
            // 退避到期（首轮失败 next_retry_at = now + 60s，测试直接把到期时间拉到过去）
            harness.ctx.db.update("UPDATE node_ops SET next_retry_at = 0 WHERE op_id = ?", opId);
            assertEquals(0, pendingCount(harness, marker), "退避到期后仍应等定时器驱动");

            BackendTasks tasks = new BackendTasks(harness.ctx, 1);
            try {
                tasks.start();
                long deadline = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < deadline && pendingCount(harness, marker) == 0) {
                    Thread.sleep(100);
                }
                assertEquals(1, pendingCount(harness, marker), "定时器应在退避到期后重发新实例");
                Map<String, Object> old = harness.ctx.db.queryOne(
                        "SELECT status FROM node_ops WHERE op_id = ?", opId).orElseThrow();
                assertEquals("abandoned", old.get("status"), "旧实例应被终结为 abandoned");
            } finally {
                tasks.close();
            }
        }
    }
}
