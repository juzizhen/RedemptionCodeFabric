package com.juzizhen.config;

import com.juzizhen.RedemptionCodeFabric;
import com.juzizhen.util.Utils;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
            // Load existing properties first
            if (CONFIG_FILE.exists()) {
                try (FileInputStream fis = new FileInputStream(CONFIG_FILE)) {
                    properties.load(fis);
                }
            }
            // Check for missing keys and save if any were added
            if (ensureAllKeysExist()) {
                save("Added missing default config entries");
            }
        } catch (IOException e) {
            RedemptionCodeFabric.LOGGER.error("Could not load or create config file!", e);
        }
    }

    private void save(String comments) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            properties.store(fos, comments);
        }
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

        updated |= maybeSetDefault("sql.host", "localhost"); // Corrected default
        updated |= maybeSetDefault("sql.port", "3306");
        updated |= maybeSetDefault("sql.user", "admin");
        updated |= maybeSetDefault("sql.password", "");
        updated |= maybeSetDefault("sql.database", "redemptioncode"); // Added database name

        updated |= maybeSetDefault("redis.host", "localhost");
        updated |= maybeSetDefault("redis.port", "6379");
        updated |= maybeSetDefault("redis.password", "");
        updated |= maybeSetDefault("redis.database", "0");

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