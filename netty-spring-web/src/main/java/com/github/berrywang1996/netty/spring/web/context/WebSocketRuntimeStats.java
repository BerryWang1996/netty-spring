package com.github.berrywang1996.netty.spring.web.context;

/**
 * Runtime snapshot for websocket mappings exposed through the lightweight status endpoint.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public final class WebSocketRuntimeStats {

    private static final WebSocketRuntimeStats EMPTY = new WebSocketRuntimeStats(0, 0);

    private final int mappingCount;

    private final int activeSessionCount;

    public WebSocketRuntimeStats(int mappingCount, int activeSessionCount) {
        this.mappingCount = mappingCount;
        this.activeSessionCount = activeSessionCount;
    }

    public static WebSocketRuntimeStats empty() {
        return EMPTY;
    }

    public int getMappingCount() {
        return mappingCount;
    }

    public int getActiveSessionCount() {
        return activeSessionCount;
    }

    @Override
    public String toString() {
        return "WebSocketRuntimeStats{" +
                "mappingCount=" + mappingCount +
                ", activeSessionCount=" + activeSessionCount +
                '}';
    }
}
