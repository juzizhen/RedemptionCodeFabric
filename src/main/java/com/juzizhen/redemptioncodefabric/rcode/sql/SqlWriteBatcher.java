package com.juzizhen.redemptioncodefabric.rcode.sql;

import com.google.gson.Gson;
import com.juzizhen.redemptioncodefabric.rcode.model.OperationLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SQL 写入批处理器（Write-Behind Batch）。
 * <p>
 * 将高频的单条写入（saveCode / removeCode / appendOperationLog）缓存到内存队列，
 * 按配置的时间间隔或数量阈值合并为一次事务提交，大幅降低数据库 I/O 压力。
 * <p>
 * 核心机制：
 * <ul>
 *     <li><b>去重合并</b>：同一兑换码在窗口期内多次保存仅持久化最终状态</li>
 *     <li><b>单事务提交</b>：UPSERT（保存）+ DELETE（删除）+ INSERT（日志）在同一事务内完成</li>
 *     <li><b>非阻塞入队</b>：调用方（MC 主线程）入队后立即返回，不再等待 SQL 完成</li>
 *     <li><b>关服安全</b>：{@link #shutdown()} 先停止定时器再执行最终刷新，确保队列排空</li>
 * </ul>
 * <p>
 * 配置项（redemptioncodefabric.properties）：
 * <pre>
 * pool.batchInterval=5000   # 定时刷新间隔（毫秒）
 * pool.batchMaxSize=50      # 队列达到此数量时立即触发刷新
 * </pre>
 */
public class SqlWriteBatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("RedemptionCodeFabric-SQLBatch");
    private static final Gson GSON = new Gson();

    private final SimpleConnectionPool pool;
    private final ConcurrentLinkedQueue<Op> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler;
    private final int batchMaxSize;
    /** L3: volatile 关闭标志，triggerFlushIfFull 检查此标志避免向已停止的 scheduler 提交任务 */
    private volatile boolean shutdown = false;

    /**
     * @param pool           SQL 连接池
     * @param intervalMs     定时刷新间隔（毫秒）
     * @param batchMaxSize   队列达到此数量时立即触发刷新
     */
    public SqlWriteBatcher(SimpleConnectionPool pool, long intervalMs, int batchMaxSize) {
        this.pool = pool;
        this.batchMaxSize = batchMaxSize;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RCF-SQL-Batch");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleWithFixedDelay(this::flush, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        LOGGER.info("SQL write batcher started: interval={}ms, maxSize={}", intervalMs, batchMaxSize);
    }

    // ==================== 入队（非阻塞，MC 主线程安全） ====================

    /** 入队一条操作日志。 */
    public void enqueueLog(OperationLogEntry entry) {
        queue.offer(new Op(entry, 0));
        triggerFlushIfFull();
    }

    private void triggerFlushIfFull() {
        // L3: shutdown 后不再提交新任务，避免 RejectedExecutionException
        if (!shutdown && queue.size() >= batchMaxSize) {
            scheduler.execute(this::flush);
        }
    }

    // ==================== 刷新（批处理线程） ====================

    /** H7: 单条日志最大重试次数，超过后丢弃并告警 */
    private static final int MAX_RETRIES = 3;

    /**
     * 将队列中的全部待写操作日志合并为一次事务提交。
     * H7: 失败时将未超重试上限的条目重新入队，下次周期再试。
     */
    public synchronized void flush() {
        if (queue.isEmpty()) return;

        // 1. 排空队列
        List<Op> ops = new ArrayList<>();
        Op op;
        while ((op = queue.poll()) != null) {
            ops.add(op);
        }
        if (ops.isEmpty()) return;

        List<OperationLogEntry> logs = new ArrayList<>(ops.size());
        for (Op o : ops) {
            logs.add(o.logEntry);
        }

        // 2. 单事务批量 INSERT
        Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement logStmt = conn.prepareStatement(
                    "INSERT INTO operation_logs (timestamp, operation_type, executor, details) VALUES (?, ?, ?, ?)")) {

                for (OperationLogEntry entry : logs) {
                    logStmt.setLong(1, entry.getTimestamp());
                    logStmt.setString(2, entry.getOperationType());
                    logStmt.setString(3, entry.getExecutor());
                    logStmt.setString(4, GSON.toJson(entry.getDetails()));
                    logStmt.addBatch();
                }

                logStmt.executeBatch();
            }

            conn.commit();
            LOGGER.debug("Batch flush: {} logs", logs.size());

        } catch (Exception e) {
            LOGGER.error("Batch flush failed ({} logs)", logs.size(), e);
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (Exception ignored) {
                }
            }
            // H7: 重新入队未超重试上限的条目
            int requeued = 0, dropped = 0;
            for (Op o : ops) {
                if (o.retryCount < MAX_RETRIES) {
                    queue.offer(new Op(o.logEntry, o.retryCount + 1));
                    requeued++;
                } else {
                    dropped++;
                }
            }
            if (dropped > 0) {
                LOGGER.warn("Dropped {} operation log entries after {} retries", dropped, MAX_RETRIES);
            }
            if (requeued > 0) {
                LOGGER.info("Re-enqueued {} operation log entries for retry", requeued);
            }
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                } catch (Exception ignored) {
                }
                pool.releaseConnection(conn);
            }
        }
    }

    // ==================== 生命周期 ====================

    /**
     * 停止定时器并刷新剩余操作。由 SqlManager.shutdown() 在关闭连接池之前调用。
     */
    public void shutdown() {
        shutdown = true;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        flush();
        LOGGER.info("SQL write batcher shut down.");
    }

    public int pendingCount() {
        return queue.size();
    }

    // ==================== 内部结构 ====================

    /**
     * 队列条目：包装一条操作日志及其重试计数。
     */
    private static final class Op {
        final OperationLogEntry logEntry;
        final int retryCount;

        Op(OperationLogEntry logEntry, int retryCount) {
            this.logEntry = logEntry;
            this.retryCount = retryCount;
        }
    }
}
