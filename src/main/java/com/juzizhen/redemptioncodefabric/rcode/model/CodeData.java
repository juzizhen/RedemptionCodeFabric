package com.juzizhen.redemptioncodefabric.rcode.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CodeData {
    private final String code;
    private final CodeType type;
    private final String reward;
    private final String player; // 用于 PERSONAL 类型
    private final int count; // 用于 GLOBAL_LIMIT 类型
    private final long startTime; // 用于 TIMED 和 CYCLE 类型
    private final long endTime; // 用于 TIMED 类型
    private final long interval; // 用于 CYCLE 类型
    private Map<String, List<Long>> usedBy; // 记录谁在何时使用了该码

    public CodeData(String code, CodeType type, String reward, String player, int count, long startTime, long endTime, long interval) {
        this.code = code;
        this.type = type;
        this.reward = reward;
        this.player = player;
        this.count = count;
        this.startTime = startTime;
        this.endTime = endTime;
        this.interval = interval;
        this.usedBy = new HashMap<>();
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

    public Map<String, List<Long>> getUsedBy() {
        // 确保非 null，尤其是在反序列化之后
        if (usedBy == null) {
            usedBy = new HashMap<>();
        }
        return usedBy;
    }

    public void addUsedBy(String playerUUID, long timestamp) {
        if (this.usedBy == null) {
            this.usedBy = new HashMap<>();
        }
        this.usedBy.computeIfAbsent(playerUUID, k -> new ArrayList<>()).add(timestamp);
    }
}
