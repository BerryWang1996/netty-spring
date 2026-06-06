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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBrokerException;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterMessageListener;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterSubscription;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.EnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ReliableBroker;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.ConsumerInfo;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.SequenceInfo;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NATS JetStream implementation of {@link ReliableBroker} (at-least-once broadcast) — the all-NATS
 * twin of {@code RedisStreamsReliableBroker}. Activated only when {@code reliable.enable=true} AND
 * {@code nats.registry=true}. Mirrors the Redis-Streams contract: per-URI durable stream + one
 * durable pull consumer per node, dedicated fetch-loop thread per subscribed URI, ack-on-success,
 * poison-pill ack-and-continue, in-process dedup window, HMAC-wrap inside the broker, inbound size
 * guard, dead-node consumer cleanup with idle gate, transport-state {@code DEGRADED}/{@code ACTIVE}
 * CAS via {@link ConnectionListener}, replay-on-resync via the durable consumer cursor.
 *
 * <p>Stream name: {@code netty-cluster-reliable-<b64url(uri)>}; subject:
 * {@code netty.reliable.<b64url(uri)>}; durable consumer: {@code g.<b64url(nodeId)>}. Storage is
 * FILE, retention LIMITS, discard OLD, replicas=1 (HA users override the bean for replicas&ge;3).
 *
 * @author berrywang1996
 * @since V1.9.0
 * @see ReliableBroker
 */
@Slf4j
public class NatsJetStreamReliableBroker implements ReliableBroker {

    private static final String STREAM_PREFIX = "netty-cluster-reliable-";
    private static final String SUBJECT_PREFIX = "netty.reliable.";
    /** Durable consumer name prefix. jnats forbids '.' in durable names (it would be parsed as a
     *  subject token); we use '_' so the durable token survives the client-side validator. */
    private static final String CONSUMER_PREFIX = "g_";
    /** Sentinel marker for the per-URI ensureStream cache (presence = stream ensured). */
    private static final Object STREAM_MARKER = new Object();

    /** jnats 2.20.x JetStream API error code for "stream not found". */
    private static final int STREAM_NOT_FOUND = 10059;
    /** jnats 2.20.x JetStream API error code for "consumer not found". */
    private static final int CONSUMER_NOT_FOUND = 10014;

    private final Connection natsConnection;
    private final EnvelopeCodec envelopeCodec;
    private final MessageAuthenticator authenticator;
    private final String nodeId;

    private final long streamMaxLen;
    private final long pollBlockMs;
    private final int pollCount;
    private final int dedupWindow;

    private final JetStream js;
    private final JetStreamManagement jsm;

    private final AtomicReference<BrokerState> state = new AtomicReference<>(BrokerState.ACTIVE);
    /** Per-URI ensureStream cache — presence = stream confirmed ensured for this URI. */
    private final ConcurrentHashMap<String, Object> streamCache = new ConcurrentHashMap<>();
    /** Per-URI subscription handles — URI → handle (thread + stop flag + subscription) for shutdown. */
    private final ConcurrentHashMap<String, SubscriptionHandle> subscriptions = new ConcurrentHashMap<>();

    /** Max accepted INBOUND envelope byte length before unwrap/decode. 0 = unlimited.
     *  Mirrors {@code RedisStreamsReliableBroker.inboundMaxBytes}. */
    private volatile int inboundMaxBytes = 0;

    /** Idle window (ms) a dead node's durable consumer must be inactive before reaping. Default 1h. */
    private volatile long groupDestroyIdleMs = Duration.ofHours(1).toMillis();

    /** Captured ConnectionListener (kept so the test can drive the CAS path deterministically). */
    private final ConnectionListener connectionListener;

    /** Backward-compat constructor — no authentication (NoOp). */
    public NatsJetStreamReliableBroker(Connection natsConnection, EnvelopeCodec envelopeCodec,
                                       String nodeId, long streamMaxLen, long pollBlockMs,
                                       int pollCount, int dedupWindow) {
        this(natsConnection, envelopeCodec, new NoOpMessageAuthenticator(), nodeId,
                streamMaxLen, pollBlockMs, pollCount, dedupWindow);
    }

    public NatsJetStreamReliableBroker(Connection natsConnection, EnvelopeCodec envelopeCodec,
                                       MessageAuthenticator authenticator, String nodeId,
                                       long streamMaxLen, long pollBlockMs, int pollCount,
                                       int dedupWindow) {
        this.natsConnection = Objects.requireNonNull(natsConnection, "natsConnection");
        this.envelopeCodec = Objects.requireNonNull(envelopeCodec, "envelopeCodec");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.streamMaxLen = streamMaxLen;
        this.pollBlockMs = pollBlockMs;
        this.pollCount = Math.max(1, pollCount);
        this.dedupWindow = Math.max(16, dedupWindow);
        try {
            this.js = natsConnection.jetStream();
            this.jsm = natsConnection.jetStreamManagement();
        } catch (IOException e) {
            throw new ClusterBrokerException("Failed to open JetStream views on NATS connection", e);
        }

        // Event-driven transport health: flip the broker state the instant the NATS connection drops/
        // recovers, mirroring NatsClusterBroker / RedisStreamsReliableBroker. DEGRADED is informational —
        // the fetch loop keeps retrying on exception (it only checks state != SHUTDOWN), so a brief
        // disconnect rides out and resumes once jnats reconnects.
        this.connectionListener = (conn, ev) -> {
            switch (ev) {
                case DISCONNECTED:
                case CLOSED:
                    if (state.compareAndSet(BrokerState.ACTIVE, BrokerState.DEGRADED)) {
                        log.warn("NatsJetStreamReliableBroker transport {} — state DEGRADED", ev);
                    }
                    break;
                case RECONNECTED:
                case CONNECTED:
                    if (state.compareAndSet(BrokerState.DEGRADED, BrokerState.ACTIVE)) {
                        log.info("NatsJetStreamReliableBroker transport {} — state ACTIVE", ev);
                    }
                    break;
                default:
                    log.debug("NATS connection event {} (no state change)", ev);
            }
        };
        natsConnection.addConnectionListener(this.connectionListener);

        log.info("NatsJetStreamReliableBroker initialized (maxMsgs={}, fetchBlock={}ms, fetchCount={}, dedupWindow={})",
                streamMaxLen, pollBlockMs, this.pollCount, this.dedupWindow);
    }

    /** Sets the max accepted inbound envelope byte length before unwrap/decode. 0 = unlimited. */
    public void setInboundMaxBytes(int inboundMaxBytes) {
        this.inboundMaxBytes = Math.max(0, inboundMaxBytes);
    }

    /** Sets the idle window (ms) before a dead node's consumer may be reaped. {@code <= 0} = never reap. */
    public void setGroupDestroyIdleMs(long groupDestroyIdleMs) {
        this.groupDestroyIdleMs = groupDestroyIdleMs;
    }

    /** Encodes a uri/nodeId into a single NATS-stream-name-safe / NATS-subject-safe token (base64url, no padding). */
    static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    // ---- ReliableBroker SPI ----

    @Override
    public void reliablePublish(String uri, ClusterEnvelope envelope) {
        if (state.get() == BrokerState.SHUTDOWN) {
            throw new ClusterBrokerException("Reliable broker SHUTDOWN");
        }
        String b64uri = b64(uri);
        ensureStream(b64uri);
        String subject = SUBJECT_PREFIX + b64uri;
        try {
            String encoded = envelopeCodec.encode(envelope);
            String wrapped = authenticator.wrap(encoded);
            byte[] payload = wrapped.getBytes(StandardCharsets.UTF_8);
            js.publishAsync(subject, payload);
        } catch (Exception ex) {
            // Mirrors RedisStreamsReliableBroker: don't throw on transient transport failure — the
            // caller's on-publish-failure policy is honored at a higher layer via ClusterMessageSender.
            log.warn("Reliable publish failed for uri={}", uri, ex);
        }
    }

    @Override
    public ClusterSubscription reliableSubscribe(String uri, String subscriberNodeId, ClusterMessageListener listener) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(subscriberNodeId, "subscriberNodeId");
        Objects.requireNonNull(listener, "listener");
        String b64uri = b64(uri);
        ensureStream(b64uri);
        String streamName = STREAM_PREFIX + b64uri;
        String subject = SUBJECT_PREFIX + b64uri;
        String consumerName = CONSUMER_PREFIX + b64(subscriberNodeId);

        ensureDurableConsumer(streamName, consumerName, subject);

        JetStreamSubscription sub;
        try {
            sub = js.subscribe(subject,
                    PullSubscribeOptions.builder().durable(consumerName).stream(streamName).build());
        } catch (IOException | JetStreamApiException e) {
            throw new ClusterBrokerException("Failed to subscribe to " + streamName, e);
        }

        AtomicBoolean stop = new AtomicBoolean(false);
        DedupRing dedup = new DedupRing(dedupWindow);
        String threadName = "nats-reliable-"
                + subscriberNodeId.substring(0, Math.min(8, subscriberNodeId.length()))
                + "-" + Integer.toHexString(uri.hashCode());
        Thread t = new Thread(() -> consumeLoop(sub, uri, listener, dedup, stop), threadName);
        t.setDaemon(true);
        SubscriptionHandle h = new SubscriptionHandle(t, sub, stop);
        // Make the handle visible to shutdown() BEFORE the thread starts running — otherwise an
        // immediate shutdown() could miss it.
        subscriptions.put(uri, h);
        t.start();

        return new ClusterSubscription() {
            @Override
            public void unsubscribe() {
                if (stop.compareAndSet(false, true)) {
                    try { sub.unsubscribe(); } catch (Exception ignored) {}
                    try { t.join(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    subscriptions.remove(uri);
                }
            }
            @Override
            public boolean isActive() {
                return !stop.get();
            }
        };
    }

    @Override
    public void destroyConsumerGroupsForNode(String deadNodeId) {
        long idleMs = groupDestroyIdleMs;
        String consumerName = CONSUMER_PREFIX + b64(deadNodeId);
        List<String> streams;
        try {
            streams = jsm.getStreamNames();
        } catch (IOException | JetStreamApiException e) {
            log.warn("destroyConsumerGroupsForNode({}) — failed to list streams", deadNodeId, e);
            return;
        }
        int reaped = 0;
        int retained = 0;
        for (String streamName : streams) {
            if (!streamName.startsWith(STREAM_PREFIX)) {
                continue; // not one of ours
            }
            if (idleMs <= 0) {
                retained++;
                continue;   // pure-retain mode
            }
            try {
                ConsumerInfo info = jsm.getConsumerInfo(streamName, consumerName);
                long pending = info.getNumPending();
                SequenceInfo delivered = info.getDelivered();
                ZonedDateTime lastActive = delivered == null ? null : delivered.getLastActive();
                long idle = lastActive == null
                        ? Long.MAX_VALUE   // never delivered → treat as fully idle
                        : System.currentTimeMillis() - lastActive.toInstant().toEpochMilli();
                if (pending == 0 && idle > idleMs) {
                    try {
                        jsm.deleteConsumer(streamName, consumerName);
                        reaped++;
                        log.info("Reaped idle reliable consumer {} from {}", consumerName, streamName);
                    } catch (JetStreamApiException | IOException de) {
                        log.debug("Delete of consumer {} on {} failed — retaining", consumerName, streamName, de);
                        retained++;
                    }
                } else {
                    retained++;
                    log.debug("Keeping reliable consumer {} on {} (pending={}, idleMs={})",
                            consumerName, streamName, pending, idle);
                }
            } catch (JetStreamApiException jae) {
                if (jae.getApiErrorCode() == CONSUMER_NOT_FOUND || jae.getErrorCode() == 404) {
                    continue; // already gone — fine
                }
                log.warn("dead-node cleanup of {} on {} failed", consumerName, streamName, jae);
                retained++;
            } catch (IOException ioe) {
                log.warn("dead-node cleanup of {} on {} failed (IO)", consumerName, streamName, ioe);
                retained++;
            }
        }
        log.info("Reliable consumer {} cleanup across {} streams: reaped={}, retained={}",
                consumerName, streams.size(), reaped, retained);
    }

    @Override
    public BrokerState state() {
        return state.get();
    }

    @Override
    public void shutdown() {
        state.set(BrokerState.SHUTDOWN);
        // Stop + unsubscribe all per-URI fetch loops. We do NOT close the NATS Connection here —
        // it is owned by the auto-config bean (nettyClusterNatsJetStreamConnection) with its own
        // destroyMethod. Unacked messages remain in the stream PEL and replay on next start via
        // the durable consumer cursor (mirrors the Redis Streams reliable shutdown contract).
        for (Map.Entry<String, SubscriptionHandle> e : subscriptions.entrySet()) {
            SubscriptionHandle h = e.getValue();
            h.stop.set(true);
            try { h.sub.unsubscribe(); } catch (Exception ignored) {}
            try { h.thread.join(2000); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        subscriptions.clear();
        log.info("NatsJetStreamReliableBroker shut down");
    }

    // ---- ensureStream + ensureDurableConsumer ----

    /** Idempotent per-URI stream creation with mismatch detection. Throws ClusterBrokerException on
     *  config mismatch (no silent reuse). Propagates transient transport errors so the cache is not
     *  poisoned with NULL — the next publish will retry. */
    void ensureStream(String b64uri) {
        streamCache.computeIfAbsent(b64uri, k -> {
            String streamName = STREAM_PREFIX + b64uri;
            StreamConfiguration desired = StreamConfiguration.builder()
                    .name(streamName)
                    .subjects(SUBJECT_PREFIX + b64uri)
                    .storageType(StorageType.File)
                    .retentionPolicy(RetentionPolicy.Limits)
                    .discardPolicy(DiscardPolicy.Old)
                    .maxMessages(streamMaxLen)
                    .maxAge(Duration.ZERO)          // unlimited — only MAXMSGS governs retention
                    .replicas(1)
                    .build();
            try {
                StreamInfo info = jsm.getStreamInfo(streamName);
                StreamConfiguration actual = info.getConfiguration();
                if (configMatches(actual, desired)) {
                    return STREAM_MARKER;
                }
                log.warn("Pre-existing JetStream stream {} has incompatible config — "
                                + "expected: storage={}, discard={}, retention={}, maxMsgs={}, replicas={}; "
                                + "actual: storage={}, discard={}, retention={}, maxMsgs={}, replicas={}",
                        streamName,
                        desired.getStorageType(), desired.getDiscardPolicy(), desired.getRetentionPolicy(),
                        desired.getMaxMsgs(), desired.getReplicas(),
                        actual.getStorageType(), actual.getDiscardPolicy(), actual.getRetentionPolicy(),
                        actual.getMaxMsgs(), actual.getReplicas());
                throw new ClusterBrokerException("JetStream stream " + streamName
                        + " already exists with incompatible config (fail-fast; do not silently reuse)");
            } catch (JetStreamApiException jae) {
                if (jae.getApiErrorCode() == STREAM_NOT_FOUND || jae.getErrorCode() == 404) {
                    try {
                        jsm.addStream(desired);
                        return STREAM_MARKER;
                    } catch (IOException | JetStreamApiException ce) {
                        throw new ClusterBrokerException("Failed to create JetStream stream " + streamName, ce);
                    }
                }
                throw new ClusterBrokerException(
                        "ensureStream getStreamInfo failed for " + streamName, jae);
            } catch (IOException ioe) {
                throw new ClusterBrokerException("ensureStream IO failed for " + streamName, ioe);
            }
        });
    }

    private static boolean configMatches(StreamConfiguration actual, StreamConfiguration desired) {
        return actual.getStorageType() == desired.getStorageType()
                && actual.getDiscardPolicy() == desired.getDiscardPolicy()
                && actual.getRetentionPolicy() == desired.getRetentionPolicy()
                && actual.getMaxMsgs() == desired.getMaxMsgs()
                && actual.getReplicas() == desired.getReplicas();
    }

    /** Lazy-create the per-node durable pull consumer on the URI's stream (idempotent). */
    private void ensureDurableConsumer(String streamName, String consumerName, String filterSubject) {
        try {
            jsm.getConsumerInfo(streamName, consumerName);
            return; // exists
        } catch (JetStreamApiException jae) {
            if (jae.getApiErrorCode() != CONSUMER_NOT_FOUND && jae.getErrorCode() != 404) {
                throw new ClusterBrokerException(
                        "Failed to check consumer " + consumerName + " on " + streamName, jae);
            }
            // fall through: create
        } catch (IOException ioe) {
            throw new ClusterBrokerException(
                    "Failed to check consumer " + consumerName + " on " + streamName, ioe);
        }
        ConsumerConfiguration cc = ConsumerConfiguration.builder()
                .durable(consumerName)
                .ackPolicy(AckPolicy.Explicit)
                .deliverPolicy(DeliverPolicy.All)
                .filterSubject(filterSubject)
                .build();
        try {
            jsm.addOrUpdateConsumer(streamName, cc);
        } catch (IOException | JetStreamApiException e) {
            throw new ClusterBrokerException(
                    "Failed to create durable consumer " + consumerName + " on " + streamName, e);
        }
    }

    // ---- Consume loop ----

    private void consumeLoop(JetStreamSubscription sub, String uri, ClusterMessageListener listener,
                             DedupRing dedup, AtomicBoolean stop) {
        while (!stop.get() && state.get() != BrokerState.SHUTDOWN) {
            try {
                List<Message> batch = sub.fetch(pollCount, Duration.ofMillis(pollBlockMs));
                if (batch == null || batch.isEmpty()) {
                    continue;
                }
                for (Message msg : batch) {
                    handleMessage(msg, uri, listener, dedup);
                }
            } catch (Exception ex) {
                if (stop.get() || state.get() == BrokerState.SHUTDOWN) {
                    break;
                }
                log.warn("Reliable fetch loop transient error on uri={}", uri, ex);
                try { Thread.sleep(500); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
        log.debug("Reliable fetch loop for uri={} stopped", uri);
    }

    void handleMessage(Message msg, String uri, ClusterMessageListener listener, DedupRing dedup) {
        byte[] data = msg.getData();
        int max = inboundMaxBytes;
        if (max > 0 && data != null && data.length > max) {
            log.warn("Dropping oversized reliable message: {} > {} bytes (uri={}) — acking to clear PEL",
                    data.length, max, uri);
            msg.ack();
            return;
        }
        String wire = data == null ? null : new String(data, StandardCharsets.UTF_8);
        String inner = wire == null ? null : authenticator.unwrap(wire);
        if (inner == null) {
            log.warn("Reliable entry on uri={} dropped (no data or rejected HMAC) — acking to clear PEL", uri);
            msg.ack();
            return;
        }
        ClusterEnvelope envelope;
        try {
            envelope = envelopeCodec.decode(inner);
        } catch (Exception e) {
            log.warn("Envelope decode failed on uri={} — acking to clear PEL", uri, e);
            msg.ack();
            return;
        }
        if (envelope == null) {
            msg.ack();
            return;
        }
        if (nodeId.equals(envelope.getOriginNodeId())) {
            // Origin self-suppress (broker-level guard; ClusterMessageSender's onReliableMessage
            // does the same check redundantly — harmless).
            msg.ack();
            return;
        }
        String envId = dedupKey(msg, envelope);
        if (envId != null && dedup.contains(envId)) {
            msg.ack();
            return;
        }
        if (envId != null) {
            dedup.add(envId);
        }
        try {
            listener.onMessage(envelope);
            msg.ack();
        } catch (Throwable t) {
            // Poison-pill guard: log + ack so a single failing handler can't livelock the loop or
            // hold up the rest of the batch. Mirrors RedisStreamsReliableBroker.
            log.warn("Reliable listener threw on uri={} (envelope dropped to avoid livelock)", uri, t);
            msg.ack();
        }
    }

    /** Per-stream sequence is the natural dedup key for JetStream (no envelope-id field exists).
     *  Falls back to a stable origin+timestamp+uri token if metadata is unavailable. */
    private static String dedupKey(Message msg, ClusterEnvelope env) {
        try {
            long seq = msg.metaData().streamSequence();
            return "s." + seq;
        } catch (Throwable ignored) {
            // No JetStream meta (e.g. unit-test stub) — fall back to envelope tuple.
            return "e." + env.getOriginNodeId() + "." + env.getTimestamp() + "." + env.getUri();
        }
    }

    // ---- Helpers ----

    /** Fixed-capacity LRU ring of recently-seen dedup keys (in-process redelivery guard). */
    static final class DedupRing {
        private final Map<String, Boolean> seen;
        DedupRing(int capacity) {
            int cap = Math.max(16, capacity);
            this.seen = Collections.synchronizedMap(
                    new LinkedHashMap<String, Boolean>(cap * 2, 0.75f, true) {
                        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                            return size() > cap;
                        }
                    });
        }
        boolean contains(String k) { return seen.containsKey(k); }
        void add(String k) { seen.put(k, Boolean.TRUE); }
    }

    /** Holds the thread + subscription + stop flag for one URI's fetch loop. */
    static final class SubscriptionHandle {
        final Thread thread;
        final JetStreamSubscription sub;
        final AtomicBoolean stop;
        SubscriptionHandle(Thread thread, JetStreamSubscription sub, AtomicBoolean stop) {
            this.thread = thread;
            this.sub = sub;
            this.stop = stop;
        }
    }

    // ---- Visible for testing ----

    /** Visible to the unit test so it can drive the connection-state CAS path deterministically. */
    ConnectionListener connectionListenerForTest() {
        return connectionListener;
    }
}
