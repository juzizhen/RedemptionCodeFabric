package com.juzizhen.redemptioncodefabric.rcode.model;

import java.util.Map;

public class OperationLogEntry {
    private final long timestamp;
    private final String operationType;
    private final String executor;
    private final Map<String, String> details;

    public OperationLogEntry(long timestamp, String operationType, String executor, Map<String, String> details) {
        this.timestamp = timestamp;
        this.operationType = operationType;
        this.executor = executor;
        this.details = details;
    }

    // Gson 序列化需要 getter 方法
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
