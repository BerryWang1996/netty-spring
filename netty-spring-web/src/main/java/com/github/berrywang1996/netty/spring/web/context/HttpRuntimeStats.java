package com.github.berrywang1996.netty.spring.web.context;

/**
 * Immutable runtime snapshot of HTTP and static-file failure and close event counters.
 *
 * <p>Captures cumulative counts for response write failures, static file rejections,
 * idle channel closures, and WebSocket handshake/origin rejections. Produced by
 * {@link HttpRuntimeRecorder#getRuntimeStats()} and exposed through the management
 * status endpoint.
 *
 * @author berrywang1996
 * @since V1.0.0
 * @see HttpRuntimeRecorder
 */
public final class HttpRuntimeStats {

    /** Singleton empty snapshot with all counters at zero. */
    private static final HttpRuntimeStats EMPTY = new HttpRuntimeStats(0L, 0L, 0L, 0L, 0L, 0L);

    private final long httpResponseWriteFailureCount;

    private final long staticFileRejectedCount;

    private final long staticFileWriteFailureCount;

    private final long idleCloseCount;

    private final long webSocketHandshakeRejectedCount;

    private final long webSocketOriginRejectedCount;

    /**
     * Creates a new HTTP runtime stats snapshot.
     *
     * @param httpResponseWriteFailureCount   count of HTTP response write failures
     * @param staticFileRejectedCount         count of static file request rejections
     * @param staticFileWriteFailureCount     count of static file response write failures
     * @param idleCloseCount                  count of channels closed due to idle timeout
     * @param webSocketHandshakeRejectedCount count of rejected WebSocket handshakes
     * @param webSocketOriginRejectedCount    count of WebSocket origin validation failures
     */
    public HttpRuntimeStats(long httpResponseWriteFailureCount,
                            long staticFileRejectedCount,
                            long staticFileWriteFailureCount,
                            long idleCloseCount,
                            long webSocketHandshakeRejectedCount,
                            long webSocketOriginRejectedCount) {
        this.httpResponseWriteFailureCount = httpResponseWriteFailureCount;
        this.staticFileRejectedCount = staticFileRejectedCount;
        this.staticFileWriteFailureCount = staticFileWriteFailureCount;
        this.idleCloseCount = idleCloseCount;
        this.webSocketHandshakeRejectedCount = webSocketHandshakeRejectedCount;
        this.webSocketOriginRejectedCount = webSocketOriginRejectedCount;
    }

    /**
     * Returns the shared empty sentinel with all counters at zero.
     *
     * @return the empty {@link HttpRuntimeStats} instance
     */
    public static HttpRuntimeStats empty() {
        return EMPTY;
    }

    /** @return count of HTTP response write failures */
    public long getHttpResponseWriteFailureCount() {
        return httpResponseWriteFailureCount;
    }

    /** @return count of static file request rejections */
    public long getStaticFileRejectedCount() {
        return staticFileRejectedCount;
    }

    /** @return count of static file response write failures */
    public long getStaticFileWriteFailureCount() {
        return staticFileWriteFailureCount;
    }

    /** @return count of channels closed due to idle timeout */
    public long getIdleCloseCount() {
        return idleCloseCount;
    }

    /** @return count of rejected WebSocket handshakes */
    public long getWebSocketHandshakeRejectedCount() {
        return webSocketHandshakeRejectedCount;
    }

    /** @return count of WebSocket origin validation failures */
    public long getWebSocketOriginRejectedCount() {
        return webSocketOriginRejectedCount;
    }

    /**
     * Returns the combined count of all write failures (HTTP responses + static files).
     *
     * @return total write failure count
     */
    public long getWriteFailureCount() {
        return httpResponseWriteFailureCount + staticFileWriteFailureCount;
    }

    /**
     * Returns the combined count of all rejections (static files + WebSocket handshakes).
     *
     * @return total rejected count
     */
    public long getRejectedCount() {
        return staticFileRejectedCount + webSocketHandshakeRejectedCount;
    }

    @Override
    public String toString() {
        return "HttpRuntimeStats{" +
                "httpResponseWriteFailureCount=" + httpResponseWriteFailureCount +
                ", staticFileRejectedCount=" + staticFileRejectedCount +
                ", staticFileWriteFailureCount=" + staticFileWriteFailureCount +
                ", idleCloseCount=" + idleCloseCount +
                ", webSocketHandshakeRejectedCount=" + webSocketHandshakeRejectedCount +
                ", webSocketOriginRejectedCount=" + webSocketOriginRejectedCount +
                '}';
    }

}
