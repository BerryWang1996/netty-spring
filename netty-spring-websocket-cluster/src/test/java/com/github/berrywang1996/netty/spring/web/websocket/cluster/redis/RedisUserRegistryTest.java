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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisUserRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRef;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link RedisUserRegistry}: verifies bind/unbind issue SADD/SREM against the hash-tagged
 * user key, that {@code sessionsForUser} parses the {@code nodeId|b64uri|sessionId} members (including the
 * multi-device, two-sessions-one-user case) with a FRESH SMEMBERS every call (no cache), and that
 * {@code isUserOnline} reads SCARD.
 */
class RedisUserRegistryTest {

    @SuppressWarnings("unchecked")
    private final RedisAsyncCommands<String, String> async = mock(RedisAsyncCommands.class);
    @SuppressWarnings("unchecked")
    private final StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);

    private RedisUserRegistry registry;

    private static final String URI = "/ws/chat";

    @BeforeEach
    void setUp() {
        when(connection.async()).thenReturn(async);
        registry = new RedisUserRegistry(connection);
    }

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    @Test
    void bindIssuesSaddAgainstHashTaggedUserKey() {
        // Build the RedisFuture stub FIRST — nesting rf() (which calls when() on a fresh mock) inside the
        // outer when().thenReturn() corrupts Mockito's stubbing state ("UnfinishedStubbing").
        RedisFuture<Long> saddFuture = rf(1L);
        when(async.sadd(anyString(), any())).thenReturn(saddFuture);

        registry.bindUser("alice", URI, "s1", "node-A").toCompletableFuture().join();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> member = ArgumentCaptor.forClass(String.class);
        verify(async).sadd(key.capture(), member.capture());
        assertEquals("netty:user:{" + b64("alice") + "}", key.getValue());
        assertEquals("node-A|" + b64(URI) + "|s1", member.getValue());
    }

    @Test
    void unbindSremsMatchingMember() {
        String member = "node-A|" + b64(URI) + "|s1";
        RedisFuture<Set<String>> smembersFuture = rf(set(member));
        RedisFuture<Long> sremFuture = rf(1L);
        when(async.smembers("netty:user:{" + b64("alice") + "}")).thenReturn(smembersFuture);
        when(async.srem(anyString(), any())).thenReturn(sremFuture);

        registry.unbindUser("alice", URI, "s1").toCompletableFuture().join();

        // The matching member is SREM'd from the hash-tagged user key (varargs captured per-element).
        ArgumentCaptor<String> removed = ArgumentCaptor.forClass(String.class);
        verify(async).srem(eq("netty:user:{" + b64("alice") + "}"), removed.capture());
        assertEquals(member, removed.getValue());
    }

    @Test
    void sessionsForUserParsesMembers_multiDevice_freshReadEachCall() {
        String userKey = "netty:user:{" + b64("alice") + "}";
        // Two devices of one user: two sessions, possibly on different nodes.
        Set<String> members = set(
                "node-A|" + b64(URI) + "|s1",
                "node-B|" + b64("/ws/notify") + "|s2");
        RedisFuture<Set<String>> smembersFuture = rf(members);
        when(async.smembers(userKey)).thenReturn(smembersFuture);

        Set<SessionRef> refs = registry.sessionsForUser("alice").toCompletableFuture().join();

        assertEquals(2, refs.size());
        assertTrue(refs.contains(new SessionRef("node-A", URI, "s1")));
        assertTrue(refs.contains(new SessionRef("node-B", "/ws/notify", "s2")));

        // No cache: a SECOND call hits Redis again (the no-cache offline-detection invariant).
        registry.sessionsForUser("alice").toCompletableFuture().join();
        verify(async, times(2)).smembers(userKey);
    }

    @Test
    void isUserOnlineReadsScard() {
        String userKey = "netty:user:{" + b64("alice") + "}";
        RedisFuture<Long> two = rf(2L);
        when(async.scard(userKey)).thenReturn(two);
        assertTrue(registry.isUserOnline("alice").toCompletableFuture().join());

        RedisFuture<Long> zero = rf(0L);
        when(async.scard(userKey)).thenReturn(zero);
        assertFalse(registry.isUserOnline("alice").toCompletableFuture().join());

        // Fresh read each call (no cache).
        verify(async, times(2)).scard(userKey);
    }

    @Test
    void sessionsForUserEmptyWhenNoMembers() {
        RedisFuture<Set<String>> empty = rf((Set<String>) null);
        when(async.smembers(anyString())).thenReturn(empty);
        assertTrue(registry.sessionsForUser("ghost").toCompletableFuture().join().isEmpty());
    }

    // ---- helpers ----

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(java.util.Arrays.asList(values));
    }

    /** RedisFuture stub delegating the chaining methods the registry uses. */
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
