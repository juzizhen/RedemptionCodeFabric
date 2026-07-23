package com.juzizhen.redemptioncodefabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class RedemptionCodeFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 向服务器发送空数据，用于验证是否安装了该 mod
        ClientPlayNetworking.registerGlobalReceiver(RedemptionCodeFabric.MOD_PRESENCE_CHANNEL, (client, handler, buf, responseSender) -> RedemptionCodeFabric.LOGGER.info("Connect!"));
    }
}
