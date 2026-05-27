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

    public WebSocketEventRecorder() {
        this(true);
    }

    private WebSocketEventRecorder(boolean enabled) {
        this.enabled = enabled;
        EnumMap<CloseReason, AtomicLong> counters = new EnumMap<>(CloseReason.class);
        for (CloseReason reason : CloseReason.values()) {
            counters.put(reason, new AtomicLong());
        }
        this.closeCounters = Collections.unmodifiableMap(counters);
    }

    public static WebSocketEventRecorder noop() {
        return NOOP;
    }

    // ---- Handshake events ----

    public void recordHandshakeAttempt() {
        increment(handshakeTotal);
    }

    public void recordHandshakeSuccess() {
        increment(handshakeSuccess);
    }

    public void recordHandshakeRejectedByInterceptor() {
        increment(handshakeRejectedByInterceptor);
    }

    // ---- Message events ----

    public void recordMessageReceived() {
        increment(messagesReceived);
    }

    public void recordMessageSent() {
        increment(messagesSent);
    }

    // ---- Close events ----

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
     * Takes a consistent snapshot of all counters.
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
