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
 * A drained offline message: the store-assigned {@code id} (the Redis stream entry id for the default impl)
 * plus the original {@link ClusterEnvelope} (1.10.0-RC2). The {@code id} is delivered to the reconnecting
 * session as {@code X-Offline-Message-Id} metadata so application handlers can dedup on the infra id, and is
 * passed back to {@link OfflineQueueStore#delete} to ack the message after delivery.
 *
 * <p>{@code StoredMessage} is a Redis-only wrapper around the existing envelope — there is no envelope wire
 * change in RC2.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
public final class StoredMessage {

    private final String id;
    private final ClusterEnvelope envelope;

    public StoredMessage(String id, ClusterEnvelope envelope) {
        this.id = id;
        this.envelope = envelope;
    }

    /** The store-assigned message id (Redis stream entry id), exposed as {@code X-Offline-Message-Id}. */
    public String getId() {
        return id;
    }

    /** The original cross-node envelope to redeliver to the reconnecting session. */
    public ClusterEnvelope getEnvelope() {
        return envelope;
    }

    @Override
    public String toString() {
        return "StoredMessage{id=" + id + ", uri=" + (envelope == null ? null : envelope.getUri()) + '}';
    }
}
