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
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ScriptOutputType;
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
 * another device of the same user is draining concurrently — it returns an empty list (the holder delivers)
 * and does NOT touch the lock (it is not ours). Otherwise it {@code XRANGE - + COUNT drain-batch-size} the
 * oldest entries up to the batch bound (Redis serializes XADD id generation, so any message enqueued before
 * this read — including in the bind&rarr;drain window — is in the set; the rest drain on the next connect),
 * dropping entries older than {@code ttl-seconds} (and any poison/undecodable entry), <b>XDELing those skipped
 * ids in the same drain</b> so they aren't re-read forever, and returns the deliverable set FIFO. If that set
 * is empty (empty stream / all-skipped) the lock is released here via compare-and-DEL; if non-empty the lock is
 * KEPT for the caller's deliver&rarr;{@link #delete} (which XDELs the delivered ids and releases the lock). The
 * lock release is a Lua compare-and-DEL keyed on this node's id, so it never clobbers a different device's
 * freshly-acquired lock after ours auto-expired ({@code PX}); and the lock auto-expires anyway so a crashed
 * drainer can't wedge a user's queue.
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

    /** Compare-and-DEL: release the drain lock ONLY if it still holds THIS node's id (FIX 1 — FOREIGN-LOCK-DEL).
     *  Guards against deleting another device's freshly-acquired lock after ours auto-expired (PX). */
    private static final String RELEASE_LOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final StatefulRedisConnection<String, String> connection;
    private final EnvelopeCodec codec;
    private final MessageAuthenticator authenticator;
    private final String nodeId;
    private final int maxMessagesPerUser;
    private final long ttlMillis;
    private final long drainLockMs;
    /** Max entries read+delivered per {@link #drain} (oldest-first FIFO); the rest drain on the next connect. */
    private final int drainBatchSize;
    private final ExecutorService executor;

    /** Optional runtime stats (nullable). When set, {@link #drain} bumps {@code offlineDroppedRetention} once
     *  per TTL-expired entry it drops (FIX 4 — the retention honesty meter). */
    private volatile com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterRuntimeStats runtimeStats;

    /** Backward-compat constructor — no authentication (NoOp). */
    public RedisOfflineQueueStore(StatefulRedisConnection<String, String> connection, EnvelopeCodec codec,
                                  String nodeId, int maxMessagesPerUser, long ttlSeconds, long drainLockMs,
                                  int drainBatchSize) {
        this(connection, codec, new NoOpMessageAuthenticator(), nodeId, maxMessagesPerUser, ttlSeconds, drainLockMs,
                drainBatchSize);
    }

    public RedisOfflineQueueStore(StatefulRedisConnection<String, String> connection, EnvelopeCodec codec,
                                  MessageAuthenticator authenticator, String nodeId, int maxMessagesPerUser,
                                  long ttlSeconds, long drainLockMs, int drainBatchSize) {
        this.connection = connection;
        this.codec = codec;
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.nodeId = nodeId;
        this.maxMessagesPerUser = Math.max(1, maxMessagesPerUser);
        this.ttlMillis = ttlSeconds > 0 ? ttlSeconds * 1000L : 0L;
        this.drainLockMs = drainLockMs > 0 ? drainLockMs : 5000L;
        this.drainBatchSize = Math.max(1, drainBatchSize);
        final AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "cluster-offline-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Wires the (nullable) cluster runtime stats so {@link #drain} can bump the {@code offline.dropped_retention}
     *  meter on a TTL-drop (FIX 4). Set by the auto-config to the SAME instance the sender + meter binder share,
     *  so the meter actually moves. No-op effect when null. */
    public void setRuntimeStats(
            com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterRuntimeStats runtimeStats) {
        this.runtimeStats = runtimeStats;
    }

    @Override
    public CompletionStage<Void> enqueue(String userId, ClusterEnvelope envelope) {
        String streamKey = offlineKey(userId);
        String data = authenticator.wrap(codec.encode(envelope));
        CompletableFuture<Void> done = connection.async()
                .xadd(streamKey, XAddArgs.Builder.maxlen(maxMessagesPerUser).approximateTrimming(),
                        Collections.singletonMap(FIELD, data))
                .thenAccept(id -> log.debug("Enqueued offline message {} for user {} on {}", id, userId, streamKey))
                .toCompletableFuture();
        // FIX 6 — NO-STREAM-EXPIRE: refresh a key TTL on the stream so an abandoned queue (a user enqueued-to
        // who never reconnects) self-reaps instead of leaking forever. Chained on the async path (no extra sync
        // round-trip on the enqueue hot path); failures are non-fatal (the MAXLEN bound still caps growth).
        if (ttlMillis > 0) {
            connection.async().pexpire(streamKey, ttlMillis).exceptionally(ex -> {
                log.debug("pexpire on offline stream {} for user {} failed (non-fatal)", streamKey, userId, ex);
                return null;
            });
        }
        return done;
    }

    @Override
    public CompletionStage<List<StoredMessage>> drain(String userId) {
        // Run on the dedicated executor: SET NX lock + XRANGE are a short sync sequence; keeping it off the
        // Lettuce I/O threads matches the other Redis SPIs and avoids blocking transport callbacks.
        return CompletableFuture.supplyAsync(() -> {
            // 1. Acquire the per-userId drain lock. If not acquired, another device is draining → return empty
            //    WITHOUT touching the lock (it is not ours — FIX 1/2).
            String acquired = connection.sync().set(lockKey(userId), nodeId,
                    SetArgs.Builder.nx().px(drainLockMs));
            if (!"OK".equals(acquired)) {
                log.debug("Drain lock for user {} already held — another device is draining; returning empty", userId);
                return Collections.<StoredMessage>emptyList();
            }
            // Lock is HELD by this node. From here, every exit path either (a) returns a NON-empty result and
            // KEEPS the lock for the caller's deliver→delete to release, or (b) releases the lock (empty result,
            // or any exception) so it never leaks (FIX 2 — EMPTY-DRAIN-LOCK-LEAK).
            try {
                String streamKey = offlineKey(userId);
                // 2. Read at most drainBatchSize oldest entries (FIFO); the rest drain on the next connect
                //    (FIX 3 — DRAIN-BATCH-SIZE was previously inert; now bounded).
                List<StreamMessage<String, String>> entries =
                        connection.sync().xrange(streamKey, Range.unbounded(), Limit.from(drainBatchSize));
                List<StoredMessage> out = new ArrayList<>();
                List<String> reapIds = new ArrayList<>(); // TTL-expired + poison ids to XDEL this drain (FIX 5)
                if (entries != null && !entries.isEmpty()) {
                    long now = System.currentTimeMillis();
                    for (StreamMessage<String, String> m : entries) {
                        String id = m.getId();
                        // FIX 5: skipped entries (TTL-expired / poison) are XDELed THIS drain (was never deleted →
                        // re-read every drain forever). They are NOT added to the deliverable `out`.
                        if (ttlMillis > 0) {
                            Long entryMs = streamIdMillis(id);
                            if (entryMs != null && (now - entryMs) > ttlMillis) {
                                log.debug("Dropping TTL-expired offline entry {} for user {}", id, userId);
                                reapIds.add(id);
                                // FIX 4: count each TTL-dropped (retention) entry on the honesty meter.
                                com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterRuntimeStats st =
                                        this.runtimeStats;
                                if (st != null) {
                                    st.addOfflineDroppedRetention(1);
                                }
                                continue;
                            }
                        }
                        String data = m.getBody() == null ? null : m.getBody().get(FIELD);
                        if (data != null) {
                            data = authenticator.unwrap(data); // null = rejected (missing/invalid HMAC)
                        }
                        if (data == null) {
                            log.warn("Offline entry {} for user {} dropped (no field, or rejected HMAC)", id, userId);
                            reapIds.add(id); // poison: corruption, not retention — reaped but NOT metered
                            continue;
                        }
                        try {
                            ClusterEnvelope env = codec.decode(data);
                            if (env != null) {
                                out.add(new StoredMessage(id, env));
                            } else {
                                log.warn("Codec returned null for offline entry {} (user {})", id, userId);
                                reapIds.add(id);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to decode offline entry {} for user {} — reaping", id, userId, e);
                            reapIds.add(id);
                        }
                    }
                }
                // Reap skipped ids in THIS drain while we hold the lock (FIX 5). Reaping is independent of the
                // empty-vs-nonempty lock decision below (reaped ids are not deliverable `out`).
                if (!reapIds.isEmpty()) {
                    connection.sync().xdel(streamKey, reapIds.toArray(new String[0]));
                }
                if (out.isEmpty()) {
                    // Nothing to deliver (empty stream / all-skipped): the caller will NOT call delete(), so
                    // release the lock here (compare-and-DEL) before returning empty (FIX 2).
                    releaseLock(userId);
                    return Collections.<StoredMessage>emptyList();
                }
                // NON-empty: KEEP the lock held; the caller delivers then calls delete() which XDELs + releases.
                return out;
            } catch (RuntimeException e) {
                // Any failure after acquire → release so the lock never leaks (FIX 2), then rethrow.
                releaseLock(userId);
                throw e;
            }
        }, executor);
    }

    @Override
    public CompletionStage<Void> delete(String userId, List<String> messageIds) {
        return CompletableFuture.runAsync(() -> {
            String streamKey = offlineKey(userId);
            if (messageIds != null && !messageIds.isEmpty()) {
                connection.sync().xdel(streamKey, messageIds.toArray(new String[0]));
            }
            // Release the drain lock — compare-and-DEL on THIS node's id so we never clobber another device's
            // freshly-acquired lock after ours auto-expired (FIX 1 — FOREIGN-LOCK-DEL).
            releaseLock(userId);
            log.debug("Deleted {} offline message(s) for user {} and released drain lock",
                    messageIds == null ? 0 : messageIds.size(), userId);
        }, executor);
    }

    @Override
    public CompletionStage<Void> removeAllForUser(String userId) {
        return CompletableFuture.runAsync(() -> {
            // Explicit user wipe: hard-DEL both keys (the lock too — this is an admin bulk cleanup, not a
            // drain-paired release).
            connection.sync().del(offlineKey(userId), lockKey(userId));
            log.debug("Removed offline queue + lock for user {}", userId);
        }, executor);
    }

    /** Releases the per-userId drain lock via compare-and-DEL keyed on THIS node's id (FIX 1): the lock is
     *  removed ONLY if it still holds {@code nodeId}, so we never delete another device's lock that replaced
     *  ours after a PX auto-expiry. Best-effort; logs (does not throw) on Redis error. */
    private void releaseLock(String userId) {
        try {
            connection.sync().eval(RELEASE_LOCK_LUA, ScriptOutputType.INTEGER,
                    new String[]{lockKey(userId)}, nodeId);
        } catch (RuntimeException e) {
            log.warn("Failed to release drain lock for user {} (compare-and-DEL) — will auto-expire (PX)", userId, e);
        }
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
