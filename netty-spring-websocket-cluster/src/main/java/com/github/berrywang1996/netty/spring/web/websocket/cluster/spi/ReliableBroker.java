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
 * SPI for opt-in at-least-once cross-node BROADCAST (a durable complement to the at-most-once
 * {@link ClusterBroker} Pub/Sub path). The default impl ({@code RedisStreamsReliableBroker}) uses
 * Redis Streams: a per-URI stream + one consumer group per node, so a node that was briefly offline
 * replays the backlog it missed.
 *
 * <p>Only constructed when {@code server.netty.websocket.cluster.reliable.enable=true}.
 * Implementations must be thread-safe.
 *
 * @author berrywang1996
 * @since V1.9.0
 * @see ClusterBroker
 */
public interface ReliableBroker {

    /**
     * Durably publishes a broadcast envelope for the given URI (e.g. XADD to the URI stream).
     * Non-blocking / fire-and-log: a failure to persist is surfaced by the caller's publish-failure
     * policy, not thrown to the hot path.
     *
     * @param uri      the WebSocket mapping URI
     * @param envelope the broadcast envelope (kind {@code BROADCAST}; carries {@code originNodeId})
     */
    void reliablePublish(String uri, ClusterEnvelope envelope);

    /**
     * Subscribes this node to the reliable stream for a URI: ensures the per-node consumer group
     * exists and starts consuming (delivering each new entry to {@code listener}, then acking).
     * On reconnect after an outage the backlog replays automatically. Idempotent per URI.
     *
     * @param uri      the WebSocket mapping URI
     * @param nodeId   this node's id (consumer-group/consumer name)
     * @param listener callback for each received envelope (does origin self-suppression + delivery)
     * @return a handle to stop consuming this URI
     */
    ClusterSubscription reliableSubscribe(String uri, String nodeId, ClusterMessageListener listener);

    /**
     * Destroys the consumer groups owned by a dead node across all known reliable streams, so its
     * group + pending-entries-list don't leak. Called once per dead node from reconciliation cleanup.
     *
     * @param nodeId the dead node's id
     */
    void destroyConsumerGroupsForNode(String nodeId);

    /** @return the broker's transport state (never null). */
    BrokerState state();

    /** Stops all consume loops and releases connections. */
    void shutdown();
}
