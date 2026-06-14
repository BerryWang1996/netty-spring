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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisPubSubBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisSessionRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.HandshakeUserIdResolver;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisOfflineQueueStore;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisUserRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two-node end-to-end offline delivery against real Redis.
 *
 * <p><b>Headline:</b> node A {@code sendToUser(alice, ...)} while alice is offline cluster-wide → the
 * messages are enqueued; alice connects on node B → the hook drains + backfills them FIFO to her session →
 * the queue is empty afterward.
 *
 * <p><b>Bind→drain-window case:</b> a message enqueued in the window between bind and drain is still
 * delivered exactly once (the drain reads the whole stream up to the tail, so the window message is in the
 * read set; it is not lost and not duplicated). Skipped (not failed) without Redis.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OfflineDeliveryE2ETest {

    private static String REDIS_URI;
    private static boolean redisAvailable;
    private static final String URI = "/ws/offline-e2e";

    @BeforeAll
    static void check() {
        redisAvailable = ClusterTestRedis.available();
        if (redisAvailable) {
            REDIS_URI = ClusterTestRedis.uri();
            flush();
        }
    }

    @BeforeEach
    void requireRedis() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available — skipping offline E2E");
        flush();
    }

    private static void flush() {
        RedisClient c = RedisClient.create(REDIS_URI);
        StatefulRedisConnection<String, String> conn = c.connect();
        conn.sync().eval("for _,k in ipairs(redis.call('keys','netty:*')) do redis.call('del',k) end",
                ScriptOutputType.INTEGER);
        conn.close();
        c.shutdown();
    }

    @Test
    @Order(1)
    void sendToOfflineUser_thenConnectOnNodeB_backfilledFifo_queueEmptyAfter() throws Exception {
        try (Node a = new Node("A"); Node b = new Node("B")) {
            a.start();
            b.start();

            // alice is offline cluster-wide. Node A sends her three messages → all enqueued.
            a.sender.sendToUser("alice", new TextMessage("m1"));
            a.sender.sendToUser("alice", new TextMessage("m2"));
            a.sender.sendToUser("alice", new TextMessage("m3"));
            assertEquals(3, a.sender.getClusterRuntimeStats().getSendToUserQueued(), "all three queued (offline)");

            // alice connects on node B as session sB → the hook resolves+binds+drains FIFO.
            b.local.addLocalSession(URI, "sB");
            MessageSession sB = sessionFor(URI + "?userId=alice", "sB");
            try {
                b.hook.onSessionRegistered(sB, URI);
            } finally {
                sB.release();
            }

            assertTrue(waitFor(() -> b.local.texts("sB").size() == 3, 4000),
                    "alice must be backfilled all 3 offline messages on connect");
            assertEquals(List.of("m1", "m2", "m3"), b.local.texts("sB"), "FIFO order preserved");

            // The queue is drained empty (a fresh drain on a separate device sees nothing).
            assertTrue(b.offlineStore.drain("alice").toCompletableFuture().join().isEmpty(),
                    "queue must be empty after backfill+delete");
        }
    }

    @Test
    @Order(2)
    void messageEnqueuedDuringBindDrainWindow_deliveredExactlyOnce() throws Exception {
        try (Node a = new Node("A"); Node b = new Node("B")) {
            a.start();
            b.start();

            // Pre-queue one message while alice is offline.
            a.sender.sendToUser("alice", new TextMessage("before-bind"));

            // alice connects on B. We bind first, then enqueue a window message BEFORE the drain reads, to
            // model the bind→drain window. The drain reads the whole stream up to the tail, so BOTH the
            // pre-bind message and the window message must be delivered, each exactly once.
            b.local.addLocalSession(URI, "sB");
            MessageSession sB = sessionFor(URI + "?userId=alice", "sB");
            try {
                // Bind presence (as the hook does), THEN enqueue the window message, THEN drain — exactly the
                // ordering the §5 window note describes.
                b.userRegistry.bindUser("alice", URI, "sB", "B").toCompletableFuture().join();
                a.sender.getClusterRuntimeStats(); // (no-op; keeps A referenced)
                b.offlineStore.enqueue("alice",
                        b.unicastEnvelope(new TextMessage("in-window"))).toCompletableFuture().join();

                // Now run the hook's drain path (drain → deliver → delete).
                List<com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.StoredMessage> drained =
                        b.offlineStore.drain("alice").toCompletableFuture().join();
                List<String> ids = new ArrayList<>();
                for (var m : drained) {
                    b.sender.deliverOfflineMessage(URI, "sB", m);
                    ids.add(m.getId());
                }
                b.offlineStore.delete("alice", ids).toCompletableFuture().join();
            } finally {
                sB.release();
            }

            List<String> got = b.local.texts("sB");
            assertEquals(2, got.size(), "both the pre-bind and the in-window message delivered");
            assertTrue(got.contains("before-bind"));
            assertTrue(got.contains("in-window"));
            // Exactly once: no message text appears twice.
            assertEquals(new HashSet<>(got).size(), got.size(), "no duplicate delivery");
            assertTrue(b.offlineStore.drain("alice").toCompletableFuture().join().isEmpty(), "queue empty after");
        }
    }

    // ============================== scaffolding ==============================

    private static boolean waitFor(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return cond.getAsBoolean();
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

    /** A full offline-enabled cluster node sharing one Redis. */
    private static final class Node implements AutoCloseable {
        final String id;
        final RedisClient client;
        final StatefulRedisConnection<String, String> conn;
        final RedisPubSubBroker broker;
        final RedisSessionRegistry sessionRegistry;
        final RedisUserRegistry userRegistry;
        final RedisOfflineQueueStore offlineStore;
        final RedisClusterNodeHeartbeat heartbeat;
        final ClusterNodeManager nodeManager;
        final RecordingLocalSender local = new RecordingLocalSender();
        final ClusterMessageSender sender;
        final CoalescingRegistryWriter writer;
        final ClusterSessionHookImpl hook;
        private final SimpleTextEnvelopeCodec codec = new SimpleTextEnvelopeCodec();

        Node(String id) {
            this.id = id;
            this.client = RedisClient.create(REDIS_URI);
            this.conn = client.connect();
            this.broker = new RedisPubSubBroker(client, new SimpleTextEnvelopeCodec());
            this.sessionRegistry = new RedisSessionRegistry(conn);
            this.userRegistry = new RedisUserRegistry(conn);
            this.offlineStore = new RedisOfflineQueueStore(conn, codec, id, 1000, 604800, 5000);
            this.heartbeat = new RedisClusterNodeHeartbeat(conn);
            this.nodeManager = new ClusterNodeManager(id, 1000, 5000, 10000, 0, heartbeat, sessionRegistry);
            this.local.addUri(URI);
            this.sender = new ClusterMessageSender(local, broker, sessionRegistry, nodeManager, 5000);
            this.sender.setUserRegistry(userRegistry);
            this.sender.setOfflineQueueStore(offlineStore);
            this.writer = new CoalescingRegistryWriter(sessionRegistry, 0, 50L, id);
            this.hook = new ClusterSessionHookImpl(writer, nodeManager, sender, true,
                    new HandshakeUserIdResolver("query:userId"), userRegistry, offlineStore);
        }

        void start() {
            nodeManager.start();
            sender.start();
            writer.start();
        }

        com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope unicastEnvelope(AbstractMessage m) {
            return new com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope(
                    id, URI, com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope.MessageKind.UNICAST,
                    new com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.DefaultMessagePayloadCodec().encode(m),
                    null, null, System.currentTimeMillis());
        }

        @Override
        public void close() {
            try { writer.shutdown(); } catch (Exception ignored) {}
            try { sender.shutdown(); } catch (Exception ignored) {}
            try { nodeManager.shutdown(); } catch (Exception ignored) {}
            try { offlineStore.shutdown(); } catch (Exception ignored) {}
            try { userRegistry.shutdown(); } catch (Exception ignored) {}
            try { broker.shutdown(); } catch (Exception ignored) {}
            try { conn.close(); } catch (Exception ignored) {}
            try { client.shutdown(); } catch (Exception ignored) {}
        }
    }

    /** Local sender recording the text delivered to each session (only delivers to known-local sessions). */
    static final class RecordingLocalSender implements MessageSender {
        final Set<String> uris = ConcurrentHashMap.newKeySet();
        final Set<String> localSessions = ConcurrentHashMap.newKeySet();
        final Map<String, List<String>> textsBySession = new ConcurrentHashMap<>();

        void addUri(String uri) { uris.add(uri); }
        void addLocalSession(String uri, String sessionId) { localSessions.add(sessionId); }
        List<String> texts(String sessionId) {
            return textsBySession.getOrDefault(sessionId, Collections.emptyList());
        }

        @Override public int getSessionNums() { return 0; }
        @Override public int getSessionNums(String uri) { return 0; }
        @Override public Set<String> getRegisteredUri() { return Collections.unmodifiableSet(uris); }
        @Override public boolean isSessionAlive(String uri, String... sessionIds) { return true; }

        @Override
        public MessageSession getSession(String uri, String sessionId) {
            return localSessions.contains(sessionId) ? sessionFor(uri, sessionId) : null;
        }

        @Override
        public void sendMessage(String uri, AbstractMessage message, String... sessionIds)
                throws MessageUriNotDefinedException, MessageSessionClosedException {
            List<String> closed = new ArrayList<>();
            for (String sid : sessionIds) {
                if (localSessions.contains(sid)) {
                    String text = message instanceof TextMessage ? ((TextMessage) message).getContent()
                            : String.valueOf(message);
                    textsBySession.computeIfAbsent(sid, k -> Collections.synchronizedList(new ArrayList<>())).add(text);
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
}
