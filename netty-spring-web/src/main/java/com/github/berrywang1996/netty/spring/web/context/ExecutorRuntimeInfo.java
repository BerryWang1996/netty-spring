package com.github.berrywang1996.netty.spring.web.context;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Immutable point-in-time snapshot of a {@link ThreadPoolExecutor}'s runtime state.
 *
 * <p>Captures key metrics such as pool sizes, active thread count, queue depth,
 * and cumulative task counts. Used by the management status endpoint and
 * {@link HandlerRuntimeStats} to expose handler thread pool health.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public final class ExecutorRuntimeInfo {

    /** Singleton empty snapshot representing a shutdown or absent executor. */
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

    /**
     * Creates a new executor runtime snapshot with the given metrics.
     *
     * @param corePoolSize           the core number of threads configured in the pool
     * @param maximumPoolSize        the maximum number of threads allowed in the pool
     * @param poolSize               the current number of threads in the pool
     * @param activeCount            the approximate number of threads actively executing tasks
     * @param queueSize              the number of tasks currently waiting in the work queue
     * @param queueRemainingCapacity the remaining capacity of the work queue
     * @param taskCount              the approximate total number of tasks ever scheduled
     * @param completedTaskCount     the approximate total number of tasks that have completed
     * @param shutdown               {@code true} if the executor has been shut down
     */
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

    /**
     * Creates a snapshot from a live {@link ThreadPoolExecutor}. Returns the
     * {@link #empty()} sentinel if the executor is {@code null}.
     *
     * @param executor the thread pool executor to snapshot (may be {@code null})
     * @return a new snapshot, or the empty sentinel
     */
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

    /**
     * Returns the shared empty sentinel representing a shutdown or absent executor.
     *
     * @return the empty {@link ExecutorRuntimeInfo} instance
     */
    public static ExecutorRuntimeInfo empty() {
        return EMPTY;
    }

    /** @return the configured core pool size */
    public int getCorePoolSize() {
        return corePoolSize;
    }

    /** @return the configured maximum pool size */
    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    /** @return the current number of threads in the pool */
    public int getPoolSize() {
        return poolSize;
    }

    /** @return the approximate number of threads actively executing tasks */
    public int getActiveCount() {
        return activeCount;
    }

    /** @return the number of tasks currently waiting in the work queue */
    public int getQueueSize() {
        return queueSize;
    }

    /** @return the remaining capacity of the work queue */
    public int getQueueRemainingCapacity() {
        return queueRemainingCapacity;
    }

    /** @return the approximate total number of tasks ever scheduled */
    public long getTaskCount() {
        return taskCount;
    }

    /** @return the approximate total number of tasks that have completed execution */
    public long getCompletedTaskCount() {
        return completedTaskCount;
    }

    /** @return {@code true} if the executor has been shut down */
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
