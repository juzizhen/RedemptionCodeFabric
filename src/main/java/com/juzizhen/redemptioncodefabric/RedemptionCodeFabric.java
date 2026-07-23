package com.juzizhen.redemptioncodefabric;

import com.juzizhen.redemptioncodefabric.async.AsyncIoManager;
import com.juzizhen.redemptioncodefabric.config.Config;
import com.juzizhen.redemptioncodefabric.rcode.command.RCodeCommand;
import com.juzizhen.redemptioncodefabric.rcode.manager.CodeManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class RedemptionCodeFabric implements ModInitializer {
    public static final String MOD_ID = "redemptioncodefabric";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final Identifier MOD_PRESENCE_CHANNEL = new Identifier(MOD_ID, "presence");
    private static final Set<UUID> playersWithMod = new HashSet<>();
    public static volatile CodeManager codeManager;
    public static Config config;
    private static MinecraftServer serverInstance;

    public static MinecraftServer getServerInstance() {
        return serverInstance;
    }

    /**
     * 重新加载配置。
     * <p>
     * 通过 AsyncIoManager 统一重新加载所有 I/O 资源（SQL、Redis、Web 服务器）。
     * 如果 web.enabled 从 false 变为 true，Web 服务器将被启动；
     * 如果从 true 变为 false，Web 服务器将被停止。
     */
    public static void reloadConfig() {
        config = new Config();

        // 通过 AsyncIoManager 异步重新加载所有 I/O 资源，避免阻塞 Minecraft 主线程
        if (serverInstance != null) {
            // 先使旧 CodeManager 失效：异步重载期间主线程继续运行，
            // 防止命令/Web 请求访问已被关闭的旧连接池；待资源就绪后在回调中重建。
            codeManager = null;
            AsyncIoManager.reloadAsync(config, serverInstance, () -> {
                codeManager = new CodeManager();
                LOGGER.info("Configuration reloaded and applied immediately to CodeManager.");
            });
        } else if (codeManager != null) {
            codeManager = new CodeManager();
            LOGGER.info("Configuration reloaded and applied immediately to CodeManager.");
        } else {
            LOGGER.info("Configuration reloaded. New configuration will be applied when the server starts.");
        }
    }

    public static boolean hasMod(UUID playerUuid) {
        return playersWithMod.contains(playerUuid);
    }

    public static String getModVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(ModContainer::getMetadata)
                .map(m -> m.getVersion().getFriendlyString())
                .orElse("Error Version");
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Loading mod -> " + MOD_ID + ":{}", getModVersion());

        config = new Config();
        RCodeCommand.register();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            serverInstance = server;

            // 先初始化 I/O 资源（SQL/Redis 连接），再创建 CodeManager 加载数据
            AsyncIoManager.init(config, server);

            codeManager = new CodeManager();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // 通过 AsyncIoManager 统一关闭所有 I/O 资源（含 Web 服务器）
            AsyncIoManager.shutdown();
            serverInstance = null;
        });

        // Mod 检测 + 向 OP 发送 Web 管理面板 URL
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (ServerPlayNetworking.canSend(handler, MOD_PRESENCE_CHANNEL)) {
                playersWithMod.add(handler.player.getUuid());
                LOGGER.info("Player {} joined with RedemptionCodeFabric mod.", handler.player.getName().getString());
            } else {
                LOGGER.info("Player {} joined without RedemptionCodeFabric mod.", handler.player.getName().getString());
            }

            // 向 OP 发送 Web 管理面板 URL（仅在 Web 服务器运行时）
            AsyncIoManager.sendWebUrlToPlayer(handler.player, server);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> playersWithMod.remove(handler.player.getUuid()));
    }
}