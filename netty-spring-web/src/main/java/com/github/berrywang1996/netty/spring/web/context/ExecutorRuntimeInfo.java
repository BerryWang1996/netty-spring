package com.github.berrywang1996.netty.spring.web.context;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Snapshot of a thread pool at a point in time.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public final class ExecutorRuntimeInfo {

    private static final ExecutorRuntimeInfo EMPTY = new ExecutorRuntimeInfo(
            0,
            0,
            0,
            0,
            0,
            0,
            0L,
            0L,
            true);

    private final int corePoolSize;

    private final int maximumPoolSize;

    private final int poolSize;

    private final int activeCount;

    private final int queueSize;

    private final int queueRemainingCapacity;

    private final long taskCount;

    private final long completedTaskCount;

    private final boolean shutdown;

    public ExecutorRuntimeInfo(int corePoolSize,
                               int maximumPoolSize,
                               int poolSize,
                               int activeCount,
                               int queueSize,
                               int queueRemainingCapacity,
                               long taskCount,
                               long completedTaskCount,
                               boolean shutdown) {
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.poolSize = poolSize;
        this.activeCount = activeCount;
        this.queueSize = queueSize;
        this.queueRemainingCapacity = queueRemainingCapacity;
        this.taskCount = taskCount;
        this.completedTaskCount = completedTaskCount;
        this.shutdown = shutdown;
    }

    public static ExecutorRuntimeInfo from(ThreadPoolExecutor executor) {
        if (executor == null) {
            return empty();
        }
        return new ExecutorRuntimeInfo(
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                executor.getPoolSize(),
                executor.getActiveCount(),
                executor.getQueue().size(),
                executor.getQueue().remainingCapacity(),
                executor.getTaskCount(),
                executor.getCompletedTaskCount(),
                executor.isShutdown());
    }

    public static ExecutorRuntimeInfo empty() {
        return EMPTY;
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public int getActiveCount() {
        return activeCount;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public int getQueueRemainingCapacity() {
        return queueRemainingCapacity;
    }

    public long getTaskCount() {
        return taskCount;
    }

    public long getCompletedTaskCount() {
        return completedTaskCount;
    }

    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public String toString() {
        return "ExecutorRuntimeInfo{" +
                "corePoolSize=" + corePoolSize +
                ", maximumPoolSize=" + maximumPoolSize +
                ", poolSize=" + poolSize +
                ", activeCount=" + activeCount +
                ", queueSize=" + queueSize +
                ", queueRemainingCapacity=" + queueRemainingCapacity +
                ", taskCount=" + taskCount +
                ", completedTaskCount=" + completedTaskCount +
                ", shutdown=" + shutdown +
                '}';
    }

}
