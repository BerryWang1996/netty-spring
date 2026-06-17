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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.mesh;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SetArgs;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link RedisMeshNodeDirectory}: advertise writes {@code netty:mesh:addr:{b64nodeId}} with a PX TTL;
 * peers SCANs the prefix, GETs each, decodes the nodeId and EXCLUDES self; remove DELs the key.
 */
class RedisMeshNodeDirectoryTest {

    @SuppressWarnings("unchecked")
    private final RedisAsyncCommands<String, String> async = mock(RedisAsyncCommands.class);
    @SuppressWarnings("unchecked")
    private final RedisCommands<String, String> sync = mock(RedisCommands.class);
    @SuppressWarnings("unchecked")
    private final StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);

    private RedisMeshNodeDirectory dir;

    @BeforeEach
    void setUp() {
        when(connection.async()).thenReturn(async);
        when(connection.sync()).thenReturn(sync);
        dir = new RedisMeshNodeDirectory(connection);
    }

    @AfterEach
    void tearDown() {
        dir.shutdown();
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void advertise_setsAddrKeyWithTtl() {
        RedisFuture<String> f = rf("OK");
        when(async.set(anyString(), anyString(), any(SetArgs.class))).thenReturn(f);

        dir.advertise("node-A", "10.0.0.7", 9000, 10000).toCompletableFuture().join();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> val = ArgumentCaptor.forClass(String.class);
        verify(async).set(key.capture(), val.capture(), any(SetArgs.class));
        assertEquals("netty:mesh:addr:" + b64("node-A"), key.getValue());
        assertEquals("10.0.0.7:9000", val.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void peers_scansGetsAndExcludesSelf() {
        String kA = "netty:mesh:addr:" + b64("node-A");
        String kB = "netty:mesh:addr:" + b64("node-B");
        KeyScanCursor<String> cur = mock(KeyScanCursor.class);
        when(cur.getKeys()).thenReturn(Arrays.asList(kA, kB));
        when(cur.isFinished()).thenReturn(true);
        when(cur.getCursor()).thenReturn("0");
        when(sync.scan(any(ScanCursor.class), any(ScanArgs.class))).thenReturn(cur);
        when(sync.get(kA)).thenReturn("10.0.0.7:9000");
        when(sync.get(kB)).thenReturn("10.0.0.8:9000");

        Map<String, String> peers = dir.peers("node-A").toCompletableFuture().join();

        assertEquals(1, peers.size(), "self (node-A) excluded");
        assertEquals("10.0.0.8:9000", peers.get("node-B"));
        assertFalse(peers.containsKey("node-A"));
    }

    @Test
    void remove_delsAddrKey() {
        RedisFuture<Long> f = rf(1L);
        when(async.del(anyString())).thenReturn(f);
        dir.remove("node-A").toCompletableFuture().join();
        verify(async).del(eq("netty:mesh:addr:" + b64("node-A")));
    }

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
