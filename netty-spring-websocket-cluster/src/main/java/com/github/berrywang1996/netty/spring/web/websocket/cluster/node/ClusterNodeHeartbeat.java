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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.node;

import java.util.List;

/**
 * Abstraction for node heartbeat persistence and peer failure detection.
 *
 * <p>The default implementation uses Redis keys with TTL. The
 * {@link ClusterNodeManager} calls these methods periodically and does not
 * assume any specific backing store.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
public interface ClusterNodeHeartbeat {

    /**
     * Registers this node with the cluster, creating the initial heartbeat key
     * with the given TTL.
     *
     * @param nodeId    unique node identifier
     * @param timeoutMs the heartbeat TTL in milliseconds
     */
    void register(String nodeId, long timeoutMs);

    /**
     * Renews the heartbeat for this node (extends the TTL).
     *
     * @param nodeId    unique node identifier
     * @param timeoutMs the heartbeat TTL in milliseconds
     */
    void renewHeartbeat(String nodeId, long timeoutMs);

    /**
     * Deregisters this node from the cluster (removes the heartbeat key and
     * the node entry).
     *
     * @param nodeId unique node identifier
     */
    void deregister(String nodeId);

    /**
     * Finds nodes whose heartbeat has expired (slow-path reconciliation backstop).
     * This compensates for missed Redis keyspace notifications.
     *
     * @param timeoutMs the heartbeat timeout threshold
     * @return list of dead node ids (may be empty, never null)
     */
    List<String> findExpiredNodes(long timeoutMs);
}
