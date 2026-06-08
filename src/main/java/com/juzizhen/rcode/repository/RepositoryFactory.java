package com.juzizhen.rcode.repository;

import com.juzizhen.RedemptionCodeFabric;
import com.juzizhen.config.Config;

public class RepositoryFactory {

    public static IDataRepository create(Config config) {
        String repositoryType = Config.getString("datastore.type", "file");
        RedemptionCodeFabric.LOGGER.info("Using data store type: {}", repositoryType);

        return switch (repositoryType.toLowerCase()) {
            case "redis" -> {
                // return new RedisRepository(config); // To be implemented
                RedemptionCodeFabric.LOGGER.warn("Redis repository is not yet implemented, falling back to file repository.");
                yield new FileRepository();
            }
            case "sql" -> {
                // return new SqlRepository(config); // To be implemented
                RedemptionCodeFabric.LOGGER.warn("SQL repository is not yet implemented, falling back to file repository.");
                yield new FileRepository();
            }
            default -> new FileRepository();
        };
    }
}