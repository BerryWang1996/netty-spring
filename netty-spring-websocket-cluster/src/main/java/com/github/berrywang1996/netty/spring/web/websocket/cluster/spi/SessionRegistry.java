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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * SPI for distributed session presence and routing (which node owns which session).
 *
 * <p>This is the second core extension point alongside {@link ClusterBroker}. The default
 * implementation in 1.8.0 is {@code RedisSessionRegistry} (Redis Hash + Set); it can be
 * replaced independently of the broker (e.g. Redis registry + NATS broker).
 *
 * <p>All mutating operations return {@link CompletionStage} to allow pipelining and
 * non-blocking I/O. Implementations must be thread-safe.
 *
 * <h3>Caching contract</h3>
 * <p>{@link #lookupNode} results may be cached locally by the caller (e.g.
 * {@code ClusterMessageSender}) with a short TTL. The cache is invalidated on
 * {@code NODE_LEFT} events. If the cached nodeId is stale (target node doesn't have
 * the session), the caller falls back to a fresh {@code lookupNode} call.
 *
 * @author berrywang1996
 * @since V1.8.0
 * @see ClusterBroker
 */
public interface SessionRegistry {

    /**
     * Registers a session in the distributed registry.
     *
     * @param uri       the WebSocket mapping URI
     * @param sessionId the session identifier
     * @param nodeId    the node that owns this session
     * @param metadata  optional metadata (e.g. userId, connectedAt); may be empty, never null
     * @return a stage that completes when the registration is persisted
     */
    CompletionStage<Void> register(String uri, String sessionId, String nodeId,
                                   Map<String, String> metadata);

    /**
     * Removes a session from the distributed registry.
     *
     * @param uri       the WebSocket mapping URI
     * @param sessionId the session identifier
     * @return a stage that completes when the deregistration is persisted
     */
    CompletionStage<Void> deregister(String uri, String sessionId);

    /**
     * Looks up which node owns the given session.
     *
     * @param uri       the WebSocket mapping URI
     * @param sessionId the session identifier
     * @return a stage that completes with the owning nodeId, or null if the session
     *         is not registered (disconnected or never registered)
     */
    CompletionStage<String> lookupNode(String uri, String sessionId);

    /**
     * Returns all session ids registered for a URI across the entire cluster.
     *
     * <p><b>Warning:</b> this is an expensive operation (Redis SMEMBERS / SCAN across
     * nodes). Use sparingly — prefer local {@code MessageSender.getSessionIds(uri)}
     * for hot-path queries.
     *
     * @param uri the WebSocket mapping URI
     * @return a stage that completes with the full set of session ids (may be empty)
     */
    CompletionStage<Set<String>> clusterSessionIds(String uri);

    /**
     * Batch lookup of which nodes own the given sessions. Default implementation
     * loops over {@link #lookupNode}; Redis implementations should override with
     * a pipeline HGET for efficiency on batch unicast.
     *
     * @param uri        the WebSocket mapping URI
     * @param sessionIds the session identifiers to look up
     * @return a stage that completes with a map of sessionId → nodeId (entries with null nodeId omitted)
     * @since V1.8.0
     */
    default CompletionStage<Map<String, String>> lookupNodeBatch(String uri, Set<String> sessionIds) {
        java.util.concurrent.CompletableFuture<Map<String, String>> result =
                new java.util.concurrent.CompletableFuture<>();
        Map<String, String> map = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.CompletableFuture<?>[] futures = sessionIds.stream()
                .map(sid -> lookupNode(uri, sid).thenAccept(nodeId -> {
                    if (nodeId != null) map.put(sid, nodeId);
                }).toCompletableFuture())
                .toArray(java.util.concurrent.CompletableFuture[]::new);
        java.util.concurrent.CompletableFuture.allOf(futures).thenRun(() -> result.complete(map));
        return result;
    }

    /**
     * Removes all sessions owned by a specific node (bulk cleanup on node failure).
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
