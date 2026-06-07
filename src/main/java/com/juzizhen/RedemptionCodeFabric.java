package com.juzizhen;

import com.juzizhen.config.ModConfig;
import com.juzizhen.rcode.command.RCodeCommand;
import com.juzizhen.rcode.manager.CodeManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedemptionCodeFabric implements ModInitializer {
	public static final String MOD_ID = "redemptioncodefabric";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static CodeManager codeManager;

	@Override
	public void onInitialize() {
		LOGGER.info("Loading mod -> " + MOD_ID + ":{}", getModVersion());
		ModConfig.onInitialize();
		RCodeCommand.register();

		ServerLifecycleEvents.SERVER_STARTING.register(server -> codeManager = new CodeManager(server));
	}

	public static String getModVersion() {
		return FabricLoader.getInstance()
				.getModContainer(MOD_ID)
				.map(ModContainer::getMetadata)
				.map(m -> m.getVersion().getFriendlyString())
				.orElse("Error Version");
	}
}
