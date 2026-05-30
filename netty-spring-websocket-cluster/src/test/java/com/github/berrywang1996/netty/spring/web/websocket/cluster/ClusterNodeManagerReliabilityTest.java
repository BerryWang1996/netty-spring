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
}
