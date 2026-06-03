package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.NodeState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterReaper;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisPubSubBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisSessionRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.*;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests against a real Redis 7.x instance (Docker: redis-7-standalone on port 16379).
 *
 * <p>Tests the full chain: RedisPubSubBroker + RedisSessionRegistry +
 * RedisClusterNodeHeartbeat + ClusterNodeManager + ClusterMessageSender.
 *
 * <p>Requires: {@code docker run -d --name redis-7-standalone -p 16379:6379 redis:7.4
 * redis-server --notify-keyspace-events Ex}
 *
 * <p>If Redis is not available, tests are skipped (not failed).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisIntegrationTest {

    private static String REDIS_URI;

    private static RedisClient redisClient;
    private static StatefulRedisConnection<String, String> connection;
    private static boolean redisAvailable;

    @BeforeAll
    static void checkRedis() {
        redisAvailable = ClusterTestRedis.available();
        if (!redisAvailable) {
            System.out.println("No Redis and no Docker — skipping integration tests");
            return;
        }
        REDIS_URI = ClusterTestRedis.uri();
        redisClient = RedisClient.create(REDIS_URI);
        connection = redisClient.connect();
        // Clean up any leftover keys from previous test runs
        connection.sync().eval("for _,k in ipairs(redis.call('keys','netty:*')) do redis.call('del',k) end",
                io.lettuce.core.ScriptOutputType.INTEGER);
    }

    @AfterAll
    static void cleanup() {
        if (connection != null) {
            try {
                connection.sync().eval("for _,k in ipairs(redis.call('keys','netty:*')) do redis.call('del',k) end",
                        io.lettuce.core.ScriptOutputType.INTEGER);
                connection.close();
            } catch (Exception ignored) {}
        }
        if (redisClient != null) {
            try { redisClient.shutdown(); } catch (Exception ignored) {}
        }
    }

    // ==================== 1. Session Registry ====================

    @Test
    @Order(1)
    void sessionRegistryRegisterAndLookup() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisSessionRegistry registry = new RedisSessionRegistry(connection);
        registry.register("/ws/test", "s1", "node-A", Map.of("userId", "u1"))
                .toCompletableFuture().join();

        String nodeId = registry.lookupNode("/ws/test", "s1")
                .toCompletableFuture().join();
        assertEquals("node-A", nodeId);

        Set<String> allIds = registry.clusterSessionIds("/ws/test")
                .toCompletableFuture().join();
        assertTrue(allIds.contains("s1"));

        // Cleanup
        registry.deregister("/ws/test", "s1").toCompletableFuture().join();
        assertNull(registry.lookupNode("/ws/test", "s1").toCompletableFuture().join());
    }

    @Test
    @Order(2)
    void sessionRegistryRemoveAllForNode() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisSessionRegistry registry = new RedisSessionRegistry(connection);
        registry.register("/ws/a", "s1", "dead-node", Map.of()).toCompletableFuture().join();
        registry.register("/ws/a", "s2", "dead-node", Map.of()).toCompletableFuture().join();
        registry.register("/ws/b", "s3", "dead-node", Map.of()).toCompletableFuture().join();

        registry.removeAllForNode("dead-node").toCompletableFuture().join();

        assertNull(registry.lookupNode("/ws/a", "s1").toCompletableFuture().join());
        assertNull(registry.lookupNode("/ws/a", "s2").toCompletableFuture().join());
        assertNull(registry.lookupNode("/ws/b", "s3").toCompletableFuture().join());
    }

    // ==================== 2. Heartbeat ====================

    @Test
    @Order(3)
    void heartbeatRegisterRenewAndExpiry() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisClusterNodeHeartbeat hb = new RedisClusterNodeHeartbeat(connection);
        hb.register("test-node", 2000); // 2s TTL

        // Should not be expired yet
        List<String> expired = hb.findExpiredNodes(2000);
        assertFalse(expired.contains("test-node"));

        // Renew
        hb.renewHeartbeat("test-node", 2000);

        // Wait for expiry
        Thread.sleep(2500);
        expired = hb.findExpiredNodes(2000);
        assertTrue(expired.contains("test-node"), "Node should be detected as expired after TTL");

        hb.deregister("test-node");
    }

    @Test
    @Order(11)
    void findExpiredNodesBatchesMultipleSimultaneousExpiries() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisClusterNodeHeartbeat hb = new RedisClusterNodeHeartbeat(connection);
        // three nodes with a short TTL → all expire together
        hb.register("batch-A", 1000);
        hb.register("batch-B", 1000);
        hb.register("batch-C", 1000);
        // one node renewed long → must NOT be reported expired
        hb.register("batch-live", 60000);

        Thread.sleep(1300); // let A/B/C TTLs lapse

        List<String> expired = hb.findExpiredNodes(1000);
        assertTrue(expired.contains("batch-A"));
        assertTrue(expired.contains("batch-B"));
        assertTrue(expired.contains("batch-C"));
        assertFalse(expired.contains("batch-live"), "a freshly-renewed node must be excluded");

        hb.deregister("batch-A"); hb.deregister("batch-B");
        hb.deregister("batch-C"); hb.deregister("batch-live");
    }

    @Test
    @Order(12)
    void reapClaimElectsSingleWinner() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisClusterReaper r1 = new RedisClusterReaper(connection);
        RedisClusterReaper r2 = new RedisClusterReaper(connection);

        boolean w1 = r1.tryClaim("dead-X", "node-1", 5000);
        boolean w2 = r2.tryClaim("dead-X", "node-2", 5000);

        assertTrue(w1, "first claimant wins");
        assertFalse(w2, "second claimant is locked out within the window");
        assertTrue(w1 ^ w2, "exactly one winner");

        connection.sync().del("netty:cluster:reaping:dead-X");
    }

    @Test
    @Order(13)
    void deregisterIsAtomicAndCleansNodeSet() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisSessionRegistry registry = new RedisSessionRegistry(connection);
        registry.register("/ws/lua", "s1", "node-L", Map.of()).toCompletableFuture().join();
        assertTrue(connection.sync().sismember("netty:node:node-L:sessions", "/ws/lua|s1"),
                "precondition: node-set has the member");

        registry.deregister("/ws/lua", "s1").toCompletableFuture().join();

        assertNull(registry.lookupNode("/ws/lua", "s1").toCompletableFuture().join(),
                "session hash deleted");
        assertFalse(connection.sync().sismember("netty:node:node-L:sessions", "/ws/lua|s1"),
                "Lua deregister must SREM the node-set member atomically (no orphan)");
    }

    // ==================== 3. Pub/Sub Broker — Broadcast + Self-Delivery Suppression ====================

    @Test
    @Order(4)
    void brokerBroadcastAndSelfSuppression() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        // Create two brokers simulating two nodes
        RedisClient client1 = RedisClient.create(REDIS_URI);
        RedisClient client2 = RedisClient.create(REDIS_URI);
        RedisPubSubBroker broker1 = new RedisPubSubBroker(client1, new SimpleTextEnvelopeCodec());
        RedisPubSubBroker broker2 = new RedisPubSubBroker(client2, new SimpleTextEnvelopeCodec());

        CountDownLatch node2Received = new CountDownLatch(1);
        List<ClusterEnvelope> node2Messages = new CopyOnWriteArrayList<>();

        CountDownLatch node1SelfReceived = new CountDownLatch(1);
        List<ClusterEnvelope> node1SelfMessages = new CopyOnWriteArrayList<>();

        // Node 2 subscribes
        broker2.subscribe("/ws/chat", envelope -> {
            node2Messages.add(envelope);
            node2Received.countDown();
        });

        // Node 1 subscribes (to observe self-delivery)
        broker1.subscribe("/ws/chat", envelope -> {
            node1SelfMessages.add(envelope);
            node1SelfReceived.countDown();
        });

        Thread.sleep(500); // Give subscriptions time to propagate

        // Node 1 publishes
        ClusterEnvelope envelope = new ClusterEnvelope(
                "node-1", "/ws/chat", ClusterEnvelope.MessageKind.BROADCAST,
                "T:hello cluster".getBytes(), null, null, System.currentTimeMillis());
        broker1.publish("/ws/chat", envelope);

        // Node 2 should receive
        assertTrue(node2Received.await(5, TimeUnit.SECONDS), "Node 2 should receive the broadcast");
        assertEquals(1, node2Messages.size());
        assertEquals("node-1", node2Messages.get(0).getOriginNodeId());

        // Node 1 also receives (Redis delivers to all subscribers including publisher)
        assertTrue(node1SelfReceived.await(5, TimeUnit.SECONDS));
        assertEquals(1, node1SelfMessages.size());
        // Verify the self-delivery suppression check would work:
        assertEquals("node-1", node1SelfMessages.get(0).getOriginNodeId());
        // ^ In ClusterMessageSender, this would be discarded because originNodeId == local nodeId

        broker1.shutdown();
        broker2.shutdown();
        client1.shutdown();
        client2.shutdown();
    }

    // ==================== 4. Unicast Routing ====================

    @Test
    @Order(5)
    void brokerUnicastRouting() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisClient client1 = RedisClient.create(REDIS_URI);
        RedisClient client2 = RedisClient.create(REDIS_URI);
        RedisPubSubBroker broker1 = new RedisPubSubBroker(client1, new SimpleTextEnvelopeCodec());
        RedisPubSubBroker broker2 = new RedisPubSubBroker(client2, new SimpleTextEnvelopeCodec());

        CountDownLatch received = new CountDownLatch(1);
        List<ClusterEnvelope> node2Unicasts = new CopyOnWriteArrayList<>();

        // Node 2 subscribes to its unicast channel
        broker2.subscribeUnicast("node-2", envelope -> {
            node2Unicasts.add(envelope);
            received.countDown();
        });

        Thread.sleep(300);

        // Node 1 sends unicast to node-2
        ClusterEnvelope envelope = new ClusterEnvelope(
                "node-1", "/ws/chat", ClusterEnvelope.MessageKind.UNICAST,
                "T:private msg".getBytes(), "session-B1", null, System.currentTimeMillis());
        broker1.unicast("node-2", envelope);

        assertTrue(received.await(5, TimeUnit.SECONDS), "Node 2 should receive the unicast");
        assertEquals(1, node2Unicasts.size());
        assertEquals("session-B1", node2Unicasts.get(0).getTargetSessionId());

        broker1.shutdown();
        broker2.shutdown();
        client1.shutdown();
        client2.shutdown();
    }

    // ==================== 5. Node Manager Lifecycle ====================

    @Test
    @Order(6)
    void nodeManagerLifecycle() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisSessionRegistry registry = new RedisSessionRegistry(connection);
        RedisClusterNodeHeartbeat heartbeat = new RedisClusterNodeHeartbeat(connection);

        ClusterNodeManager manager = new ClusterNodeManager(
                "lifecycle-node", 1000, 3000, 5000, 10000, heartbeat, registry);

        assertEquals(NodeState.JOINING, manager.getState());
        manager.start();
        assertEquals(NodeState.ACTIVE, manager.getState());

        // Heartbeat should be active
        Thread.sleep(1500);
        List<String> expired = heartbeat.findExpiredNodes(3000);
        assertFalse(expired.contains("lifecycle-node"), "Node should still be alive");

        // Drain
        manager.drain();
        assertEquals(NodeState.DRAINING, manager.getState());

        // Shutdown
        manager.shutdown();
        assertEquals(NodeState.LEFT, manager.getState());
    }

    // ==================== 6. Envelope Version Compatibility ====================

    @Test
    @Order(7)
    void envelopeVersionField() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisClient client = RedisClient.create(REDIS_URI);
        RedisPubSubBroker broker = new RedisPubSubBroker(client, new SimpleTextEnvelopeCodec());

        CountDownLatch received = new CountDownLatch(1);
        List<ClusterEnvelope> messages = new CopyOnWriteArrayList<>();

        broker.subscribe("/ws/ver", envelope -> {
            messages.add(envelope);
            received.countDown();
        });

        Thread.sleep(300);

        // Publish with current version
        ClusterEnvelope envelope = new ClusterEnvelope(
                "ver-node", "/ws/ver", ClusterEnvelope.MessageKind.BROADCAST,
                "T:v1msg".getBytes(), null, null, System.currentTimeMillis());
        assertEquals(ClusterEnvelope.CURRENT_VERSION, envelope.getVersion());
        broker.publish("/ws/ver", envelope);

        assertTrue(received.await(5, TimeUnit.SECONDS));
        assertEquals(1, messages.size());
        assertEquals(1, messages.get(0).getVersion());

        broker.shutdown();
        client.shutdown();
    }

    // ==================== 6b. Inbound size cap (security) ====================

    @Test
    @Order(7)
    void inboundSizeCapDropsOversizedReceivedMessage() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisClient pubClient = RedisClient.create(REDIS_URI);
        RedisClient subClient = RedisClient.create(REDIS_URI);
        RedisPubSubBroker publisher = new RedisPubSubBroker(pubClient, new SimpleTextEnvelopeCodec());
        RedisPubSubBroker subscriber = new RedisPubSubBroker(subClient, new SimpleTextEnvelopeCodec());
        subscriber.setInboundMaxBytes(200); // tiny inbound cap

        AtomicInteger delivered = new AtomicInteger();
        subscriber.subscribe("/ws/cap", envelope -> delivered.incrementAndGet());
        Thread.sleep(300);

        // Small message → delivered
        publisher.publish("/ws/cap", new ClusterEnvelope(
                "n", "/ws/cap", ClusterEnvelope.MessageKind.BROADCAST,
                "T:small".getBytes(), null, null, System.currentTimeMillis()));

        // Oversized message (payload ~5000 chars → encoded envelope well over 200) → dropped at the receiver
        StringBuilder big = new StringBuilder("T:");
        for (int i = 0; i < 5000; i++) big.append('z');
        publisher.publish("/ws/cap", new ClusterEnvelope(
                "n", "/ws/cap", ClusterEnvelope.MessageKind.BROADCAST,
                big.toString().getBytes(), null, null, System.currentTimeMillis()));

        Thread.sleep(800);
        assertEquals(1, delivered.get(), "Only the small message should be delivered; oversized one dropped pre-decode");

        publisher.shutdown();
        subscriber.shutdown();
        pubClient.shutdown();
        subClient.shutdown();
    }

    // ==================== 7. Full ClusterMessageSender End-to-End ====================

    @Test
    @Order(8)
    void fullClusterMessageSenderEndToEnd() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        // --- Set up two "nodes" ---
        RedisClient clientA = RedisClient.create(REDIS_URI);
        RedisClient clientB = RedisClient.create(REDIS_URI);
        StatefulRedisConnection<String, String> connA = clientA.connect();
        StatefulRedisConnection<String, String> connB = clientB.connect();

        RedisPubSubBroker brokerA = new RedisPubSubBroker(clientA, new SimpleTextEnvelopeCodec());
        RedisPubSubBroker brokerB = new RedisPubSubBroker(clientB, new SimpleTextEnvelopeCodec());
        RedisSessionRegistry registryA = new RedisSessionRegistry(connA);
        RedisSessionRegistry registryB = new RedisSessionRegistry(connB);
        RedisClusterNodeHeartbeat hbA = new RedisClusterNodeHeartbeat(connA);
        RedisClusterNodeHeartbeat hbB = new RedisClusterNodeHeartbeat(connB);

        ClusterNodeManager nodeA = new ClusterNodeManager("e2e-A", 1000, 5000, 10000, 30000, hbA, registryA);
        ClusterNodeManager nodeB = new ClusterNodeManager("e2e-B", 1000, 5000, 10000, 30000, hbB, registryB);

        // Recording local senders
        RecordingSender localSenderA = new RecordingSender();
        RecordingSender localSenderB = new RecordingSender();

        ClusterMessageSender senderA = new ClusterMessageSender(localSenderA, brokerA, registryA, nodeA, 5000);
        ClusterMessageSender senderB = new ClusterMessageSender(localSenderB, brokerB, registryB, nodeB, 5000);

        nodeA.start();
        nodeB.start();
        localSenderA.addUri("/ws/e2e");
        localSenderB.addUri("/ws/e2e");
        senderA.start();
        senderB.start();

        Thread.sleep(500); // Let subscriptions settle

        // Register sessions in the registry
        registryA.register("/ws/e2e", "sA1", "e2e-A", Map.of()).toCompletableFuture().join();
        registryB.register("/ws/e2e", "sB1", "e2e-B", Map.of()).toCompletableFuture().join();

        // --- Test broadcast ---
        senderA.topicMessage("/ws/e2e", new TextMessage("broadcast from A"));

        // localSenderA should receive the local fan-out
        assertEquals(1, localSenderA.topicCount.get());

        // Wait for broker delivery to node B
        Thread.sleep(1000);
        // localSenderB should receive from the cluster broadcast
        assertEquals(1, localSenderB.topicCount.get(),
                "Node B should have received the broadcast via Redis Pub/Sub");

        // --- Test cluster session query ---
        Set<String> allSessions = senderA.getClusterSessionIds("/ws/e2e")
                .toCompletableFuture().join();
        assertTrue(allSessions.contains("sA1"));
        assertTrue(allSessions.contains("sB1"));

        // --- Cleanup ---
        senderA.shutdown();
        senderB.shutdown();
        nodeA.shutdown();
        nodeB.shutdown();
        brokerA.shutdown();
        brokerB.shutdown();
        registryA.deregister("/ws/e2e", "sA1").toCompletableFuture().join();
        registryB.deregister("/ws/e2e", "sB1").toCompletableFuture().join();
        connA.close();
        connB.close();
        clientA.shutdown();
        clientB.shutdown();
    }

    // ==================== Helper: Recording local sender ====================

    static class RecordingSender implements MessageSender {
        final AtomicInteger topicCount = new AtomicInteger();
        final AtomicInteger sendCount = new AtomicInteger();
        final Set<String> uris = new HashSet<>();

        void addUri(String uri) { uris.add(uri); }

        @Override public int getSessionNums() { return 0; }
        @Override public int getSessionNums(String uri) { return 0; }
        @Override public Set<String> getRegisteredUri() { return Collections.unmodifiableSet(uris); }
        @Override public boolean isSessionAlive(String uri, String... sessionIds) { return false; }

        @Override
        public void sendMessage(String uri, AbstractMessage message, String... sessionIds)
                throws MessageUriNotDefinedException, MessageSessionClosedException {
            sendCount.incrementAndGet();
        }

        @Override
        public void topicMessage(String uri, AbstractMessage message) throws MessageUriNotDefinedException {
            topicCount.incrementAndGet();
        }
    }
}
