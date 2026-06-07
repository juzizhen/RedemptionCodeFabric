package com.juzizhen.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.juzizhen.RedemptionCodeFabric;
import com.juzizhen.util.Utils;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir();
    private static final File CONFIG_FILE = CONFIG_PATH.resolve(RedemptionCodeFabric.MOD_ID + ".json").toFile();

    public static ModConfigData CONFIG = new ModConfigData();

    public static void onInitialize() {
        load();
    }

    public static void load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                CONFIG = GSON.fromJson(reader, ModConfigData.class);
                if (CONFIG == null) {
                    CONFIG = new ModConfigData();
                }
            } catch (IOException e) {
                RedemptionCodeFabric.LOGGER.error("Failed to load config", e);
                CONFIG = new ModConfigData();
            }
        }
        
        // Check for new fields and save back to file
        if (CONFIG.adminPassword == null || CONFIG.adminPassword.isEmpty()) {
            CONFIG.adminPassword = Utils.generateRandomString(16);
        }
        if (CONFIG.dataStoragePattern < 0 || CONFIG.dataStoragePattern > 2) {
            CONFIG.dataStoragePattern = 0;
        }

        save();
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(CONFIG, writer);
        } catch (IOException e) {
            RedemptionCodeFabric.LOGGER.error("Failed to save config", e);
        }
    }
}
