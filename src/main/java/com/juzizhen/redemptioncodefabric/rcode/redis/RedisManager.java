package com.juzizhen.redemptioncodefabric.rcode.redis;

import com.juzizhen.redemptioncodefabric.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Jedis 连接池的单例管理器，
 * 沿用 {@link com.juzizhen.redemptioncodefabric.rcode.sql.SqlManager} 的 init/reload/shutdown 模式。
 */
public class RedisManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("RedemptionCodeFabric-Redis");
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 2000;

    private static RedisManager instance;

    private JedisPool jedisPool;
    private boolean connected = false;

    private RedisManager() {
    }

    public static synchronized RedisManager getInstance() {
        if (instance == null) {
            instance = new RedisManager();
        }
        return instance;
    }

    /**
     * 初始化 Redis 连接池，带重试逻辑。
     *
     * @param config 包含 Redis 连接属性的 mod 配置
     * @return 连接成功返回 true，所有重试均失败返回 false
     */
    public boolean init(Config config) {
        shutdown();

        String host = config.getRedisHost();
        int port = config.getRedisPort();
        String password = config.getRedisPassword();
        int database = config.getRedisDatabase();

        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                LOGGER.info("Attempting Redis connection (attempt {}/{})...", attempt, MAX_RETRY_ATTEMPTS);

                JedisPoolConfig poolConfig = new JedisPoolConfig();
                poolConfig.setMaxTotal(10);
                poolConfig.setMaxIdle(5);
                poolConfig.setMinIdle(1);
                poolConfig.setTestOnBorrow(true);
                poolConfig.setTestOnReturn(true);
                poolConfig.setTestWhileIdle(true);

                String pwd = (password == null || password.isEmpty()) ? null : password;

                this.jedisPool = new JedisPool(poolConfig, host, port, 10000, pwd, database);

                try (Jedis jedis = jedisPool.getResource()) {
                    String pong = jedis.ping();
                    if (!"PONG".equalsIgnoreCase(pong)) {
                        throw new RuntimeException("Redis PING returned: " + pong);
                    }
                }

                this.connected = true;
                LOGGER.info("Redis Manager initialized successfully (attempt {}).", attempt);
                return true;

            } catch (Exception e) {
                LOGGER.error("Redis connection attempt {} failed: {}", attempt, e.getMessage());
                closePool();

                if (attempt < MAX_RETRY_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        LOGGER.warn("Redis connection retry interrupted.");
                        break;
                    }
                }
            }
        }

        connected = false;
        LOGGER.error("========================================");
        LOGGER.error(" ALL REDIS CONNECTION ATTEMPTS FAILED!");
        LOGGER.error(" Falling back to FILE storage mode.");
        LOGGER.error("========================================");
        return false;
    }

    /**
     * 从连接池获取一个 Jedis 实例。
     * 调用方必须关闭返回的 Jedis（推荐 try-with-resources）。
     *
     * @return Jedis 连接；未连接时返回 null
     */
    public Jedis getResource() {
        if (!connected || jedisPool == null) return null;
        return jedisPool.getResource();
    }

    /**
     * 关闭连接池并释放所有资源。
     */
    public void shutdown() {
        if (jedisPool != null) {
            LOGGER.info("Shutting down Redis Manager...");
            closePool();
            connected = false;
            LOGGER.info("Redis Manager shut down.");
        }
    }

    private void closePool() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
            } catch (Exception e) {
                LOGGER.warn("Error closing Jedis pool: {}", e.getMessage());
            }
        }
        jedisPool = null;
    }

    /**
     * 返回当前 Redis 连接是否处于可用状态。
     */
    public boolean isConnected() {
        return connected && jedisPool != null && !jedisPool.isClosed();
    }
}
