package com.github.berrywang1996.netty.spring.web.context;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared counters for HTTP/static-file runtime diagnostics.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public final class HttpRuntimeRecorder {

    private static final HttpRuntimeRecorder NOOP = new HttpRuntimeRecorder(false);

    private final boolean enabled;

    private final AtomicLong httpResponseWriteFailureCount = new AtomicLong();

    private final AtomicLong staticFileRejectedCount = new AtomicLong();

    private final AtomicLong staticFileWriteFailureCount = new AtomicLong();

    private final AtomicLong idleCloseCount = new AtomicLong();

    private final AtomicLong webSocketHandshakeRejectedCount = new AtomicLong();

    private final AtomicLong webSocketOriginRejectedCount = new AtomicLong();

    public HttpRuntimeRecorder() {
        this(true);
    }

    private HttpRuntimeRecorder(boolean enabled) {
        this.enabled = enabled;
    }

    public static HttpRuntimeRecorder noop() {
        return NOOP;
    }

    public void recordHttpResponseWriteFailure() {
        increment(httpResponseWriteFailureCount);
    }

    public void recordStaticFileRejected() {
        increment(staticFileRejectedCount);
    }

    public void recordStaticFileWriteFailure() {
        increment(staticFileWriteFailureCount);
    }

    public void recordIdleClose() {
        increment(idleCloseCount);
    }

    public void recordWebSocketHandshakeRejected() {
        increment(webSocketHandshakeRejectedCount);
    }

    public void recordWebSocketOriginRejected() {
        increment(webSocketOriginRejectedCount);
    }

    public HttpRuntimeStats getRuntimeStats() {
        return new HttpRuntimeStats(
                httpResponseWriteFailureCount.get(),
                staticFileRejectedCount.get(),
                staticFileWriteFailureCount.get(),
                idleCloseCount.get(),
                webSocketHandshakeRejectedCount.get(),
                webSocketOriginRejectedCount.get());
    }

    private void increment(AtomicLong counter) {
        if (enabled) {
            counter.incrementAndGet();
        }
    }

}
