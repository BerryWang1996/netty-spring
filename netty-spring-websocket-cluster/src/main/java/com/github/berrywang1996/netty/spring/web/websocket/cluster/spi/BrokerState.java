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
 * The health state of a {@link ClusterBroker}'s connection to the underlying transport.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
public enum BrokerState {

    /** The broker is connected and operating normally. */
    ACTIVE,

    /**
     * The broker has lost its connection to the transport (e.g. Redis disconnected)
     * but has not yet exceeded the grace period. Cross-node messages are buffered or
     * dropped depending on the configured policy; local fan-out continues.
     */
    DEGRADED,

    /**
     * The broker is reconnecting and rebuilding state (re-subscribing channels,
     * re-syncing session registry). Incoming cross-node messages are not yet accepted.
     */
    RESYNC,

    /** The broker has been shut down and will not accept further operations. */
    SHUTDOWN
}
