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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.StoredMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for the {@link InMemoryOfflineQueueStore} stub — verifies FIFO enqueue/drain/delete and the
 * REAL per-userId drain lock (the non-holder's concurrent drain returns empty; the lock releases on delete).
 */
class InMemoryOfflineQueueStoreTest {

    private static final String URI = "/ws/chat";

    @Test
    void enqueueDrainFifoThenDelete() {
        InMemoryOfflineQueueStore store = new InMemoryOfflineQueueStore();
        store.enqueue("alice", env("m1")).toCompletableFuture().join();
        store.enqueue("alice", env("m2")).toCompletableFuture().join();
        store.enqueue("alice", env("m3")).toCompletableFuture().join();
        assertEquals(3, store.depth("alice"));

        List<StoredMessage> drained = store.drain("alice").toCompletableFuture().join();
        assertEquals(List.of("m1", "m2", "m3"),
                drained.stream().map(m -> text(m.getEnvelope())).collect(Collectors.toList()));
        assertTrue(store.isLocked("alice"), "drain holds the lock until delete");

        List<String> ids = drained.stream().map(StoredMessage::getId).collect(Collectors.toList());
        store.delete("alice", ids).toCompletableFuture().join();
        assertEquals(0, store.depth("alice"));
        assertFalse(store.isLocked("alice"), "delete releases the lock");
        store.shutdown();
    }

    @Test
    void concurrentDrainGetsLockSkip() {
        InMemoryOfflineQueueStore store = new InMemoryOfflineQueueStore();
        store.enqueue("alice", env("m1")).toCompletableFuture().join();

        // First device acquires the lock and drains.
        List<StoredMessage> first = store.drain("alice").toCompletableFuture().join();
        assertEquals(1, first.size());

        // Second device drains while the first still holds the lock → empty (the holder delivers).
        List<StoredMessage> second = store.drain("alice").toCompletableFuture().join();
        assertTrue(second.isEmpty(), "the non-holder's concurrent drain must return empty");

        // After the first device acks+unlocks, a later drain sees an empty (now-deleted) queue.
        store.delete("alice", first.stream().map(StoredMessage::getId).collect(Collectors.toList()))
                .toCompletableFuture().join();
        assertFalse(store.isLocked("alice"));
        store.shutdown();
    }

    @Test
    void removeAllForUserClearsQueueAndLock() {
        InMemoryOfflineQueueStore store = new InMemoryOfflineQueueStore();
        store.enqueue("alice", env("m1")).toCompletableFuture().join();
        store.drain("alice").toCompletableFuture().join(); // acquires lock
        assertTrue(store.isLocked("alice"));

        store.removeAllForUser("alice").toCompletableFuture().join();
        assertEquals(0, store.depth("alice"));
        assertFalse(store.isLocked("alice"));
        store.shutdown();
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
