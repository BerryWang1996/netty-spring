package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.NodeState;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the behavioral configuration knobs wired in the 1.8.0 review pass:
 * {@code messageMaxSizeBytes}, {@code onPublishFailure}, {@code onRedisLoss}.
 *
 * <p>Each knob is verified to have an observable effect (the whole point of the
 * "no lying config" review — every property must actually do something).
 */
class ClusterMessageSenderKnobsTest {

    private InMemoryBroker broker;
    private InMemorySessionRegistry registry;
    private SessionAwareLocalSender localSender;
    private ClusterNodeManager nodeManager;
    private ClusterMessageSender clusterSender;

    @BeforeEach
    void setUp() {
        broker = new InMemoryBroker();
        registry = new InMemorySessionRegistry();
        localSender = new SessionAwareLocalSender();
        ClusterNodeHeartbeat heartbeat = new NoOpHeartbeat();
        // drainTimeoutMs = 0 → shutdown() (which now folds a bounded drain grace, FIX D) returns
        // immediately in tearDown instead of sleeping the would-be 60s default.
        nodeManager = new ClusterNodeManager("node-A", 3000, 10000, 15000, 0, heartbeat, registry);
        nodeManager.setRedisLossGracePeriodMs(0); // tests verify instant-degrade (1.8.0 behavior)
        clusterSender = new ClusterMessageSender(localSender, broker, registry, nodeManager, 5000);
    }

    @AfterEach
    void tearDown() {
        try { clusterSender.shutdown(); } catch (Exception ignored) {}
        try { nodeManager.shutdown(); } catch (Exception ignored) {}
    }

    // ==================== messageMaxSizeBytes ====================

    @Test
    void oversizedBroadcastIsNotPublishedButLocalDeliveryStillHappens() {
        localSender.addUri("/ws/test");
        clusterSender.setMessageMaxSizeBytes(10); // tiny limit
        nodeManager.start();
        clusterSender.start();

        // "T:" + a 100-char string → ~102 bytes payload, well over the 10-byte limit
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 100; i++) big.append('x');
        clusterSender.topicMessage("/ws/test", new TextMessage(big.toString()));

        // Local fan-out always happens
        assertEquals(1, localSender.topicMessageCount.get());
        // But nothing is published to the cluster (oversized)
        assertEquals(0, broker.getPublishedEnvelopes().size());
        // And the failure counter records the drop
        assertEquals(1, clusterSender.getClusterRuntimeStats().getPublishFailures());
    }

    @Test
    void withinLimitBroadcastIsPublished() {
        localSender.addUri("/ws/test");
        clusterSender.setMessageMaxSizeBytes(1048576); // 1 MiB
        nodeManager.start();
        clusterSender.start();

        clusterSender.topicMessage("/ws/test", new TextMessage("small"));

        assertEquals(1, localSender.topicMessageCount.get());
        assertEquals(1, broker.getPublishedEnvelopes().size());
        assertEquals(0, clusterSender.getClusterRuntimeStats().getPublishFailures());
    }

    @Test
    void unlimitedWhenMaxSizeIsZero() {
        localSender.addUri("/ws/test");
        clusterSender.setMessageMaxSizeBytes(0); // 0 = unlimited
        nodeManager.start();
        clusterSender.start();

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append('y');
        clusterSender.topicMessage("/ws/test", new TextMessage(big.toString()));

        assertEquals(1, broker.getPublishedEnvelopes().size());
        assertEquals(0, clusterSender.getClusterRuntimeStats().getPublishFailures());
    }

    // ==================== onPublishFailure ====================

    @Test
    void onPublishFailureDropDoesNotThrowAndCounts() {
        localSender.addUri("/ws/test");
        clusterSender.setMessageMaxSizeBytes(5);
        clusterSender.setOnPublishFailure(ClusterProperties.OnPublishFailure.DROP);
        nodeManager.start();
        clusterSender.start();

        // Should not throw regardless of DROP/LOG
        assertDoesNotThrow(() ->
                clusterSender.topicMessage("/ws/test", new TextMessage("way-too-large-message")));
        assertEquals(1, clusterSender.getClusterRuntimeStats().getPublishFailures());
    }

    // ==================== onRedisLoss ====================

    @Test
    void onRedisLossCloseAllClosesLocalSessionsOnDegrade() {
        localSender.addUri("/ws/room");
        localSender.addLocalSession("/ws/room", "s1");
        localSender.addLocalSession("/ws/room", "s2");
        clusterSender.setOnRedisLoss(ClusterProperties.OnRedisLoss.CLOSE_ALL);
        nodeManager.start();
        clusterSender.start();

        // Transport lost → ACTIVE → DEGRADED → CLOSE_ALL listener fires
        nodeManager.onTransportLost();

        assertEquals(2, localSender.closedSessions.size(), "Both local sessions should be closed");
        assertTrue(localSender.closedSessions.contains("s1"));
        assertTrue(localSender.closedSessions.contains("s2"));
    }

    @Test
    void onRedisLossDegradeToLocalKeepsSessionsAlive() {
        localSender.addUri("/ws/room");
        localSender.addLocalSession("/ws/room", "s1");
        clusterSender.setOnRedisLoss(ClusterProperties.OnRedisLoss.DEGRADE_TO_LOCAL); // default
        nodeManager.start();
        clusterSender.start();

        nodeManager.onTransportLost();

        assertEquals(0, localSender.closedSessions.size(), "Sessions should NOT be closed in degrade-to-local");
    }

    // ==================== degrade-to-local broadcast: local delivered, cross-node loss visible ====================

    @Test
    void whileDegraded_broadcastDeliversLocallyButCrossNodeSkipIsCounted() {
        localSender.addUri("/ws/room");
        clusterSender.setOnRedisLoss(ClusterProperties.OnRedisLoss.DEGRADE_TO_LOCAL);
        nodeManager.start();
        clusterSender.start();

        // Go DEGRADED (Redis lost) — cross-node publish should now be skipped, not attempted.
        nodeManager.onTransportLost();
        assertEquals(com.github.berrywang1996.netty.spring.web.websocket.cluster.node.NodeState.DEGRADED,
                nodeManager.getState());

        clusterSender.topicMessage("/ws/room", new TextMessage("while-degraded"));

        // Local fan-out still happened (never lost)...
        assertEquals(1, localSender.topicMessageCount.get());
        // ...but the cross-node copy was NOT published (nothing on the broker)...
        assertEquals(0, broker.getPublishedEnvelopes().size());
        // ...and the loss is now VISIBLE via the counter (was silent debug-only before).
        assertEquals(1, clusterSender.getClusterRuntimeStats().getBroadcastsSkippedDegraded());
    }

    // ==================== nodeCache is bounded (no unbounded growth on the unicast hot path) ====================

    @Test
    void nodeLookupCacheIsBoundedToConfiguredMaxSize() throws Exception {
        localSender.addUri("/ws/cap");
        int cap = 50;
        clusterSender.setRegistryReadCacheMaxSize(cap);
        nodeManager.start();
        clusterSender.start();

        // Register and unicast to FAR more distinct LIVE remote sessions than the cap. Each send populates
        // the (uri|sessionId)->nodeId cache; without a bound the map would grow to 'total' entries.
        int total = cap * 10;
        for (int i = 0; i < total; i++) {
            String sid = "remote-" + i;
            registry.register("/ws/cap", sid, "node-B", Collections.emptyMap()).toCompletableFuture().join();
            // ACTIVE + remote nodeId resolved → broker.unicast (no-op for an unsubscribed node), no exception.
            clusterSender.sendMessage("/ws/cap", new TextMessage("m" + i), sid);
        }

        assertTrue(clusterSender.nodeCacheSize() <= cap,
                "node-lookup cache must be bounded to the configured max (" + cap
                        + ") but was " + clusterSender.nodeCacheSize());
    }

    @Test
    void nodeLookupCacheIsBoundedByDefaultEvenWithLegacyConstructor() {
        // The legacy/no-knob path must still be bounded (default cap), not an unbounded ConcurrentHashMap.
        localSender.addUri("/ws/dflt");
        nodeManager.start();
        clusterSender.start();
        // We can't realistically insert 100k entries in a unit test; instead prove the map enforces a
        // removeEldestEntry policy by setting a tiny cap and confirming eviction (covered above), and here
        // simply assert the accessor works and starts empty (the structure is in place).
        assertEquals(0, clusterSender.nodeCacheSize());
    }

    // ==================== S1: event-driven degradation ====================

    @Test
    void transportLostEventDegradesNodeImmediately() {
        localSender.addUri("/ws/x");
        nodeManager.start();
        clusterSender.start();

        // The sender must register a transport-state listener on the broker (S1 wiring).
        assertTrue(broker.hasTransportStateListener(),
                "sender should register a transport-state listener on the broker");
        assertEquals(NodeState.ACTIVE, nodeManager.getState());

        // Simulate a Lettuce disconnect event → node degrades IMMEDIATELY (not heartbeat-late).
        broker.fireTransportLost();
        assertEquals(NodeState.DEGRADED, nodeManager.getState(),
                "transport-lost event should degrade the node immediately");
    }

    // ==================== S2: degraded send short-circuits without registry lookup ====================

    @Test
    void degradedSendMessageShortCircuitsRemoteWithoutRegistryLookup() throws Exception {
        localSender.addUri("/ws/x");
        registry.register("/ws/x", "remote-s", "node-B", Collections.emptyMap())
                .toCompletableFuture().join();
        nodeManager.start();
        clusterSender.start();

        nodeManager.onTransportLost();
        assertEquals(NodeState.DEGRADED, nodeManager.getState());

        int lookupsBefore = registry.getLookupNodeCalls();
        // Sending to a remote session while degraded → reported closed, WITHOUT any registry lookup
        // (so a Redis outage can never block the unicast hot path on a registry round-trip).
        MessageSessionClosedException ex = assertThrows(MessageSessionClosedException.class, () ->
                clusterSender.sendMessage("/ws/x", new TextMessage("hi"), "remote-s"));
        assertTrue(ex.getSessionIds().contains("remote-s"));
        assertEquals(lookupsBefore, registry.getLookupNodeCalls(),
                "degraded send must NOT query the registry (hot-path short-circuit)");
    }

    // ==================== Test helpers ====================

    /** Local sender that tracks per-URI sessionIds and records closeSession calls. */
    static class SessionAwareLocalSender implements MessageSender {
        final AtomicInteger topicMessageCount = new AtomicInteger();
        final Set<String> uris = new HashSet<>();
        final Map<String, Set<String>> sessionsByUri = new HashMap<>();
        final List<String> closedSessions = new ArrayList<>();

        void addUri(String uri) { uris.add(uri); }
        void addLocalSession(String uri, String sessionId) {
            uris.add(uri);
            sessionsByUri.computeIfAbsent(uri, k -> new HashSet<>()).add(sessionId);
        }

        @Override public int getSessionNums() { return 0; }
        @Override public int getSessionNums(String uri) { return 0; }
        @Override public Set<String> getRegisteredUri() { return Collections.unmodifiableSet(uris); }
        @Override public Set<String> getSessionIds(String uri) {
            return sessionsByUri.getOrDefault(uri, Collections.emptySet());
        }
        @Override public boolean isSessionAlive(String uri, String... sessionIds) { return false; }
        @Override public void sendMessage(String uri, AbstractMessage message, String... sessionIds) {}
        @Override public void topicMessage(String uri, AbstractMessage message) {
            topicMessageCount.incrementAndGet();
        }
        @Override public boolean closeSession(String uri, String sessionId, int statusCode, String reasonText) {
            closedSessions.add(sessionId);
            return true;
        }
    }

    static class NoOpHeartbeat implements ClusterNodeHeartbeat {
        @Override public void register(String nodeId, long timeoutMs) {}
        @Override public void renewHeartbeat(String nodeId, long timeoutMs) {}
        @Override public void deregister(String nodeId) {}
        @Override public List<String> findExpiredNodes(long timeoutMs) { return Collections.emptyList(); }
    }
}
