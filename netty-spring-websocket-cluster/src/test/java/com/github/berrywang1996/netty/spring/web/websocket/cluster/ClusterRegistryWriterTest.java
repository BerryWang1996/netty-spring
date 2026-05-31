package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for CoalescingRegistryWriter (item ⑤): pass-through under rate, throttle+coalesce over rate, never drop. */
class ClusterRegistryWriterTest {

    /** Counts register/deregister; no-ops the rest. */
    static class RecordingRegistry implements SessionRegistry {
        final AtomicInteger registerCount = new AtomicInteger();
        final AtomicInteger deregisterCount = new AtomicInteger();
        @Override public CompletionStage<Void> register(String uri, String sessionId, String nodeId, Map<String, String> metadata) {
            registerCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> deregister(String uri, String sessionId) {
            deregisterCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<String> lookupNode(String uri, String sessionId) { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Set<String>> clusterSessionIds(String uri) { return CompletableFuture.completedFuture(Collections.emptySet()); }
        @Override public CompletionStage<Void> removeAllForNode(String nodeId) { return CompletableFuture.completedFuture(null); }
        @Override public void shutdown() {}
    }

    @Test
    void passesThroughWhenUnderRate() {
        RecordingRegistry reg = new RecordingRegistry();
        CoalescingRegistryWriter w = new CoalescingRegistryWriter(reg, 1000, 50, "test");
        // no start() → no background flusher; under-rate writes pass straight through
        for (int i = 0; i < 5; i++) w.register("/ws/x", "s" + i, "n", Collections.emptyMap());
        assertEquals(5, reg.registerCount.get(), "under-rate writes pass straight through");
        assertEquals(0, w.pendingCount());
    }

    @Test
    void throttlesOverRateButNeverDropsRegister() {
        RecordingRegistry reg = new RecordingRegistry();
        CoalescingRegistryWriter w = new CoalescingRegistryWriter(reg, 1, 50, "test"); // ~1 immediate token
        int n = 50;
        for (int i = 0; i < n; i++) w.register("/ws/x", "s" + i, "n", Collections.emptyMap()); // distinct
        assertTrue(reg.registerCount.get() < n, "over-rate writes are throttled, not all immediate");
        assertTrue(w.pendingCount() > 0, "excess writes are queued, not dropped");
        w.shutdown(); // final drain ignores the rate — everything lands
        assertEquals(n, reg.registerCount.get(), "every distinct register eventually lands; none dropped");
        assertEquals(0, w.pendingCount());
    }

    @Test
    void coalescesRepeatedOpsOnSameSession() {
        RecordingRegistry reg = new RecordingRegistry();
        CoalescingRegistryWriter w = new CoalescingRegistryWriter(reg, 1, 50, "test");
        w.register("/ws/x", "warmup", "n", Collections.emptyMap()); // consumes the single initial token
        for (int i = 0; i < 20; i++) w.register("/ws/x", "s1", "n", Collections.emptyMap()); // throttled, same key
        w.deregister("/ws/x", "s1");
        assertEquals(1, w.pendingCount(), "20 reg + 1 dereg on one session coalesce to ONE pending op");
        w.shutdown();
        assertEquals(1, reg.registerCount.get(), "only the warmup register hit the registry immediately");
        assertEquals(1, reg.deregisterCount.get(), "the coalesced op for s1 is the final deregister");
    }

    @Test
    void queuedRegisterThenDeregisterDoesNotLeaveStaleRegistration() throws Exception {
        RecordingRegistry reg = new RecordingRegistry();
        CoalescingRegistryWriter w = new CoalescingRegistryWriter(reg, 1, 50, "test"); // rate=1 op/s
        w.register("/ws/x", "warmup", "n", Collections.emptyMap()); // consumes the single initial token
        w.register("/ws/x", "s1", "n", Collections.emptyMap());     // no token → queued REGISTER for s1
        assertEquals(1, w.pendingCount());
        Thread.sleep(1100);                                          // a token refills (rate=1 → 1/sec)
        // s1 already has a queued op → deregister MUST coalesce into the queue, not pass through ahead of it.
        w.deregister("/ws/x", "s1");
        assertEquals(1, w.pendingCount(), "deregister must coalesce with the pending register, not overtake it");
        w.shutdown();
        // Net intent for s1 is deregister → it must NOT end up registered. Only the warmup registered.
        assertEquals(1, reg.registerCount.get(), "s1's queued register must have been coalesced away (no stale registration)");
        assertEquals(1, reg.deregisterCount.get(), "s1's final coalesced op is the deregister");
    }
}
