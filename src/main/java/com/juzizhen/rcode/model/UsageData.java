package com.juzizhen.rcode.model;

public class UsageData {
    private final String playerUUID;
    private final String code;
    private final long timestamp;

    public UsageData(String playerUUID, String code, long timestamp) {
        this.playerUUID = playerUUID;
        this.code = code;
        this.timestamp = timestamp;
    }

    // Getters
    public String getPlayerUUID() {
        return playerUUID;
    }

    public String getCode() {
        return code;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
