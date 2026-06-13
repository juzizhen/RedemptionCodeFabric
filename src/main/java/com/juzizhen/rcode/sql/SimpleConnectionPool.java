package com.juzizhen.rcode.sql;

import com.juzizhen.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生产级 SQL 连接池，对标 HikariCP 核心特性。
 * <p>
 * 特性清单：
 * <ul>
 *     <li><b>弹性池大小</b>：minIdle ~ maxPoolSize，按需伸缩</li>
 *     <li><b>连接生命周期回收</b>：超过 maxLifetime 的连接在归还时被淘汰</li>
 *     <li><b>空闲超时驱逐</b>：idle 超过 idleTimeout 且总数 > minIdle 时淘汰</li>
 *     <li><b>泄漏检测</b>：checkout 超过 leakDetectionThreshold 未归还则告警</li>
 *     <li><b>Keepalive 心跳</b>：定期对空闲连接执行 isValid() 探测</li>
 *     <li><b>借出验证</b>：每次 getConnection() 均验证连接有效性</li>
 *     <li><b>获取超时</b>：pool 满时等待 connectionTimeout，超时抛异常</li>
 *     <li><b>优雅关闭</b>：等待 in-use 连接归还，超时后强制关闭</li>
 *     <li><b>连接初始化 SQL</b>：新连接建立后执行 connectionInitSql（如果配置）</li>
 * </ul>
 * <p>
 * 配置项（redemptioncodefabric.properties）：
 * <pre>
 * pool.maxPoolSize=10
 * pool.minIdle=2
 * pool.connectionTimeout=30000
 * pool.idleTimeout=600000
 * pool.maxLifetime=1800000
 * pool.leakDetectionThreshold=60000
 * pool.keepaliveTime=300000
 * pool.validationTimeout=5000
 * pool.connectionInitSql=SELECT 1
 * </pre>
 */
public class SimpleConnectionPool {

    private static final Logger LOGGER = LoggerFactory.getLogger("RedemptionCodeFabric-SQLPool");

    private static final int DEFAULT_MAX_POOL_SIZE = 10;
    private static final int DEFAULT_MIN_IDLE = 2;
    private static final long DEFAULT_CONNECTION_TIMEOUT_MS = 30_000;   // 30s
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 600_000;        // 10min
    private static final long DEFAULT_MAX_LIFETIME_MS = 1_800_000;      // 30min
    private static final long DEFAULT_LEAK_DETECTION_MS = 60_000;       // 60s
    private static final long DEFAULT_KEEPALIVE_MS = 300_000;           // 5min
    private static final long DEFAULT_VALIDATION_TIMEOUT_MS = 5_000;    // 5s
    private static final long MAINTENANCE_INTERVAL_MS = 30_000;         // 30s
    private static final long SHUTDOWN_WAIT_MS = 30_000;               // 30s

    private static class PoolEntry {
        final Connection connection;
        final long createdAt;
        volatile long lastUsedAt;
        volatile long lastValidatedAt;
        volatile long checkedOutAt;

        PoolEntry(Connection connection) {
            this.connection = connection;
            long now = System.currentTimeMillis();
            this.createdAt = now;
            this.lastUsedAt = now;
            this.lastValidatedAt = now;
            this.checkedOutAt = 0;
        }

        boolean isAlive() {
            try {
                return !connection.isClosed();
            } catch (SQLException e) {
                return false;
            }
        }
    }

    // 连接信息
    private final String jdbcUrl;
    private final String user;
    private final String password;

    // 池配置
    private final int maxPoolSize;
    private final int minIdle;
    private final long connectionTimeout;
    private final long idleTimeout;
    private final long maxLifetime;
    private final long leakDetectionThreshold;
    private final long keepaliveTime;
    private final int validationTimeoutSec;
    private final String connectionInitSql;

    // 池状态
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final ConcurrentLinkedQueue<PoolEntry> idleQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Connection, PoolEntry> allEntries = new ConcurrentHashMap<>();

    // 后台维护调度器
    private final ScheduledExecutorService maintenanceScheduler;
    private volatile boolean isShutdown = false;

    /**
     * 初始化连接池。
     *
     * @param config 模组配置
     * @throws SQLException 无法创建任何初始连接时抛出
     */
    public SimpleConnectionPool(Config config) throws SQLException {
        this.jdbcUrl = config.getSqlUrl();
        this.user = config.getDbUser();
        this.password = config.getDbPassword();

        // 读取池配置（缺失则使用默认值）
        this.maxPoolSize = Config.getInt("pool.maxPoolSize", DEFAULT_MAX_POOL_SIZE);
        this.minIdle = Config.getInt("pool.minIdle", DEFAULT_MIN_IDLE);
        this.connectionTimeout = Config.getInt("pool.connectionTimeout", (int) DEFAULT_CONNECTION_TIMEOUT_MS);
        this.idleTimeout = Config.getInt("pool.idleTimeout", (int) DEFAULT_IDLE_TIMEOUT_MS);
        this.maxLifetime = Config.getInt("pool.maxLifetime", (int) DEFAULT_MAX_LIFETIME_MS);
        this.leakDetectionThreshold = Config.getInt("pool.leakDetectionThreshold", (int) DEFAULT_LEAK_DETECTION_MS);
        this.keepaliveTime = Config.getInt("pool.keepaliveTime", (int) DEFAULT_KEEPALIVE_MS);
        this.validationTimeoutSec = Config.getInt("pool.validationTimeout", (int) DEFAULT_VALIDATION_TIMEOUT_MS) / 1000;
        this.connectionInitSql = Config.getString("pool.connectionInitSql", "");

        // 加载 JDBC 驱动
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL driver not found", e);
        }

        // 预热：创建 minIdle 个连接
        int initialCount = Math.min(minIdle, maxPoolSize);
        for (int i = 0; i < initialCount; i++) {
            PoolEntry entry = createPoolEntry();
            if (entry != null) {
                idleQueue.offer(entry);
                allEntries.put(entry.connection, entry);
                totalConnections.incrementAndGet();
            }
        }

        if (totalConnections.get() == 0) {
            throw new SQLException("Failed to create any initial SQL connections");
        }

        // 启动后台维护任务
        maintenanceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RCF-Pool-Maintenance");
            t.setDaemon(true);
            return t;
        });
        maintenanceScheduler.scheduleWithFixedDelay(
                this::runMaintenance,
                MAINTENANCE_INTERVAL_MS,
                MAINTENANCE_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        LOGGER.info("Connection pool created: maxPoolSize={}, minIdle={}, connectionTimeout={}ms, " +
                        "idleTimeout={}ms, maxLifetime={}ms, leakDetection={}ms, keepalive={}ms, initialCount={}",
                maxPoolSize, minIdle, connectionTimeout, idleTimeout, maxLifetime,
                leakDetectionThreshold, keepaliveTime, totalConnections.get());
    }

    /**
     * 从池中获取一个有效连接。
     * <p>
     * 流程（对标 HikariCP getConnection）：
     * <ol>
     *     <li>尝试从 idle queue 取出一个空闲连接</li>
     *     <li>验证连接有效性 + 生命周期，无效则淘汰并继续尝试</li>
     *     <li>如果 idle queue 为空且 total < maxPoolSize，创建新连接</li>
     *     <li>如果 total == maxPoolSize，等待 connectionTimeout 毫秒</li>
     *     <li>超时仍无可用连接则抛出异常</li>
     * </ol>
     *
     * @return 有效的 JDBC 连接
     * @throws RuntimeException 获取失败时抛出
     */
    public Connection getConnection() {
        if (isShutdown) {
            throw new RuntimeException("Connection pool is shut down");
        }

        long startTime = System.nanoTime();
        long deadlineNanos = startTime + TimeUnit.MILLISECONDS.toNanos(connectionTimeout);

        while (!isShutdown) {
            // === Step 1: 尝试从空闲队列获取 ===
            PoolEntry entry = idleQueue.poll();

            if (entry != null) {
                // 检查生命周期
                if (isExpired(entry)) {
                    closeAndRemove(entry, "maxLifetime reached on borrow");
                    continue;
                }
                // 检查有效性
                if (!validateConnection(entry)) {
                    closeAndRemove(entry, "validation failed on borrow");
                    continue;
                }
                // 标记为使用中
                entry.checkedOutAt = System.currentTimeMillis();
                entry.lastUsedAt = entry.checkedOutAt;
                return entry.connection;
            }

            // === Step 2: 尝试创建新连接 ===
            int current;
            while ((current = totalConnections.get()) < maxPoolSize) {
                if (totalConnections.compareAndSet(current, current + 1)) {
                    PoolEntry newEntry = createPoolEntry();
                    if (newEntry != null) {
                        allEntries.put(newEntry.connection, newEntry);
                        newEntry.checkedOutAt = System.currentTimeMillis();
                        newEntry.lastUsedAt = newEntry.checkedOutAt;
                        return newEntry.connection;
                    } else {
                        // 创建失败，回退计数
                        totalConnections.decrementAndGet();
                        break;
                    }
                }
                // CAS 失败 → 重试
            }

            // === Step 3: 池已满，等待归还 ===
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new RuntimeException(
                        String.format("Connection acquisition timed out after %dms. Pool stats: %s",
                                connectionTimeout, getPoolStats()));
            }

            try {
                long waitMs = Math.min(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 250);
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for connection", e);
            }
        }

        throw new RuntimeException("Connection pool is shut down");
    }

    /**
     * 归还连接到池中。
     * <p>
     * 流程（对标 HikariCP releaseConnection）：
     * <ol>
     *     <li>清除 checkout 标记</li>
     *     <li>如果连接已死或超过 maxLifetime → 关闭并从池中移除</li>
     *     <li>否则 → 重置 autoCommit 并放回 idle queue</li>
     * </ol>
     *
     * @param conn 要归还的连接
     */
    public void releaseConnection(Connection conn) {
        if (conn == null) return;

        PoolEntry entry = allEntries.get(conn);
        if (entry == null) {
            // 未知连接，直接关闭
            closeQuietly(conn);
            return;
        }

        // 清除 checkout 标记
        entry.checkedOutAt = 0;
        entry.lastUsedAt = System.currentTimeMillis();

        if (isShutdown) {
            closeAndRemove(entry, "pool shutting down");
            return;
        }

        // 检查连接是否应该被淘汰
        if (isExpired(entry)) {
            closeAndRemove(entry, "maxLifetime reached on release");
            return;
        }

        if (!entry.isAlive()) {
            closeAndRemove(entry, "connection dead on release");
            return;
        }

        // 重置 autoCommit（对标 HikariCP resetConnectionState）
        try {
            if (!conn.getAutoCommit()) {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            closeAndRemove(entry, "failed to reset autoCommit");
            return;
        }

        // 归还到空闲队列
        if (!idleQueue.offer(entry)) {
            // 队列异常（理论上不会发生，ConcurrentLinkedQueue 无界）
            closeAndRemove(entry, "failed to return to idle queue");
        }
    }

    public int getIdleConnections() {
        return idleQueue.size();
    }

    public int getActiveConnections() {
        return totalConnections.get() - idleQueue.size();
    }

    public int getTotalConnections() {
        return totalConnections.get();
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public String getPoolStats() {
        return String.format("[total=%d, active=%d, idle=%d, max=%d]",
                getTotalConnections(), getActiveConnections(), getIdleConnections(), maxPoolSize);
    }

    /**
     * 定期维护任务（对标 HikariCP housekeeper）。
     */
    private void runMaintenance() {
        if (isShutdown) return;

        try {
            detectLeaks();
            performKeepalive();
            evictIdleConnections();
            refillIfNeeded();
        } catch (Exception e) {
            LOGGER.warn("Pool maintenance task encountered an error", e);
        }
    }

    /**
     * 泄漏检测（对标 HikariCP leakDetection）。
     * <p>
     * 如果连接被借出后超过 leakDetectionThreshold 毫秒仍未归还，
     * 记录警告日志（不关闭连接，仅告警）。
     */
    private void detectLeaks() {
        if (leakDetectionThreshold <= 0) return;

        long now = System.currentTimeMillis();
        for (Map.Entry<Connection, PoolEntry> mapEntry : allEntries.entrySet()) {
            PoolEntry entry = mapEntry.getValue();
            long checkoutTime = entry.checkedOutAt;
            if (checkoutTime > 0 && (now - checkoutTime) > leakDetectionThreshold) {
                LOGGER.warn("⚠ Connection leak detected! Connection has been in use for {}ms " +
                                "(threshold: {}ms). Created at: {}",
                        now - checkoutTime, leakDetectionThreshold, entry.createdAt);
            }
        }
    }

    /**
     * Keepalive 心跳（对标 HikariCP keepaliveTime）。
     * <p>
     * 对空闲队列中的连接执行 isValid() 验证，淘汰失效连接。
     * 仅验证超过 keepaliveTime 未验证的连接，避免过于频繁。
     */
    private void performKeepalive() {
        if (keepaliveTime <= 0) return;

        long now = System.currentTimeMillis();
        int validated = 0;

        for (PoolEntry entry : idleQueue) {
            if (isShutdown) break;

            // 仅验证超过 keepaliveTime 未验证的连接
            if ((now - entry.lastValidatedAt) < keepaliveTime) continue;

            if (!validateConnection(entry)) {
                idleQueue.remove(entry);
                closeAndRemove(entry, "keepalive validation failed");
            } else {
                validated++;
            }
        }

        if (validated > 0) {
            LOGGER.debug("Keepalive: validated {} idle connections", validated);
        }
    }

    /**
     * 空闲驱逐（对标 HikariCP idleTimeout）。
     * <p>
     * 淘汰空闲时间超过 idleTimeout 的连接，但保证总数不低于 minIdle。
     */
    private void evictIdleConnections() {
        if (idleTimeout <= 0) return;

        long now = System.currentTimeMillis();
        int evicted = 0;

        Iterator<PoolEntry> it = idleQueue.iterator();
        while (it.hasNext() && !isShutdown) {
            PoolEntry entry = it.next();

            // 保留 minIdle 个连接
            if (totalConnections.get() <= minIdle) break;

            long idleTime = now - entry.lastUsedAt;
            if (idleTime > idleTimeout) {
                it.remove();
                closeAndRemove(entry, "idleTimeout exceeded (idle for " + idleTime + "ms)");
                evicted++;
            }
        }

        if (evicted > 0) {
            LOGGER.debug("Evicted {} idle connections (idleTimeout={}ms)", evicted, idleTimeout);
        }
    }

    /**
     * 补充连接（对标 HikariCP minIdle 保证）。
     * <p>
     * 如果总连接数低于 minIdle，创建新连接填充。
     */
    private void refillIfNeeded() {
        while (totalConnections.get() < minIdle && !isShutdown) {
            int current = totalConnections.get();
            if (current >= maxPoolSize) break;

            if (totalConnections.compareAndSet(current, current + 1)) {
                PoolEntry entry = createPoolEntry();
                if (entry != null) {
                    allEntries.put(entry.connection, entry);
                    idleQueue.offer(entry);
                    LOGGER.debug("Refill: created new connection to maintain minIdle (total={})",
                            totalConnections.get());
                } else {
                    totalConnections.decrementAndGet();
                    break;
                }
            }
        }
    }

    private boolean validateConnection(PoolEntry entry) {
        try {
            if (entry.connection.isClosed()) return false;
            boolean valid = entry.connection.isValid(Math.max(validationTimeoutSec, 1));
            if (valid) {
                entry.lastValidatedAt = System.currentTimeMillis();
            }
            return valid;
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean isExpired(PoolEntry entry) {
        if (maxLifetime <= 0) return false;
        return (System.currentTimeMillis() - entry.createdAt) > maxLifetime;
    }

    private PoolEntry createPoolEntry() {
        try {
            Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
            conn.setAutoCommit(true);

            // 执行连接初始化 SQL（对标 HikariCP connectionInitSql）
            if (connectionInitSql != null && !connectionInitSql.isEmpty()) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(connectionInitSql);
                }
            }

            return new PoolEntry(conn);
        } catch (SQLException e) {
            LOGGER.error("Failed to create SQL connection: {}", e.getMessage());
            return null;
        }
    }

    private void closeAndRemove(PoolEntry entry, String reason) {
        allEntries.remove(entry.connection);
        totalConnections.decrementAndGet();
        closeQuietly(entry.connection);
        LOGGER.debug("Connection removed: {} ({})", reason, getPoolStats());
    }

    private void closeQuietly(Connection conn) {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException ignored) {
        }
    }

    /**
     * 优雅关闭连接池（对标 HikariCP shutdown）。
     */
    public void shutdown() {
        if (isShutdown) return;
        isShutdown = true;
        LOGGER.info("Shutting down connection pool... {}", getPoolStats());

        // 停止维护调度器
        maintenanceScheduler.shutdown();
        try {
            if (!maintenanceScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                maintenanceScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            maintenanceScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 排空空闲队列
        PoolEntry entry;
        while ((entry = idleQueue.poll()) != null) {
            closeAndRemove(entry, "shutdown draining idle");
        }

        // 等待 in-use 连接归还
        long deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_MS;
        while (totalConnections.get() > 0 && System.currentTimeMillis() < deadline) {
            LOGGER.info("Waiting for {} active connection(s) to be returned...",
                    totalConnections.get());
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 强制关闭仍存活的连接
        for (Map.Entry<Connection, PoolEntry> mapEntry : allEntries.entrySet()) {
            PoolEntry e = mapEntry.getValue();
            LOGGER.warn("Force-closing connection still in use (created {}ms ago)",
                    System.currentTimeMillis() - e.createdAt);
            closeAndRemove(e, "force close on shutdown");
        }

        LOGGER.info("Connection pool shut down complete.");
    }
}
