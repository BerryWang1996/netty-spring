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

import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Node-address directory for the mesh transport (1.10.0-RC4a): resolves {@code nodeId → reachable host:port} so a node
 * can open a direct TCP connection to a peer. The cluster heartbeat tracks only liveness ({@code nodeId + timestamp}),
 * not addresses — this fills that gap. Default impl is Redis-backed; pluggable (k8s/Consul) later.
 *
 * <p><b>Not the liveness source of truth.</b> {@link #peers} returns advertised addresses; callers intersect with the
 * heartbeat's liveness so the mesh has a single membership truth (live-by-heartbeat ∩ has-address) and the directory
 * can't diverge into a second "is alive" view.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
public interface MeshNodeDirectory {

    /** Advertises this node's reachable address with a TTL (written on start + refreshed periodically). */
    CompletionStage<Void> advertise(String nodeId, String host, int port, long ttlMs);

    /** Live {@code nodeId → "host:port"} for all currently-advertised nodes EXCEPT {@code selfNodeId}. */
    CompletionStage<Map<String, String>> peers(String selfNodeId);

    /** Removes this node's advertisement (graceful shutdown). */
    CompletionStage<Void> remove(String nodeId);

    /** Releases resources (the refresh executor). */
    void shutdown();
}
