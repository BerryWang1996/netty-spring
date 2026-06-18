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
import io.netty.channel.ChannelOption;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RC4a MF2: the {@code connect-timeout-ms} knob must actually reach the outbound client bootstrap as
 * {@link ChannelOption#CONNECT_TIMEOUT_MILLIS} — otherwise {@code connectionTo()}'s blocking {@code cf.sync()} on the
 * publish/unicast caller thread falls back to Netty's 30s default and a dead/black-holing peer stalls the hot path.
 */
class MeshConnectTimeoutTest {

    @Test
    void connectTimeout_isAppliedToClientBootstrap() throws Exception {
        MeshBroker broker = new MeshBroker("node-A", new InMemoryMeshNodeDirectory(), new SimpleTextEnvelopeCodec(),
                new NoOpMessageAuthenticator(), new ClusterRuntimeStats(),
                "127.0.0.1", 0, "127.0.0.1", 1_048_576, 30000, 32768, 65536, 1234);
        broker.start();
        try {
            Object applied = broker.clientBootstrapForTest().config().options().get(ChannelOption.CONNECT_TIMEOUT_MILLIS);
            assertEquals(Integer.valueOf(1234), applied, "configured mesh connect-timeout-ms is set on the bootstrap");
        } finally {
            broker.shutdown();
        }
    }
}
