package com.juzizhen.redemptioncodefabric.rcode.repository;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
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
    /**
     * 紧凑 Gson：操作日志按 JSON Lines（每条单行）追加写入，避免多行美化格式破坏流式解析。
     */
    private static final Gson LOG_GSON = new Gson();
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
                Type type = new TypeToken<Map<String, CodeData>>() {
                }.getType();
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
     * 原子更新兑换码文件的辅助方法：读取当前数据、应用修改后写回。
     *
     * @param updater 用于修改兑换码 Map 的消费者
     */
    private synchronized void updateCodes(Consumer<Map<String, CodeData>> updater) {
        Map<String, CodeData> codes = loadAllCodes();
        updater.accept(codes);
        saveAllCodes(codes);
    }

    @Override
    public void appendOperationLog(OperationLogEntry logEntry) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
            writer.write(LOG_GSON.toJson(logEntry));
            writer.newLine();
        } catch (IOException e) {
            RedemptionCodeFabric.LOGGER.error("Failed to write operation log", e);
        }
    }

    @Override
    public List<OperationLogEntry> getOperationLog(int offset, int limit) {
        List<OperationLogEntry> all = new ArrayList<>();
        if (!LOG_FILE.exists()) return all;
        try (JsonReader reader = new JsonReader(new FileReader(LOG_FILE))) {
            reader.setStrictness(Strictness.LENIENT);
            while (reader.hasNext()) {
                OperationLogEntry entry = GSON.fromJson(reader, OperationLogEntry.class);
                if (entry != null) all.add(entry);
            }
        } catch (Exception e) {
            RedemptionCodeFabric.LOGGER.error("Failed to read operation log", e);
        }
        Collections.reverse(all);
        int fromIndex = Math.min(offset, all.size());
        int toIndex = Math.min(fromIndex + limit, all.size());
        return new ArrayList<>(all.subList(fromIndex, toIndex));
    }
}