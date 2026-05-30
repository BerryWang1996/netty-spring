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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.node;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages this node's lifecycle within the cluster: heartbeat, state transitions,
 * failure detection of peers, and periodic reconciliation.
 *
 * <p>State machine:
 * <pre>
 *   JOINING ──→ ACTIVE ──→ DRAINING ──→ LEFT
 *                  │                       ↑
 *                  ↓                       │
 *              DEGRADED ──→ RESYNC ────────┘
 * </pre>
 *
 * <p>This class is transport-agnostic: it does not directly touch Redis or any broker.
 * Heartbeat persistence and peer-failure detection are delegated to
 * {@link ClusterNodeHeartbeat} (an interface with a Redis default impl).
 *
 * @author berrywang1996
 * @since V1.8.0
 */
@Slf4j
public class ClusterNodeManager {

    private final String nodeId;
    private final AtomicReference<NodeState> state = new AtomicReference<>(NodeState.JOINING);
    private final List<NodeStateListener> listeners = new CopyOnWriteArrayList<>();

    private final long heartbeatIntervalMs;
    private final long heartbeatTimeoutMs;
    private final long reconciliationIntervalMs;
    private final long drainTimeoutMs;

    private final ClusterNodeHeartbeat heartbeat;
    private final SessionRegistry sessionRegistry;

    /** Callback invoked when a dead node is detected during reconciliation (for cache invalidation). */
    private volatile java.util.function.Consumer<String> deadNodeCallback;

    /** Reconciliation leader-election: only the claim winner cleans up a dead node. Default = always-reap. */
    private volatile ClusterReaper reaper = ClusterReaper.alwaysReap();

    /** Max jitter (ms) applied before DEGRADED→RESYNC re-registration, to avoid reconnect storms.
     *  Default 10000ms; configurable via {@link #setReconnectJitterMaxMs(long)}. */
    private volatile long reconnectJitterMaxMs = 10_000L;

    /** Grace period (ms) before a transport loss actually degrades this node. 0 = instant. Default 5000. */
    private volatile long redisLossGracePeriodMs = 5000L;
    /** Pending grace-period degrade task (null = none). Mutated under {@link #graceLock}; volatile for the
     *  fast-path read in {@link #doHeartbeat()}. */
    private volatile ScheduledFuture<?> graceFuture;
    private final Object graceLock = new Object();

    /** Dedicated heartbeat-renewal scheduler (thread cluster-hb-{node}) — kept lean so a slow
     *  reconciliation sweep can never delay heartbeat renewal (which would let peers falsely reap us). */
    private ScheduledExecutorService heartbeatScheduler;
    /** Dedicated reconciliation scheduler (thread cluster-recon-{node}) — also runs the
     *  RESYNC re-register task. */
    private ScheduledExecutorService reconScheduler;
    private ScheduledFuture<?> heartbeatFuture;
    private ScheduledFuture<?> reconciliationFuture;

    /**
     * Creates a new node manager.
     *
     * @param nodeId                    unique identifier for this node (null = auto-generate UUID)
     * @param heartbeatIntervalMs       interval between heartbeat writes (default 3000)
     * @param heartbeatTimeoutMs        time after which a missing heartbeat = dead node (default 10000)
     * @param reconciliationIntervalMs  interval for the slow-path reconciliation sweep (default 15000)
     * @param drainTimeoutMs            max time to wait for sessions to close during drain (default 60000)
     * @param heartbeat                 heartbeat persistence strategy (e.g. Redis-backed)
     * @param sessionRegistry           the session registry for bulk cleanup on node failure
     */
    public ClusterNodeManager(String nodeId,
                              long heartbeatIntervalMs,
                              long heartbeatTimeoutMs,
                              long reconciliationIntervalMs,
                              long drainTimeoutMs,
                              ClusterNodeHeartbeat heartbeat,
                              SessionRegistry sessionRegistry) {
        this.nodeId = (nodeId != null && !nodeId.isEmpty()) ? nodeId : UUID.randomUUID().toString();
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
        this.reconciliationIntervalMs = reconciliationIntervalMs;
        this.drainTimeoutMs = drainTimeoutMs;
        this.heartbeat = heartbeat;
        this.sessionRegistry = sessionRegistry;
    }

    /** Returns this node's unique identifier. */
    public String getNodeId() {
        return nodeId;
    }

    /** Returns the current state of this node. */
    public NodeState getState() {
        return state.get();
    }

    /** Registers a listener for state transitions. */
    public void addStateListener(NodeStateListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Sets a callback invoked when reconciliation detects a dead node.
     * Used by ClusterMessageSender to invalidate its node lookup cache.
     */
    public void setDeadNodeCallback(java.util.function.Consumer<String> callback) {
        this.deadNodeCallback = callback;
    }

    /**
     * Sets the max jitter (ms) applied before a DEGRADED→RESYNC re-registration.
     * Bound from {@code server.netty.websocket.cluster.reconnect-jitter-max-seconds}.
     */
    public void setReconnectJitterMaxMs(long reconnectJitterMaxMs) {
        if (reconnectJitterMaxMs >= 0) {
            this.reconnectJitterMaxMs = reconnectJitterMaxMs;
        }
    }

    /**
     * Sets the grace period (ms) before a transport loss degrades the node. 0 = instant degrade.
     * Bound from {@code server.netty.websocket.cluster.redis-loss-grace-period-ms}.
     */
    public void setRedisLossGracePeriodMs(long redisLossGracePeriodMs) {
        if (redisLossGracePeriodMs >= 0) {
            this.redisLossGracePeriodMs = redisLossGracePeriodMs;
        }
    }

    /**
     * Sets the reconciliation reaper (leader-election for dead-node cleanup). Default is
     * {@link ClusterReaper#alwaysReap()} (every node reaps; correct but not deduplicated).
     */
    public void setReaper(ClusterReaper reaper) {
        if (reaper != null) {
            this.reaper = reaper;
        }
    }

    /**
     * Starts the node: registers with the cluster, begins heartbeating, starts
     * reconciliation sweeps. Transitions from JOINING → ACTIVE.
     */
    public void start() {
        if (!state.compareAndSet(NodeState.JOINING, NodeState.ACTIVE)) {
            log.warn("Cannot start node {} — current state is {}", nodeId, state.get());
            return;
        }

        String shortId = nodeId.substring(0, Math.min(8, nodeId.length()));
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-hb-" + shortId);
            t.setDaemon(true);
            return t;
        });
        reconScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-recon-" + shortId);
            t.setDaemon(true);
            return t;
        });

        // Always start reconciliation (slow-path backstop — even if initial registration
        // fails, reconciliation can detect and clean dead peers, and the heartbeat task
        // will retry registration on its next tick).
        reconciliationFuture = reconScheduler.scheduleAtFixedRate(
                this::doReconciliation, reconciliationIntervalMs, reconciliationIntervalMs, TimeUnit.MILLISECONDS);

        // Register this node
        try {
            heartbeat.register(nodeId, heartbeatTimeoutMs);
            log.info("Cluster node {} registered (heartbeat={}ms, timeout={}ms)",
                    nodeId, heartbeatIntervalMs, heartbeatTimeoutMs);
        } catch (Exception e) {
            log.error("Failed to register cluster node {} — starting in DEGRADED mode, "
                    + "heartbeat task will retry", nodeId, e);
            transitionTo(NodeState.DEGRADED);
        }

        // Schedule periodic heartbeat on its OWN thread (runs even in DEGRADED — doHeartbeat()
        // handles retry-on-failure and calls onTransportLost/onTransportRestored).
        heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(
                this::doHeartbeat, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);

        if (state.get() == NodeState.ACTIVE) {
            fireStateChange(NodeState.JOINING, NodeState.ACTIVE);
        }
    }

    /**
     * Initiates graceful drain: stops accepting new cross-node messages, sends close
     * frames to local sessions (handled by caller), waits up to drainTimeout, then
     * deregisters from the cluster.
     */
    public void drain() {
        NodeState current = state.get();
        if (current == NodeState.LEFT || current == NodeState.DRAINING) {
            return;
        }
        transitionTo(NodeState.DRAINING);
        log.info("Cluster node {} entering DRAINING state (timeout={}ms)", nodeId, drainTimeoutMs);
    }

    /**
     * Fully shuts down this node: cancels heartbeat, deregisters from cluster,
     * transitions to LEFT.
     */
    public void shutdown() {
        NodeState prev = state.getAndSet(NodeState.LEFT);
        if (prev == NodeState.LEFT) {
            return;
        }

        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
        if (reconciliationFuture != null) {
            reconciliationFuture.cancel(false);
        }
        // Cancel any pending grace-period degrade so it can't fire during/after shutdown.
        synchronized (graceLock) {
            if (graceFuture != null) {
                graceFuture.cancel(false);
                graceFuture = null;
            }
        }

        try {
            heartbeat.deregister(nodeId);
        } catch (Exception e) {
            log.warn("Error deregistering node {} from cluster", nodeId, e);
        }

        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
        }
        if (reconScheduler != null) {
            reconScheduler.shutdown();
        }

        fireStateChange(prev, NodeState.LEFT);
        log.info("Cluster node {} has LEFT the cluster", nodeId);
    }

    /**
     * Called by the broker (connection event) or {@link #doHeartbeat()} (renewal failure) when
     * transport connectivity is lost. Debounced: schedules a degrade after the grace period unless
     * the transport recovers first. With grace = 0, degrades immediately (1.8.0 behavior).
     */
    public void onTransportLost() {
        if (state.get() != NodeState.ACTIVE) {
            return; // already degraded/leaving — nothing to debounce
        }
        long grace = redisLossGracePeriodMs;
        if (grace <= 0) {
            degradeNow();
            return;
        }
        synchronized (graceLock) {
            if (state.get() != NodeState.ACTIVE) {
                return;
            }
            if (graceFuture != null && !graceFuture.isDone()) {
                return; // a grace timer is already counting down
            }
            log.warn("Cluster node {} transport lost — {}ms grace before degrading "
                    + "(local delivery continues meanwhile)", nodeId, grace);
            try {
                graceFuture = reconScheduler.schedule(this::onGraceExpired, grace, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException rej) {
                degradeNow(); // scheduler shutting down — degrade now as a fallback
            }
        }
    }

    /** Grace timer body: degrade only if still ACTIVE (i.e. transport never recovered). */
    private void onGraceExpired() {
        synchronized (graceLock) {
            graceFuture = null;
            if (state.get() == NodeState.ACTIVE) {
                degradeNow();
            }
        }
    }

    /** Performs the ACTIVE→DEGRADED transition (CAS so it fires the state change exactly once). */
    private void degradeNow() {
        if (state.compareAndSet(NodeState.ACTIVE, NodeState.DEGRADED)) {
            log.warn("Cluster node {} degraded — transport lost, local-only mode", nodeId);
            fireStateChange(NodeState.ACTIVE, NodeState.DEGRADED);
        }
    }

    /**
     * Called when transport connectivity is restored. If a grace-period degrade is still pending
     * (node never left ACTIVE), cancels it — no flap. Otherwise runs the DEGRADED→RESYNC→ACTIVE recovery.
     */
    public void onTransportRestored() {
        synchronized (graceLock) {
            if (graceFuture != null && !graceFuture.isDone()) {
                graceFuture.cancel(false);
                graceFuture = null;
                log.info("Cluster node {} transport restored within grace — staying ACTIVE (no flap)", nodeId);
                if (state.get() == NodeState.ACTIVE) {
                    return; // never left ACTIVE — done
                }
                // else: state is DEGRADED (onGraceExpired won the race and degraded before we
                // cancelled, or a prior heartbeat failure already degraded us) → run RESYNC recovery
            }
        }
        if (state.compareAndSet(NodeState.DEGRADED, NodeState.RESYNC)) {
            log.info("Cluster node {} entering RESYNC — rebuilding cluster state", nodeId);
            fireStateChange(NodeState.DEGRADED, NodeState.RESYNC);
            try {
                reconScheduler.schedule(() -> {
                    try {
                        heartbeat.register(nodeId, heartbeatTimeoutMs);
                        transitionTo(NodeState.ACTIVE);
                        log.info("Cluster node {} completed RESYNC → ACTIVE", nodeId);
                    } catch (Exception e) {
                        log.error("Failed to resync node {}, staying DEGRADED", nodeId, e);
                        transitionTo(NodeState.DEGRADED);
                    }
                }, jitter(reconnectJitterMaxMs), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException rej) {
                log.debug("Resync task rejected (scheduler shutting down) for node {}", nodeId);
                transitionTo(NodeState.DEGRADED);
            }
        }
    }

    // ---- Internal ----

    private void doHeartbeat() {
        NodeState currentState = state.get();
        if (currentState == NodeState.LEFT || currentState == NodeState.DRAINING) {
            return;
        }
        // Note: RESYNC state is handled by the scheduled task in onTransportRestored().
        // During RESYNC, heartbeat does nothing — the resync task will re-register and
        // transition to ACTIVE (or back to DEGRADED on failure). This delay is bounded
        // by the reconnect jitter in onTransportRestored and is acceptable.

        try {
            if (currentState == NodeState.DEGRADED) {
                // Retry registration — we may have failed during initial start
                heartbeat.register(nodeId, heartbeatTimeoutMs);
                log.info("Cluster node {} re-registered after DEGRADED state", nodeId);
                onTransportRestored();
            } else {
                heartbeat.renewHeartbeat(nodeId, heartbeatTimeoutMs);
                // A successful renew while a grace-period degrade is pending means the transport
                // recovered (no connection event fired) — cancel the pending degrade. This is an
                // intentionally optimistic, lock-free read of graceFuture: onTransportRestored() is
                // idempotent (its DEGRADED→RESYNC CAS is the real guard), so a stale read here at
                // worst triggers one harmless no-op call — cheaper than locking every heartbeat tick.
                if (graceFuture != null) {
                    onTransportRestored();
                }
            }
        } catch (Exception e) {
            if (currentState == NodeState.ACTIVE) {
                log.warn("Heartbeat renewal failed for node {} — transitioning to DEGRADED", nodeId, e);
                onTransportLost();
            } else {
                log.debug("Heartbeat/registration retry failed for node {} (state={})", nodeId, currentState, e);
            }
        }
    }

    /**
     * Slow-path reconciliation: scan all registered nodes and detect any whose
     * heartbeat has expired but whose NODE_LEFT event was missed (keyspace
     * notifications are fire-and-forget with no replay).
     */
    private void doReconciliation() {
        try {
            List<String> deadNodes = heartbeat.findExpiredNodes(heartbeatTimeoutMs);
            for (String deadNodeId : deadNodes) {
                if (!deadNodeId.equals(nodeId)) {
                    // Leader-election: only the claim winner cleans up, so a dead node detected by
                    // every surviving node in the same sweep is reaped once, not N times.
                    if (!reaper.tryClaim(deadNodeId, nodeId, reconciliationIntervalMs)) {
                        log.debug("Skipping cleanup of dead node {} — another node claimed it", deadNodeId);
                        continue;
                    }
                    log.warn("Reconciliation detected dead node {} — cleaning up sessions + cache", deadNodeId);
                    sessionRegistry.removeAllForNode(deadNodeId);
                    heartbeat.deregister(deadNodeId);
                    // Notify sender to invalidate cached routes to the dead node (I-3)
                    java.util.function.Consumer<String> cb = deadNodeCallback;
                    if (cb != null) {
                        try { cb.accept(deadNodeId); } catch (Exception ex) {
                            log.debug("Dead node callback failed for {}", deadNodeId, ex);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Reconciliation sweep failed for node {}", nodeId, e);
        }
    }

    private void transitionTo(NodeState newState) {
        NodeState prev = state.getAndSet(newState);
        if (prev != newState) {
            fireStateChange(prev, newState);
        }
    }

    private void fireStateChange(NodeState from, NodeState to) {
        for (NodeStateListener listener : listeners) {
            try {
                listener.onStateChange(nodeId, from, to);
            } catch (Exception e) {
                log.warn("NodeStateListener threw exception on {} → {}", from, to, e);
            }
        }
    }

    /** Returns a random jitter between 0 and maxMs (for reconnect storm prevention). */
    private static long jitter(long maxMs) {
        return (long) (Math.random() * maxMs);
    }
}
