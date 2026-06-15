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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceStatus;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceTransition;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserPresence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process {@link PresenceRegistry} stub (test scope) mirroring {@code RedisPresenceRegistry}'s transition
 * semantics with per-userId atomicity. Field = {@code nodeId|sessionId}; aggregate derived via
 * {@link UserPresence#aggregateOf}. Reads are uncached (snapshot each call).
 */
public class InMemoryPresenceRegistry implements PresenceRegistry {

    /** userId -> (field "nodeId|sessionId" -> status). */
    private final Map<String, Map<String, PresenceStatus>> users = new ConcurrentHashMap<>();

    private static String field(String nodeId, String sessionId) {
        return nodeId + "|" + sessionId;
    }

    private static PresenceStatus aggregate(Map<String, PresenceStatus> conns) {
        int online = 0;
        int away = 0;
        for (PresenceStatus s : conns.values()) {
            if (s == PresenceStatus.ONLINE) {
                online++;
            } else {
                away++;
            }
        }
        return UserPresence.aggregateOf(online, away);
    }

    @Override
    public CompletionStage<PresenceTransition> setPresence(String userId, String nodeId, String sessionId, PresenceStatus status) {
        Map<String, PresenceStatus> conns = users.computeIfAbsent(userId, k -> new LinkedHashMap<>());
        synchronized (conns) {
            PresenceStatus old = aggregate(conns);
            conns.put(field(nodeId, sessionId), status);
            PresenceStatus now = aggregate(conns);
            return CompletableFuture.completedFuture(new PresenceTransition(null, old, now));
        }
    }

    @Override
    public CompletionStage<PresenceTransition> setPresenceForUser(String userId, PresenceStatus status) {
        Map<String, PresenceStatus> conns = users.computeIfAbsent(userId, k -> new LinkedHashMap<>());
        synchronized (conns) {
            PresenceStatus old = aggregate(conns);
            for (String f : new ArrayList<>(conns.keySet())) {
                conns.put(f, status);
            }
            PresenceStatus now = aggregate(conns);
            return CompletableFuture.completedFuture(new PresenceTransition(null, old, now));
        }
    }

    @Override
    public CompletionStage<PresenceTransition> clearPresence(String userId, String nodeId, String sessionId) {
        Map<String, PresenceStatus> conns = users.computeIfAbsent(userId, k -> new LinkedHashMap<>());
        synchronized (conns) {
            PresenceStatus old = aggregate(conns);
            conns.remove(field(nodeId, sessionId));
            PresenceStatus now = aggregate(conns);
            if (conns.isEmpty()) {
                users.remove(userId, conns);
            }
            return CompletableFuture.completedFuture(new PresenceTransition(null, old, now));
        }
    }

    @Override
    public CompletionStage<UserPresence> getPresence(String userId) {
        Map<String, PresenceStatus> conns = users.get(userId);
        if (conns == null) {
            return CompletableFuture.completedFuture(new UserPresence(PresenceStatus.OFFLINE, 0, 0));
        }
        synchronized (conns) {
            int online = 0;
            int away = 0;
            for (PresenceStatus s : conns.values()) {
                if (s == PresenceStatus.ONLINE) {
                    online++;
                } else {
                    away++;
                }
            }
            return CompletableFuture.completedFuture(new UserPresence(UserPresence.aggregateOf(online, away), online, away));
        }
    }

    @Override
    public CompletionStage<List<PresenceTransition>> removeAllForNode(String nodeId) {
        String prefix = nodeId + "|";
        List<PresenceTransition> changed = new ArrayList<>();
        for (Map.Entry<String, Map<String, PresenceStatus>> e : users.entrySet()) {
            Map<String, PresenceStatus> conns = e.getValue();
            synchronized (conns) {
                PresenceStatus old = aggregate(conns);
                conns.keySet().removeIf(f -> f.startsWith(prefix));
                PresenceStatus now = aggregate(conns);
                if (old != now) {
                    changed.add(new PresenceTransition(e.getKey(), old, now));
                }
            }
        }
        users.entrySet().removeIf(e -> e.getValue().isEmpty());
        return CompletableFuture.completedFuture(changed);
    }

    @Override
    public void shutdown() {
        // no-op
    }
}
