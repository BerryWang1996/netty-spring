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
import io.netty.channel.Channel;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RC4c BL3: a WRITER_IDLE outbound channel (no writes for idle-timeout-ms) is closed + evicted from the cache. */
class MeshIdleReapTest {

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private MeshBroker newBroker(InMemoryMeshNodeDirectory dir, String id, int port) {
        return new MeshBroker(id, dir, new SimpleTextEnvelopeCodec(), new NoOpMessageAuthenticator(),
                new ClusterRuntimeStats(), "127.0.0.1", port, "127.0.0.1", 1_048_576, 30000, 32768, 65536, 5000);
    }

    @Test
    void idleOutboundChannelIsReaped() throws Exception {
        InMemoryMeshNodeDirectory dir = new InMemoryMeshNodeDirectory();
        MeshBroker a = newBroker(dir, "node-A", freePort());
        MeshBroker b = newBroker(dir, "node-B", freePort());
        a.setIdleTimeoutMs(200L);   // short idle so the test is quick
        a.start();
        b.start();
        try {
            String addrB = a.directoryForTest().peers("node-A").toCompletableFuture().join().get("node-B");
            Channel ch = a.connectionTo("node-B", addrB);   // establish + cache
            assertNotNull(ch);
            assertTrue(ch.isActive());
            assertEquals(1, a.activeOutboundConnections(),
                    "RC4d: connections.active reads the live outbound cache (1 after a dial)");

            // no writes for > idleTimeoutMs → WRITER_IDLE fires → channel closes → evicted from the cache
            boolean reaped = false;
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                if (!ch.isActive() && a.outboundForTest().get("node-B") == null) {
                    reaped = true;
                    break;
                }
                Thread.sleep(50);
            }
            assertTrue(reaped, "an idle outbound channel is reaped (closed + evicted from the cache)");
            assertTrue(a.runtimeStats().getMeshIdleReaps() >= 1, "RC4d: an idle reap increments mesh.idle.reaps");
            assertEquals(0, a.activeOutboundConnections(),
                    "RC4d: connections.active drops back to 0 after the idle reap evicts the channel");

            // RC4c §6: the next send re-establishes the connection (the idle reap is transparent to traffic).
            Channel reDialed = a.connectionTo("node-B", addrB);
            assertNotNull(reDialed, "the next send re-dials a fresh channel after the idle reap");
            assertTrue(reDialed.isActive());
            assertTrue(a.outboundForTest().get("node-B") == reDialed, "the re-dialed channel is cached");
        } finally {
            a.shutdown();
            b.shutdown();
            dir.shutdown();
        }
    }
}
