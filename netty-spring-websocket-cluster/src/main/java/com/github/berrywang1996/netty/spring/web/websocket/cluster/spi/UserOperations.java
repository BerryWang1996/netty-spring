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

import com.github.berrywang1996.netty.spring.web.websocket.context.AbstractMessage;

import java.util.concurrent.CompletionStage;

/**
 * User-addressed message operations layered on top of the base
 * {@link com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender} (1.10.0-RC2).
 *
 * <p>Kept as a separate sub-interface (parallel to {@code RoomOperations}) so the focused base
 * {@code MessageSender} — keyed on ephemeral per-connection {@code sessionId} — is untouched: user-addressed
 * delivery is a cluster-only, opt-in capability ({@code server.netty.websocket.cluster.offline.enable=true})
 * keyed on a stable {@code userId}. {@code ClusterMessageSender} implements this; a non-cluster sender does
 * not. The pairing of {@link #sendToUser} with {@link #isUserOnline} gives the sub-interface a dual purpose
 * (mirrors {@code RoomOperations} carrying multiple methods).
 *
 * @author berrywang1996
 * @since V1.10.0
 */
public interface UserOperations {

    /**
     * Delivers a message to a user by their stable identity: if the user has any live session cluster-wide
     * (fresh, uncached {@code UserRegistry} lookup), the message is unicast to each — realtime. If the user
     * is offline (zero sessions anywhere), or all reachable sessions fail at send time, the message is
     * appended to the user's durable offline queue and backfilled (FIFO) when they reconnect.
     *
     * <p><b>Semantics (honest):</b> at-least-once to offline users within the retention window
     * ({@code offline.max-messages-per-user} / {@code offline.ttl-seconds}); handlers must be idempotent
     * (a redelivered offline message carries an {@code X-Offline-Message-Id} for dedup). The offline queue
     * is a <b>send-time</b> fallback only: {@code broker.unicast} is fire-and-forget, so a remote session
     * that closes after the broker accepted the unicast (but before the frame arrives) is NOT recovered —
     * see {@code docs/release-notes-1.10.0.md} §Offline for the full contract.
     *
     * @param userId  the recipient's stable identity (from {@link UserIdResolver}; never null)
     * @param message the message to deliver
     * @throws IllegalStateException if the offline / user-addressed path is disabled
     *                               ({@code offline.enable=false})
     */
    void sendToUser(String userId, AbstractMessage message);

    /**
     * Whether the user has any live session anywhere in the cluster (a fresh, uncached presence lookup).
     *
     * @param userId the recipient's stable identity
     * @return a stage completing with {@code true} if the user is online on at least one node
     * @throws IllegalStateException if the offline / user-addressed path is disabled
     */
    CompletionStage<Boolean> isUserOnline(String userId);
}
