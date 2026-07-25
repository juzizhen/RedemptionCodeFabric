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
 * SQL 写入批处理器（Write-Behind Batch）：将高频单条写入缓存到内存队列，
 * 按时间间隔或数量阈值合并为一次事务提交，降低数据库 I/O 压力。
 * 调用方（MC 主线程）入队后立即返回；{@link #shutdown()} 先停止定时器再做最终刷新以确保队列排空。
 */
public class SqlWriteBatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("RedemptionCodeFabric-SQLBatch");
    private static final Gson GSON = new Gson();

    private final SimpleConnectionPool pool;
    private final ConcurrentLinkedQueue<Op> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler;
    private final int batchMaxSize;
    /** volatile 关闭标志，triggerFlushIfFull 检查此标志避免向已停止的 scheduler 提交任务 */
    private volatile boolean shutdown = false;

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

    /** 入队一条操作日志。 */
    public void enqueueLog(OperationLogEntry entry) {
        queue.offer(new Op(entry, 0));
        triggerFlushIfFull();
    }

    private void triggerFlushIfFull() {
        // shutdown 后不再提交新任务，避免 RejectedExecutionException
        if (!shutdown && queue.size() >= batchMaxSize) {
            scheduler.execute(this::flush);
        }
    }

    /** 单条日志最大重试次数，超过后丢弃并告警 */
    private static final int MAX_RETRIES = 3;

    /**
     * 将队列中的全部待写操作日志合并为一次事务提交；失败时将未超重试上限的条目重新入队，下次周期再试。
     */
    public synchronized void flush() {
        if (queue.isEmpty()) return;

        List<Op> ops = new ArrayList<>();
        Op op;
        while ((op = queue.poll()) != null) {
            ops.add(op);
        }
        if (ops.isEmpty()) return;

        List<OperationLogEntry> logs = new ArrayList<>(ops.size());
        for (Op o : ops) {
            logs.add(o.logEntry());
        }

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
            // 重新入队未超重试上限的条目
            int requeued = 0, dropped = 0;
            for (Op o : ops) {
                if (o.retryCount() < MAX_RETRIES) {
                    queue.offer(new Op(o.logEntry(), o.retryCount() + 1));
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

    /**
     * 队列条目：包装一条操作日志及其重试计数。
     */
    private record Op(OperationLogEntry logEntry, int retryCount) {
    }
}
