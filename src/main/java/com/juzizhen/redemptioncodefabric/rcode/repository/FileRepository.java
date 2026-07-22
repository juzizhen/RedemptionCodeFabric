package com.juzizhen.redemptioncodefabric.rcode.repository;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.juzizhen.redemptioncodefabric.RedemptionCodeFabric;
import com.juzizhen.redemptioncodefabric.rcode.model.CodeData;
import com.juzizhen.redemptioncodefabric.rcode.model.OperationLogEntry;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

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
    public void saveCode(CodeData codeData) {
        updateCodes(codes -> codes.put(codeData.getCode(), codeData));
    }

    @Override
    public void removeCode(String code) {
        updateCodes(codes -> codes.remove(code));
    }

    /**
     * A helper method to atomically update the codes file.
     * It reads the current codes, applies a modification, and writes them back.
     * @param updater A consumer that modifies the map of codes.
     */
    private synchronized void updateCodes(Consumer<Map<String, CodeData>> updater) {
        Map<String, CodeData> codes = loadAllCodes();
        updater.accept(codes);
        saveAllCodes(codes);
    }

    @Override
    public void appendOperationLog(OperationLogEntry logEntry) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
            writer.write(GSON.toJson(logEntry));
            writer.newLine();
        } catch (IOException e) {
            RedemptionCodeFabric.LOGGER.error("Failed to write operation log", e);
        }
    }

    @Override
    public List<OperationLogEntry> getOperationLog(int offset, int limit) {
        List<OperationLogEntry> all = new ArrayList<>();
        if (!LOG_FILE.exists()) return all;
        try (BufferedReader reader = new BufferedReader(new FileReader(LOG_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                OperationLogEntry entry = GSON.fromJson(line, OperationLogEntry.class);
                if (entry != null) all.add(entry);
            }
        } catch (IOException e) {
            RedemptionCodeFabric.LOGGER.error("Failed to read operation log", e);
        }
        // Reverse for newest-first, then apply pagination
        Collections.reverse(all);
        int fromIndex = Math.min(offset, all.size());
        int toIndex = Math.min(fromIndex + limit, all.size());
        return all.subList(fromIndex, toIndex);
    }
}