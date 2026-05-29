package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
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
        nodeManager = new ClusterNodeManager("node-A", 3000, 10000, 15000, 60000, heartbeat, registry);
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
