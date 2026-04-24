package com.github.berrywang1996.netty.spring.web.context;

/**
 * Runtime snapshot for the shared handler execution model.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public final class HandlerRuntimeStats {

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

    public static HandlerRuntimeStats empty() {
        return EMPTY;
    }

    public ExecutorRuntimeInfo getExecutor() {
        return executor;
    }

    public int getPermitLimit() {
        return permitLimit;
    }

    public int getAvailablePermits() {
        return availablePermits;
    }

    public long getPermitRejectedCount() {
        return permitRejectedCount;
    }

    public long getExecutorRejectedCount() {
        return executorRejectedCount;
    }

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
