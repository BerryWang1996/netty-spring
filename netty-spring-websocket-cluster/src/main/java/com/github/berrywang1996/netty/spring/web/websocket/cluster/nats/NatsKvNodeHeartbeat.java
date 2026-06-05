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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.nats;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import io.nats.client.KeyValue;
import io.nats.client.api.KeyValueEntry;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * NATS JetStream-KV implementation of {@link ClusterNodeHeartbeat} (bucket {@code netty-nodes}). Liveness is
 * timestamp-based (no reliance on KV maxAge purge timing): each key is {@code nodeId} → last-seen millis. A node
 * whose timestamp is older than the timeout is reported expired (and reaped/cleaned by reconciliation, exactly
 * like the Redis nodes-hash timestamp check). Keys are raw nodeIds (exact-key ops only).
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class NatsKvNodeHeartbeat implements ClusterNodeHeartbeat {

    private final KeyValue kv;

    public NatsKvNodeHeartbeat(KeyValue kv) {
        this.kv = kv;
    }

    @Override
    public void register(String nodeId, long timeoutMs) {
        renewHeartbeat(nodeId, timeoutMs);
    }

    @Override
    public void renewHeartbeat(String nodeId, long timeoutMs) {
        try {
            kv.put(nodeId, String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("NATS KV heartbeat put failed for node {}", nodeId, e);
        }
    }

    @Override
    public void deregister(String nodeId) {
        try {
            kv.delete(nodeId);
        } catch (Exception e) {
            log.warn("NATS KV heartbeat delete failed for node {}", nodeId, e);
        }
    }

    @Override
    public List<String> findExpiredNodes(long timeoutMs) {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        try {
            for (String nodeId : kv.keys()) {
                KeyValueEntry e = kv.get(nodeId);
                if (e == null) {
                    continue;
                }
                try {
                    if (now - Long.parseLong(e.getValueAsString()) > timeoutMs) {
                        expired.add(nodeId);
                    }
                } catch (NumberFormatException nfe) {
                    log.warn("Invalid heartbeat timestamp for node {}: {}", nodeId, e.getValueAsString());
                }
            }
        } catch (Exception ex) {
            log.warn("NATS KV findExpiredNodes failed", ex);
        }
        return expired;
    }
}
