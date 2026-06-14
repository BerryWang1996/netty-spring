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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link ClusterMessageSender#sendToUser} with in-memory SPI stubs (no Lettuce): online → realtime
 * unicast; offline (zero sessions cluster-wide) → enqueued for backfill; a session that closes at send time
 * (no reachable session remains) → fallback enqueue. Also asserts {@code sessionsForUser} is hit FRESH on
 * each call (no cache) and that the realtime/queued/unicast-failure meters move.
 */
class ClusterSendToUserTest {

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
    void onlineUserGetsRealtimeUnicast_notEnqueued() {
        // alice has a live LOCAL session s1 → online.
        local.addLocalSession(URI, "s1");
        userRegistry.bindUser("alice", URI, "s1", "node-A").toCompletableFuture().join();

        sender.sendToUser("alice", new TextMessage("hello"));

        assertEquals(1, local.deliveries(URI, "s1"), "delivered realtime to the live session");
        assertEquals(0, offlineStore.depth("alice"), "online → NOT enqueued");
        assertEquals(1, sender.getClusterRuntimeStats().getSendToUserRealtime());
        assertEquals(0, sender.getClusterRuntimeStats().getSendToUserQueued());
    }

    @Test
    void offlineUserIsEnqueuedForBackfill() {
        // alice has no session anywhere → offline.
        assertFalse(userRegistry.isUserOnline("alice").toCompletableFuture().join());

        sender.sendToUser("alice", new TextMessage("ping"));

        assertEquals(1, offlineStore.depth("alice"), "offline → enqueued");
        assertEquals(0, local.deliveries(URI, "s1"));
        assertEquals(1, sender.getClusterRuntimeStats().getSendToUserQueued());
        assertEquals(1, sender.getClusterRuntimeStats().getOfflineEnqueued());
        assertEquals(0, sender.getClusterRuntimeStats().getSendToUserRealtime());
    }

    @Test
    void localCloseAtSendTimeFallsBackToEnqueue() {
        // alice is "bound" (presence says online) but the LOCAL session was already removed → sendMessage
        // throws MessageSessionClosedException; no reachable session remains → send-time fallback enqueue.
        userRegistry.bindUser("alice", URI, "s1", "node-A").toCompletableFuture().join();
        // NOTE: we deliberately do NOT register s1 as a local session, so the local send closes.

        sender.sendToUser("alice", new TextMessage("ping"));

        assertEquals(1, offlineStore.depth("alice"), "all sessions unreachable → fallback enqueue");
        assertEquals(1, sender.getClusterRuntimeStats().getUnicastFailures());
        assertEquals(1, sender.getClusterRuntimeStats().getSendToUserQueued());
        assertEquals(0, sender.getClusterRuntimeStats().getSendToUserRealtime());
    }

    @Test
    void sessionsForUserHitFreshEachCall_noCache() {
        sender.sendToUser("alice", new TextMessage("a"));
        sender.sendToUser("alice", new TextMessage("b"));
        // Each sendToUser does exactly one sessionsForUser lookup — never cached.
        assertEquals(2, userRegistry.sessionsForUserCalls(), "sessionsForUser must be hit fresh per send");
    }

    @Test
    void disabledThrows() {
        ClusterMessageSender plain = new ClusterMessageSender(local, broker, sessionRegistry, nodeManager, 5000);
        plain.start();
        try {
            assertThrows(IllegalStateException.class, () -> plain.sendToUser("alice", new TextMessage("x")));
            assertThrows(IllegalStateException.class, () -> plain.isUserOnline("alice"));
        } finally {
            plain.shutdown();
        }
    }

    // ============================== Test scaffolding ==============================

    /** InMemoryUserRegistry that counts sessionsForUser invocations (proves the no-cache invariant). */
    static final class CountingUserRegistry extends InMemoryUserRegistry {
        private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public java.util.concurrent.CompletionStage<Set<com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRef>>
        sessionsForUser(String userId) {
            calls.incrementAndGet();
            return super.sessionsForUser(userId);
        }

        int sessionsForUserCalls() {
            return calls.get();
        }
    }

    /** Local sender recording deliveries; an unknown sessionId throws MessageSessionClosedException. */
    static final class RecordingLocalSender implements MessageSender {
        final Set<String> uris = new HashSet<>();
        final Map<String, Set<String>> localSessions = new ConcurrentHashMap<>();
        final Map<String, Integer> deliveriesBySession = new ConcurrentHashMap<>();

        void addUri(String uri) { uris.add(uri); }
        void addLocalSession(String uri, String sessionId) {
            localSessions.computeIfAbsent(uri, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
        }

        int deliveries(String uri, String sessionId) {
            return deliveriesBySession.getOrDefault(uri + "|" + sessionId, 0);
        }

        @Override public int getSessionNums() { return 0; }
        @Override public int getSessionNums(String uri) { return 0; }
        @Override public Set<String> getRegisteredUri() { return Collections.unmodifiableSet(uris); }
        @Override public boolean isSessionAlive(String uri, String... sessionIds) { return true; }

        @Override
        public MessageSession getSession(String uri, String sessionId) {
            // Returning a non-null session only when it is local makes ClusterMessageSender route it as a
            // LOCAL send (otherwise it would try a registry lookup → remote). null = not local.
            return localSessions.getOrDefault(uri, Collections.emptySet()).contains(sessionId)
                    ? localSession(uri, sessionId) : null;
        }

        @Override
        public void sendMessage(String uri, AbstractMessage message, String... sessionIds)
                throws MessageUriNotDefinedException, MessageSessionClosedException {
            Set<String> live = localSessions.getOrDefault(uri, Collections.emptySet());
            List<String> closed = new ArrayList<>();
            for (String sid : sessionIds) {
                if (live.contains(sid)) {
                    deliveriesBySession.merge(uri + "|" + sid, 1, Integer::sum);
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

    /** Builds a real {@link MessageSession} over an EmbeddedChannel — ClusterMessageSender only null-checks
     *  the returned session, so a lightweight real one is sufficient (and avoids proxying the concrete class). */
    private static MessageSession localSession(String uri, String sessionId) {
        ContextHolder holder = new ContextHolder();
        new EmbeddedChannel(holder);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
        MessageSession session = new MessageSession(sessionId, holder.ctx, request);
        request.release();
        return session;
    }

    private static final class ContextHolder extends ChannelInboundHandlerAdapter {
        private ChannelHandlerContext ctx;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }
    }

    static final class NoOpHeartbeat implements ClusterNodeHeartbeat {
        @Override public void register(String nodeId, long timeoutMs) {}
        @Override public void renewHeartbeat(String nodeId, long timeoutMs) {}
        @Override public void deregister(String nodeId) {}
        @Override public List<String> findExpiredNodes(long timeoutMs) { return Collections.emptyList(); }
    }
}
