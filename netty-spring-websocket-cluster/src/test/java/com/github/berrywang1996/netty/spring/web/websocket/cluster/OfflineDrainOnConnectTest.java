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

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link ClusterSessionHookImpl}'s RC2 resolve + bind + drain-on-connect path with in-memory stubs.
 * Headline: a user with queued offline messages reconnects → the hook resolves the userId, binds presence,
 * drains the queue FIFO to the new session, and acks (queue empty after). Also pins the byte-identical
 * disabled path (offline.enable=false → emptyMap metadata, no resolver/registry/store touched).
 */
class OfflineDrainOnConnectTest {

    private static final String URI = "/ws/chat";

    private RecordingLocalSender local;
    private InMemoryBroker broker;
    private InMemorySessionRegistry sessionRegistry;
    private InMemoryUserRegistry userRegistry;
    private InMemoryOfflineQueueStore offlineStore;
    private ClusterNodeManager nodeManager;
    private ClusterMessageSender sender;
    private CoalescingRegistryWriter writer;

    @BeforeEach
    void setUp() {
        local = new RecordingLocalSender();
        local.addUri(URI);
        broker = new InMemoryBroker();
        sessionRegistry = new InMemorySessionRegistry();
        userRegistry = new InMemoryUserRegistry();
        offlineStore = new InMemoryOfflineQueueStore();
        nodeManager = new ClusterNodeManager("node-A", 3000, 10000, 15000, 0, new NoOpHeartbeat(), sessionRegistry);
        sender = new ClusterMessageSender(local, broker, sessionRegistry, nodeManager, 5000);
        sender.setUserRegistry(userRegistry);
        sender.setOfflineQueueStore(offlineStore);
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

    @Test
    void resolvesBindsAndDrainsFifoOnConnect() {
        // alice has 3 queued offline messages while she was disconnected.
        offlineStore.enqueue("alice", env("m1")).toCompletableFuture().join();
        offlineStore.enqueue("alice", env("m2")).toCompletableFuture().join();
        offlineStore.enqueue("alice", env("m3")).toCompletableFuture().join();

        ClusterSessionHookImpl hook = enabledHook();

        // alice reconnects on this node as session s1.
        local.addLocalSession(URI, "s1");
        MessageSession s1 = sessionFor(URI + "?userId=alice", "s1");
        try {
            hook.onSessionRegistered(s1, URI);
        } finally {
            s1.release();
        }

        // Backfilled FIFO to the new session, queue drained + lock released.
        assertEquals(List.of("m1", "m2", "m3"), local.textsTo(URI, "s1"));
        assertEquals(0, offlineStore.depth("alice"), "queue must be empty after drain+delete");
        assertFalse(offlineStore.isLocked("alice"), "drain lock released after delete");
        // Presence bound + identity counted.
        assertTrue(userRegistry.isUserOnline("alice").toCompletableFuture().join());
        assertEquals(1, sender.getClusterRuntimeStats().getResolvedIdentities());
        assertEquals(3, sender.getClusterRuntimeStats().getOfflineDrained());
    }

    @Test
    void noQueuedMessages_bindsButDeliversNothing() {
        ClusterSessionHookImpl hook = enabledHook();
        local.addLocalSession(URI, "s1");
        MessageSession s1 = sessionFor(URI + "?userId=bob", "s1");
        try {
            hook.onSessionRegistered(s1, URI);
        } finally {
            s1.release();
        }
        assertTrue(local.textsTo(URI, "s1").isEmpty());
        assertTrue(userRegistry.isUserOnline("bob").toCompletableFuture().join());
    }

    @Test
    void onSessionRemovedUnbindsUser() {
        ClusterSessionHookImpl hook = enabledHook();
        local.addLocalSession(URI, "s1");
        MessageSession s1 = sessionFor(URI + "?userId=alice", "s1");
        hook.onSessionRegistered(s1, URI);
        assertTrue(userRegistry.isUserOnline("alice").toCompletableFuture().join());

        hook.onSessionRemoved(s1, URI);
        s1.release();
        assertFalse(userRegistry.isUserOnline("alice").toCompletableFuture().join(), "unbind on disconnect");
    }

    @Test
    void anonymousSession_emptyMapNoBind() {
        ClusterSessionHookImpl hook = enabledHook();
        local.addLocalSession(URI, "s1");
        // No userId query param → resolver returns null → anonymous.
        MessageSession s1 = sessionFor(URI, "s1");
        try {
            hook.onSessionRegistered(s1, URI);
        } finally {
            s1.release();
        }
        // No presence binding, no offline delivery, the unresolved meter moved.
        assertTrue(local.textsTo(URI, "s1").isEmpty());
        assertEquals(0, sender.getClusterRuntimeStats().getResolvedIdentities());
        assertEquals(1, sender.getClusterRuntimeStats().getUnresolvedSessions());
    }

    @Test
    void disabledOfflinePath_isByteIdentical_emptyMapNoResolverCall() {
        // The RC1-compatible constructor (offline disabled): the resolver/registry/store are never touched.
        ThrowingResolver tripwire = new ThrowingResolver();
        ClusterSessionHookImpl hook = new ClusterSessionHookImpl(writer, nodeManager, sender,
                false, tripwire, userRegistry, offlineStore);
        // Pre-seed a queue: a disabled hook must NOT drain it.
        offlineStore.enqueue("alice", env("m1")).toCompletableFuture().join();

        local.addLocalSession(URI, "s1");
        MessageSession s1 = sessionFor(URI + "?userId=alice", "s1");
        try {
            hook.onSessionRegistered(s1, URI);
            hook.onSessionRemoved(s1, URI);
        } finally {
            s1.release();
        }

        assertFalse(tripwire.wasCalled(), "disabled path must NOT call the resolver (RC1-identical)");
        assertTrue(local.textsTo(URI, "s1").isEmpty(), "disabled path delivers no backfill");
        assertEquals(1, offlineStore.depth("alice"), "disabled path must not drain the queue");
        assertFalse(userRegistry.isUserOnline("alice").toCompletableFuture().join(), "disabled path does not bind");
    }

    // ============================== Test scaffolding ==============================

    private ClusterSessionHookImpl enabledHook() {
        UserIdResolver resolver =
                new com.github.berrywang1996.netty.spring.web.websocket.cluster.room.HandshakeUserIdResolver("query:userId");
        return new ClusterSessionHookImpl(writer, nodeManager, sender, true, resolver, userRegistry, offlineStore);
    }

    private ClusterEnvelope env(String text) {
        return new ClusterEnvelope("node-A", URI, ClusterEnvelope.MessageKind.UNICAST,
                ("T:" + text).getBytes(StandardCharsets.UTF_8), null, null, System.currentTimeMillis());
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

    /** A resolver that records whether it was invoked (tripwire for the disabled-path test). */
    static final class ThrowingResolver implements UserIdResolver {
        private volatile boolean called;
        @Override public String resolve(MessageSession session) { called = true; return "alice"; }
        boolean wasCalled() { return called; }
    }

    /** Local sender recording the text delivered to each (uri,sessionId). The DefaultMessagePayloadCodec
     *  encodes a TextMessage as "T:<text>"; the cluster sender decodes it back to a TextMessage on delivery. */
    static final class RecordingLocalSender implements MessageSender {
        final Set<String> uris = new HashSet<>();
        final Map<String, Set<String>> localSessions = new ConcurrentHashMap<>();
        final Map<String, List<String>> textsBySession = new ConcurrentHashMap<>();

        void addUri(String uri) { uris.add(uri); }
        void addLocalSession(String uri, String sessionId) {
            localSessions.computeIfAbsent(uri, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
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
