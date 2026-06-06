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
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.cluster.pubsub.RedisClusterPubSubAdapter;
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Redis-Cluster (topology) twin of {@link RedisPubSubBroker} using <b>regular</b> cluster Pub/Sub.
 *
 * <p>Behaviourally identical to the standalone {@link RedisPubSubBroker} — same channel naming
 * ({@code netty:broadcast:{uri}} / {@code netty:unicast:{targetNodeId}}), same {@link EnvelopeCodec}
 * + {@link MessageAuthenticator} wrap/unwrap, same inbound-size cap, same {@link TransportStateListener}
 * wiring — but it talks to a {@link RedisClusterClient} instead of a standalone {@code RedisClient}.
 *
 * <p><b>Cluster Pub/Sub specifics (vs. standalone):</b>
 * <ul>
 *   <li>Connections are the cluster variants: {@link StatefulRedisClusterConnection} (publish) and
 *       {@link StatefulRedisClusterPubSubConnection} (subscribe).</li>
 *   <li>The pub/sub listener is a {@link RedisClusterPubSubAdapter}, whose firing message callback is
 *       the node-aware {@code message(RedisClusterNode, channel, message)} overload (the plain
 *       {@code message(channel, message)} of the standalone adapter does NOT exist on the cluster
 *       adapter in Lettuce 6.1).</li>
 *   <li>{@code setNodeMessagePropagation(true)} is enabled on the subscribe connection so messages
 *       received on any upstream node connection are surfaced to the single registered listener
 *       (regular cluster {@code PUBLISH} propagates cluster-wide; this makes the client deliver them).</li>
 * </ul>
 *
 * <p>This uses <em>regular</em> cluster Pub/Sub (SUBSCRIBE/PUBLISH), not sharded Pub/Sub
 * (SSUBSCRIBE/SPUBLISH), which requires Lettuce 6.2+/Redis 7 and is reserved for a later release.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class RedisClusterModePubSubBroker implements ClusterBroker {

    private static final String BROADCAST_PREFIX = "netty:broadcast:";
    private static final String UNICAST_PREFIX = "netty:unicast:";

    private final EnvelopeCodec codec;
    private final MessageAuthenticator authenticator;
    private final AtomicReference<BrokerState> state = new AtomicReference<>(BrokerState.ACTIVE);

    /** Connection for PUBLISH commands (shared, thread-safe in Lettuce). */
    private final StatefulRedisClusterConnection<String, String> publishConnection;

    /** Cluster Pub/Sub connection for SUBSCRIBE callbacks. */
    private final StatefulRedisClusterPubSubConnection<String, String> subscribeConnection;

    /** Active listeners keyed by channel name. */
    private final ConcurrentHashMap<String, ClusterMessageListener> channelListeners = new ConcurrentHashMap<>();

    /** Max accepted UTF-8 byte length of an INBOUND pub/sub message before decode. 0 = unlimited.
     *  Protects against a malicious/compromised peer publishing a huge payload (remote OOM). */
    private volatile int inboundMaxBytes = 0;

    /** Notified the instant the Redis connection drops/recovers (event-driven degrade/recover). */
    private volatile TransportStateListener transportStateListener;

    /** Backward-compat constructor — no authentication (NoOp). */
    public RedisClusterModePubSubBroker(RedisClusterClient redisClusterClient, EnvelopeCodec codec) {
        this(redisClusterClient, codec, new NoOpMessageAuthenticator());
    }

    public RedisClusterModePubSubBroker(RedisClusterClient redisClusterClient, EnvelopeCodec codec,
                                        MessageAuthenticator authenticator) {
        this.codec = codec;
        this.authenticator = java.util.Objects.requireNonNull(authenticator, "authenticator");

        // Event-driven transport health: flip broker state the instant Redis drops/recovers and
        // notify the listener (the cluster degrades/recovers immediately, not up to a heartbeat
        // interval late). The listener fires per-connection; CAS keeps the state transition
        // idempotent across the publish + pub/sub connections.
        redisClusterClient.addListener(new RedisConnectionStateListener() {
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

        this.publishConnection = redisClusterClient.connect();
        this.subscribeConnection = redisClusterClient.connectPubSub();

        // Regular cluster PUBLISH propagates cluster-wide, but the cluster pub/sub connection only
        // surfaces messages received on a node connection to the user listener when node-message
        // propagation is enabled. Must be set BEFORE the first subscribe.
        this.subscribeConnection.setNodeMessagePropagation(true);

        // Wire up the Lettuce cluster pub/sub listener. The cluster adapter's firing callback is the
        // node-aware overload message(node, channel, message) — the plain message(channel, message)
        // (standalone) does not exist here, so we override the node-aware one and ignore the node.
        subscribeConnection.addListener(new RedisClusterPubSubAdapter<String, String>() {
            @Override
            public void message(RedisClusterNode node, String channel, String message) {
                onClusterMessage(channel, message);
            }
        });

        log.info("RedisClusterModePubSubBroker initialized (codec={})", codec.getClass().getSimpleName());
    }

    /**
     * Handles an inbound cluster pub/sub message: inbound-size guard, listener lookup, HMAC unwrap,
     * decode, then dispatch. Identical body to the standalone broker's {@code message(channel, msg)}.
     */
    private void onClusterMessage(String channel, String message) {
        // Inbound size guard — reject oversized messages BEFORE allocating via decode/Base64.
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
        subscribeConnection.async().subscribe(channel);
        log.debug("Subscribed to broadcast channel {}", channel);
        return createSubscription(channel);
    }

    @Override
    public ClusterSubscription subscribeUnicast(String nodeId, ClusterMessageListener listener) {
        String channel = UNICAST_PREFIX + nodeId;
        channelListeners.put(channel, listener);
        subscribeConnection.async().subscribe(channel);
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
        try { subscribeConnection.close(); } catch (Exception e) { log.warn("Error closing pub/sub conn", e); }
        try { publishConnection.close(); } catch (Exception e) { log.warn("Error closing publish conn", e); }
        channelListeners.clear();
        log.info("RedisClusterModePubSubBroker shut down");
    }

    // ---- Internal ----

    private ClusterSubscription createSubscription(String channel) {
        AtomicBoolean active = new AtomicBoolean(true);
        return new ClusterSubscription() {
            @Override
            public void unsubscribe() {
                if (active.compareAndSet(true, false)) {
                    channelListeners.remove(channel);
                    try { subscribeConnection.async().unsubscribe(channel); }
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
