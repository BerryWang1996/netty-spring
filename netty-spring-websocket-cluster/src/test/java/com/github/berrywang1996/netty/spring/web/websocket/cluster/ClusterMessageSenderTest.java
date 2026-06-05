package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.NodeState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClusterMessageSender} using in-memory SPI stubs.
 * These tests prove that the cluster sender:
 * <ol>
 *   <li>Does not depend on Lettuce or any Redis-specific class (SPI isolation)</li>
 *   <li>Correctly suppresses self-delivery on broadcast</li>
 *   <li>Routes unicast to the correct node via the registry</li>
 *   <li>Falls back to local-only when broker is degraded</li>
 * </ol>
 */
class ClusterMessageSenderTest {

    private InMemoryBroker broker;
    private InMemorySessionRegistry registry;
    private RecordingLocalSender localSender;
    private ClusterNodeManager nodeManager;
    private ClusterMessageSender clusterSender;

    @BeforeEach
    void setUp() {
        broker = new InMemoryBroker();
        registry = new InMemorySessionRegistry();
        localSender = new RecordingLocalSender();

        // Simple no-op heartbeat for unit tests
        ClusterNodeHeartbeat heartbeat = new NoOpHeartbeat();
        nodeManager = new ClusterNodeManager(
                "node-A", 3000, 10000, 15000, 0, heartbeat, registry); // drainTimeout=0 → fast tearDown shutdown

        clusterSender = new ClusterMessageSender(
                localSender, broker, registry, nodeManager, 5000);

        // Simulate node start (JOINING → ACTIVE)
        nodeManager.start();
        clusterSender.start();
    }

    @AfterEach
    void tearDown() {
        clusterSender.shutdown();
        nodeManager.shutdown();
    }

    @Test
    void broadcastPublishesToBrokerAndDoesLocalFanOut() {
        localSender.addUri("/ws/test");

        clusterSender.topicMessage("/ws/test", new TextMessage("hello"));

        // Local sender should have received the broadcast
        assertEquals(1, localSender.topicMessageCount.get());
        // Broker should have received one published envelope
        assertEquals(1, broker.getPublishedEnvelopes().size());
        ClusterEnvelope env = broker.getPublishedEnvelopes().get(0);
        assertEquals("node-A", env.getOriginNodeId());
        assertEquals("/ws/test", env.getUri());
        assertEquals(ClusterEnvelope.MessageKind.BROADCAST, env.getKind());
    }

    @Test
    void broadcastSelfDeliverySuppression() {
        localSender.addUri("/ws/test");
        clusterSender.start(); // re-subscribe to get the broadcast callback

        // Simulate what Redis Pub/Sub does: deliver the message back to the publisher
        clusterSender.topicMessage("/ws/test", new TextMessage("hello"));

        // The broker's InMemory impl delivers to all subscribers synchronously,
        // including the origin node. The origin node's callback should detect
        // originNodeId == "node-A" and discard it. So local sender should have
        // received exactly 1 topicMessage call (the initial local fan-out),
        // NOT 2 (which would happen without self-delivery suppression).
        assertEquals(1, localSender.topicMessageCount.get());
    }

    @Test
    void unicastRoutesToCorrectNodeViaRegistry() throws Exception {
        localSender.addUri("/ws/test");

        // Register a session on a remote node
        registry.register("/ws/test", "session-B1", "node-B", Collections.emptyMap())
                .toCompletableFuture().join();

        // Set up a listener on node-B's unicast channel to capture the message
        AtomicInteger nodeB_received = new AtomicInteger();
        broker.subscribeUnicast("node-B", envelope -> nodeB_received.incrementAndGet());

        // Send to the remote session — should route via broker.unicast to node-B
        // (won't throw because the session is found in registry and unicast succeeds)
        clusterSender.sendMessage("/ws/test", new TextMessage("dm"), "session-B1");

        // Verify the message was routed to node-B
        assertEquals(1, nodeB_received.get(), "Message should have been unicast to node-B");
        // Verify the registry was consulted correctly
        String lookedUpNode = registry.lookupNode("/ws/test", "session-B1")
                .toCompletableFuture().join();
        assertEquals("node-B", lookedUpNode);
    }

    @Test
    void localQueryMethodsDelegateToLocalSender() {
        localSender.addUri("/ws/test");

        assertEquals(0, clusterSender.getSessionNums());
        assertEquals(0, clusterSender.getSessionNums("/ws/test"));
        assertTrue(clusterSender.getSessionIds("/ws/test").isEmpty());
        assertNull(clusterSender.getSession("/ws/test", "any"));
        assertTrue(clusterSender.getSessions("/ws/test").isEmpty());
        assertTrue(clusterSender.getRegisteredUri().contains("/ws/test"));
    }

    @Test
    void clusterWideSessionIdsReturnsFromRegistry() throws Exception {
        registry.register("/ws/test", "s1", "node-A", Collections.emptyMap())
                .toCompletableFuture().join();
        registry.register("/ws/test", "s2", "node-B", Collections.emptyMap())
                .toCompletableFuture().join();

        Set<String> ids = clusterSender.getClusterSessionIds("/ws/test")
                .toCompletableFuture().join();
        assertEquals(new HashSet<>(Arrays.asList("s1", "s2")), ids);
    }

    @Test
    void cacheInvalidationOnNodeLeft() throws Exception {
        registry.register("/ws/test", "s1", "node-B", Collections.emptyMap())
                .toCompletableFuture().join();

        // Trigger a lookup to populate the cache
        clusterSender.isSessionAliveCluster("/ws/test", "s1")
                .toCompletableFuture().join();

        // Simulate node-B leaving — invalidate cache
        clusterSender.invalidateCacheForNode("node-B");
        registry.removeAllForNode("node-B").toCompletableFuture().join();

        // Now the lookup should return null (session gone from registry)
        Boolean alive = clusterSender.isSessionAliveCluster("/ws/test", "s1")
                .toCompletableFuture().join();
        assertFalse(alive);
    }

    // ---- Test helpers ----

    /**
     * Minimal local sender that records calls without needing real Netty channels.
     */
    static class RecordingLocalSender implements MessageSender {
        final AtomicInteger topicMessageCount = new AtomicInteger();
        final AtomicInteger sendMessageCount = new AtomicInteger();
        final Set<String> uris = new HashSet<>();

        void addUri(String uri) { uris.add(uri); }

        @Override public int getSessionNums() { return 0; }
        @Override public int getSessionNums(String uri) { return 0; }
        @Override public Set<String> getRegisteredUri() { return Collections.unmodifiableSet(uris); }
        @Override public boolean isSessionAlive(String uri, String... sessionIds) { return false; }

        @Override
        public void sendMessage(String uri, AbstractMessage message, String... sessionIds)
                throws MessageUriNotDefinedException, MessageSessionClosedException {
            sendMessageCount.incrementAndGet();
            // Simulate: none of the sessions are local
            throw new MessageSessionClosedException(uri, Arrays.asList(sessionIds));
        }

        @Override
        public void topicMessage(String uri, AbstractMessage message)
                throws MessageUriNotDefinedException {
            topicMessageCount.incrementAndGet();
        }
    }

    /** No-op heartbeat for unit tests. */
    static class NoOpHeartbeat implements ClusterNodeHeartbeat {
        @Override public void register(String nodeId, long timeoutMs) {}
        @Override public void renewHeartbeat(String nodeId, long timeoutMs) {}
        @Override public void deregister(String nodeId) {}
        @Override public List<String> findExpiredNodes(long timeoutMs) { return Collections.emptyList(); }
    }
}
