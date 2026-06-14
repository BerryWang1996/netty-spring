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
 * SPI for room-scoped membership and the per-room <b>node-set</b> routing primitive (1.10.0).
 *
 * <p>A room is a sub-dimension within a {@code @MessageMapping} URI: one {@code /ws/chat} endpoint hosts
 * unlimited rooms, and a session may belong to many rooms. The routing key is {@code (uri, room)}.
 *
 * <p>This is the third core extension point alongside {@link ClusterBroker} and {@link SessionRegistry},
 * and it mirrors {@code SessionRegistry}'s shape: atomic mutating operations return {@link CompletionStage}
 * (for Redis pipelining / non-blocking I/O), and the receive-side fan-out is served from a per-node local
 * index with zero I/O. The default 1.10.0 implementation is {@code RedisRoomRegistry} (atomic Lua for
 * join/leave/removeAll, a local index for the hot path); it is transport-agnostic so a NATS-KV impl slots in
 * later with no API change. Implementations must be thread-safe.
 *
 * <h3>The routing primitive: per-room node-set</h3>
 * <p>The registry tracks, per room, the <b>set of nodes hosting ≥1 member</b> of that room.
 * {@code roomMessage(uri, room, msg)} looks up that node-set via {@link #nodesForRoom} and targets only
 * those nodes (reusing the existing per-node unicast channel) rather than fanning out to all N nodes — so
 * fan-out drops to N/k (k = nodes with members). This is a real reduction for bounded rooms in large
 * clusters, even under random load-balanced placement; a "hot" room whose members span every node sees no
 * reduction (and costs k≈N targeted publishes vs 1 global publish — the documented crossover).
 *
 * <h3>Caching contract</h3>
 * <p>{@link #nodesForRoom} results may be cached locally by the caller (e.g. {@code ClusterMessageSender})
 * with a short TTL (see {@code server.netty.websocket.cluster.room.node-set-cache-ttl-ms}), invalidated on
 * {@code NODE_LEFT}. A stale node-set at worst targets a node that no longer hosts the room (the receiver
 * fans out to an empty local set — counted as a stale-target waste meter) or misses a node that just
 * gained a member (bounded by the cache TTL).
 *
 * <h3>Local-index ordering invariant</h3>
 * <p>The per-node local index ({@link #localMembers} / {@link #roomsForSession}) is updated <b>after</b> the
 * distributed mutation confirms, so the local index never claims a membership the distributed store rejected.
 *
 * @author berrywang1996
 * @since V1.10.0
 * @see ClusterBroker
 * @see SessionRegistry
 */
public interface ClusterRoomRegistry {

    /**
     * Adds a session (owned by {@code nodeId}) to a room. Atomic. When this is the first member of the room
     * on {@code nodeId}, {@code nodeId} is atomically added to the room's node-set. The local index is
     * updated only after the distributed mutation confirms.
     *
     * @param uri       the WebSocket mapping URI
     * @param room      the room within the URI
     * @param sessionId the joining session id
     * @param nodeId    the node that owns this session
     * @return a stage that completes when the join is persisted
     */
    CompletionStage<Void> join(String uri, String room, String sessionId, String nodeId);

    /**
     * Removes a session from a room. Atomic. When it was the room's last member on {@code nodeId},
     * {@code nodeId} is atomically removed from the room's node-set. The local index is updated only after
     * the distributed mutation confirms.
     *
     * @param uri       the WebSocket mapping URI
     * @param room      the room within the URI
     * @param sessionId the leaving session id
     * @param nodeId    the node that owns this session
     * @return a stage that completes when the leave is persisted
     */
    CompletionStage<Void> leave(String uri, String room, String sessionId, String nodeId);

    /**
     * Returns the set of nodeIds currently hosting ≥1 member of the room — <b>the routing primitive</b>.
     * Cacheable by the caller with a short TTL (see {@code cluster.room.node-set-cache-ttl-ms}).
     *
     * @param uri  the WebSocket mapping URI
     * @param room the room within the URI
     * @return a stage that completes with the room's node-set (may be empty, never null)
     */
    CompletionStage<Set<String>> nodesForRoom(String uri, String room);

    /**
     * Returns the local sessionIds in a room on THIS node — the receive-side fan-out target. Served from the
     * local index with no I/O. Never null (an empty set when this node hosts no members of the room).
     *
     * @param uri  the WebSocket mapping URI
     * @param room the room within the URI
     * @return the local member session ids (possibly empty, never null)
     */
    Set<String> localMembers(String uri, String room);

    /**
     * Returns the rooms a local session is currently in (for disconnect cleanup). Served from the local
     * index with no I/O. Never null.
     *
     * @param uri       the WebSocket mapping URI
     * @param sessionId the local session id
     * @return the rooms the session is in (possibly empty, never null)
     */
    Set<String> roomsForSession(String uri, String sessionId);

    /**
     * Atomically removes a session from ALL its rooms in a single distributed call (not N {@link #leave}
     * calls). Used on local disconnect. When a removal leaves a room with zero members on {@code nodeId},
     * {@code nodeId} is removed from that room's node-set.
     *
     * @param uri       the WebSocket mapping URI
     * @param sessionId the disconnecting session id
     * @param nodeId    the node that owned the session
     * @return a stage that completes when the cleanup is persisted
     */
    CompletionStage<Void> removeAllForSession(String uri, String sessionId, String nodeId);

    /**
     * Removes a dead node from every room's node-set and per-node member set (bulk cleanup on node failure).
     * Driven by the dead-node reconciliation hook, parallel to {@link SessionRegistry#removeAllForNode}.
     *
     * @param nodeId the failed node's identifier
     * @return a stage that completes when cleanup is done
     */
    CompletionStage<Void> removeAllForNode(String nodeId);

    /**
     * Shuts down the registry, releasing any resources it owns (but not a shared transport connection,
     * which is owned by the auto-configuration).
     */
    void shutdown();
}
