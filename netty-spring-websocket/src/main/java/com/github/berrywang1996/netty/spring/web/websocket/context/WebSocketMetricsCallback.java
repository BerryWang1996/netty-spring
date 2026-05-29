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

/**
 * Callback interface for recording distribution-based WebSocket metrics
 * (timers, histograms) that require event-time observation.
 *
 * <p>This interface decouples the WebSocket module from Micrometer: the
 * {@link WebSocketEventRecorder} accepts an optional callback, and the
 * autoconfigure module provides a Micrometer-backed implementation that
 * records observations into Timer and DistributionSummary meters.
 *
 * <p>When no callback is registered (the default), these distribution metrics
 * are silently skipped; the atomic-counter-based metrics in
 * {@link WebSocketEventRecorder} continue to work independently.
 *
 * @author berrywang1996
 * @since V1.7.0
 * @see WebSocketEventRecorder#addMetricsCallback(WebSocketMetricsCallback)
 */
public interface WebSocketMetricsCallback {

    /**
     * Records the duration of a WebSocket connection from handshake to close.
     *
     * @param closeReason the close reason tag (e.g. "client_close", "heartbeat_timeout")
     * @param durationNanos the connection duration in nanoseconds
     */
    void recordConnectionDuration(String closeReason, long durationNanos);

    /**
     * Records the size of an inbound WebSocket message.
     *
     * @param bytes the message payload size in bytes
     */
    void recordMessageSize(int bytes);

    /**
     * Records the fan-out count of a broadcast operation (number of sessions
     * the message was sent to).
     *
     * @param sessionCount the number of sessions targeted by the broadcast
     */
    void recordBroadcastFanout(int sessionCount);

    /**
     * Records the latency of a handler method invocation (from entry to return).
     *
     * @param latencyNanos the handler execution time in nanoseconds
     */
    void recordHandlerLatency(long latencyNanos);
}
