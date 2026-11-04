package com.dsmnh.mobile.backend.core;

import com.dsmnh.mobile.backend.auth.AuthService;
import com.dsmnh.mobile.backend.config.Config;
import com.dsmnh.mobile.backend.net.RateLimiter;
import com.dsmnh.mobile.backend.store.Database;
import com.dsmnh.mobile.backend.sub.TeamStream;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 全局装配：配置、存储、鉴权、订阅流与业务线程池。业务逻辑一律在 biz 线程执行。 */
public final class ServerContext implements AutoCloseable {
    public final Config config;
    public final ObjectMapper mapper = new ObjectMapper();
    public final Database db;
    public final AuthService auth;
    public final TeamStream stream;
    public final com.dsmnh.mobile.backend.sub.NodeOpsStream opsStream;
    public final RateLimiter rateLimiter = new RateLimiter();
    /** 由入口在装配 Dispatcher 后回填。 */
    public volatile com.dsmnh.mobile.backend.api.Dispatcher dispatcher;

    private final ExecutorService biz = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "biz");
        t.setDaemon(true);
        return t;
    });

    public ServerContext(Config config) {
        this.config = config;
        this.db = new Database(config.dbPath);
        this.auth = new AuthService(config, db);
        this.stream = new TeamStream(this);
        this.opsStream = new com.dsmnh.mobile.backend.sub.NodeOpsStream(this);
    }

    public ExecutorService biz() {
        return biz;
    }

    @Override
    public void close() {
        biz.shutdown();
        try {
            if (!biz.awaitTermination(5, TimeUnit.SECONDS)) {
                biz.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stream.close();
        opsStream.close();
        db.close();
    }
}
