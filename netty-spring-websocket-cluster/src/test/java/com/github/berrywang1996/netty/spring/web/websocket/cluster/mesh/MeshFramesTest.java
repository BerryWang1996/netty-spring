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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the mesh length-prefix framing: a wrapped codec line round-trips through prepender→decoder, partial reads
 * reassemble into one frame, and an oversized declared length is rejected (the RC4a M2 inbound frame cap).
 */
class MeshFramesTest {

    private static final String WIRE = "H1:abc123:2|node-A|/ws/chat|BROADCAST|||7|cGF5bG9hZA";

    /** Drains all outbound buffers from the prepender channel and concatenates them (the prepender emits the length
     *  header and the payload as separate buffers). */
    private static ByteBuf frame(String wire) {
        EmbeddedChannel out = new EmbeddedChannel(MeshFrames.prepender());
        out.writeOutbound(MeshFrames.toPayload(wire));
        ByteBuf combined = Unpooled.buffer();
        ByteBuf b;
        while ((b = out.readOutbound()) != null) {
            combined.writeBytes(b);
            b.release();
        }
        out.finishAndReleaseAll();
        return combined;
    }

    @Test
    void encodeDecode_roundTrips() {
        ByteBuf framed = frame(WIRE);
        assertEquals(4 + WIRE.getBytes(StandardCharsets.UTF_8).length, framed.readableBytes());

        EmbeddedChannel in = new EmbeddedChannel(MeshFrames.decoder(1_048_576));
        in.writeInbound(framed);
        ByteBuf decoded = in.readInbound();
        assertNotNull(decoded);
        assertEquals(WIRE, MeshFrames.fromPayload(decoded));
        decoded.release();
        in.finishAndReleaseAll();
    }

    @Test
    void partialRead_reassembles() {
        ByteBuf framed = frame(WIRE);
        // Split mid-header (offset 3) — the hardest reassembly case.
        ByteBuf first = framed.readRetainedSlice(3);
        ByteBuf second = framed.readRetainedSlice(framed.readableBytes());
        framed.release();

        EmbeddedChannel in = new EmbeddedChannel(MeshFrames.decoder(1_048_576));
        in.writeInbound(first);
        assertNull(in.readInbound(), "no full frame after the first partial chunk");
        in.writeInbound(second);
        ByteBuf decoded = in.readInbound();
        assertNotNull(decoded, "full frame after the second chunk");
        assertEquals(WIRE, MeshFrames.fromPayload(decoded));
        decoded.release();
        in.finishAndReleaseAll();
    }

    @Test
    void oversizedFrame_rejected() {
        ByteBuf framed = frame(WIRE);
        EmbeddedChannel in = new EmbeddedChannel(MeshFrames.decoder(8)); // cap below the frame size
        assertThrows(Exception.class, () -> in.writeInbound(framed));
        in.finishAndReleaseAll();
    }
}
