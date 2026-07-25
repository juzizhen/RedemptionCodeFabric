package com.juzizhen.redemptioncodefabric.async;

import com.juzizhen.redemptioncodefabric.config.Config;
import com.juzizhen.redemptioncodefabric.rcode.redis.RedisManager;
import com.juzizhen.redemptioncodefabric.rcode.sql.SqlManager;
import com.juzizhen.redemptioncodefabric.rcode.web.WebServer;
import com.juzizhen.redemptioncodefabric.util.Utils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 全局异步 I/O 调度中心。
 * <p>
 * 统一管理所有阻塞式 I/O（SQL、Redis、文件）使其运行在独立线程池，绝不阻塞 Minecraft 主线程；
 * 同时管理 Web 服务器生命周期，确保 reload 时 web.enabled 变化被正确响应。
 */
public final class AsyncIoManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("RedemptionCodeFabric-AsyncIO");
    /**
     * 生命周期锁：串行化异步重载，避免并发 reload 互相踩踏。
     */
    private static final Object LIFECYCLE_LOCK = new Object();
    /**
     * 专用 I/O 线程池。非 final，shutdown 后可在下次 init 时重建。
     */
    private static volatile ExecutorService ioExecutor;
    private static boolean initialized = false;
    /**
     * 记录当前 Web 服务器实际监听的端口（-1 表示未运行）
     */
    private static volatile int activeWebPort = -1;
    /**
     * 是否有 reload 线程正在运行（串行化标志），与 {@link #LIFECYCLE_LOCK} 配合保证同一时刻只有一个 reload。
     */
    private static volatile boolean reloading = false;
    /**
     * 最新待处理的重载请求（latest-wins）。reload 线程处理完当前请求后会领取它；
     * 重载期间到达的多次请求会互相覆盖，最终只执行最新的一次，确保用户最后的配置一定生效。
     */
    private static volatile ReloadRequest pendingRequest;

    private AsyncIoManager() {
    }

    /** 一次重载请求的不可变快照。 */
    private record ReloadRequest(Config config, MinecraftServer server, Runnable onReady) {
    }

    /**
     * 获取 I/O 线程池（懒初始化，确保永不为 null）。
     * <p>
     * 如果线程池已被 shutdown 或尚未创建，自动新建一个。
     */
    public static ExecutorService getIoExecutor() {
        ExecutorService exec = ioExecutor;
        if (exec == null || exec.isShutdown() || exec.isTerminated()) {
            synchronized (AsyncIoManager.class) {
                exec = ioExecutor;
                if (exec == null || exec.isShutdown() || exec.isTerminated()) {
                    exec = createIoExecutor();
                    ioExecutor = exec;
                }
            }
        }
        return exec;
    }

    private static ExecutorService createIoExecutor() {
        LOGGER.info("Creating new I/O thread pool (4 workers).");
        return Executors.newFixedThreadPool(4, r -> {
            Thread thread = new Thread(r, "RCF-IO-Worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 初始化所有 I/O 资源（SQL 连接池、Redis 连接池、Web 服务器）。
     */
    public static synchronized void init(Config config, MinecraftServer server) {
        if (initialized) {
            LOGGER.warn("AsyncIoManager already initialized, skipping.");
            return;
        }

        // 确保线程池可用（reload / 重启场景）
        getIoExecutor();

        String dsType = Config.getString("datastore.type", "file");

        if ("sql".equalsIgnoreCase(dsType)) {
            SqlManager.getInstance().setServer(server);
            boolean ok = SqlManager.getInstance().init(config);
            if (!ok) {
                LOGGER.warn("SQL initialization failed, RepositoryFactory will fall back to file storage.");
            }
        }

        if ("redis".equalsIgnoreCase(dsType)) {
            boolean ok = RedisManager.getInstance().init(config);
            if (!ok) {
                LOGGER.warn("Redis initialization failed, RepositoryFactory will fall back to file storage.");
            }
        }

        initWebServer();

        initialized = true;
        LOGGER.info("AsyncIoManager initialized successfully.");
    }

    /**
     * 启动 Web 服务器（如果 web.enabled=true）。
     */
    private static void initWebServer() {
        if (!Config.getBoolean("web.enabled", false)) {
            LOGGER.info("Web server is disabled in config.");
            activeWebPort = -1;
            return;
        }

        int configuredPort = Config.getInt("web.port", 8080);
        int port = configuredPort;

        if (!Utils.isPortAvailable(port)) {
            int fallbackPort = Utils.findAvailablePort(4000, 25564);
            if (fallbackPort != -1) {
                port = fallbackPort;
                LOGGER.info("Original web port {} occupied, using fallback port {}", configuredPort, fallbackPort);
            } else {
                LOGGER.error("Could not find available port for web server, web server not started.");
                activeWebPort = -1;
                return;
            }
        }

        WebServer.getInstance().start(port);
        activeWebPort = port;

        String baseUrl = Config.getString("web.url", "http://localhost") + ":" + port;
        String adminPath = Config.getString("web.adminPath", "/admin.html");
        LOGGER.info("Web management panel available at: {}", baseUrl);
        LOGGER.info("Admin panel path: {}{}", baseUrl, adminPath);
    }

    /**
     * 重新加载配置（用于 /rcode reload 命令）：shutdown → init。
     * Web 服务器在 shutdown 阶段停止，再在 init 阶段按新配置决定是否重启。
     */
    public static synchronized void reload(Config config, MinecraftServer server) {
        LOGGER.info("Reloading AsyncIoManager...");
        shutdown();
        init(config, server);
    }

    /**
     * 在专用后台守护线程上异步执行重载（shutdown → init → onReady），避免阻塞 Minecraft 主线程。
     * <p>
     * 不能提交到 {@link #getIoExecutor()}：{@link #shutdown()} 会关闭并 awaitTermination 该线程池自身，
     * 在其工作线程上调用会自锁，故使用专用线程。
     * <p>
     * 并发语义（latest-wins）：重载期间的慢速连接重试期间用户可能再次触发重载，新请求覆盖
     * {@link #pendingRequest} 槽位，当前重载结束后由 {@link #reloadLoop()} 以最新配置再执行一次。
     * 这样既串行化（同一时刻只有一个 reload），又保证用户最后的配置一定生效。
     */
    public static void reloadAsync(Config config, MinecraftServer server, Runnable onReady) {
        boolean startThread;
        synchronized (LIFECYCLE_LOCK) {
            // latest-wins：覆盖尚未处理的请求，确保用户最终的配置一定会生效
            pendingRequest = new ReloadRequest(config, server, onReady);
            startThread = !reloading;
            reloading = true;
        }
        if (startThread) {
            Thread thread = new Thread(AsyncIoManager::reloadLoop, "RCF-Reload");
            thread.setDaemon(true);
            thread.start();
        } else {
            LOGGER.info("A reload is already in progress; the latest configuration will be applied once it finishes.");
        }
    }

    /**
     * reload 线程主体：循环领取最新待处理请求并处理，直到没有请求为止。
     * 领取请求与管理 {@link #reloading} 标志在 {@link #LIFECYCLE_LOCK} 内原子完成，
     * 实际的 shutdown/init 在锁外执行。由此保证串行化、重载期间的新请求不丢失
     * （以最新配置再执行一次），并由 finally 兜底避免丢失请求或泄漏活跃标志。
     */
    private static void reloadLoop() {
        try {
            while (true) {
                ReloadRequest request;
                synchronized (LIFECYCLE_LOCK) {
                    request = pendingRequest;
                    if (request == null) {
                        return; // 无待处理请求，退出（finally 统一释放活跃标志）
                    }
                    pendingRequest = null;
                }
                try {
                    LOGGER.info("Reloading AsyncIoManager asynchronously...");
                    shutdown();
                    init(request.config(), request.server());
                    if (request.onReady() != null) {
                        request.onReady().run();
                    }
                } catch (Exception e) {
                    LOGGER.error("Asynchronous reload failed.", e);
                }
            }
        } finally {
            synchronized (LIFECYCLE_LOCK) {
                if (pendingRequest == null) {
                    reloading = false;
                } else {
                    // 异常退出但仍有待处理请求：重启线程继续处理，避免请求丢失
                    Thread thread = new Thread(AsyncIoManager::reloadLoop, "RCF-Reload");
                    thread.setDaemon(true);
                    thread.start();
                }
            }
        }
    }

    /**
     * 关闭所有 I/O 资源和 Web 服务器。
     * <p>
     * 关闭顺序：先停 WebServer（停止接受新请求），再关 I/O 线程池（终止 in-flight 任务）。
     */
    public static synchronized void shutdown() {
        LOGGER.info("Shutting down AsyncIoManager...");

        // 先停 Web 服务器，避免新请求进入即将关闭的线程池
        WebServer.getInstance().stop();
        activeWebPort = -1;

        SqlManager.getInstance().shutdown();

        RedisManager.getInstance().shutdown();

        // 不置 null，下次 getIoExecutor() 时重建
        ExecutorService exec = ioExecutor;
        if (exec != null) {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                }
            } catch (InterruptedException e) {
                exec.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        initialized = false;
        LOGGER.info("AsyncIoManager shut down complete.");
    }

}
