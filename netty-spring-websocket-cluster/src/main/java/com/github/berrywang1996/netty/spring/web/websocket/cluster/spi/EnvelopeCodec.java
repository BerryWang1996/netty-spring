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

/**
 * SPI for serializing/deserializing {@link ClusterEnvelope} to and from the wire format
 * used by the {@link ClusterBroker} transport (e.g. Redis Pub/Sub message body).
 *
 * <p>The default implementation ({@link com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec})
 * uses a compact pipe-delimited text format with Base64-encoded payload — zero external
 * dependencies. Users who need JSON, Protobuf, or other formats can provide their own
 * implementation via Spring {@code @Bean} (the auto-configuration uses
 * {@code @ConditionalOnMissingBean}).
 *
 * <p>Implementations must be thread-safe.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
public interface EnvelopeCodec {

    /**
     * Serializes an envelope to a string suitable for the transport wire.
     *
     * @param envelope the envelope to serialize (never null)
     * @return the serialized string representation
     * @throws ClusterBrokerException if serialization fails
     */
    String encode(ClusterEnvelope envelope);

    /**
     * Deserializes a string from the transport wire back to an envelope.
     *
     * @param data the serialized string
     * @return the deserialized envelope, or {@code null} if the data represents an
     *         unsupported version (should log a warning, not throw)
     * @throws ClusterBrokerException if deserialization fails due to malformed data
     */
    ClusterEnvelope decode(String data);
}
