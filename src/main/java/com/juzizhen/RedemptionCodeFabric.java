package com.juzizhen;

import com.juzizhen.config.ModConfig;
import com.juzizhen.rcode.command.RCodeCommand;
import com.juzizhen.rcode.manager.CodeManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
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

	public static final Identifier MOD_PRESENCE_CHANNEL = new Identifier(MOD_ID, "presence");
	private static final Set<UUID> playersWithMod = new HashSet<>();

	@Override
	public void onInitialize() {
		LOGGER.info("Loading mod -> " + MOD_ID + ":{}", getModVersion());
		ModConfig.onInitialize();
		RCodeCommand.register();

		ServerLifecycleEvents.SERVER_STARTING.register(server -> codeManager = new CodeManager());

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
