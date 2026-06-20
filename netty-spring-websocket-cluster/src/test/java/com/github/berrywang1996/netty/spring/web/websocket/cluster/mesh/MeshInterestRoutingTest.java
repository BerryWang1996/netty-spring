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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterRuntimeStats;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.InMemoryMeshInterestRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.InMemoryMeshNodeDirectory;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MeshNodeDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RC4b: MeshBroker.publish routes only to peers in the interest set; uninterested peers are skipped. */
class MeshInterestRoutingTest {

    private MeshNodeDirectory dir;
    private InMemoryMeshInterestRegistry interest;
    private MeshBroker a;
    private MeshBroker b;
    private final List<String> recvB = new CopyOnWriteArrayList<>();

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        dir = new InMemoryMeshNodeDirectory();
        interest = new InMemoryMeshInterestRegistry();
        a = newBroker("node-A", freePort());
        b = newBroker("node-B", freePort());
        // node-A routes via interest; node-B just receives. 50ms TTL = snappy re-read between publishes.
        a.setInterestRouter(new MeshInterestRouter(interest, Set.of(), 50L, 2000L));
        a.start();
        b.start();
        b.subscribe("/ws/a", env -> recvB.add(body(env)));
        b.subscribe("/ws/b", env -> recvB.add(body(env)));
        // RC4c BL5: publish reads A's peer SNAPSHOT (not a live Redis read); B advertised after A's start()-time
        // populate, so refresh A's snapshot via the membership tick (the real periodic tick does this every ~10s).
        a.membershipTick();
    }

    private MeshBroker newBroker(String nodeId, int port) {
        return new MeshBroker(nodeId, dir, new SimpleTextEnvelopeCodec(), new NoOpMessageAuthenticator(),
                new ClusterRuntimeStats(), "127.0.0.1", port, "127.0.0.1", 1_048_576, 30000, 32768, 65536, 5000);
    }

    @AfterEach
    void tearDown() {
        if (a != null) {
            a.shutdown();
        }
        if (b != null) {
            b.shutdown();
        }
        dir.shutdown();
        interest.shutdown();
    }

    private static boolean waitFor(BooleanSupplier cond, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return cond.getAsBoolean();
    }

    private static ClusterEnvelope bc(String uri, String body) {
        return new ClusterEnvelope("node-A", uri, ClusterEnvelope.MessageKind.BROADCAST,
                body.getBytes(StandardCharsets.UTF_8), null, null, 1L);
    }

    private static String body(ClusterEnvelope e) {
        return new String(e.getPayload(), StandardCharsets.UTF_8);
    }

    @Test
    void publishTargetsOnlyInterestedNodes() throws Exception {
        // node-B has a live session for /ws/a only.
        interest.subscribe("/ws/a", "s1", "node-B").toCompletableFuture().join();

        assertTrue(waitFor(() -> {
            a.publish("/ws/a", bc("/ws/a", "hello-a"));
            return recvB.contains("hello-a");
        }, 4000), "B (interested in /ws/a) receives the /ws/a broadcast");

        // B is NOT interested in /ws/b → must not receive it.
        a.publish("/ws/b", bc("/ws/b", "hello-b"));
        Thread.sleep(300);
        assertFalse(recvB.contains("hello-b"), "B (no /ws/b session) is pruned from the /ws/b broadcast");
    }

    /** RC4d: publish records the inline-filtered fan-out — the interested-peer count, or 0 when all peers are pruned. */
    @Test
    void fanoutRecordsTargetedPeerCount() throws Exception {
        interest.subscribe("/ws/a", "s1", "node-B").toCompletableFuture().join();
        assertTrue(waitFor(() -> {
            a.publish("/ws/a", bc("/ws/a", "hi"));
            return recvB.contains("hi");
        }, 4000), "B (interested in /ws/a) receives the broadcast");
        assertEquals(1, a.runtimeStats().getMeshFanoutTargetsLast(),
                "fan-out records the single interested peer for /ws/a");

        a.publish("/ws/b", bc("/ws/b", "hi-b"));   // B not interested → all pruned
        assertEquals(0, a.runtimeStats().getMeshFanoutTargetsLast(),
                "an all-pruned broadcast records fan-out 0");
    }

    /** RC4d: a successful mesh write increments mesh.frames.sent (counted on the async write-success listener). */
    @Test
    void framesSentCountsSuccessfulWrites() throws Exception {
        interest.subscribe("/ws/a", "s1", "node-B").toCompletableFuture().join();
        long sent0 = a.runtimeStats().getMeshFramesSent();
        assertTrue(waitFor(() -> {
            a.publish("/ws/a", bc("/ws/a", "hello-a"));
            return recvB.contains("hello-a");
        }, 4000), "B receives the /ws/a broadcast over TCP");
        assertTrue(a.runtimeStats().getMeshFramesSent() >= sent0 + 1,
                "a successful mesh write increments frames.sent");
    }

    /** RC4b R2: a reserved channel (e.g. PRESENCE_CHANNEL) BYPASSES interest pruning — even with an empty/excluding
     *  interest set, the router returns null ⇒ publish goes all-peers ⇒ B still receives. Guards against presence
     *  being silently pruned to zero. */
    @Test
    void reservedChannel_bypassesPruning_allPeersReceive() throws Exception {
        a.setInterestRouter(new MeshInterestRouter(interest, Set.of("/ws/reserved"), 50L, 2000L));
        b.subscribe("/ws/reserved", env -> recvB.add(body(env)));
        // No interest is registered for /ws/reserved → an authoritative-empty read would prune B; but it is reserved.
        assertTrue(waitFor(() -> {
            a.publish("/ws/reserved", bc("/ws/reserved", "presence-like"));
            return recvB.contains("presence-like");
        }, 4000), "reserved channel bypasses interest pruning — B receives despite empty interest");
    }
}
