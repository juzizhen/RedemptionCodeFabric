package com.juzizhen.redemptioncodefabric.rcode.manager;

import com.juzizhen.redemptioncodefabric.RedemptionCodeFabric;
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

public class CodeManager {

    private final IDataRepository repository;
    private final Map<String, CodeData> codes;

    public CodeManager() {
        this.repository = RepositoryFactory.create();
        this.codes = repository.loadAllCodes();
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
        if (codes.containsKey(code)) {
            CodeData deletedCode = codes.remove(code);
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

    public Text redeemCode(ServerCommandSource source, String code) {
        CodeData codeData = codes.get(code);
        if (codeData == null) {
            return MessageUtils.createText(source, "redemptioncodefabric.message.code_invalid_or_nonexistent");
        }

        String playerUUID = source.getPlayer() != null ? source.getPlayer().getUuidAsString() : "";
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

        recordUsage(codeData, playerUUID, currentTime);
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
                    List<String> allowedPlayers = Arrays.asList(codeData.getPlayer().split(","));
                    if (!allowedPlayers.contains(playerUUID)) {
                        return "redemptioncodefabric.message.code_invalid_or_nonexistent";
                    }
                }
                if (!playerUsageTimestamps.isEmpty()) return "redemptioncodefabric.message.code_already_used";
                break;
            case TIMED:
                if (currentTime < codeData.getStartTime() || (codeData.getEndTime() != 0 && currentTime > codeData.getEndTime())) return "redemptioncodefabric.message.code_out_of_time";
                if (!playerUsageTimestamps.isEmpty()) return "redemptioncodefabric.message.code_already_used";
                break;
            case CYCLE:
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
                List<String> allowedPlayers = Arrays.asList(codeData.getPlayer().split(","));
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

    private Text grantReward(ServerCommandSource source, CodeData codeData) {
        String rewardString = codeData.getReward();
        String lowerReward = rewardString.toLowerCase();
        if (source == null) {
            return null;
        }
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return null;
        }

        if (lowerReward.startsWith("item@")) {
            try {
                String itemPart = rewardString.substring(5);
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
        } else if (lowerReward.startsWith("exp@")) {
            String expPart = rewardString.substring(4);
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
        } else if (lowerReward.startsWith("permissions@")) {
            return MessageUtils.createText(source, "redemptioncodefabric.message.permission_reward_contact_admin");
        }
        return null;
    }

    private void recordUsage(CodeData codeData, String playerUUID, long currentTime) {
        codeData.addUsedBy(playerUUID, currentTime);
        repository.saveCode(codeData);
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