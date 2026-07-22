package com.juzizhen.redemptioncodefabric.rcode.model;

import java.util.Map;

public class OperationLogEntry {
    private final long timestamp;
    private final String operationType;
    private final String executor;
    private final Map<String, String> details; // Changed from String to Map<String, String>

    public OperationLogEntry(long timestamp, String operationType, String executor, Map<String, String> details) {
        this.timestamp = timestamp;
        this.operationType = operationType;
        this.executor = executor;
        this.details = details;
    }

    // Getters are needed for Gson to serialize the object
    public long getTimestamp() {
        return timestamp;
    }

    public String getOperationType() {
        return operationType;
    }

    public String getExecutor() {
        return executor;
    }

    public Map<String, String> getDetails() {
        return details;
    }
}
