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
        store = new RedisOfflineQueueStore(connection, codec, "node-A", 1000, 604800, 5000);
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
        store = new RedisOfflineQueueStore(connection, codec, "node-A", 1000, 604800, 5000);
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
        store = new RedisOfflineQueueStore(connection, codec, "node-A", 100, 604800, 5000);
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
        store = new RedisOfflineQueueStore(connection, codec, "node-A", 1000, 604800, 5000);
        store.enqueue("alice", env("m1")).toCompletableFuture().join();
        store.drain("alice").toCompletableFuture().join(); // hold the lock

        store.removeAllForUser("alice").toCompletableFuture().join();
        // Both stream + lock gone → a fresh drain acquires the lock and reads an empty stream.
        assertTrue(store.drain("alice").toCompletableFuture().join().isEmpty());
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
