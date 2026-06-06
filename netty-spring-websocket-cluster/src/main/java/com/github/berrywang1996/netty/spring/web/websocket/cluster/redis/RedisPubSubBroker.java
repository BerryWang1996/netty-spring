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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.redis;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.*;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Redis Pub/Sub implementation of {@link ClusterBroker}.
 *
 * <p>Uses Lettuce for non-blocking Redis I/O. Broadcast messages are published to
 * {@code netty:broadcast:{uri}} channels; unicast messages to
 * {@code netty:unicast:{targetNodeId}} channels.
 *
 * <p>Envelope serialization is delegated to {@link EnvelopeCodec} (SPI). The default
 * codec is {@link com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec}
 * which has zero external dependencies (no Jackson). Users can provide their own codec
 * (e.g. JSON, Protobuf) via Spring {@code @Bean}.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
@Slf4j
public class RedisPubSubBroker implements ClusterBroker {

    private static final String BROADCAST_PREFIX = "netty:broadcast:";
    private static final String UNICAST_PREFIX = "netty:unicast:";

    private final EnvelopeCodec codec;
    private final MessageAuthenticator authenticator;
    private final AtomicReference<BrokerState> state = new AtomicReference<>(BrokerState.ACTIVE);

    /** Connection for PUBLISH commands (shared, thread-safe in Lettuce). */
    private final StatefulRedisConnection<String, String> publishConnection;

    /** Pub/Sub connections for SUBSCRIBE callbacks. Channels are partitioned across these by a stable
     *  hash so inbound decode runs on up to N Lettuce I/O threads. Size N = pubsub-connections (>= 1).
     *  The {@link #channelListeners} map is shared across all of them. */
    private final java.util.List<StatefulRedisPubSubConnection<String, String>> subscribeConnections;

    /** Active listeners keyed by channel name. */
    private final ConcurrentHashMap<String, ClusterMessageListener> channelListeners = new ConcurrentHashMap<>();

    /** Max accepted UTF-8 byte length of an INBOUND pub/sub message before decode. 0 = unlimited.
     *  Protects against a malicious/compromised peer publishing a huge payload (remote OOM). */
    private volatile int inboundMaxBytes = 0;

    /** Notified the instant the Redis connection drops/recovers (event-driven degrade/recover). */
    private volatile TransportStateListener transportStateListener;

    /** Backward-compat constructor — no authentication (NoOp), single pub/sub connection. */
    public RedisPubSubBroker(RedisClient redisClient, EnvelopeCodec codec) {
        this(redisClient, codec, new NoOpMessageAuthenticator());
    }

    /** Single pub/sub connection (delegates with {@code pubsubConnections = 1}). */
    public RedisPubSubBroker(RedisClient redisClient, EnvelopeCodec codec, MessageAuthenticator authenticator) {
        this(redisClient, codec, authenticator, 1);
    }

    /**
     * @param pubsubConnections number of Redis pub/sub SUBSCRIBE connections to spread inbound decode
     *                          across (clamped to {@code [1, 16]}); {@code 1} = single connection.
     */
    public RedisPubSubBroker(RedisClient redisClient, EnvelopeCodec codec, MessageAuthenticator authenticator,
                             int pubsubConnections) {
        this.codec = codec;
        this.authenticator = java.util.Objects.requireNonNull(authenticator, "authenticator");

        int n = Math.max(1, Math.min(16, pubsubConnections));
        if (n != pubsubConnections) {
            log.warn("pubsub-connections={} out of range [1,16] — clamped to {}", pubsubConnections, n);
        }

        // Event-driven transport health: flip broker state the instant Redis drops/recovers and
        // notify the listener (the cluster degrades/recovers immediately, not up to a heartbeat
        // interval late). The listener fires per-connection; CAS keeps the state transition
        // idempotent across the publish + pub/sub connections.
        redisClient.addListener(new RedisConnectionStateListener() {
            @Override
            public void onRedisDisconnected(RedisChannelHandler<?, ?> connection) {
                if (state.compareAndSet(BrokerState.ACTIVE, BrokerState.DEGRADED)) {
                    log.warn("Redis transport disconnected — broker DEGRADED (cross-node paused, local fan-out continues)");
                    TransportStateListener l = transportStateListener;
                    if (l != null) {
                        try { l.onTransportLost(); } catch (Exception e) { log.debug("transportStateListener.onTransportLost failed", e); }
                    }
                }
            }

            @Override
            public void onRedisConnected(RedisChannelHandler<?, ?> connection, SocketAddress remoteAddress) {
                if (state.compareAndSet(BrokerState.DEGRADED, BrokerState.ACTIVE)) {
                    log.info("Redis transport reconnected — broker ACTIVE");
                    TransportStateListener l = transportStateListener;
                    if (l != null) {
                        try { l.onTransportRestored(); } catch (Exception e) { log.debug("transportStateListener.onTransportRestored failed", e); }
                    }
                }
            }

            @Override
            public void onRedisExceptionCaught(RedisChannelHandler<?, ?> connection, Throwable cause) {
                // Connection-level exceptions are surfaced via onRedisDisconnected; just trace here.
                log.debug("Redis connection exception", cause);
            }
        });

        this.publishConnection = redisClient.connect();

        // N pub/sub connections; each decodes only the channels that hash to it (Redis routes per
        // connection), parallelising inbound decode across up to N Lettuce I/O threads. The shared
        // channelListeners map lets any connection's adapter resolve the listener for its channel.
        java.util.List<StatefulRedisPubSubConnection<String, String>> conns = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StatefulRedisPubSubConnection<String, String> sub = redisClient.connectPubSub();
            sub.addListener(new RedisPubSubAdapter<String, String>() {
                @Override
                public void message(String channel, String message) {
                    onInboundMessage(channel, message);
                }
            });
            conns.add(sub);
        }
        this.subscribeConnections = conns;

        log.info("RedisPubSubBroker initialized (codec={}, pubsubConnections={})",
                codec.getClass().getSimpleName(), n);
    }

    /**
     * Handles an inbound pub/sub message from any of the N subscribe connections: inbound-size guard,
     * listener lookup, HMAC unwrap, decode, dispatch. May run concurrently on up to N I/O threads for
     * DIFFERENT channels (a channel is pinned to one connection, so same-channel messages stay ordered);
     * the downstream listener is concurrency-safe.
     */
    private void onInboundMessage(String channel, String message) {
        int max = inboundMaxBytes;
        if (max > 0 && message != null) {
            int sz = message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (sz > max) {
                log.warn("Dropping oversized inbound cluster message on channel {} ({} > {} bytes) "
                        + "— possible misbehaving/hostile publisher", channel, sz, max);
                return;
            }
        }
        ClusterMessageListener listener = channelListeners.get(channel);
        if (listener != null) {
            String inner = authenticator.unwrap(message);
            if (inner == null) {
                log.warn("Rejected inbound cluster message on channel {} — missing/invalid HMAC tag", channel);
                return;
            }
            try {
                ClusterEnvelope envelope = codec.decode(inner);
                if (envelope != null) { // null = unsupported version, already logged
                    listener.onMessage(envelope);
                }
            } catch (Exception e) {
                log.warn("Failed to decode cluster envelope on channel {}", channel, e);
            }
        }
    }

    /** Maps a channel to its (stable) subscribe connection, so SUBSCRIBE, inbound and UNSUBSCRIBE for a
     *  channel always use the same connection. */
    private StatefulRedisPubSubConnection<String, String> connectionFor(String channel) {
        return subscribeConnections.get(Math.floorMod(channel.hashCode(), subscribeConnections.size()));
    }

    /** Sets the max accepted UTF-8 byte length of an inbound pub/sub message before decode. 0 = unlimited. */
    public void setInboundMaxBytes(int inboundMaxBytes) {
        this.inboundMaxBytes = Math.max(0, inboundMaxBytes);
    }

    @Override
    public void publish(String uri, ClusterEnvelope envelope) {
        checkActive();
        String channel = BROADCAST_PREFIX + uri;
        String data = authenticator.wrap(codec.encode(envelope));
        publishConnection.async().publish(channel, data)
                .exceptionally(ex -> logAsyncPublishFailure(channel, ex));
    }

    @Override
    public void unicast(String targetNodeId, ClusterEnvelope envelope) {
        checkActive();
        String channel = UNICAST_PREFIX + targetNodeId;
        String data = authenticator.wrap(codec.encode(envelope));
        publishConnection.async().publish(channel, data)
                .exceptionally(ex -> logAsyncPublishFailure(channel, ex));
    }

    /** Surfaces async publish failures instead of silently dropping them. */
    private Long logAsyncPublishFailure(String channel, Throwable ex) {
        log.warn("Async cluster publish to channel {} failed — message not delivered to remote nodes",
                channel, ex);
        return null;
    }

    @Override
    public void setTransportStateListener(TransportStateListener listener) {
        this.transportStateListener = listener;
    }

    @Override
    public ClusterSubscription subscribe(String uri, ClusterMessageListener listener) {
        String channel = BROADCAST_PREFIX + uri;
        channelListeners.put(channel, listener);
        connectionFor(channel).async().subscribe(channel);
        log.debug("Subscribed to broadcast channel {}", channel);
        return createSubscription(channel);
    }

    @Override
    public ClusterSubscription subscribeUnicast(String nodeId, ClusterMessageListener listener) {
        String channel = UNICAST_PREFIX + nodeId;
        channelListeners.put(channel, listener);
        connectionFor(channel).async().subscribe(channel);
        log.debug("Subscribed to unicast channel {}", channel);
        return createSubscription(channel);
    }

    @Override
    public BrokerState state() {
        return state.get();
    }

    @Override
    public void shutdown() {
        state.set(BrokerState.SHUTDOWN);
        for (StatefulRedisPubSubConnection<String, String> sub : subscribeConnections) {
            try { sub.close(); } catch (Exception e) { log.warn("Error closing pub/sub conn", e); }
        }
        try { publishConnection.close(); } catch (Exception e) { log.warn("Error closing publish conn", e); }
        channelListeners.clear();
        log.info("RedisPubSubBroker shut down");
    }

    // ---- Internal ----

    private ClusterSubscription createSubscription(String channel) {
        AtomicBoolean active = new AtomicBoolean(true);
        return new ClusterSubscription() {
            @Override
            public void unsubscribe() {
                if (active.compareAndSet(true, false)) {
                    channelListeners.remove(channel);
                    try { connectionFor(channel).async().unsubscribe(channel); }
                    catch (Exception e) { log.debug("Unsubscribe from {} failed", channel); }
                }
            }

            @Override
            public boolean isActive() {
                return active.get();
            }
        };
    }

    private void checkActive() {
        BrokerState s = state.get();
        if (s != BrokerState.ACTIVE) {
            throw new ClusterBrokerException("Broker is not active: " + s);
        }
    }
}
