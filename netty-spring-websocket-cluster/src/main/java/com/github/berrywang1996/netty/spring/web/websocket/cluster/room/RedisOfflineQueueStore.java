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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.room;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.EnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.OfflineQueueStore;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.StoredMessage;
import io.lettuce.core.Range;
import io.lettuce.core.SetArgs;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis Streams implementation of {@link OfflineQueueStore} (1.10.0-RC2) — the per-user durable offline
 * queue for user-addressed delivery to a user who has no live session anywhere in the cluster.
 *
 * <p><b>Key design</b> (userId base64url-encoded — delimiter-safe; the hash tag {@code {b64userId}} pins a
 * user's queue + drain-lock to a single Redis-Cluster slot so the {@code SET NX} lock and the stream
 * operations co-locate):
 * <ul>
 *   <li>{@code netty:offline:{b64userId}} → Redis Stream of queued envelopes (FIFO, bounded
 *       {@code MAXLEN ~ max-messages-per-user}). One field {@code e} = the HMAC-wrapped codec-encoded
 *       envelope (mirrors {@code RedisStreamsReliableBroker}'s wrap-on-write, so the offline path carries
 *       the same anti-forgery tag as the reliable path).</li>
 *   <li>{@code netty:offline-lock:{b64userId}} → the per-userId drain lock ({@code SET NX PX drain-lock-ms},
 *       value = this node id). Acquired in {@link #drain}, released in {@link #delete}.</li>
 * </ul>
 *
 * <p><b>Drain is EXCLUSIVE per userId</b> (the multi-device double-delivery fix): {@link #drain} first
 * {@code SET netty:offline-lock:{b64userId} {node} NX PX <drain-lock-ms>}. If the lock is already held —
 * another device of the same user is draining concurrently — it returns an empty list (the holder delivers).
 * Otherwise it {@code XRANGE - +} the whole stream up to the tail at call time (Redis serializes XADD id
 * generation, so any message enqueued before this read — including in the bind&rarr;drain window — is in the
 * set), lazily dropping entries older than {@code ttl-seconds}, and returns them FIFO. The caller delivers,
 * then {@link #delete} XDELs the delivered ids and releases the lock (DEL). The lock auto-expires
 * ({@code PX}) so a crashed drainer can't wedge a user's queue.
 *
 * <p><b>Threading</b>: a small dedicated executor (mirrors {@code RedisUserRegistry} / {@code RedisRoomRegistry})
 * keeps the offline control path off the transport I/O threads.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
@Slf4j
public class RedisOfflineQueueStore implements OfflineQueueStore {

    private static final String OFFLINE_PREFIX = "netty:offline:";
    private static final String LOCK_PREFIX = "netty:offline-lock:";
    private static final String FIELD = "e";

    private final StatefulRedisConnection<String, String> connection;
    private final EnvelopeCodec codec;
    private final MessageAuthenticator authenticator;
    private final String nodeId;
    private final int maxMessagesPerUser;
    private final long ttlMillis;
    private final long drainLockMs;
    private final ExecutorService executor;

    /** Backward-compat constructor — no authentication (NoOp). */
    public RedisOfflineQueueStore(StatefulRedisConnection<String, String> connection, EnvelopeCodec codec,
                                  String nodeId, int maxMessagesPerUser, long ttlSeconds, long drainLockMs) {
        this(connection, codec, new NoOpMessageAuthenticator(), nodeId, maxMessagesPerUser, ttlSeconds, drainLockMs);
    }

    public RedisOfflineQueueStore(StatefulRedisConnection<String, String> connection, EnvelopeCodec codec,
                                  MessageAuthenticator authenticator, String nodeId, int maxMessagesPerUser,
                                  long ttlSeconds, long drainLockMs) {
        this.connection = connection;
        this.codec = codec;
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.nodeId = nodeId;
        this.maxMessagesPerUser = Math.max(1, maxMessagesPerUser);
        this.ttlMillis = ttlSeconds > 0 ? ttlSeconds * 1000L : 0L;
        this.drainLockMs = drainLockMs > 0 ? drainLockMs : 5000L;
        final AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "cluster-offline-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public CompletionStage<Void> enqueue(String userId, ClusterEnvelope envelope) {
        String streamKey = offlineKey(userId);
        String data = authenticator.wrap(codec.encode(envelope));
        return connection.async()
                .xadd(streamKey, XAddArgs.Builder.maxlen(maxMessagesPerUser).approximateTrimming(),
                        Collections.singletonMap(FIELD, data))
                .thenAccept(id -> log.debug("Enqueued offline message {} for user {} on {}", id, userId, streamKey))
                .toCompletableFuture();
    }

    @Override
    public CompletionStage<List<StoredMessage>> drain(String userId) {
        // Run on the dedicated executor: SET NX lock + XRANGE are a short sync sequence; keeping it off the
        // Lettuce I/O threads matches the other Redis SPIs and avoids blocking transport callbacks.
        return CompletableFuture.supplyAsync(() -> {
            String lockKey = lockKey(userId);
            // 1. Acquire the per-userId drain lock. If not acquired, another device is draining → return empty.
            String acquired = connection.sync().set(lockKey, nodeId,
                    SetArgs.Builder.nx().px(drainLockMs));
            if (!"OK".equals(acquired)) {
                log.debug("Drain lock for user {} already held — another device is draining; returning empty", userId);
                return Collections.<StoredMessage>emptyList();
            }
            // 2. Read the whole stream up to the current tail (FIFO).
            String streamKey = offlineKey(userId);
            List<StreamMessage<String, String>> entries =
                    connection.sync().xrange(streamKey, Range.unbounded());
            if (entries == null || entries.isEmpty()) {
                return Collections.<StoredMessage>emptyList();
            }
            long now = System.currentTimeMillis();
            List<StoredMessage> out = new ArrayList<>(entries.size());
            for (StreamMessage<String, String> m : entries) {
                String id = m.getId();
                // Lazy TTL drop: a stream id is "<millis>-<seq>"; if the entry is older than ttl, skip it
                // (it will be XDELed alongside the delivered ids — see delete()'s caller passing all read ids).
                if (ttlMillis > 0) {
                    Long entryMs = streamIdMillis(id);
                    if (entryMs != null && (now - entryMs) > ttlMillis) {
                        log.debug("Dropping TTL-expired offline entry {} for user {}", id, userId);
                        continue;
                    }
                }
                String data = m.getBody() == null ? null : m.getBody().get(FIELD);
                if (data != null) {
                    data = authenticator.unwrap(data); // null = rejected (missing/invalid HMAC)
                }
                if (data == null) {
                    log.warn("Offline entry {} for user {} dropped (no field, or rejected HMAC)", id, userId);
                    continue;
                }
                try {
                    ClusterEnvelope env = codec.decode(data);
                    if (env != null) {
                        out.add(new StoredMessage(id, env));
                    } else {
                        log.warn("Codec returned null for offline entry {} (user {})", id, userId);
                    }
                } catch (Exception e) {
                    log.warn("Failed to decode offline entry {} for user {} — skipping", id, userId, e);
                }
            }
            return out;
        }, executor);
    }

    @Override
    public CompletionStage<Void> delete(String userId, List<String> messageIds) {
        return CompletableFuture.runAsync(() -> {
            String streamKey = offlineKey(userId);
            if (messageIds != null && !messageIds.isEmpty()) {
                connection.sync().xdel(streamKey, messageIds.toArray(new String[0]));
            }
            // Release the drain lock regardless (delete is the ack-and-unlock step paired with drain).
            connection.sync().del(lockKey(userId));
            log.debug("Deleted {} offline message(s) for user {} and released drain lock",
                    messageIds == null ? 0 : messageIds.size(), userId);
        }, executor);
    }

    @Override
    public CompletionStage<Void> removeAllForUser(String userId) {
        return CompletableFuture.runAsync(() -> {
            connection.sync().del(offlineKey(userId), lockKey(userId));
            log.debug("Removed offline queue + lock for user {}", userId);
        }, executor);
    }

    @Override
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        log.info("RedisOfflineQueueStore shut down");
    }

    // ---- Key helpers ----

    /** Base64url (no padding) — delimiter-safe (no ':' / '|' / '{' / '}'). */
    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Stream key, hash-tagged on {@code b64userId} for Redis-Cluster slot co-location with the lock. */
    private static String offlineKey(String userId) {
        return OFFLINE_PREFIX + "{" + b64(userId) + "}";
    }

    /** Drain-lock key, hash-tagged identically so SET NX co-locates with the stream. */
    private static String lockKey(String userId) {
        return LOCK_PREFIX + "{" + b64(userId) + "}";
    }

    /** Parses the millisecond timestamp from a Redis stream id ("&lt;millis&gt;-&lt;seq&gt;"); null if unparseable. */
    private static Long streamIdMillis(String id) {
        if (id == null) {
            return null;
        }
        int dash = id.indexOf('-');
        String ms = dash >= 0 ? id.substring(0, dash) : id;
        try {
            return Long.parseLong(ms);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
