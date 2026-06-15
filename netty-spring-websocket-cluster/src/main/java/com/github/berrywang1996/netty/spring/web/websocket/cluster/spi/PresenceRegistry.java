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

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Per-user multi-device presence index (1.10.0-RC3) — a Redis-only SPI parallel to {@link UserRegistry} (NOT bolted
 * onto it). Stores each live connection's {@link PresenceStatus} keyed by {@code (userId, nodeId, sessionId)} and
 * derives the per-user aggregate.
 *
 * <p><b>Atomic transition detection</b> is the correctness core: every mutation runs as a single atomic operation that
 * reads the old aggregate, applies the change, recomputes the new aggregate, and returns the
 * {@link PresenceTransition}. The caller publishes a {@code PRESENCE_CHANGE} event only when
 * {@link PresenceTransition#changed()}. Concurrent multi-node mutations on the same user serialize on the single key,
 * so two simultaneous first-connections yield exactly one {@code OFFLINE -> ONLINE} transition.
 *
 * <p>Reads ({@link #getPresence}) are <b>NOT cached</b> — the same anti-false-online contract as
 * {@link UserRegistry#sessionsForUser}. {@link #getPresence} reflects last-known state and is ADVISORY (see
 * {@link UserPresence}), not a liveness probe.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
public interface PresenceRegistry {

    /**
     * Sets ONE connection's status (atomic). Returns the resulting aggregate transition; {@code userId} on the
     * returned transition is {@code null} (the caller knows it).
     */
    CompletionStage<PresenceTransition> setPresence(String userId, String nodeId, String sessionId, PresenceStatus status);

    /**
     * Sets ALL of a user's connections to a status (the "set me away" convenience). Atomic; returns the aggregate
     * transition.
     */
    CompletionStage<PresenceTransition> setPresenceForUser(String userId, PresenceStatus status);

    /**
     * Clears ONE connection (graceful disconnect). Atomic; when the last connection clears the new aggregate is
     * {@code OFFLINE}.
     */
    CompletionStage<PresenceTransition> clearPresence(String userId, String nodeId, String sessionId);

    /**
     * Aggregate read for a user. NOT cached (hits the store every call). Returns last-known state — ADVISORY, not a
     * liveness probe.
     */
    CompletionStage<UserPresence> getPresence(String userId);

    /**
     * Transition-aware dead-node reap: removes every connection on the dead node from every user's presence, and
     * returns one {@link PresenceTransition} (with {@code userId} set) per user whose aggregate changed — so the
     * leader can publish the {@code -> OFFLINE} events for crashed-but-not-gracefully-closed connections. This is the
     * authoritative source of OFFLINE events on the dominant crash path.
     */
    CompletionStage<List<PresenceTransition>> removeAllForNode(String nodeId);

    /** Releases resources (executor). */
    void shutdown();
}
