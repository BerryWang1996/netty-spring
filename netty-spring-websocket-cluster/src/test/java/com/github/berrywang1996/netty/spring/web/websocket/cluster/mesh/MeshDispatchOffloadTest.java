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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.EnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RC4a M3: the listener callback runs on the mesh DISPATCH pool, never on the caller (Netty I/O) thread — decode
 * happens on the I/O loop, delivery is handed off. Asserted by capturing the thread the listener runs on.
 */
class MeshDispatchOffloadTest {

    private MeshBroker broker;

    @AfterEach
    void tearDown() {
        if (broker != null) {
            broker.shutdown();
        }
    }

    @Test
    void listenerRunsOnDispatchPool_notCallerThread() throws Exception {
        EnvelopeCodec codec = new SimpleTextEnvelopeCodec();
        MessageAuthenticator auth = new NoOpMessageAuthenticator();
        broker = new MeshBroker("node-A", new InMemoryMeshNodeDirectory(), codec, auth, new ClusterRuntimeStats(),
                "127.0.0.1", 0, "127.0.0.1", 1_048_576, 30000, 32768, 65536);

        CompletableFuture<String> listenerThread = new CompletableFuture<>();
        broker.subscribe("/ws/chat", env -> listenerThread.complete(Thread.currentThread().getName()));

        // Feed a decoded-on-this-thread inbound frame (no TCP needed); delivery must be offloaded.
        ClusterEnvelope env = new ClusterEnvelope("node-B", "/ws/chat", ClusterEnvelope.MessageKind.BROADCAST,
                "hi".getBytes(StandardCharsets.UTF_8), null, null, 1L);
        String wire = auth.wrap(codec.encode(env));
        String callerThread = Thread.currentThread().getName();

        broker.onInboundFrame(wire);

        String ranOn = listenerThread.get(4, TimeUnit.SECONDS);
        assertTrue(ranOn.startsWith("cluster-mesh-dispatch"), "listener ran on the dispatch pool, was: " + ranOn);
        assertNotEquals(callerThread, ranOn, "listener did NOT run on the caller (decode/IO) thread");
    }
}
