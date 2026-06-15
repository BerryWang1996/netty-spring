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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.spi;

/**
 * The public presence read shape for a user (1.10.0-RC3): the derived {@link #getAggregate() aggregate} plus the
 * per-status <b>connection counts</b>.
 *
 * <p><b>No device map.</b> RC3 deliberately does NOT expose a per-device map keyed on internal
 * {@code nodeId|sessionId} identifiers (that would leak cluster topology and freeze an ephemeral, reconnect-unstable
 * key into a public API — see the design review). Per-device <i>addressing</i> with a stable device identity is a
 * later concern (a {@code DeviceIdResolver} SPI). The aggregate here is still multi-device-aware (ONLINE if any
 * connection is ONLINE).
 *
 * <p><b>Advisory, not a liveness probe.</b> This reflects last-known state. After a hard node crash a dead connection
 * lingers until reconciliation reaps it (bounded by {@code heartbeat-timeout + reconciliation-interval}); during that
 * window the aggregate may read ONLINE for a dead connection. Latency-sensitive callers treat this as advisory and
 * rely on the {@code sendToUser} delivery-time fallback for correctness.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
public final class UserPresence {

    private final PresenceStatus aggregate;
    private final int onlineConnections;
    private final int awayConnections;

    public UserPresence(PresenceStatus aggregate, int onlineConnections, int awayConnections) {
        this.aggregate = aggregate;
        this.onlineConnections = onlineConnections;
        this.awayConnections = awayConnections;
    }

    public PresenceStatus getAggregate() {
        return aggregate;
    }

    public int getOnlineConnections() {
        return onlineConnections;
    }

    public int getAwayConnections() {
        return awayConnections;
    }

    public int getTotalConnections() {
        return onlineConnections + awayConnections;
    }

    /** Derives the aggregate from connection counts (shared by every {@link PresenceRegistry} impl). */
    public static PresenceStatus aggregateOf(int online, int away) {
        if (online > 0) {
            return PresenceStatus.ONLINE;
        }
        if (away > 0) {
            return PresenceStatus.AWAY;
        }
        return PresenceStatus.OFFLINE;
    }

    @Override
    public String toString() {
        return "UserPresence{" + aggregate + ", online=" + onlineConnections + ", away=" + awayConnections + "}";
    }
}
