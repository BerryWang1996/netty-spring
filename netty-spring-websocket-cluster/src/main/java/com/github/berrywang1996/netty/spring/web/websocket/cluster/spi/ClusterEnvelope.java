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
 * Immutable wire-format envelope that wraps every cross-node message (broadcast or unicast).
 *
 * <p>The envelope carries routing metadata alongside the serialized payload so that
 * subscribers can:
 * <ul>
 *   <li>Suppress self-delivery (compare {@link #originNodeId} with the local node id)</li>
 *   <li>Propagate distributed trace context ({@link #traceparent})</li>
 *   <li>Route unicast messages to the correct local session ({@link #targetSessionId})</li>
 * </ul>
 *
 * <p>Serialization to/from the transport wire format is handled by an {@link EnvelopeCodec}
 * (the default {@code SimpleTextEnvelopeCodec} is a zero-dependency pipe-delimited format),
 * not by this class and not by the {@link ClusterBroker} itself.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
public final class ClusterEnvelope {

    /** Wire format version. Incremented when the envelope structure changes.
     *  Receivers should discard envelopes with version &gt; their max supported.
     *  <p>v1 (1.8.0–1.9.x): 8 wire fields, no {@code room}.
     *  <p>v2 (1.10.0): adds the {@link #room} field + {@link MessageKind#ROOM_BROADCAST}. The default
     *  {@code SimpleTextEnvelopeCodec} appends {@code room} as the second-to-last wire field (payload stays
     *  last) and decodes version-aware, so a v2 codec reads v1 wires (room=null) and a v1 codec discards a
     *  v2 wire on the version gate — see the codec for the rolling-upgrade contract. */
    public static final int CURRENT_VERSION = 2;

    private final int version;
    private final String originNodeId;
    private final String uri;
    private final MessageKind kind;
    private final byte[] payload;
    private final String targetSessionId;
    private final String traceparent;
    private final long timestamp;
    /** Room sub-dimension within {@link #uri} for {@link MessageKind#ROOM_BROADCAST}; null otherwise. */
    private final String room;

    /**
     * Constructs a new envelope with the current wire version.
     *
     * @param originNodeId    the node id of the publisher (used for self-delivery suppression)
     * @param uri             the WebSocket mapping URI this message belongs to
     * @param kind            broadcast, unicast, or close
     * @param payload         the serialized message bytes (opaque to the transport layer)
     * @param targetSessionId for unicast/close: the target session id; null for broadcast
     * @param traceparent     W3C traceparent header value for distributed tracing; may be null
     * @param timestamp       creation timestamp in epoch millis (for latency metrics)
     */
    public ClusterEnvelope(String originNodeId, String uri, MessageKind kind,
                           byte[] payload, String targetSessionId, String traceparent,
                           long timestamp) {
        this(CURRENT_VERSION, originNodeId, uri, kind, payload, targetSessionId, traceparent, timestamp, null);
    }

    /**
     * Constructs a new room-scoped envelope ({@code room} non-null) with the current wire version.
     * Used for {@link MessageKind#ROOM_BROADCAST}.
     *
     * @param room the room sub-dimension within {@code uri}; null for non-room messages
     * @since V1.10.0
     */
    public ClusterEnvelope(String originNodeId, String uri, MessageKind kind,
                           byte[] payload, String targetSessionId, String traceparent,
                           long timestamp, String room) {
        this(CURRENT_VERSION, originNodeId, uri, kind, payload, targetSessionId, traceparent, timestamp, room);
    }

    /**
     * Constructs a new envelope with an explicit wire version (used by deserializers). Room defaults to null.
     */
    public ClusterEnvelope(int version, String originNodeId, String uri, MessageKind kind,
                           byte[] payload, String targetSessionId, String traceparent,
                           long timestamp) {
        this(version, originNodeId, uri, kind, payload, targetSessionId, traceparent, timestamp, null);
    }

    /**
     * Constructs a new envelope with an explicit wire version and room (used by the v2 deserializer).
     *
     * @since V1.10.0
     */
    public ClusterEnvelope(int version, String originNodeId, String uri, MessageKind kind,
                           byte[] payload, String targetSessionId, String traceparent,
                           long timestamp, String room) {
        this.version = version;
        this.originNodeId = originNodeId;
        this.uri = uri;
        this.kind = kind;
        this.payload = payload;
        this.targetSessionId = targetSessionId;
        this.traceparent = traceparent;
        this.timestamp = timestamp;
        this.room = room;
    }

    public int getVersion() {
        return version;
    }

    public String getOriginNodeId() {
        return originNodeId;
    }

    public String getUri() {
        return uri;
    }

    public MessageKind getKind() {
        return kind;
    }

    public byte[] getPayload() {
        return payload;
    }

    public String getTargetSessionId() {
        return targetSessionId;
    }

    public String getTraceparent() {
        return traceparent;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * The room sub-dimension within {@link #getUri()} for {@link MessageKind#ROOM_BROADCAST}.
     *
     * @return the room, or null for non-room messages
     * @since V1.10.0
     */
    public String getRoom() {
        return room;
    }

    /**
     * The kind of cross-node message.
     */
    public enum MessageKind {
        /** Fan-out to all subscribers of a URI (at-most-once via Pub/Sub). */
        BROADCAST,
        /** Targeted delivery to a single session on a specific node. */
        UNICAST,
        /** Control command: close a remote session (payload = "statusCode|reasonText"). */
        CLOSE,
        /** Room-scoped fan-out: delivered only to nodes hosting members of {@link #getRoom()} within
         *  {@link #getUri()}, via the per-node unicast channel; receivers fan out to their local room
         *  members. Carries a non-null {@code room}. At-most-once (Pub/Sub fire-and-forget). Since V1.10.0. */
        ROOM_BROADCAST
    }
}
