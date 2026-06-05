package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisPubSubBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisSessionRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance benchmark comparing:
 * 1. Local-only broadcast (DefaultMessageSender, no Redis) — baseline
 * 2. Cluster broadcast (ClusterMessageSender, Redis Pub/Sub) — measures overhead
 * 3. Redis Pub/Sub raw throughput — how fast the broker can publish/subscribe
 *
 * Requires Redis 7 on localhost:16379.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerformanceBenchmark {

    private static final String REDIS_URI = "redis://localhost:16379";
    private static final int WARMUP = 500;
    private static final int MESSAGES = 5000;
    private static boolean redisAvailable;

    @BeforeAll
    static void checkRedis() {
        try {
            RedisClient c = RedisClient.create(REDIS_URI);
            StatefulRedisConnection<String, String> conn = c.connect();
            conn.sync().ping();
            conn.close();
            c.shutdown();
            redisAvailable = true;
        } catch (Exception e) {
            redisAvailable = false;
        }
    }

    // ==================== 1. Local-only broadcast baseline ====================

    @Test
    @Order(1)
    void localOnlyBroadcastThroughput() {
        CountingSender sender = new CountingSender();
        sender.addUri("/ws/bench");

        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            sender.topicMessage("/ws/bench", new TextMessage("warmup"));
        }
        sender.count.set(0);

        long start = System.nanoTime();
        for (int i = 0; i < MESSAGES; i++) {
            sender.topicMessage("/ws/bench", new TextMessage("msg-" + i));
        }
        long elapsed = System.nanoTime() - start;

        double msgsPerSec = MESSAGES / (elapsed / 1_000_000_000.0);
        double avgLatencyUs = (elapsed / 1_000.0) / MESSAGES;

        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  LOCAL-ONLY BROADCAST (no Redis, no cluster)     ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.printf("║  Messages:     %,d%n", MESSAGES);
        System.out.printf("║  Throughput:   %,.0f msg/s%n", msgsPerSec);
        System.out.printf("║  Avg latency:  %.1f µs/msg%n", avgLatencyUs);
        System.out.printf("║  Total time:   %.1f ms%n", elapsed / 1_000_000.0);
        System.out.println("╚═══════════════════════════════════════════════════╝");
    }

    // ==================== 2. Cluster broadcast (with Redis Pub/Sub) ====================

    @Test
    @Order(2)
    void clusterBroadcastThroughput() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisClient client = RedisClient.create(REDIS_URI);
        StatefulRedisConnection<String, String> conn = client.connect();
        conn.sync().eval("for _,k in ipairs(redis.call('keys','netty:*')) do redis.call('del',k) end",
                io.lettuce.core.ScriptOutputType.INTEGER);

        RedisPubSubBroker broker = new RedisPubSubBroker(client, new SimpleTextEnvelopeCodec());
        RedisSessionRegistry registry = new RedisSessionRegistry(conn);
        RedisClusterNodeHeartbeat hb = new RedisClusterNodeHeartbeat(conn);
        ClusterNodeManager nodeManager = new ClusterNodeManager(
                "bench-node", 5000, 15000, 30000, 0, hb, registry); // drainTimeout=0 → fast shutdown

        CountingSender localSender = new CountingSender();
        localSender.addUri("/ws/bench");

        ClusterMessageSender clusterSender = new ClusterMessageSender(
                localSender, broker, registry, nodeManager, 5000);

        nodeManager.start();
        clusterSender.start();
        Thread.sleep(500);

        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            clusterSender.topicMessage("/ws/bench", new TextMessage("warmup"));
        }
        localSender.count.set(0);
        Thread.sleep(200);

        long start = System.nanoTime();
        for (int i = 0; i < MESSAGES; i++) {
            clusterSender.topicMessage("/ws/bench", new TextMessage("msg-" + i));
        }
        long elapsed = System.nanoTime() - start;

        double msgsPerSec = MESSAGES / (elapsed / 1_000_000_000.0);
        double avgLatencyUs = (elapsed / 1_000.0) / MESSAGES;

        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  CLUSTER BROADCAST (Redis Pub/Sub)               ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.printf("║  Messages:     %,d%n", MESSAGES);
        System.out.printf("║  Throughput:   %,.0f msg/s%n", msgsPerSec);
        System.out.printf("║  Avg latency:  %.1f µs/msg%n", avgLatencyUs);
        System.out.printf("║  Total time:   %.1f ms%n", elapsed / 1_000_000.0);
        System.out.printf("║  Local fan-out count: %,d%n", localSender.count.get());
        System.out.println("╚═══════════════════════════════════════════════════╝");

        clusterSender.shutdown();
        nodeManager.shutdown();
        broker.shutdown();
        conn.close();
        client.shutdown();
    }

    // ==================== 3. Raw Redis Pub/Sub throughput ====================

    @Test
    @Order(3)
    void rawRedisPubSubThroughput() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisClient pubClient = RedisClient.create(REDIS_URI);
        RedisClient subClient = RedisClient.create(REDIS_URI);
        RedisPubSubBroker publisher = new RedisPubSubBroker(pubClient, new SimpleTextEnvelopeCodec());
        RedisPubSubBroker subscriber = new RedisPubSubBroker(subClient, new SimpleTextEnvelopeCodec());

        AtomicInteger received = new AtomicInteger();
        CountDownLatch allReceived = new CountDownLatch(MESSAGES);

        subscriber.subscribe("/ws/raw-bench", envelope -> {
            received.incrementAndGet();
            allReceived.countDown();
        });

        Thread.sleep(300);

        long start = System.nanoTime();
        for (int i = 0; i < MESSAGES; i++) {
            ClusterEnvelope env = new ClusterEnvelope(
                    "pub-node", "/ws/raw-bench", ClusterEnvelope.MessageKind.BROADCAST,
                    ("T:msg-" + i).getBytes(), null, null, System.currentTimeMillis());
            publisher.publish("/ws/raw-bench", env);
        }
        boolean done = allReceived.await(30, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - start;

        double pubRate = MESSAGES / (elapsed / 1_000_000_000.0);
        double avgE2eLatencyUs = (elapsed / 1_000.0) / MESSAGES;

        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  RAW REDIS PUB/SUB THROUGHPUT                    ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.printf("║  Published:    %,d%n", MESSAGES);
        System.out.printf("║  Received:     %,d%n", received.get());
        System.out.printf("║  All received: %s%n", done ? "YES" : "NO (timeout)");
        System.out.printf("║  Throughput:   %,.0f msg/s (publish rate)%n", pubRate);
        System.out.printf("║  Avg E2E:      %.1f µs/msg%n", avgE2eLatencyUs);
        System.out.printf("║  Total time:   %.1f ms%n", elapsed / 1_000_000.0);
        System.out.println("╚═══════════════════════════════════════════════════╝");

        publisher.shutdown();
        subscriber.shutdown();
        pubClient.shutdown();
        subClient.shutdown();
    }

    // ==================== 4. Two-node cluster cross-node delivery latency ====================

    @Test
    @Order(4)
    void twoNodeCrossNodeDeliveryLatency() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisClient clientA = RedisClient.create(REDIS_URI);
        RedisClient clientB = RedisClient.create(REDIS_URI);
        StatefulRedisConnection<String, String> connA = clientA.connect();
        StatefulRedisConnection<String, String> connB = clientB.connect();
        connA.sync().eval("for _,k in ipairs(redis.call('keys','netty:*')) do redis.call('del',k) end",
                io.lettuce.core.ScriptOutputType.INTEGER);

        RedisPubSubBroker brokerA = new RedisPubSubBroker(clientA, new SimpleTextEnvelopeCodec());
        RedisPubSubBroker brokerB = new RedisPubSubBroker(clientB, new SimpleTextEnvelopeCodec());
        RedisSessionRegistry regA = new RedisSessionRegistry(connA);
        RedisSessionRegistry regB = new RedisSessionRegistry(connB);
        RedisClusterNodeHeartbeat hbA = new RedisClusterNodeHeartbeat(connA);
        RedisClusterNodeHeartbeat hbB = new RedisClusterNodeHeartbeat(connB);

        ClusterNodeManager nodeA = new ClusterNodeManager("perf-A", 5000, 15000, 30000, 0, hbA, regA);
        ClusterNodeManager nodeB = new ClusterNodeManager("perf-B", 5000, 15000, 30000, 0, hbB, regB);

        CountingSender localA = new CountingSender();
        CountingSender localB = new CountingSender();
        localA.addUri("/ws/perf");
        localB.addUri("/ws/perf");

        ClusterMessageSender senderA = new ClusterMessageSender(localA, brokerA, regA, nodeA, 5000);
        ClusterMessageSender senderB = new ClusterMessageSender(localB, brokerB, regB, nodeB, 5000);

        nodeA.start();
        nodeB.start();
        senderA.start();
        senderB.start();
        Thread.sleep(500);

        int crossNodeMsgs = 2000;
        // Warmup
        for (int i = 0; i < 200; i++) {
            senderA.topicMessage("/ws/perf", new TextMessage("warm"));
        }
        localA.count.set(0);
        localB.count.set(0);
        Thread.sleep(500);

        // Measure: Node A broadcasts, Node B should receive via Pub/Sub
        long start = System.nanoTime();
        for (int i = 0; i < crossNodeMsgs; i++) {
            senderA.topicMessage("/ws/perf", new TextMessage("x-" + i));
        }
        // Wait for node B to receive all
        int maxWaitMs = 10000;
        int waited = 0;
        while (localB.count.get() < crossNodeMsgs && waited < maxWaitMs) {
            Thread.sleep(50);
            waited += 50;
        }
        long elapsed = System.nanoTime() - start;

        double crossNodeRate = crossNodeMsgs / (elapsed / 1_000_000_000.0);
        double avgCrossLatencyUs = (elapsed / 1_000.0) / crossNodeMsgs;

        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  TWO-NODE CROSS-NODE DELIVERY                    ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.printf("║  Sent from A:  %,d%n", crossNodeMsgs);
        System.out.printf("║  Node A local: %,d (self fan-out)%n", localA.count.get());
        System.out.printf("║  Node B recv:  %,d (cross-node via Redis)%n", localB.count.get());
        System.out.printf("║  Throughput:   %,.0f msg/s (A→B delivery rate)%n", crossNodeRate);
        System.out.printf("║  Avg latency:  %.1f µs/msg (publish-to-receive)%n", avgCrossLatencyUs);
        System.out.printf("║  Total time:   %.1f ms%n", elapsed / 1_000_000.0);
        System.out.println("╚═══════════════════════════════════════════════════╝");

        senderA.shutdown();
        senderB.shutdown();
        nodeA.shutdown();
        nodeB.shutdown();
        brokerA.shutdown();
        brokerB.shutdown();
        connA.close();
        connB.close();
        clientA.shutdown();
        clientB.shutdown();
    }

    // ==================== Helper ====================

    static class CountingSender implements MessageSender {
        final AtomicInteger count = new AtomicInteger();
        final Set<String> uris = new HashSet<>();
        void addUri(String u) { uris.add(u); }
        @Override public int getSessionNums() { return 0; }
        @Override public int getSessionNums(String uri) { return 0; }
        @Override public Set<String> getRegisteredUri() { return Collections.unmodifiableSet(uris); }
        @Override public boolean isSessionAlive(String uri, String... ids) { return false; }
        @Override public void sendMessage(String uri, AbstractMessage msg, String... ids) {}
        @Override public void topicMessage(String uri, AbstractMessage msg) { count.incrementAndGet(); }
    }
}
