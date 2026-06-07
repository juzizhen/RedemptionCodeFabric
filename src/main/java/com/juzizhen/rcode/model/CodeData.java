package com.juzizhen.rcode.model;

import java.util.ArrayList;
import java.util.List;

public class CodeData {
    private final String code;
    private final CodeType type;
    private final String reward;
    private final String player; // For PERSONAL codes
    private int count; // For GLOBAL_LIMIT codes
    private final long startTime; // For TIMED and CYCLE codes
    private final long endTime; // For TIMED codes
    private final long interval; // For CYCLE codes
    private List<String> usedBy; // To track who has used the code

    public CodeData(String code, CodeType type, String reward, String player, int count, long startTime, long endTime, long interval) {
        this.code = code;
        this.type = type;
        this.reward = reward;
        this.player = player;
        this.count = count;
        this.startTime = startTime;
        this.endTime = endTime;
        this.interval = interval;
        this.usedBy = new ArrayList<>();
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

    public void setCount(int count) {
        this.count = count;
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

    public List<String> getUsedBy() {
        return usedBy;
    }

    public void addUsedBy(String playerUUID) {
        if (this.usedBy == null) {
            this.usedBy = new ArrayList<>();
        }
        this.usedBy.add(playerUUID);
    }
}
