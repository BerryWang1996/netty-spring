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

package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisUserRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRef;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.*;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Redis integration test for {@link RedisUserRegistry}: full bind/unbind/sessionsForUser/isUserOnline
 * round-trips, the multi-device (two sessions one user) case, and removeAllForNode pruning only the dead
 * node's members. Skipped (not failed) when no Redis is available.
 */
class RedisUserRegistryIntegrationTest {

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static boolean redisAvailable;
    private RedisUserRegistry registry;

    private static final String URI = "/ws/chat";

    @BeforeAll
    static void checkRedis() {
        redisAvailable = ClusterTestRedis.available();
        if (!redisAvailable) {
            return;
        }
        client = RedisClient.create(ClusterTestRedis.uri());
        connection = client.connect();
    }

    @AfterAll
    static void cleanup() {
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
        }
        if (client != null) {
            try { client.shutdown(); } catch (Exception ignored) {}
        }
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");
        flush();
        registry = new RedisUserRegistry(connection);
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.shutdown();
        }
        if (redisAvailable) {
            flush();
        }
    }

    private static void flush() {
        connection.sync().eval("for _,k in ipairs(redis.call('keys','netty:user:*')) do redis.call('del',k) end",
                ScriptOutputType.INTEGER);
    }

    @Test
    void bindLookupUnbindRoundTrip() {
        registry.bindUser("alice", URI, "s1", "node-A").toCompletableFuture().join();
        assertTrue(registry.isUserOnline("alice").toCompletableFuture().join());

        Set<SessionRef> refs = registry.sessionsForUser("alice").toCompletableFuture().join();
        assertEquals(1, refs.size());
        assertTrue(refs.contains(new SessionRef("node-A", URI, "s1")));

        registry.unbindUser("alice", URI, "s1").toCompletableFuture().join();
        assertFalse(registry.isUserOnline("alice").toCompletableFuture().join());
        assertTrue(registry.sessionsForUser("alice").toCompletableFuture().join().isEmpty());
    }

    @Test
    void multiDeviceTwoSessionsOneUser() {
        registry.bindUser("alice", URI, "s1", "node-A").toCompletableFuture().join();
        registry.bindUser("alice", "/ws/notify", "s2", "node-B").toCompletableFuture().join();

        assertEquals(2, registry.sessionsForUser("alice").toCompletableFuture().join().size());
        // One device leaves; the other keeps the user online.
        registry.unbindUser("alice", URI, "s1").toCompletableFuture().join();
        assertTrue(registry.isUserOnline("alice").toCompletableFuture().join());
        Set<SessionRef> remaining = registry.sessionsForUser("alice").toCompletableFuture().join();
        assertEquals(1, remaining.size());
        assertTrue(remaining.contains(new SessionRef("node-B", "/ws/notify", "s2")));
    }

    @Test
    void removeAllForNodePrunesOnlyThatNode() {
        registry.bindUser("alice", URI, "s1", "node-A").toCompletableFuture().join();
        registry.bindUser("alice", "/ws/notify", "s2", "node-B").toCompletableFuture().join();
        registry.bindUser("bob", URI, "s3", "node-A").toCompletableFuture().join();

        registry.removeAllForNode("node-A").toCompletableFuture().join();

        // alice keeps only her node-B session; bob (only on node-A) is gone.
        Set<SessionRef> alice = registry.sessionsForUser("alice").toCompletableFuture().join();
        assertEquals(1, alice.size());
        assertTrue(alice.contains(new SessionRef("node-B", "/ws/notify", "s2")));
        assertFalse(registry.isUserOnline("bob").toCompletableFuture().join());
    }
}
