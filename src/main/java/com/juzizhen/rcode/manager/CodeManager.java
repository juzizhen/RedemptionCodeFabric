package com.juzizhen.rcode.manager;

import com.juzizhen.RedemptionCodeFabric;
import com.juzizhen.rcode.model.CodeData;
import com.juzizhen.rcode.model.CodeType;
import com.juzizhen.rcode.model.OperationLogEntry;
import com.juzizhen.rcode.repository.FileRepository;
import com.juzizhen.rcode.repository.IDataRepository;
import com.juzizhen.util.MessageUtils;
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
        this.repository = new FileRepository();
        this.codes = repository.loadAllCodes();
    }

    public CodeData getCode(String code) {
        return codes.get(code);
    }

    public void addCode(CodeData codeData, String executorName) {
        codes.put(codeData.getCode(), codeData);
        repository.saveAllCodes(codes);

        Map<String, String> details = new LinkedHashMap<>();
        details.put("code", codeData.getCode());
        details.put("type", codeData.getType().name());
        details.put("reward", codeData.getReward());
        if (codeData.getPlayer() != null) details.put("owner", codeData.getPlayer());
        if (codeData.getCount() != -1) details.put("count", String.valueOf(codeData.getCount()));
        if (codeData.getStartTime() != 0) details.put("startTime", String.valueOf(codeData.getStartTime()));
        if (codeData.getEndTime() != 0) details.put("endTime", String.valueOf(codeData.getEndTime()));
        if (codeData.getInterval() != 0) details.put("interval", String.valueOf(codeData.getInterval()));

        repository.appendOperationLog(new OperationLogEntry(System.currentTimeMillis(), "GENERATE", executorName, details));
    }

    public boolean deleteCode(String code, String executorName) {
        if (codes.containsKey(code)) {
            CodeData deletedCode = codes.remove(code);
            repository.saveAllCodes(codes);
            Map<String, String> details = new HashMap<>();
            details.put("code", code);
            details.put("type", deletedCode.getType().name());
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
            info.append(Text.literal("Usage Counts by Player:").formatted(Formatting.BLUE));
            info.append("\n");

            Map<String, List<Long>> usedByMap = codeData.getUsedBy();
            if (usedByMap.isEmpty()) {
                info.append(Text.literal("  Not used by anyone yet.").formatted(Formatting.GRAY));
                info.append("\n");
            } else {
                usedByMap.entrySet().stream()
                    .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
                    .forEach(entry -> {
                        String uuid = entry.getKey();
                        long count = entry.getValue().size();
                        String name = uuid;

                        try {
                            UUID playerUuid = UUID.fromString(uuid);
                            ServerPlayerEntity onlinePlayer = source.getServer().getPlayerManager().getPlayer(playerUuid);
                            if (onlinePlayer != null) {
                                name = onlinePlayer.getName().getString();
                            } else {
                                if (source.getServer().getUserCache() == null) return;
                                source.getServer().getUserCache().getByUuid(playerUuid).ifPresent(profile -> info.append(Text.literal(profile.getName())));
                            }
                        } catch (IllegalArgumentException e) { /* keep name as uuid */ }

                        MutableText userText = Text.literal(name).formatted(Formatting.AQUA)
                                .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, uuid))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Copy UUID"))));

                        info.append(Text.literal("  - ").append(userText).append(Text.literal(": " + count + " times")).formatted(Formatting.BLUE));
                        info.append("\n");
                    });
            }
        } else {
            info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_used_by_header", codeData.getUsedBy().size()).copy().formatted(Formatting.BLUE));
            info.append("\n");
            for (String uuid : codeData.getUsedBy().keySet()) {
                MutableText uuidText = Text.literal(uuid)
                    .setStyle(Text.empty().getStyle()
                    .withColor(Formatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, uuid))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Copy UUID"))));
                info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_used_by_entry", "").copy().append(uuidText));
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
                // No validation needed
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
        repository.saveAllCodes(codes);
    }
}
