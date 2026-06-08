package com.juzizhen.rcode.manager;

import com.juzizhen.RedemptionCodeFabric;
import com.juzizhen.config.ModConfig;
import com.juzizhen.rcode.model.CodeData;
import com.juzizhen.rcode.model.CodeType;
import com.juzizhen.rcode.model.UsageData;
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
import java.util.stream.Collectors;

public class CodeManager {

    private final IDataRepository repository;
    private final Map<String, CodeData> codes;
    private final List<UsageData> usageHistory;

    public CodeManager() {
        this.repository = new FileRepository();
        this.codes = repository.loadAllCodes();
        this.usageHistory = repository.loadAllUsageData();
    }

    public CodeData getCode(String code) {
        return codes.get(code);
    }

    public void addCode(CodeData codeData) {
        codes.put(codeData.getCode(), codeData);
        repository.saveAllCodes(codes);
    }

    public boolean deleteCode(String code) {
        if (codes.containsKey(code)) {
            codes.remove(code);
            repository.saveAllCodes(codes);
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
                String ownerName = codeData.getPlayer();
                try {
                    UUID ownerUuid = UUID.fromString(codeData.getPlayer());
                    ServerPlayerEntity player = source.getServer().getPlayerManager().getPlayer(ownerUuid);
                    if (player != null) {
                        ownerName = player.getName().getString();
                    }
                } catch (IllegalArgumentException e) {
                    // Not a valid UUID, just use the stored string
                }
                info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_player", ownerName).copy().formatted(Formatting.BLUE));
                info.append("\n");
                break;
            case GLOBAL_LIMIT:
                if (codeData.getPlayer() != null && !codeData.getPlayer().isEmpty()) {
                    List<String> allowedPlayers = Arrays.asList(codeData.getPlayer().split(","));
                    info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_player_group", allowedPlayers.size()).copy().formatted(Formatting.BLUE));
                    info.append("\n");
                }
                info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_uses_left", codeData.getCount() - codeData.getUsedBy().size()).copy().formatted(Formatting.BLUE));
                info.append("\n");
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

            Map<String, Long> usageCounts = usageHistory.stream()
                    .filter(u -> u.getCode().equals(code))
                    .collect(Collectors.groupingBy(UsageData::getPlayerUUID, Collectors.counting()));

            if (usageCounts.isEmpty()) {
                info.append(Text.literal("  Not used by anyone yet.").formatted(Formatting.GRAY));
                info.append("\n");
            } else {
                List<Map.Entry<String, Long>> sortedUsers = new ArrayList<>(usageCounts.entrySet());
                sortedUsers.sort(Map.Entry.<String, Long>comparingByValue().reversed());

                for (Map.Entry<String, Long> entry : sortedUsers) {
                    String uuid = entry.getKey();
                    long count = entry.getValue();
                    String name = uuid; // Default to UUID

                    try {
                        UUID playerUuid = UUID.fromString(uuid);
                        ServerPlayerEntity onlinePlayer = source.getServer().getPlayerManager().getPlayer(playerUuid);
                        if (onlinePlayer != null) {
                            name = onlinePlayer.getName().getString();
                        } else {
                            if (source.getServer().getUserCache() == null) {
                                return Text.empty();
                            }
                            var profileOpt = source.getServer().getUserCache().getByUuid(playerUuid);
                            if (profileOpt.isPresent()) {
                                name = profileOpt.get().getName();
                            }
                        }
                    } catch (IllegalArgumentException e) { /* keep name as uuid */ }

                    MutableText userText = Text.literal(name).formatted(Formatting.AQUA)
                            .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, uuid))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Copy UUID"))));

                    info.append(Text.literal("  - ").append(userText).append(Text.literal(": " + count + " times")).formatted(Formatting.BLUE));
                    info.append("\n");
                }
            }
        } else {
            info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_used_by_header", codeData.getUsedBy().size()).copy().formatted(Formatting.BLUE));
            info.append("\n");
            for (String uuid : codeData.getUsedBy()) {
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

        return MessageUtils.createText(source, "redemptioncodefabric.message.redeem_success");
    }

    private String validateCode(CodeData codeData, String playerUUID, long currentTime) {
        switch (codeData.getType()) {
            case ONCE:
                if (!codeData.getUsedBy().isEmpty()) return "redemptioncodefabric.message.code_already_used";
                break;
            case GLOBAL_UNLIMITED:
            case PERSONAL:
            case GLOBAL_LIMIT:
                if (codeData.getUsedBy().contains(playerUUID)) return "redemptioncodefabric.message.code_already_used";
                break;
            case TIMED:
                if (currentTime < codeData.getStartTime() || (codeData.getEndTime() != 0 && currentTime > codeData.getEndTime())) return "redemptioncodefabric.message.code_out_of_time";
                if (codeData.getUsedBy().contains(playerUUID)) return "redemptioncodefabric.message.code_already_used";
                break;
            case CYCLE:
                if (currentTime < codeData.getStartTime()) return "redemptioncodefabric.message.code_not_yet_active";
                
                long timeSinceStart = currentTime - codeData.getStartTime();
                long currentCycleIndex = timeSinceStart / codeData.getInterval();
                long currentCycleStartTime = codeData.getStartTime() + currentCycleIndex * codeData.getInterval();

                UsageData lastUsage = findLastUsage(codeData.getCode(), playerUUID);
                if (lastUsage != null && lastUsage.getTimestamp() >= currentCycleStartTime) {
                    return "redemptioncodefabric.message.cycle_wait";
                }
                break;
            case PERMANENT:
                // No validation needed, anyone can use it anytime, any number of times.
                break;
        }

        if (codeData.getType() == CodeType.PERSONAL && !playerUUID.equals(codeData.getPlayer())) return "redemptioncodefabric.message.code_invalid_or_nonexistent";
        if (codeData.getType() == CodeType.GLOBAL_LIMIT) {
            if (codeData.getPlayer() != null && !codeData.getPlayer().isEmpty()) {
                List<String> allowedPlayers = Arrays.asList(codeData.getPlayer().split(","));
                if (!allowedPlayers.contains(playerUUID)) {
                    return "redemptioncodefabric.message.code_invalid_or_nonexistent";
                }
            }
            if (codeData.getCount() <= codeData.getUsedBy().size() && codeData.getCount() != -1) {
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
                int amount = Integer.parseInt(expPart.substring(0, expPart.length() - 1));
                if (expPart.toUpperCase().endsWith("L")) {
                    finalAmount = amount;
                    player.addExperienceLevels(finalAmount);
                } else {
                    if (expPart.toUpperCase().endsWith("P")) {
                        finalAmount = amount;
                    } else {
                        finalAmount = Integer.parseInt(expPart);
                    }
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
        // For CYCLE and PERMANENT, we still add to usedBy to track usage counts for the info command.
        // The validation logic handles whether they can be used again.
        codeData.addUsedBy(playerUUID);

        if (ModConfig.CONFIG.logRedemptionHistory) {
            usageHistory.add(new UsageData(playerUUID, codeData.getCode(), currentTime));
            if (ModConfig.CONFIG.maxLogEntries != -1 && usageHistory.size() > ModConfig.CONFIG.maxLogEntries) {
                usageHistory.remove(0);
            }
            repository.saveAllUsageData(usageHistory);
        }
        repository.saveAllCodes(codes);
    }
    
    private UsageData findLastUsage(String code, String playerUUID) {
        for (int i = usageHistory.size() - 1; i >= 0; i--) {
            UsageData u = usageHistory.get(i);
            if (u.getPlayerUUID().equals(playerUUID) && u.getCode().equals(code)) {
                return u;
            }
        }
        return null;
    }
}
