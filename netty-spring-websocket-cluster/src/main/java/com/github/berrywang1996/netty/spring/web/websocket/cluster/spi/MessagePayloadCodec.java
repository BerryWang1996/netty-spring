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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.spi;

import com.github.berrywang1996.netty.spring.web.websocket.context.AbstractMessage;

/**
 * SPI for serializing/deserializing {@link AbstractMessage} (the business message body)
 * to and from the byte array carried inside a {@link ClusterEnvelope#getPayload()}.
 *
 * <p>This is separate from {@link EnvelopeCodec} by design:
 * <ul>
 *   <li>{@link EnvelopeCodec} handles the <b>envelope wire format</b> (routing metadata
 *       + base64 payload) — transport concern</li>
 *   <li>{@code MessagePayloadCodec} handles the <b>business message body</b>
 *       (TextMessage/JsonMessage/BinaryMessage ↔ byte[]) — application concern</li>
 * </ul>
 *
 * <p>The default implementation
 * ({@link com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.DefaultMessagePayloadCodec})
 * uses a compact prefix-based format ({@code T:}, {@code J:}, {@code B:}) with zero
 * external dependencies. Users who want Protobuf, MessagePack, or a custom binary format
 * for their business objects can provide their own implementation via Spring {@code @Bean}.
 *
 * <p>Implementations must be thread-safe.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
public interface MessagePayloadCodec {

    /**
     * Serializes a business message to bytes for inclusion in a cluster envelope.
     *
     * @param message the message to serialize (never null)
     * @return the serialized bytes
     */
    byte[] encode(AbstractMessage message);

    /**
     * Deserializes bytes from a cluster envelope back to a business message.
     *
     * @param payload the serialized bytes (from {@link ClusterEnvelope#getPayload()})
     * @return the deserialized message
     */
    AbstractMessage decode(byte[] payload);
}
