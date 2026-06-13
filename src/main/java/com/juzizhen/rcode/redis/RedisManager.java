package com.juzizhen.rcode.redis;

import com.juzizhen.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Singleton manager for the Jedis connection pool,
 * mirroring the init/reload/shutdown pattern used by {@link com.juzizhen.rcode.sql.SqlManager}.
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
     * Initializes the Redis connection pool with retry logic.
     *
     * @param config the mod configuration containing Redis connection properties
     * @return true if the connection was established successfully, false if all retries failed
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
     * Obtains a Jedis instance from the pool.
     * Callers MUST close the returned Jedis (try-with-resources recommended).
     *
     * @return a Jedis connection, or null if not connected
     */
    public Jedis getResource() {
        if (!connected || jedisPool == null) return null;
        return jedisPool.getResource();
    }

    /**
     * Reloads the Redis connection with new configuration.
     * Tears down the existing pool and re-initializes.
     *
     * @param config the new configuration
     * @return true if the new connection was established successfully
     */
    public boolean reload(Config config) {
        LOGGER.info("Reloading Redis Manager...");
        shutdown();
        return init(config);
    }

    /**
     * Shuts down the connection pool and releases all resources.
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
     * Returns whether the Redis connection is currently active.
     */
    public boolean isConnected() {
        return connected && jedisPool != null && !jedisPool.isClosed();
    }
}
