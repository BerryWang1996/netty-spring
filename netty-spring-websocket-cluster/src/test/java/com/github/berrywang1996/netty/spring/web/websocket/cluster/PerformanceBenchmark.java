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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsClusterBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsJetStreamReliableBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisPubSubBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisSessionRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisStreamsReliableBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterSubscription;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
    private static final String NATS_URI = "nats://localhost:4222";
    private static final int WARMUP = 500;
    private static final int MESSAGES = 5000;
    private static boolean redisAvailable;
    private static boolean natsAvailable;

    @BeforeAll
    static void checkBrokers() {
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
        try {
            Connection nc = Nats.connect(Options.builder()
                    .server(NATS_URI)
                    .connectionTimeout(Duration.ofSeconds(3))
                    .build());
            nc.close();
            natsAvailable = true;
        } catch (Exception e) {
            natsAvailable = false;
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

    // ==================== 5. Raw NATS core pub/sub throughput ====================

    @Test
    @Order(5)
    void rawNatsPubSubThroughput() throws Exception {
        Assumptions.assumeTrue(natsAvailable, "NATS not available");

        Connection pubConn = Nats.connect(Options.builder().server(NATS_URI).build());
        Connection subConn = Nats.connect(Options.builder().server(NATS_URI).build());
        NatsClusterBroker publisher = new NatsClusterBroker(new SimpleTextEnvelopeCodec(), new NoOpMessageAuthenticator());
        NatsClusterBroker subscriber = new NatsClusterBroker(new SimpleTextEnvelopeCodec(), new NoOpMessageAuthenticator());
        publisher.attach(pubConn);
        subscriber.attach(subConn);

        AtomicInteger received = new AtomicInteger();
        CountDownLatch allReceived = new CountDownLatch(MESSAGES);

        subscriber.subscribe("/ws/raw-nats-bench", envelope -> {
            received.incrementAndGet();
            allReceived.countDown();
        });

        Thread.sleep(300);

        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            ClusterEnvelope env = new ClusterEnvelope(
                    "pub-node", "/ws/raw-nats-bench", ClusterEnvelope.MessageKind.BROADCAST,
                    ("T:warm-" + i).getBytes(StandardCharsets.UTF_8), null, null, System.currentTimeMillis());
            publisher.publish("/ws/raw-nats-bench", env);
        }
        Thread.sleep(300);
        received.set(0);
        // Re-arm the latch — warmup ate from the original. Re-create.
        CountDownLatch measureLatch = new CountDownLatch(MESSAGES);
        // Swap by attaching a counter wrapper via a second subscribe is overkill; instead
        // we just count published vs received using AtomicInteger and a tight loop wait.

        long start = System.nanoTime();
        for (int i = 0; i < MESSAGES; i++) {
            ClusterEnvelope env = new ClusterEnvelope(
                    "pub-node", "/ws/raw-nats-bench", ClusterEnvelope.MessageKind.BROADCAST,
                    ("T:msg-" + i).getBytes(StandardCharsets.UTF_8), null, null, System.currentTimeMillis());
            publisher.publish("/ws/raw-nats-bench", env);
        }
        // Wait for all received (post-warmup counter began at 0)
        long deadline = System.currentTimeMillis() + 30_000;
        while (received.get() < MESSAGES && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        long elapsed = System.nanoTime() - start;
        // suppress unused warning
        if (measureLatch.getCount() > 0) { /* not used; counter path is canonical */ }

        double pubRate = MESSAGES / (elapsed / 1_000_000_000.0);
        double avgE2eLatencyUs = (elapsed / 1_000.0) / MESSAGES;

        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  RAW NATS PUB/SUB THROUGHPUT                     ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.printf("║  Published:    %,d%n", MESSAGES);
        System.out.printf("║  Received:     %,d%n", received.get());
        System.out.printf("║  All received: %s%n", received.get() >= MESSAGES ? "YES" : "NO (timeout)");
        System.out.printf("║  Throughput:   %,.0f msg/s (publish rate)%n", pubRate);
        System.out.printf("║  Avg E2E:      %.1f µs/msg%n", avgE2eLatencyUs);
        System.out.printf("║  Total time:   %.1f ms%n", elapsed / 1_000_000.0);
        System.out.println("╚═══════════════════════════════════════════════════╝");

        publisher.shutdown();
        subscriber.shutdown();
        // NatsClusterBroker.shutdown() closes the attached connection; explicit close is redundant
        // but safe in case of partial shutdown failure.
        try { pubConn.close(); } catch (Exception ignored) {}
        try { subConn.close(); } catch (Exception ignored) {}
    }

    // ==================== 6. NATS broker via ClusterMessageSender (two-node, mixed mode) ====================

    @Test
    @Order(6)
    void twoNodeNatsBrokerRedisRegistry() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");
        Assumptions.assumeTrue(natsAvailable, "NATS not available");

        // Registry/heartbeat on Redis (mixed-mode default for RC9+ NATS broker)
        RedisClient clientA = RedisClient.create(REDIS_URI);
        RedisClient clientB = RedisClient.create(REDIS_URI);
        StatefulRedisConnection<String, String> connA = clientA.connect();
        StatefulRedisConnection<String, String> connB = clientB.connect();
        connA.sync().eval("for _,k in ipairs(redis.call('keys','netty:*')) do redis.call('del',k) end",
                io.lettuce.core.ScriptOutputType.INTEGER);

        // Broker transport on NATS
        Connection natsA = Nats.connect(Options.builder().server(NATS_URI).build());
        Connection natsB = Nats.connect(Options.builder().server(NATS_URI).build());
        NatsClusterBroker brokerA = new NatsClusterBroker(new SimpleTextEnvelopeCodec(), new NoOpMessageAuthenticator());
        NatsClusterBroker brokerB = new NatsClusterBroker(new SimpleTextEnvelopeCodec(), new NoOpMessageAuthenticator());
        brokerA.attach(natsA);
        brokerB.attach(natsB);

        RedisSessionRegistry regA = new RedisSessionRegistry(connA);
        RedisSessionRegistry regB = new RedisSessionRegistry(connB);
        RedisClusterNodeHeartbeat hbA = new RedisClusterNodeHeartbeat(connA);
        RedisClusterNodeHeartbeat hbB = new RedisClusterNodeHeartbeat(connB);

        ClusterNodeManager nodeA = new ClusterNodeManager("perf-nats-A", 5000, 15000, 30000, 0, hbA, regA);
        ClusterNodeManager nodeB = new ClusterNodeManager("perf-nats-B", 5000, 15000, 30000, 0, hbB, regB);

        CountingSender localA = new CountingSender();
        CountingSender localB = new CountingSender();
        localA.addUri("/ws/perf-nats");
        localB.addUri("/ws/perf-nats");

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
            senderA.topicMessage("/ws/perf-nats", new TextMessage("warm"));
        }
        localA.count.set(0);
        localB.count.set(0);
        Thread.sleep(500);

        long start = System.nanoTime();
        for (int i = 0; i < crossNodeMsgs; i++) {
            senderA.topicMessage("/ws/perf-nats", new TextMessage("x-" + i));
        }
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
        System.out.println("║  TWO-NODE NATS BROKER + REDIS REGISTRY (mixed)   ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.printf("║  Sent from A:  %,d%n", crossNodeMsgs);
        System.out.printf("║  Node A local: %,d (self fan-out)%n", localA.count.get());
        System.out.printf("║  Node B recv:  %,d (cross-node via NATS)%n", localB.count.get());
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
        try { natsA.close(); } catch (Exception ignored) {}
        try { natsB.close(); } catch (Exception ignored) {}
        connA.close();
        connB.close();
        clientA.shutdown();
        clientB.shutdown();
    }

    // ==================== 7. Redis Streams reliable broadcast throughput ====================

    @Test
    @Order(7)
    void redisStreamsReliableBroadcastThroughput() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisClient client = RedisClient.create(REDIS_URI);
        StatefulRedisConnection<String, String> conn = client.connect();
        conn.sync().eval("for _,k in ipairs(redis.call('keys','netty:cluster:rstream*')) do redis.call('del',k) end",
                io.lettuce.core.ScriptOutputType.INTEGER);
        conn.sync().del("netty:cluster:rstreams");
        conn.close();

        int reliableMsgs = 2000;

        RedisStreamsReliableBroker broker = new RedisStreamsReliableBroker(
                client, new SimpleTextEnvelopeCodec(),
                100_000, 1000L, 64, 4096);

        AtomicInteger received = new AtomicInteger();
        ClusterSubscription sub = broker.reliableSubscribe("/ws/reliable-bench", "bench-sub",
                envelope -> received.incrementAndGet());
        Thread.sleep(500); // let the consume loop warm up

        // Warmup
        for (int i = 0; i < 200; i++) {
            ClusterEnvelope env = new ClusterEnvelope(
                    "pub-warm", "/ws/reliable-bench", ClusterEnvelope.MessageKind.BROADCAST,
                    ("T:warm-" + i).getBytes(StandardCharsets.UTF_8), null, null, System.currentTimeMillis());
            broker.reliablePublish("/ws/reliable-bench", env);
        }
        // Wait for warmup to drain so the counter starts clean
        long warmDeadline = System.currentTimeMillis() + 10_000;
        while (received.get() < 200 && System.currentTimeMillis() < warmDeadline) {
            Thread.sleep(20);
        }
        received.set(0);
        Thread.sleep(200);

        long start = System.nanoTime();
        for (int i = 0; i < reliableMsgs; i++) {
            ClusterEnvelope env = new ClusterEnvelope(
                    "pub-node", "/ws/reliable-bench", ClusterEnvelope.MessageKind.BROADCAST,
                    ("T:msg-" + i).getBytes(StandardCharsets.UTF_8), null, null, System.currentTimeMillis());
            broker.reliablePublish("/ws/reliable-bench", env);
        }
        long deadline = System.currentTimeMillis() + 60_000;
        while (received.get() < reliableMsgs && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        long elapsed = System.nanoTime() - start;

        double rate = reliableMsgs / (elapsed / 1_000_000_000.0);
        double avgE2eLatencyUs = (elapsed / 1_000.0) / reliableMsgs;

        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  RELIABLE BROADCAST (Redis Streams, at-least-once)║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.printf("║  Published:    %,d%n", reliableMsgs);
        System.out.printf("║  Received:     %,d%n", received.get());
        System.out.printf("║  All received: %s%n", received.get() >= reliableMsgs ? "YES" : "NO (timeout)");
        System.out.printf("║  Throughput:   %,.0f msg/s (E2E xadd→deliver)%n", rate);
        System.out.printf("║  Avg E2E:      %.1f µs/msg%n", avgE2eLatencyUs);
        System.out.printf("║  Total time:   %.1f ms%n", elapsed / 1_000_000.0);
        System.out.println("╚═══════════════════════════════════════════════════╝");

        sub.unsubscribe();
        broker.shutdown();
        client.shutdown();
    }

    // ==================== 8. NATS JetStream reliable broadcast throughput ====================

    @Test
    @Order(8)
    void natsJetStreamReliableBroadcastThroughput() throws Exception {
        Assumptions.assumeTrue(natsAvailable, "NATS not available");

        int reliableMsgs = 2000;

        Connection natsConn = Nats.connect(Options.builder().server(NATS_URI).build());

        // Best-effort wipe of any leftover stream from a prior run so ensureStream config-mismatch
        // can't trip on us.
        String streamName = "netty-cluster-reliable-"
                + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("/ws/jetstream-bench".getBytes(StandardCharsets.UTF_8));
        try { natsConn.jetStreamManagement().deleteStream(streamName); } catch (Exception ignored) {}

        NatsJetStreamReliableBroker broker = new NatsJetStreamReliableBroker(
                natsConn, new SimpleTextEnvelopeCodec(),
                new NoOpMessageAuthenticator(), "bench-jet-node",
                100_000L, 500L, 64, 4096);

        AtomicInteger received = new AtomicInteger();
        // NB: origin self-suppression in the broker drops envelopes whose originNodeId matches the
        // broker's own nodeId. Use a DIFFERENT originNodeId in published envelopes so they reach us.
        ClusterSubscription sub = broker.reliableSubscribe("/ws/jetstream-bench", "bench-jet-node",
                envelope -> received.incrementAndGet());
        Thread.sleep(700); // let the durable consumer bind

        // Warmup
        for (int i = 0; i < 200; i++) {
            ClusterEnvelope env = new ClusterEnvelope(
                    "pub-warm", "/ws/jetstream-bench", ClusterEnvelope.MessageKind.BROADCAST,
                    ("T:warm-" + i).getBytes(StandardCharsets.UTF_8), null, null, System.currentTimeMillis());
            broker.reliablePublish("/ws/jetstream-bench", env);
        }
        long warmDeadline = System.currentTimeMillis() + 15_000;
        while (received.get() < 200 && System.currentTimeMillis() < warmDeadline) {
            Thread.sleep(20);
        }
        received.set(0);
        Thread.sleep(200);

        long start = System.nanoTime();
        for (int i = 0; i < reliableMsgs; i++) {
            ClusterEnvelope env = new ClusterEnvelope(
                    "pub-node", "/ws/jetstream-bench", ClusterEnvelope.MessageKind.BROADCAST,
                    ("T:msg-" + i).getBytes(StandardCharsets.UTF_8), null, null, System.currentTimeMillis());
            broker.reliablePublish("/ws/jetstream-bench", env);
        }
        long deadline = System.currentTimeMillis() + 60_000;
        while (received.get() < reliableMsgs && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        long elapsed = System.nanoTime() - start;

        double rate = reliableMsgs / (elapsed / 1_000_000_000.0);
        double avgE2eLatencyUs = (elapsed / 1_000.0) / reliableMsgs;

        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  RELIABLE BROADCAST (NATS JetStream, at-least-once)║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.printf("║  Published:    %,d%n", reliableMsgs);
        System.out.printf("║  Received:     %,d%n", received.get());
        System.out.printf("║  All received: %s%n", received.get() >= reliableMsgs ? "YES" : "NO (timeout)");
        System.out.printf("║  Throughput:   %,.0f msg/s (E2E publish→deliver)%n", rate);
        System.out.printf("║  Avg E2E:      %.1f µs/msg%n", avgE2eLatencyUs);
        System.out.printf("║  Total time:   %.1f ms%n", elapsed / 1_000_000.0);
        System.out.println("╚═══════════════════════════════════════════════════╝");

        sub.unsubscribe();
        broker.shutdown();
        // Cleanup the stream so re-runs are deterministic
        try { natsConn.jetStreamManagement().deleteStream(streamName); } catch (Exception ignored) {}
        try { natsConn.close(); } catch (Exception ignored) {}
    }

    // ==================== 9. HMAC envelope auth overhead (vs no-HMAC) ====================

    @Test
    @Order(9)
    void hmacAuthOverhead() {
        SimpleTextEnvelopeCodec codec = new SimpleTextEnvelopeCodec();
        MessageAuthenticator noOp = new NoOpMessageAuthenticator();
        byte[] secret = "perf-bench-cluster-secret-32+chars!!".getBytes(StandardCharsets.UTF_8);
        MessageAuthenticator hmac = new HmacMessageAuthenticator(secret, true);

        // Build a representative envelope once (same shape used in @Order(3))
        ClusterEnvelope env = new ClusterEnvelope(
                "pub-node", "/ws/hmac-bench", ClusterEnvelope.MessageKind.BROADCAST,
                "T:representative-payload-of-modest-length".getBytes(StandardCharsets.UTF_8),
                null, null, System.currentTimeMillis());

        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            String encoded = codec.encode(env);
            String wrapped = noOp.wrap(encoded);
            noOp.unwrap(wrapped);
        }
        for (int i = 0; i < WARMUP; i++) {
            String encoded = codec.encode(env);
            String wrapped = hmac.wrap(encoded);
            hmac.unwrap(wrapped);
        }

        // Measure no-HMAC: encode + NoOp.wrap + NoOp.unwrap + decode (the local fan-out wire path
        // that goes through the authenticator + codec without crossing the network)
        long startNoOp = System.nanoTime();
        for (int i = 0; i < MESSAGES; i++) {
            String encoded = codec.encode(env);
            String wrapped = noOp.wrap(encoded);
            String unwrapped = noOp.unwrap(wrapped);
            ClusterEnvelope decoded = codec.decode(unwrapped);
            if (decoded == null) throw new IllegalStateException("decode failed");
        }
        long noOpElapsed = System.nanoTime() - startNoOp;

        long startHmac = System.nanoTime();
        for (int i = 0; i < MESSAGES; i++) {
            String encoded = codec.encode(env);
            String wrapped = hmac.wrap(encoded);
            String unwrapped = hmac.unwrap(wrapped);
            ClusterEnvelope decoded = codec.decode(unwrapped);
            if (decoded == null) throw new IllegalStateException("hmac verify failed");
        }
        long hmacElapsed = System.nanoTime() - startHmac;

        double noOpRate = MESSAGES / (noOpElapsed / 1_000_000_000.0);
        double hmacRate = MESSAGES / (hmacElapsed / 1_000_000_000.0);
        double noOpLatencyUs = (noOpElapsed / 1_000.0) / MESSAGES;
        double hmacLatencyUs = (hmacElapsed / 1_000.0) / MESSAGES;
        double overheadPct = 100.0 * (noOpRate - hmacRate) / noOpRate;

        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  HMAC OVERHEAD (envelope sign+verify on local)   ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.printf("║  Messages:     %,d%n", MESSAGES);
        System.out.printf("║  No-HMAC tput: %,.0f msg/s%n", noOpRate);
        System.out.printf("║  HMAC tput:    %,.0f msg/s%n", hmacRate);
        System.out.printf("║  No-HMAC lat:  %.2f µs/msg%n", noOpLatencyUs);
        System.out.printf("║  HMAC lat:     %.2f µs/msg%n", hmacLatencyUs);
        System.out.printf("║  HMAC overhead: %.1f%%%n", overheadPct);
        System.out.println("╚═══════════════════════════════════════════════════╝");
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
