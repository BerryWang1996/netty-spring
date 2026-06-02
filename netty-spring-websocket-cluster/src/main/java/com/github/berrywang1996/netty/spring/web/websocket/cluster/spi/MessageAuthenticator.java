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
 * Transport-layer authenticator for cross-node envelopes: wraps the codec-encoded string with an
 * integrity/authenticity tag before publish, and verifies+strips it on receive. Sits OUTSIDE the
 * {@link EnvelopeCodec} so it is codec-agnostic and applies uniformly to broadcast/unicast/close and
 * the reliable Streams path.
 *
 * <p>Default impl is a no-op (no signing). {@code HmacMessageAuthenticator} signs/verifies with a
 * shared secret when {@code server.netty.websocket.cluster.auth.enable=true}. Implementations must be
 * thread-safe.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
public interface MessageAuthenticator {

    /**
     * Produces the wire string from a codec-encoded envelope (e.g. prepends an HMAC tag).
     * @param encoded the codec output (never null)
     * @return the wire string to publish (no-op returns {@code encoded} unchanged)
     */
    String wrap(String encoded);

    /**
     * Verifies and strips the tag, returning the inner codec-encoded envelope, or {@code null} to
     * REJECT the message (missing/invalid tag when verification is required).
     * @param wireData the received wire string
     * @return the inner encoded envelope, or null to drop
     */
    String unwrap(String wireData);
}
