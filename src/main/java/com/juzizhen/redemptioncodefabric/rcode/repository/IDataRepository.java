package com.juzizhen.redemptioncodefabric.rcode.repository;

import com.juzizhen.redemptioncodefabric.rcode.model.CodeData;
import com.juzizhen.redemptioncodefabric.rcode.model.OperationLogEntry;

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
     * This is intended for full persistence, e.g., on server shutdown.
     *
     * @param codes The map of codes to save.
     */
    void saveAllCodes(Map<String, CodeData> codes);

    /**
     * Saves or updates a single redemption code.
     *
     * @param codeData The code data to save.
     */
    void saveCode(CodeData codeData);

    /**
     * Deletes a single redemption code from the data source.
     *
     * @param code The code to delete.
     */
    void removeCode(String code);

    /**
     * Appends a new operation log entry to the log.
     *
     * @param logEntry The log entry to append.
     */
    void appendOperationLog(OperationLogEntry logEntry);

    /**
     * Retrieves operation log entries, newest first.
     *
     * @param offset number of entries to skip
     * @param limit  max number of entries to return
     * @return list of log entries
     */
    List<OperationLogEntry> getOperationLog(int offset, int limit);
}