package com.juzizhen.redemptioncodefabric.rcode.repository;

import com.juzizhen.redemptioncodefabric.rcode.model.CodeData;
import com.juzizhen.redemptioncodefabric.rcode.model.OperationLogEntry;

import java.util.List;
import java.util.Map;

/**
 * 数据存储操作的契约接口，便于在不改动核心业务逻辑的前提下切换不同的存储实现（文件、SQL、Redis）。
 */
public interface IDataRepository {

    Map<String, CodeData> loadAllCodes();

    void saveAllCodes(Map<String, CodeData> codes);

    void saveCode(CodeData codeData);

    void removeCode(String code);

    void appendOperationLog(OperationLogEntry logEntry);

    /**
     * 获取操作日志，按时间倒序（最新在前）。
     *
     * @param offset 跳过的条目数
     * @param limit  返回的最大条目数
     */
    List<OperationLogEntry> getOperationLog(int offset, int limit);
}
