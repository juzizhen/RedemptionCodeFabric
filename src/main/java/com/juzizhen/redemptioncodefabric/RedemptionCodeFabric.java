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

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RedemptionCodeFabric implements ModInitializer {
    public static final String MOD_ID = "redemptioncodefabric";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final Identifier MOD_PRESENCE_CHANNEL = new Identifier(MOD_ID, "presence");
    // 线程安全 Set：JOIN/DISCONNECT 事件与 hasMod() 查询可能并发
    private static final Set<UUID> playersWithMod = ConcurrentHashMap.newKeySet();
    public static volatile CodeManager codeManager;
    public static Config config;
    private static MinecraftServer serverInstance;

    public static MinecraftServer getServerInstance() {
        return serverInstance;
    }

    /**
     * 重新加载配置，并通过 AsyncIoManager 异步重载所有 I/O 资源。
     * web.enabled 由 false→true 时启动 Web 服务器，由 true→false 时停止。
     */
    public static void reloadConfig() {
        config = new Config();

        if (serverInstance != null) {
            // 先使旧 CodeManager 失效：异步重载期间主线程继续运行，
            // 防止命令/Web 请求访问已被关闭的旧连接池；待资源就绪后在回调中重建。
            CodeManager oldManager = codeManager;
            codeManager = null;
            if (oldManager != null) {
                oldManager.shutdown();
            }
            AsyncIoManager.reloadAsync(config, serverInstance, () -> {
                // 合并重载时上一次重载可能已建过 CodeManager，先关闭它，避免其缓存同步线程泄漏
                CodeManager previous = codeManager;
                if (previous != null) {
                    previous.shutdown();
                }
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

            // 重新读取配置：会话内可能在主界面手工修改（如 file↔sql 切换），
            // onInitialize 仅启动时读取一次，此处刷新以采用最新配置。
            config = new Config();

            // 先初始化 I/O 资源，再创建 CodeManager 加载数据
            AsyncIoManager.init(config, server);

            codeManager = new CodeManager();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // 先停止缓存同步，再关闭 I/O 资源
            if (codeManager != null) {
                codeManager.shutdown();
            }
            AsyncIoManager.shutdown();
            serverInstance = null;
        });

        // Mod 检测
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (ServerPlayNetworking.canSend(handler, MOD_PRESENCE_CHANNEL)) {
                playersWithMod.add(handler.player.getUuid());
                LOGGER.info("Player {} joined with RedemptionCodeFabric mod.", handler.player.getName().getString());
            } else {
                LOGGER.info("Player {} joined without RedemptionCodeFabric mod.", handler.player.getName().getString());
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> playersWithMod.remove(handler.player.getUuid()));
    }
}