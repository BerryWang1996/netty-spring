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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.nats;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.*;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NATS core Pub/Sub implementation of {@link ClusterBroker} — ADR-001's "scaling tier", the NATS twin of
 * {@code RedisPubSubBroker}. Broadcast → subject {@code netty.broadcast.<b64url(uri)>}, unicast →
 * {@code netty.unicast.<b64url(nodeId)>}. Same {@link EnvelopeCodec} + {@link MessageAuthenticator} +
 * inbound-size-cap + {@link TransportStateListener} contract; core pub/sub is at-most-once (JetStream
 * reliable is a separate future impl). Transport only — the SessionRegistry/heartbeat stay on Redis.
 *
 * <p>The NATS {@link Connection} is injected via {@link #attach(Connection)} after construction so the
 * connection's build-time {@link ConnectionListener} can target {@link #onConnectionEvent}.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class NatsClusterBroker implements ClusterBroker {

    private static final String BROADCAST_PREFIX = "netty.broadcast.";
    private static final String UNICAST_PREFIX = "netty.unicast.";

    private final EnvelopeCodec codec;
    private final MessageAuthenticator authenticator;
    private final AtomicReference<BrokerState> state = new AtomicReference<>(BrokerState.ACTIVE);

    /** Subject → listener (resolved by the single shared dispatcher's handler). */
    private final ConcurrentHashMap<String, ClusterMessageListener> channelListeners = new ConcurrentHashMap<>();

    private volatile Connection connection;
    private volatile Dispatcher dispatcher;
    private volatile int inboundMaxBytes = 0;
    private volatile TransportStateListener transportStateListener;

    /** Backward-compat constructor — no authentication (NoOp). */
    public NatsClusterBroker(EnvelopeCodec codec) {
        this(codec, new NoOpMessageAuthenticator());
    }

    public NatsClusterBroker(EnvelopeCodec codec, MessageAuthenticator authenticator) {
        this.codec = codec;
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    /**
     * Injects the NATS connection (built by the auto-config with {@link #onConnectionEvent} as its
     * ConnectionListener) and creates the single shared subscribe dispatcher. Called once after construction.
     */
    public void attach(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.dispatcher = connection.createDispatcher(msg -> onInboundMessage(msg.getSubject(), msg.getData()));
        log.info("NatsClusterBroker initialized (codec={})", codec.getClass().getSimpleName());
    }

    /** NATS connection-state callback (registered as the connection's ConnectionListener at build time). */
    public void onConnectionEvent(Connection conn, ConnectionListener.Events type) {
        switch (type) {
            case DISCONNECTED:
            case CLOSED:
                if (state.compareAndSet(BrokerState.ACTIVE, BrokerState.DEGRADED)) {
                    log.warn("NATS transport {} — broker DEGRADED (cross-node paused, local fan-out continues)", type);
                    TransportStateListener l = transportStateListener;
                    if (l != null) {
                        try { l.onTransportLost(); } catch (Exception e) { log.debug("onTransportLost failed", e); }
                    }
                }
                break;
            case CONNECTED:
            case RECONNECTED:
                if (state.compareAndSet(BrokerState.DEGRADED, BrokerState.ACTIVE)) {
                    log.info("NATS transport {} — broker ACTIVE", type);
                    TransportStateListener l = transportStateListener;
                    if (l != null) {
                        try { l.onTransportRestored(); } catch (Exception e) { log.debug("onTransportRestored failed", e); }
                    }
                }
                break;
            default:
                log.debug("NATS connection event {}", type);
        }
    }

    /** Encodes a uri/nodeId into a single NATS-subject-safe token (base64url, no padding). */
    private static String subjectToken(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Sets the max accepted size of an inbound message (bytes) before decode. 0 = unlimited. */
    public void setInboundMaxBytes(int inboundMaxBytes) {
        this.inboundMaxBytes = Math.max(0, inboundMaxBytes);
    }

    @Override
    public void publish(String uri, ClusterEnvelope envelope) {
        checkActive();
        String subject = BROADCAST_PREFIX + subjectToken(uri);
        byte[] data = authenticator.wrap(codec.encode(envelope)).getBytes(StandardCharsets.UTF_8);
        connection.publish(subject, data);
    }

    @Override
    public void unicast(String targetNodeId, ClusterEnvelope envelope) {
        checkActive();
        String subject = UNICAST_PREFIX + subjectToken(targetNodeId);
        byte[] data = authenticator.wrap(codec.encode(envelope)).getBytes(StandardCharsets.UTF_8);
        connection.publish(subject, data);
    }

    /** Handles an inbound NATS message: inbound-size guard → listener lookup → HMAC unwrap → decode → dispatch.
     *  Runs on a NATS dispatcher thread; the downstream listener is concurrency-safe. */
    private void onInboundMessage(String subject, byte[] bytes) {
        int max = inboundMaxBytes;
        if (max > 0 && bytes != null && bytes.length > max) {
            log.warn("Dropping oversized inbound cluster message on subject {} ({} > {} bytes) "
                    + "— possible misbehaving/hostile publisher", subject, bytes.length, max);
            return;
        }
        ClusterMessageListener listener = channelListeners.get(subject);
        if (listener != null) {
            String inner = authenticator.unwrap(new String(bytes, StandardCharsets.UTF_8));
            if (inner == null) {
                log.warn("Rejected inbound cluster message on subject {} — missing/invalid HMAC tag", subject);
                return;
            }
            try {
                ClusterEnvelope envelope = codec.decode(inner);
                if (envelope != null) {
                    listener.onMessage(envelope);
                }
            } catch (Exception e) {
                log.warn("Failed to decode cluster envelope on subject {}", subject, e);
            }
        }
    }

    @Override
    public void setTransportStateListener(TransportStateListener listener) {
        this.transportStateListener = listener;
    }

    @Override
    public ClusterSubscription subscribe(String uri, ClusterMessageListener listener) {
        String subject = BROADCAST_PREFIX + subjectToken(uri);
        channelListeners.put(subject, listener);
        dispatcher.subscribe(subject);
        log.debug("Subscribed to broadcast subject {}", subject);
        return createSubscription(subject);
    }

    @Override
    public ClusterSubscription subscribeUnicast(String nodeId, ClusterMessageListener listener) {
        String subject = UNICAST_PREFIX + subjectToken(nodeId);
        channelListeners.put(subject, listener);
        dispatcher.subscribe(subject);
        log.debug("Subscribed to unicast subject {}", subject);
        return createSubscription(subject);
    }

    @Override
    public BrokerState state() {
        return state.get();
    }

    @Override
    public void shutdown() {
        state.set(BrokerState.SHUTDOWN);
        Connection c = connection;
        if (c != null) {
            try {
                c.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Error closing NATS connection", e);
            }
        }
        channelListeners.clear();
        log.info("NatsClusterBroker shut down");
    }

    private ClusterSubscription createSubscription(String subject) {
        AtomicBoolean active = new AtomicBoolean(true);
        return new ClusterSubscription() {
            @Override
            public void unsubscribe() {
                if (active.compareAndSet(true, false)) {
                    channelListeners.remove(subject);
                    try { dispatcher.unsubscribe(subject); }
                    catch (Exception e) { log.debug("Unsubscribe from {} failed", subject); }
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
