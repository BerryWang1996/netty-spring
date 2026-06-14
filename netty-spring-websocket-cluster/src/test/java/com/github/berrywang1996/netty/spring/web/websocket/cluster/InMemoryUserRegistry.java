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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRef;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserRegistry;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stub {@link UserRegistry} for SPI-isolation tests (no Lettuce / Redis). Proves the sender + hook
 * do not depend on Redis for the user-index path, and serves the {@code sendToUser}/drain unit tests.
 *
 * <p>{@code sessionsForUser} returns a fresh snapshot every call (matches the no-cache contract).
 */
public class InMemoryUserRegistry implements UserRegistry {

    /** userId -> set of session refs. */
    private final ConcurrentHashMap<String, Set<SessionRef>> userToSessions = new ConcurrentHashMap<>();

    @Override
    public CompletionStage<Void> bindUser(String userId, String uri, String sessionId, String nodeId) {
        userToSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                .add(new SessionRef(nodeId, uri, sessionId));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> unbindUser(String userId, String uri, String sessionId) {
        Set<SessionRef> set = userToSessions.get(userId);
        if (set != null) {
            set.removeIf(ref -> ref.getUri().equals(uri) && ref.getSessionId().equals(sessionId));
            if (set.isEmpty()) {
                userToSessions.remove(userId, set);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Set<SessionRef>> sessionsForUser(String userId) {
        Set<SessionRef> set = userToSessions.get(userId);
        // Fresh snapshot every call (no caching) — matches the no-cache offline-detection contract.
        return CompletableFuture.completedFuture(set == null ? new HashSet<>() : new HashSet<>(set));
    }

    @Override
    public CompletionStage<Boolean> isUserOnline(String userId) {
        Set<SessionRef> set = userToSessions.get(userId);
        return CompletableFuture.completedFuture(set != null && !set.isEmpty());
    }

    @Override
    public CompletionStage<Void> removeAllForNode(String nodeId) {
        for (Set<SessionRef> set : userToSessions.values()) {
            set.removeIf(ref -> ref.getNodeId().equals(nodeId));
        }
        userToSessions.values().removeIf(Set::isEmpty);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void shutdown() {
        userToSessions.clear();
    }
}
