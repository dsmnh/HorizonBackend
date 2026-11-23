package com.dsmnh.mobile.backend.sub;

import com.dsmnh.mobile.backend.core.ServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 后端定时任务（企划书 07）：每日凌晨 1 点榜单聚合，以及每小时过期数据清扫。 */
public final class BackendTasks implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BackendTasks.class);
    private static final int RANKINGS_HOUR = 1;
    private static final int METRICS_RETENTION_SEC = 3600;
    private static final int AUDIT_RETENTION_SEC = 30 * 24 * 3600;
    /** 下行操作重试周期：节点回报失败后只标记状态与退避时间，重发由本定时器驱动。 */
    private static final int OPS_SWEEP_SEC = 30;

    private final ServerContext ctx;
    private final int opsSweepSec;
    private ScheduledExecutorService scheduler;

    public BackendTasks(ServerContext ctx) {
        this(ctx, OPS_SWEEP_SEC);
    }

    /** 重试周期可注入，便于测试用秒级间隔验证定时重发。 */
    public BackendTasks(ServerContext ctx, int opsSweepSec) {
        this.ctx = ctx;
        this.opsSweepSec = opsSweepSec;
    }

    /** 启动调度；首次延迟按服务器系统时区计算到下一个凌晨 1 点。 */
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "backend-tasks");
            t.setDaemon(true);
            return t;
        });
        scheduleNextDaily();
        scheduler.scheduleAtFixedRate(this::safeSweep, 1, 1, TimeUnit.HOURS);
        // 下行操作重试：node.op.fail 只置 failed 与 next_retry_at，重发依赖本定时器。
        // 缺了这一步，节点回报「玩家不在线」的 op.set 会永久停在 failed，离线玩家上线后也不会补执行。
        scheduler.scheduleAtFixedRate(this::safeOpsSweep, opsSweepSec, opsSweepSec, TimeUnit.SECONDS);
    }

    private void scheduleNextDaily() {
        if (scheduler.isShutdown()) {
            return;
        }
        scheduler.schedule(() -> {
            safeDaily();
            scheduleNextDaily();
        }, secondsUntilNext(RANKINGS_HOUR), TimeUnit.SECONDS);
    }

    private void safeDaily() {
        try {
            ctx.rankings.dailyAggregation();
        } catch (Exception e) {
            LOG.warn("榜单聚合任务失败：{}", e.toString());
        }
    }

    private void safeOpsSweep() {
        try {
            ctx.opsStream.sweep();
        } catch (Exception e) {
            LOG.warn("下行操作重试清扫失败：{}", e.toString());
        }
    }

    private void safeSweep() {
        try {
            sweepExpired();
        } catch (Exception e) {
            LOG.warn("过期数据清扫失败：{}", e.toString());
        }
    }

    /** 聚合一次并向所有节点分发，供定时器与验证直接调用。 */
    public int dailyRankings() {
        return ctx.rankings.dailyAggregation();
    }

    /** 过期处理：BQ 快照 7 天、监控帧 1 小时、审计 30 天、每日限次记录按天清理。 */
    public void sweepExpired() {
        long now = System.currentTimeMillis() / 1000;
        ctx.db.update("UPDATE bq_snapshots SET status = 'expired', payload = '' "
                + "WHERE expires_at <= ? AND status IN ('pending','exported')", now);
        ctx.db.update("DELETE FROM metrics_frames WHERE at < ?", now - METRICS_RETENTION_SEC);
        ctx.db.update("DELETE FROM audit_log WHERE at < ?", now - AUDIT_RETENTION_SEC);
        ctx.db.update("DELETE FROM rank_sync_day WHERE day < ?", LocalDate.now().toEpochDay() - 1);
    }

    private long secondsUntilNext(int hour) {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = now.with(LocalTime.of(hour, 0));
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Math.max(1, Duration.between(now, next).getSeconds() + 1);
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
