package com.juzizhen.rcode.manager;

import com.juzizhen.RedemptionCodeFabric;
import com.juzizhen.config.ModConfig;
import com.juzizhen.rcode.model.CodeData;
import com.juzizhen.rcode.model.CodeType;
import com.juzizhen.rcode.model.UsageData;
import com.juzizhen.rcode.repository.FileRepository;
import com.juzizhen.rcode.repository.IDataRepository;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Map;

public class CodeManager {

    private final IDataRepository repository;
    private final Map<String, CodeData> codes;
    private final List<UsageData> usageHistory;
    private final MinecraftServer server;

    public CodeManager(MinecraftServer server) {
        this.server = server;
        // For now, we are hard-coding the FileRepository.
        // In the future, you could use a factory or dependency injection
        // to choose the repository based on a config setting.
        this.repository = new FileRepository();
        this.codes = repository.loadAllCodes();
        this.usageHistory = repository.loadAllUsageData();
    }

    public void addCode(CodeData codeData) {
        codes.put(codeData.getCode(), codeData);
        repository.saveAllCodes(codes);
    }

    public Text redeemCode(String code, ServerPlayerEntity player) {
        CodeData codeData = codes.get(code);
        if (codeData == null) {
            return Text.translatable("redemptioncodefabric.message.code_invalid_or_nonexistent");
        }

        String playerUUID = player.getUuidAsString();
        long currentTime = System.currentTimeMillis();

        // Validation logic...
        String validationError = validateCode(codeData, playerUUID, currentTime);
        if (validationError != null) {
            return Text.translatable(validationError);
        }
        
        // Grant reward
        Text rewardResult = grantReward(codeData, player);
        if (rewardResult != null) {
            return rewardResult;
        }

        // Record usage
        recordUsage(codeData, playerUUID, currentTime);

        return Text.translatable("redemptioncodefabric.message.redeem_success");
    }

    private String validateCode(CodeData codeData, String playerUUID, long currentTime) {
        switch (codeData.getType()) {
            case ONCE:
                if (!codeData.getUsedBy().isEmpty()) {
                    return "redemptioncodefabric.message.code_already_used";
                }
                break;
            case PERMANENT:
            case GLOBAL_UNLIMITED:
            case PERSONAL:
            case GLOBAL_LIMIT:
            case TIMED:
                if (codeData.getUsedBy().contains(playerUUID)) {
                    return "redemptioncodefabric.message.code_already_used";
                }
                break;
            case CYCLE:
                UsageData lastUsage = findLastUsage(codeData.getCode(), playerUUID);
                if (lastUsage != null) {
                    long nextAvailableTime = lastUsage.getTimestamp() + codeData.getInterval();
                    if (currentTime < nextAvailableTime) {
                        long remainingTime = (nextAvailableTime - currentTime) / 1000;
                        return "redemptioncodefabric.message.cycle_wait";
                    }
                }
                break;
        }

        if (codeData.getType() == CodeType.PERSONAL && !playerUUID.equals(codeData.getPlayer())) {
            return "redemptioncodefabric.message.code_invalid_or_nonexistent";
        }
        if (codeData.getType() == CodeType.GLOBAL_LIMIT && codeData.getCount() <= 0) {
            return "redemptioncodefabric.message.code_invalid_or_nonexistent";
        }
        if (codeData.getType() == CodeType.TIMED && (currentTime < codeData.getStartTime() || (codeData.getEndTime() != 0 && currentTime > codeData.getEndTime()))) {
            return "redemptioncodefabric.message.code_invalid_or_nonexistent";
        }

        return null; // No validation errors
    }

    private Text grantReward(CodeData codeData, ServerPlayerEntity player) {
        String rewardString = codeData.getReward();
        String lowerReward = rewardString.toLowerCase();

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
                player.getInventory().offer(stack, true);
            } catch (CommandSyntaxException e) {
                RedemptionCodeFabric.LOGGER.error("Failed to redeem item code: {}", rewardString, e);
                return Text.translatable("redemptioncodefabric.message.redeem_fail_item");
            }
        } else if (lowerReward.startsWith("exp@")) {
            // ... (exp logic)
        } else if (lowerReward.startsWith("permissions@")) {
            return Text.translatable("redemptioncodefabric.message.permission_reward_contact_admin");
        }
        return null; // Reward granted successfully
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
