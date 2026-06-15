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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisPresenceRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceChangeListener;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceStatus;
import com.github.berrywang1996.netty.spring.web.websocket.context.AbstractMessage;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The RC3 headline: a hard crash of the node hosting a user's ONLY device must still deliver a {@code →OFFLINE}
 * presence event to watchers on other nodes — the dominant crash path the design review flagged as the BLOCKER.
 *
 * <p>Two real {@link ClusterMessageSender}s (node-A, node-B) share one Redis, both subscribed to the reserved
 * presence channel. User {@code u}'s only device connects on node-A → node-B's listener observes {@code ONLINE}.
 * We then simulate a HARD crash of node-A (no graceful clearPresence) by driving node-B's reconciliation reap of
 * node-A directly. node-B's listener must receive {@code u: ONLINE→OFFLINE} and {@code getPresence(u) == OFFLINE}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PresenceCrashReapE2ETest {

    private static String REDIS_URI;
    private static boolean redisAvailable;
    private static final String URI = "/ws/presence-e2e";

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
        Assumptions.assumeTrue(redisAvailable, "Redis not available — skipping presence crash-reap E2E");
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
    void hardCrashOfOnlyDevicesNode_deliversOfflineEventToOtherNode() throws Exception {
        try (Node a = new Node("A"); Node b = new Node("B")) {
            a.start();
            b.start();

            // u's ONLY device connects on node-A via the sender's hook entry → the OFFLINE→ONLINE transition
            // fires the local listener + publishes on the reserved channel (mirrors the connect hook).
            a.sender.setPresenceFromHook("u", "A", "s1", PresenceStatus.ONLINE).toCompletableFuture().join();

            assertTrue(waitFor(() -> b.listener.contains("u|OFFLINE|ONLINE"), 4000),
                    "node-B must observe u coming ONLINE");
            assertEquals(PresenceStatus.ONLINE, b.presenceRegistry.getPresence("u").toCompletableFuture().join().getAggregate());

            // HARD CRASH of node-A: do NOT call clearPresence (a kill -9 never does). node-B's reconciliation
            // detects the dead node and reaps node-A's presence — emitting the authoritative →OFFLINE event.
            b.sender.reapPresenceForDeadNode("A").toCompletableFuture().join();

            assertTrue(waitFor(() -> b.listener.contains("u|ONLINE|OFFLINE"), 4000),
                    "node-B must receive the reap-emitted u: ONLINE→OFFLINE event (the headline)");
            assertEquals(PresenceStatus.OFFLINE, b.presenceRegistry.getPresence("u").toCompletableFuture().join().getAggregate(),
                    "u is OFFLINE cluster-wide after the dead node is reaped");
            assertTrue(b.sender.getClusterRuntimeStats().getPresenceReapOffline() >= 1,
                    "the crash-path correction meter moved");
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

    /** A presence-enabled cluster node sharing one Redis. */
    private static final class Node implements AutoCloseable {
        final String id;
        final RedisClient client;
        final StatefulRedisConnection<String, String> conn;
        final RedisPubSubBroker broker;
        final RedisSessionRegistry sessionRegistry;
        final RedisPresenceRegistry presenceRegistry;
        final RedisClusterNodeHeartbeat heartbeat;
        final ClusterNodeManager nodeManager;
        final RecordingLocalSender local = new RecordingLocalSender();
        final ClusterMessageSender sender;
        final RecordingListener listener = new RecordingListener();

        Node(String id) {
            this.id = id;
            this.client = RedisClient.create(REDIS_URI);
            this.conn = client.connect();
            this.broker = new RedisPubSubBroker(client, new SimpleTextEnvelopeCodec());
            this.sessionRegistry = new RedisSessionRegistry(conn);
            this.presenceRegistry = new RedisPresenceRegistry(conn);
            this.heartbeat = new RedisClusterNodeHeartbeat(conn);
            this.nodeManager = new ClusterNodeManager(id, 1000, 5000, 10000, 0, heartbeat, sessionRegistry);
            this.local.addUri(URI);
            this.sender = new ClusterMessageSender(local, broker, sessionRegistry, nodeManager, 5000);
            this.sender.setPresenceRegistry(presenceRegistry);
            this.sender.setPresenceChangeListener(listener);
            this.nodeManager.setPresenceReaper(sender::reapPresenceForDeadNode);
        }

        void start() {
            nodeManager.start();
            sender.start();
        }

        @Override
        public void close() {
            try { sender.shutdown(); } catch (Exception ignored) {}
            try { nodeManager.shutdown(); } catch (Exception ignored) {}
            try { presenceRegistry.shutdown(); } catch (Exception ignored) {}
            try { broker.shutdown(); } catch (Exception ignored) {}
            try { conn.close(); } catch (Exception ignored) {}
            try { client.shutdown(); } catch (Exception ignored) {}
        }
    }

    /** Records each presence transition (userId|old|new) the node observes. */
    static final class RecordingListener implements PresenceChangeListener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        @Override public void onPresenceChange(String userId, PresenceStatus oldAgg, PresenceStatus newAgg) {
            events.add(userId + "|" + oldAgg + "|" + newAgg);
        }
        boolean contains(String e) { return events.contains(e); }
    }

    /** Minimal local sender — presence E2E only needs getRegisteredUri(). */
    static final class RecordingLocalSender implements MessageSender {
        final Set<String> uris = ConcurrentHashMap.newKeySet();
        void addUri(String uri) { uris.add(uri); }
        @Override public int getSessionNums() { return 0; }
        @Override public int getSessionNums(String uri) { return 0; }
        @Override public Set<String> getRegisteredUri() { return Collections.unmodifiableSet(uris); }
        @Override public boolean isSessionAlive(String uri, String... sessionIds) { return true; }
        @Override public MessageSession getSession(String uri, String sessionId) { return null; }
        @Override public void sendMessage(String uri, AbstractMessage message, String... sessionIds)
                throws MessageUriNotDefinedException, MessageSessionClosedException { }
        @Override public void topicMessage(String uri, AbstractMessage message) throws MessageUriNotDefinedException { }
    }
}
