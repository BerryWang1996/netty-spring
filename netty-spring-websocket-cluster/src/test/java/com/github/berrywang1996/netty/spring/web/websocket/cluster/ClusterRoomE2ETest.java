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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.HmacMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisPubSubBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisSessionRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisRoomRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two-node (plus a third bystander) end-to-end test of per-room node-targeted delivery against real Redis.
 *
 * <p><b>Headline reduction assertion:</b> node A {@code roomMessage(R)} where R has a member on B → B
 * delivers to its local member; a third node C that does NOT host R receives nothing (it was never in the
 * room's node-set, so it was never targeted). Plus origin self-suppression and HMAC-on.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClusterRoomE2ETest {

    private static String REDIS_URI;
    private static boolean redisAvailable;
    private static final String URI = "/ws/room-e2e";
    private static final byte[] SECRET = "room-e2e-cluster-secret-32+chars!!".getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    static void check() {
        redisAvailable = ClusterTestRedis.available();
        if (redisAvailable) {
            REDIS_URI = ClusterTestRedis.uri();
            RedisClient c = RedisClient.create(REDIS_URI);
            StatefulRedisConnection<String, String> conn = c.connect();
            conn.sync().eval("for _,k in ipairs(redis.call('keys','netty:*')) do redis.call('del',k) end",
                    ScriptOutputType.INTEGER);
            conn.close();
            c.shutdown();
        }
    }

    @BeforeEach
    void requireRedis() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available — skipping room E2E");
    }

    @Test
    @Order(1)
    void roomMessageReachesNodeHostingMember_butNotABystanderNode() throws Exception {
        try (Cluster cl = new Cluster("A", "B", "C")) {
            Node a = cl.node("A"), b = cl.node("B"), c = cl.node("C");

            // A has sA in room "r1"; B has sB in room "r1"; C hosts NO member of r1.
            a.local.addLocalSession(URI, "sA");
            b.local.addLocalSession(URI, "sB");
            a.room.join(URI, "r1", "sA", "A").toCompletableFuture().join();
            b.room.join(URI, "r1", "sB", "B").toCompletableFuture().join();

            Thread.sleep(500); // let SUBSCRIBE settle on all unicast channels

            a.sender.roomMessage(URI, "r1", new TextMessage("hello room r1"));

            // A delivered to its local member sA (local-first fan-out).
            assertTrue(waitFor(() -> a.local.count("sA") == 1, 3000), "origin A must deliver to its local sA");
            // B (in the node-set) received via its unicast channel and delivered to sB.
            assertTrue(waitFor(() -> b.local.count("sB") == 1, 3000), "node B (hosts a member) must receive");
            // C (NOT in the node-set) must NOT have been targeted at all → zero deliveries. THE REDUCTION.
            Thread.sleep(500);
            assertEquals(0, c.local.totalDeliveries(), "bystander node C must receive nothing");
            assertEquals(0, c.sender.getClusterRuntimeStats().getRoomBroadcastReceived(),
                    "C must not even receive a ROOM_BROADCAST envelope");

            // A's fan-out targeted exactly 1 remote node (B), excluding self.
            assertEquals(1, a.sender.getClusterRuntimeStats().getRoomFanoutTargetsLast());
            // Origin self-suppression: A did not double-deliver to sA.
            assertEquals(1, a.local.count("sA"));
        }
    }

    @Test
    @Order(2)
    void hmacOn_roomMessageStillDelivers() throws Exception {
        try (Cluster cl = new Cluster(true, "A", "B")) {
            Node a = cl.node("A"), b = cl.node("B");
            b.local.addLocalSession(URI, "sB");
            b.room.join(URI, "r2", "sB", "B").toCompletableFuture().join();
            Thread.sleep(500);

            a.sender.roomMessage(URI, "r2", new TextMessage("signed room msg"));

            assertTrue(waitFor(() -> b.local.count("sB") == 1, 3000),
                    "with HMAC on, the signed ROOM_BROADCAST must still reach B");
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

    /** A set of full cluster nodes sharing one Redis, each with its own client/connection/broker/registry. */
    private static final class Cluster implements AutoCloseable {
        final Map<String, Node> nodes = new LinkedHashMap<>();

        Cluster(String... ids) {
            this(false, ids);
        }

        Cluster(boolean hmac, String... ids) {
            for (String id : ids) {
                nodes.put(id, new Node(id, hmac));
            }
            // start everything, then let subscriptions settle (caller sleeps)
            nodes.values().forEach(Node::start);
        }

        Node node(String id) {
            return nodes.get(id);
        }

        @Override
        public void close() {
            nodes.values().forEach(Node::shutdown);
        }
    }

    private static final class Node {
        final String id;
        final RedisClient client;
        final StatefulRedisConnection<String, String> conn;
        final RedisPubSubBroker broker;
        final RedisSessionRegistry sessionRegistry;
        final RedisRoomRegistry room;
        final RedisClusterNodeHeartbeat heartbeat;
        final ClusterNodeManager nodeManager;
        final RecordingLocalSender local = new RecordingLocalSender();
        final ClusterMessageSender sender;

        Node(String id, boolean hmac) {
            this.id = id;
            this.client = RedisClient.create(REDIS_URI);
            this.conn = client.connect();
            this.broker = hmac
                    ? new RedisPubSubBroker(client, new SimpleTextEnvelopeCodec(),
                            new HmacMessageAuthenticator(SECRET, true), 1)
                    : new RedisPubSubBroker(client, new SimpleTextEnvelopeCodec());
            this.sessionRegistry = new RedisSessionRegistry(conn);
            this.room = new RedisRoomRegistry(conn);
            this.heartbeat = new RedisClusterNodeHeartbeat(conn);
            this.nodeManager = new ClusterNodeManager(id, 1000, 5000, 10000, 0, heartbeat, sessionRegistry);
            this.local.addUri(URI);
            this.sender = new ClusterMessageSender(local, broker, sessionRegistry, nodeManager, 5000);
            this.sender.setRoomRegistry(room);
        }

        void start() {
            nodeManager.start();
            sender.start();
        }

        void shutdown() {
            try { sender.shutdown(); } catch (Exception ignored) {}
            try { nodeManager.shutdown(); } catch (Exception ignored) {}
            try { room.shutdown(); } catch (Exception ignored) {}
            try { broker.shutdown(); } catch (Exception ignored) {}
            try { conn.close(); } catch (Exception ignored) {}
            try { client.shutdown(); } catch (Exception ignored) {}
        }
    }

    /** Local sender that records per-session delivery counts (only delivers to known-local sessions). */
    static final class RecordingLocalSender implements MessageSender {
        final Set<String> uris = ConcurrentHashMap.newKeySet();
        final Set<String> localSessions = ConcurrentHashMap.newKeySet();
        final Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();

        void addUri(String uri) { uris.add(uri); }
        void addLocalSession(String uri, String sessionId) { localSessions.add(sessionId); }
        int count(String sessionId) {
            AtomicInteger c = deliveries.get(sessionId);
            return c == null ? 0 : c.get();
        }
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
}
