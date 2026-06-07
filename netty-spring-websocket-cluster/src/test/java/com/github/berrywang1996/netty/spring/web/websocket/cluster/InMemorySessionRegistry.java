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

package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stub implementation of {@link SessionRegistry} for testing SPI isolation.
 * Proves that {@link ClusterMessageSender} does not depend on Lettuce or Redis.
 */
public class InMemorySessionRegistry implements SessionRegistry {

    /** Key = "uri|sessionId", Value = nodeId. */
    private final ConcurrentHashMap<String, String> sessionToNode = new ConcurrentHashMap<>();
    /** Key = nodeId, Value = set of "uri|sessionId". */
    private final ConcurrentHashMap<String, Set<String>> nodeToSessions = new ConcurrentHashMap<>();
    /** Test helper: counts lookupNode invocations (to prove the degraded short-circuit skips it). */
    private final java.util.concurrent.atomic.AtomicInteger lookupNodeCalls = new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public CompletionStage<Void> register(String uri, String sessionId, String nodeId,
                                          Map<String, String> metadata) {
        String key = uri + "|" + sessionId;
        sessionToNode.put(key, nodeId);
        nodeToSessions.computeIfAbsent(nodeId, k -> ConcurrentHashMap.newKeySet()).add(key);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> deregister(String uri, String sessionId) {
        String key = uri + "|" + sessionId;
        String nodeId = sessionToNode.remove(key);
        if (nodeId != null) {
            Set<String> set = nodeToSessions.get(nodeId);
            if (set != null) set.remove(key);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<String> lookupNode(String uri, String sessionId) {
        lookupNodeCalls.incrementAndGet();
        String key = uri + "|" + sessionId;
        return CompletableFuture.completedFuture(sessionToNode.get(key));
    }

    /** Test helper: number of lookupNode invocations so far. */
    public int getLookupNodeCalls() {
        return lookupNodeCalls.get();
    }

    @Override
    public CompletionStage<Set<String>> clusterSessionIds(String uri) {
        Set<String> result = new HashSet<>();
        String prefix = uri + "|";
        for (String key : sessionToNode.keySet()) {
            if (key.startsWith(prefix)) {
                result.add(key.substring(prefix.length()));
            }
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletionStage<Void> removeAllForNode(String nodeId) {
        Set<String> keys = nodeToSessions.remove(nodeId);
        if (keys != null) {
            for (String key : keys) {
                sessionToNode.remove(key);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void shutdown() {
        sessionToNode.clear();
        nodeToSessions.clear();
    }

    /** Test helper: get the full map for assertions. */
    public Map<String, String> getSessionToNodeMap() {
        return Collections.unmodifiableMap(sessionToNode);
    }
}
