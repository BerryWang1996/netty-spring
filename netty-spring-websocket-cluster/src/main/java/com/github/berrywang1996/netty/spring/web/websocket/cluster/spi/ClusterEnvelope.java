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
 * <p>Serialization to/from the transport wire format (e.g. JSON for Redis Pub/Sub) is
 * handled by the {@link ClusterBroker} implementation, not by this class.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
public final class ClusterEnvelope {

    /** Wire format version. Incremented when the envelope structure changes.
     *  Receivers should discard envelopes with version > their max supported. */
    public static final int CURRENT_VERSION = 1;

    private final int version;
    private final String originNodeId;
    private final String uri;
    private final MessageKind kind;
    private final byte[] payload;
    private final String targetSessionId;
    private final String traceparent;
    private final long timestamp;

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
        this(CURRENT_VERSION, originNodeId, uri, kind, payload, targetSessionId, traceparent, timestamp);
    }

    /**
     * Constructs a new envelope with an explicit wire version (used by deserializers).
     */
    public ClusterEnvelope(int version, String originNodeId, String uri, MessageKind kind,
                           byte[] payload, String targetSessionId, String traceparent,
                           long timestamp) {
        this.version = version;
        this.originNodeId = originNodeId;
        this.uri = uri;
        this.kind = kind;
        this.payload = payload;
        this.targetSessionId = targetSessionId;
        this.traceparent = traceparent;
        this.timestamp = timestamp;
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
     * The kind of cross-node message.
     */
    public enum MessageKind {
        /** Fan-out to all subscribers of a URI (at-most-once via Pub/Sub). */
        BROADCAST,
        /** Targeted delivery to a single session on a specific node. */
        UNICAST,
        /** Control command: close a remote session (payload = "statusCode|reasonText"). */
        CLOSE
    }
}
