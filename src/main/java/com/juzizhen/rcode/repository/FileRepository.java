package com.juzizhen.rcode.repository;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.juzizhen.RedemptionCodeFabric;
import com.juzizhen.rcode.model.CodeData;
import com.juzizhen.rcode.model.OperationLogEntry;
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
    private static final File LOG_FILE = new File(CODE_DIR, "operationLog.json");

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
    public void appendOperationLog(OperationLogEntry logEntry) {
        List<OperationLogEntry> logs = new ArrayList<>();
        if (LOG_FILE.exists()) {
            try (FileReader reader = new FileReader(LOG_FILE)) {
                Type type = new TypeToken<List<OperationLogEntry>>() {}.getType();
                List<OperationLogEntry> existingLogs = GSON.fromJson(reader, type);
                if (existingLogs != null) {
                    logs.addAll(existingLogs);
                }
            } catch (IOException e) {
                RedemptionCodeFabric.LOGGER.error("Failed to read operation log", e);
            }
        }
        logs.add(logEntry);
        try (FileWriter writer = new FileWriter(LOG_FILE)) {
            GSON.toJson(logs, writer);
        } catch (IOException e) {
            RedemptionCodeFabric.LOGGER.error("Failed to write operation log", e);
        }
    }
}
