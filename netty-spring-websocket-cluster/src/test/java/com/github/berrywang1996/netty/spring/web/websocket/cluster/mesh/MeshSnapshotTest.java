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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MeshNodeDirectory;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RC4c BL5: publish/unicast read the in-memory peerSnapshot (Redis off the hot path); unicast warms on a miss. */
class MeshSnapshotTest {

    /** A directory whose peers() count is observable and which can be flipped to throw. */
    static final class CountingDirectory implements MeshNodeDirectory {
        final Map<String, String> addrs = new HashMap<>();
        final AtomicInteger peersCalls = new AtomicInteger();
        volatile boolean throwOnPeers = false;

        public CompletionStage<Void> advertise(String n, String h, int p, long t) {
            addrs.put(n, h + ":" + p);
            return CompletableFuture.completedFuture(null);
        }

        public CompletionStage<Map<String, String>> peers(String self) {
            peersCalls.incrementAndGet();
            if (throwOnPeers) {
                CompletableFuture<Map<String, String>> f = new CompletableFuture<>();
                f.completeExceptionally(new RuntimeException("redis down"));
                return f;
            }
            Map<String, String> out = new HashMap<>(addrs);
            out.remove(self);
            return CompletableFuture.completedFuture(out);
        }

        public CompletionStage<Void> remove(String n) {
            addrs.remove(n);
            return CompletableFuture.completedFuture(null);
        }

        public void shutdown() {
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private MeshBroker newBroker(MeshNodeDirectory dir, int port) {
        return new MeshBroker("node-A", dir, new SimpleTextEnvelopeCodec(), new NoOpMessageAuthenticator(),
                new ClusterRuntimeStats(), "127.0.0.1", port, "127.0.0.1", 1_048_576, 30000, 32768, 65536, 5000);
    }

    private static ClusterEnvelope bc(String uri, String body) {
        return new ClusterEnvelope("node-A", uri, ClusterEnvelope.MessageKind.BROADCAST,
                body.getBytes(StandardCharsets.UTF_8), null, null, 1L);
    }

    @Test
    void publishReadsSnapshot_noPeersCallOnHotPath() throws Exception {
        CountingDirectory dir = new CountingDirectory();
        MeshBroker a = newBroker(dir, freePort());
        a.start();   // does the bounded initial snapshot populate (1 peers() call)
        try {
            int afterStart = dir.peersCalls.get();
            dir.throwOnPeers = true;                 // any further peers() would now throw
            a.publish("/ws/x", bc("/ws/x", "hi"));   // must NOT call peers() — reads the snapshot
            assertEquals(afterStart, dir.peersCalls.get(), "publish must not hit the directory on the hot path");
        } finally {
            a.shutdown();
        }
    }

    @Test
    void unicastFreshTarget_fallsBackAndWarmsSnapshot() throws Exception {
        CountingDirectory dir = new CountingDirectory();
        MeshBroker a = newBroker(dir, freePort());
        a.start();
        try {
            // A target advertised AFTER the initial snapshot (snapshot miss). Unreachable but resolvable address.
            dir.addrs.put("node-Z", "127.0.0.1:" + freePort());
            int before = dir.peersCalls.get();
            a.unicast("node-Z", new ClusterEnvelope("node-A", "/ws/x", ClusterEnvelope.MessageKind.UNICAST,
                    "dm".getBytes(StandardCharsets.UTF_8), "s1", null, 1L));
            assertTrue(dir.peersCalls.get() > before, "unicast miss falls back to a direct peers() read");
            int afterFallback = dir.peersCalls.get();
            // a SECOND unicast to the same fresh target must hit the warmed snapshot (no new peers() call)
            a.unicast("node-Z", new ClusterEnvelope("node-A", "/ws/x", ClusterEnvelope.MessageKind.UNICAST,
                    "dm2".getBytes(StandardCharsets.UTF_8), "s1", null, 1L));
            assertEquals(afterFallback, dir.peersCalls.get(), "the fallback warmed the snapshot — no re-SCAN");
        } finally {
            a.shutdown();
        }
    }
}
