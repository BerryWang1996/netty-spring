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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisPresenceRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceStatus;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceTransition;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Redis integration test for {@link RedisPresenceRegistry}: atomic Lua transition detection across
 * setPresence / setPresenceForUser / clearPresence / getPresence round-trips, concurrent first-connect yielding
 * exactly one OFFLINE→ONLINE transition (Lua serializes on the single hash slot), and removeAllForNode returning
 * the right per-user transitions while pruning only the target node's fields. Skipped (not failed) without Redis.
 */
class RedisPresenceRegistryIntegrationTest {

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static boolean redisAvailable;
    private RedisPresenceRegistry registry;

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
        registry = new RedisPresenceRegistry(connection);
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
        connection.sync().eval("for _,k in ipairs(redis.call('keys','netty:presence:*')) do redis.call('del',k) end",
                ScriptOutputType.INTEGER);
    }

    @Test
    void setClearRoundTrip_aggregateTransitions() {
        PresenceTransition t1 = registry.setPresence("u", "node-A", "s1", PresenceStatus.ONLINE)
                .toCompletableFuture().join();
        assertEquals(PresenceStatus.OFFLINE, t1.getOldAggregate());
        assertEquals(PresenceStatus.ONLINE, t1.getNewAggregate());
        assertTrue(t1.changed());
        assertEquals(PresenceStatus.ONLINE, registry.getPresence("u").toCompletableFuture().join().getAggregate());

        // Second device, same status → no aggregate change.
        PresenceTransition t2 = registry.setPresence("u", "node-A", "s2", PresenceStatus.ONLINE)
                .toCompletableFuture().join();
        assertFalse(t2.changed());
        assertEquals(2, registry.getPresence("u").toCompletableFuture().join().getOnlineConnections());

        // Clear one — still ONLINE via the other.
        assertFalse(registry.clearPresence("u", "node-A", "s1").toCompletableFuture().join().changed());
        // Clear the last — ONLINE→OFFLINE.
        PresenceTransition last = registry.clearPresence("u", "node-A", "s2").toCompletableFuture().join();
        assertEquals(PresenceStatus.ONLINE, last.getOldAggregate());
        assertEquals(PresenceStatus.OFFLINE, last.getNewAggregate());
        assertEquals(PresenceStatus.OFFLINE, registry.getPresence("u").toCompletableFuture().join().getAggregate());
    }

    @Test
    void setPresenceForUser_setsAllConnections() {
        registry.setPresence("u", "node-A", "s1", PresenceStatus.ONLINE).toCompletableFuture().join();
        registry.setPresence("u", "node-B", "s2", PresenceStatus.ONLINE).toCompletableFuture().join();

        PresenceTransition t = registry.setPresenceForUser("u", PresenceStatus.AWAY).toCompletableFuture().join();
        assertEquals(PresenceStatus.ONLINE, t.getOldAggregate());
        assertEquals(PresenceStatus.AWAY, t.getNewAggregate());
        assertEquals(PresenceStatus.AWAY, registry.getPresence("u").toCompletableFuture().join().getAggregate());
        assertEquals(0, registry.getPresence("u").toCompletableFuture().join().getOnlineConnections());
        assertEquals(2, registry.getPresence("u").toCompletableFuture().join().getAwayConnections());
    }

    @Test
    void concurrentFirstConnect_exactlyOneOnlineTransition() throws Exception {
        // Two simultaneous first-connections of the same user (different sessions). Lua serializes on the single
        // hash, so EXACTLY ONE reports OFFLINE→ONLINE (the second sees old==ONLINE).
        CompletableFuture<PresenceTransition> f1 = registry
                .setPresence("u", "node-A", "s1", PresenceStatus.ONLINE).toCompletableFuture();
        CompletableFuture<PresenceTransition> f2 = registry
                .setPresence("u", "node-B", "s2", PresenceStatus.ONLINE).toCompletableFuture();
        PresenceTransition r1 = f1.get();
        PresenceTransition r2 = f2.get();

        int onlineTransitions = (r1.changed() ? 1 : 0) + (r2.changed() ? 1 : 0);
        assertEquals(1, onlineTransitions, "exactly one OFFLINE→ONLINE transition across two concurrent first-connects");
        assertEquals(PresenceStatus.ONLINE, registry.getPresence("u").toCompletableFuture().join().getAggregate());
    }

    @Test
    void removeAllForNode_returnsTransitionsAndPrunesOnlyThatNode() {
        // u: only on node-A. v: on node-A AND node-B.
        registry.setPresence("u", "node-A", "s1", PresenceStatus.ONLINE).toCompletableFuture().join();
        registry.setPresence("v", "node-A", "s2", PresenceStatus.ONLINE).toCompletableFuture().join();
        registry.setPresence("v", "node-B", "s3", PresenceStatus.ONLINE).toCompletableFuture().join();

        List<PresenceTransition> changed = registry.removeAllForNode("node-A").toCompletableFuture().join();

        // Only u changed (ONLINE→OFFLINE); v stays ONLINE via node-B.
        assertEquals(1, changed.size(), "only the user whose last device was on the dead node transitions");
        PresenceTransition u = changed.get(0);
        assertEquals("u", u.getUserId());
        assertEquals(PresenceStatus.ONLINE, u.getOldAggregate());
        assertEquals(PresenceStatus.OFFLINE, u.getNewAggregate());

        assertEquals(PresenceStatus.OFFLINE, registry.getPresence("u").toCompletableFuture().join().getAggregate());
        assertEquals(PresenceStatus.ONLINE, registry.getPresence("v").toCompletableFuture().join().getAggregate());
        assertEquals(1, registry.getPresence("v").toCompletableFuture().join().getTotalConnections());
    }
}
