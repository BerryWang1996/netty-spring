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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisPresenceRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceStatus;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceTransition;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserPresence;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link RedisPresenceRegistry}: verifies each mutation runs the correct Lua against the hash-tagged
 * presence key and maps {old,new} to a {@link PresenceTransition}; that {@code getPresence} derives the aggregate
 * from HVALS with a FRESH read every call; and that {@code removeAllForNode} SCANs + reaps per hash and returns only
 * the users whose aggregate changed.
 *
 * <p>Note: the eval varargs ({@code V... values}) is matched with {@code any()} (not {@code any(String[].class)}) —
 * mirroring the existing cluster Lua-mock pattern; the latter does not match a Lettuce varargs argument.
 */
class RedisPresenceRegistryTest {

    @SuppressWarnings("unchecked")
    private final RedisAsyncCommands<String, String> async = mock(RedisAsyncCommands.class);
    @SuppressWarnings("unchecked")
    private final RedisCommands<String, String> sync = mock(RedisCommands.class);
    @SuppressWarnings("unchecked")
    private final StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);

    private RedisPresenceRegistry registry;

    @BeforeEach
    void setUp() {
        when(connection.async()).thenReturn(async);
        when(connection.sync()).thenReturn(sync);
        registry = new RedisPresenceRegistry(connection);
    }

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    @Test
    void setPresence_runsSetLuaAndMapsTransition() {
        // Build the RedisFuture stub FIRST (nesting rf() inside when().thenReturn() corrupts Mockito stubbing).
        RedisFuture<List<Object>> evalFuture = rf(Arrays.asList("OFFLINE", "ONLINE"));
        when(async.<List<Object>>eval(eq(RedisPresenceRegistry.SET_LUA), any(ScriptOutputType.class),
                any(String[].class), any())).thenReturn(evalFuture);

        PresenceTransition t = registry.setPresence("u", "nodeA", "s1", PresenceStatus.ONLINE)
                .toCompletableFuture().join();

        assertEquals(PresenceStatus.OFFLINE, t.getOldAggregate());
        assertEquals(PresenceStatus.ONLINE, t.getNewAggregate());
        assertTrue(t.changed());

        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        verify(async).eval(eq(RedisPresenceRegistry.SET_LUA), any(ScriptOutputType.class), keys.capture(), any());
        assertEquals("netty:presence:{" + b64("u") + "}", keys.getValue()[0]);
    }

    @Test
    void clearPresence_runsClearLua() {
        RedisFuture<List<Object>> evalFuture = rf(Arrays.asList("ONLINE", "OFFLINE"));
        when(async.<List<Object>>eval(eq(RedisPresenceRegistry.CLEAR_LUA), any(ScriptOutputType.class),
                any(String[].class), any())).thenReturn(evalFuture);

        PresenceTransition t = registry.clearPresence("u", "nodeA", "s1").toCompletableFuture().join();
        assertEquals(PresenceStatus.ONLINE, t.getOldAggregate());
        assertEquals(PresenceStatus.OFFLINE, t.getNewAggregate());
    }

    @Test
    void setPresenceForUser_runsSetUserLua() {
        RedisFuture<List<Object>> evalFuture = rf(Arrays.asList("ONLINE", "AWAY"));
        when(async.<List<Object>>eval(eq(RedisPresenceRegistry.SET_USER_LUA), any(ScriptOutputType.class),
                any(String[].class), any())).thenReturn(evalFuture);

        PresenceTransition t = registry.setPresenceForUser("u", PresenceStatus.AWAY).toCompletableFuture().join();
        assertEquals(PresenceStatus.ONLINE, t.getOldAggregate());
        assertEquals(PresenceStatus.AWAY, t.getNewAggregate());
    }

    @Test
    void getPresence_readsHvalsAndDerives_noCache() {
        String key = "netty:presence:{" + b64("u") + "}";
        RedisFuture<List<String>> hvalsFuture = rf(Arrays.asList("ONLINE", "AWAY"));
        when(async.hvals(key)).thenReturn(hvalsFuture);

        UserPresence p = registry.getPresence("u").toCompletableFuture().join();
        assertEquals(PresenceStatus.ONLINE, p.getAggregate());
        assertEquals(1, p.getOnlineConnections());
        assertEquals(1, p.getAwayConnections());

        // Fresh read each call (no cache).
        registry.getPresence("u").toCompletableFuture().join();
        verify(async, times(2)).hvals(key);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void removeAllForNode_scansAndReapsPerHash_returnsOnlyChanged() {
        String k1 = "netty:presence:{" + b64("u") + "}";
        String k2 = "netty:presence:{" + b64("v") + "}";
        KeyScanCursor<String> cur = mock(KeyScanCursor.class);
        when(cur.getKeys()).thenReturn(Arrays.asList(k1, k2));
        when(cur.isFinished()).thenReturn(true);
        when(cur.getCursor()).thenReturn("0");
        when(sync.scan(any(ScanCursor.class), any(ScanArgs.class))).thenReturn(cur);
        // k1 (user u) flips ONLINE->OFFLINE; k2 (user v) unchanged ONLINE->ONLINE. sync.eval returns the value directly.
        when(sync.eval(eq(RedisPresenceRegistry.REAP_LUA), any(ScriptOutputType.class),
                aryEq(new String[]{k1}), any())).thenReturn((List) Arrays.asList("ONLINE", "OFFLINE"));
        when(sync.eval(eq(RedisPresenceRegistry.REAP_LUA), any(ScriptOutputType.class),
                aryEq(new String[]{k2}), any())).thenReturn((List) Arrays.asList("ONLINE", "ONLINE"));

        List<PresenceTransition> changed = registry.removeAllForNode("nodeA").toCompletableFuture().join();

        assertEquals(1, changed.size());
        assertEquals("u", changed.get(0).getUserId());
        assertEquals(PresenceStatus.ONLINE, changed.get(0).getOldAggregate());
        assertEquals(PresenceStatus.OFFLINE, changed.get(0).getNewAggregate());
    }

    // ---- helpers ----

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static <T> RedisFuture<T> rf(T value) {
        RedisFuture<T> f = mock(RedisFuture.class);
        java.util.concurrent.CompletableFuture<T> cf = java.util.concurrent.CompletableFuture.completedFuture(value);
        when(f.toCompletableFuture()).thenReturn(cf);
        when(f.thenApply(any())).thenAnswer(inv -> cf.thenApply(inv.getArgument(0)));
        when(f.thenAccept(any())).thenAnswer(inv -> cf.thenAccept(inv.getArgument(0)));
        when(f.thenCompose(any())).thenAnswer(inv -> cf.thenCompose(inv.getArgument(0)));
        return f;
    }
}
