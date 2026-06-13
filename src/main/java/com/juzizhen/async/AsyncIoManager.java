package com.juzizhen.async;

import com.juzizhen.config.Config;
import com.juzizhen.rcode.redis.RedisManager;
import com.juzizhen.rcode.sql.SqlManager;
import com.juzizhen.rcode.web.WebServer;
import com.juzizhen.util.MessageUtils;
import com.juzizhen.util.Utils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 全局异步 I/O 调度中心。
 * <p>
 * 统一管理所有阻塞式 I/O 操作（SQL、Redis、文件），确保它们运行在独立的线程池中，
 * 绝不阻塞 Minecraft Server Thread（主线程）。
 * <p>
 * 同时统一管理 Web 服务器的生命周期（启动/停止/重载），确保 reload 时
 * web.enabled 变化能被正确响应。
 * <p>
 * 生命周期：
 * - 服务器启动时调用 {@link #init(Config, MinecraftServer)} 初始化
 * - 服务器关闭时调用 {@link #shutdown()} 清理资源
 * - reload 时自动 shutdown → 重建线程池 → init
 */
public final class AsyncIoManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("RedemptionCodeFabric-AsyncIO");

    /**
     * 专用 I/O 线程池。非 final，shutdown 后可在下次 init 时重建。
     */
    private static volatile ExecutorService ioExecutor;

    private static boolean initialized = false;

    /** 记录当前 Web 服务器实际监听的端口（-1 表示未运行） */
    private static int activeWebPort = -1;

    private AsyncIoManager() {
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
     *
     * @param config 模组配置
     * @param server Minecraft 服务器实例（用于发送警报）
     */
    public static synchronized void init(Config config, MinecraftServer server) {
        if (initialized) {
            LOGGER.warn("AsyncIoManager already initialized, skipping.");
            return;
        }

        // ★ 确保线程池可用（reload / 服务器重启场景）
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

        // ★ 初始化 Web 服务器
        initWebServer();

        initialized = true;
        LOGGER.info("AsyncIoManager initialized successfully.");
    }

    /**
     * 启动 Web 服务器（如果 web.enabled=true）并向 OP 发送 URL。
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
     * 向刚加入的 OP 玩家发送 Web 管理面板 URL。
     * <p>
     * 必须在玩家 JOIN 事件中调用，因为 SERVER_STARTING 阶段没有在线玩家。
     *
     * @param player 刚加入的玩家
     * @param server Minecraft 服务器实例
     */
    public static void sendWebUrlToPlayer(ServerPlayerEntity player, MinecraftServer server) {
        if (server == null) return;
        if (activeWebPort < 0) return; // Web 服务器未运行
        if (!Config.getBoolean("web.sendUrlToOP", true)) return;
        if (!server.getPlayerManager().isOperator(player.getGameProfile())) return;

        String baseUrl = Config.getString("web.url", "http://localhost") + ":" + activeWebPort + "/";

        Text prefix = MessageUtils.createText(player.getCommandSource(),
                        "redemptioncodefabric.message.webpage_prefix")
                .copy().styled(style -> style.withColor(Formatting.GOLD));

        Text link = Text.literal(baseUrl)
                .styled(style -> style
                        .withColor(Formatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, baseUrl))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                MessageUtils.createText(player.getCommandSource(),
                                        "redemptioncodefabric.message.webpage_link_hover")))
                );

        Text message = Text.empty().append(prefix).append(link);
        player.sendMessage(message, false);
    }

    /**
     * 重新加载配置（用于 /rcode reload 命令）。
     * <p>
     * 完整生命周期：shutdown → 重建线程池 → init。
     * Web 服务器会在 shutdown 阶段先停止，再在 init 阶段根据新配置决定是否重启。
     *
     * @param config 新配置
     * @param server Minecraft 服务器实例
     */
    public static synchronized void reload(Config config, MinecraftServer server) {
        LOGGER.info("Reloading AsyncIoManager...");
        shutdown();
        init(config, server);
    }

    /**
     * 关闭所有 I/O 资源和 Web 服务器。
     * <p>
     * 关闭顺序：先停 WebServer（停止接受新请求），再关 I/O 线程池（终止 in-flight 任务）。
     */
    public static synchronized void shutdown() {
        LOGGER.info("Shutting down AsyncIoManager...");

        // ★ 先停 Web 服务器，避免新请求进入即将关闭的线程池
        WebServer.getInstance().stop();
        activeWebPort = -1;

        // 关闭 SQL 连接池
        SqlManager.getInstance().shutdown();

        // 关闭 Redis 连接池
        RedisManager.getInstance().shutdown();

        // 关闭 I/O 线程池（不置 null，下次 getIoExecutor() 时重建）
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
