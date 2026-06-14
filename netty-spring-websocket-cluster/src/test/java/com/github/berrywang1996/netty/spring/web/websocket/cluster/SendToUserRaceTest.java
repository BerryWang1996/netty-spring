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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The send-time race test: a user who just disconnected (no online cache — {@code sessionsForUser} hits the
 * store fresh) gets enqueued, NOT a fire-and-forget unicast to a stale session; and a LOCAL mid-send close on
 * a still-"bound" session falls back to enqueue. Uses in-memory stubs so the no-cache read is directly
 * observable (the counting registry proves every send issues a fresh lookup).
 */
class SendToUserRaceTest {

    private static final String URI = "/ws/chat";

    private RecordingLocalSender local;
    private InMemoryBroker broker;
    private InMemorySessionRegistry sessionRegistry;
    private CountingUserRegistry userRegistry;
    private InMemoryOfflineQueueStore offlineStore;
    private ClusterNodeManager nodeManager;
    private ClusterMessageSender sender;

    @BeforeEach
    void setUp() {
        local = new RecordingLocalSender();
        local.addUri(URI);
        broker = new InMemoryBroker();
        sessionRegistry = new InMemorySessionRegistry();
        userRegistry = new CountingUserRegistry();
        offlineStore = new InMemoryOfflineQueueStore();
        nodeManager = new ClusterNodeManager("node-A", 3000, 10000, 15000, 0, new NoOpHeartbeat(), sessionRegistry);
        sender = new ClusterMessageSender(local, broker, sessionRegistry, nodeManager, 5000);
        sender.setUserRegistry(userRegistry);
        sender.setOfflineQueueStore(offlineStore);
        nodeManager.start();
        sender.start();
    }

    @AfterEach
    void tearDown() {
        sender.shutdown();
        nodeManager.shutdown();
        offlineStore.shutdown();
    }

    @Test
    void justDisconnectedUser_freshLookupSeesOffline_enqueuedNotUnicastToStale() {
        // alice connects then disconnects: the registry NO LONGER lists her (no stale cache).
        userRegistry.bindUser("alice", URI, "s1", "node-A").toCompletableFuture().join();
        userRegistry.unbindUser("alice", URI, "s1").toCompletableFuture().join();

        sender.sendToUser("alice", new TextMessage("ping"));

        // sessionsForUser was read FRESH (no cache) and saw zero sessions → enqueued, not unicast-to-stale.
        assertEquals(1, offlineStore.depth("alice"), "offline (fresh read) → enqueued");
        assertEquals(0, local.totalDeliveries(), "must NOT unicast to a stale/dead session");
        assertEquals(1, userRegistry.lookups(), "exactly one fresh sessionsForUser lookup for the send");
        assertEquals(1, sender.getClusterRuntimeStats().getSendToUserQueued());
    }

    @Test
    void localMidSendClose_onBoundSession_fallsBackToEnqueue() {
        // alice is bound (presence says online), but her local session was already removed → the local send
        // throws MessageSessionClosedException and no reachable session remains → fallback enqueue.
        userRegistry.bindUser("alice", URI, "s1", "node-A").toCompletableFuture().join();
        // s1 is NOT a live local session → sendMessage closes it.

        sender.sendToUser("alice", new TextMessage("ping"));

        assertEquals(1, offlineStore.depth("alice"), "local mid-send close → fallback enqueue");
        assertEquals(1, sender.getClusterRuntimeStats().getUnicastFailures());
        assertEquals(1, sender.getClusterRuntimeStats().getSendToUserQueued());
        assertEquals(0, sender.getClusterRuntimeStats().getSendToUserRealtime());
    }

    // ============================== scaffolding ==============================

    static final class CountingUserRegistry implements UserRegistry {
        private final InMemoryUserRegistry delegate = new InMemoryUserRegistry();
        private final AtomicInteger lookups = new AtomicInteger();

        int lookups() { return lookups.get(); }

        @Override public java.util.concurrent.CompletionStage<Void> bindUser(String u, String uri, String s, String n) {
            return delegate.bindUser(u, uri, s, n);
        }
        @Override public java.util.concurrent.CompletionStage<Void> unbindUser(String u, String uri, String s) {
            return delegate.unbindUser(u, uri, s);
        }
        @Override public java.util.concurrent.CompletionStage<Set<SessionRef>> sessionsForUser(String u) {
            lookups.incrementAndGet();
            return delegate.sessionsForUser(u);
        }
        @Override public java.util.concurrent.CompletionStage<Boolean> isUserOnline(String u) {
            return delegate.isUserOnline(u);
        }
        @Override public java.util.concurrent.CompletionStage<Void> removeAllForNode(String n) {
            return delegate.removeAllForNode(n);
        }
        @Override public void shutdown() { delegate.shutdown(); }
    }

    static final class RecordingLocalSender implements MessageSender {
        final Set<String> uris = ConcurrentHashMap.newKeySet();
        final Set<String> localSessions = ConcurrentHashMap.newKeySet();
        final Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();

        void addUri(String uri) { uris.add(uri); }
        int totalDeliveries() {
            int sum = 0;
            for (AtomicInteger c : deliveries.values()) { sum += c.get(); }
            return sum;
        }

        @Override public int getSessionNums() { return 0; }
        @Override public int getSessionNums(String uri) { return 0; }
        @Override public Set<String> getRegisteredUri() { return Collections.unmodifiableSet(uris); }
        @Override public boolean isSessionAlive(String uri, String... sessionIds) { return true; }

        @Override
        public void sendMessage(String uri, AbstractMessage message, String... sessionIds)
                throws MessageUriNotDefinedException, MessageSessionClosedException {
            List<String> closed = new ArrayList<>();
            for (String sid : sessionIds) {
                if (localSessions.contains(sid)) {
                    deliveries.computeIfAbsent(sid, k -> new AtomicInteger()).incrementAndGet();
                } else {
                    closed.add(sid);
                }
            }
            if (!closed.isEmpty()) {
                throw new MessageSessionClosedException(uri, closed);
            }
        }

        @Override
        public void topicMessage(String uri, AbstractMessage message) throws MessageUriNotDefinedException {
        }
    }

    static final class NoOpHeartbeat implements ClusterNodeHeartbeat {
        @Override public void register(String nodeId, long timeoutMs) {}
        @Override public void renewHeartbeat(String nodeId, long timeoutMs) {}
        @Override public void deregister(String nodeId) {}
        @Override public List<String> findExpiredNodes(long timeoutMs) { return Collections.emptyList(); }
    }
}
