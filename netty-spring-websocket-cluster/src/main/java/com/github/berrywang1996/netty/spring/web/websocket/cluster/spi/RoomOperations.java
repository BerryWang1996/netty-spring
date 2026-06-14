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
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;

/**
 * Room-scoped message operations layered on top of the base
 * {@link com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender} (1.10.0).
 *
 * <p>Kept as a separate sub-interface so the focused base {@code MessageSender} is untouched: room
 * routing is a cluster-only, opt-in capability ({@code server.netty.websocket.cluster.room.enable=true}).
 * {@code ClusterMessageSender} implements this; a non-cluster sender does not. When rooms are disabled the
 * methods throw {@link IllegalStateException} (parallel to {@code reliableBroadcast} when reliable delivery
 * is off), so the failure is explicit rather than silently dropped.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
public interface RoomOperations {

    /**
     * Adds a session to a room (membership + the per-room node-set). The session is added to the room on
     * THIS node; the distributed node-set gains this node when it becomes the room's first local member.
     *
     * @param uri       the WebSocket mapping URI
     * @param room      the room within the URI
     * @param sessionId the session id (must be a local session)
     * @throws IllegalStateException if room routing is disabled
     */
    void joinRoom(String uri, String room, String sessionId);

    /**
     * Removes a session from a room. When it was the room's last local member, this node leaves the room's
     * node-set.
     *
     * @param uri       the WebSocket mapping URI
     * @param room      the room within the URI
     * @param sessionId the session id
     * @throws IllegalStateException if room routing is disabled
     */
    void leaveRoom(String uri, String room, String sessionId);

    /**
     * Sends a message to every member of a room across the cluster, via per-room node-targeted delivery:
     * local members get it directly, and only the nodes hosting members of the room (the node-set) receive
     * a targeted copy over the existing per-node unicast channel — so fan-out drops to N/k (k = nodes with
     * members), a real reduction for bounded rooms even under random load-balanced placement.
     *
     * <p><b>Crossover (honest):</b> a "hot" room whose members span k≈N nodes costs k targeted publishes vs
     * the 1 global publish of {@code topicMessage(uri, msg)} — for rooms expected to span most nodes, prefer
     * the global broadcast. RC1 documents + meters the crossover; it does not auto-switch.
     *
     * <p>Local fan-out happens first (always, even when the cluster transport is degraded), mirroring
     * {@code topicMessage}'s contract.
     *
     * @param uri     the WebSocket mapping URI
     * @param room    the room within the URI
     * @param message the message to deliver
     * @throws MessageUriNotDefinedException if the URI is not registered
     * @throws IllegalStateException         if room routing is disabled
     */
    void roomMessage(String uri, String room, AbstractMessage message) throws MessageUriNotDefinedException;
}
