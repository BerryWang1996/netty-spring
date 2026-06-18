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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterTestRedis;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link RedisMeshInterestRegistry}: subscribe/unsubscribe each issue a SINGLE atomic Lua {@code EVAL}
 * with the single-slot hash tag {@code {b64uri}}, and {@code nodesForUri} reads the node-set key. Plus a real-Redis IT
 * (assumeTrue-gated) proving the node-set flip and the same-node connect/disconnect race keeps a live node.
 */
class RedisMeshInterestRegistryTest {

    @SuppressWarnings("unchecked")
    private final RedisAsyncCommands<String, String> async = mock(RedisAsyncCommands.class);
    @SuppressWarnings("unchecked")
    private final StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);

    private RedisMeshInterestRegistry registry;
    private static final String URI = "/ws/chat";

    @BeforeEach
    void setUp() {
        when(connection.async()).thenReturn(async);
        registry = new RedisMeshInterestRegistry(connection);
    }

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    @Test
    void subscribeIssuesSingleEval_withHashTaggedKeys() {
        // Build the RedisFuture stub FIRST — rf() does when(...) on a fresh mock; nesting it inside the outer
        // when().thenReturn() corrupts Mockito's stubbing state ("UnfinishedStubbing").
        RedisFuture<Long> evalFuture = rf(1L);
        when(async.<Long>eval(anyString(), any(ScriptOutputType.class), any(String[].class), any()))
                .thenReturn(evalFuture);

        registry.subscribe(URI, "s1", "node-A").toCompletableFuture().join();

        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        verify(async, times(1)).eval(anyString(), eq(ScriptOutputType.INTEGER), keys.capture(), any());
        String tag = "{" + b64(URI) + "}";
        assertEquals("netty:interest:" + tag + ":n:node-A", keys.getValue()[0]);
        assertEquals("netty:interest:" + tag + ":nodes", keys.getValue()[1]);
    }

    @Test
    void unsubscribeIssuesSingleEval() {
        RedisFuture<Long> evalFuture = rf(1L);
        when(async.<Long>eval(anyString(), any(ScriptOutputType.class), any(String[].class), any()))
                .thenReturn(evalFuture);

        registry.unsubscribe(URI, "s1", "node-A").toCompletableFuture().join();

        verify(async, times(1)).eval(anyString(), eq(ScriptOutputType.INTEGER), any(String[].class), any());
    }

    @Test
    void nodesForUriReadsTheHashTaggedNodesKey() {
        RedisFuture<Set<String>> smembersFuture = rf(Set.of("node-A", "node-B"));
        when(async.smembers(anyString())).thenReturn(smembersFuture);

        Set<String> nodes = registry.nodesForUri(URI).toCompletableFuture().join();

        assertEquals(Set.of("node-A", "node-B"), nodes);
        verify(async).smembers("netty:interest:{" + b64(URI) + "}:nodes");
    }

    @Test
    void realRedis_subscribeUnsubscribe_flipsNodeSet_andRaceKeepsLiveNode() {
        Assumptions.assumeTrue(ClusterTestRedis.available(), "Redis not available");
        RedisClient client = RedisClient.create(ClusterTestRedis.uri());
        StatefulRedisConnection<String, String> conn = client.connect();
        try {
            for (String k : conn.sync().keys("netty:interest:*")) {
                conn.sync().del(k);
            }
            RedisMeshInterestRegistry r = new RedisMeshInterestRegistry(conn);
            try {
                r.subscribe("/ws/it", "s1", "node-A").toCompletableFuture().join();
                assertEquals(Set.of("node-A"), r.nodesForUri("/ws/it").toCompletableFuture().join());

                // last-leave concurrent with a fresh connect (same node) — the node must stay (in-EVAL atomicity)
                CompletableFuture<Void> leave = r.unsubscribe("/ws/it", "s1", "node-A").toCompletableFuture();
                CompletableFuture<Void> join = r.subscribe("/ws/it", "s2", "node-A").toCompletableFuture();
                CompletableFuture.allOf(leave, join).join();
                assertEquals(Set.of("node-A"), r.nodesForUri("/ws/it").toCompletableFuture().join());

                r.unsubscribe("/ws/it", "s2", "node-A").toCompletableFuture().join();
                assertEquals(Set.of(), r.nodesForUri("/ws/it").toCompletableFuture().join());
            } finally {
                r.shutdown();
            }
        } finally {
            conn.close();
            client.shutdown();
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static <T> RedisFuture<T> rf(T value) {
        RedisFuture<T> f = mock(RedisFuture.class);
        CompletableFuture<T> cf = CompletableFuture.completedFuture(value);
        when(f.toCompletableFuture()).thenReturn(cf);
        when(f.thenAccept(any())).thenAnswer(inv -> cf.thenAccept(inv.getArgument(0)));
        when(f.thenApply(any())).thenAnswer(inv -> cf.thenApply(inv.getArgument(0)));
        return f;
    }
}
