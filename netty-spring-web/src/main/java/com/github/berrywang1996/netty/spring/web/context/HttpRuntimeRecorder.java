package com.github.berrywang1996.netty.spring.web.context;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe accumulator of HTTP and static-file runtime diagnostic counters.
 *
 * <p>Records failure and rejection events across the HTTP request lifecycle,
 * including response write failures, static file access rejections, idle channel
 * closures, and WebSocket handshake/origin rejections.
 *
 * <p>A no-op singleton ({@link #noop()}) is available for use when runtime
 * recording is disabled, avoiding null checks throughout the codebase.
 *
 * @author berrywang1996
 * @since V1.0.0
 * @see HttpRuntimeStats
 */
public final class HttpRuntimeRecorder {

    /** Shared no-op instance that silently discards all recorded events. */
    private static final HttpRuntimeRecorder NOOP = new HttpRuntimeRecorder(false);

    /** Whether counter increments are actually performed. */
    private final boolean enabled;

    /** Number of times an HTTP response could not be written to the channel. */
    private final AtomicLong httpResponseWriteFailureCount = new AtomicLong();

    /** Number of static file requests that were rejected (e.g. forbidden paths). */
    private final AtomicLong staticFileRejectedCount = new AtomicLong();

    /** Number of times a static file response could not be written to the channel. */
    private final AtomicLong staticFileWriteFailureCount = new AtomicLong();

    /** Number of channels closed due to idle timeout. */
    private final AtomicLong idleCloseCount = new AtomicLong();

    /** Number of WebSocket handshake attempts that were rejected (e.g. max connections). */
    private final AtomicLong webSocketHandshakeRejectedCount = new AtomicLong();

    /** Number of WebSocket handshake attempts rejected due to origin validation failure. */
    private final AtomicLong webSocketOriginRejectedCount = new AtomicLong();

    /**
     * Creates an enabled recorder that actively tracks all events.
     */
    public HttpRuntimeRecorder() {
        this(true);
    }

    /**
     * Internal constructor that controls whether counter increments are performed.
     *
     * @param enabled {@code true} to enable recording, {@code false} for no-op behavior
     */
    private HttpRuntimeRecorder(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the shared no-op recorder that silently discards all events.
     *
     * @return the no-op {@link HttpRuntimeRecorder} singleton
     */
    public static HttpRuntimeRecorder noop() {
        return NOOP;
    }

    /** Records an HTTP response write failure event. */
    public void recordHttpResponseWriteFailure() {
        increment(httpResponseWriteFailureCount);
    }

    /** Records a static file request rejection event. */
    public void recordStaticFileRejected() {
        increment(staticFileRejectedCount);
    }

    /** Records a static file write failure event. */
    public void recordStaticFileWriteFailure() {
        increment(staticFileWriteFailureCount);
    }

    /** Records an idle channel close event. */
    public void recordIdleClose() {
        increment(idleCloseCount);
    }

    /** Records a WebSocket handshake rejection event. */
    public void recordWebSocketHandshakeRejected() {
        increment(webSocketHandshakeRejectedCount);
    }

    /** Records a WebSocket origin validation rejection event. */
    public void recordWebSocketOriginRejected() {
        increment(webSocketOriginRejectedCount);
    }

    /**
     * Creates an immutable snapshot of all current counter values.
     *
     * @return a new {@link HttpRuntimeStats} reflecting the current state
     */
    public HttpRuntimeStats getRuntimeStats() {
        return new HttpRuntimeStats(
                httpResponseWriteFailureCount.get(),
                staticFileRejectedCount.get(),
                staticFileWriteFailureCount.get(),
                idleCloseCount.get(),
                webSocketHandshakeRejectedCount.get(),
                webSocketOriginRejectedCount.get());
    }

    /**
     * Conditionally increments the given counter. No-op recorders skip the increment.
     *
     * @param counter the atomic counter to increment
     */
    private void increment(AtomicLong counter) {
        if (enabled) {
            counter.incrementAndGet();
        }
    }

}
