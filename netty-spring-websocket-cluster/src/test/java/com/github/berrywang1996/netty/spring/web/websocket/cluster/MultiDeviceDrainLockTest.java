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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisOfflineQueueStore;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.EnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.StoredMessage;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE multi-device double-delivery regression gate (real Redis). Two devices of one user reconnect
 * CONCURRENTLY against the same offline queue: each queued message must be delivered EXACTLY ONCE. The
 * per-userId SET-NX drain lock serializes the drain — only the lock holder reads + delivers + deletes; the
 * non-holder's {@code drain()} returns empty. Without the lock, both devices would XRANGE the whole stream
 * before either deleted, producing a DETERMINISTIC duplicate (not a rare race). Skipped (not failed) without
 * Redis.
 */
class MultiDeviceDrainLockTest {

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static boolean redisAvailable;

    private final EnvelopeCodec codec = new SimpleTextEnvelopeCodec();

    private static final String URI = "/ws/chat";
    private static final int MESSAGE_COUNT = 30;

    @BeforeAll
    static void checkRedis() {
        redisAvailable = ClusterTestRedis.available();
        if (!redisAvailable) {
            return;
        }
        client = RedisClient.create(ClusterTestRedis.uri());
        connection = client.connect();
    }

    @AfterAll
    static void cleanup() {
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
        }
        if (client != null) {
            try { client.shutdown(); } catch (Exception ignored) {}
        }
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");
        flush();
    }

    @AfterEach
    void tearDown() {
        if (redisAvailable) {
            flush();
        }
    }

    private static void flush() {
        connection.sync().eval(
                "for _,k in ipairs(redis.call('keys','netty:offline*')) do redis.call('del',k) end",
                ScriptOutputType.INTEGER);
    }

    @Test
    void twoConcurrentReconnects_eachMessageDeliveredExactlyOnce() throws Exception {
        // Each "device" uses its own store instance (its own connection-shared executor + node id), exactly
        // as two nodes would. They share the same Redis, so the SET-NX lock is the real arbiter.
        RedisOfflineQueueStore deviceA = new RedisOfflineQueueStore(connection, codec, "node-A", 1000, 604800, 5000);
        RedisOfflineQueueStore deviceB = new RedisOfflineQueueStore(connection, codec, "node-B", 1000, 604800, 5000);
        try {
            // Queue MESSAGE_COUNT offline messages for alice.
            for (int i = 1; i <= MESSAGE_COUNT; i++) {
                deviceA.enqueue("alice", env("m" + i)).toCompletableFuture().join();
            }

            // Count how many times each message id is delivered across BOTH concurrent drains.
            Map<String, Integer> deliveredCounts = new ConcurrentHashMap<>();
            AtomicInteger drainsThatGotMessages = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);

            Thread tA = new Thread(() -> drainOnce(deviceA, deliveredCounts, drainsThatGotMessages, start));
            Thread tB = new Thread(() -> drainOnce(deviceB, deliveredCounts, drainsThatGotMessages, start));
            tA.start();
            tB.start();
            start.countDown(); // fire both simultaneously
            tA.join(10_000);
            tB.join(10_000);

            // EXACTLY ONE drain got the messages (the lock holder); the other got empty.
            assertEquals(1, drainsThatGotMessages.get(),
                    "exactly one concurrent drain may deliver (the drain-lock holder); the other returns empty");

            // Every queued message delivered EXACTLY ONCE — no duplicate, no loss.
            assertEquals(MESSAGE_COUNT, deliveredCounts.size(), "all messages delivered");
            for (Map.Entry<String, Integer> e : deliveredCounts.entrySet()) {
                assertEquals(1, e.getValue().intValue(),
                        "message " + e.getKey() + " must be delivered exactly once (got " + e.getValue() + ")");
            }
        } finally {
            deviceA.shutdown();
            deviceB.shutdown();
        }
    }

    /** Drains once on the given store; records delivered message texts (deduped count) and acks. */
    private void drainOnce(RedisOfflineQueueStore store, Map<String, Integer> deliveredCounts,
                           AtomicInteger drainsThatGotMessages, CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        List<StoredMessage> drained = store.drain("alice").toCompletableFuture().join();
        if (drained.isEmpty()) {
            return;
        }
        drainsThatGotMessages.incrementAndGet();
        for (StoredMessage m : drained) {
            deliveredCounts.merge(text(m.getEnvelope()), 1, Integer::sum);
        }
        // Ack + unlock after "delivering".
        store.delete("alice", drained.stream().map(StoredMessage::getId).collect(Collectors.toList()))
                .toCompletableFuture().join();
    }

    private ClusterEnvelope env(String text) {
        return new ClusterEnvelope("node-A", URI, ClusterEnvelope.MessageKind.UNICAST,
                ("T:" + text).getBytes(StandardCharsets.UTF_8), "s1", null, System.currentTimeMillis());
    }

    private static String text(ClusterEnvelope env) {
        String payload = new String(env.getPayload(), StandardCharsets.UTF_8);
        return payload.startsWith("T:") ? payload.substring(2) : payload;
    }
}
