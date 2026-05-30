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
                "iso-node", 200, 10000, 200, 60000,
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
                "grace-node", 60000, 600000, 60000, 60000, noOpHeartbeat(), new InMemorySessionRegistry());
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
        Thread.sleep(900);                      // exceed the grace window with no recovery
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
}
