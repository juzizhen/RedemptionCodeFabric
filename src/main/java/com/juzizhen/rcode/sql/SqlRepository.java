package com.juzizhen.rcode.sql;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.juzizhen.RedemptionCodeFabric;
import com.juzizhen.async.AsyncIoManager;
import com.juzizhen.rcode.model.CodeData;
import com.juzizhen.rcode.model.CodeType;
import com.juzizhen.rcode.model.OperationLogEntry;
import com.juzizhen.rcode.repository.IDataRepository;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 基于原生 JDBC 的 SQL 存储实现，连接从 {@link SqlManager#getConnectionPool()} 获取。
 */
public class SqlRepository implements IDataRepository {

    private static final Gson GSON = new Gson();
    private static final Type USED_BY_TYPE = new TypeToken<Map<String, List<Long>>>() {}.getType();
    private static final Type DETAILS_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final SqlManager sqlManager;

    public SqlRepository(SqlManager sqlManager) {
        this.sqlManager = sqlManager;
    }

    @Override
    public Map<String, CodeData> loadAllCodes() {
        try {
            return CompletableFuture.supplyAsync(() -> {
                Map<String, CodeData> codes = new HashMap<>();
                Connection conn = null;
                try {
                    conn = sqlManager.getConnectionPool().getConnection();
                    String sql = """
                        SELECT code, type, reward, player, count, start_time, end_time, code_interval, used_by
                        FROM redemption_codes
                        """;

                    try (PreparedStatement stmt = conn.prepareStatement(sql);
                         ResultSet rs = stmt.executeQuery()) {

                        while (rs.next()) {
                            CodeData codeData = mapRowToCodeData(rs);
                            codes.put(codeData.getCode(), codeData);
                        }
                    }
                } catch (SQLException e) {
                    RedemptionCodeFabric.LOGGER.error("Failed to load codes from SQL", e);
                } finally {
                    if (conn != null) {
                        sqlManager.getConnectionPool().releaseConnection(conn);
                    }
                }
                return codes;
            }, AsyncIoManager.getIoExecutor()).join();
        } catch (Exception e) {
            RedemptionCodeFabric.LOGGER.error("Failed to load codes", e);
            return new HashMap<>();
        }
    }

    @Override
    public void saveAllCodes(Map<String, CodeData> codes) {
        for (CodeData codeData : codes.values()) {
            saveCode(codeData);
        }
    }

    @Override
    public void saveCode(CodeData codeData) {
        try {
            CompletableFuture.runAsync(() -> {
                Connection conn = null;
                try {
                    conn = sqlManager.getConnectionPool().getConnection();
                    String usedByJson = GSON.toJson(codeData.getUsedBy());

                    String sql = """
                        INSERT INTO redemption_codes
                        (code, type, reward, player, count, start_time, end_time, code_interval, used_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                        type = VALUES(type), reward = VALUES(reward), player = VALUES(player),
                        count = VALUES(count), start_time = VALUES(start_time),
                        end_time = VALUES(end_time), code_interval = VALUES(code_interval),
                        used_by = VALUES(used_by)
                        """;

                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, codeData.getCode());
                        stmt.setString(2, codeData.getType().name());
                        stmt.setString(3, codeData.getReward());
                        stmt.setString(4, codeData.getPlayer());
                        stmt.setInt(5, codeData.getCount());
                        stmt.setLong(6, codeData.getStartTime());
                        stmt.setLong(7, codeData.getEndTime());
                        stmt.setLong(8, codeData.getInterval());
                        stmt.setString(9, usedByJson);
                        stmt.executeUpdate();
                    }
                } catch (SQLException e) {
                    RedemptionCodeFabric.LOGGER.error("Failed to save code to SQL: {}", codeData.getCode(), e);
                } finally {
                    if (conn != null) {
                        sqlManager.getConnectionPool().releaseConnection(conn);
                    }
                }
            }, AsyncIoManager.getIoExecutor()).join();
        } catch (Exception e) {
            RedemptionCodeFabric.LOGGER.error("Failed to save code", e);
        }
    }

    /**
     * 将数据库行映射为 CodeData 对象。
     */
    private CodeData mapRowToCodeData(ResultSet rs) throws SQLException {
        String usedByJson = rs.getString("used_by");
        Map<String, List<Long>> usedBy = parseUsedByJson(usedByJson);

        CodeData codeData = new CodeData(
                rs.getString("code"),
                CodeType.valueOf(rs.getString("type")),
                rs.getString("reward"),
                rs.getString("player"),
                rs.getInt("count"),
                rs.getLong("start_time"),
                rs.getLong("end_time"),
                rs.getLong("code_interval")
        );

        // 添加使用记录
        usedBy.forEach((uuid, timestamps) ->
            timestamps.forEach(ts -> codeData.addUsedBy(uuid, ts))
        );

        return codeData;
    }

    /**
     * 安全解析 used_by JSON 字符串。
     */
    private Map<String, List<Long>> parseUsedByJson(String usedByJson) {
        if (usedByJson == null || usedByJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            Map<String, List<Long>> result = GSON.fromJson(usedByJson, USED_BY_TYPE);
            return result != null ? result : new HashMap<>();
        } catch (Exception e) {
            RedemptionCodeFabric.LOGGER.warn("Failed to parse used_by JSON, returning empty map", e);
            return new HashMap<>();
        }
    }

    @Override
    public void removeCode(String code) {
        try {
            CompletableFuture.runAsync(() -> {
                Connection conn = null;
                try {
                    conn = sqlManager.getConnectionPool().getConnection();
                    String sql = "DELETE FROM redemption_codes WHERE code = ?";

                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, code);
                        stmt.executeUpdate();
                    }
                } catch (SQLException e) {
                    RedemptionCodeFabric.LOGGER.error("Failed to remove code from SQL: {}", code, e);
                } finally {
                    if (conn != null) {
                        sqlManager.getConnectionPool().releaseConnection(conn);
                    }
                }
            }, AsyncIoManager.getIoExecutor()).join();
        } catch (Exception e) {
            RedemptionCodeFabric.LOGGER.error("Failed to remove code", e);
        }
    }

    @Override
    public void appendOperationLog(OperationLogEntry logEntry) {
        try {
            CompletableFuture.runAsync(() -> {
                Connection conn = null;
                try {
                    conn = sqlManager.getConnectionPool().getConnection();
                    String detailsJson = GSON.toJson(logEntry.getDetails());
                    String sql = """
                        INSERT INTO operation_logs (timestamp, operation_type, executor, details)
                        VALUES (?, ?, ?, ?)
                        """;

                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setLong(1, logEntry.getTimestamp());
                        stmt.setString(2, logEntry.getOperationType());
                        stmt.setString(3, logEntry.getExecutor());
                        stmt.setString(4, detailsJson);
                        stmt.executeUpdate();
                    }
                } catch (SQLException e) {
                    RedemptionCodeFabric.LOGGER.error("Failed to append operation log to SQL", e);
                } finally {
                    if (conn != null) {
                        sqlManager.getConnectionPool().releaseConnection(conn);
                    }
                }
            }, AsyncIoManager.getIoExecutor()).join();
        } catch (Exception e) {
            RedemptionCodeFabric.LOGGER.error("Failed to append operation log", e);
        }
    }

    @Override
    public List<OperationLogEntry> getOperationLog(int offset, int limit) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                List<OperationLogEntry> logs = new ArrayList<>();
                Connection conn = null;
                try {
                    conn = sqlManager.getConnectionPool().getConnection();
                    String sql = """
                        SELECT timestamp, operation_type, executor, details
                        FROM operation_logs
                        ORDER BY timestamp DESC
                        LIMIT ? OFFSET ?
                        """;

                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setInt(1, limit);
                        stmt.setInt(2, offset);

                        try (ResultSet rs = stmt.executeQuery()) {
                            while (rs.next()) {
                                String detailsJson = rs.getString("details");
                                Map<String, String> details = parseDetailsJson(detailsJson);

                                OperationLogEntry entry = new OperationLogEntry(
                                        rs.getLong("timestamp"),
                                        rs.getString("operation_type"),
                                        rs.getString("executor"),
                                        details
                                );
                                logs.add(entry);
                            }
                        }
                    }
                } catch (SQLException e) {
                    RedemptionCodeFabric.LOGGER.error("Failed to read operation log from SQL", e);
                } finally {
                    if (conn != null) {
                        sqlManager.getConnectionPool().releaseConnection(conn);
                    }
                }
                return logs;
            }, AsyncIoManager.getIoExecutor()).join();
        } catch (Exception e) {
            RedemptionCodeFabric.LOGGER.error("Failed to get operation log", e);
            return new ArrayList<>();
        }
    }

    /**
     * 安全解析 details JSON 字符串。
     */
    private Map<String, String> parseDetailsJson(String detailsJson) {
        if (detailsJson == null || detailsJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            Map<String, String> result = GSON.fromJson(detailsJson, DETAILS_TYPE);
            return result != null ? result : new HashMap<>();
        } catch (Exception e) {
            RedemptionCodeFabric.LOGGER.warn("Failed to parse details JSON, returning empty map", e);
            return new HashMap<>();
        }
    }
}
