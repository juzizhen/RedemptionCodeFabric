package com.juzizhen.redemptioncodefabric.rcode.manager;

import com.juzizhen.redemptioncodefabric.RedemptionCodeFabric;
import com.juzizhen.redemptioncodefabric.async.AsyncIoManager;
import com.juzizhen.redemptioncodefabric.rcode.model.CodeData;
import com.juzizhen.redemptioncodefabric.rcode.model.CodeType;
import com.juzizhen.redemptioncodefabric.rcode.model.OperationLogEntry;
import com.juzizhen.redemptioncodefabric.rcode.repository.IDataRepository;
import com.juzizhen.redemptioncodefabric.rcode.repository.RepositoryFactory;
import com.juzizhen.redemptioncodefabric.util.MessageUtils;
import com.juzizhen.redemptioncodefabric.util.Utils;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class CodeManager {

    private final IDataRepository repository;
    private final Map<String, CodeData> codes;
    private final ScheduledExecutorService syncScheduler;

    public CodeManager() {
        this.repository = RepositoryFactory.create();
        // ConcurrentHashMap：HTTP 线程遍历 getAllCodes() 与主线程 addCode/deleteCode 并发安全
        this.codes = new ConcurrentHashMap<>(repository.loadAllCodes());

        // 定期从数据源同步兑换码到内存缓存（集群环境下拾取其他服务器的变更）
        long syncIntervalMs = com.juzizhen.redemptioncodefabric.config.Config.getInt("cache.syncInterval", 10000);
        this.syncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RCF-Cache-Sync");
            t.setDaemon(true);
            return t;
        });
        this.syncScheduler.scheduleWithFixedDelay(this::syncCache, syncIntervalMs, syncIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止缓存同步定时器。由 reloadConfig / 关服流程调用，防止旧实例泄漏。
     */
    public void shutdown() {
        syncScheduler.shutdown();
        try {
            if (!syncScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                syncScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            syncScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 从数据源重新加载兑换码，增量同步到内存 Map。
     * <p>
     * 先移除数据源中已不存在的 key（其他服务器删除），再覆盖/新增全部条目。
     * ConcurrentHashMap 的 keySet().retainAll() + putAll() 组合保证并发安全。
     */
    private void syncCache() {
        try {
            Map<String, CodeData> fresh = repository.loadAllCodes();
            // C4 防护：数据源返回空但本地有数据时，视为瞬时 I/O 错误，跳过本次同步
            if ((fresh == null || fresh.isEmpty()) && !codes.isEmpty()) {
                RedemptionCodeFabric.LOGGER.warn("Cache sync returned empty while {} codes in memory, skipping to protect cache", codes.size());
                return;
            }
            if (fresh != null) {
                codes.keySet().retainAll(fresh.keySet());
                codes.putAll(fresh);
            }
        } catch (Exception e) {
            RedemptionCodeFabric.LOGGER.warn("Cache sync failed, will retry next cycle", e);
        }
    }

    public CodeData getCode(String code) {
        return codes.get(code);
    }

    public Map<String, CodeData> getAllCodes() {
        return Collections.unmodifiableMap(codes);
    }

    public List<OperationLogEntry> getOperationLog(int offset, int limit) {
        return repository.getOperationLog(offset, limit);
    }

    public void addCode(CodeData codeData, String executorName) {
        addCode(codeData, executorName, null);
    }

    public void addCode(CodeData codeData, String executorName, String executorUuid) {
        codes.put(codeData.getCode(), codeData);
        repository.saveCode(codeData);

        Map<String, String> details = new LinkedHashMap<>();
        details.put("code", codeData.getCode());
        details.put("type", codeData.getType().name());
        details.put("reward", codeData.getReward());
        if (executorUuid != null && !executorUuid.isEmpty()) details.put("executor_uuid", executorUuid);
        if (codeData.getPlayer() != null) details.put("owner", codeData.getPlayer());
        if (codeData.getCount() != -1) details.put("count", String.valueOf(codeData.getCount()));
        if (codeData.getStartTime() != 0) details.put("startTime", String.valueOf(codeData.getStartTime()));
        if (codeData.getEndTime() != 0) details.put("endTime", String.valueOf(codeData.getEndTime()));
        if (codeData.getInterval() != 0) details.put("interval", String.valueOf(codeData.getInterval()));

        repository.appendOperationLog(new OperationLogEntry(System.currentTimeMillis(), "GENERATE", executorName, details));
    }

    public void deleteCode(String code, String executorName) {
        deleteCode(code, executorName, null);
    }

    public boolean deleteCode(String code, String executorName, String executorUuid) {
        // 原子 remove：避免 containsKey + remove 之间同步线程 retainAll 删除 key 导致 NPE
        CodeData deletedCode = codes.remove(code);
        if (deletedCode != null) {
            repository.removeCode(code);
            Map<String, String> details = new HashMap<>();
            details.put("code", code);
            details.put("type", deletedCode.getType().name());
            if (executorUuid != null && !executorUuid.isEmpty()) details.put("executor_uuid", executorUuid);
            repository.appendOperationLog(new OperationLogEntry(System.currentTimeMillis(), "DELETE", executorName, details));
            return true;
        }
        return false;
    }

    public Text getCodeInfo(ServerCommandSource source, String code) {
        CodeData codeData = codes.get(code);
        if (codeData == null) {
            return MessageUtils.createText(source, "redemptioncodefabric.message.code_not_found");
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        MutableText info = Text.literal("");
        info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_header", codeData.getCode()).copy().formatted(Formatting.YELLOW));
        info.append("\n");
        info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_type", codeData.getType()).copy().formatted(Formatting.BLUE));
        info.append("\n");
        info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_reward", codeData.getReward()).copy().formatted(Formatting.BLUE));
        info.append("\n");

        switch (codeData.getType()) {
            case PERSONAL:
            case GLOBAL_LIMIT:
                if (codeData.getPlayer() != null && !codeData.getPlayer().isEmpty()) {
                    List<String> allowedPlayers = Arrays.asList(codeData.getPlayer().split(","));
                    info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_player_group", allowedPlayers.size()).copy().formatted(Formatting.BLUE));
                    info.append("\n");
                }
                if (codeData.getType() == CodeType.GLOBAL_LIMIT) {
                    info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_uses_left", codeData.getCount() - codeData.getUsedBy().values().stream().mapToInt(List::size).sum()).copy().formatted(Formatting.BLUE));
                    info.append("\n");
                }
                break;
            case TIMED:
                info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_start_time", sdf.format(new Date(codeData.getStartTime()))).copy().formatted(Formatting.BLUE));
                info.append("\n");
                info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_end_time", sdf.format(new Date(codeData.getEndTime()))).copy().formatted(Formatting.BLUE));
                info.append("\n");
                break;
            case CYCLE:
                info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_start_time", sdf.format(new Date(codeData.getStartTime()))).copy().formatted(Formatting.BLUE));
                info.append("\n");
                info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_interval", codeData.getInterval() / 1000).copy().formatted(Formatting.BLUE));
                info.append("\n");
                break;
        }

        if (codeData.getType() == CodeType.PERMANENT || codeData.getType() == CodeType.CYCLE) {
            info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_usage_counts_header").copy().formatted(Formatting.BLUE));
            info.append("\n");

            Map<String, List<Long>> usedByMap = codeData.getUsedBy();
            if (usedByMap.isEmpty()) {
                info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_not_used_yet").copy().formatted(Formatting.GRAY));
                info.append("\n");
            } else {
                usedByMap.entrySet().stream()
                        .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
                        .forEach(entry -> {
                            String uuid = entry.getKey();
                            long usageCount = entry.getValue().size();
                            String displayName = resolvePlayerDisplayName(source, uuid);

                            MutableText userText = Text.literal(displayName).formatted(Formatting.AQUA)
                                    .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, uuid))
                                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, MessageUtils.createText(source, "redemptioncodefabric.message.info_copy_uuid"))));

                            info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_usage_entry", userText, usageCount).copy().formatted(Formatting.BLUE));
                            info.append("\n");
                        });
            }
        } else {
            info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_used_by_header", codeData.getUsedBy().size()).copy().formatted(Formatting.BLUE));
            info.append("\n");
            for (String uuid : codeData.getUsedBy().keySet()) {
                String displayName = resolvePlayerDisplayName(source, uuid);
                MutableText playerText = Text.literal(displayName)
                        .setStyle(Text.empty().getStyle()
                                .withColor(Formatting.AQUA)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, uuid))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, MessageUtils.createText(source, "redemptioncodefabric.message.info_copy_uuid"))));
                info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_used_by_entry", "").copy().append(playerText));
                info.append("\n");
            }
        }

        info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_footer").copy().formatted(Formatting.YELLOW));
        return info;
    }

    /**
     * C2 折中方案：缓存命中同步处理，未命中异步查 DB 后回主线程发奖。
     * <p>
     * 快路径（99% 场景）：码在本地缓存中，直接同步校验+发奖，零阻塞。
     * 慢路径（集群/冷启动）：码不在缓存，提交 IO 线程查 DB，查到后回 MC 主线程处理，
     * 玩家先收到"处理中"提示，结果异步推送。
     */
    public Text redeemCode(ServerCommandSource source, String code) {
        // C8: 仅允许玩家兑换
        if (source.getPlayer() == null) {
            return MessageUtils.createText(source, "redemptioncodefabric.message.code_invalid_or_nonexistent");
        }

        // ── 快路径：缓存命中，同步处理 ──
        CodeData cached = codes.get(code);
        if (cached != null) {
            return processRedeem(source, code, cached);
        }

        // ── 慢路径：缓存未命中，异步查 DB（集群中其他服务器创建的码） ──
        ServerPlayerEntity player = source.getPlayer();
        CompletableFuture.runAsync(() -> {
            CodeData dbCode = repository.loadCode(code);
            if (dbCode == null) {
                // 玩家可能已下线，安全发送
                if (!player.isDisconnected()) {
                    player.getServer().execute(() ->
                            player.sendMessage(MessageUtils.createText(source,
                                    "redemptioncodefabric.message.code_invalid_or_nonexistent"), false));
                }
                return;
            }
            // 放入缓存，后续请求走快路径
            codes.put(code, dbCode);
            // 回 MC 主线程执行校验+发奖
            player.getServer().execute(() -> {
                if (player.isDisconnected()) return;
                Text result = processRedeem(source, code, dbCode);
                player.sendMessage(result, false);
            });
        }, AsyncIoManager.getIoExecutor()).exceptionally(e -> {
            RedemptionCodeFabric.LOGGER.error("Async redeemCode DB lookup failed for '{}'", code, e);
            if (!player.isDisconnected()) {
                player.getServer().execute(() ->
                        player.sendMessage(MessageUtils.createText(source,
                                "redemptioncodefabric.message.code_invalid_or_nonexistent"), false));
            }
            return null;
        });

        // 立即返回"处理中"提示，不阻塞主线程
        return MessageUtils.createText(source, "redemptioncodefabric.message.redeem_processing");
    }

    /**
     * 兑换核心逻辑：校验 → 发奖 → 记录使用。必须在 MC 主线程调用。
     */
    private Text processRedeem(ServerCommandSource source, String code, CodeData codeData) {
        String playerUUID = source.getPlayer().getUuidAsString();
        long currentTime = System.currentTimeMillis();

        String validationErrorKey = validateCode(codeData, playerUUID, currentTime);
        if (validationErrorKey != null) {
            if (validationErrorKey.equals("redemptioncodefabric.message.cycle_wait")) {
                long timeSinceStart = currentTime - codeData.getStartTime();
                long currentCycle = timeSinceStart / codeData.getInterval();
                long nextCycleStartTime = codeData.getStartTime() + (currentCycle + 1) * codeData.getInterval();
                long remainingTime = (nextCycleStartTime - currentTime) / 1000;
                return MessageUtils.createText(source, validationErrorKey, remainingTime);
            }
            return MessageUtils.createText(source, validationErrorKey);
        }

        Text rewardResult = grantReward(source, codeData);
        if (rewardResult != null) {
            return rewardResult;
        }

        // 记录使用：缓存立即更新，DB 落盘异步
        recordUsage(codeData, playerUUID, currentTime);
        codes.put(code, codeData);

        Map<String, String> details = new HashMap<>();
        details.put("code", code);
        details.put("player_uuid", playerUUID);
        repository.appendOperationLog(new OperationLogEntry(currentTime, "REDEEM", source.getName(), details));

        return MessageUtils.createText(source, "redemptioncodefabric.message.redeem_success");
    }

    private String validateCode(CodeData codeData, String playerUUID, long currentTime) {
        List<Long> playerUsageTimestamps = codeData.getUsedBy().getOrDefault(playerUUID, Collections.emptyList());

        switch (codeData.getType()) {
            case ONCE:
                if (!codeData.getUsedBy().isEmpty()) return "redemptioncodefabric.message.code_already_used";
                break;
            case GLOBAL_UNLIMITED:
            case GLOBAL_LIMIT:
                if (!playerUsageTimestamps.isEmpty()) return "redemptioncodefabric.message.code_already_used";
                break;
            case PERSONAL:
                if (codeData.getPlayer() != null && !codeData.getPlayer().isEmpty()) {
                    List<String> allowedPlayers = splitPlayerList(codeData.getPlayer());
                    if (!allowedPlayers.contains(playerUUID)) {
                        return "redemptioncodefabric.message.code_invalid_or_nonexistent";
                    }
                }
                if (!playerUsageTimestamps.isEmpty()) return "redemptioncodefabric.message.code_already_used";
                break;
            case TIMED:
                if (currentTime < codeData.getStartTime() || (codeData.getEndTime() != 0 && currentTime > codeData.getEndTime()))
                    return "redemptioncodefabric.message.code_out_of_time";
                if (!playerUsageTimestamps.isEmpty()) return "redemptioncodefabric.message.code_already_used";
                break;
            case CYCLE:
                if (codeData.getInterval() <= 0) return "redemptioncodefabric.message.code_invalid_or_nonexistent";
                if (currentTime < codeData.getStartTime()) return "redemptioncodefabric.message.code_not_yet_active";

                long timeSinceStart = currentTime - codeData.getStartTime();
                long currentCycleIndex = timeSinceStart / codeData.getInterval();
                long currentCycleStartTime = codeData.getStartTime() + currentCycleIndex * codeData.getInterval();

                if (!playerUsageTimestamps.isEmpty()) {
                    long lastUsage = playerUsageTimestamps.get(playerUsageTimestamps.size() - 1);
                    if (lastUsage >= currentCycleStartTime) {
                        return "redemptioncodefabric.message.cycle_wait";
                    }
                }
                break;
            case PERMANENT:
                break;
        }

        if (codeData.getType() == CodeType.GLOBAL_LIMIT) {
            if (codeData.getPlayer() != null && !codeData.getPlayer().isEmpty()) {
                List<String> allowedPlayers = splitPlayerList(codeData.getPlayer());
                if (!allowedPlayers.contains(playerUUID)) {
                    return "redemptioncodefabric.message.code_invalid_or_nonexistent";
                }
            }
            if (codeData.getCount() <= codeData.getUsedBy().values().stream().mapToInt(List::size).sum() && codeData.getCount() != -1) {
                return "redemptioncodefabric.message.code_invalid_or_nonexistent";
            }
        }

        return null;
    }

    // L9: 奖励类型前缀常量
    private static final String REWARD_PREFIX_ITEM = "item@";
    private static final String REWARD_PREFIX_EXP = "exp@";
    private static final String REWARD_PREFIX_PERMISSIONS = "permissions@";

    private Text grantReward(ServerCommandSource source, CodeData codeData) {
        String rewardString = codeData.getReward();
        String lowerReward = rewardString.toLowerCase();
        if (source == null) {
            return MessageUtils.createText(source, "redemptioncodefabric.message.code_invalid_or_nonexistent");
        }
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return MessageUtils.createText(source, "redemptioncodefabric.message.code_invalid_or_nonexistent");
        }

        if (lowerReward.startsWith(REWARD_PREFIX_ITEM)) {
            try {
                String itemPart = rewardString.substring(REWARD_PREFIX_ITEM.length());
                String itemId;
                String nbtStr = null;
                int nbtStartIndex = itemPart.indexOf('{');
                if (nbtStartIndex != -1) {
                    itemId = itemPart.substring(0, nbtStartIndex);
                    nbtStr = itemPart.substring(nbtStartIndex);
                } else {
                    itemId = itemPart;
                }

                ItemStack stack = new ItemStack(Registries.ITEM.get(new Identifier(itemId)));
                if (nbtStr != null) {
                    stack.setNbt(StringNbtReader.parse(nbtStr));
                }
                player.giveItemStack(stack);
            } catch (CommandSyntaxException e) {
                RedemptionCodeFabric.LOGGER.error("Failed to redeem item code: {}", rewardString, e);
                return MessageUtils.createText(source, "redemptioncodefabric.message.redeem_fail_item");
            }
        } else if (lowerReward.startsWith(REWARD_PREFIX_EXP)) {
            String expPart = rewardString.substring(REWARD_PREFIX_EXP.length());
            try {
                int finalAmount;
                if (expPart.toUpperCase().endsWith("L")) {
                    finalAmount = Integer.parseInt(expPart.substring(0, expPart.length() - 1));
                    player.addExperienceLevels(finalAmount);
                } else if (expPart.toUpperCase().endsWith("P")) {
                    finalAmount = Integer.parseInt(expPart.substring(0, expPart.length() - 1));
                    player.addExperience(finalAmount);
                } else {
                    finalAmount = Integer.parseInt(expPart);
                    player.addExperience(finalAmount);
                }
            } catch (NumberFormatException e) {
                return MessageUtils.createText(source, "redemptioncodefabric.message.redeem_fail_exp");
            }
        } else if (lowerReward.startsWith(REWARD_PREFIX_PERMISSIONS)) {
            return MessageUtils.createText(source, "redemptioncodefabric.message.permission_reward_contact_admin");
        } else {
            // H11: 未知奖励格式 — 记录警告并返回错误，不消耗兑换码
            RedemptionCodeFabric.LOGGER.error("Unrecognized reward format for code '{}': {}", codeData.getCode(), rewardString);
            return MessageUtils.createText(source, "redemptioncodefabric.message.redeem_fail_item");
        }
        return null;
    }

    /**
     * C2: 记录使用——内存立即更新，DB 落盘异步（不阻塞 MC 主线程）。
     * 缓存已是权威读源，DB 写入为持久化保障，允许短暂延迟。
     */
    private void recordUsage(CodeData codeData, String playerUUID, long currentTime) {
        codeData.addUsedBy(playerUUID, currentTime);
        CompletableFuture.runAsync(() -> repository.saveCode(codeData), AsyncIoManager.getIoExecutor())
                .exceptionally(e -> {
                    RedemptionCodeFabric.LOGGER.error("Async saveCode failed for '{}', data safe in cache",
                            codeData.getCode(), e);
                    return null;
                });
    }

    /**
     * 按逗号分割玩家 UUID 列表，trim 每个元素并过滤空串。
     */
    private static List<String> splitPlayerList(String playerStr) {
        return Arrays.stream(playerStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 根据 UUID 解析玩家显示名称：在线则返回玩家名，离线则返回 UUID。
     */
    private String resolvePlayerDisplayName(ServerCommandSource source, String uuid) {
        try {
            UUID playerUuid = UUID.fromString(uuid);
            if (Utils.isPlayerOnline(source.getServer(), playerUuid)) {
                ServerPlayerEntity onlinePlayer = source.getServer().getPlayerManager().getPlayer(playerUuid);
                if (onlinePlayer != null) {
                    return onlinePlayer.getName().getString();
                }
            }
        } catch (IllegalArgumentException ignored) {

        }
        return uuid;
    }
}