package com.juzizhen;

import com.juzizhen.config.Config;
import com.juzizhen.rcode.command.RCodeCommand;
import com.juzizhen.rcode.manager.CodeManager;
import com.juzizhen.rcode.sql.SqlManager;
import com.juzizhen.rcode.web.WebServer;
import com.juzizhen.util.Utils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class RedemptionCodeFabric implements ModInitializer {
	public static final String MOD_ID = "redemptioncodefabric";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static CodeManager codeManager;
	public static Config config;

	public static final Identifier MOD_PRESENCE_CHANNEL = new Identifier(MOD_ID, "presence");
	private static final Set<UUID> playersWithMod = new HashSet<>();
	private static int WebPort;

	@Override
	public void onInitialize() {
		LOGGER.info("Loading mod -> " + MOD_ID + ":{}", getModVersion());

		config = new Config();
		WebPort = Config.getInt("web.port", 8080);
		RCodeCommand.register();

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			codeManager = new CodeManager(config);
			if (Config.getString("datastore.type", "file").equals("sql")) {
				SqlManager.getInstance().init(config); // Initialize SqlManager
			}

			if (Config.getBoolean("web.enabled", false)) {
				if (Utils.isPortAvailable(WebPort)) {
					WebServer.getInstance().start(WebPort);
				} else {
					int WebPort = Utils.findAvailablePort(4000, 25564);
					if (WebPort != -1) {
						WebServer.getInstance().start(WebPort);
						LOGGER.info("The original port was in use and has been replaced with port {}", WebPort);
					} else {
						LOGGER.error("Could not find available port");
					}
				}
			}
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			WebServer.getInstance().stop();
			SqlManager.getInstance().shutdown(); // Shutdown SqlManager
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (ServerPlayNetworking.canSend(handler, MOD_PRESENCE_CHANNEL)) {
				playersWithMod.add(handler.player.getUuid());
				LOGGER.info("Player {} joined with RedemptionCodeFabric mod.", handler.player.getName().getString());
			} else {
				LOGGER.info("Player {} joined without RedemptionCodeFabric mod.", handler.player.getName().getString());
			}

			if (Config.getBoolean("web.enabled", false) && Config.getBoolean("web.sendUrlToOP", true)) {
				ServerPlayerEntity joinedPlayer = handler.player;
				boolean isOp = server.getPlayerManager().isOperator(joinedPlayer.getGameProfile());
				if (isOp) {
					String url = Config.getString("web.url", "http://localhost") + ":" + Config.getInt("web.port", 8080);
					Text prefix = Text.literal("[System] Backend Webpage: ")
							.styled(style -> style.withColor(Formatting.GOLD));

					Text link = Text.literal(url)
							.styled(style -> style
									.withColor(Formatting.AQUA)
									.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
									.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("点击打开后台网页")))
							);

					Text message = Text.empty().append(prefix).append(link);
					joinedPlayer.sendMessage(message, false);
				}
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> playersWithMod.remove(handler.player.getUuid()));
	}

	public static void reloadConfig() {
		config = new Config();
		if (codeManager != null) {
			// 注意：如果 CodeManager 内部持有数据库连接、文件句柄等资源，
			// 在重新创建之前，可能需要调用一个方法来优雅地关闭旧实例的资源，
			// 以避免资源泄露。目前的代码中没有这样的方法，这里只是简单地替换实例。
			codeManager = new CodeManager(config);
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
}