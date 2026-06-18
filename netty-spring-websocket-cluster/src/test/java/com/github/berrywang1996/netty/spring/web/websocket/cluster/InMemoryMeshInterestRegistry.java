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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MeshInterestRegistry;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link MeshInterestRegistry} stub (test scope) — mirrors {@link InMemoryRoomRegistry} minus the room
 * dimension. {@code sessionsByNode}: (uri,node) &rarr; sessionIds; {@code nodeSet}: uri &rarr; nodeIds (a node is in
 * the set iff it has &ge;1 session — added on first, removed on last — the invariant the Redis Lua enforces).
 */
public class InMemoryMeshInterestRegistry implements MeshInterestRegistry {

    private final ConcurrentHashMap<String, Set<String>> sessionsByNode = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> nodeSet = new ConcurrentHashMap<>();

    private static String k(String a, String b) {
        return a + " " + b;
    }

    @Override
    public synchronized CompletionStage<Void> subscribe(String uri, String sessionId, String nodeId) {
        Set<String> sessions = sessionsByNode.computeIfAbsent(k(uri, nodeId), x -> ConcurrentHashMap.newKeySet());
        boolean wasEmpty = sessions.isEmpty();
        sessions.add(sessionId);
        if (wasEmpty) {
            nodeSet.computeIfAbsent(uri, x -> ConcurrentHashMap.newKeySet()).add(nodeId);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> unsubscribe(String uri, String sessionId, String nodeId) {
        Set<String> sessions = sessionsByNode.get(k(uri, nodeId));
        if (sessions != null) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                sessionsByNode.remove(k(uri, nodeId));
                Set<String> ns = nodeSet.get(uri);
                if (ns != null) {
                    ns.remove(nodeId);
                    if (ns.isEmpty()) {
                        nodeSet.remove(uri);
                    }
                }
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Set<String>> nodesForUri(String uri) {
        Set<String> ns = nodeSet.get(uri);
        return CompletableFuture.completedFuture(ns == null ? Collections.emptySet() : new HashSet<>(ns));
    }

    @Override
    public synchronized CompletionStage<Void> removeAllForNode(String nodeId) {
        String suffix = " " + nodeId;
        sessionsByNode.keySet().removeIf(key -> {
            if (key.endsWith(suffix)) {
                String uri = key.substring(0, key.length() - suffix.length());
                Set<String> ns = nodeSet.get(uri);
                if (ns != null) {
                    ns.remove(nodeId);
                    if (ns.isEmpty()) {
                        nodeSet.remove(uri);
                    }
                }
                return true;
            }
            return false;
        });
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void shutdown() {
        sessionsByNode.clear();
        nodeSet.clear();
    }
}
