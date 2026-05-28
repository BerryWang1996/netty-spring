/*
 * Copyright 2018 berrywang1996
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.websocket.consts.CloseReason;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe recorder for WebSocket session events with close-reason dimensions.
 *
 * <p>This class maintains counters for handshakes, messages, and session closes
 * broken down by {@link CloseReason}. It follows the same pattern as
 * {@code HttpRuntimeRecorder} in the web module.
 *
 * <p>A {@link #noop()} instance is available for contexts where recording is disabled.
 *
 * @author berrywang1996
 * @since V1.3.0
 */
public final class WebSocketEventRecorder {

    private static final WebSocketEventRecorder NOOP = new WebSocketEventRecorder(false);

    private final boolean enabled;

    // ---- Handshake counters ----
    private final AtomicLong handshakeTotal = new AtomicLong();
    private final AtomicLong handshakeSuccess = new AtomicLong();
    private final AtomicLong handshakeRejectedByInterceptor = new AtomicLong();

    // ---- Message counters ----
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong messagesSent = new AtomicLong();

    // ---- Close counters by reason ----
    private final Map<CloseReason, AtomicLong> closeCounters;

    /**
     * Creates an enabled recorder that actively counts events.
     */
    public WebSocketEventRecorder() {
        this(true);
    }

    /**
     * Internal constructor for creating either an enabled or no-op recorder.
     *
     * @param enabled whether this recorder should actively count events
     */
    private WebSocketEventRecorder(boolean enabled) {
        this.enabled = enabled;
        EnumMap<CloseReason, AtomicLong> counters = new EnumMap<>(CloseReason.class);
        for (CloseReason reason : CloseReason.values()) {
            counters.put(reason, new AtomicLong());
        }
        this.closeCounters = Collections.unmodifiableMap(counters);
    }

    /**
     * Returns a shared no-op recorder that silently ignores all events.
     *
     * @return the no-op recorder singleton
     */
    public static WebSocketEventRecorder noop() {
        return NOOP;
    }

    // ---- Handshake events ----

    /** Records a WebSocket handshake attempt (before validation). */
    public void recordHandshakeAttempt() {
        increment(handshakeTotal);
    }

    /** Records a successful WebSocket handshake upgrade. */
    public void recordHandshakeSuccess() {
        increment(handshakeSuccess);
    }

    /** Records a handshake rejection by the {@link WebSocketHandshakeInterceptor}. */
    public void recordHandshakeRejectedByInterceptor() {
        increment(handshakeRejectedByInterceptor);
    }

    // ---- Message events ----

    /** Records an inbound WebSocket message (any frame type). */
    public void recordMessageReceived() {
        increment(messagesReceived);
    }

    /** Records a successfully sent outbound WebSocket message. */
    public void recordMessageSent() {
        increment(messagesSent);
    }

    // ---- Close events ----

    /**
     * Records a session close event categorized by the given reason.
     *
     * @param reason the reason the session was closed; {@code null} is silently ignored
     */
    public void recordClose(CloseReason reason) {
        if (!enabled || reason == null) {
            return;
        }
        AtomicLong counter = closeCounters.get(reason);
        if (counter != null) {
            counter.incrementAndGet();
        }
    }

    // ---- Snapshot ----

    /**
     * Takes a consistent snapshot of all counters and returns them as an
     * immutable {@link WebSocketEventStats}.
     *
     * @return an immutable snapshot of all event counters
     */
    public WebSocketEventStats getStats() {
        EnumMap<CloseReason, Long> closeSnapshot = new EnumMap<>(CloseReason.class);
        long totalCloses = 0;
        for (Map.Entry<CloseReason, AtomicLong> entry : closeCounters.entrySet()) {
            long val = entry.getValue().get();
            closeSnapshot.put(entry.getKey(), val);
            totalCloses += val;
        }
        return new WebSocketEventStats(
                handshakeTotal.get(),
                handshakeSuccess.get(),
                handshakeRejectedByInterceptor.get(),
                messagesReceived.get(),
                messagesSent.get(),
                totalCloses,
                Collections.unmodifiableMap(closeSnapshot));
    }

    private void increment(AtomicLong counter) {
        if (enabled) {
            counter.incrementAndGet();
        }
    }
}
