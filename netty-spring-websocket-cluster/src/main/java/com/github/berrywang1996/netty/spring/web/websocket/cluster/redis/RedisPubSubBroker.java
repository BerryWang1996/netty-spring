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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.*;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import lombok.extern.slf4j.Slf4j;

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
    private final AtomicReference<BrokerState> state = new AtomicReference<>(BrokerState.ACTIVE);

    /** Connection for PUBLISH commands (shared, thread-safe in Lettuce). */
    private final StatefulRedisConnection<String, String> publishConnection;

    /** Pub/Sub connection for SUBSCRIBE callbacks. */
    private final StatefulRedisPubSubConnection<String, String> subscribeConnection;

    /** Active listeners keyed by channel name. */
    private final ConcurrentHashMap<String, ClusterMessageListener> channelListeners = new ConcurrentHashMap<>();

    /**
     * Creates a new broker with the given Redis client and envelope codec.
     *
     * @param redisClient the Lettuce Redis client
     * @param codec       the envelope serializer/deserializer
     */
    public RedisPubSubBroker(RedisClient redisClient, EnvelopeCodec codec) {
        this.codec = codec;
        this.publishConnection = redisClient.connect();
        this.subscribeConnection = redisClient.connectPubSub();

        // Wire up the Lettuce pub/sub listener
        subscribeConnection.addListener(new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String channel, String message) {
                ClusterMessageListener listener = channelListeners.get(channel);
                if (listener != null) {
                    try {
                        ClusterEnvelope envelope = codec.decode(message);
                        if (envelope != null) { // null = unsupported version, already logged
                            listener.onMessage(envelope);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to decode cluster envelope on channel {}", channel, e);
                    }
                }
            }
        });

        log.info("RedisPubSubBroker initialized (codec={})", codec.getClass().getSimpleName());
    }

    @Override
    public void publish(String uri, ClusterEnvelope envelope) {
        checkActive();
        String channel = BROADCAST_PREFIX + uri;
        String data = codec.encode(envelope);
        publishConnection.async().publish(channel, data);
    }

    @Override
    public void unicast(String targetNodeId, ClusterEnvelope envelope) {
        checkActive();
        String channel = UNICAST_PREFIX + targetNodeId;
        String data = codec.encode(envelope);
        publishConnection.async().publish(channel, data);
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
