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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.InMemoryMeshNodeDirectory;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterSubscription;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MeshNodeDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two real MeshBrokers on ephemeral localhost ports (in-JVM, no Redis): direct unicast + naive broadcast over TCP,
 *  self-suppression (no loopback to the publisher), state, and subscribe/unsubscribe. */
class MeshBrokerTest {

    private MeshNodeDirectory dir;
    private MeshBroker a;
    private MeshBroker b;
    private final List<String> recvA = new CopyOnWriteArrayList<>();
    private final List<String> recvB = new CopyOnWriteArrayList<>();

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        dir = new InMemoryMeshNodeDirectory();
        int portA = freePort();
        int portB = freePort();
        a = newBroker("node-A", portA);
        b = newBroker("node-B", portB);
        a.start();
        b.start();
    }

    private MeshBroker newBroker(String nodeId, int port) {
        return new MeshBroker(nodeId, dir, new SimpleTextEnvelopeCodec(), new NoOpMessageAuthenticator(),
                new ClusterRuntimeStats(), "127.0.0.1", port, "127.0.0.1", 1_048_576, 30000, 32768, 65536);
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

    private static ClusterEnvelope broadcast(String origin, String uri, String body) {
        return new ClusterEnvelope(origin, uri, ClusterEnvelope.MessageKind.BROADCAST,
                body.getBytes(StandardCharsets.UTF_8), null, null, 1L);
    }

    private static ClusterEnvelope unicast(String origin, String uri, String sessionId, String body) {
        return new ClusterEnvelope(origin, uri, ClusterEnvelope.MessageKind.UNICAST,
                body.getBytes(StandardCharsets.UTF_8), sessionId, null, 1L);
    }

    private static String body(ClusterEnvelope e) {
        return new String(e.getPayload(), StandardCharsets.UTF_8);
    }

    @Test
    void unicast_deliversToTargetPeerOverTcp() throws Exception {
        a.subscribeUnicast("node-A", env -> recvA.add(body(env)));
        b.unicast("node-A", unicast("node-B", "/ws/chat", "s1", "dm-from-B"));
        assertTrue(waitFor(() -> recvA.contains("dm-from-B"), 4000), "node-A received the direct unicast over TCP");
    }

    @Test
    void publish_deliversToAllPeers_naive() throws Exception {
        a.subscribe("/ws/chat", env -> recvA.add(body(env)));
        b.publish("/ws/chat", broadcast("node-B", "/ws/chat", "hello-room"));
        assertTrue(waitFor(() -> recvA.contains("hello-room"), 4000), "node-A received the broadcast over TCP");
    }

    @Test
    void publish_doesNotLoopBackToPublisher() throws Exception {
        b.subscribe("/ws/chat", env -> recvB.add(body(env)));
        b.publish("/ws/chat", broadcast("node-B", "/ws/chat", "self"));
        // the broker sends only to peers (A), never to self; B's own listener is not invoked via the mesh
        Thread.sleep(300);
        assertFalse(recvB.contains("self"), "publisher's own broker listener is not looped back");
    }

    @Test
    void state_activeWhileBound_shutdownAfter() {
        assertEquals(BrokerState.ACTIVE, a.state());
        a.shutdown();
        assertEquals(BrokerState.SHUTDOWN, a.state());
        a = null; // already shut down
    }

    @Test
    void subscribe_unsubscribe() {
        ClusterSubscription sub = a.subscribe("/ws/chat", env -> { });
        assertTrue(sub.isActive());
        sub.unsubscribe();
        assertFalse(sub.isActive());
    }
}
