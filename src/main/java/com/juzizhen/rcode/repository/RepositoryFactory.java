package com.juzizhen.rcode.repository;

import com.juzizhen.RedemptionCodeFabric;
import com.juzizhen.config.Config;
import com.juzizhen.rcode.redis.RedisManager;
import com.juzizhen.rcode.redis.RedisRepository;
import com.juzizhen.rcode.sql.SqlManager;

public class RepositoryFactory {

    public static IDataRepository create() {
        String repositoryType = Config.getString("datastore.type", "file");
        RedemptionCodeFabric.LOGGER.info("Using data store type: {}", repositoryType);

        return switch (repositoryType.toLowerCase()) {
            case "redis" -> {
                if (RedisManager.getInstance().isConnected()) {
                    RedemptionCodeFabric.LOGGER.info("Using Redis repository.");
                    yield new RedisRepository();
                } else {
                    RedemptionCodeFabric.LOGGER.warn("Redis connection not available, falling back to file repository.");
                    yield new FileRepository();
                }
            }
            case "sql" -> {
                if (SqlManager.getInstance().isConnected()) {
                    RedemptionCodeFabric.LOGGER.info("Using SQL repository.");
                    yield new SqlRepository(SqlManager.getInstance());
                } else {
                    RedemptionCodeFabric.LOGGER.warn("SQL connection not available, falling back to file repository.");
                    yield new FileRepository();
                }
            }
            default -> new FileRepository();
        };
    }
}
