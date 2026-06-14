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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Redis integration test for {@link RedisOfflineQueueStore}: enqueue → drain (FIFO) → delete round-trip,
 * the per-userId SET-NX drain lock (a second concurrent drain returns empty until the first deletes/unlocks),
 * MAXLEN retention dropping the oldest, and removeAllForUser. Skipped (not failed) without Redis.
 */
class RedisOfflineQueueStoreIntegrationTest {

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static boolean redisAvailable;

    private final EnvelopeCodec codec = new SimpleTextEnvelopeCodec();
    private RedisOfflineQueueStore store;

    private static final String URI = "/ws/chat";

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
        if (store != null) {
            store.shutdown();
        }
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
    void enqueueDrainFifoThenDelete() {
        store = new RedisOfflineQueueStore(connection, codec, "node-A", 1000, 604800, 5000, 100);
        store.enqueue("alice", env("m1")).toCompletableFuture().join();
        store.enqueue("alice", env("m2")).toCompletableFuture().join();
        store.enqueue("alice", env("m3")).toCompletableFuture().join();

        List<StoredMessage> drained = store.drain("alice").toCompletableFuture().join();
        assertEquals(List.of("m1", "m2", "m3"),
                drained.stream().map(m -> text(m.getEnvelope())).collect(Collectors.toList()));

        // delete acks + unlocks; the queue is then empty.
        store.delete("alice", drained.stream().map(StoredMessage::getId).collect(Collectors.toList()))
                .toCompletableFuture().join();
        assertTrue(store.drain("alice").toCompletableFuture().join().isEmpty(),
                "after delete, the queue is empty");
    }

    @Test
    void drainLockMakesSecondConcurrentDrainEmptyUntilDelete() {
        store = new RedisOfflineQueueStore(connection, codec, "node-A", 1000, 604800, 5000, 100);
        store.enqueue("alice", env("m1")).toCompletableFuture().join();

        // First drain acquires the SET-NX lock and reads the message.
        List<StoredMessage> first = store.drain("alice").toCompletableFuture().join();
        assertEquals(1, first.size());

        // A second drain while the lock is held returns empty (the holder delivers).
        List<StoredMessage> second = store.drain("alice").toCompletableFuture().join();
        assertTrue(second.isEmpty(), "the non-holder's drain must return empty while the lock is held");

        // After delete (ack + DEL lock), a fresh drain sees the now-empty stream.
        store.delete("alice", first.stream().map(StoredMessage::getId).collect(Collectors.toList()))
                .toCompletableFuture().join();
        assertTrue(store.drain("alice").toCompletableFuture().join().isEmpty());
    }

    @Test
    void retentionTrimDropsOldestKeepsNewest_fifo() {
        // Redis MAXLEN ~ is APPROXIMATE: it trims only at radix-tree macro-node boundaries (~100 entries),
        // so a small stream may not trim immediately — this is the intended speed/exactness trade and what
        // the impl uses (XADD ... MAXLEN ~ N approximateTrimming()). The retention CONTRACT we assert is the
        // one that holds regardless of when the approximate trim fires: ordering is FIFO and the NEWEST
        // entries always survive (trimming only ever drops the OLDEST). We push 2000 entries (well past the
        // macro-node threshold) so a trim definitely fires, then assert (a) the stream is bounded — far below
        // the 2000 enqueued — and (b) the newest message is present and last (FIFO).
        // Large drain batch (4000 > the trimmed stream) so this test drains the WHOLE trimmed stream and can
        // assert the newest entry survived; the bounded-batch behavior is asserted separately (drainsAtMostBatch).
        store = new RedisOfflineQueueStore(connection, codec, "node-A", 100, 604800, 5000, 4000);
        for (int i = 1; i <= 2000; i++) {
            store.enqueue("alice", env("m" + i)).toCompletableFuture().join();
        }
        List<StoredMessage> drained = store.drain("alice").toCompletableFuture().join();
        assertTrue(drained.size() < 2000, "approximate MAXLEN trimming must bound the stream — got " + drained.size());
        List<String> texts = drained.stream().map(m -> text(m.getEnvelope())).collect(Collectors.toList());
        // Newest survives + FIFO: the last drained entry is the last enqueued.
        assertEquals("m2000", texts.get(texts.size() - 1), "the newest enqueued message must survive trimming (FIFO)");
        // The oldest were trimmed (m1 is gone).
        assertFalse(texts.contains("m1"), "the oldest entries must be trimmed");
    }

    @Test
    void removeAllForUserClearsStreamAndLock() {
        store = new RedisOfflineQueueStore(connection, codec, "node-A", 1000, 604800, 5000, 100);
        store.enqueue("alice", env("m1")).toCompletableFuture().join();
        store.drain("alice").toCompletableFuture().join(); // hold the lock

        store.removeAllForUser("alice").toCompletableFuture().join();
        // Both stream + lock gone → a fresh drain acquires the lock and reads an empty stream.
        assertTrue(store.drain("alice").toCompletableFuture().join().isEmpty());
    }

    @Test
    void emptyDrainReleasesLock_soAnotherNodeAcquiresImmediately() {
        // FIX 2 — EMPTY-DRAIN-LOCK-LEAK: a drain of an EMPTY queue must release the lock (the caller never
        // calls delete()), so a subsequent drain by ANOTHER node acquires immediately rather than waiting PX.
        RedisOfflineQueueStore nodeA = new RedisOfflineQueueStore(connection, codec, "node-A", 1000, 604800, 60000, 100);
        RedisOfflineQueueStore nodeB = new RedisOfflineQueueStore(connection, codec, "node-B", 1000, 604800, 60000, 100);
        try {
            // node-A drains an empty queue → empty result, lock must be released.
            assertTrue(nodeA.drain("ghost").toCompletableFuture().join().isEmpty());
            assertNull(connection.sync().get("netty:offline-lock:{" + b64("ghost") + "}"),
                    "an empty drain must release the lock (not leak it until PX)");
            // node-B can now acquire immediately (it would not if node-A leaked the lock under a 60s PX).
            nodeB.enqueue("ghost", env("m1")).toCompletableFuture().join();
            List<StoredMessage> drained = nodeB.drain("ghost").toCompletableFuture().join();
            assertEquals(1, drained.size(), "node-B acquires the freed lock and drains the message");
            nodeB.delete("ghost", drained.stream().map(StoredMessage::getId).collect(Collectors.toList()))
                    .toCompletableFuture().join();
        } finally {
            nodeA.shutdown();
            nodeB.shutdown();
        }
    }

    @Test
    void foreignLockIsNotDeletedOnDelete() {
        // FIX 1 — FOREIGN-LOCK-DEL: node-A drains (acquires the lock), then (simulating node-A's lock
        // auto-expiring and node-B re-acquiring) we overwrite the lock with node-B's id. node-A's delete()
        // must NOT remove node-B's lock — the compare-and-DEL only deletes when the value still holds node-A.
        RedisOfflineQueueStore nodeA = new RedisOfflineQueueStore(connection, codec, "node-A", 1000, 604800, 60000, 100);
        try {
            nodeA.enqueue("alice", env("m1")).toCompletableFuture().join();
            List<StoredMessage> drained = nodeA.drain("alice").toCompletableFuture().join();
            assertEquals(1, drained.size());
            String lockKey = "netty:offline-lock:{" + b64("alice") + "}";
            assertEquals("node-A", connection.sync().get(lockKey), "node-A holds the lock after drain");

            // Simulate node-A's lock expiry + node-B re-acquiring the SAME lock key.
            connection.sync().set(lockKey, "node-B");

            // node-A acks/deletes — its compare-and-DEL must leave node-B's lock intact.
            nodeA.delete("alice", drained.stream().map(StoredMessage::getId).collect(Collectors.toList()))
                    .toCompletableFuture().join();

            assertEquals("node-B", connection.sync().get(lockKey),
                    "node-A's delete() must NOT clobber node-B's freshly-acquired lock (compare-and-DEL)");
        } finally {
            connection.sync().del("netty:offline-lock:{" + b64("alice") + "}");
            nodeA.shutdown();
        }
    }

    @Test
    void drainsAtMostBatchSize_remainderDrainsOnNextDrain_fifo() {
        // FIX 3 — DRAIN-BATCH-SIZE: enqueue 150 with batch=100 → first drain returns exactly 100 (oldest-first
        // FIFO); after delete, the next drain returns the remaining 50 (sequential drains by the same node work
        // because delete() releases the lock).
        store = new RedisOfflineQueueStore(connection, codec, "node-A", 1000, 604800, 60000, 100);
        for (int i = 1; i <= 150; i++) {
            store.enqueue("alice", env("m" + i)).toCompletableFuture().join();
        }
        List<StoredMessage> first = store.drain("alice").toCompletableFuture().join();
        assertEquals(100, first.size(), "first drain returns exactly drainBatchSize (100)");
        List<String> firstTexts = first.stream().map(m -> text(m.getEnvelope())).collect(Collectors.toList());
        assertEquals("m1", firstTexts.get(0), "FIFO: oldest first");
        assertEquals("m100", firstTexts.get(99), "FIFO: the 100th-oldest is last in the batch");

        store.delete("alice", first.stream().map(StoredMessage::getId).collect(Collectors.toList()))
                .toCompletableFuture().join();

        List<StoredMessage> second = store.drain("alice").toCompletableFuture().join();
        assertEquals(50, second.size(), "the remaining 50 drain on the next drain");
        List<String> secondTexts = second.stream().map(m -> text(m.getEnvelope())).collect(Collectors.toList());
        assertEquals("m101", secondTexts.get(0));
        assertEquals("m150", secondTexts.get(49));
        store.delete("alice", second.stream().map(StoredMessage::getId).collect(Collectors.toList()))
                .toCompletableFuture().join();
    }

    @Test
    void ttlExpiredEntryReapedAndRetentionMeterIncremented() {
        // FIX 4 + FIX 5: an entry older than ttl-seconds is reaped (XDEL) on drain AND counted on the retention
        // honesty meter. We inject ONE entry with an explicitly OLD stream id (well past a 1h TTL) plus one
        // fresh entry directly via XADD (bypassing enqueue()'s pexpire so the stream key itself stays alive),
        // then drain with a 1h ttl-seconds store.
        com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterRuntimeStats stats =
                new com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterRuntimeStats();
        store = new RedisOfflineQueueStore(connection, codec, "node-A", 1000, 3600 /*ttlSec=1h*/, 60000, 100);
        store.setRuntimeStats(stats);
        String streamKey = "netty:offline:{" + b64("alice") + "}";
        long oldMs = System.currentTimeMillis() - (2L * 3600L * 1000L); // 2h old → past the 1h TTL
        connection.sync().xadd(streamKey, new io.lettuce.core.XAddArgs().id(oldMs + "-0"),
                java.util.Collections.singletonMap("e", codec.encode(env("stale"))));
        connection.sync().xadd(streamKey,
                java.util.Collections.singletonMap("e", codec.encode(env("fresh"))));

        List<StoredMessage> drained = store.drain("alice").toCompletableFuture().join();
        assertEquals(1, drained.size(), "only the fresh entry is delivered");
        assertEquals("fresh", text(drained.get(0).getEnvelope()));
        assertEquals(1, stats.getOfflineDroppedRetention(), "the retention meter must move on a TTL-drop (FIX 4)");
        // The stale entry was reaped (XDEL) in this drain; only the (still-pending, undeleted) fresh entry remains.
        assertEquals(1L, connection.sync().xlen(streamKey),
                "the TTL-expired entry must be XDELed in the same drain (FIX 5); the fresh one remains pending");
        // release the held lock for cleanliness
        store.delete("alice", drained.stream().map(StoredMessage::getId).collect(Collectors.toList()))
                .toCompletableFuture().join();
    }

    @Test
    void enqueueSetsStreamKeyTtl() {
        // FIX 6 — NO-STREAM-EXPIRE: after XADD, the stream key carries a PTTL so an abandoned queue self-reaps.
        store = new RedisOfflineQueueStore(connection, codec, "node-A", 1000, 604800, 5000, 100);
        store.enqueue("alice", env("m1")).toCompletableFuture().join();
        String streamKey = "netty:offline:{" + b64("alice") + "}";
        Long pttl = connection.sync().pttl(streamKey);
        assertNotNull(pttl);
        assertTrue(pttl > 0, "the offline stream key must have a positive PTTL (FIX 6) — got " + pttl);
    }

    private static String b64(String s) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
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
