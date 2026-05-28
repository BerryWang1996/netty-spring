package com.github.berrywang1996.netty.spring.web.context;

/**
 * Immutable runtime snapshot of the shared handler execution model, including
 * thread pool state and semaphore-based admission control metrics.
 *
 * <p>Combines an {@link ExecutorRuntimeInfo} snapshot with permit limit, available permits,
 * and rejection counters. This is exposed through the management status endpoint to help
 * operators diagnose capacity issues and tune thread pool / permit settings.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public final class HandlerRuntimeStats {

    /** Singleton empty snapshot used when no handler execution model is active. */
    private static final HandlerRuntimeStats EMPTY = new HandlerRuntimeStats(
            ExecutorRuntimeInfo.empty(),
            0,
            0,
            0L,
            0L);

    private final ExecutorRuntimeInfo executor;

    private final int permitLimit;

    private final int availablePermits;

    private final long permitRejectedCount;

    private final long executorRejectedCount;

    /**
     * Creates a new handler runtime snapshot.
     *
     * @param executor              snapshot of the handler thread pool
     * @param permitLimit           the configured maximum number of semaphore permits
     * @param availablePermits      the current number of available semaphore permits
     * @param permitRejectedCount   cumulative count of tasks rejected due to no available permits
     * @param executorRejectedCount cumulative count of tasks rejected by the thread pool executor
     */
    public HandlerRuntimeStats(ExecutorRuntimeInfo executor,
                               int permitLimit,
                               int availablePermits,
                               long permitRejectedCount,
                               long executorRejectedCount) {
        this.executor = executor;
        this.permitLimit = permitLimit;
        this.availablePermits = availablePermits;
        this.permitRejectedCount = permitRejectedCount;
        this.executorRejectedCount = executorRejectedCount;
    }

    /**
     * Returns the shared empty sentinel used when no handler execution model is active.
     *
     * @return the empty {@link HandlerRuntimeStats} instance
     */
    public static HandlerRuntimeStats empty() {
        return EMPTY;
    }

    /** @return the thread pool executor snapshot */
    public ExecutorRuntimeInfo getExecutor() {
        return executor;
    }

    /** @return the configured maximum number of semaphore permits */
    public int getPermitLimit() {
        return permitLimit;
    }

    /** @return the current number of available semaphore permits */
    public int getAvailablePermits() {
        return availablePermits;
    }

    /** @return cumulative count of tasks rejected due to no available permits */
    public long getPermitRejectedCount() {
        return permitRejectedCount;
    }

    /** @return cumulative count of tasks rejected by the thread pool executor */
    public long getExecutorRejectedCount() {
        return executorRejectedCount;
    }

    /**
     * Returns the total number of rejected tasks (permit + executor rejections combined).
     *
     * @return the sum of permit and executor rejected counts
     */
    public long getRejectedCount() {
        return permitRejectedCount + executorRejectedCount;
    }

    @Override
    public String toString() {
        return "HandlerRuntimeStats{" +
                "executor=" + executor +
                ", permitLimit=" + permitLimit +
                ", availablePermits=" + availablePermits +
                ", permitRejectedCount=" + permitRejectedCount +
                ", executorRejectedCount=" + executorRejectedCount +
                '}';
    }

}
