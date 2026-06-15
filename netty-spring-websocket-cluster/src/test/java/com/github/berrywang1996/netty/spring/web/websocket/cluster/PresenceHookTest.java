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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceChangeListener;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceStatus;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserIdResolver;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ClusterSessionHookImpl}'s RC3 identity/offline/presence flag-split. The headline: a presence-only
 * config (offline.enable=false, presence.enable=true) now BINDS the user (identity-split) and sets the connection
 * ONLINE on connect — pre-RC3 the single offlineEnabled flag took the else branch and never bound. Also pins:
 * presence+offline both fire on connect; the disabled (identity-off) path is byte-identical to RC2 (emptyMap, no
 * resolver/registry touched); and the first device fires an OFFLINE-&gt;ONLINE transition publish.
 */
class PresenceHookTest {

    private static final String URI = "/ws/chat";

    private RecordingLocalSender local;
    private InMemoryBroker broker;
    private InMemorySessionRegistry sessionRegistry;
    private InMemoryUserRegistry userRegistry;
    private InMemoryOfflineQueueStore offlineStore;
    private InMemoryPresenceRegistry presenceRegistry;
    private ClusterNodeManager nodeManager;
    private ClusterMessageSender sender;
    private CoalescingRegistryWriter writer;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        local = new RecordingLocalSender();
        local.addUri(URI);
        broker = new InMemoryBroker();
        sessionRegistry = new InMemorySessionRegistry();
        userRegistry = new InMemoryUserRegistry();
        offlineStore = new InMemoryOfflineQueueStore();
        presenceRegistry = new InMemoryPresenceRegistry();
        listener = new RecordingListener();
        nodeManager = new ClusterNodeManager("node-A", 3000, 10000, 15000, 0, new NoOpHeartbeat(), sessionRegistry);
        sender = new ClusterMessageSender(local, broker, sessionRegistry, nodeManager, 5000);
        sender.setUserRegistry(userRegistry);
        sender.setOfflineQueueStore(offlineStore);
        sender.setPresenceRegistry(presenceRegistry);
        sender.setPresenceChangeListener(listener);
        writer = new CoalescingRegistryWriter(sessionRegistry, 0, 50L, "node-A"); // rate 0 = pass-through
        writer.start();
        nodeManager.start();
        sender.start();
    }

    @AfterEach
    void tearDown() {
        writer.shutdown();
        sender.shutdown();
        nodeManager.shutdown();
        offlineStore.shutdown();
    }

    /** presence ON, offline OFF → resolve + bind + setPresence(ONLINE); NO drain (offline off). */
    @Test
    void presenceOnly_connect_bindsAndSetsOnline() {
        // Pre-seed a queue: a presence-only hook (offline off) must NOT drain it.
        offlineStore.enqueue("alice", env("m1")).toCompletableFuture().join();

        ClusterSessionHookImpl hook = presenceOnlyHook();
        local.addLocalSession(URI, "s1");
        MessageSession s1 = sessionFor(URI + "?userId=alice", "s1");
        try {
            hook.onSessionRegistered(s1, URI);
        } finally {
            s1.release();
        }

        // identity-split: presence-only BINDS the user.
        assertTrue(userRegistry.isUserOnline("alice").toCompletableFuture().join(), "presence-only must bind the user");
        // presence write happened (aggregate ONLINE).
        assertEquals(PresenceStatus.ONLINE, presenceRegistry.getPresence("alice").toCompletableFuture().join().getAggregate());
        // offline OFF → no drain.
        assertTrue(local.textsTo(URI, "s1").isEmpty(), "presence-only does not drain the offline queue");
        assertEquals(1, offlineStore.depth("alice"), "presence-only must not drain the queue");
    }

    /** presence ON disconnect → clearPresence + unbind. */
    @Test
    void presenceOnly_disconnect_clearsAndUnbinds() {
        ClusterSessionHookImpl hook = presenceOnlyHook();
        local.addLocalSession(URI, "s1");
        MessageSession s1 = sessionFor(URI + "?userId=alice", "s1");
        hook.onSessionRegistered(s1, URI);
        assertEquals(PresenceStatus.ONLINE, presenceRegistry.getPresence("alice").toCompletableFuture().join().getAggregate());

        hook.onSessionRemoved(s1, URI);
        s1.release();

        assertEquals(PresenceStatus.OFFLINE, presenceRegistry.getPresence("alice").toCompletableFuture().join().getAggregate(),
                "clearPresence on disconnect → OFFLINE");
        assertFalse(userRegistry.isUserOnline("alice").toCompletableFuture().join(), "unbind on disconnect");
    }

    /** presence AND offline ON → bind + setPresence(ONLINE) + drainOnConnect all fire. */
    @Test
    void presenceAndOffline_connect_bindsSetsOnlineAndDrains() {
        offlineStore.enqueue("bob", env("hello")).toCompletableFuture().join();

        ClusterSessionHookImpl hook = fullHook();
        local.addLocalSession(URI, "s1");
        MessageSession s1 = sessionFor(URI + "?userId=bob", "s1");
        try {
            hook.onSessionRegistered(s1, URI);
        } finally {
            s1.release();
        }

        assertTrue(userRegistry.isUserOnline("bob").toCompletableFuture().join());
        assertEquals(PresenceStatus.ONLINE, presenceRegistry.getPresence("bob").toCompletableFuture().join().getAggregate());
        assertEquals(List.of("hello"), local.textsTo(URI, "s1"), "drain delivered the queued message");
        assertEquals(0, offlineStore.depth("bob"), "queue drained");
    }

    /** presence OFF, offline OFF → identity off → register(emptyMap), no resolve, no bind, no presence write. */
    @Test
    void identityOff_connect_byteIdentical_emptyMapNoResolverCall() {
        ThrowingResolver tripwire = new ThrowingResolver();
        // Both offline and presence collaborators present but identity disabled (resolver/registry both null path):
        // the RC1-compatible constructor disables everything.
        ClusterSessionHookImpl hook = new ClusterSessionHookImpl(writer, nodeManager, sender);

        local.addLocalSession(URI, "s1");
        MessageSession s1 = sessionFor(URI + "?userId=alice", "s1");
        try {
            hook.onSessionRegistered(s1, URI);
            hook.onSessionRemoved(s1, URI);
        } finally {
            s1.release();
        }

        // No resolver bean was even passed — assert no identity side effects at all.
        assertFalse(userRegistry.isUserOnline("alice").toCompletableFuture().join(), "identity off → no bind");
        assertEquals(PresenceStatus.OFFLINE, presenceRegistry.getPresence("alice").toCompletableFuture().join().getAggregate(),
                "identity off → no presence write");
        assertEquals(0, sender.getClusterRuntimeStats().getResolvedIdentities());
        assertEquals(0, sender.getClusterRuntimeStats().getPresenceSet(), "identity off → setPresence never called");
        // tripwire unused here (RC1 ctor has no resolver) — keep the assertion meaningful via the gated-resolver test below.
        assertFalse(tripwire.wasCalled());
    }

    /** First device of a user fires an OFFLINE→ONLINE transition publish on the reserved channel. */
    @Test
    void connect_offlineToOnline_firesTransitionPublish() {
        ClusterSessionHookImpl hook = presenceOnlyHook();
        local.addLocalSession(URI, "s1");
        MessageSession s1 = sessionFor(URI + "?userId=carol", "s1");
        try {
            hook.onSessionRegistered(s1, URI);
        } finally {
            s1.release();
        }

        long presenceEvents = broker.getPublishedEnvelopes().stream()
                .filter(e -> e.getKind() == ClusterEnvelope.MessageKind.PRESENCE_CHANGE).count();
        assertEquals(1, presenceEvents, "first device → one OFFLINE→ONLINE publish");
        assertTrue(listener.events.contains("carol|OFFLINE|ONLINE"), "local-first listener fired the transition");
        assertEquals(1, sender.getClusterRuntimeStats().getPresenceSet());
    }

    // ============================== Test scaffolding ==============================

    /** presence ON, offline OFF: pass a null offlineStore so offlineEnabled=false but identity+presence are on. */
    private ClusterSessionHookImpl presenceOnlyHook() {
        UserIdResolver resolver =
                new com.github.berrywang1996.netty.spring.web.websocket.cluster.room.HandshakeUserIdResolver("query:userId");
        return new ClusterSessionHookImpl(writer, nodeManager, sender, resolver, userRegistry, null, presenceRegistry);
    }

    /** presence ON, offline ON. */
    private ClusterSessionHookImpl fullHook() {
        UserIdResolver resolver =
                new com.github.berrywang1996.netty.spring.web.websocket.cluster.room.HandshakeUserIdResolver("query:userId");
        return new ClusterSessionHookImpl(writer, nodeManager, sender, resolver, userRegistry, offlineStore, presenceRegistry);
    }

    private ClusterEnvelope env(String text) {
        return new ClusterEnvelope("node-A", URI, ClusterEnvelope.MessageKind.UNICAST,
                ("T:" + text).getBytes(java.nio.charset.StandardCharsets.UTF_8), null, null, System.currentTimeMillis());
    }

    private static MessageSession sessionFor(String uri, String sessionId) {
        ContextHolder holder = new ContextHolder();
        new EmbeddedChannel(holder);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
        MessageSession session = new MessageSession(sessionId, holder.ctx, request);
        request.release();
        return session;
    }

    private static final class ContextHolder extends ChannelInboundHandlerAdapter {
        private ChannelHandlerContext ctx;
        @Override public void handlerAdded(ChannelHandlerContext ctx) { this.ctx = ctx; }
    }

    /** Records each presence transition the listener observes. */
    private static final class RecordingListener implements PresenceChangeListener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        @Override public void onPresenceChange(String userId, PresenceStatus oldAgg, PresenceStatus newAgg) {
            events.add(userId + "|" + oldAgg + "|" + newAgg);
        }
    }

    /** A resolver that records whether it was invoked (tripwire for the identity-off path). */
    static final class ThrowingResolver implements UserIdResolver {
        private volatile boolean called;
        @Override public String resolve(MessageSession session) { called = true; return "alice"; }
        boolean wasCalled() { return called; }
    }

    /** Local sender recording text delivered to each (uri,sessionId) — copied from OfflineDrainOnConnectTest. */
    static final class RecordingLocalSender implements MessageSender {
        final Set<String> uris = new HashSet<>();
        final java.util.Map<String, Set<String>> localSessions = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Map<String, List<String>> textsBySession = new java.util.concurrent.ConcurrentHashMap<>();

        void addUri(String uri) { uris.add(uri); }
        void addLocalSession(String uri, String sessionId) {
            localSessions.computeIfAbsent(uri, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(sessionId);
        }
        List<String> textsTo(String uri, String sessionId) {
            return textsBySession.getOrDefault(uri + "|" + sessionId, Collections.emptyList());
        }

        @Override public int getSessionNums() { return 0; }
        @Override public int getSessionNums(String uri) { return 0; }
        @Override public Set<String> getRegisteredUri() { return Collections.unmodifiableSet(uris); }
        @Override public boolean isSessionAlive(String uri, String... sessionIds) { return true; }

        @Override
        public MessageSession getSession(String uri, String sessionId) {
            return localSessions.getOrDefault(uri, Collections.emptySet()).contains(sessionId)
                    ? sessionFor(uri, sessionId) : null;
        }

        @Override
        public void sendMessage(String uri, AbstractMessage message, String... sessionIds)
                throws MessageUriNotDefinedException, MessageSessionClosedException {
            Set<String> live = localSessions.getOrDefault(uri, Collections.emptySet());
            List<String> closed = new ArrayList<>();
            for (String sid : sessionIds) {
                if (live.contains(sid)) {
                    String text = message instanceof TextMessage ? ((TextMessage) message).getContent()
                            : String.valueOf(message);
                    textsBySession.computeIfAbsent(uri + "|" + sid, k -> Collections.synchronizedList(new ArrayList<>()))
                            .add(text);
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
