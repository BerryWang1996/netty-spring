package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.context.ExecutorRuntimeInfo;

/**
 * Immutable runtime snapshot of the {@link DefaultMessageSender}'s operational statistics.
 *
 * <p>Captures a point-in-time view of the broadcast thread pool state and cumulative
 * counters for error conditions such as rejected tasks, dropped messages, non-writable
 * channels, and write failures. Useful for diagnostics, monitoring, and log output.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public final class MessageSenderRuntimeStats {

    /** Singleton empty stats instance returned when no sender is active. */
    private static final MessageSenderRuntimeStats EMPTY = new MessageSenderRuntimeStats(
            ExecutorRuntimeInfo.empty(),
            0L,
            0L,
            0L,
            0L,
            0L,
            0L);

    /** Snapshot of the broadcast thread pool's runtime state. */
    private final ExecutorRuntimeInfo executor;

    /** Total number of broadcast tasks rejected by the thread pool. */
    private final long rejectedBroadcastCount;

    /** Number of rejected tasks that fell back to the caller thread. */
    private final long callerRunsFallbackCount;

    /** Number of rejected tasks that were dropped entirely. */
    private final long droppedBroadcastCount;

    /** Number of broadcasts skipped because the channel was not writable. */
    private final long nonWritableSkipCount;

    /** Number of sessions force-closed because the channel was not writable during broadcast. */
    private final long nonWritableCloseCount;

    /** Total number of write failures that led to session closure. */
    private final long writeFailureCount;

    /**
     * Creates a new runtime stats snapshot.
     *
     * @param executor                 thread pool runtime info
     * @param rejectedBroadcastCount   total rejected broadcast tasks
     * @param callerRunsFallbackCount  rejected tasks executed on the caller thread
     * @param droppedBroadcastCount    rejected tasks that were dropped
     * @param nonWritableSkipCount     broadcasts skipped due to non-writable channels
     * @param nonWritableCloseCount    sessions closed due to non-writable channels
     * @param writeFailureCount        total write failures
     */
    public MessageSenderRuntimeStats(ExecutorRuntimeInfo executor,
                                     long rejectedBroadcastCount,
                                     long callerRunsFallbackCount,
                                     long droppedBroadcastCount,
                                     long nonWritableSkipCount,
                                     long nonWritableCloseCount,
                                     long writeFailureCount) {
        this.executor = executor;
        this.rejectedBroadcastCount = rejectedBroadcastCount;
        this.callerRunsFallbackCount = callerRunsFallbackCount;
        this.droppedBroadcastCount = droppedBroadcastCount;
        this.nonWritableSkipCount = nonWritableSkipCount;
        this.nonWritableCloseCount = nonWritableCloseCount;
        this.writeFailureCount = writeFailureCount;
    }

    /**
     * Returns a singleton instance with all counters at zero.
     *
     * @return the empty stats instance
     */
    public static MessageSenderRuntimeStats empty() {
        return EMPTY;
    }

    /** @return the broadcast thread pool runtime info */
    public ExecutorRuntimeInfo getExecutor() {
        return executor;
    }

    /** @return total number of broadcast tasks rejected by the thread pool */
    public long getRejectedBroadcastCount() {
        return rejectedBroadcastCount;
    }

    /** @return number of rejected tasks that fell back to the caller thread */
    public long getCallerRunsFallbackCount() {
        return callerRunsFallbackCount;
    }

    /** @return number of rejected tasks that were dropped entirely */
    public long getDroppedBroadcastCount() {
        return droppedBroadcastCount;
    }

    /** @return broadcasts skipped due to non-writable channels */
    public long getNonWritableSkipCount() {
        return nonWritableSkipCount;
    }

    /** @return sessions force-closed due to non-writable channels during broadcast */
    public long getNonWritableCloseCount() {
        return nonWritableCloseCount;
    }

    /** @return total write failures that led to session closure */
    public long getWriteFailureCount() {
        return writeFailureCount;
    }

    @Override
    public String toString() {
        return "MessageSenderRuntimeStats{" +
                "executor=" + executor +
                ", rejectedBroadcastCount=" + rejectedBroadcastCount +
                ", callerRunsFallbackCount=" + callerRunsFallbackCount +
                ", droppedBroadcastCount=" + droppedBroadcastCount +
                ", nonWritableSkipCount=" + nonWritableSkipCount +
                ", nonWritableCloseCount=" + nonWritableCloseCount +
                ", writeFailureCount=" + writeFailureCount +
                '}';
    }

}
