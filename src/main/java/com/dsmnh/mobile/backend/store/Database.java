package com.dsmnh.mobile.backend.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * SQLite 访问：WAL 模式，全部读写经单线程队列串行执行（企划书 02 的受控写入队列）。
 * 调用方运行在业务线程，禁止在 Netty I/O 线程直接调用。
 */
public final class Database implements AutoCloseable {
    private final Connection conn;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "db-writer");
        t.setDaemon(true);
        return t;
    });

    public Database(String path) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA foreign_keys=ON");
            }
            createSchema();
        } catch (Exception e) {
            throw new IllegalStateException("无法打开数据库 " + path, e);
        }
    }

    /** 在数据库线程执行任务并等待结果；超时视为数据库阻塞，按规范中断而非无限等待。 */
    public <T> T call(Supplier<T> task) {
        Future<T> future = executor.submit(task::get);
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            future.cancel(true);
            throw new IllegalStateException("数据库操作失败或超时", e);
        }
    }

    public int update(String sql, Object... params) {
        return call(() -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 插入并返回自增主键。 */
    public long insert(String sql, Object... params) {
        return call(() -> {
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, params);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public List<Map<String, Object>> query(String sql, Object... params) {
        return call(() -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                            row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    return rows;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public Optional<Map<String, Object>> queryOne(String sql, Object... params) {
        List<Map<String, Object>> rows = query(sql, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private void bind(PreparedStatement ps, Object[] params) throws Exception {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private void createSchema() {
        for (String ddl : SCHEMA) {
            update(ddl);
        }
        migrate();
    }

    /** 既有库的就地迁移：1.1.0 起 accounts 增加 role 列。 */
    private void migrate() {
        List<Map<String, Object>> cols = query("PRAGMA table_info(accounts)");
        boolean hasRole = false;
        for (Map<String, Object> col : cols) {
            if ("role".equals(col.get("name"))) {
                hasRole = true;
            }
        }
        if (!hasRole) {
            update("ALTER TABLE accounts ADD COLUMN role TEXT NOT NULL DEFAULT 'user'");
        }
    }

    private static final String[] SCHEMA = {
        """
        CREATE TABLE IF NOT EXISTS accounts(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT NOT NULL UNIQUE,
          pass_hash TEXT NOT NULL,
          failed_count INTEGER NOT NULL DEFAULT 0,
          locked_until INTEGER NOT NULL DEFAULT 0,
          created_at INTEGER NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS sessions(
          id TEXT PRIMARY KEY,
          account_id INTEGER NOT NULL,
          token TEXT NOT NULL UNIQUE,
          kid TEXT NOT NULL,
          app TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          last_seen INTEGER NOT NULL,
          revoked_at INTEGER)
        """,
        """
        CREATE TABLE IF NOT EXISTS uuid_bindings(
          uuid TEXT PRIMARY KEY,
          account_id INTEGER NOT NULL,
          bound_at INTEGER NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS codes(
          code TEXT PRIMARY KEY,
          type TEXT NOT NULL,
          uuid TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          expires_at INTEGER NOT NULL,
          used_at INTEGER)
        """,
        """
        CREATE TABLE IF NOT EXISTS nodes(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT,
          token TEXT NOT NULL UNIQUE,
          kid TEXT NOT NULL UNIQUE,
          status TEXT NOT NULL DEFAULT 'active',
          created_at INTEGER NOT NULL,
          revoked_at INTEGER,
          last_seen INTEGER)
        """,
        """
        CREATE TABLE IF NOT EXISTS node_codes(
          code TEXT PRIMARY KEY,
          purpose TEXT NOT NULL,
          restore_node_id INTEGER,
          created_at INTEGER NOT NULL,
          expires_at INTEGER NOT NULL,
          used_at INTEGER,
          used_by INTEGER)
        """,
        """
        CREATE TABLE IF NOT EXISTS teams(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT NOT NULL UNIQUE,
          join_mode TEXT NOT NULL DEFAULT 'invite',
          created_at INTEGER NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS team_members(
          team_id INTEGER NOT NULL,
          uuid TEXT NOT NULL,
          role TEXT NOT NULL,
          joined_at INTEGER NOT NULL,
          PRIMARY KEY(team_id, uuid))
        """,
        """
        CREATE TABLE IF NOT EXISTS invites(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          team_id INTEGER NOT NULL,
          from_uuid TEXT NOT NULL,
          target_uuid TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'pending',
          created_at INTEGER NOT NULL,
          expires_at INTEGER NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS applies(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          team_id INTEGER NOT NULL,
          uuid TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'pending',
          created_at INTEGER NOT NULL,
          expires_at INTEGER NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS eu_bindings(
          node_id INTEGER NOT NULL,
          player TEXT NOT NULL,
          team_name TEXT NOT NULL,
          bound_at INTEGER NOT NULL,
          PRIMARY KEY(node_id, player))
        """,
        """
        CREATE TABLE IF NOT EXISTS chat_messages(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          scope TEXT NOT NULL,
          sender TEXT NOT NULL,
          recipient TEXT,
          text TEXT NOT NULL,
          srv TEXT,
          ts INTEGER NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS chat_friends(
          a TEXT NOT NULL,
          b TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          PRIMARY KEY(a, b))
        """,
        """
        CREATE TABLE IF NOT EXISTS chat_friend_requests(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          sender TEXT NOT NULL,
          recipient TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'pending',
          created_at INTEGER NOT NULL,
          expires_at INTEGER NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS chat_blocks(
          blocker TEXT NOT NULL,
          blocked TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          PRIMARY KEY(blocker, blocked))
        """,
        "CREATE INDEX IF NOT EXISTS idx_chat_msg ON chat_messages(scope, ts)",
        "CREATE INDEX IF NOT EXISTS idx_chat_peer ON chat_messages(scope, sender, recipient, ts)",
        "CREATE INDEX IF NOT EXISTS idx_sessions_account ON sessions(account_id)",
        """
        CREATE TABLE IF NOT EXISTS owner_codes(
          code TEXT PRIMARY KEY,
          purpose TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          expires_at INTEGER NOT NULL,
          used_at INTEGER,
          used_by INTEGER)
        """,
        """
        CREATE TABLE IF NOT EXISTS node_ops(
          op_id TEXT PRIMARY KEY,
          node_id INTEGER NOT NULL,
          type TEXT NOT NULL,
          payload TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'pending',
          created_at INTEGER NOT NULL,
          expires_at INTEGER NOT NULL,
          attempts INTEGER NOT NULL DEFAULT 0,
          next_retry_at INTEGER,
          fail_reason TEXT)
        """,
        "CREATE INDEX IF NOT EXISTS idx_node_ops_node ON node_ops(node_id, status)",
        "CREATE INDEX IF NOT EXISTS idx_invites_target ON invites(target_uuid, status)",
        "CREATE INDEX IF NOT EXISTS idx_invites_team ON invites(team_id, status)",
        "CREATE INDEX IF NOT EXISTS idx_applies_team ON applies(team_id, status)",
        "CREATE INDEX IF NOT EXISTS idx_members_uuid ON team_members(uuid)"
    };

    @Override
    public void close() {
        executor.shutdown();
        try (conn) {
            // WAL 检查点随连接关闭自动完成
        } catch (Exception e) {
            // 关闭失败不影响进程退出
        }
    }
}
