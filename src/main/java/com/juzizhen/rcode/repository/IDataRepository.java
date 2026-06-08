package com.juzizhen.rcode.repository;

import com.juzizhen.rcode.model.CodeData;
import com.juzizhen.rcode.model.OperationLogEntry;

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
     * Appends a new operation log entry to the log file.
     *
     * @param logEntry The log entry to append.
     */
    void appendOperationLog(OperationLogEntry logEntry);
}
