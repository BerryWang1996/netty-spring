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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.spi;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * SPI for the per-user durable offline queue (1.10.0-RC2): stores messages addressed to a user who has no
 * live session anywhere in the cluster, and drains + deletes them when the user reconnects (backfill).
 * Bounded retention (count + age). FIFO per user.
 *
 * <p><b>Delivery semantics (honest):</b> at-least-once within the retention window. Drain delivers then
 * deletes; if the delete fails after delivery, the next connect redelivers — handlers must be idempotent
 * (same contract as reliable broadcast). The offline queue is a fallback for <b>send-time</b> failures only
 * (zero reachable sessions, or a local {@code MessageSessionClosedException}); a remote session that closes
 * after the broker accepted a fire-and-forget unicast is NOT recovered here (post-accept loss is out of
 * scope; apps reconcile via idempotency).
 *
 * <p>All operations return {@link CompletionStage}; implementations must be thread-safe.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
public interface OfflineQueueStore {

    /**
     * Appends a message to the user's offline queue (bounded by retention).
     *
     * @param userId   the recipient's stable identity
     * @param envelope the cross-node envelope to store
     * @return a stage that completes when the message is persisted
     */
    CompletionStage<Void> enqueue(String userId, ClusterEnvelope envelope);

    /**
     * Drains the user's queued messages for delivery on reconnect — <b>EXCLUSIVE per userId</b>.
     *
     * <p>The implementation acquires a per-userId distributed drain lock, then reads the whole stream up to
     * the current tail (FIFO). If the lock is already held — another device of the same user is draining
     * concurrently — this returns an empty list (the lock holder delivers; this prevents deterministic
     * multi-device double-delivery). The caller delivers the returned messages to the newly-connected
     * session, then calls {@link #delete} with the delivered ids (which also releases the lock).
     *
     * @param userId the recipient's stable identity
     * @return a stage completing with the FIFO-ordered messages to deliver (empty if the lock was not acquired)
     */
    CompletionStage<List<StoredMessage>> drain(String userId);

    /**
     * Acks delivered messages (removes them from the store) and releases the drain lock acquired by
     * {@link #drain}. Called after the caller has delivered the drained messages.
     *
     * @param userId     the recipient's stable identity
     * @param messageIds the store-assigned ids of the delivered messages
     * @return a stage that completes when the messages are deleted and the lock is released
     */
    CompletionStage<Void> delete(String userId, List<String> messageIds);

    /**
     * Removes the user's entire offline queue (and any held drain lock). Bulk cleanup.
     *
     * @param userId the recipient's stable identity
     * @return a stage that completes when the queue is removed
     */
    CompletionStage<Void> removeAllForUser(String userId);

    /**
     * Shuts down the store, releasing underlying resources.
     */
    void shutdown();
}
