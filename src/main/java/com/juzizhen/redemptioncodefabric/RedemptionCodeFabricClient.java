package com.juzizhen.redemptioncodefabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class RedemptionCodeFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 注册 presence 接收器，使服务器能检测到客户端安装了本 mod
        ClientPlayNetworking.registerGlobalReceiver(RedemptionCodeFabric.MOD_PRESENCE_CHANNEL, (client, handler, buf, responseSender) -> RedemptionCodeFabric.LOGGER.info("Connect!"));
    }
}
