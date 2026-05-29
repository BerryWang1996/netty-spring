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
 * Callback interface for receiving cross-node messages from a {@link ClusterBroker}.
 *
 * <p>Implementations must be non-blocking: the callback is invoked on the transport's
 * I/O thread (e.g. Lettuce event loop). Any heavy processing (deserialization, local
 * fan-out, business logic dispatch) should be handed off to a separate executor.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
@FunctionalInterface
public interface ClusterMessageListener {

    /**
     * Called when a cross-node message arrives.
     *
     * @param envelope the received message envelope
     */
    void onMessage(ClusterEnvelope envelope);
}
