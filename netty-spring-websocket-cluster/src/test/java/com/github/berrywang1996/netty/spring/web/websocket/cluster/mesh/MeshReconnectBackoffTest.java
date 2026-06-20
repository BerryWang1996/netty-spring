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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
