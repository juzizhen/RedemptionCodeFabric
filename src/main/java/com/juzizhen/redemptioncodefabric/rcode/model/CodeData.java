package com.juzizhen.redemptioncodefabric.rcode.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CodeData {
    private final String code;
    private final CodeType type;
    private final String reward;
    private final String player; // For PERSONAL codes
    private final int count; // For GLOBAL_LIMIT codes
    private final long startTime; // For TIMED and CYCLE codes
    private final long endTime; // For TIMED codes
    private final long interval; // For CYCLE codes
    private Map<String, List<Long>> usedBy; // To track who has used the code and when

    public CodeData(String code, CodeType type, String reward, String player, int count, long startTime, long endTime, long interval) {
        this.code = code;
        this.type = type;
        this.reward = reward;
        this.player = player;
        this.count = count;
        this.startTime = startTime;
        this.endTime = endTime;
        this.interval = interval;
        this.usedBy = new HashMap<>(); // Initialize as HashMap
    }

    // Getters and setters
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
        // Ensure it's never null, especially after deserialization
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
