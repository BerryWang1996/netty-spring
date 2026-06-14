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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.redis;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterRuntimeStats;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisOfflineQueueStore;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.EnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.StoredMessage;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link RedisOfflineQueueStore}: verifies enqueue issues an XADD with MAXLEN against the
 * hash-tagged stream key (and a PTTL refresh — FIX 6), that drain acquires the per-userId SET NX lock then
 * XRANGE-reads FIFO with a COUNT bound (FIX 3 — drainBatchSize), reaps TTL-expired/poison entries via XDEL in
 * the same drain (FIX 5) bumping the retention meter (FIX 4), releases the lock on an empty drain (FIX 2) and
 * on delete via compare-and-DEL Lua (FIX 1), and returns empty (without reading the stream) when the lock is
 * NOT acquired — the multi-device drain-lock skip.
 */
class RedisOfflineQueueStoreTest {

    @SuppressWarnings("unchecked")
    private final RedisAsyncCommands<String, String> async = mock(RedisAsyncCommands.class);
    @SuppressWarnings("unchecked")
    private final RedisCommands<String, String> sync = mock(RedisCommands.class);
    @SuppressWarnings("unchecked")
    private final StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);

    private final EnvelopeCodec codec = new SimpleTextEnvelopeCodec();
    private RedisOfflineQueueStore store;

    private static final String USER = "alice";
    private static final String URI = "/ws/chat";
    private static final long TTL_SECONDS = 604800; // 7 days
    private static final long DRAIN_LOCK_MS = 5000;
    private static final int DRAIN_BATCH = 100;

    private static final String STREAM_KEY = "netty:offline:{" + b64(USER) + "}";
    private static final String LOCK_KEY = "netty:offline-lock:{" + b64(USER) + "}";

    @BeforeEach
    void setUp() {
        when(connection.async()).thenReturn(async);
        when(connection.sync()).thenReturn(sync);
        store = new RedisOfflineQueueStore(connection, codec, "node-A", 1000, TTL_SECONDS, DRAIN_LOCK_MS, DRAIN_BATCH);
    }

    @AfterEach
    void tearDown() {
        store.shutdown();
    }

    @Test
    void enqueueXaddsWithMaxlenAndSetsPttl() {
        RedisFuture<String> xaddFuture = rf("1700000000000-0");
        RedisFuture<Boolean> pexpireFuture = rf(Boolean.TRUE);
        when(async.xadd(anyString(), any(XAddArgs.class), anyMap())).thenReturn(xaddFuture);
        when(async.pexpire(anyString(), anyLong())).thenReturn(pexpireFuture);

        store.enqueue(USER, env("hello")).toCompletableFuture().join();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(async).xadd(key.capture(), any(XAddArgs.class), anyMap());
        assertEquals(STREAM_KEY, key.getValue());
        // FIX 6: a key TTL is refreshed on the stream so an abandoned queue self-reaps.
        verify(async).pexpire(eq(STREAM_KEY), eq(TTL_SECONDS * 1000L));
    }

    @Test
    void drainAcquiresLockThenReadsFifoWithBatchLimit() {
        when(sync.set(eq(LOCK_KEY), eq("node-A"), any(SetArgs.class))).thenReturn("OK");
        long now = System.currentTimeMillis();
        List<StreamMessage<String, String>> entries = new ArrayList<>();
        entries.add(entry((now - 1000) + "-0", "first"));
        entries.add(entry((now - 500) + "-0", "second"));
        when(sync.xrange(eq(STREAM_KEY), any(Range.class), any(Limit.class))).thenReturn(entries);

        List<StoredMessage> drained = store.drain(USER).toCompletableFuture().join();

        assertEquals(2, drained.size());
        // FIFO preserved.
        assertEquals((now - 1000) + "-0", drained.get(0).getId());
        assertEquals("first", text(drained.get(0).getEnvelope()));
        assertEquals("second", text(drained.get(1).getEnvelope()));
        verify(sync).set(eq(LOCK_KEY), eq("node-A"), any(SetArgs.class));
        // FIX 3: the read is bounded by a Limit (drainBatchSize).
        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(sync).xrange(eq(STREAM_KEY), any(Range.class), limit.capture());
        assertEquals(DRAIN_BATCH, limit.getValue().getCount());
        // FIX 2: a NON-empty drain KEEPS the lock (no release here — the caller's delete() releases it).
        verify(sync, never()).eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class));
    }

    @Test
    void drainReturnsEmptyWhenLockNotAcquired() {
        // Another device holds the lock — SET NX returns null.
        when(sync.set(eq(LOCK_KEY), anyString(), any(SetArgs.class))).thenReturn(null);

        List<StoredMessage> drained = store.drain(USER).toCompletableFuture().join();

        assertTrue(drained.isEmpty());
        // The non-holder must NOT read the stream (the holder delivers) and must NOT touch the lock (not ours).
        verify(sync, never()).xrange(anyString(), any(Range.class), any(Limit.class));
        verify(sync, never()).eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class));
    }

    @Test
    void drainOnEmptyStreamReleasesLockViaCompareAndDel() {
        when(sync.set(eq(LOCK_KEY), eq("node-A"), any(SetArgs.class))).thenReturn("OK");
        when(sync.xrange(eq(STREAM_KEY), any(Range.class), any(Limit.class)))
                .thenReturn(Collections.emptyList());

        List<StoredMessage> drained = store.drain(USER).toCompletableFuture().join();

        assertTrue(drained.isEmpty());
        // FIX 2: an empty drain releases the lock (the caller never calls delete()), via the compare-and-DEL Lua.
        verifyReleaseLock();
    }

    @Test
    void drainReapsTtlExpiredEntriesAndBumpsRetentionMeter() {
        ClusterRuntimeStats stats = new ClusterRuntimeStats();
        store.setRuntimeStats(stats);
        when(sync.set(eq(LOCK_KEY), eq("node-A"), any(SetArgs.class))).thenReturn("OK");
        long now = System.currentTimeMillis();
        long expiredMs = now - (TTL_SECONDS * 1000L) - 60_000L; // older than TTL
        String staleId = expiredMs + "-0";
        List<StreamMessage<String, String>> entries = new ArrayList<>();
        entries.add(entry(staleId, "stale"));
        entries.add(entry((now - 100) + "-0", "fresh"));
        when(sync.xrange(eq(STREAM_KEY), any(Range.class), any(Limit.class))).thenReturn(entries);
        when(sync.xdel(eq(STREAM_KEY), any(String[].class))).thenReturn(1L);

        List<StoredMessage> drained = store.drain(USER).toCompletableFuture().join();

        assertEquals(1, drained.size(), "the TTL-expired entry must be dropped");
        assertEquals("fresh", text(drained.get(0).getEnvelope()));
        // FIX 5: the skipped (TTL-expired) id is XDELed in THIS drain so it isn't re-read forever.
        verify(sync).xdel(eq(STREAM_KEY), eq(staleId));
        // FIX 4: the retention honesty meter moved by 1 (the one TTL-dropped entry).
        assertEquals(1, stats.getOfflineDroppedRetention());
        // Non-empty deliverable result → lock KEPT (no release).
        verify(sync, never()).eval(anyString(), any(ScriptOutputType.class), any(String[].class), any(String[].class));
    }

    @Test
    void deleteXdelsThenReleasesLockViaCompareAndDel() {
        when(sync.xdel(eq(STREAM_KEY), any(String[].class))).thenReturn(2L);

        store.delete(USER, java.util.Arrays.asList("1-0", "2-0")).toCompletableFuture().join();

        verify(sync).xdel(eq(STREAM_KEY), eq("1-0"), eq("2-0"));
        // FIX 1: the lock is released via compare-and-DEL Lua keyed on THIS node's id (not an unconditional DEL).
        verifyReleaseLock();
        verify(sync, never()).del(LOCK_KEY);
    }

    @Test
    void removeAllForUserDeletesStreamAndLock() {
        when(sync.del(any(String[].class))).thenReturn(2L);

        store.removeAllForUser(USER).toCompletableFuture().join();

        // Explicit user wipe still hard-DELs stream + lock in one call (varargs matched positionally).
        verify(sync).del(STREAM_KEY, LOCK_KEY);
    }

    // ---- helpers ----

    /** Verifies the compare-and-DEL lock release: EVAL(&lt;lua&gt;, INTEGER, [lockKey], nodeId) — the
     *  {@code eval(String, ScriptOutputType, K[] keys, V... values)} overload, keys=[lockKey], values=[nodeId]. */
    private void verifyReleaseLock() {
        verify(sync).eval(contains("redis.call('del'"), eq(ScriptOutputType.INTEGER),
                aryEq(new String[]{LOCK_KEY}), eq("node-A"));
    }

    private ClusterEnvelope env(String text) {
        return new ClusterEnvelope("node-A", URI, ClusterEnvelope.MessageKind.UNICAST,
                ("T:" + text).getBytes(StandardCharsets.UTF_8), "s1", null, System.currentTimeMillis());
    }

    private static String text(ClusterEnvelope env) {
        String payload = new String(env.getPayload(), StandardCharsets.UTF_8);
        return payload.startsWith("T:") ? payload.substring(2) : payload;
    }

    private StreamMessage<String, String> entry(String id, String text) {
        Map<String, String> body = Collections.singletonMap("e", codec.encode(env(text)));
        return new StreamMessage<>(STREAM_KEY, id, body);
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static <T> RedisFuture<T> rf(T value) {
        RedisFuture<T> f = mock(RedisFuture.class);
        java.util.concurrent.CompletableFuture<T> cf = java.util.concurrent.CompletableFuture.completedFuture(value);
        when(f.toCompletableFuture()).thenReturn(cf);
        when(f.thenAccept(any())).thenAnswer(inv -> cf.thenAccept(inv.getArgument(0)));
        when(f.thenApply(any())).thenAnswer(inv -> cf.thenApply(inv.getArgument(0)));
        when(f.thenCompose(any())).thenAnswer(inv -> cf.thenCompose(inv.getArgument(0)));
        when(f.exceptionally(any())).thenAnswer(inv -> cf.exceptionally(inv.getArgument(0)));
        return f;
    }
}
