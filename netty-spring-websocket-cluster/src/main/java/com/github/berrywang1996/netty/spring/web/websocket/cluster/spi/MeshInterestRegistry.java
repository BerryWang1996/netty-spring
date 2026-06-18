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

import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * SPI for the mesh's per-URI <b>interest node-set</b> routing primitive (1.10.0-RC4b). A node is "interested in
 * {@code uri}" iff it currently hosts &ge;1 live local session for {@code uri}; {@code MeshBroker.publish(uri)} routes
 * only to interested nodes instead of all peers.
 *
 * <p>This mirrors {@link ClusterRoomRegistry} <b>minus the room dimension</b>: the node-set add-on-first /
 * remove-on-last transition is decided atomically (inside a Lua, in the Redis default) over a per-node session set,
 * so concurrent connect/disconnect cannot corrupt the node-set. The default impl is {@code RedisMeshInterestRegistry};
 * pluggable. Implementations must be thread-safe.
 *
 * <h3>Caching contract</h3>
 * <p>{@link #nodesForUri} results may be cached by the caller (a {@code MeshInterestRouter}) with a short TTL.
 * <b>A read failure/timeout must complete the stage EXCEPTIONALLY</b> (not with an empty set) so the router maps it to
 * an all-peers fallback — never a missed broadcast from a transient Redis blip. A successful read of a URI no node is
 * interested in completes with an EMPTY set (authoritative: no remote audience).
 *
 * @author berrywang1996
 * @since V1.10.0
 * @see ClusterRoomRegistry
 * @see ClusterBroker
 */
public interface MeshInterestRegistry {

    /** A local session for {@code uri} registered on this node (call per session-register; the impl decides the
     *  0&rarr;1 node-set add atomically). */
    CompletionStage<Void> subscribe(String uri, String sessionId, String nodeId);

    /** A local session for {@code uri} was removed from this node (call per session-remove; the impl decides the
     *  1&rarr;0 node-set remove atomically). */
    CompletionStage<Void> unsubscribe(String uri, String sessionId, String nodeId);

    /** Removes a (dead) node from every URI's interest node-set — driven by the leader-elected dead-node reap,
     *  parallel to {@link SessionRegistry#removeAllForNode}. */
    CompletionStage<Void> removeAllForNode(String nodeId);

    /** Node-set currently hosting a live session for {@code uri} (the routing primitive). Completes with the
     *  authoritative set (possibly empty, never null on success); completes EXCEPTIONALLY on read failure/timeout. */
    CompletionStage<Set<String>> nodesForUri(String uri);

    /** Releases resources owned by the registry (not a shared transport connection). */
    void shutdown();
}
