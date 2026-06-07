package com.juzizhen.rcode.manager;

import com.juzizhen.RedemptionCodeFabric;
import com.juzizhen.rcode.model.CodeData;
import com.juzizhen.rcode.model.UsageData;
import com.juzizhen.rcode.repository.FileRepository;
import com.juzizhen.rcode.repository.IDataRepository;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Language;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CodeManager {

    private final IDataRepository repository;
    private final Map<String, CodeData> codes;
    private final List<UsageData> usageHistory;
    private final MinecraftServer server;

    public CodeManager(MinecraftServer server) {
        this.server = server;
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

    public Text getCodeInfo(String code) {
        CodeData codeData = codes.get(code);
        if (codeData == null) {
            return createTextForPlayer(null, "redemptioncodefabric.message.code_not_found");
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        MutableText info = Text.literal("");
        info.append(createTextForPlayer(null, "redemptioncodefabric.message.info_header", codeData.getCode()).copy().formatted(Formatting.YELLOW));
        info.append("\n");
        info.append(createTextForPlayer(null, "redemptioncodefabric.message.info_type", codeData.getType()).copy().formatted(Formatting.BLUE));
        info.append("\n");
        info.append(createTextForPlayer(null, "redemptioncodefabric.message.info_reward", codeData.getReward()).copy().formatted(Formatting.BLUE));
        info.append("\n");

        switch (codeData.getType()) {
            case PERSONAL:
                info.append(createTextForPlayer(null, "redemptioncodefabric.message.info_player", codeData.getPlayer()).copy().formatted(Formatting.BLUE));
                info.append("\n");
                break;
            case GLOBAL_LIMIT:
                info.append(createTextForPlayer(null, "redemptioncodefabric.message.info_uses_left", codeData.getCount()).copy().formatted(Formatting.BLUE));
                info.append("\n");
                break;
            case TIMED:
                info.append(createTextForPlayer(null, "redemptioncodefabric.message.info_start_time", sdf.format(new Date(codeData.getStartTime()))).copy().formatted(Formatting.BLUE));
                info.append("\n");
                info.append(createTextForPlayer(null, "redemptioncodefabric.message.info_end_time", sdf.format(new Date(codeData.getEndTime()))).copy().formatted(Formatting.BLUE));
                info.append("\n");
                break;
            case CYCLE:
                info.append(createTextForPlayer(null, "redemptioncodefabric.message.info_start_time", sdf.format(new Date(codeData.getStartTime()))).copy().formatted(Formatting.BLUE));
                info.append("\n");
                info.append(createTextForPlayer(null, "redemptioncodefabric.message.info_interval", codeData.getInterval() / 1000).copy().formatted(Formatting.BLUE));
                info.append("\n");
                break;
        }

        info.append(createTextForPlayer(null, "redemptioncodefabric.message.info_used_by_header", codeData.getUsedBy().size()).copy().formatted(Formatting.BLUE));
        info.append("\n");
        for (String uuid : codeData.getUsedBy()) {
            MutableText uuidText = Text.literal(uuid)
                .setStyle(Text.empty().getStyle()
                .withColor(Formatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, uuid))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Copy UUID"))));
            info.append(createTextForPlayer(null, "redemptioncodefabric.message.info_used_by_entry", "").copy().append(uuidText));
            info.append("\n");
        }
        info.append(createTextForPlayer(null, "redemptioncodefabric.message.info_footer").copy().formatted(Formatting.YELLOW));

        return info;
    }


    public Text redeemCode(String code, ServerPlayerEntity player) {
        CodeData codeData = codes.get(code);
        if (codeData == null) {
            return createTextForPlayer(player.getUuid(), "redemptioncodefabric.message.code_invalid_or_nonexistent");
        }

        String playerUUID = player.getUuidAsString();
        long currentTime = System.currentTimeMillis();

        String validationErrorKey = validateCode(codeData, playerUUID, currentTime);
        if (validationErrorKey != null) {
            if (validationErrorKey.equals("redemptioncodefabric.message.cycle_wait")) {
                 UsageData lastUsage = findLastUsage(codeData.getCode(), playerUUID);
                 long nextAvailableTime = lastUsage.getTimestamp() + codeData.getInterval();
                 long remainingTime = (nextAvailableTime - currentTime) / 1000;
                 return createTextForPlayer(player.getUuid(), validationErrorKey, remainingTime);
            }
            return createTextForPlayer(player.getUuid(), validationErrorKey);
        }
        
        Text rewardResult = grantReward(codeData, player);
        if (rewardResult != null) {
            return rewardResult;
        }

        recordUsage(codeData, playerUUID, currentTime);

        return createTextForPlayer(player.getUuid(), "redemptioncodefabric.message.redeem_success");
    }

    private String validateCode(CodeData codeData, String playerUUID, long currentTime) {
        // ... (same as before)
        return null;
    }

    private Text grantReward(CodeData codeData, ServerPlayerEntity player) {
        // ... (same as before, but using createTextForPlayer for errors)
        return null;
    }

    private void recordUsage(CodeData codeData, String playerUUID, long currentTime) {
        // ... (same as before)
    }
    
    private UsageData findLastUsage(String code, String playerUUID) {
        // ... (same as before)
        return null;
    }

    public static Text createTextForPlayer(UUID playerUuid, String key, Object... args) {
        if (playerUuid != null && RedemptionCodeFabric.hasMod(playerUuid)) {
            return Text.translatable(key, args);
        } else {
            return Text.literal(String.format(Language.getInstance().get(key), args));
        }
    }
}
