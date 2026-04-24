package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.context.ExecutorRuntimeInfo;

/**
 * Runtime snapshot for websocket message sending.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public final class MessageSenderRuntimeStats {

    private static final MessageSenderRuntimeStats EMPTY = new MessageSenderRuntimeStats(
            ExecutorRuntimeInfo.empty(),
            0L,
            0L,
            0L,
            0L,
            0L,
            0L);

    private final ExecutorRuntimeInfo executor;

    private final long rejectedBroadcastCount;

    private final long callerRunsFallbackCount;

    private final long droppedBroadcastCount;

    private final long nonWritableSkipCount;

    private final long nonWritableCloseCount;

    private final long writeFailureCount;

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

    public static MessageSenderRuntimeStats empty() {
        return EMPTY;
    }

    public ExecutorRuntimeInfo getExecutor() {
        return executor;
    }

    public long getRejectedBroadcastCount() {
        return rejectedBroadcastCount;
    }

    public long getCallerRunsFallbackCount() {
        return callerRunsFallbackCount;
    }

    public long getDroppedBroadcastCount() {
        return droppedBroadcastCount;
    }

    public long getNonWritableSkipCount() {
        return nonWritableSkipCount;
    }

    public long getNonWritableCloseCount() {
        return nonWritableCloseCount;
    }

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
