package com.juzizhen.util;

import com.juzizhen.RedemptionCodeFabric;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Language;

import java.util.UUID;

public class MessageUtils {
    
    /**
     * Sends a feedback message to the command source and broadcasts to ops.
     *
     * @param source The source of the command.
     * @param key    The translation key for the message.
     * @param args   The arguments for the message format.
     */
    public static void sendFeedback(ServerCommandSource source, String key, boolean broadcastToOps, Object... args) {
        source.sendFeedback(() -> createText(source, key, args), broadcastToOps);
    }

    /**
     * Sends an error message to the command source, adapting to whether the client has the mod.
     *
     * @param source The source of the command.
     * @param key    The translation key for the message.
     * @param args   The arguments for the message format.
     */
    public static void sendError(ServerCommandSource source, String key, Object... args) {
        source.sendError(createText(source, key, args));
    }

    /**
     * Creates a Text object, either translatable or literal, based on whether the player has the mod.
     *
     * @param source The source, used to check for a player.
     * @param key    The translation key.
     * @param args   The arguments for the message.
     * @return A Text object ready to be sent.
     */
    public static Text createText(ServerCommandSource source, String key, Object... args) {
        ServerPlayerEntity player = source.getPlayer();
        UUID playerUuid = (player != null) ? player.getUuid() : null;

        if (playerUuid != null && RedemptionCodeFabric.hasMod(playerUuid)) {
            return Text.translatable(key, args);
        } else {
            // For console or players without the mod, pre-translate to the server's default language (usually English).
            return Text.literal(String.format(Language.getInstance().get(key), args));
        }
    }
}
