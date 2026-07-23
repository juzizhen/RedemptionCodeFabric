package com.juzizhen.redemptioncodefabric.rcode.web;

import com.juzizhen.redemptioncodefabric.config.Config;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 安全过滤拦截器：统一处理安全响应头注入、IP 限流和动态 Token 鉴权。
 * <p>
 * 使用独立的 Redis 连接池（web.redis.* 配置），与数据存储的 RedisManager 完全隔离。
 * 当 web.redis.enabled=true 且连接池可用时走 Redis；否则降级为内存模式。
 */
public class SecurityFilter extends Filter {

    private static final Logger LOGGER = LoggerFactory.getLogger("RedemptionCodeFabric-Web");

    private static final String REDIS_KEY_TOKEN = "admin:active_token";
    private static final int TOKEN_TTL_SECONDS = 1800; // 30 分钟
    // ── 内存降级：限流 ──
    private static final ConcurrentHashMap<String, RateBucket> memoryRateBuckets = new ConcurrentHashMap<>();
    // ── 独立 Redis 连接池（web.redis.* 配置） ──
    private static volatile JedisPool webRedisPool = null;
    // ── 内存降级：单 Token ──
    private static volatile String memoryActiveToken = null;
    private static volatile long memoryTokenExpireAt = 0;

    /**
     * 初始化 Web 安全层专用 Redis 连接池。
     * 由 WebServer.start() 调用，使用 web.redis.* 配置项。
     * 连接失败时不抛异常，仅记录日志，运行时自动降级为内存模式。
     */
    public static void initPool() {
        shutdownPool();
        if (!Config.getBoolean("web.redis.enabled", false)) {
            LOGGER.info("Web Redis disabled, SecurityFilter will use in-memory mode.");
            return;
        }

        String host = Config.getString("web.redis.host", "localhost");
        int port = Config.getInt("web.redis.port", 6379);
        String password = Config.getString("web.redis.password", "");
        int database = Config.getInt("web.redis.database", 0);

        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(4);
            poolConfig.setMaxIdle(2);
            poolConfig.setMinIdle(0);
            poolConfig.setTestOnBorrow(true);

            String pwd = (password == null || password.isEmpty()) ? null : password;
            webRedisPool = new JedisPool(poolConfig, host, port, 3000, pwd, database);

            // 验证连接
            try (Jedis jedis = webRedisPool.getResource()) {
                String pong = jedis.ping();
                if (!"PONG".equalsIgnoreCase(pong)) {
                    throw new RuntimeException("Redis PING returned: " + pong);
                }
            }
            LOGGER.info("Web Redis connected successfully ({}:{}, db={}).", host, port, database);
        } catch (Exception e) {
            LOGGER.warn("Web Redis connection failed ({}:{}), SecurityFilter will use in-memory mode. Error: {}",
                    host, port, e.getMessage());
            closePool();
        }
    }

    /**
     * 关闭 Web 安全层 Redis 连接池。由 WebServer.stop() 调用。
     */
    public static void shutdownPool() {
        closePool();
    }

    private static void closePool() {
        JedisPool pool = webRedisPool;
        if (pool != null && !pool.isClosed()) {
            try {
                pool.close();
            } catch (Exception e) {
                LOGGER.warn("Error closing web Redis pool: {}", e.getMessage());
            }
        }
        webRedisPool = null;
    }

    /**
     * 供 LoginHandler 调用：写入新 Token（抢占式覆盖旧会话）。
     */
    public static void setActiveToken(String token) {
        if (isRedisAvailable()) {
            try (Jedis jedis = webRedisPool.getResource()) {
                jedis.setex(REDIS_KEY_TOKEN, TOKEN_TTL_SECONDS, token);
                return;
            } catch (Exception e) {
                LOGGER.warn("Redis setex failed for token, falling back to memory.", e);
            }
        }
        memoryActiveToken = token;
        memoryTokenExpireAt = System.currentTimeMillis() + TOKEN_TTL_SECONDS * 1000L;
    }

    /**
     * 供 LogoutHandler 调用：清除当前 Token。
     */
    public static void clearActiveToken() {
        if (isRedisAvailable()) {
            try (Jedis jedis = webRedisPool.getResource()) {
                jedis.del(REDIS_KEY_TOKEN);
                return;
            } catch (Exception e) {
                LOGGER.warn("Redis del failed for token, falling back to memory.", e);
            }
        }
        memoryActiveToken = null;
        memoryTokenExpireAt = 0;
    }

    /**
     * 判断 Web Redis 连接池是否可用。
     */
    public static boolean isRedisAvailable() {
        JedisPool pool = webRedisPool;
        return pool != null && !pool.isClosed();
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        // 1. 注入基础安全 Header
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self' 'unsafe-inline'");

        String path = exchange.getRequestURI().getPath();
        String clientIp = getClientIp(exchange);

        // 2. 登录接口限流：单 IP 每分钟最多 5 次
        if ("/api/login".equals(path) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            if (!checkRateLimit("login:" + clientIp, 5, 60)) {
                sendError(exchange, 429, "请求过于频繁，请 1 分钟后再试");
                return;
            }
        }

        // 3. 业务接口限流：单 IP 每秒最多 20 次
        if (path.startsWith("/api/") && !"/api/login".equals(path)) {
            if (!checkRateLimit("api:" + clientIp, 20, 1)) {
                sendError(exchange, 429, "请求过于频繁，请稍后再试");
                return;
            }
        }

        // 4. 静态资源与登录接口免鉴权
        if (isPublicPath(path)) {
            chain.doFilter(exchange);
            return;
        }

        // 5. API 动态 Token 鉴权
        String token = exchange.getRequestHeaders().getFirst("X-Admin-Token");
        if (token != null && !token.isEmpty() && validateAndRefreshSession(token)) {
            chain.doFilter(exchange);
        } else {
            sendError(exchange, 401, "未授权或登录已过期");
        }
    }

    private boolean isPublicPath(String path) {
        if ("/".equals(path) || "/index.html".equals(path)) return true;
        if (path.startsWith("/lang/")) return true;
        if ("/api/login".equals(path)) return true;
        // admin 面板路径（可配置）
        String adminPath = Config.getString("web.adminPath", "/admin.html");
        if (!adminPath.startsWith("/")) adminPath = "/" + adminPath;
        return path.equals(adminPath);
    }

    private boolean checkRateLimit(String key, int maxLimit, int expireSeconds) {
        if (isRedisAvailable()) {
            try (Jedis jedis = webRedisPool.getResource()) {
                String redisKey = "ratelimit:" + key;
                long count = jedis.incr(redisKey);
                if (count == 1) {
                    jedis.expire(redisKey, expireSeconds);
                }
                return count <= maxLimit;
            } catch (Exception e) {
                LOGGER.warn("Redis rate limit failed, falling back to memory.", e);
            }
        }
        return checkMemoryRateLimit(key, maxLimit, expireSeconds);
    }

    private boolean checkMemoryRateLimit(String key, int maxLimit, int expireSeconds) {
        long now = System.currentTimeMillis();
        RateBucket bucket = memoryRateBuckets.computeIfAbsent(key, k -> new RateBucket());
        synchronized (bucket) {
            if (now - bucket.windowStart > expireSeconds * 1000L) {
                bucket.count.set(0);
                bucket.windowStart = now;
            }
            return bucket.count.incrementAndGet() <= maxLimit;
        }
    }

    private boolean validateAndRefreshSession(String token) {
        if (isRedisAvailable()) {
            try (Jedis jedis = webRedisPool.getResource()) {
                String activeToken = jedis.get(REDIS_KEY_TOKEN);
                if (token.equals(activeToken)) {
                    jedis.expire(REDIS_KEY_TOKEN, TOKEN_TTL_SECONDS); // 滑动续期
                    return true;
                }
                return false;
            } catch (Exception e) {
                LOGGER.warn("Redis token validation failed, falling back to memory.", e);
            }
        }
        // 内存降级
        if (memoryActiveToken != null && memoryActiveToken.equals(token)) {
            if (System.currentTimeMillis() < memoryTokenExpireAt) {
                memoryTokenExpireAt = System.currentTimeMillis() + TOKEN_TTL_SECONDS * 1000L;
                return true;
            }
            memoryActiveToken = null; // 过期清除
        }
        return false;
    }

    private String getClientIp(HttpExchange exchange) {
        String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        byte[] resp = String.format("{\"success\":false,\"message\":\"%s\"}", msg).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }

    @Override
    public String description() {
        return "Security and Auth Filter";
    }

    private static class RateBucket {
        final AtomicInteger count = new AtomicInteger(0);
        volatile long windowStart = System.currentTimeMillis();
    }
}
