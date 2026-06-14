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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisOfflineQueueStore;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.EnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.StoredMessage;
import io.lettuce.core.Range;
import io.lettuce.core.RedisFuture;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link RedisOfflineQueueStore}: verifies enqueue issues an XADD with MAXLEN against the
 * hash-tagged stream key, that drain acquires the per-userId SET NX lock then XRANGE-reads FIFO (and returns
 * empty when the lock is NOT acquired — the multi-device drain-lock skip), that delete XDELs the ids then
 * releases the lock, and that a TTL-expired entry is lazily dropped on drain.
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

    private static final String STREAM_KEY = "netty:offline:{" + b64(USER) + "}";
    private static final String LOCK_KEY = "netty:offline-lock:{" + b64(USER) + "}";

    @BeforeEach
    void setUp() {
        when(connection.async()).thenReturn(async);
        when(connection.sync()).thenReturn(sync);
        store = new RedisOfflineQueueStore(connection, codec, "node-A", 1000, TTL_SECONDS, DRAIN_LOCK_MS);
    }

    @AfterEach
    void tearDown() {
        store.shutdown();
    }

    @Test
    void enqueueXaddsWithMaxlenAgainstHashTaggedStream() {
        RedisFuture<String> xaddFuture = rf("1700000000000-0");
        when(async.xadd(anyString(), any(XAddArgs.class), anyMap())).thenReturn(xaddFuture);

        store.enqueue(USER, env("hello")).toCompletableFuture().join();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(async).xadd(key.capture(), any(XAddArgs.class), anyMap());
        assertEquals(STREAM_KEY, key.getValue());
    }

    @Test
    void drainAcquiresLockThenReadsFifo() {
        when(sync.set(eq(LOCK_KEY), eq("node-A"), any(SetArgs.class))).thenReturn("OK");
        long now = System.currentTimeMillis();
        List<StreamMessage<String, String>> entries = new ArrayList<>();
        entries.add(entry((now - 1000) + "-0", "first"));
        entries.add(entry((now - 500) + "-0", "second"));
        when(sync.xrange(eq(STREAM_KEY), any(Range.class))).thenReturn(entries);

        List<StoredMessage> drained = store.drain(USER).toCompletableFuture().join();

        assertEquals(2, drained.size());
        // FIFO preserved.
        assertEquals((now - 1000) + "-0", drained.get(0).getId());
        assertEquals("first", text(drained.get(0).getEnvelope()));
        assertEquals("second", text(drained.get(1).getEnvelope()));
        verify(sync).set(eq(LOCK_KEY), eq("node-A"), any(SetArgs.class));
    }

    @Test
    void drainReturnsEmptyWhenLockNotAcquired() {
        // Another device holds the lock — SET NX returns null.
        when(sync.set(eq(LOCK_KEY), anyString(), any(SetArgs.class))).thenReturn(null);

        List<StoredMessage> drained = store.drain(USER).toCompletableFuture().join();

        assertTrue(drained.isEmpty());
        // The non-holder must NOT read the stream (the holder delivers).
        verify(sync, never()).xrange(anyString(), any(Range.class));
    }

    @Test
    void drainLazilyDropsTtlExpiredEntries() {
        when(sync.set(eq(LOCK_KEY), eq("node-A"), any(SetArgs.class))).thenReturn("OK");
        long now = System.currentTimeMillis();
        long expiredMs = now - (TTL_SECONDS * 1000L) - 60_000L; // older than TTL
        List<StreamMessage<String, String>> entries = new ArrayList<>();
        entries.add(entry(expiredMs + "-0", "stale"));
        entries.add(entry((now - 100) + "-0", "fresh"));
        when(sync.xrange(eq(STREAM_KEY), any(Range.class))).thenReturn(entries);

        List<StoredMessage> drained = store.drain(USER).toCompletableFuture().join();

        assertEquals(1, drained.size(), "the TTL-expired entry must be lazily dropped");
        assertEquals("fresh", text(drained.get(0).getEnvelope()));
    }

    @Test
    void deleteXdelsThenReleasesLock() {
        when(sync.xdel(eq(STREAM_KEY), any(String[].class))).thenReturn(2L);
        when(sync.del(any(String[].class))).thenReturn(1L);

        store.delete(USER, java.util.Arrays.asList("1-0", "2-0")).toCompletableFuture().join();

        verify(sync).xdel(eq(STREAM_KEY), eq("1-0"), eq("2-0"));
        // The lock is released via DEL of the lock key (varargs matched positionally).
        verify(sync).del(LOCK_KEY);
    }

    @Test
    void removeAllForUserDeletesStreamAndLock() {
        when(sync.del(any(String[].class))).thenReturn(2L);

        store.removeAllForUser(USER).toCompletableFuture().join();

        // DEL stream + lock in one call (varargs matched positionally).
        verify(sync).del(STREAM_KEY, LOCK_KEY);
    }

    // ---- helpers ----

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
        return f;
    }
}
