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

    /** Max accepted UTF-8 byte length of an INBOUND stream entry's payload before unwrap/decode. 0 = unlimited.
     *  Mirrors {@code RedisPubSubBroker.inboundMaxBytes} — guards against a malicious/compromised peer
     *  writing a huge entry into the shared stream (remote OOM on the consuming node). */
    private volatile int inboundMaxBytes = 0;

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

        // Event-driven transport health (parity with RedisPubSubBroker): flip broker state the instant
        // the Lettuce connection drops/recovers. DEGRADED here is informational — the consume loop
        // keeps retrying on exception (it does NOT check `state == ACTIVE`, only `!= SHUTDOWN`), so
        // it naturally rides out a brief disconnect and resumes once Lettuce reconnects.
        this.redisClient.addListener(new io.lettuce.core.RedisConnectionStateListener() {
            @Override
            public void onRedisConnected(io.lettuce.core.RedisChannelHandler<?, ?> connection, java.net.SocketAddress addr) {
                if (state.compareAndSet(BrokerState.DEGRADED, BrokerState.ACTIVE)) {
                    log.info("RedisStreamsReliableBroker transport reconnected — state ACTIVE");
                }
            }
            @Override
            public void onRedisDisconnected(io.lettuce.core.RedisChannelHandler<?, ?> connection) {
                if (state.compareAndSet(BrokerState.ACTIVE, BrokerState.DEGRADED)) {
                    log.warn("RedisStreamsReliableBroker transport disconnected — state DEGRADED");
                }
            }
            @Override
            public void onRedisExceptionCaught(io.lettuce.core.RedisChannelHandler<?, ?> connection, Throwable cause) {
                // Surfaced via onRedisDisconnected; nothing to do here.
            }
        });

        this.commandConnection = redisClient.connect();
        log.info("RedisStreamsReliableBroker initialized (maxlen={}, block={}ms, count={})",
                streamMaxLen, pollBlockMs, pollCount);
    }

    /** Sets the max accepted UTF-8 byte length of an inbound stream entry's payload before unwrap/decode.
     *  0 = unlimited. Wired by the auto-config from the same inbound-size-cap property as the pub/sub broker. */
    public void setInboundMaxBytes(int inboundMaxBytes) {
        this.inboundMaxBytes = Math.max(0, inboundMaxBytes);
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
        // Inbound-size guard BEFORE unwrap/decode: an attacker-influenced stream entry could be huge
        // (remote OOM). Drop it — but ACK first so it clears the PEL and is never redelivered (same
        // handling as a poison/rejected entry below).
        int max = inboundMaxBytes;
        if (max > 0 && data != null) {
            int sz = data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (sz > max) {
                log.warn("Dropping oversized reliable entry {} on {} ({} > {} bytes) — possible "
                        + "misbehaving/hostile publisher; acking to clear PEL", id, streamKey, sz, max);
                seen.put(id, Boolean.TRUE);
                ack(streamKey, group, id);
                return;
            }
        }
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

    /**
     * Idle window (ms) a consumer group's last-delivered entry must be older than before it is eligible for
     * destruction. A node's id is stable/configurable, so a node that DIES and RESTARTS reuses its group
     * {@code g:{nodeId}} — destroying it on bare heartbeat-expiry would XGROUP-DESTROY the group, wipe its
     * retained offset + PEL, and the restarted node would resubscribe from {@code $} and silently SKIP the
     * backlog (the very replay the reliable path promises → data loss). This window keeps a recently-active
     * group (i.e. a node that just died and may restart) intact. Default 1h; far larger than any realistic
     * crash-restart gap, while stream MAXLEN trimming still bounds growth. {@code 0} = never destroy on
     * expiry (pure retain — see {@link #destroyConsumerGroupsForNode}).
     */
    private volatile long groupDestroyIdleMs = Duration.ofHours(1).toMillis();

    /** Sets the idle window (ms) before a node's consumer group may be destroyed on heartbeat-expiry.
     *  {@code <= 0} = never destroy on expiry (retain for possible node restart; rely on MAXLEN trimming). */
    public void setGroupDestroyIdleMs(long groupDestroyIdleMs) {
        this.groupDestroyIdleMs = groupDestroyIdleMs;
    }

    /**
     * Called from the reconciliation dead-node callback. <b>Safety-gated</b> so it can NEVER wipe the
     * offset + PEL of a node that merely crashed and will restart with the same (stable/configurable) id.
     *
     * <p>For each stream, a node's group {@code g:{nodeId}} is destroyed ONLY when it is provably stale:
     * <ul>
     *   <li>it has <b>zero pending</b> entries (nothing in-flight that a restart would need to re-process), AND</li>
     *   <li>its <b>last-delivered entry is older than {@link #groupDestroyIdleMs}</b> (the node has been gone
     *       long past any realistic crash-restart window).</li>
     * </ul>
     * If {@code groupDestroyIdleMs <= 0}, or the group's staleness cannot be confirmed from
     * {@code XINFO GROUPS} (e.g. the field is missing or unparseable), the group is <b>retained</b> — a
     * retained group costs only a small offset/PEL entry, bounded by the stream's MAXLEN trimming, whereas
     * a wrongly-destroyed group means silent backlog loss on restart. We always prefer to retain on doubt.
     */
    @Override
    public void destroyConsumerGroupsForNode(String nodeId) {
        String group = GROUP_PREFIX + nodeId;
        long idleMs = groupDestroyIdleMs;
        try {
            Set<String> uris = commandConnection.sync().smembers(STREAMS_SET);
            if (uris == null) return;
            int destroyed = 0;
            int retained = 0;
            for (String uri : uris) {
                String streamKey = STREAM_PREFIX + uri;
                if (idleMs <= 0) {
                    retained++;
                    continue; // pure-retain mode: never destroy on bare expiry
                }
                try {
                    if (isGroupStale(streamKey, group, idleMs)) {
                        commandConnection.sync().xgroupDestroy(streamKey, group);
                        destroyed++;
                    } else {
                        retained++;
                    }
                } catch (Exception e) {
                    // On any doubt, retain (do NOT destroy) — losing a backlog is worse than an idle group.
                    log.debug("Staleness check/destroy of {} on {} failed — retaining group", group, streamKey, e);
                    retained++;
                }
            }
            log.info("Reliable consumer group {} cleanup across {} streams: destroyed={}, retained={}",
                    group, uris.size(), destroyed, retained);
        } catch (Exception e) {
            log.warn("destroyConsumerGroupsForNode({}) failed", nodeId, e);
        }
    }

    /**
     * Returns true only if the group is provably safe to destroy: zero pending AND its last-delivered entry
     * is older than {@code idleMs}. Any ambiguity (group absent → already gone, so "stale"; field missing or
     * unparseable → conservatively NOT stale = retain) errs toward retaining the backlog.
     */
    private boolean isGroupStale(String streamKey, String group, long idleMs) {
        List<Object> groups;
        try {
            groups = commandConnection.sync().xinfoGroups(streamKey);
        } catch (RedisCommandExecutionException e) {
            // Stream itself is gone (no group/key) → nothing to protect; treat as stale (destroy is a no-op anyway).
            return true;
        }
        if (groups == null) {
            return false; // can't tell → retain
        }
        for (Object g : groups) {
            if (!(g instanceof List)) continue;
            List<?> fields = (List<?>) g;
            Map<String, Object> info = new java.util.HashMap<>();
            for (int i = 0; i + 1 < fields.size(); i += 2) {
                Object k = fields.get(i);
                if (k != null) info.put(String.valueOf(k), fields.get(i + 1));
            }
            if (!group.equals(String.valueOf(info.get("name")))) {
                continue;
            }
            // Found our group. Pending > 0 → in-flight work a restart must re-process → retain.
            long pending = asLong(info.get("pending"), -1);
            if (pending != 0) {
                return false;
            }
            // last-delivered-id is "<millis>-<seq>"; if it's recent, the node may have just died → retain.
            String lastId = info.get("last-delivered-id") == null ? null : String.valueOf(info.get("last-delivered-id"));
            Long lastMs = streamIdMillis(lastId);
            if (lastMs == null) {
                return false; // unknown activity time → retain
            }
            return (System.currentTimeMillis() - lastMs) > idleMs;
        }
        // Group not present in this stream → already gone; destroying is a harmless no-op.
        return true;
    }

    private static long asLong(Object o, long dflt) {
        if (o instanceof Number) return ((Number) o).longValue();
        try { return o == null ? dflt : Long.parseLong(String.valueOf(o)); }
        catch (NumberFormatException e) { return dflt; }
    }

    /** Parses the millisecond timestamp from a Redis stream id ("<millis>-<seq>"); null if unparseable. */
    private static Long streamIdMillis(String id) {
        if (id == null) return null;
        int dash = id.indexOf('-');
        String ms = dash >= 0 ? id.substring(0, dash) : id;
        try { return Long.parseLong(ms); }
        catch (NumberFormatException e) { return null; }
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
