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

/**
 * Immutable snapshot of WebSocket event counters, including close-reason breakdown.
 *
 * @author berrywang1996
 * @since V1.3.0
 */
public final class WebSocketEventStats {

    private static final Map<CloseReason, Long> EMPTY_CLOSE_MAP;

    static {
        EnumMap<CloseReason, Long> map = new EnumMap<>(CloseReason.class);
        for (CloseReason reason : CloseReason.values()) {
            map.put(reason, 0L);
        }
        EMPTY_CLOSE_MAP = Collections.unmodifiableMap(map);
    }

    private static final WebSocketEventStats EMPTY =
            new WebSocketEventStats(0, 0, 0, 0, 0, 0, EMPTY_CLOSE_MAP);

    private final long handshakeTotal;
    private final long handshakeSuccess;
    private final long handshakeRejectedByInterceptor;
    private final long messagesReceived;
    private final long messagesSent;
    private final long totalCloses;
    private final Map<CloseReason, Long> closesByReason;

    /**
     * Creates a new immutable event stats snapshot.
     *
     * @param handshakeTotal                  total handshake attempts
     * @param handshakeSuccess                successful handshakes
     * @param handshakeRejectedByInterceptor  handshakes rejected by the interceptor
     * @param messagesReceived                total inbound messages
     * @param messagesSent                    total outbound messages
     * @param totalCloses                     total session close events
     * @param closesByReason                  close counts broken down by {@link CloseReason}
     */
    public WebSocketEventStats(long handshakeTotal,
                               long handshakeSuccess,
                               long handshakeRejectedByInterceptor,
                               long messagesReceived,
                               long messagesSent,
                               long totalCloses,
                               Map<CloseReason, Long> closesByReason) {
        this.handshakeTotal = handshakeTotal;
        this.handshakeSuccess = handshakeSuccess;
        this.handshakeRejectedByInterceptor = handshakeRejectedByInterceptor;
        this.messagesReceived = messagesReceived;
        this.messagesSent = messagesSent;
        this.totalCloses = totalCloses;
        this.closesByReason = closesByReason;
    }

    /**
     * Returns a singleton instance with all counters at zero.
     *
     * @return the empty stats instance
     */
    public static WebSocketEventStats empty() {
        return EMPTY;
    }

    /** @return total number of handshake attempts */
    public long getHandshakeTotal() {
        return handshakeTotal;
    }

    /** @return number of handshakes that completed successfully */
    public long getHandshakeSuccess() {
        return handshakeSuccess;
    }

    /** @return number of handshakes rejected by the interceptor */
    public long getHandshakeRejectedByInterceptor() {
        return handshakeRejectedByInterceptor;
    }

    /** @return total inbound messages received */
    public long getMessagesReceived() {
        return messagesReceived;
    }

    /** @return total outbound messages sent */
    public long getMessagesSent() {
        return messagesSent;
    }

    /** @return total number of session close events */
    public long getTotalCloses() {
        return totalCloses;
    }

    /**
     * Returns close counts broken down by {@link CloseReason}.
     * The map contains every enum constant; counters that were never
     * incremented are 0.
     *
     * @return unmodifiable map of close reason to count
     */
    public Map<CloseReason, Long> getClosesByReason() {
        return closesByReason;
    }

    /**
     * Returns the close count for a specific reason.
     *
     * @param reason the close reason to query
     * @return the close count, or 0 if never recorded
     */
    public long getCloseCount(CloseReason reason) {
        Long val = closesByReason.get(reason);
        return val != null ? val : 0L;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("WebSocketEventStats{");
        sb.append("handshakes=").append(handshakeSuccess).append('/').append(handshakeTotal);
        sb.append(", interceptorRejected=").append(handshakeRejectedByInterceptor);
        sb.append(", received=").append(messagesReceived);
        sb.append(", sent=").append(messagesSent);
        sb.append(", closes=").append(totalCloses);
        sb.append(", closesByReason={");
        boolean first = true;
        for (Map.Entry<CloseReason, Long> entry : closesByReason.entrySet()) {
            if (entry.getValue() > 0) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(entry.getKey().getTag()).append('=').append(entry.getValue());
                first = false;
            }
        }
        sb.append("}}");
        return sb.toString();
    }
}
