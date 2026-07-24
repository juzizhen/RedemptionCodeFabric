package com.juzizhen.redemptioncodefabric.rcode.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class CodeData {
    private final String code;
    private final CodeType type;
    private final String reward;
    private final String player; // 用于 PERSONAL 类型
    private final int count; // 用于 GLOBAL_LIMIT 类型
    private final long startTime; // 用于 TIMED 和 CYCLE 类型
    private final long endTime; // 用于 TIMED 类型
    private final long interval; // 用于 CYCLE 类型

    /**
     * 使用记录：playerUUID -> 使用时间戳列表。
     * <p>
     * 线程安全设计：
     * - 外层 ConcurrentHashMap：MC 主线程写入（addUsedBy），HTTP 线程 / 同步线程并发读取
     * - 内层 CopyOnWriteArrayList：单玩家多次使用（CYCLE/PERMANENT），写少读多场景
     * <p>
     * Gson 反序列化可能产生 HashMap + ArrayList，{@link #ensureConcurrentUsedBy()} 负责惰性转换。
     */
    private volatile Map<String, List<Long>> usedBy;

    public CodeData(String code, CodeType type, String reward, String player, int count, long startTime, long endTime, long interval) {
        this.code = code;
        this.type = type;
        this.reward = reward;
        this.player = player;
        this.count = count;
        this.startTime = startTime;
        this.endTime = endTime;
        this.interval = interval;
        this.usedBy = new ConcurrentHashMap<>();
    }

    public String getCode() {
        return code;
    }

    public CodeType getType() {
        return type;
    }

    public String getReward() {
        return reward;
    }

    public String getPlayer() {
        return player;
    }

    public int getCount() {
        return count;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public long getInterval() {
        return interval;
    }

    /**
     * 返回使用记录的不可变视图。线程安全：底层为 ConcurrentHashMap + CopyOnWriteArrayList。
     */
    public Map<String, List<Long>> getUsedBy() {
        return Collections.unmodifiableMap(ensureConcurrentUsedBy());
    }

    public void addUsedBy(String playerUUID, long timestamp) {
        ensureConcurrentUsedBy().computeIfAbsent(playerUUID, k -> new CopyOnWriteArrayList<>()).add(timestamp);
    }

    /**
     * 确保 usedBy 为线程安全的 ConcurrentHashMap（处理 Gson 反序列化产生的 HashMap 和 null 两种情况）。
     */
    private Map<String, List<Long>> ensureConcurrentUsedBy() {
        Map<String, List<Long>> map = usedBy;
        if (map instanceof ConcurrentHashMap) {
            return map;
        }
        synchronized (this) {
            map = usedBy;
            if (map == null) {
                map = new ConcurrentHashMap<>();
                usedBy = map;
            } else if (!(map instanceof ConcurrentHashMap)) {
                // Gson 反序列化产生 HashMap + ArrayList，转换为线程安全容器
                ConcurrentHashMap<String, List<Long>> concurrent = new ConcurrentHashMap<>();
                for (Map.Entry<String, List<Long>> entry : map.entrySet()) {
                    concurrent.put(entry.getKey(), new CopyOnWriteArrayList<>(entry.getValue()));
                }
                usedBy = concurrent;
                map = concurrent;
            }
        }
        return map;
    }
}
