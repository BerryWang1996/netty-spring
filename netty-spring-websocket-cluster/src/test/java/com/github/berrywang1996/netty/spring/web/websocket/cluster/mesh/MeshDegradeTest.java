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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RC4a M5: the mesh degrades + fires {@code onTransportLost} ONLY on TOTAL isolation (peers expected, zero reachable);
 * a single dead peer stays ACTIVE; recovery restores; a single-node cluster (no peers) stays ACTIVE. This keeps the
 * on-redis-loss/grace/DEGRADED machinery meaningful in mesh mode instead of silently dead.
 */
class MeshDegradeTest {

    private MeshBroker broker;
    private final AtomicInteger lost = new AtomicInteger();
    private final AtomicInteger restored = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        broker = new MeshBroker("node-A", new InMemoryMeshNodeDirectory(), new SimpleTextEnvelopeCodec(),
                new NoOpMessageAuthenticator(), new ClusterRuntimeStats(),
                "127.0.0.1", 0, "127.0.0.1", 1_048_576, 30000, 32768, 65536);
        broker.setTransportStateListener(new ClusterBroker.TransportStateListener() {
            @Override
            public void onTransportLost() {
                lost.incrementAndGet();
            }

            @Override
            public void onTransportRestored() {
                restored.incrementAndGet();
            }
        });
        broker.start(); // → ACTIVE; the 10s membership tick (ttl 30000/3) won't interfere with the fast assertions
    }

    @AfterEach
    void tearDown() {
        broker.shutdown();
    }

    @Test
    void singleDeadPeer_staysActive() {
        broker.evaluateReachability(2, 1); // 2 peers, 1 reachable
        assertEquals(BrokerState.ACTIVE, broker.state());
        assertEquals(0, lost.get());
    }

    @Test
    void totalIsolation_degradesAndNotifiesOnce() {
        broker.evaluateReachability(2, 0); // 2 peers, none reachable → isolated
        assertEquals(BrokerState.DEGRADED, broker.state());
        assertEquals(1, lost.get());
        broker.evaluateReachability(2, 0); // still isolated → no duplicate notification
        assertEquals(1, lost.get());
    }

    @Test
    void recovery_restores() {
        broker.evaluateReachability(2, 0);
        assertEquals(BrokerState.DEGRADED, broker.state());
        broker.evaluateReachability(2, 1); // a peer reachable again
        assertEquals(BrokerState.ACTIVE, broker.state());
        assertEquals(1, restored.get());
    }

    @Test
    void noPeers_staysActive() {
        broker.evaluateReachability(0, 0); // single-node cluster — isolation needs peers-it-should-reach
        assertEquals(BrokerState.ACTIVE, broker.state());
        assertEquals(0, lost.get());
    }
}
