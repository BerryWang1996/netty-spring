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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvReaper;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvSessionRegistry;
import io.nats.client.Connection;
import io.nats.client.api.KeyValueConfiguration;
import org.junit.jupiter.api.*;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration ORACLE for the NATS JetStream-KV impls over a real {@code nats:2.10 -js} (Testcontainers).
 * Proves the registry register/lookup/deregister round-trip, the timestamp-liveness heartbeat, and the
 * create-if-absent single-winner reaper claim actually work against a live JetStream KV store.
 * <p>
 * Long-running tests (&ge;10 s) are tagged {@code @Tag("slow")} so CI profiles can optionally
 * skip them via {@code -DexcludedGroups=slow}.
 */
class NatsKvIntegrationTest {

    private static Connection conn;
    private static boolean available;

    @BeforeAll
    static void up() throws Exception {
        available = ClusterTestNatsJetStream.available();
        Assumptions.assumeTrue(available, "no JetStream NATS (no env + no Docker)");
        conn = ClusterTestNatsJetStream.newConnection();
        for (String b : List.of("netty-sessions", "netty-nodes", "netty-reaping")) {
            if (!conn.keyValueManagement().getBucketNames().contains(b)) {
                KeyValueConfiguration.Builder cfg = KeyValueConfiguration.builder().name(b);
                if ("netty-reaping".equals(b)) {
                    // Mirror production (NettyWebSocketClusterConfigure uses 30s); 10s keeps the
                    // claim-expiry IT reasonably fast while still exercising the maxAge path.
                    // jnats exposes KV-bucket maxAge as ttl(Duration).
                    cfg = cfg.ttl(java.time.Duration.ofSeconds(10));
                }
                conn.keyValueManagement().create(cfg.build());
            }
        }
    }

    @AfterAll
    static void down() throws Exception {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    void registry_registerLookupDeregister() throws Exception {
        NatsKvSessionRegistry reg = new NatsKvSessionRegistry(conn.keyValue("netty-sessions"));
        reg.register("/ws/kv", "kid-1", "node-A", Collections.emptyMap()).toCompletableFuture().join();
        assertEquals("node-A", reg.lookupNode("/ws/kv", "kid-1").toCompletableFuture().join());
        assertTrue(reg.clusterSessionIds("/ws/kv").toCompletableFuture().join().contains("kid-1"));
        reg.deregister("/ws/kv", "kid-1").toCompletableFuture().join();
        assertNull(reg.lookupNode("/ws/kv", "kid-1").toCompletableFuture().join());
    }

    @Test
    void heartbeat_staleDetected_freshExcluded() throws Exception {
        NatsKvNodeHeartbeat hb = new NatsKvNodeHeartbeat(conn.keyValue("netty-nodes"));
        hb.register("kv-stale", 200);
        Thread.sleep(400); // let kv-stale's timestamp age past 200ms
        hb.register("kv-fresh", 60000);
        List<String> expired = hb.findExpiredNodes(200);
        assertTrue(expired.contains("kv-stale"), "stale node detected via timestamp");
        assertFalse(expired.contains("kv-fresh"), "freshly-registered node excluded");
        hb.deregister("kv-stale");
        hb.deregister("kv-fresh");
    }

    @Test
    void reaper_claimOnceSingleWinner() throws Exception {
        NatsKvReaper r1 = new NatsKvReaper(conn.keyValue("netty-reaping"));
        NatsKvReaper r2 = new NatsKvReaper(conn.keyValue("netty-reaping"));
        boolean w1 = r1.tryClaim("kv-dead", "node-1", 5000);
        boolean w2 = r2.tryClaim("kv-dead", "node-2", 5000);
        assertTrue(w1 ^ w2, "exactly one winner");
        assertTrue(w1, "first claimant wins");
        assertFalse(w2, "second locked out");
    }

    @Tag("slow")
    @Test
    void reaper_claimExpires_thenReclaimSucceeds() throws Exception {
        NatsKvReaper r1 = new NatsKvReaper(conn.keyValue("netty-reaping"));
        NatsKvReaper r2 = new NatsKvReaper(conn.keyValue("netty-reaping"));

        assertTrue(
                r1.tryClaim("expiry-target", "node-1", 5000),
                "first claim must succeed");

        // P4: replaced blind 12s sleep with poll-until-success (bounded 15s) — safer against
        // JetStream housekeeping jitter on slow CI. Bucket maxAge is 10s, so wait at least 11s before polling.
        Thread.sleep(11_000);
        long deadline = System.currentTimeMillis() + 4_000;
        boolean won = false;
        while (!won && System.currentTimeMillis() < deadline) {
            won = r2.tryClaim("expiry-target", "node-2", 5000);
            if (!won) Thread.sleep(100);
        }
        assertTrue(won, "after maxAge expiry, re-claim by a different reaper must succeed");
    }
}
