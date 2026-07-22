package com.juzizhen.redemptioncodefabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class RedemptionCodeFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Send empty data to the server to verify if the mod is installed.
        ClientPlayNetworking.registerGlobalReceiver(RedemptionCodeFabric.MOD_PRESENCE_CHANNEL, (client, handler, buf, responseSender) -> RedemptionCodeFabric.LOGGER.info("Connect!"));
    }
}
