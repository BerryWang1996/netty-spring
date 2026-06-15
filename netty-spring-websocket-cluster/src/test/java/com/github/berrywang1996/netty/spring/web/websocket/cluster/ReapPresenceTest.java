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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceStatus;
import com.github.berrywang1996.netty.spring.web.websocket.context.AbstractMessage;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the RC3 dead-node reap (BLOCKER fix + the latent RC2 gap). The sender's reap publishes one OFFLINE event
 * per user whose aggregate dropped (and none for a user still online elsewhere); and the {@link ClusterNodeManager}
 * reconciliation now chains the userRegistry + presence reaps on the SAME leader-elected path as the session reap —
 * proving {@code userRegistry.removeAllForNode(dead)} is finally invoked (pre-RC3 it never was → stale-binding leak).
 */
class ReapPresenceTest {

    private RecordingLocalSender local;
    private InMemoryBroker broker;
    private InMemorySessionRegistry sessionRegistry;
    private InMemoryPresenceRegistry presenceRegistry;
    private ClusterNodeManager nodeManager;
    private ClusterMessageSender sender;

    @BeforeEach
    void setUp() {
        local = new RecordingLocalSender();
        local.addUri("/ws/chat");
        broker = new InMemoryBroker();
        sessionRegistry = new InMemorySessionRegistry();
        presenceRegistry = new InMemoryPresenceRegistry();
        nodeManager = new ClusterNodeManager("node-B", 3000, 10000, 15000, 0, new NoOpHeartbeat(), sessionRegistry);
        sender = new ClusterMessageSender(local, broker, sessionRegistry, nodeManager, 5000);
        sender.setPresenceRegistry(presenceRegistry);
        nodeManager.start();
        sender.start();
    }

    @AfterEach
    void tearDown() {
        sender.shutdown();
        nodeManager.shutdown();
    }

    private long presenceEvents() {
        return broker.getPublishedEnvelopes().stream()
                .filter(e -> e.getKind() == ClusterEnvelope.MessageKind.PRESENCE_CHANGE).count();
    }

    private String lastPresencePayload() {
        List<ClusterEnvelope> all = broker.getPublishedEnvelopes();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).getKind() == ClusterEnvelope.MessageKind.PRESENCE_CHANGE) {
                return new String(all.get(i).getPayload(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** user u's ONLY device was on the dead node → reap publishes u: ONLINE→OFFLINE. */
    @Test
    void reap_lastDeviceOnDeadNode_publishesOffline() {
        presenceRegistry.setPresence("u", "node-A", "s1", PresenceStatus.ONLINE).toCompletableFuture().join();

        sender.reapPresenceForDeadNode("node-A").toCompletableFuture().join();

        assertEquals(1, presenceEvents(), "one OFFLINE event for the user whose last device was on the dead node");
        assertEquals("u|ONLINE|OFFLINE", lastPresencePayload());
        assertEquals(1, sender.getClusterRuntimeStats().getPresenceReapOffline());
        assertEquals(PresenceStatus.OFFLINE,
                presenceRegistry.getPresence("u").toCompletableFuture().join().getAggregate());
    }

    /** user v is still online on another node → reap of the dead node emits NO event for v. */
    @Test
    void reap_userStillOnElsewhere_noEvent() {
        presenceRegistry.setPresence("v", "node-A", "s1", PresenceStatus.ONLINE).toCompletableFuture().join();
        presenceRegistry.setPresence("v", "node-C", "s2", PresenceStatus.ONLINE).toCompletableFuture().join();

        sender.reapPresenceForDeadNode("node-A").toCompletableFuture().join();

        assertEquals(0, presenceEvents(), "v still ONLINE via node-C → no transition, no event");
        assertEquals(PresenceStatus.ONLINE,
                presenceRegistry.getPresence("v").toCompletableFuture().join().getAggregate());
    }

    /**
     * RC2-gap regression: the reconciliation path must invoke {@code userRegistry.removeAllForNode(dead)} AND the
     * presence reaper on the same leader-elected chain as the session reap. Pre-RC3, userRegistry was never reaped.
     */
    @Test
    void reconciliation_chainsUserRegistryAndPresenceReap() throws Exception {
        AtomicReference<String> sessionReaped = new AtomicReference<>();
        AtomicReference<String> userReaped = new AtomicReference<>();
        AtomicReference<String> presenceReaped = new AtomicReference<>();
        CountDownLatch allDone = new CountDownLatch(1);

        InMemorySessionRegistry reg = new InMemorySessionRegistry() {
            @Override
            public CompletionStage<Void> removeAllForNode(String nodeId) {
                sessionReaped.set(nodeId);
                return super.removeAllForNode(nodeId);
            }
        };

        ClusterNodeManager mgr = new ClusterNodeManager(
                "live-r", 600000, 10000, 100, 0, expiredReturning("dead-node"), reg);
        mgr.setReaper((dead, me, win) -> true); // always win the claim
        // The auto-config would wire these; here we assert they ALL fire on the chained path.
        mgr.setUserRegistryReaper(dead -> {
            userReaped.set(dead);
            return CompletableFuture.completedFuture(null);
        });
        mgr.setPresenceReaper(dead -> {
            presenceReaped.set(dead);
            allDone.countDown();
            return CompletableFuture.completedFuture(null);
        });
        mgr.start();

        assertTrue(allDone.await(3, TimeUnit.SECONDS), "the chained reap must complete within 3s");
        mgr.shutdown();

        assertEquals("dead-node", sessionReaped.get(), "session reap ran");
        assertEquals("dead-node", userReaped.get(), "userRegistry reap ran (RC2 gap closed)");
        assertEquals("dead-node", presenceReaped.get(), "presence reap ran");
    }

    private static ClusterNodeHeartbeat expiredReturning(String deadNodeId) {
        return new ClusterNodeHeartbeat() {
            @Override public void register(String nodeId, long timeoutMs) {}
            @Override public void renewHeartbeat(String nodeId, long timeoutMs) {}
            @Override public void deregister(String nodeId) {}
            @Override public List<String> findExpiredNodes(long timeoutMs) { return List.of(deadNodeId); }
        };
    }

    /** Minimal local sender — reap tests only need getRegisteredUri(). */
    static final class RecordingLocalSender implements MessageSender {
        final Set<String> uris = new HashSet<>();
        void addUri(String uri) { uris.add(uri); }
        @Override public int getSessionNums() { return 0; }
        @Override public int getSessionNums(String uri) { return 0; }
        @Override public Set<String> getRegisteredUri() { return Collections.unmodifiableSet(uris); }
        @Override public boolean isSessionAlive(String uri, String... sessionIds) { return true; }
        @Override public MessageSession getSession(String uri, String sessionId) { return null; }
        @Override public void sendMessage(String uri, AbstractMessage message, String... sessionIds)
                throws MessageUriNotDefinedException, MessageSessionClosedException { }
        @Override public void topicMessage(String uri, AbstractMessage message) throws MessageUriNotDefinedException { }
    }

    static final class NoOpHeartbeat implements ClusterNodeHeartbeat {
        @Override public void register(String nodeId, long timeoutMs) { }
        @Override public void renewHeartbeat(String nodeId, long timeoutMs) { }
        @Override public void deregister(String nodeId) { }
        @Override public List<String> findExpiredNodes(long timeoutMs) { return Collections.emptyList(); }
    }
}
