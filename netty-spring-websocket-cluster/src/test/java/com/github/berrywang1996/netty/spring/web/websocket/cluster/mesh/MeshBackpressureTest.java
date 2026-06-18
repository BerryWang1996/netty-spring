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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RC4a M1 (the verified BLOCKER) regression: when a peer's outbound channel is NOT writable (its buffer is at the high
 * watermark — a slow peer), {@code writeFramed} DROPS the frame and counts it, rather than buffering it unboundedly
 * (which would OOM the sender). A writable channel writes normally.
 */
class MeshBackpressureTest {

    private final ClusterRuntimeStats stats = new ClusterRuntimeStats();

    private MeshBroker broker() {
        return new MeshBroker("node-A", new InMemoryMeshNodeDirectory(), new SimpleTextEnvelopeCodec(),
                new NoOpMessageAuthenticator(), stats,
                "127.0.0.1", 0, "127.0.0.1", 1_048_576, 30000, 32768, 65536, 5000);
    }

    @Test
    void nonWritableChannel_dropsFrameAndCounts() {
        MeshBroker b = broker();
        Channel ch = mock(Channel.class);
        when(ch.isWritable()).thenReturn(false);

        b.writeFramed("node-B", ch, "H1:tag:wire");

        assertEquals(1, stats.getMeshSendDroppedBackpressure(), "frame dropped + counted");
        verify(ch, never()).writeAndFlush(any());
    }

    @Test
    void writableChannel_writes() {
        MeshBroker b = broker();
        Channel ch = mock(Channel.class);
        when(ch.isWritable()).thenReturn(true);
        when(ch.writeAndFlush(any())).thenReturn(mock(io.netty.channel.ChannelFuture.class));

        b.writeFramed("node-B", ch, "H1:tag:wire");

        assertEquals(0, stats.getMeshSendDroppedBackpressure());
        verify(ch).writeAndFlush(any());
    }
}
