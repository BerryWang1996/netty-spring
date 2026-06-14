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

import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * SPI for the {@code userId → live sessions} reverse index (1.10.0-RC2) — a <b>derived routing/presence
 * index</b>, NOT a durable replica of {@link SessionRegistry}. It answers "which sessions (cluster-wide) does
 * this user currently have?" so {@code sendToUser} can unicast to an online user, and "is this user online
 * anywhere?" so a {@code sendToUser} to an offline user falls through to the offline queue.
 *
 * <p>Reconciled via {@link #removeAllForNode} on dead-node cleanup (parallel to {@code SessionRegistry} and
 * {@code ClusterRoomRegistry}). A dedicated SPI was chosen over adding methods to {@link SessionRegistry} so
 * the 1.9.0 {@code SessionRegistry} signature (and its 3 impls) stays untouched (see the RC2 design-review
 * decision record). RC3 multi-device presence extends this same SPI.
 *
 * <p>All mutating operations return {@link CompletionStage}; implementations must be thread-safe.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
public interface UserRegistry {

    /**
     * Binds a live session to a user (adds {@code (nodeId, uri, sessionId)} to the user's session set).
     * Called on connect, after the {@link SessionRegistry#register} completes.
     *
     * @param userId    the stable user identity (from {@link UserIdResolver})
     * @param uri       the WebSocket mapping URI
     * @param sessionId the session identifier
     * @param nodeId    the node that owns this session
     * @return a stage that completes when the binding is persisted
     */
    CompletionStage<Void> bindUser(String userId, String uri, String sessionId, String nodeId);

    /**
     * Unbinds a session from a user (removes it from the user's session set). Called on disconnect.
     *
     * @param userId    the stable user identity
     * @param uri       the WebSocket mapping URI
     * @param sessionId the session identifier
     * @return a stage that completes when the binding is removed
     */
    CompletionStage<Void> unbindUser(String userId, String uri, String sessionId);

    /**
     * Returns the user's currently-bound sessions across the cluster.
     *
     * <p><b>NOT cached</b> — every call hits the store. Caching this would create a false-online silent-loss
     * window: a just-disconnected user could read "online" → a fire-and-forget unicast to a dead session →
     * no exception → no offline-queue fallback → the message is silently lost. This lookup is on the
     * relatively cold {@code sendToUser} path (not the per-session hot path), so the fresh read is affordable.
     * See the RC2 spec §5/§6 and the design-review decision record.
     *
     * @param userId the stable user identity
     * @return a stage completing with the user's live session refs (empty = offline cluster-wide)
     */
    CompletionStage<Set<SessionRef>> sessionsForUser(String userId);

    /**
     * Whether the user has any live session anywhere in the cluster.
     *
     * @param userId the stable user identity
     * @return a stage completing with {@code true} if the user is online on at least one node
     */
    CompletionStage<Boolean> isUserOnline(String userId);

    /**
     * Removes all of a node's session bindings from every user's set (bulk cleanup on node failure).
     *
     * @param nodeId the failed node's identifier
     * @return a stage that completes when cleanup is done
     */
    CompletionStage<Void> removeAllForNode(String nodeId);

    /**
     * Shuts down the registry, releasing underlying resources.
     */
    void shutdown();
}
