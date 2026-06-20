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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.NodeState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
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

    /** RC4c BL4: if the broker is already DEGRADED when the sender starts (a mesh tick degraded it before the
     *  transport listener was wired), start() reconciles by delivering onTransportLost to the node manager. */
    @Test
    void start_reconcilesAlreadyDegradedBroker() {
        InMemoryBroker degraded = new InMemoryBroker();
        degraded.setState(BrokerState.DEGRADED);
        ClusterNodeManager nm = new ClusterNodeManager("node-R", 3000, 10000, 15000, 0, new NoOpHeartbeat(), registry);
        nm.setRedisLossGracePeriodMs(0);   // instant degrade so the reconcile is observable without a 5s grace wait
        ClusterMessageSender s = new ClusterMessageSender(localSender, degraded, registry, nm, 5000);
        nm.start();   // JOINING → ACTIVE
        s.start();    // RC4c BL4: broker DEGRADED → reconcile → nm.onTransportLost() → DEGRADED
        try {
            assertEquals(NodeState.DEGRADED, nm.getState(), "sender start reconciles an already-degraded broker");
        } finally {
            s.shutdown();
            nm.shutdown();
        }
    }

    /** RC4b: the sender forwards every local session connect/disconnect to the interest registry (the registry's
     *  atomic op decides the node-set 0↔1); the held broker subscription is independent of interest. */
    @Test
    void interestRegisteredPerSession_subscribeAndUnsubscribeForwarded() {
        RecordingInterest rec = new RecordingInterest();
        clusterSender.setInterestRegistry(rec);

        clusterSender.onLocalUriActive("/ws/a", "s1");   // first session
        clusterSender.onLocalUriActive("/ws/a", "s2");   // second session
        clusterSender.onLocalUriInactive("/ws/a", "s1");
        clusterSender.onLocalUriInactive("/ws/a", "s2");

        assertEquals(List.of("sub /ws/a s1", "sub /ws/a s2", "unsub /ws/a s1", "unsub /ws/a s2"), rec.calls);
    }

    /** Records subscribe/unsubscribe forwarding from the sender; returns empty node-sets. */
    static final class RecordingInterest
            implements com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MeshInterestRegistry {
        final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        public java.util.concurrent.CompletionStage<Void> subscribe(String u, String s, String n) {
            calls.add("sub " + u + " " + s);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        public java.util.concurrent.CompletionStage<Void> unsubscribe(String u, String s, String n) {
            calls.add("unsub " + u + " " + s);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        public java.util.concurrent.CompletionStage<Void> removeAllForNode(String n) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        public java.util.concurrent.CompletionStage<Set<String>> nodesForUri(String u) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Collections.emptySet());
        }
        public void shutdown() { }
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
    void sendMessageShortCircuitsRemoteWhenBrokerDegraded() throws Exception {
        // L6: during the redis-loss-grace-period-ms debounce window, transport is lost
        // (broker.state()==DEGRADED) but the node state machine still says ACTIVE. The
        // unicast hot path must short-circuit remote sessions to "closed" WITHOUT a
        // registry lookup — the broker can't deliver anyway.
        localSender.addUri("/ws/test");

        // Register a remote session in the registry so a lookup *would* succeed
        // if the gate didn't short-circuit.
        registry.register("/ws/test", "session-B1", "node-B", Collections.emptyMap())
                .toCompletableFuture().join();

        int lookupsBefore = registry.getLookupNodeCalls();

        // Degrade the broker but leave nodeManager state == ACTIVE (the debounce window).
        broker.setState(BrokerState.DEGRADED);
        assertEquals(NodeState.ACTIVE, nodeManager.getState(),
                "Node state machine must still be ACTIVE during grace-period debounce");

        // Send to the remote session — must throw MessageSessionClosedException without
        // ever calling registry.lookupNode().
        MessageSessionClosedException ex = assertThrows(MessageSessionClosedException.class,
                () -> clusterSender.sendMessage("/ws/test", new TextMessage("dm"), "session-B1"));
        assertTrue(ex.getSessionIds().contains("session-B1"));

        assertEquals(lookupsBefore, registry.getLookupNodeCalls(),
                "Registry lookupNode must NOT be called when broker is DEGRADED");
    }

    @Test
    void closeSessionShortCircuitsRemoteWhenBrokerDegraded() throws Exception {
        // P1 (RC14): closeSession's remote-unicast path must also short-circuit when the broker
        // is DEGRADED (redis-loss-grace-period-ms debounce window) — the bounded registry lookup
        // is wasted work because the broker can't unicast the CLOSE control envelope anyway.
        localSender.addUri("/ws/test");

        // Register a remote session in the registry so a lookup *would* find it
        // if the gate didn't short-circuit.
        registry.register("/ws/test", "session-B1", "node-B", Collections.emptyMap())
                .toCompletableFuture().join();

        int lookupsBefore = registry.getLookupNodeCalls();

        // Degrade the broker but leave nodeManager state == ACTIVE (the debounce window).
        broker.setState(BrokerState.DEGRADED);
        assertEquals(NodeState.ACTIVE, nodeManager.getState(),
                "Node state machine must still be ACTIVE during grace-period debounce");

        // closeSession must return false (could not close) without ever calling lookupNode().
        boolean closed = clusterSender.closeSession("/ws/test", "session-B1", 1000, "bye");
        assertFalse(closed, "closeSession must report no-op when broker is DEGRADED");
        assertEquals(lookupsBefore, registry.getLookupNodeCalls(),
                "Registry lookupNode must NOT be called when broker is DEGRADED");
    }

    @Test
    void topicMessageShortCircuitsRemoteWhenBrokerDegraded() throws Exception {
        // P1 (RC14): topicMessage's cross-node publish path must also short-circuit when the broker
        // is DEGRADED (redis-loss-grace-period-ms debounce window). Local fan-out still happens; only
        // the broker.publish(...) call is avoided so a doomed publish does not waste a transport round-trip.
        localSender.addUri("/ws/test");

        int publishedBefore = broker.getPublishedEnvelopes().size();

        // Degrade the broker but leave nodeManager state == ACTIVE (the debounce window).
        broker.setState(BrokerState.DEGRADED);
        assertEquals(NodeState.ACTIVE, nodeManager.getState(),
                "Node state machine must still be ACTIVE during grace-period debounce");

        clusterSender.topicMessage("/ws/test", new TextMessage("hello"));

        // Local fan-out must still have happened.
        assertEquals(1, localSender.topicMessageCount.get(),
                "Local fan-out must still occur when broker is DEGRADED");
        // But the broker must NOT have been asked to publish.
        assertEquals(publishedBefore, broker.getPublishedEnvelopes().size(),
                "broker.publish must NOT be called when broker is DEGRADED");
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
