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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisUserRegistry;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.*;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Closes the latent RC2 gap end-to-end on real Redis: a crashed node's {@code netty:user:*} bindings were
 * implemented to be reaped via {@code UserRegistry.removeAllForNode} but that method was NEVER wired into the
 * dead-node reconciliation path in prod — so a hard crash leaked the bindings forever (false-ONLINE →
 * {@code sendToUser} fire-and-forget to a dead session → silent loss).
 *
 * <p>This IT binds user {@code u} with a member on a dead node directly via {@link RedisUserRegistry}, then runs
 * a real {@link ClusterNodeManager} reconciliation sweep (heartbeat reports the dead node expired; the
 * userRegistryReaper is wired exactly as the RC3 auto-config wires it) and asserts the dead-node member is gone.
 * Pre-RC3 this would still leak. Skipped (not failed) without Redis.
 */
class UserRegistryReapRegressionIT {

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static boolean redisAvailable;

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
    }

    @AfterEach
    void tearDown() {
        if (redisAvailable) {
            flush();
        }
    }

    private static void flush() {
        connection.sync().eval("for _,k in ipairs(redis.call('keys','netty:*')) do redis.call('del',k) end",
                ScriptOutputType.INTEGER);
    }

    @Test
    void reconciliationReapsDeadNodeUserBindings() throws Exception {
        RedisUserRegistry userRegistry = new RedisUserRegistry(connection);
        try {
            // u's only device was on the (now dead) node. The binding lingers in Redis.
            userRegistry.bindUser("u", "/ws/chat", "s-dead", "node-dead").toCompletableFuture().join();
            assertTrue(userRegistry.isUserOnline("u").toCompletableFuture().join(),
                    "precondition: u is bound on the dead node");

            // A live node runs reconciliation: heartbeat reports node-dead expired, the reaper claims it, and the
            // userRegistryReaper (wired exactly as RC3 auto-config does) reaps the dead node's bindings.
            ClusterNodeManager mgr = new ClusterNodeManager(
                    "node-live", 600000, 5000, 200, 0, expiredReturning("node-dead"),
                    new InMemorySessionRegistry());
            mgr.setReaper((dead, me, win) -> true);
            mgr.setUserRegistryReaper(userRegistry::removeAllForNode);
            mgr.start();
            try {
                boolean reaped = waitFor(
                        () -> !userRegistry.isUserOnline("u").toCompletableFuture().join(), 4000);
                assertTrue(reaped, "the dead node's user binding must be reaped by reconciliation (RC2 gap closed)");
                assertTrue(userRegistry.sessionsForUser("u").toCompletableFuture().join().isEmpty(),
                        "no stale member remains for u after the dead node is reaped");
            } finally {
                mgr.shutdown();
            }
        } finally {
            userRegistry.shutdown();
        }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return cond.getAsBoolean();
    }

    private static ClusterNodeHeartbeat expiredReturning(String deadNodeId) {
        return new ClusterNodeHeartbeat() {
            @Override public void register(String nodeId, long timeoutMs) {}
            @Override public void renewHeartbeat(String nodeId, long timeoutMs) {}
            @Override public void deregister(String nodeId) {}
            @Override public List<String> findExpiredNodes(long timeoutMs) { return Collections.singletonList(deadNodeId); }
        };
    }
}
