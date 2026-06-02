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
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Redis Streams implementation of {@link ReliableBroker} (at-least-once broadcast).
 *
 * <p>Per URI: stream {@code netty:cluster:rstream:{uri}}; one consumer group per node ({@code g:{nodeId}}).
 * {@code reliablePublish} = async XADD (MAXLEN~). Each subscribed URI runs a dedicated blocking connection
 * + consume thread doing {@code XREADGROUP >} (which auto-replays the backlog a briefly-offline node missed),
 * preceded by a one-time PEL drain ({@code XREADGROUP 0}) on start/after-reconnect. Entries are acked after
 * delivery; an in-process entry-id ring de-dups redelivery.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class RedisStreamsReliableBroker implements ReliableBroker {

    private static final String STREAM_PREFIX = "netty:cluster:rstream:";
    private static final String STREAMS_SET = "netty:cluster:rstreams";
    private static final String GROUP_PREFIX = "g:";
    private static final String FIELD = "e";

    private final RedisClient redisClient;
    private final EnvelopeCodec codec;
    private final MessageAuthenticator authenticator;
    private final int streamMaxLen;
    private final long pollBlockMs;
    private final int pollCount;
    private final int dedupWindow;

    private final StatefulRedisConnection<String, String> commandConnection;
    private final AtomicReference<BrokerState> state = new AtomicReference<>(BrokerState.ACTIVE);
    /** All per-subscription blocking connections, so shutdown() can close them (interrupts XREADGROUP BLOCK). */
    private final java.util.List<StatefulRedisConnection<String, String>> blockingConnections =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Backward-compat constructor — no authentication (NoOp). */
    public RedisStreamsReliableBroker(RedisClient redisClient, EnvelopeCodec codec,
                                      int streamMaxLen, long pollBlockMs, int pollCount, int dedupWindow) {
        this(redisClient, codec, streamMaxLen, pollBlockMs, pollCount, dedupWindow, new NoOpMessageAuthenticator());
    }

    public RedisStreamsReliableBroker(RedisClient redisClient, EnvelopeCodec codec,
                                      int streamMaxLen, long pollBlockMs, int pollCount, int dedupWindow,
                                      MessageAuthenticator authenticator) {
        this.redisClient = redisClient;
        this.codec = codec;
        this.authenticator = java.util.Objects.requireNonNull(authenticator, "authenticator");
        this.streamMaxLen = streamMaxLen;
        this.pollBlockMs = pollBlockMs;
        this.pollCount = pollCount;
        this.dedupWindow = Math.max(16, dedupWindow);
        this.commandConnection = redisClient.connect();
        log.info("RedisStreamsReliableBroker initialized (maxlen={}, block={}ms, count={})",
                streamMaxLen, pollBlockMs, pollCount);
    }

    @Override
    public void reliablePublish(String uri, ClusterEnvelope envelope) {
        if (state.get() == BrokerState.SHUTDOWN) {
            throw new ClusterBrokerException("Reliable broker shut down");
        }
        String streamKey = STREAM_PREFIX + uri;
        String data = authenticator.wrap(codec.encode(envelope));
        commandConnection.async().sadd(STREAMS_SET, uri)
                .exceptionally(ex -> { log.debug("SADD of {} to reliable registry failed", uri, ex); return null; });
        commandConnection.async()
                .xadd(streamKey, XAddArgs.Builder.maxlen(streamMaxLen).approximateTrimming(),
                        Collections.singletonMap(FIELD, data))
                .exceptionally(ex -> {
                    log.warn("Reliable XADD to {} failed — not persisted for remotes", streamKey, ex);
                    return null;
                });
    }

    @Override
    public ClusterSubscription reliableSubscribe(String uri, String nodeId, ClusterMessageListener listener) {
        String streamKey = STREAM_PREFIX + uri;
        String group = GROUP_PREFIX + nodeId;

        // Register this URI in the global streams set so destroyConsumerGroupsForNode can find it even if
        // this node is subscriber-only and never publishes. Use sync so the registration is durable
        // before the consumer group is created (ordering guarantee for destroyConsumerGroupsForNode).
        try { commandConnection.sync().sadd(STREAMS_SET, uri); }
        catch (Exception ex) { log.debug("SADD of {} to reliable registry (subscribe) failed", uri, ex); }

        try {
            commandConnection.sync().xgroupCreate(
                    XReadArgs.StreamOffset.from(streamKey, "$"), group, XGroupCreateArgs.Builder.mkstream());
        } catch (RedisCommandExecutionException e) {
            if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) {
                throw e;
            }
        }

        AtomicBoolean running = new AtomicBoolean(true);
        StatefulRedisConnection<String, String> blockingConn = redisClient.connect();
        blockingConn.setTimeout(Duration.ofMillis(pollBlockMs + 5000));
        blockingConnections.add(blockingConn);

        Thread t = new Thread(() -> consumeLoop(uri, streamKey, group, nodeId, listener, blockingConn, running),
                "cluster-rstream-" + nodeId.substring(0, Math.min(8, nodeId.length())) + "-" + Integer.toHexString(uri.hashCode()));
        t.setDaemon(true);
        t.start();

        return new ClusterSubscription() {
            @Override public void unsubscribe() {
                if (running.compareAndSet(true, false)) {
                    blockingConnections.remove(blockingConn);
                    try { blockingConn.close(); } catch (Exception ignored) {}
                }
            }
            @Override public boolean isActive() { return running.get(); }
        };
    }

    private void consumeLoop(String uri, String streamKey, String group, String consumerName,
                            ClusterMessageListener listener, StatefulRedisConnection<String, String> conn,
                            AtomicBoolean running) {
        Consumer consumer = Consumer.from(group, consumerName);
        Map<String, Boolean> seen = Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(dedupWindow * 2, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > dedupWindow; }
        });
        boolean drainPending = true;
        while (running.get() && state.get() != BrokerState.SHUTDOWN) {
            try {
                if (drainPending) {
                    while (running.get()) {
                        List<StreamMessage<String, String>> pend = conn.sync().xreadgroup(consumer,
                                XReadArgs.Builder.count(pollCount), XReadArgs.StreamOffset.from(streamKey, "0"));
                        if (pend == null || pend.isEmpty()) break;
                        for (StreamMessage<String, String> m : pend) deliver(streamKey, group, m, listener, seen);
                        if (pend.size() < pollCount) break;
                    }
                    drainPending = false;
                }
                List<StreamMessage<String, String>> msgs = conn.sync().xreadgroup(consumer,
                        XReadArgs.Builder.count(pollCount).block(pollBlockMs),
                        XReadArgs.StreamOffset.lastConsumed(streamKey));
                if (msgs != null) {
                    for (StreamMessage<String, String> m : msgs) deliver(streamKey, group, m, listener, seen);
                }
            } catch (Exception e) {
                if (!running.get() || state.get() == BrokerState.SHUTDOWN) break;
                log.warn("Reliable consume loop for {} errored — retrying (will re-drain PEL)", uri, e);
                drainPending = true;
                try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        log.debug("Reliable consume loop for {} stopped", uri);
    }

    private void deliver(String streamKey, String group, StreamMessage<String, String> m,
                         ClusterMessageListener listener, Map<String, Boolean> seen) {
        String id = m.getId();
        if (seen.containsKey(id)) { ack(streamKey, group, id); return; } // in-process redelivery → drop, re-ack
        String data = m.getBody() == null ? null : m.getBody().get(FIELD);
        if (data != null) {
            data = authenticator.unwrap(data); // null = rejected (missing/invalid HMAC)
        }
        if (data == null) {
            log.warn("Reliable entry {} on {} dropped (no field, or rejected HMAC) — acking to clear PEL", id, streamKey);
        } else {
            try {
                ClusterEnvelope env = codec.decode(data);
                if (env != null) {
                    listener.onMessage(env); // listener does origin self-suppression + delivery
                } else {
                    log.warn("Codec returned null for reliable entry {} on {} — acking to clear PEL", id, streamKey);
                }
            } catch (Throwable ex) {
                // Catch Throwable so a single poison entry (or an Error from a handler) can't kill the
                // consume thread or silently consume the entry without a trace. Ack to clear the PEL.
                log.warn("Failed to decode/deliver reliable entry {} on {} — acking to clear PEL", id, streamKey, ex);
            }
        }
        seen.put(id, Boolean.TRUE);
        ack(streamKey, group, id);
    }

    private void ack(String streamKey, String group, String id) {
        try { commandConnection.async().xack(streamKey, group, id); }
        catch (Exception e) { log.debug("XACK {} on {} failed", id, streamKey, e); }
    }

    @Override
    public void destroyConsumerGroupsForNode(String nodeId) {
        String group = GROUP_PREFIX + nodeId;
        try {
            Set<String> uris = commandConnection.sync().smembers(STREAMS_SET);
            if (uris == null) return;
            for (String uri : uris) {
                try { commandConnection.sync().xgroupDestroy(STREAM_PREFIX + uri, group); }
                catch (Exception e) { log.debug("XGROUP DESTROY {} on {} failed", group, uri, e); }
            }
            log.info("Destroyed reliable consumer group {} across {} streams", group, uris.size());
        } catch (Exception e) {
            log.warn("destroyConsumerGroupsForNode({}) failed", nodeId, e);
        }
    }

    @Override public BrokerState state() { return state.get(); }

    @Override
    public void shutdown() {
        state.set(BrokerState.SHUTDOWN);
        // Close all blocking connections — interrupts any in-flight XREADGROUP BLOCK so the daemon
        // consume threads exit promptly (the SPI contract: shutdown stops loops + releases connections).
        java.util.List<StatefulRedisConnection<String, String>> snapshot;
        synchronized (blockingConnections) { snapshot = new java.util.ArrayList<>(blockingConnections); }
        for (StatefulRedisConnection<String, String> c : snapshot) {
            try { c.close(); } catch (Exception ignored) {}
        }
        blockingConnections.clear();
        try { commandConnection.close(); } catch (Exception e) { log.warn("Error closing reliable cmd conn", e); }
        log.info("RedisStreamsReliableBroker shut down");
    }
}
