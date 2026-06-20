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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.InMemoryMeshInterestRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MeshInterestRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshInterestRouterTest {

    private MeshInterestRouter router(MeshInterestRegistry reg) {
        return new MeshInterestRouter(reg, Set.of("__presence__"), 5000L, 2000L);
    }

    @Test
    void reservedChannel_returnsNull_allPeers() {
        MeshInterestRouter r = router(new InMemoryMeshInterestRegistry());
        assertNull(r.nodesForUriCached("__presence__"), "reserved channels bypass interest pruning");
    }

    @Test
    void authoritativeRead_returnsTheSet_evenWhenEmpty() {
        InMemoryMeshInterestRegistry reg = new InMemoryMeshInterestRegistry();
        MeshInterestRouter r = router(reg);
        assertTrue(r.nodesForUriCached("/ws/a").isEmpty(), "authoritative empty ⇒ prune all (non-null empty)");

        reg.subscribe("/ws/a", "s1", "node-B").toCompletableFuture().join();
        // first read may be cached-empty; clear cache to read fresh
        r.onNodeLeft("force-clear-unused");
        assertEquals(Set.of("node-B"), r.nodesForUriCached("/ws/a"));
    }

    @Test
    void readFailure_returnsNull_andIsNotCached() {
        MeshInterestRegistry failing = new MeshInterestRegistry() {
            public CompletionStage<Void> subscribe(String u, String s, String n) { return CompletableFuture.completedFuture(null); }
            public CompletionStage<Void> unsubscribe(String u, String s, String n) { return CompletableFuture.completedFuture(null); }
            public CompletionStage<Void> removeAllForNode(String n) { return CompletableFuture.completedFuture(null); }
            public CompletionStage<Set<String>> nodesForUri(String u) {
                CompletableFuture<Set<String>> f = new CompletableFuture<>();
                f.completeExceptionally(new RuntimeException("redis blip"));
                return f;
            }
            public void shutdown() { }
        };
        MeshInterestRouter r = router(failing);
        assertNull(r.nodesForUriCached("/ws/a"), "read failure ⇒ null ⇒ all-peers fallback");
        // second call must attempt the read again (null not cached) — still null, but proves no cached-null poisoning
        assertNull(r.nodesForUriCached("/ws/a"));
    }

    /** RC4b R6: onNodeLeft clears the send-cache (so a re-read is fresh) AND reaps the registry (removeAllForNode). */
    @Test
    void onNodeLeft_clearsCache_andReapsRegistry() {
        List<String> reaped = new ArrayList<>();
        InMemoryMeshInterestRegistry backing = new InMemoryMeshInterestRegistry();
        backing.subscribe("/ws/a", "s1", "node-B").toCompletableFuture().join();
        MeshInterestRegistry recording = new MeshInterestRegistry() {
            public CompletionStage<Void> subscribe(String u, String s, String n) { return backing.subscribe(u, s, n); }
            public CompletionStage<Void> unsubscribe(String u, String s, String n) { return backing.unsubscribe(u, s, n); }
            public CompletionStage<Void> removeAllForNode(String n) { reaped.add(n); return backing.removeAllForNode(n); }
            public CompletionStage<Set<String>> nodesForUri(String u) { return backing.nodesForUri(u); }
            public void shutdown() { }
        };
        // long TTL so only onNodeLeft (not expiry) can clear the cache
        MeshInterestRouter r = new MeshInterestRouter(recording, Set.of(), 60_000L, 2000L);
        assertEquals(Set.of("node-B"), r.nodesForUriCached("/ws/a")); // primes the cache

        r.onNodeLeft("node-B");

        assertEquals(List.of("node-B"), reaped, "registry.removeAllForNode invoked for the dead node");
        assertTrue(r.nodesForUriCached("/ws/a").isEmpty(),
                "cache cleared → fresh read reflects the reap (node-B gone)");
    }
}
