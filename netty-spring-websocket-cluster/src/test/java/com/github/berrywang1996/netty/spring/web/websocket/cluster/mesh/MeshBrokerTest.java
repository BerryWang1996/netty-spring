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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterSubscription;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MeshNodeDirectory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void connectionTo_replacesDeadCachedChannel_noLeak() throws Exception {
        // RC4a BL1: a dead-but-still-mapped outbound entry (its async closeFuture-remove hasn't fired yet) must be
        // EVICTED and replaced by the freshly dialed live channel — not left in the map while the new channel is
        // returned-but-never-cached (a connection/fd leak + per-send reconnect storm on a flapping peer).
        Channel dead = mock(Channel.class);
        when(dead.isActive()).thenReturn(false);
        a.outboundForTest().put("node-B", dead);

        String addrB = a.directoryForTest().peers("node-A").toCompletableFuture().join().get("node-B");
        Channel live = a.connectionTo("node-B", addrB);

        assertNotNull(live, "a live channel to node-B was dialed");
        assertTrue(live.isActive(), "the returned channel is live");
        assertSame(live, a.outboundForTest().get("node-B"), "the dead entry was replaced by the live channel (no leak)");
    }

    /** RC4d: writeFramed's async write-FAILURE branch increments mesh.send.failures (not frames.sent). The peer
     *  channel is writable (passes the backpressure guard) but its write completes exceptionally. */
    @Test
    void writeFramed_asyncWriteFailure_countsSendFailureNotFrameSent() {
        EmbeddedChannel failing = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                promise.setFailure(new IOException("forced write failure"));
            }
        });
        assertTrue(failing.isWritable(), "an empty EmbeddedChannel is writable (so we hit the write path, not backpressure)");
        long sent0 = a.runtimeStats().getMeshFramesSent();
        long fail0 = a.runtimeStats().getMeshSendFailures();

        a.writeFramed("node-Z", failing, "payload");
        failing.runPendingTasks();

        assertEquals(fail0 + 1, a.runtimeStats().getMeshSendFailures(), "a failed async write increments send.failures");
        assertEquals(sent0, a.runtimeStats().getMeshFramesSent(), "a failed write does NOT increment frames.sent");
    }

    /** A unicast to this node's own id is a no-op (origin self-suppression on the send side) — no send, no failure. */
    @Test
    void unicast_toSelf_isNoOp() {
        long fail0 = a.runtimeStats().getMeshSendFailures();
        a.unicast("node-A", unicast("node-A", "/ws/x", "s1", "self-dm"));   // target == own nodeId
        assertEquals(fail0, a.runtimeStats().getMeshSendFailures(), "unicast to self sends nothing and counts no failure");
    }

    /** connectionTo with a malformed address (no host:port colon) returns null rather than throwing. */
    @Test
    void connectionTo_malformedAddress_returnsNull() {
        assertNull(a.connectionTo("node-X", "no-colon-here"), "a malformed peer address yields no channel");
    }

    /** onNodeLeft with no interest router wired is a harmless no-op (the router is nullable). */
    @Test
    void onNodeLeft_withoutRouter_isNoOp() {
        a.onNodeLeft("ghost-node");   // no interest router on this broker → no-op, no NPE
        assertEquals(BrokerState.ACTIVE, a.state());
    }

    /** onNodeLeft delegates the dead-node reap to the interest router (which clears the node from the registry). */
    @Test
    void onNodeLeft_withRouter_reapsTheNodeFromInterest() throws Exception {
        InMemoryMeshInterestRegistry reg = new InMemoryMeshInterestRegistry();
        reg.subscribe("/ws/x", "s1", "node-Z").toCompletableFuture().join();
        a.setInterestRouter(new MeshInterestRouter(reg, Set.of(), 50L, 2000L));
        assertTrue(reg.nodesForUri("/ws/x").toCompletableFuture().join().contains("node-Z"));

        a.onNodeLeft("node-Z");

        assertFalse(reg.nodesForUri("/ws/x").toCompletableFuture().join().contains("node-Z"),
                "onNodeLeft delegates to the interest router's dead-node reap");
    }

    /** deliver routes a CLOSE-kind envelope to the unicast listener (kind-based routing, not URI-based). */
    @Test
    void deliver_closeKind_routesToUnicastListener() {
        List<String> kinds = new CopyOnWriteArrayList<>();
        a.subscribeUnicast("node-A", env -> kinds.add(env.getKind().name()));
        ClusterEnvelope close = new ClusterEnvelope("node-B", "/ws/x", ClusterEnvelope.MessageKind.CLOSE,
                "bye".getBytes(StandardCharsets.UTF_8), "s1", null, 1L);
        a.deliver(close);
        assertTrue(kinds.contains("CLOSE"), "a CLOSE envelope routes to the unicast listener");
    }

    /** deliver of a BROADCAST whose URI has no local listener is a silent drop (RC4a naive-broadcast no-op). */
    @Test
    void deliver_broadcastWithNoLocalListener_isSilentDrop() {
        a.deliver(broadcast("node-B", "/ws/nobody-listening", "x"));   // no subscriber for this URI → no-op, no throw
        assertEquals(BrokerState.ACTIVE, a.state());
    }
}
