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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.node;

/**
 * Lifecycle states of a cluster node.
 *
 * <pre>
 *   JOINING ──→ ACTIVE ──→ DRAINING ──→ LEFT
 *                  │                       ↑
 *                  ↓                       │
 *              DEGRADED ──→ RESYNC ────────┘
 * </pre>
 *
 * @author berrywang1996
 * @since V1.8.0
 */
public enum NodeState {

    /** Node is registering with the cluster but not yet accepting cross-node messages. */
    JOINING,

    /** Node is fully operational: heartbeating, publishing, subscribing, routing. */
    ACTIVE,

    /**
     * Node has lost its connection to the cluster transport (e.g. Redis disconnected)
     * and is operating in local-only mode. Cross-node broadcast and unicast are paused.
     * Local sessions and local fan-out continue working.
     */
    DEGRADED,

    /**
     * Node is reconnecting to the transport and rebuilding cluster state (re-subscribing
     * channels, re-syncing the session registry). Not yet accepting inbound cross-node
     * messages.
     */
    RESYNC,

    /**
     * Node is shutting down gracefully: no longer accepting new connections, sending
     * close frames to all sessions, waiting for drain timeout.
     */
    DRAINING,

    /** Node has fully deregistered from the cluster. Terminal state. */
    LEFT
}
