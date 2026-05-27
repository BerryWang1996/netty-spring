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

package com.github.berrywang1996.netty.spring.web.websocket.consts;

/**
 * Enumeration of all reasons a WebSocket session can be closed.
 * Used for metrics aggregation, diagnostics and alerting.
 *
 * @author berrywang1996
 * @since V1.3.0
 */
public enum CloseReason {

    // ---- Client-initiated ----

    /**
     * Client sent a {@code CloseWebSocketFrame}.
     */
    CLIENT_CLOSE("client_close", "Client sent close frame"),

    // ---- Server explicit closes ----

    /**
     * Business code called {@code MessageSender.closeSession()} or equivalent API.
     */
    API_CLOSE("api_close", "Closed via MessageSender API"),

    /**
     * Server is shutting down gracefully.
     */
    SERVER_SHUTDOWN("server_shutdown", "Server shutting down"),

    // ---- Timeout-based closes ----

    /**
     * No ping/pong response within the heartbeat timeout window.
     */
    HEARTBEAT_TIMEOUT("heartbeat_timeout", "Heartbeat timeout"),

    // ---- Error-based closes ----

    /**
     * A network/IO or Netty transport error occurred.
     */
    TRANSPORT_ERROR("transport_error", "Transport error"),

    /**
     * The inbound WebSocket frame payload exceeded the configured maximum.
     */
    FRAME_TOO_LARGE("frame_too_large", "Frame payload too large"),

    /**
     * Failed to decrypt an inbound frame (crypto decode failure).
     */
    DECRYPT_FAILURE("decrypt_failure", "Crypto decrypt failure"),

    /**
     * The WebSocket handshake failed (e.g. version mismatch).
     */
    HANDSHAKE_FAILURE("handshake_failure", "Handshake failure"),

    /**
     * An unhandled exception occurred in the {@code ON_CONNECTED} lifecycle callback.
     */
    CONNECTED_HANDLER_ERROR("connected_handler_error", "Exception in ON_CONNECTED handler"),

    // ---- Channel state closes ----

    /**
     * The Netty channel became inactive (e.g. TCP reset, client disappeared).
     */
    CHANNEL_INACTIVE("channel_inactive", "Channel inactive"),

    /**
     * The channel was not writable during a broadcast and was force-closed.
     */
    CHANNEL_NOT_WRITABLE("channel_not_writable", "Channel not writable"),

    /**
     * A write operation to the channel failed.
     */
    WRITE_FAILURE("write_failure", "Write failure"),

    // ---- Rejection (pre-session) ----

    /**
     * The WebSocket handshake was rejected by the {@code WebSocketHandshakeInterceptor}.
     */
    INTERCEPTOR_REJECTED("interceptor_rejected", "Rejected by handshake interceptor"),

    /**
     * Catch-all for close reasons not covered by other enum constants.
     */
    UNKNOWN("unknown", "Unknown close reason");

    private final String tag;

    private final String description;

    CloseReason(String tag, String description) {
        this.tag = tag;
        this.description = description;
    }

    /**
     * A short, metrics-friendly tag suitable for use as a Micrometer tag value
     * or JSON field name.
     */
    public String getTag() {
        return tag;
    }

    /**
     * Human-readable description of the close reason.
     */
    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return tag;
    }
}
