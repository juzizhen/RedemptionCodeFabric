package com.juzizhen.rcode.repository;

import com.juzizhen.rcode.model.CodeData;
import com.juzizhen.rcode.model.UsageData;

import java.util.List;
import java.util.Map;

/**
 * An interface that defines the contract for data storage operations.
 * This allows for different storage implementations (e.g., file, SQL, Redis)
 * without changing the core business logic.
 */
public interface IDataRepository {

    /**
     * Loads all redemption codes from the data source.
     *
     * @return A map of all redemption codes.
     */
    Map<String, CodeData> loadAllCodes();

    /**
     * Saves all redemption codes to the data source.
     *
     * @param codes The map of codes to save.
     */
    void saveAllCodes(Map<String, CodeData> codes);

    /**
     * Loads the entire usage history from the data source.
     *
     * @return A list of all usage data entries.
     */
    List<UsageData> loadAllUsageData();

    /**
     * Saves the entire usage history to the data source.
     *
     * @param usageHistory The list of usage data to save.
     */
    void saveAllUsageData(List<UsageData> usageHistory);
}
