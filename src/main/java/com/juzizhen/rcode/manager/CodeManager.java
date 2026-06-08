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
import java.util.Date;
import java.util.List;
import java.util.Map;

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
                info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_player", codeData.getPlayer()).copy().formatted(Formatting.BLUE));
                info.append("\n");
                break;
            case GLOBAL_LIMIT:
                info.append(MessageUtils.createText(source, "redemptioncodefabric.message.info_uses_left", codeData.getCount()).copy().formatted(Formatting.BLUE));
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
                 UsageData lastUsage = findLastUsage(codeData.getCode(), playerUUID);
                 long nextAvailableTime = lastUsage != null ? lastUsage.getTimestamp() : currentTime + codeData.getInterval();
                 long remainingTime = (nextAvailableTime - currentTime) / 1000;
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
            case PERMANENT:
            case GLOBAL_UNLIMITED:
            case PERSONAL:
            case GLOBAL_LIMIT:
            case TIMED:
                if (codeData.getUsedBy().contains(playerUUID)) return "redemptioncodefabric.message.code_already_used";
                break;
            case CYCLE:
                UsageData lastUsage = findLastUsage(codeData.getCode(), playerUUID);
                if (lastUsage != null) {
                    long nextAvailableTime = lastUsage.getTimestamp() + codeData.getInterval();
                    if (currentTime < nextAvailableTime) return "redemptioncodefabric.message.cycle_wait";
                }
                break;
        }

        if (codeData.getType() == CodeType.PERSONAL && !playerUUID.equals(codeData.getPlayer())) return "redemptioncodefabric.message.code_invalid_or_nonexistent";
        if (codeData.getType() == CodeType.GLOBAL_LIMIT && codeData.getCount() <= 0) return "redemptioncodefabric.message.code_invalid_or_nonexistent";
        if (codeData.getType() == CodeType.TIMED && (currentTime < codeData.getStartTime() || (codeData.getEndTime() != 0 && currentTime > codeData.getEndTime()))) return "redemptioncodefabric.message.code_invalid_or_nonexistent";

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
                int amount;
                if (expPart.toUpperCase().endsWith("L")) {
                    amount = Integer.parseInt(expPart.substring(0, expPart.length() - 1));
                    player.addExperienceLevels(amount);
                } else {
                    if (expPart.toUpperCase().endsWith("P")) {
                        amount = Integer.parseInt(expPart.substring(0, expPart.length() - 1));
                    } else {
                        amount = Integer.parseInt(expPart);
                    }
                    player.addExperience(amount);
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
        if (codeData.getType() == CodeType.GLOBAL_LIMIT) {
            codeData.setCount(codeData.getCount() - 1);
        }
        if (codeData.getType() != CodeType.CYCLE) {
            codeData.addUsedBy(playerUUID);
        }

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
