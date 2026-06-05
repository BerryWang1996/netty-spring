package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.NodeState;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Reliability hardening tests (1.9.0): scheduler isolation (②), grace period (①), reaper gating (④). */
class ClusterNodeManagerReliabilityTest {

    // ---- helpers ----

    /** Heartbeat stub whose findExpiredNodes BLOCKS, to prove it can't starve heartbeat renewal. */
    private static ClusterNodeHeartbeat blockingReconHeartbeat(AtomicInteger renews, CountDownLatch reconEntered) {
        return new ClusterNodeHeartbeat() {
            @Override public void register(String nodeId, long timeoutMs) {}
            @Override public void renewHeartbeat(String nodeId, long timeoutMs) { renews.incrementAndGet(); }
            @Override public void deregister(String nodeId) {}
            @Override public List<String> findExpiredNodes(long timeoutMs) {
                reconEntered.countDown();
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return Collections.emptyList();
            }
        };
    }

    @Test
    void slowReconciliationDoesNotStarveHeartbeat() throws Exception {
        AtomicInteger renews = new AtomicInteger();
        CountDownLatch reconEntered = new CountDownLatch(1);
        // heartbeat every 200ms, reconciliation every 200ms (which then blocks 2s inside findExpiredNodes)
        ClusterNodeManager mgr = new ClusterNodeManager(
                "iso-node", 200, 10000, 200, 0,
                blockingReconHeartbeat(renews, reconEntered), new InMemorySessionRegistry());
        mgr.start();
        assertTrue(reconEntered.await(2, TimeUnit.SECONDS), "reconciliation should run and block");
        Thread.sleep(1000); // during the blocked sweep, heartbeat must keep ticking on its own thread
        mgr.shutdown();
        // >= 2 cleanly separates the fixed case from the broken single-scheduler case (which gets 0
        // ticks during the 2s block); kept low so it's robust on a slow/loaded CI box.
        assertTrue(renews.get() >= 2,
                "heartbeat must keep renewing while reconciliation is blocked (two schedulers); got " + renews.get());
    }

    private static ClusterNodeHeartbeat noOpHeartbeat() {
        return new ClusterNodeHeartbeat() {
            @Override public void register(String nodeId, long timeoutMs) {}
            @Override public void renewHeartbeat(String nodeId, long timeoutMs) {}
            @Override public void deregister(String nodeId) {}
            @Override public List<String> findExpiredNodes(long timeoutMs) { return Collections.emptyList(); }
        };
    }

    /** Manager with a long heartbeat/recon interval so only the explicit transport events drive state. */
    private static ClusterNodeManager graceManager(long graceMs) {
        ClusterNodeManager mgr = new ClusterNodeManager(
                "grace-node", 60000, 600000, 60000, 0, noOpHeartbeat(), new InMemorySessionRegistry());
        mgr.setRedisLossGracePeriodMs(graceMs);
        return mgr;
    }

    @Test
    void transportLostWithinGraceDoesNotDegrade() throws Exception {
        ClusterNodeManager mgr = graceManager(500);
        mgr.start();
        assertEquals(NodeState.ACTIVE, mgr.getState());
        mgr.onTransportLost();                  // starts the grace countdown, does NOT degrade
        Thread.sleep(150);
        assertEquals(NodeState.ACTIVE, mgr.getState(), "must not degrade during the grace window");
        mgr.onTransportRestored();              // recovery within grace cancels the countdown
        Thread.sleep(600);                      // past where the grace timer would have fired
        assertEquals(NodeState.ACTIVE, mgr.getState(), "recovered within grace — no flap, stays ACTIVE");
        mgr.shutdown();
    }

    @Test
    void transportLostBeyondGraceDegrades() throws Exception {
        ClusterNodeManager mgr = graceManager(400);
        mgr.start();
        mgr.onTransportLost();
        // grace fires after 400ms → DEGRADED; poll up to 3s so a loaded CI box can't flake.
        long deadline = System.currentTimeMillis() + 3000;
        while (mgr.getState() != NodeState.DEGRADED && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(NodeState.DEGRADED, mgr.getState(), "no recovery → degrade after the grace period");
        mgr.shutdown();
    }

    @Test
    void zeroGraceDegradesInstantly() {
        ClusterNodeManager mgr = graceManager(0);
        mgr.start();
        mgr.onTransportLost();
        assertEquals(NodeState.DEGRADED, mgr.getState(), "grace=0 preserves 1.8.0 instant-degrade");
        mgr.shutdown();
    }

    // ---- FIX C: a pending RESYNC must not resurrect a LEFT node after shutdown ----

    /** Heartbeat whose register() counts how many times the node (re-)registers — to prove a cancelled
     *  RESYNC never re-registers after shutdown. */
    private static final class CountingHeartbeat implements ClusterNodeHeartbeat {
        final AtomicInteger registers = new AtomicInteger();
        @Override public void register(String nodeId, long timeoutMs) { registers.incrementAndGet(); }
        @Override public void renewHeartbeat(String nodeId, long timeoutMs) {}
        @Override public void deregister(String nodeId) {}
        @Override public List<String> findExpiredNodes(long timeoutMs) { return Collections.emptyList(); }
    }

    @Test
    void shutdownCancelsPendingResyncSoNodeStaysLeft() throws Exception {
        CountingHeartbeat hb = new CountingHeartbeat();
        // Long heartbeat/recon so only explicit events drive state; grace=0 → instant degrade.
        ClusterNodeManager mgr = new ClusterNodeManager(
                "resync-node", 600000, 600000, 600000, 0, hb, new InMemorySessionRegistry());
        mgr.setRedisLossGracePeriodMs(0);
        // Large reconnect jitter so the RESYNC re-register task is scheduled FAR in the future and is
        // still pending when we shut down — shutdown() must cancel it.
        mgr.setReconnectJitterMaxMs(60000);
        mgr.start();
        int registersAfterStart = hb.registers.get(); // 1 (initial)

        mgr.onTransportLost();                          // ACTIVE → DEGRADED (grace=0)
        assertEquals(NodeState.DEGRADED, mgr.getState());
        mgr.onTransportRestored();                      // DEGRADED → RESYNC, schedules a delayed re-register
        assertEquals(NodeState.RESYNC, mgr.getState());

        mgr.shutdown();                                  // must cancel the pending RESYNC + go terminal LEFT
        assertEquals(NodeState.LEFT, mgr.getState());

        // Give the (cancelled) RESYNC task ample time to NOT fire.
        Thread.sleep(300);
        assertEquals(NodeState.LEFT, mgr.getState(), "a pending RESYNC must not resurrect a LEFT node");
        assertEquals(registersAfterStart, hb.registers.get(),
                "the cancelled RESYNC must not re-register the node after shutdown");
    }

    @Test
    void transitionToCannotLeaveTerminalLeftState() {
        // Directly assert the terminal-LEFT guard: once LEFT (via shutdown), drain()/any transition no-ops.
        ClusterNodeManager mgr = new ClusterNodeManager(
                "terminal-node", 600000, 600000, 600000, 0, noOpHeartbeat(), new InMemorySessionRegistry());
        mgr.start();
        mgr.shutdown();
        assertEquals(NodeState.LEFT, mgr.getState());
        // A late drain() (public entry into a transition) must be ignored — node stays LEFT.
        mgr.drain();
        assertEquals(NodeState.LEFT, mgr.getState(), "LEFT is terminal — drain() must not move it");
    }

    // ---- FIX D: shutdown folds a bounded drain, transitioning DRAINING → LEFT ----

    @Test
    void shutdownTransitionsThroughDrainingToLeft() {
        List<NodeState> seen = Collections.synchronizedList(new java.util.ArrayList<>());
        ClusterNodeManager mgr = new ClusterNodeManager(
                "drain-node", 600000, 600000, 600000, 50, noOpHeartbeat(), new InMemorySessionRegistry());
        mgr.addStateListener((node, from, to) -> seen.add(to));
        mgr.start();
        assertEquals(NodeState.ACTIVE, mgr.getState());

        long t0 = System.currentTimeMillis();
        mgr.shutdown();
        long elapsed = System.currentTimeMillis() - t0;

        assertEquals(NodeState.LEFT, mgr.getState());
        // The graceful shutdown must have passed THROUGH DRAINING (FIX D folds drain into shutdown).
        assertTrue(seen.contains(NodeState.DRAINING),
                "shutdown must transition through DRAINING; observed " + seen);
        assertTrue(seen.indexOf(NodeState.DRAINING) < seen.indexOf(NodeState.LEFT),
                "DRAINING must precede LEFT; observed " + seen);
        // drainTimeoutMs=50 was honored (a bounded grace was actually waited).
        assertTrue(elapsed >= 50, "drainTimeoutMs must be honored on shutdown (waited " + elapsed + "ms)");
    }

    @Test
    void shutdownWithZeroDrainTimeoutStillReachesLeftViaDraining() {
        List<NodeState> seen = Collections.synchronizedList(new java.util.ArrayList<>());
        ClusterNodeManager mgr = new ClusterNodeManager(
                "drain0-node", 600000, 600000, 600000, 0, noOpHeartbeat(), new InMemorySessionRegistry());
        mgr.addStateListener((node, from, to) -> seen.add(to));
        mgr.start();
        mgr.shutdown();
        assertEquals(NodeState.LEFT, mgr.getState());
        assertTrue(seen.contains(NodeState.DRAINING) && seen.contains(NodeState.LEFT),
                "even with drainTimeout=0 the node transitions ACTIVE→DRAINING→LEFT; observed " + seen);
    }

    // ---- reaper gating helpers + tests ----

    private static ClusterNodeHeartbeat expiredReturning(String deadNodeId) {
        return new ClusterNodeHeartbeat() {
            @Override public void register(String nodeId, long timeoutMs) {}
            @Override public void renewHeartbeat(String nodeId, long timeoutMs) {}
            @Override public void deregister(String nodeId) {}
            @Override public List<String> findExpiredNodes(long timeoutMs) { return List.of(deadNodeId); }
        };
    }

    @Test
    void reconciliationSkipsCleanupWhenReapClaimLost() throws Exception {
        InMemorySessionRegistry reg = new InMemorySessionRegistry();
        reg.register("/ws/x", "s-dead", "dead-1", Collections.emptyMap()).toCompletableFuture().join();
        ClusterNodeManager mgr = new ClusterNodeManager(
                "live-1", 600000, 10000, 200, 0, expiredReturning("dead-1"), reg);
        mgr.setReaper((dead, me, win) -> false); // claim always lost
        mgr.start();
        Thread.sleep(600); // a few reconciliation cycles @200ms
        mgr.shutdown();
        assertEquals("dead-1", reg.lookupNode("/ws/x", "s-dead").toCompletableFuture().join(),
                "cleanup must be skipped when the reap claim is lost");
    }

    @Test
    void reconciliationCleansUpWhenReapClaimWon() throws Exception {
        InMemorySessionRegistry reg = new InMemorySessionRegistry();
        reg.register("/ws/x", "s-dead", "dead-2", Collections.emptyMap()).toCompletableFuture().join();
        ClusterNodeManager mgr = new ClusterNodeManager(
                "live-2", 600000, 10000, 200, 0, expiredReturning("dead-2"), reg);
        mgr.setReaper((dead, me, win) -> true);
        mgr.start();
        Thread.sleep(600);
        mgr.shutdown();
        assertNull(reg.lookupNode("/ws/x", "s-dead").toCompletableFuture().join(),
                "cleanup must run when the reap claim is won");
    }
}
