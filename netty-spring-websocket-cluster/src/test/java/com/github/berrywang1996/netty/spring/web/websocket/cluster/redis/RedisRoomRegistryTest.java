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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisRoomRegistry;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link RedisRoomRegistry}: verifies join/leave/removeAllForSession each issue a SINGLE
 * atomic Lua {@code EVAL} (never looped per-key SREMs), that the local index is kept consistent AFTER the
 * Lua confirms, and that the per-room keys carry the single-slot hash tag {@code {b64uri:b64room}}.
 */
class RedisRoomRegistryTest {

    @SuppressWarnings("unchecked")
    private final RedisAsyncCommands<String, String> async = mock(RedisAsyncCommands.class);
    @SuppressWarnings("unchecked")
    private final StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);

    private RedisRoomRegistry registry;

    private static final String URI = "/ws/chat";

    @BeforeEach
    void setUp() {
        when(connection.async()).thenReturn(async);
        registry = new RedisRoomRegistry(connection);
    }

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    @Test
    void joinIssuesSingleEval_notLoopedSadds_andUpdatesLocalIndex() {
        // Build the RedisFuture stub FIRST (rf() does when(...) on a fresh mock); nesting it inside the
        // outer when().thenReturn() corrupts Mockito's stubbing state ("UnfinishedStubbing").
        RedisFuture<Long> evalFuture = rf(1L);
        when(async.<Long>eval(anyString(), any(ScriptOutputType.class), any(String[].class), any()))
                .thenReturn(evalFuture);

        registry.join(URI, "r1", "s1", "node-A").toCompletableFuture().join();

        // Exactly one EVAL; NO standalone SADD/SREM loop on the hot path.
        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        verify(async, times(1)).eval(anyString(), eq(ScriptOutputType.INTEGER), keys.capture(), any());
        verify(async, never()).sadd(anyString(), any(String[].class));
        verify(async, never()).srem(anyString(), any(String[].class));

        // The per-node member key + node-set key share the single-slot hash tag {b64uri:b64room}.
        String tag = "{" + b64(URI) + ":" + b64("r1") + "}";
        assertEquals("netty:room:" + tag + ":n:node-A", keys.getValue()[0]);
        assertEquals("netty:room:" + tag + ":nodes", keys.getValue()[1]);
        assertEquals("netty:roomsession:" + b64(URI) + ":s1", keys.getValue()[2]);

        // Local index updated only AFTER the Lua completes.
        assertEquals(Set.of("s1"), registry.localMembers(URI, "r1"));
        assertEquals(Set.of("r1"), registry.roomsForSession(URI, "s1"));
    }

    @Test
    void leaveIssuesSingleEval_andUpdatesLocalIndex() {
        RedisFuture<Long> evalFuture = rf(1L);
        when(async.<Long>eval(anyString(), any(ScriptOutputType.class), any(String[].class), any()))
                .thenReturn(evalFuture);

        registry.join(URI, "r1", "s1", "node-A").toCompletableFuture().join();
        assertEquals(Set.of("s1"), registry.localMembers(URI, "r1"));

        registry.leave(URI, "r1", "s1", "node-A").toCompletableFuture().join();

        // Two EVALs total (join + leave); never a looped SREM.
        verify(async, times(2)).eval(anyString(), eq(ScriptOutputType.INTEGER), any(String[].class), any());
        verify(async, never()).srem(anyString(), any(String[].class));

        // Local index cleared for the left session.
        assertTrue(registry.localMembers(URI, "r1").isEmpty());
        assertTrue(registry.roomsForSession(URI, "s1").isEmpty());
    }

    @Test
    void removeAllForSessionIssuesSingleEval_clearsLocalIndexAcrossRooms() {
        RedisFuture<Long> joinFuture = rf(1L);
        when(async.<Long>eval(anyString(), any(ScriptOutputType.class), any(String[].class), any()))
                .thenReturn(joinFuture);

        registry.join(URI, "r1", "s1", "node-A").toCompletableFuture().join();
        registry.join(URI, "r2", "s1", "node-A").toCompletableFuture().join();
        assertEquals(Set.of("r1", "r2"), registry.roomsForSession(URI, "s1"));

        reset(async); // forget the 2 join EVALs so we can count removeAllForSession's exactly
        when(connection.async()).thenReturn(async);
        RedisFuture<Long> removeFuture = rf(2L);
        when(async.<Long>eval(anyString(), any(ScriptOutputType.class), any(String[].class), any()))
                .thenReturn(removeFuture);

        registry.removeAllForSession(URI, "s1", "node-A").toCompletableFuture().join();

        // ONE EVAL clears every room (not N leave() calls).
        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        verify(async, times(1)).eval(anyString(), eq(ScriptOutputType.INTEGER), keys.capture(), any());
        assertEquals("netty:roomsession:" + b64(URI) + ":s1", keys.getValue()[0]);

        // Local index fully cleared for s1.
        assertTrue(registry.roomsForSession(URI, "s1").isEmpty());
        assertTrue(registry.localMembers(URI, "r1").isEmpty());
        assertTrue(registry.localMembers(URI, "r2").isEmpty());
    }

    @Test
    void nodesForRoomReadsTheHashTaggedNodesKey() {
        RedisFuture<Set<String>> smembersFuture = rf(Set.of("node-A", "node-B"));
        when(async.smembers(anyString())).thenReturn(smembersFuture);

        Set<String> nodes = registry.nodesForRoom(URI, "r1").toCompletableFuture().join();
        assertEquals(Set.of("node-A", "node-B"), nodes);

        verify(async).smembers("netty:room:{" + b64(URI) + ":" + b64("r1") + "}:nodes");
    }

    // ---- helpers ----

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** RedisFuture stub delegating the chaining methods the registry uses (thenAccept/thenApply/toCF). */
    @SuppressWarnings("unchecked")
    private static <T> RedisFuture<T> rf(T value) {
        RedisFuture<T> f = mock(RedisFuture.class);
        java.util.concurrent.CompletableFuture<T> cf = java.util.concurrent.CompletableFuture.completedFuture(value);
        when(f.toCompletableFuture()).thenReturn(cf);
        when(f.thenAccept(any())).thenAnswer(inv -> cf.thenAccept(inv.getArgument(0)));
        when(f.thenApply(any())).thenAnswer(inv -> cf.thenApply(inv.getArgument(0)));
        return f;
    }
}
