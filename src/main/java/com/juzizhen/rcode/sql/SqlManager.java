package com.juzizhen.rcode.sql;

import com.juzizhen.config.Config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

public class SqlManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqlManager.class);
    private static SqlManager instance;

    private HikariDataSource dataSource;
    private Jdbi jdbi;
    private final ExecutorService executorService;

    private SqlManager() {
        this.executorService = Executors.newCachedThreadPool(); // Use a cached thread pool for async DB operations
    }

    public static SqlManager getInstance() {
        if (instance == null) {
            instance = new SqlManager();
        }
        return instance;
    }

    public void init(Config config) {
        LOGGER.info("Initializing SQL Manager...");

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s",
                config.getDbHost(),
                config.getDbPort(),
                config.getDbName()));
        hikariConfig.setUsername(config.getDbUser());
        hikariConfig.setPassword(config.getDbPassword());
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.setPoolName("RedemptionCodeFabric-HikariCP");
        hikariConfig.setMaximumPoolSize(10); // Max pool size
        hikariConfig.setMinimumIdle(2); // Min idle connections
        hikariConfig.setConnectionTimeout(30000); // 30 seconds
        hikariConfig.setIdleTimeout(600000); // 10 minutes
        hikariConfig.setMaxLifetime(1800000); // 30 minutes

        try {
            this.dataSource = new HikariDataSource(hikariConfig);
            this.jdbi = Jdbi.create(dataSource);
            this.jdbi.installPlugin(new SqlObjectPlugin());
            LOGGER.info("SQL Manager initialized successfully.");

            // Run initial schema creation/update
            initializeSchema();
        } catch (Exception e) {
            LOGGER.error("Failed to initialize SQL Manager!", e);
            // Optionally, rethrow or handle more gracefully
        }
    }

    private void initializeSchema() {
        // This method will read SQL files from resources and execute them
        // For now, let's just create a simple table if it doesn't exist
        execute(handle -> {
            handle.execute("CREATE TABLE IF NOT EXISTS redemption_codes (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "code VARCHAR(255) NOT NULL UNIQUE," +
                    "item_json TEXT NOT NULL," +
                    "uses_left INT NOT NULL," +
                    "max_uses INT NOT NULL," +
                    "expiration_date BIGINT," + // Unix timestamp
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            handle.execute("CREATE TABLE IF NOT EXISTS redeemed_history (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "code_id INT NOT NULL," +
                    "player_uuid VARCHAR(36) NOT NULL," +
                    "player_name VARCHAR(255) NOT NULL," +
                    "redeemed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY (code_id) REFERENCES redemption_codes(id) ON DELETE CASCADE" +
                    ")");
            LOGGER.info("Database schema initialized/updated.");
            return null;
        }).exceptionally(e -> {
            LOGGER.error("Failed to initialize database schema!", e);
            return null;
        });
    }


    public void shutdown() {
        LOGGER.info("Shutting down SQL Manager...");
        if (dataSource != null) {
            dataSource.close();
        }
        if (executorService != null) {
            executorService.shutdown();
        }
        LOGGER.info("SQL Manager shut down.");
    }

    /**
     * Executes a database operation asynchronously in a separate thread.
     * The operation is wrapped in a transaction.
     *
     * @param callback The function to execute, receiving a JDBI Handle.
     * @param <R> The return type of the operation.
     * @return A CompletableFuture holding the result of the operation.
     */
    public <R> CompletableFuture<R> execute(Function<Handle, R> callback) {
        return CompletableFuture.supplyAsync(() -> jdbi.inTransaction(callback::apply), executorService);
    }

    /**
     * Provides an instance of a SqlObject DAO.
     *
     * @param daoType The class of the DAO interface.
     * @param <T> The type of the DAO.
     * @return An instance of the specified DAO.
     */
    public <T> T getDao(Class<T> daoType) {
        return jdbi.onDemand(daoType);
    }
}
