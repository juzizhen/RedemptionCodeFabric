package com.juzizhen.redemptioncodefabric.rcode.sql;

import com.juzizhen.redemptioncodefabric.async.AsyncIoManager;
import com.juzizhen.redemptioncodefabric.config.Config;
import com.juzizhen.redemptioncodefabric.util.MessageUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 轻量级 SQL 管理器，基于原生 JDBC 和 {@link SimpleConnectionPool}。
 * 所有数据库操作通过 {@link AsyncIoManager#getIoExecutor()} 异步执行。
 */
public class SqlManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("RedemptionCodeFabric-SQL");
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 2000;

    private static SqlManager instance;

    private volatile SimpleConnectionPool connectionPool;
    private volatile boolean connected = false;
    private MinecraftServer cachedServer;
    private final List<Runnable> shutdownCallbacks = new ArrayList<>();

    private SqlManager() {
    }

    public static synchronized SqlManager getInstance() {
        if (instance == null) {
            instance = new SqlManager();
        }
        return instance;
    }

    /**
     * 初始化 SQL 连接池（带重试逻辑），返回是否成功。
     */
    public boolean init(Config config) {
        shutdown();

        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                LOGGER.info("Attempting SQL connection (attempt {}/{})...", attempt, MAX_RETRY_ATTEMPTS);

                this.connectionPool = new SimpleConnectionPool(config);
                this.connected = true;

                initializeSchema();

                LOGGER.info("SQL Manager initialized successfully (attempt {}).", attempt);
                return true;

            } catch (Exception e) {
                LOGGER.error("SQL connection attempt {} failed: {}", attempt, e.getMessage());
                closePool();

                if (attempt < MAX_RETRY_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        LOGGER.warn("SQL connection retry interrupted.");
                        break;
                    }
                }
            }
        }

        connected = false;
        LOGGER.error("========================================");
        LOGGER.error(" ALL SQL CONNECTION ATTEMPTS FAILED!");
        LOGGER.error(" Switching to FILE storage mode.");
        LOGGER.error("========================================");
        sendAlertToOps();
        return false;
    }

    /**
     * 初始化数据库表结构（如果不存在）。
     * 同步执行，确保在 init() 返回前建表完成。
     */
    private void initializeSchema() {
        if (!connected || connectionPool == null) return;

        Connection conn = null;
        try {
            conn = connectionPool.getConnection();

            String createCodesTable = """
                    CREATE TABLE IF NOT EXISTS redemption_codes (
                        code VARCHAR(255) PRIMARY KEY,
                        type VARCHAR(50) NOT NULL,
                        reward TEXT NOT NULL,
                        player TEXT,
                        count INT DEFAULT -1,
                        start_time BIGINT DEFAULT 0,
                        end_time BIGINT DEFAULT 0,
                        code_interval BIGINT DEFAULT 0,
                        used_by LONGTEXT
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                    """;

            try (var stmt = conn.prepareStatement(createCodesTable)) {
                stmt.execute();
            }

            String createLogsTable = """
                    CREATE TABLE IF NOT EXISTS operation_logs (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        timestamp BIGINT NOT NULL,
                        operation_type VARCHAR(50) NOT NULL,
                        executor VARCHAR(255) NOT NULL,
                        details LONGTEXT
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                    """;

            try (var stmt = conn.prepareStatement(createLogsTable)) {
                stmt.execute();
            }

            LOGGER.info("Database schema initialized.");

        } catch (SQLException e) {
            LOGGER.error("Failed to initialize database schema", e);
        } finally {
            if (conn != null) {
                connectionPool.releaseConnection(conn);
            }
        }
    }

    /**
     * 向在线 OP 发送 SQL 连接失败警报。
     */
    private void sendAlertToOps() {
        if (cachedServer == null) return;

        cachedServer.execute(() -> {
            for (ServerPlayerEntity player : cachedServer.getPlayerManager().getPlayerList()) {
                if (cachedServer.getPlayerManager().isOperator(player.getGameProfile())) {
                    player.sendMessage(
                            MessageUtils.createText(player.getCommandSource(), "redemptioncodefabric.message.sql_fallback_alert")
                                    .copy().formatted(Formatting.RED, Formatting.BOLD),
                            false
                    );
                }
            }
        });
    }

    public void setServer(MinecraftServer server) {
        this.cachedServer = server;
    }

    /**
     * 注册关闭前回调（如批处理器刷新），在连接池关闭之前执行。
     */
    public void addShutdownCallback(Runnable callback) {
        shutdownCallbacks.add(callback);
    }

    /**
     * 关闭 SQL 连接池。先执行所有关闭前回调（刷新待写入数据），再关闭连接池。
     */
    public void shutdown() {
        if (connectionPool != null) {
            LOGGER.info("Shutting down SQL Manager...");
            for (Runnable callback : shutdownCallbacks) {
                try {
                    callback.run();
                } catch (Exception e) {
                    LOGGER.warn("Shutdown callback failed", e);
                }
            }
            shutdownCallbacks.clear();
            closePool();
            connected = false;
            LOGGER.info("SQL Manager shut down.");
        }
    }

    private void closePool() {
        if (connectionPool != null) {
            connectionPool.shutdown();
            connectionPool = null;
        }
    }

    public boolean isConnected() {
        return connected && connectionPool != null;
    }

    public SimpleConnectionPool getConnectionPool() {
        return connectionPool;
    }

}

