package com.juzizhen.config;

import com.juzizhen.RedemptionCodeFabric;
import com.juzizhen.util.Utils;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Path;
import java.util.Properties;

public class Config {

    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir();
    private static final File CONFIG_FILE = CONFIG_DIR.resolve(RedemptionCodeFabric.MOD_ID).resolve("redemptioncodefabric.properties").toFile();
    private static final Properties properties = new Properties();

    public Config() {
        load();
    }

    private void load() {
        try {
            if (!CONFIG_FILE.getParentFile().exists()) {
                RedemptionCodeFabric.LOGGER.info("Creating config directory: {}", CONFIG_FILE.getParentFile().mkdirs());
            }
            properties.clear();
            if (CONFIG_FILE.exists()) {
                try (FileInputStream fis = new FileInputStream(CONFIG_FILE)) {
                    properties.load(fis);
                }
            }
            if (ensureAllKeysExist()) {
                saveOrganized();
            }
        } catch (IOException e) {
            RedemptionCodeFabric.LOGGER.error("Could not load or create config file!", e);
        }
    }

    /**
     * 以分段注释格式保存配置文件（替代 Properties.store 的无序输出）。
     * <p>
     * 输出效果示例：
     * <pre>
     * # ============================================================
     * #  RedemptionCodeFabric Configuration
     * # ============================================================
     *
     * # ── Datastore ────────────────────────────────────────
     * # Storage backend: file / sql / redis
     * datastore.type=file
     * ...
     * </pre>
     */
    private void saveOrganized() throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(CONFIG_FILE))) {
            w.write("# ============================================================"); w.newLine();
            w.write("#  RedemptionCodeFabric Configuration"); w.newLine();
            w.write("# ============================================================"); w.newLine();

            // ── Datastore ──
            section(w, "Datastore", "Storage backend: file / sql / redis");
            prop(w, "datastore.type");

            // ── Logging ──
            section(w, "Logging");
            prop(w, "log.redemption.history");
            prop(w, "log.max.entries");

            // ── Web Server ──
            section(w, "Web Server",
                    "Enable or disable the web management panel",
                    "web.password is auto-generated on first run; find it in this file");
            prop(w, "web.enabled");
            prop(w, "web.url");
            prop(w, "web.port");
            prop(w, "web.user");
            prop(w, "web.password");
            prop(w, "web.sendUrlToOP");

            // ── SQL ──
            section(w, "SQL (MySQL)",
                    "sql.url is auto-built from host/port/database; override it for advanced setups");
            prop(w, "sql.host");
            prop(w, "sql.port");
            prop(w, "sql.user");
            prop(w, "sql.password");
            prop(w, "sql.database");
            prop(w, "sql.url");

            // ── Redis ──
            section(w, "Redis");
            prop(w, "redis.host");
            prop(w, "redis.port");
            prop(w, "redis.password");
            prop(w, "redis.database");

            // ── Connection Pool ──
            section(w, "Connection Pool (HikariCP-style)",
                    "All timeout values are in milliseconds",
                    "leakDetectionThreshold: 0 = disabled");
            prop(w, "pool.maxPoolSize");
            prop(w, "pool.minIdle");
            prop(w, "pool.connectionTimeout");
            prop(w, "pool.idleTimeout");
            prop(w, "pool.maxLifetime");
            prop(w, "pool.leakDetectionThreshold");
            prop(w, "pool.keepaliveTime");
            prop(w, "pool.validationTimeout");
            prop(w, "pool.connectionInitSql");

            w.newLine();
        }
    }

    private void section(BufferedWriter w, String title, String... comments) throws IOException {
        w.newLine();
        w.write("# ── " + title + " " + "─".repeat(Math.max(0, 48 - title.length())));
        w.newLine();
        for (String c : comments) {
            w.write("# " + c);
            w.newLine();
        }
    }

    private void prop(BufferedWriter w, String key) throws IOException {
        String value = properties.getProperty(key, "");
        w.write(key + "=" + value);
        w.newLine();
    }

    private boolean ensureAllKeysExist() {
        boolean updated = false;
        // The datastore.type from the old dataStoragePattern
        // 0 -> file, 1 -> sql, 2 -> redis
        String dataStoragePattern = properties.getProperty("dataStoragePattern");
        if (dataStoragePattern != null) {
            try {
                int pattern = Integer.parseInt(dataStoragePattern);
                if (pattern == 1) {
                    updated |= maybeSetDefault("datastore.type", "sql");
                } else if (pattern == 2) {
                    updated |= maybeSetDefault("datastore.type", "redis");
                } else {
                    updated |= maybeSetDefault("datastore.type", "file");
                }
            } catch (NumberFormatException e) {
                updated = maybeSetDefault("datastore.type", "file");
            }
        } else {
            updated |= maybeSetDefault("datastore.type", "file");
        }

        updated |= maybeSetDefault("log.redemption.history", "true");
        updated |= maybeSetDefault("log.max.entries", "20000");

        updated |= maybeSetDefault("web.enabled", "false");
        updated |= maybeSetDefault("web.url", "http://localhost");
        updated |= maybeSetDefault("web.port", "8080");
        updated |= maybeSetDefault("web.user", "admin");
        if (!properties.containsKey("web.password") || properties.getProperty("web.password").isEmpty()) {
            updated |= maybeSetDefault("web.password", Utils.generateRandomString(16));
        }
        updated |= maybeSetDefault("web.sendUrlToOP", "true");

        updated |= maybeSetDefault("sql.host", "localhost");
        updated |= maybeSetDefault("sql.port", "3306");
        updated |= maybeSetDefault("sql.user", "admin");
        updated |= maybeSetDefault("sql.password", "");
        updated |= maybeSetDefault("sql.database", "redemptioncode");

        String defaultSqlUrl = String.format("jdbc:mysql://%s:%s/%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
                properties.getProperty("sql.host", "localhost"),
                properties.getProperty("sql.port", "3306"),
                properties.getProperty("sql.database", "redemptioncode"));
        updated |= maybeSetDefault("sql.url", defaultSqlUrl);

        updated |= maybeSetDefault("redis.host", "localhost");
        updated |= maybeSetDefault("redis.port", "6379");
        updated |= maybeSetDefault("redis.password", "");
        updated |= maybeSetDefault("redis.database", "0");

        updated |= maybeSetDefault("pool.maxPoolSize", "10");
        updated |= maybeSetDefault("pool.minIdle", "2");
        updated |= maybeSetDefault("pool.connectionTimeout", "30000");
        updated |= maybeSetDefault("pool.idleTimeout", "600000");
        updated |= maybeSetDefault("pool.maxLifetime", "1800000");
        updated |= maybeSetDefault("pool.leakDetectionThreshold", "60000");
        updated |= maybeSetDefault("pool.keepaliveTime", "300000");
        updated |= maybeSetDefault("pool.validationTimeout", "5000");
        updated |= maybeSetDefault("pool.connectionInitSql", "");

        return updated;
    }

    private boolean maybeSetDefault(String key, String defaultValue) {
        if (!properties.containsKey(key)) {
            properties.setProperty(key, defaultValue);
            return true;
        }
        return false;
    }

    public String getDbHost() {
        return properties.getProperty("sql.host", "localhost");
    }

    public int getDbPort() {
        return getInt("sql.port", 3306);
    }

    public String getDbUser() {
        return properties.getProperty("sql.user", "admin");
    }

    public String getDbPassword() {
        return properties.getProperty("sql.password", "");
    }

    public String getDbName() {
        return properties.getProperty("sql.database", "redemptioncode");
    }

    public String getSqlUrl() {
        String url = properties.getProperty("sql.url");
        if (url != null && !url.isEmpty()) {
            return url;
        }
        return String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
                getDbHost(), getDbPort(), getDbName());
    }

    public String getRedisHost() {
        return properties.getProperty("redis.host", "localhost");
    }

    public int getRedisPort() {
        return getInt("redis.port", 6379);
    }

    public String getRedisPassword() {
        return properties.getProperty("redis.password", "");
    }

    public int getRedisDatabase() {
        return getInt("redis.database", 0);
    }

    public int getPort() {
        return getInt("web.port", 8080);
    }

    public static String getString(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public static int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            RedemptionCodeFabric.LOGGER.warn("Invalid number format for key '{}'. Using default value '{}'.", key, defaultValue);
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(properties.getProperty(key, String.valueOf(defaultValue)));
    }
}