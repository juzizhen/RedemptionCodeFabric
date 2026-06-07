package com.juzizhen.config;

public class ModConfigData {
    public boolean logRedemptionHistory = true;
    public int maxLogEntries = 20000;
    public String adminPassword = "";
    // 数据存储模式 0.文件模式 1.SQL模式 2.Redis模式
    public int dataStoragePattern = 0;
}