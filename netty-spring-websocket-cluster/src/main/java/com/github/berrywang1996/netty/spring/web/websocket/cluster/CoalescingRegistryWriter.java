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

package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Rate-limited, coalescing writer in front of {@link SessionRegistry} register/deregister, to absorb
 * reconnect storms without dropping any registration.
 *
 * <p><b>Pass-through under rate:</b> while token-bucket tokens are available (normal load), a write
 * goes straight to the registry with no added latency — behaviorally identical to pre-1.9.0. Only when
 * the per-node write rate is exceeded do ops queue into a per-session coalescing map (latest op per
 * {@code uri|sessionId} wins) and drain on a flusher thread as tokens refill. A register is NEVER
 * dropped: the map is bounded by the number of distinct pending sessions, and {@link #shutdown()}
 * drains everything ignoring the rate.
 *
 * <p>Rate {@code <= 0} disables throttling entirely (pure pass-through, no flusher thread).
 *
 * <p><b>Tradeoff:</b> a session that connects and disconnects faster than the flush interval while
 * throttled may never be written (its register+deregister coalesce to a no-op DEL). That sub-flush-
 * interval window is acceptable given cross-node delivery is already at-most-once.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class CoalescingRegistryWriter {

    private enum OpType { REGISTER, DEREGISTER }

    private static final class Op {
        final OpType type;
        final String uri;
        final String sessionId;
        final String nodeId;
        final Map<String, String> metadata;
        Op(OpType type, String uri, String sessionId, String nodeId, Map<String, String> metadata) {
            this.type = type; this.uri = uri; this.sessionId = sessionId;
            this.nodeId = nodeId; this.metadata = metadata;
        }
    }

    private static final int BACKLOG_WARN_THRESHOLD = 10_000;

    private final SessionRegistry delegate;
    private final double maxTokens;
    private final double refillPerMs;
    private final long flushIntervalMs;
    private final String nodeLabel;

    private final ConcurrentHashMap<String, Op> pending = new ConcurrentHashMap<>();
    private final Object bucketLock = new Object();
    private double tokens;
    private long lastRefillNanos = System.nanoTime();

    private ScheduledExecutorService flusher;

    /**
     * @param delegate       the underlying registry
     * @param ratePerSecond  max sustained register+deregister ops/sec; {@code <= 0} disables throttling
     * @param flushIntervalMs how often the flusher drains the coalescing map (ms)
     * @param nodeLabel      node id (used only for the flusher thread name)
     */
    public CoalescingRegistryWriter(SessionRegistry delegate, long ratePerSecond,
                                    long flushIntervalMs, String nodeLabel) {
        this.delegate = delegate;
        this.maxTokens = ratePerSecond <= 0 ? 0 : Math.max(1, ratePerSecond);
        this.refillPerMs = ratePerSecond <= 0 ? 0 : ratePerSecond / 1000.0;
        this.flushIntervalMs = Math.max(1, flushIntervalMs);
        this.tokens = this.maxTokens;
        this.nodeLabel = nodeLabel == null ? "" : nodeLabel.substring(0, Math.min(8, nodeLabel.length()));
    }

    /** Starts the background flusher (no-op when throttling is disabled). */
    public void start() {
        if (maxTokens <= 0) {
            return; // pure pass-through — no flusher needed
        }
        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-regwriter-" + nodeLabel);
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleAtFixedRate(this::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    public void register(String uri, String sessionId, String nodeId, Map<String, String> metadata) {
        if (maxTokens <= 0 || tryAcquire()) {
            doRegister(uri, sessionId, nodeId, metadata);
        } else {
            enqueue(new Op(OpType.REGISTER, uri, sessionId, nodeId, metadata));
        }
    }

    public void deregister(String uri, String sessionId) {
        if (maxTokens <= 0 || tryAcquire()) {
            doDeregister(uri, sessionId);
        } else {
            enqueue(new Op(OpType.DEREGISTER, uri, sessionId, null, null));
        }
    }

    /** Drains the coalescing map up to the tokens available this tick. Exposed for tests/manual drain. */
    public void flush() {
        try {
            Iterator<Map.Entry<String, Op>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                if (!tryAcquire()) {
                    break; // out of tokens — the rest drain next tick
                }
                Op op = it.next().getValue();
                it.remove();
                apply(op);
            }
        } catch (Exception e) {
            log.warn("Cluster registry flush failed", e);
        }
    }

    /** Number of coalesced ops currently queued (for tests / visibility). */
    public int pendingCount() {
        return pending.size();
    }

    /** Stops the flusher (if any) and drains everything remaining, ignoring the rate, so nothing is lost. */
    public void shutdown() {
        if (flusher != null) {
            flusher.shutdown();
        }
        Iterator<Map.Entry<String, Op>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Op op = it.next().getValue();
            it.remove();
            apply(op);
        }
    }

    // ---- internals ----

    private void enqueue(Op op) {
        pending.put(op.uri + "|" + op.sessionId, op); // coalesce: latest op per session wins
        if (pending.size() == BACKLOG_WARN_THRESHOLD) {
            log.warn("Cluster registry write backlog reached {} (reconnect storm?) — writes are being "
                    + "rate-limited to protect Redis; no registration is dropped", BACKLOG_WARN_THRESHOLD);
        }
    }

    private void apply(Op op) {
        if (op.type == OpType.REGISTER) {
            doRegister(op.uri, op.sessionId, op.nodeId, op.metadata);
        } else {
            doDeregister(op.uri, op.sessionId);
        }
    }

    private boolean tryAcquire() {
        synchronized (bucketLock) {
            long now = System.nanoTime();
            double elapsedMs = (now - lastRefillNanos) / 1_000_000.0;
            tokens = Math.min(maxTokens, tokens + elapsedMs * refillPerMs);
            lastRefillNanos = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private void doRegister(String uri, String sessionId, String nodeId, Map<String, String> metadata) {
        delegate.register(uri, sessionId, nodeId, metadata).exceptionally(ex -> {
            log.warn("Failed to register session {} in cluster registry", sessionId, ex);
            return null;
        });
    }

    private void doDeregister(String uri, String sessionId) {
        delegate.deregister(uri, sessionId).exceptionally(ex -> {
            log.warn("Failed to deregister session {} from cluster registry", sessionId, ex);
            return null;
        });
    }
}
