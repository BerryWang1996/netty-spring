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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.OfflineQueueStore;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.StoredMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory stub {@link OfflineQueueStore} for SPI-isolation tests (no Lettuce / Redis). Carries a REAL
 * per-userId drain lock (an in-process {@link Set} of locked userIds) so the multi-device drain-lock
 * semantics — the non-holder's {@code drain} returns empty — can be unit-tested without Redis.
 */
public class InMemoryOfflineQueueStore implements OfflineQueueStore {

    /** userId -> ordered (FIFO) list of (id, envelope). */
    private final ConcurrentHashMap<String, List<StoredMessage>> queues = new ConcurrentHashMap<>();
    /** Monotonic id generator mimicking the Redis stream entry id ("&lt;n&gt;-0"). */
    private final AtomicLong seq = new AtomicLong();
    /** Held drain locks (userId). A second concurrent drain of the same user returns empty. */
    private final Set<String> drainLocks = ConcurrentHashMap.newKeySet();
    /** Max entries returned per drain (oldest-first FIFO) — parity with {@code RedisOfflineQueueStore}. */
    private final int drainBatchSize;

    /** Default batch size (100) — parity with {@code offline.drain-batch-size} default. */
    public InMemoryOfflineQueueStore() {
        this(100);
    }

    public InMemoryOfflineQueueStore(int drainBatchSize) {
        this.drainBatchSize = Math.max(1, drainBatchSize);
    }

    @Override
    public CompletionStage<Void> enqueue(String userId, ClusterEnvelope envelope) {
        String id = seq.incrementAndGet() + "-0";
        queues.computeIfAbsent(userId, k -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(new StoredMessage(id, envelope));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<List<StoredMessage>> drain(String userId) {
        // Real exclusive lock: if another device already holds it, return empty (the holder delivers) and do
        // NOT touch the lock (it is not ours) — parity with RedisOfflineQueueStore (FIX 1/2).
        if (!drainLocks.add(userId)) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        List<StoredMessage> q = queues.get(userId);
        List<StoredMessage> snapshot;
        if (q == null) {
            snapshot = new ArrayList<>();
        } else {
            synchronized (q) {
                // FIFO snapshot bounded to drainBatchSize (oldest-first) — parity with the bounded XRANGE (FIX 3).
                int limit = Math.min(drainBatchSize, q.size());
                snapshot = new ArrayList<>(q.subList(0, limit));
            }
        }
        if (snapshot.isEmpty()) {
            // Empty result: the caller will NOT call delete(), so release the lock here (FIX 2 parity).
            drainLocks.remove(userId);
        }
        // Non-empty: KEEP the lock until delete() (multi-device dedup) — parity with Redis.
        return CompletableFuture.completedFuture(snapshot);
    }

    @Override
    public CompletionStage<Void> delete(String userId, List<String> messageIds) {
        List<StoredMessage> q = queues.get(userId);
        if (q != null && messageIds != null && !messageIds.isEmpty()) {
            synchronized (q) {
                q.removeIf(m -> messageIds.contains(m.getId()));
            }
        }
        // Release the drain lock (paired with drain — the ack-and-unlock step).
        drainLocks.remove(userId);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> removeAllForUser(String userId) {
        queues.remove(userId);
        drainLocks.remove(userId);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void shutdown() {
        queues.clear();
        drainLocks.clear();
    }

    // ---- test helpers ----

    /** Current queue depth for a user (0 if none). */
    public int depth(String userId) {
        List<StoredMessage> q = queues.get(userId);
        return q == null ? 0 : q.size();
    }

    /** Whether the per-userId drain lock is currently held. */
    public boolean isLocked(String userId) {
        return drainLocks.contains(userId);
    }
}
