package com.github.berrywang1996.netty.spring.web.context;

import java.util.Collections;
import java.util.Map;

/**
 * Runtime snapshot for websocket mappings exposed through the lightweight status endpoint.
 * Since V1.3.0, includes aggregated event counters from all resolver instances.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public final class WebSocketRuntimeStats {

    private static final WebSocketRuntimeStats EMPTY =
            new WebSocketRuntimeStats(0, 0, Collections.<String, Object>emptyMap());

    private final int mappingCount;

    private final int activeSessionCount;

    /**
     * Aggregated event counters from all WebSocket resolvers.
     * Typical keys: handshakeTotal, handshakeSuccess, messagesReceived,
     * messagesSent, totalCloses, closesByReason (Map&lt;String,Long&gt;).
     *
     * @since V1.3.0
     */
    private final Map<String, Object> eventCounters;

    /**
     * Creates a snapshot with the given mapping and session counts and no event counters.
     *
     * @param mappingCount       the number of registered WebSocket URL mappings
     * @param activeSessionCount the total number of currently active WebSocket sessions
     */
    public WebSocketRuntimeStats(int mappingCount, int activeSessionCount) {
        this(mappingCount, activeSessionCount, Collections.<String, Object>emptyMap());
    }

    /**
     * Creates a snapshot with the given mapping count, session count, and event counters.
     *
     * @param mappingCount       the number of registered WebSocket URL mappings
     * @param activeSessionCount the total number of currently active WebSocket sessions
     * @param eventCounters      aggregated event counters from all WebSocket resolvers (may be {@code null})
     */
    public WebSocketRuntimeStats(int mappingCount, int activeSessionCount,
                                 Map<String, Object> eventCounters) {
        this.mappingCount = mappingCount;
        this.activeSessionCount = activeSessionCount;
        this.eventCounters = eventCounters != null ? eventCounters : Collections.<String, Object>emptyMap();
    }

    /**
     * Returns the shared empty sentinel with zero mappings, zero sessions, and no event counters.
     *
     * @return the empty {@link WebSocketRuntimeStats} instance
     */
    public static WebSocketRuntimeStats empty() {
        return EMPTY;
    }

    /** @return the number of registered WebSocket URL mappings */
    public int getMappingCount() {
        return mappingCount;
    }

    /** @return the total number of currently active WebSocket sessions */
    public int getActiveSessionCount() {
        return activeSessionCount;
    }

    /**
     * Returns aggregated event counters. Empty map if no resolvers are active
     * or event recording is disabled.
     *
     * @return an unmodifiable map of counter names to values, or an empty map
     * @since V1.3.0
     */
    public Map<String, Object> getEventCounters() {
        return eventCounters;
    }

    @Override
    public String toString() {
        return "WebSocketRuntimeStats{" +
                "mappingCount=" + mappingCount +
                ", activeSessionCount=" + activeSessionCount +
                ", eventCounters=" + eventCounters +
                '}';
    }
}
