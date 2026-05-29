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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.redis;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Redis-backed implementation of {@link ClusterNodeHeartbeat}.
 *
 * <p>Redis key design:
 * <ul>
 *   <li>{@code netty:cluster:heartbeat:{nodeId}} — String with TTL (heartbeat alive signal)</li>
 *   <li>{@code netty:cluster:nodes} — Hash { nodeId → timestamp } (node registry)</li>
 * </ul>
 *
 * <p>Failure detection relies on two mechanisms:
 * <ol>
 *   <li><b>Fast path:</b> Redis Keyspace Notification on heartbeat key expiry
 *       (configured by the auto-configuration, not by this class)</li>
 *   <li><b>Slow path (reconciliation):</b> {@link #findExpiredNodes} scans the
 *       {@code netty:cluster:nodes} hash and compares timestamps — this compensates
 *       for missed keyspace notifications (which are fire-and-forget)</li>
 * </ol>
 *
 * @author berrywang1996
 * @since V1.8.0
 */
@Slf4j
public class RedisClusterNodeHeartbeat implements ClusterNodeHeartbeat {

    private static final String HEARTBEAT_PREFIX = "netty:cluster:heartbeat:";
    private static final String NODES_KEY = "netty:cluster:nodes";

    private final StatefulRedisConnection<String, String> connection;

    public RedisClusterNodeHeartbeat(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    @Override
    public void register(String nodeId, long timeoutMs) {
        RedisCommands<String, String> sync = connection.sync();
        String heartbeatKey = HEARTBEAT_PREFIX + nodeId;
        String now = String.valueOf(System.currentTimeMillis());

        // Set heartbeat key with TTL
        sync.set(heartbeatKey, now, SetArgs.Builder.px(timeoutMs));
        // Register in the nodes hash
        sync.hset(NODES_KEY, nodeId, now);

        log.debug("Node {} registered with heartbeat TTL {}ms", nodeId, timeoutMs);
    }

    @Override
    public void renewHeartbeat(String nodeId, long timeoutMs) {
        RedisCommands<String, String> sync = connection.sync();
        String heartbeatKey = HEARTBEAT_PREFIX + nodeId;
        String now = String.valueOf(System.currentTimeMillis());

        // Renew the TTL
        sync.set(heartbeatKey, now, SetArgs.Builder.px(timeoutMs));
        // Update the timestamp in the nodes hash
        sync.hset(NODES_KEY, nodeId, now);
    }

    @Override
    public void deregister(String nodeId) {
        RedisCommands<String, String> sync = connection.sync();

        sync.del(HEARTBEAT_PREFIX + nodeId);
        sync.hdel(NODES_KEY, nodeId);

        log.debug("Node {} deregistered from cluster", nodeId);
    }

    @Override
    public List<String> findExpiredNodes(long timeoutMs) {
        RedisCommands<String, String> sync = connection.sync();
        Map<String, String> nodes = sync.hgetall(NODES_KEY);
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }

        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();

        for (Map.Entry<String, String> entry : nodes.entrySet()) {
            String nodeId = entry.getKey();
            try {
                long lastHeartbeat = Long.parseLong(entry.getValue());
                if (now - lastHeartbeat > timeoutMs) {
                    // Double-check: is the heartbeat key actually gone?
                    Boolean exists = sync.exists(HEARTBEAT_PREFIX + nodeId) > 0;
                    if (!exists) {
                        expired.add(nodeId);
                    }
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid heartbeat timestamp for node {}: {}", nodeId, entry.getValue());
            }
        }

        return expired;
    }
}
