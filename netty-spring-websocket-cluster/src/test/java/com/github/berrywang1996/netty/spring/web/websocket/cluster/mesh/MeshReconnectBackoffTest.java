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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RC4c: per-peer reconnect backoff is on the SEND path only; the membership tick (raw connectionTo) dials regardless,
 *  so it stays the recovery probe. Uses the dial-attempt hook to distinguish a backoff-skip from a dialed+failed. */
class MeshReconnectBackoffTest {

    private MeshBroker a;

    private static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        a = new MeshBroker("node-A", new InMemoryMeshNodeDirectory(), new SimpleTextEnvelopeCodec(),
                new NoOpMessageAuthenticator(), new ClusterRuntimeStats(),
                "127.0.0.1", 0, "127.0.0.1", 1_048_576, 30000, 32768, 65536, 300);
        a.setReconnectBackoff(10_000L, 30_000L);   // long base so the window doesn't elapse mid-test
        a.start();
    }

    @AfterEach
    void tearDown() {
        a.shutdown();
    }

    @Test
    void sendPathBacksOffAfterFailedDial_butRawTickDialsRegardless() throws Exception {
        String deadAddr = "127.0.0.1:" + closedPort();   // nothing listening → connect refused

        int d0 = a.dialAttemptsForTest();
        assertNull(a.connectionForSend("node-Z", deadAddr));          // dial #1 → fails → backoff set
        assertEquals(d0 + 1, a.dialAttemptsForTest(), "first send-path connect dials");

        assertNull(a.connectionForSend("node-Z", deadAddr));          // within window → SKIP the dial
        assertEquals(d0 + 1, a.dialAttemptsForTest(), "second send-path connect within backoff does NOT dial");

        assertNull(a.connectionTo("node-Z", deadAddr));               // the raw tick path is NOT gated by backoff
        assertEquals(d0 + 2, a.dialAttemptsForTest(), "raw connectionTo (the tick recovery probe) dials regardless");
    }

    /** RC4d: a backoff-skipped send increments ONLY mesh.reconnect.backoff_skips; a genuine dial failure increments
     *  ONLY mesh.send.failures. The two counters are disjoint (no double-count of a skipped send as a failure). */
    @Test
    void backoffSkipAndFailureAreDisjoint() throws Exception {
        com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterRuntimeStats s = a.runtimeStats();
        String deadAddr = "127.0.0.1:" + closedPort();
        long f0 = s.getMeshSendFailures();
        long b0 = s.getMeshReconnectBackoffSkips();

        a.connectionForSend("node-Z", deadAddr);                 // dial #1 fails → ONE send-failure, NO skip
        assertEquals(f0 + 1, s.getMeshSendFailures(), "a genuine dial failure counts one send-failure");
        assertEquals(b0, s.getMeshReconnectBackoffSkips(), "a dial failure is not a backoff skip");

        a.connectionForSend("node-Z", deadAddr);                 // within backoff → ONE skip, NO new failure
        assertEquals(f0 + 1, s.getMeshSendFailures(), "a backoff-skipped send is NOT counted as a failure");
        assertEquals(b0 + 1, s.getMeshReconnectBackoffSkips(), "a backoff-skipped send counts one skip");
    }

    /** The round-1 MAJOR's end-to-end guard: a node DEGRADED with a send-path backoff for a peer still RECOVERS to
     *  ACTIVE within one membership tick once the peer is reachable, because the tick dials the RAW connectionTo. */
    @Test
    void tickRecoversDegradedNode_despiteSendPathBackoff() throws Exception {
        InMemoryMeshNodeDirectory dir = new InMemoryMeshNodeDirectory();
        MeshBroker node = new MeshBroker("recover-A", dir, new SimpleTextEnvelopeCodec(),
                new NoOpMessageAuthenticator(), new ClusterRuntimeStats(),
                "127.0.0.1", 0, "127.0.0.1", 1_048_576, 30000, 32768, 65536, 300);
        node.setReconnectBackoff(60_000L, 60_000L);   // a 60s backoff — would suppress the SEND path for a minute
        node.start();
        MeshBroker peer = null;
        try {
            int deadPort = closedPort();
            dir.advertise("recover-B", "127.0.0.1", deadPort, 30000).toCompletableFuture().join();
            node.connectionForSend("recover-B", "127.0.0.1:" + deadPort);   // set the send-path backoff for recover-B
            node.membershipTick();   // 1 advertised peer, unreachable → DEGRADED
            assertEquals(BrokerState.DEGRADED, node.state(), "isolated (advertised-but-unreachable peer) → DEGRADED");

            // recover: bring the peer up; peer.start() re-advertises recover-B at its real (reachable) address
            peer = new MeshBroker("recover-B", dir, new SimpleTextEnvelopeCodec(),
                    new NoOpMessageAuthenticator(), new ClusterRuntimeStats(),
                    "127.0.0.1", closedPort(), "127.0.0.1", 1_048_576, 30000, 32768, 65536, 5000);
            peer.start();

            int d0 = node.dialAttemptsForTest();
            node.membershipTick();   // RAW connectionTo to the real addr → reachable → restore ACTIVE (backoff irrelevant)
            assertTrue(node.dialAttemptsForTest() > d0, "the membership tick dials raw despite the send-path backoff");
            assertEquals(BrokerState.ACTIVE, node.state(), "the raw tick recovers the node within one tick");
        } finally {
            node.shutdown();
            if (peer != null) {
                peer.shutdown();
            }
            dir.shutdown();
        }
    }
}
