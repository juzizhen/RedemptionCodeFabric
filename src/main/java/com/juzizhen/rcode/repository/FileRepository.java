package com.juzizhen.rcode.repository;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.juzizhen.RedemptionCodeFabric;
import com.juzizhen.config.ModConfig;
import com.juzizhen.rcode.model.CodeData;
import com.juzizhen.rcode.model.UsageData;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileRepository implements IDataRepository {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir();
    private static final File CODE_DIR = CONFIG_DIR.resolve(RedemptionCodeFabric.MOD_ID).toFile();
    private static final File CODE_FILE = new File(CODE_DIR, "redemptioncodefabric-code.json");
    private static final File USAGE_FILE = new File(CODE_DIR, "usage-history.json");

    public FileRepository() {
        if (!CODE_DIR.exists()) {
            if (!CODE_DIR.mkdirs()) {
                RedemptionCodeFabric.LOGGER.error("Failed to create code directory");
            }
        }
    }

    @Override
    public Map<String, CodeData> loadAllCodes() {
        if (CODE_FILE.exists()) {
            try (FileReader reader = new FileReader(CODE_FILE)) {
                Type type = new TypeToken<Map<String, CodeData>>() {}.getType();
                Map<String, CodeData> codes = GSON.fromJson(reader, type);
                return codes != null ? codes : new HashMap<>();
            } catch (IOException e) {
                RedemptionCodeFabric.LOGGER.error("Failed to load codes", e);
            }
        }
        return new HashMap<>();
    }

    @Override
    public void saveAllCodes(Map<String, CodeData> codes) {
        try (FileWriter writer = new FileWriter(CODE_FILE)) {
            GSON.toJson(codes, writer);
        } catch (IOException e) {
            RedemptionCodeFabric.LOGGER.error("Failed to save codes", e);
        }
    }

    @Override
    public List<UsageData> loadAllUsageData() {
        if (ModConfig.CONFIG.logRedemptionHistory && USAGE_FILE.exists()) {
            try (FileReader reader = new FileReader(USAGE_FILE)) {
                Type type = new TypeToken<List<UsageData>>() {}.getType();
                List<UsageData> usageHistory = GSON.fromJson(reader, type);
                return usageHistory != null ? usageHistory : new ArrayList<>();
            } catch (IOException e) {
                RedemptionCodeFabric.LOGGER.error("Failed to load usage history", e);
            }
        }
        return new ArrayList<>();
    }

    @Override
    public void saveAllUsageData(List<UsageData> usageHistory) {
        if (ModConfig.CONFIG.logRedemptionHistory) {
            try (FileWriter writer = new FileWriter(USAGE_FILE)) {
                GSON.toJson(usageHistory, writer);
            } catch (IOException e) {
                RedemptionCodeFabric.LOGGER.error("Failed to save usage history", e);
            }
        }
    }
}
