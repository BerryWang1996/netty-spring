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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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

    /**
     * Reaper for the {@code userRegistry} reverse-index on the leader-elected primary path (RC3 — closes the latent
     * RC2 gap where a crashed node's {@code netty:user:*} bindings leaked forever). Null = not wired (no offline /
     * presence identity). Chained AFTER {@code sessionRegistry.removeAllForNode} on the SAME retried path, so a
     * failure re-queues the dead node for the next sweep (it is NOT the exception-swallowing deadNodeCallback).
     */
    private volatile Function<String, CompletionStage<Void>> userRegistryReaper;

    /**
     * Reaper for the presence index on the leader-elected primary path (RC3 BLOCKER fix). Removes a crashed node's
     * connections from every user's presence AND publishes the resulting OFFLINE/AWAY transitions — the authoritative
     * crash-path OFFLINE event. Null = presence disabled. Chained on the same retried path as the session reap.
     */
    private volatile Function<String, CompletionStage<Void>> presenceReaper;

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
    /** Pending DEGRADED→RESYNC re-register task (scheduled with jitter). Stored so {@link #shutdown()}
     *  can cancel it — otherwise a late RESYNC after shutdown could re-register a terminal-LEFT node.
     *  Mutated under {@link #lifecycleLock}. */
    private ScheduledFuture<?> resyncFuture;
    /** Guards the future fields (heartbeat/recon/resync) so shutdown's cancellation and the
     *  RESYNC-scheduling path don't race on them. */
    private final Object lifecycleLock = new Object();

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
     * Sets the leader-path reaper for the {@code userRegistry} reverse-index (RC3 — closes the RC2 stale-binding
     * leak). Wired by auto-config to {@code userRegistry::removeAllForNode} whenever a {@code UserRegistry} bean
     * exists (independent of presence). Null = not wired.
     */
    public void setUserRegistryReaper(Function<String, CompletionStage<Void>> userRegistryReaper) {
        this.userRegistryReaper = userRegistryReaper;
    }

    /**
     * Sets the leader-path presence reaper (RC3 BLOCKER fix). Wired by auto-config to
     * {@code sender::reapPresenceForDeadNode}, which removes the dead node's presence connections AND publishes the
     * resulting OFFLINE/AWAY events. Null = presence disabled.
     */
    public void setPresenceReaper(Function<String, CompletionStage<Void>> presenceReaper) {
        this.presenceReaper = presenceReaper;
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
     * Marks this node as DRAINING (a no-op if already LEFT or DRAINING). This only flips the state +
     * fires the listener — the bounded {@code drainTimeoutMs} wait is performed by {@link #shutdown()}
     * (the bean's destroyMethod), which folds drain into graceful shutdown. Call this if you want to
     * begin shedding cross-node traffic ahead of the actual shutdown; otherwise {@code shutdown()}
     * transitions through DRAINING for you.
     */
    public void drain() {
        NodeState current = state.get();
        if (current == NodeState.LEFT || current == NodeState.DRAINING) {
            return;
        }
        transitionTo(NodeState.DRAINING);
        log.info("Cluster node {} entering DRAINING state (timeout={}ms honored on shutdown)",
                nodeId, drainTimeoutMs);
    }

    /**
     * Gracefully shuts this node down. Honors {@code drainTimeoutMs}: a live node (JOINING/ACTIVE/
     * DEGRADED/RESYNC) is first transitioned to DRAINING and given a bounded grace window
     * (up to {@code drainTimeoutMs}) for in-flight cross-node traffic to settle, then it is
     * deregistered from the cluster and moved to the terminal LEFT state. Idempotent: a second call
     * (already LEFT) returns immediately. Once LEFT, no later task (e.g. a pending RESYNC) can
     * resurrect the node — see {@link #transitionTo}.
     */
    public void shutdown() {
        // First, take this node out of ACTIVE/JOINING into DRAINING (unless already terminal). Use a
        // CAS guard so a concurrent second shutdown() or a racing transition can't double-drain.
        NodeState before = state.get();
        if (before == NodeState.LEFT) {
            return;
        }

        // Cancel any pending grace-period degrade + RESYNC re-register so neither can fire during/after
        // shutdown (RESYNC would otherwise re-register a node we are tearing down).
        synchronized (graceLock) {
            if (graceFuture != null) {
                graceFuture.cancel(false);
                graceFuture = null;
            }
        }
        synchronized (lifecycleLock) {
            if (resyncFuture != null) {
                resyncFuture.cancel(false);
                resyncFuture = null;
            }
        }

        // Stop heartbeat renewal first so we don't keep advertising liveness while draining.
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
        if (reconciliationFuture != null) {
            reconciliationFuture.cancel(false);
        }

        // Bounded drain: flip to DRAINING and wait up to drainTimeoutMs. The manager has no local
        // session count, so this is a bounded grace window (not a busy session-drain) — it gives
        // in-flight cross-node deliveries time to settle before we deregister. Skipped if already
        // DRAINING/terminal or if the timeout is non-positive.
        if (before != NodeState.DRAINING) {
            transitionTo(NodeState.DRAINING);
        }
        long drainMs = drainTimeoutMs;
        if (drainMs > 0 && state.get() == NodeState.DRAINING) {
            log.info("Cluster node {} draining — waiting up to {}ms before deregister", nodeId, drainMs);
            try {
                Thread.sleep(drainMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.debug("Drain wait interrupted for node {} — proceeding to deregister", nodeId);
            }
        }

        // Terminal transition: atomically claim LEFT. If another thread beat us to it, bail.
        NodeState prev = state.getAndSet(NodeState.LEFT);
        if (prev == NodeState.LEFT) {
            return;
        }

        // FIX L7: await scheduler termination BEFORE deregister so any in-flight reconciliation
        // (which may chain dead-node cleanup via thenRunAsync(reconScheduler)) has settled.
        // Mirrors CoalescingRegistryWriter.shutdown pattern: bounded 5s, then shutdownNow as a
        // last resort. This guarantees L4's chained dead-node callback runs to completion before
        // this node deregisters itself — otherwise the chained block could race with the local
        // deregister and observe a half-shut-down transport.
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
        }
        if (reconScheduler != null) {
            reconScheduler.shutdown();
        }
        try {
            if (heartbeatScheduler != null
                    && !heartbeatScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("heartbeatScheduler did not terminate within 5s — forcing shutdownNow");
                heartbeatScheduler.shutdownNow();
            }
            if (reconScheduler != null
                    && !reconScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("reconScheduler did not terminate within 5s — forcing shutdownNow");
                reconScheduler.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (heartbeatScheduler != null) {
                heartbeatScheduler.shutdownNow();
            }
            if (reconScheduler != null) {
                reconScheduler.shutdownNow();
            }
        }

        try {
            heartbeat.deregister(nodeId);
        } catch (Exception ex) {
            log.warn("deregister on shutdown failed for {}", nodeId, ex);
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
            synchronized (lifecycleLock) {
                if (state.get() == NodeState.LEFT) {
                    // shutdown() raced in between the CAS above and here — do not schedule a resync.
                    return;
                }
                try {
                    // Store the future so shutdown() can cancel it — a late RESYNC must never re-register
                    // a node that has already LEFT. The task itself also re-checks via transitionTo()'s
                    // terminal-LEFT guard (belt and suspenders).
                    resyncFuture = reconScheduler.schedule(() -> {
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
                    final String dead = deadNodeId; // effectively final for the lambda
                    // FIX L4: chain deregister + cache-callback AFTER session cleanup completes, on
                    // reconScheduler so shutdown's awaitTermination (FIX L7) covers the tail. The LEFT
                    // guard prevents a shutdown-race from poking the transport from a dead local node.
                    // RC3: on the SAME leader-elected + retried path, also reap the userRegistry reverse-index
                    // (closes the RC2 stale-binding leak) and the presence index (which publishes the crash-path
                    // OFFLINE events). Both are chained BEFORE the deregister so a failure in either re-queues the
                    // dead node via the shared .exceptionally — they are NOT on the exception-swallowing callback.
                    try {
                        sessionRegistry.removeAllForNode(dead)
                                .thenComposeAsync(v -> reapUserRegistry(dead), reconScheduler)
                                .thenComposeAsync(v -> reapPresence(dead), reconScheduler)
                                .thenRunAsync(() -> {
                                    if (state.get() == NodeState.LEFT) {
                                        log.debug("Skipping deregister of dead node {} — local node LEFT", dead);
                                        return;
                                    }
                                    try {
                                        heartbeat.deregister(dead);
                                    } catch (Exception ex) {
                                        log.warn("Failed to deregister dead node {}", dead, ex);
                                    }
                                    // Notify sender to invalidate cached routes to the dead node (I-3)
                                    java.util.function.Consumer<String> cb = deadNodeCallback;
                                    if (cb != null) {
                                        try { cb.accept(dead); } catch (Exception ex) {
                                            log.debug("Dead node callback failed for {}", dead, ex);
                                        }
                                    }
                                }, reconScheduler)
                                .exceptionally(ex -> {
                                    // Cleanup failed (session/user/presence reap or deregister) → leave the dead
                                    // node in the nodes set so the next sweep retries it (we don't deregister it
                                    // prematurely, and the stale bindings/presence get another reap attempt).
                                    log.warn("Failed to clean up dead node {} — will retry on next sweep", dead, ex);
                                    return null;
                                });
                    } catch (java.util.concurrent.RejectedExecutionException rex) {
                        // reconScheduler already shutting down — drop silently; next instance retries.
                        log.debug("Could not schedule dead-node cleanup for {} — scheduler shutting down", dead, rex);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Reconciliation sweep failed for node {}", nodeId, e);
        }
    }

    /** Invokes the userRegistry reaper if wired (RC3); a completed stage when not (no-op, preserves the chain). */
    private CompletionStage<Void> reapUserRegistry(String dead) {
        Function<String, CompletionStage<Void>> r = this.userRegistryReaper;
        return r != null ? r.apply(dead) : CompletableFuture.completedFuture(null);
    }

    /** Invokes the presence reaper if wired (RC3 — also publishes the crash-path OFFLINE events); else no-op. */
    private CompletionStage<Void> reapPresence(String dead) {
        Function<String, CompletionStage<Void>> r = this.presenceReaper;
        return r != null ? r.apply(dead) : CompletableFuture.completedFuture(null);
    }

    /**
     * Transitions to {@code newState}, but NEVER leaves the terminal {@link NodeState#LEFT} state:
     * once a node has shut down (LEFT), a late RESYNC re-register task (or any other transition) must
     * not resurrect it. Implemented as a CAS loop so a transition racing with {@link #shutdown()}'s
     * {@code getAndSet(LEFT)} is a no-op (lost-update prevention on the state AtomicReference).
     */
    private void transitionTo(NodeState newState) {
        for (;;) {
            NodeState prev = state.get();
            if (prev == NodeState.LEFT) {
                // Terminal — refuse to leave LEFT (e.g. a RESYNC task that fired after shutdown).
                return;
            }
            if (state.compareAndSet(prev, newState)) {
                if (prev != newState) {
                    fireStateChange(prev, newState);
                }
                return;
            }
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
