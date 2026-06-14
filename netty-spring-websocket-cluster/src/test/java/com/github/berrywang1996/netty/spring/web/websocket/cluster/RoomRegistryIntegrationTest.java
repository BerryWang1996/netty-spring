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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisRoomRegistry;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link RedisRoomRegistry} against a real Redis (Docker via {@link ClusterTestRedis}).
 * Exercises the atomic Lua against live Redis: join/leave/nodesForRoom round-trip, the node-set
 * add-on-first / remove-on-last transition, removeAllForSession (single EVAL clears every room),
 * removeAllForNode dead-node cleanup, and concurrent join/leave on one room staying consistent.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoomRegistryIntegrationTest {

    private static String REDIS_URI;
    private static RedisClient client;
    private static StatefulRedisConnection<String, String> conn;
    private static boolean redisAvailable;

    private static final String URI = "/ws/chat";

    @BeforeAll
    static void setUp() {
        redisAvailable = ClusterTestRedis.available();
        if (!redisAvailable) {
            return;
        }
        REDIS_URI = ClusterTestRedis.uri();
        client = RedisClient.create(REDIS_URI);
        conn = client.connect();
        wipe();
    }

    @AfterAll
    static void tearDown() {
        if (conn != null) {
            wipe();
            conn.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    private static void wipe() {
        conn.sync().eval("for _,k in ipairs(redis.call('keys','netty:room*')) do redis.call('del',k) end",
                ScriptOutputType.INTEGER);
        conn.sync().eval("for _,k in ipairs(redis.call('keys','netty:roomsession*')) do redis.call('del',k) end",
                ScriptOutputType.INTEGER);
    }

    private RedisRoomRegistry newRegistry() {
        return new RedisRoomRegistry(conn);
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String nodesKey(String uri, String room) {
        return "netty:room:{" + b64(uri) + ":" + b64(room) + "}:nodes";
    }

    @Test
    @Order(1)
    void joinLeaveNodeSetRoundTrip() {
        RedisRoomRegistry r = newRegistry();
        try {
            r.join(URI, "r1", "s1", "node-A").toCompletableFuture().join();
            assertEquals(Set.of("node-A"), r.nodesForRoom(URI, "r1").toCompletableFuture().join());
            assertEquals(Set.of("s1"), r.localMembers(URI, "r1"));

            // second member on the same node → node-set unchanged
            r.join(URI, "r1", "s2", "node-A").toCompletableFuture().join();
            assertEquals(Set.of("node-A"), r.nodesForRoom(URI, "r1").toCompletableFuture().join());

            // leave one of two → node still present
            r.leave(URI, "r1", "s1", "node-A").toCompletableFuture().join();
            assertEquals(Set.of("node-A"), r.nodesForRoom(URI, "r1").toCompletableFuture().join());

            // leave the last → node removed from the node-set AND the per-node member key deleted
            r.leave(URI, "r1", "s2", "node-A").toCompletableFuture().join();
            assertTrue(r.nodesForRoom(URI, "r1").toCompletableFuture().join().isEmpty());
            assertFalse(conn.sync().exists(nodesKey(URI, "r1")) > 0
                    && conn.sync().scard(nodesKey(URI, "r1")) > 0, "node-set must be empty/gone");
        } finally {
            r.shutdown();
            wipe();
        }
    }

    @Test
    @Order(2)
    void twoNodesShareOneRoomNodeSet() {
        RedisRoomRegistry r = newRegistry();
        try {
            r.join(URI, "r2", "sA", "node-A").toCompletableFuture().join();
            r.join(URI, "r2", "sB", "node-B").toCompletableFuture().join();
            assertEquals(Set.of("node-A", "node-B"),
                    r.nodesForRoom(URI, "r2").toCompletableFuture().join());
        } finally {
            r.shutdown();
            wipe();
        }
    }

    @Test
    @Order(3)
    void removeAllForSessionClearsEveryRoomAtomically() {
        RedisRoomRegistry r = newRegistry();
        try {
            r.join(URI, "ra", "s1", "node-A").toCompletableFuture().join();
            r.join(URI, "rb", "s1", "node-A").toCompletableFuture().join();
            r.join(URI, "rc", "s1", "node-A").toCompletableFuture().join();
            // a co-member so node-A stays in rb's node-set after s1 leaves
            r.join(URI, "rb", "s2", "node-A").toCompletableFuture().join();

            r.removeAllForSession(URI, "s1", "node-A").toCompletableFuture().join();

            assertTrue(r.nodesForRoom(URI, "ra").toCompletableFuture().join().isEmpty(), "ra emptied");
            assertEquals(Set.of("node-A"), r.nodesForRoom(URI, "rb").toCompletableFuture().join(),
                    "rb keeps node-A (s2 still a member)");
            assertTrue(r.nodesForRoom(URI, "rc").toCompletableFuture().join().isEmpty(), "rc emptied");
            // roomsession key for s1 is gone
            assertEquals(0L, conn.sync().exists("netty:roomsession:" + b64(URI) + ":s1"));
        } finally {
            r.shutdown();
            wipe();
        }
    }

    @Test
    @Order(4)
    void removeAllForNodeScrubsEveryRoom() {
        RedisRoomRegistry r = newRegistry();
        try {
            r.join(URI, "rx", "s1", "dead-node").toCompletableFuture().join();
            r.join(URI, "rx", "s2", "live-node").toCompletableFuture().join();
            r.join(URI, "ry", "s3", "dead-node").toCompletableFuture().join();

            r.removeAllForNode("dead-node").toCompletableFuture().join();

            assertEquals(Set.of("live-node"), r.nodesForRoom(URI, "rx").toCompletableFuture().join(),
                    "dead-node scrubbed from rx; live-node remains");
            assertTrue(r.nodesForRoom(URI, "ry").toCompletableFuture().join().isEmpty(),
                    "ry had only dead-node → node-set empty");
            // the per-node member keys for dead-node are gone
            assertEquals(0L, conn.sync().exists("netty:room:{" + b64(URI) + ":" + b64("rx") + "}:n:dead-node"));
            assertEquals(0L, conn.sync().exists("netty:room:{" + b64(URI) + ":" + b64("ry") + "}:n:dead-node"));
        } finally {
            r.shutdown();
            wipe();
        }
    }

    @Test
    @Order(5)
    void concurrentJoinLeaveOnOneRoomStaysConsistent() throws Exception {
        RedisRoomRegistry r = newRegistry();
        try {
            int sessions = 50;
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < sessions; i++) {
                String sid = "cs-" + i;
                futures.add(pool.submit(() -> {
                    r.join(URI, "rconc", sid, "node-A").toCompletableFuture().join();
                    r.leave(URI, "rconc", sid, "node-A").toCompletableFuture().join();
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

            // After every join is matched by a leave, the room's node-set must be empty (no orphan node).
            assertTrue(r.nodesForRoom(URI, "rconc").toCompletableFuture().join().isEmpty(),
                    "balanced concurrent join/leave must leave an empty node-set");
            assertEquals(0L, conn.sync().scard("netty:room:{" + b64(URI) + ":" + b64("rconc") + "}:n:node-A"),
                    "per-node member set must be empty");
        } finally {
            r.shutdown();
            wipe();
        }
    }

    @BeforeEach
    void requireRedis() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available — skipping room registry IT");
    }
}
