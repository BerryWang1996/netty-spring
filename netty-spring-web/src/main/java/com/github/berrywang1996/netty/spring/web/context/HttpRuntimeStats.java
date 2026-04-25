package com.github.berrywang1996.netty.spring.web.context;

/**
 * Runtime snapshot for HTTP/static-file failure and close paths.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public final class HttpRuntimeStats {

    private static final HttpRuntimeStats EMPTY = new HttpRuntimeStats(0L, 0L, 0L, 0L, 0L, 0L);

    private final long httpResponseWriteFailureCount;

    private final long staticFileRejectedCount;

    private final long staticFileWriteFailureCount;

    private final long idleCloseCount;

    private final long webSocketHandshakeRejectedCount;

    private final long webSocketOriginRejectedCount;

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

    public static HttpRuntimeStats empty() {
        return EMPTY;
    }

    public long getHttpResponseWriteFailureCount() {
        return httpResponseWriteFailureCount;
    }

    public long getStaticFileRejectedCount() {
        return staticFileRejectedCount;
    }

    public long getStaticFileWriteFailureCount() {
        return staticFileWriteFailureCount;
    }

    public long getIdleCloseCount() {
        return idleCloseCount;
    }

    public long getWebSocketHandshakeRejectedCount() {
        return webSocketHandshakeRejectedCount;
    }

    public long getWebSocketOriginRejectedCount() {
        return webSocketOriginRejectedCount;
    }

    public long getWriteFailureCount() {
        return httpResponseWriteFailureCount + staticFileWriteFailureCount;
    }

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
