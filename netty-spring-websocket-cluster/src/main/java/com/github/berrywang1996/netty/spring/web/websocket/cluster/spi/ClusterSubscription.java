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
 * Handle returned by {@link ClusterBroker#subscribe} / {@link ClusterBroker#subscribeUnicast}.
 * Call {@link #unsubscribe()} to stop receiving messages for that channel.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
public interface ClusterSubscription {

    /**
     * Unsubscribes from the channel. Idempotent — calling multiple times is safe.
     * After this call, the associated {@link ClusterMessageListener} will no longer
     * receive messages.
     */
    void unsubscribe();

    /**
     * Returns whether this subscription is still active.
     *
     * @return true if still subscribed, false after {@link #unsubscribe()} or broker shutdown
     */
    boolean isActive();
}
