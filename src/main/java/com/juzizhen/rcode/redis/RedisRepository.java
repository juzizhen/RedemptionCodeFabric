package com.juzizhen.rcode.redis;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.juzizhen.rcode.model.CodeData;
import com.juzizhen.rcode.model.CodeType;
import com.juzizhen.rcode.model.OperationLogEntry;
import com.juzizhen.rcode.repository.FileRepository;
import com.juzizhen.rcode.repository.IDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.lang.reflect.Type;
import java.util.*;

/**
 * Redis-backed implementation of {@link IDataRepository}.
 * Falls back to {@link FileRepository} when Redis is unavailable.
 */
public class RedisRepository implements IDataRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger("RedemptionCodeFabric-Redis");
    private static final Gson GSON = new Gson();
    private static final Type USED_BY_TYPE = new TypeToken<Map<String, List<Long>>>() {}.getType();

    private static final String KEY_PREFIX = "rcode:code:";
    private static final String KEY_CODE_INDEX = "rcode:codes";
    private static final String KEY_OPERATION_LOG = "rcode:operation_log";

    private final FileRepository fileFallback;

    public RedisRepository() {
        this.fileFallback = new FileRepository();
    }

    private boolean isRedisUnavailable() {
        return RedisManager.getInstance().isConnected();
    }

    private String serializeUsedBy(Map<String, List<Long>> usedBy) {
        if (usedBy == null || usedBy.isEmpty()) return "{}";
        return GSON.toJson(usedBy);
    }

    private Map<String, List<Long>> deserializeUsedBy(String json) {
        if (json == null || json.isEmpty() || json.equals("{}")) {
            return new HashMap<>();
        }
        try {
            Map<String, List<Long>> result = GSON.fromJson(json, USED_BY_TYPE);
            if (result != null) {
                Map<String, List<Long>> normalized = new HashMap<>();
                for (Map.Entry<String, List<Long>> entry : result.entrySet()) {
                    List<Long> timestamps = new ArrayList<>();
                    for (Number val : entry.getValue()) {
                        timestamps.add(val.longValue());
                    }
                    normalized.put(entry.getKey(), timestamps);
                }
                return normalized;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to deserialize used_by JSON: {}", json, e);
        }
        return new HashMap<>();
    }

    private CodeData fromHash(String code, Map<String, String> hash) {
        CodeData codeData = new CodeData(
                code,
                CodeType.valueOf(hash.getOrDefault("type", "ONCE")),
                hash.getOrDefault("reward", ""),
                hash.get("player"),
                hash.get("count") == null || hash.get("count").isEmpty() ? -1 : Integer.parseInt(hash.get("count")),
                hash.get("start_time") == null || hash.get("start_time").isEmpty() ? 0L : Long.parseLong(hash.get("start_time")),
                hash.get("end_time") == null || hash.get("end_time").isEmpty() ? 0L : Long.parseLong(hash.get("end_time")),
                hash.get("interval") == null || hash.get("interval").isEmpty() ? 0L : Long.parseLong(hash.get("interval"))
        );

        Map<String, List<Long>> usedBy = deserializeUsedBy(hash.get("used_by"));
        for (Map.Entry<String, List<Long>> entry : usedBy.entrySet()) {
            for (Long timestamp : entry.getValue()) {
                codeData.addUsedBy(entry.getKey(), timestamp);
            }
        }

        return codeData;
    }

    private Map<String, String> toHash(CodeData cd) {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("type", cd.getType().name());
        hash.put("reward", cd.getReward());
        hash.put("player", cd.getPlayer() != null ? cd.getPlayer() : "");
        hash.put("count", String.valueOf(cd.getCount()));
        hash.put("start_time", String.valueOf(cd.getStartTime()));
        hash.put("end_time", String.valueOf(cd.getEndTime()));
        hash.put("interval", String.valueOf(cd.getInterval()));
        hash.put("used_by", serializeUsedBy(cd.getUsedBy()));
        return hash;
    }

    @Override
    public Map<String, CodeData> loadAllCodes() {
        if (isRedisUnavailable()) {
            LOGGER.warn("Redis not available during loadAllCodes, falling back to file.");
            return fileFallback.loadAllCodes();
        }

        try (Jedis jedis = RedisManager.getInstance().getResource()) {
            Set<String> codeKeys = jedis.smembers(KEY_CODE_INDEX);
            Map<String, CodeData> result = new HashMap<>();

            for (String key : codeKeys) {
                // key format: rcode:code:{code}
                String code = key.substring(KEY_PREFIX.length());
                Map<String, String> hash = jedis.hgetAll(key);
                if (hash != null && !hash.isEmpty()) {
                    result.put(code, fromHash(code, hash));
                }
            }

            LOGGER.info("Loaded {} codes from Redis.", result.size());
            return result;

        } catch (Exception e) {
            LOGGER.error("Failed to load codes from Redis, falling back to file.", e);
            return fileFallback.loadAllCodes();
        }
    }

    @Override
    public void saveAllCodes(Map<String, CodeData> codes) {
        if (isRedisUnavailable()) {
            fileFallback.saveAllCodes(codes);
            return;
        }

        try (Jedis jedis = RedisManager.getInstance().getResource()) {
            // Use pipeline for batch efficiency
            var pipeline = jedis.pipelined();
            for (CodeData cd : codes.values()) {
                String key = KEY_PREFIX + cd.getCode();
                pipeline.hmset(key, toHash(cd));
                pipeline.sadd(KEY_CODE_INDEX, key);
            }
            pipeline.sync();
        } catch (Exception e) {
            LOGGER.error("Failed to save all codes to Redis, falling back to file.", e);
            fileFallback.saveAllCodes(codes);
        }
    }

    @Override
    public void saveCode(CodeData codeData) {
        if (isRedisUnavailable()) {
            fileFallback.saveCode(codeData);
            return;
        }

        try (Jedis jedis = RedisManager.getInstance().getResource()) {
            String key = KEY_PREFIX + codeData.getCode();
            jedis.hmset(key, toHash(codeData));
            jedis.sadd(KEY_CODE_INDEX, key);
        } catch (Exception e) {
            LOGGER.error("Failed to save code '{}' to Redis, falling back to file.", codeData.getCode(), e);
            fileFallback.saveCode(codeData);
        }
    }

    @Override
    public void removeCode(String code) {
        if (isRedisUnavailable()) {
            fileFallback.removeCode(code);
            return;
        }

        try (Jedis jedis = RedisManager.getInstance().getResource()) {
            String key = KEY_PREFIX + code;
            jedis.del(key);
            jedis.srem(KEY_CODE_INDEX, key);
        } catch (Exception e) {
            LOGGER.error("Failed to delete code '{}' from Redis, falling back to file.", code, e);
            fileFallback.removeCode(code);
        }
    }

    @Override
    public void appendOperationLog(OperationLogEntry logEntry) {
        if (isRedisUnavailable()) {
            fileFallback.appendOperationLog(logEntry);
            return;
        }

        try (Jedis jedis = RedisManager.getInstance().getResource()) {
            String json = GSON.toJson(logEntry);
            jedis.rpush(KEY_OPERATION_LOG, json);
        } catch (Exception e) {
            LOGGER.error("Failed to write operation log to Redis, falling back to file.", e);
            fileFallback.appendOperationLog(logEntry);
        }
    }

    @Override
    public List<OperationLogEntry> getOperationLog(int offset, int limit) {
        if (isRedisUnavailable()) {
            return fileFallback.getOperationLog(offset, limit);
        }

        try (Jedis jedis = RedisManager.getInstance().getResource()) {
            long total = jedis.llen(KEY_OPERATION_LOG);
            if (total == 0 || offset >= total) return new ArrayList<>();

            // List is chronological (rpush), we want newest first — reverse the indices
            int fromIndex = Math.min(offset, (int) total);
            int toIndex = Math.min(fromIndex + limit, (int) total);
            long start = total - toIndex;
            long end = total - fromIndex - 1;
            List<String> jsonList = jedis.lrange(KEY_OPERATION_LOG, start, end);
            Collections.reverse(jsonList);

            List<OperationLogEntry> result = new ArrayList<>();
            Type detailsType = new TypeToken<Map<String, String>>() {}.getType();
            for (String json : jsonList) {
                try {
                    com.google.gson.JsonObject obj = GSON.fromJson(json, com.google.gson.JsonObject.class);
                    long timestamp = obj.get("timestamp").getAsLong();
                    String opType = obj.get("operationType").getAsString();
                    String executor = obj.get("executor").getAsString();
                    Map<String, String> details = GSON.fromJson(obj.get("details"), detailsType);
                    result.add(new OperationLogEntry(timestamp, opType, executor, details != null ? details : new HashMap<>()));
                } catch (Exception e) {
                    LOGGER.warn("Failed to parse operation log entry: {}", json, e);
                }
            }
            return result;
        } catch (Exception e) {
            LOGGER.error("Failed to read operation log from Redis, falling back to file.", e);
            return fileFallback.getOperationLog(offset, limit);
        }
    }
}
