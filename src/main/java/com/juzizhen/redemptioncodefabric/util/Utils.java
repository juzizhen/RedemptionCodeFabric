package com.juzizhen.redemptioncodefabric.util;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.EntitySelectorReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.UserCache;

import java.io.IOException;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public class Utils {

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    /**
     * 生成指定长度的随机字符串。
     */
    public static String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARACTERS.charAt(RANDOM.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }

    /**
     * 检查端口是否可用（true 表示可用，false 表示已被占用）。
     */
    public static boolean isPortAvailable(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 从起始端口开始查找第一个可用端口，未找到返回 -1。
     */
    public static int findAvailablePort(int startPort, int maxPort) {
        for (int port = startPort; port <= maxPort; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        return -1;
    }

    /**
     * 判断玩家是否在线。
     */
    public static boolean isPlayerOnline(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) return false;
        return server.getPlayerManager().getPlayer(uuid) != null;
    }

    /**
     * 解析玩家引用（UUID / 玩家名 / @选择器），用于替代 {@code GameProfileArgumentType}（它不支持直接输入 UUID）。
     *
     * @param input 包含玩家引用和奖励的组合字符串（如 "Steve diamond 64"）
     * @return 两个元素的数组：[0] = 逗号分隔的 UUID 字符串，[1] = 剩余的奖励字符串
     */
    public static String[] resolvePlayerRef(String input, MinecraftServer server) throws CommandSyntaxException {
        input = input.trim();

        // 分支 1：实体选择器（以 @ 开头）
        if (input.startsWith("@")) {
            StringReader reader = new StringReader(input);
            EntitySelectorReader selectorReader = new EntitySelectorReader(reader);
            var selector = selectorReader.read();
            List<ServerPlayerEntity> players = selector.getPlayers(
                    server.getCommandSource().withSilent());
            String uuids = players.stream()
                    .map(p -> p.getUuid().toString())
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            reader.skipWhitespace();
            String remaining = reader.getRemaining();
            return new String[]{uuids, remaining};
        }

        // 分支 2：UUID
        String firstWord;
        String rest;
        int firstSpace = input.indexOf(' ');
        if (firstSpace == -1) {
            firstWord = input;
            rest = "";
        } else {
            firstWord = input.substring(0, firstSpace);
            rest = input.substring(firstSpace).trim();
        }

        if (UUID_PATTERN.matcher(firstWord).matches()) {
            // 确保是合法 UUID（通过 UUID.fromString 做二次校验）
            String uuid = UUID.fromString(firstWord).toString();
            return new String[]{uuid, rest};
        }

        // 分支 3：玩家名（通过 UserCache 查找 UUID）
        UserCache userCache = server.getUserCache();
        if (userCache != null) {
            Optional<com.mojang.authlib.GameProfile> profile = userCache.findByName(firstWord);
            if (profile.isPresent()) {
                String uuid = profile.get().getId().toString();
                return new String[]{uuid, rest};
            }
        }

        throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().create();
    }
}
