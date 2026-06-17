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
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import java.nio.charset.StandardCharsets;

/**
 * TCP framing for the mesh transport (1.10.0-RC4a). A frame is
 * {@code [4-byte big-endian length][UTF-8 bytes of the HMAC-wrapped EnvelopeCodec line]}.
 *
 * <p>TCP is a byte stream with no message boundary, so the mesh adds explicit length-prefix framing around the
 * existing codec output (which is a single delimiter-free line). The inbound decoder caps the frame length
 * ({@code maxFrameBytes}) so a corrupt/malicious length prefix cannot OOM the receiver (RC4a M2).
 *
 * @author berrywang1996
 * @since V1.10.0
 */
public final class MeshFrames {

    private MeshFrames() {
    }

    /**
     * Inbound decoder: a 4-byte length header at offset 0; strip the 4-byte header; reject frames whose declared
     * length exceeds {@code maxFrameBytes} (throws {@code TooLongFrameException} — the caller closes the connection).
     */
    public static LengthFieldBasedFrameDecoder decoder(int maxFrameBytes) {
        return new LengthFieldBasedFrameDecoder(Math.max(1, maxFrameBytes), 0, 4, 0, 4);
    }

    /** Outbound encoder: prepend a 4-byte big-endian length header. */
    public static LengthFieldPrepender prepender() {
        return new LengthFieldPrepender(4);
    }

    /** Wrapped codec line → payload ByteBuf (the prepender adds the length header on write). */
    public static ByteBuf toPayload(String wrapped) {
        return Unpooled.copiedBuffer(wrapped, StandardCharsets.UTF_8);
    }

    /** Decoded frame ByteBuf → wrapped codec line. */
    public static String fromPayload(ByteBuf frame) {
        return frame.toString(StandardCharsets.UTF_8);
    }
}
