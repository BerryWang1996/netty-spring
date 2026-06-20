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

package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterRuntimeStats;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.mesh.MeshBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.NodeState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MeshNodeDirectory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RC4d (impl-review RC4d-C1): the BL6 end-to-end loop — a REAL {@link MeshBroker} bound through
 * {@link NettyClusterMeterBinder} into a real registry, where an actual broadcast moves the bound
 * {@code netty.cluster.mesh.frames.sent} FunctionCounter (proving the binder reads the broker's OWN stats,
 * not the sender's) and the {@code netty.cluster.mesh.peers.known} gauge reads the real accessor. The unit
 * test {@code NettyClusterMeterBinderTest} covers the wiring with a mock broker; this closes the live loop.
 */
class NettyClusterMeshMetricsIntegrationTest {

    /** In-process {@link MeshNodeDirectory} (the cluster module's test stub is not on the starter test classpath). */
    static final class InMemoryDirectory implements MeshNodeDirectory {
        private final Map<String, String> addrs = new ConcurrentHashMap<>();

        public CompletionStage<Void> advertise(String nodeId, String host, int port, long ttlMs) {
            addrs.put(nodeId, host + ":" + port);
            return CompletableFuture.completedFuture(null);
        }

        public CompletionStage<Map<String, String>> peers(String selfNodeId) {
            Map<String, String> out = new HashMap<>(addrs);
            out.remove(selfNodeId);
            return CompletableFuture.completedFuture(out);
        }

        public CompletionStage<Void> remove(String nodeId) {
            addrs.remove(nodeId);
            return CompletableFuture.completedFuture(null);
        }

        public void shutdown() {
            addrs.clear();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static MeshBroker broker(MeshNodeDirectory dir, String id, int port) {
        return new MeshBroker(id, dir, new SimpleTextEnvelopeCodec(), new NoOpMessageAuthenticator(),
                new ClusterRuntimeStats(), "127.0.0.1", port, "127.0.0.1", 1_048_576, 30000, 32768, 65536, 5000);
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

    @Test
    void realBroadcastMovesBoundMeshFramesSentCounter() throws Exception {
        InMemoryDirectory dir = new InMemoryDirectory();
        MeshBroker a = broker(dir, "metrics-A", freePort());
        MeshBroker b = broker(dir, "metrics-B", freePort());
        List<String> recvB = new CopyOnWriteArrayList<>();
        // Start B first so it has advertised its address AND bound its server before A's start-time snapshot populate
        // reads the directory — A then knows + can reach B immediately, with no reliance on the periodic membership tick
        // (which is package-private and ~1s+ away).
        b.start();
        b.subscribe("/ws/m", env -> recvB.add(new String(env.getPayload(), StandardCharsets.UTF_8)));
        a.start();
        try {
            // Bind the REAL broker A through the binder into a real registry. The mesh meters must read A's OWN stats.
            ClusterMessageSender sender = mock(ClusterMessageSender.class);
            when(sender.getClusterRuntimeStats()).thenReturn(mock(ClusterRuntimeStats.class));   // sender's (non-mesh) stats
            ClusterNodeManager nodeManager = mock(ClusterNodeManager.class);
            when(nodeManager.getState()).thenReturn(NodeState.ACTIVE);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new NettyClusterMeterBinder(sender, nodeManager, a, new NoOpMessageAuthenticator()).bindTo(registry);

            // a real broadcast A -> B over TCP, retried until B receives (covers the connect warm-up)
            assertTrue(waitFor(() -> {
                a.publish("/ws/m", new ClusterEnvelope("metrics-A", "/ws/m", ClusterEnvelope.MessageKind.BROADCAST,
                        "hi".getBytes(StandardCharsets.UTF_8), null, null, 1L));
                return recvB.contains("hi");
            }, 5000), "B received the broadcast over real TCP");

            // the BOUND FunctionCounter reflects A's own stats — proving BL6 end-to-end (not the sender's stats).
            assertTrue(registry.get("netty.cluster.mesh.frames.sent").functionCounter().count() > 0.0,
                    "a real broadcast moves the bound netty.cluster.mesh.frames.sent counter");
            // and the gauge reads the real accessor: A knows exactly one peer (B).
            assertEquals(1.0, registry.get("netty.cluster.mesh.peers.known").gauge().value(),
                    "netty.cluster.mesh.peers.known reflects the real knownPeerCount()");
        } finally {
            a.shutdown();
            b.shutdown();
            dir.shutdown();
        }
    }
}
