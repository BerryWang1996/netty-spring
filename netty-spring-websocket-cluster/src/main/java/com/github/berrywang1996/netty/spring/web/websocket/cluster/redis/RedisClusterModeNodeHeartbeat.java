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
import io.lettuce.core.RedisFuture;
import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis-Cluster (topology) implementation of {@link ClusterNodeHeartbeat}. Identical key design and
 * logic to {@code RedisClusterNodeHeartbeat} — every operation is single-key (slot-routed) including
 * the per-key {@code EXISTS} reconciliation, so it is slot-safe as-is; only the connection type differs.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class RedisClusterModeNodeHeartbeat implements ClusterNodeHeartbeat {

    private static final String HEARTBEAT_PREFIX = "netty:cluster:heartbeat:";
    private static final String NODES_KEY = "netty:cluster:nodes";

    private final StatefulRedisClusterConnection<String, String> connection;

    public RedisClusterModeNodeHeartbeat(StatefulRedisClusterConnection<String, String> connection) {
        this.connection = connection;
    }

    @Override
    public void register(String nodeId, long timeoutMs) {
        RedisAdvancedClusterCommands<String, String> sync = connection.sync();
        String now = String.valueOf(System.currentTimeMillis());
        sync.set(HEARTBEAT_PREFIX + nodeId, now, SetArgs.Builder.px(timeoutMs));
        sync.hset(NODES_KEY, nodeId, now);
        log.debug("Node {} registered (cluster mode) with heartbeat TTL {}ms", nodeId, timeoutMs);
    }

    @Override
    public void renewHeartbeat(String nodeId, long timeoutMs) {
        RedisAdvancedClusterCommands<String, String> sync = connection.sync();
        String now = String.valueOf(System.currentTimeMillis());
        sync.set(HEARTBEAT_PREFIX + nodeId, now, SetArgs.Builder.px(timeoutMs));
        sync.hset(NODES_KEY, nodeId, now);
    }

    @Override
    public void deregister(String nodeId) {
        RedisAdvancedClusterCommands<String, String> sync = connection.sync();
        sync.del(HEARTBEAT_PREFIX + nodeId);
        sync.hdel(NODES_KEY, nodeId);
        log.debug("Node {} deregistered (cluster mode)", nodeId);
    }

    @Override
    public List<String> findExpiredNodes(long timeoutMs) {
        RedisAdvancedClusterCommands<String, String> sync = connection.sync();
        Map<String, String> nodes = sync.hgetall(NODES_KEY);
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, String> e : nodes.entrySet()) {
            try {
                if (now - Long.parseLong(e.getValue()) > timeoutMs) {
                    candidates.add(e.getKey());
                }
            } catch (NumberFormatException ex) {
                log.warn("Invalid heartbeat timestamp for node {}: {}", e.getKey(), e.getValue());
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        RedisAdvancedClusterAsyncCommands<String, String> async = connection.async();
        Map<String, RedisFuture<Long>> existsFutures = new LinkedHashMap<>();
        for (String nodeId : candidates) {
            existsFutures.put(nodeId, async.exists(HEARTBEAT_PREFIX + nodeId)); // per-key EXISTS — slot-safe
        }
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, RedisFuture<Long>> e : existsFutures.entrySet()) {
            try {
                Long exists = e.getValue().get(timeoutMs, TimeUnit.MILLISECONDS);
                if (exists != null && exists == 0L) {
                    expired.add(e.getKey());
                }
            } catch (Exception ex) {
                log.debug("EXISTS check failed for candidate node {} (cluster mode)", e.getKey(), ex);
            }
        }
        return expired;
    }
}
