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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceChangeListener;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ClusterMessageSender}'s {@code PresenceOperations} with in-memory SPIs: a transition fires the local
 * listener once and publishes a {@code PRESENCE_CHANGE} on the reserved channel; a same-aggregate set is a no-op;
 * an own-origin echo is self-suppressed; a remote event fires the listener; {@code publish-changes=false} suppresses
 * the broadcast but keeps the local listener; and presence is disabled when no registry is wired.
 */
class ClusterPresenceTest {

    private RecordingLocalSender local;
    private InMemoryBroker broker;
    private InMemorySessionRegistry sessionRegistry;
    private InMemoryPresenceRegistry presenceRegistry;
    private ClusterNodeManager nodeManager;
    private ClusterMessageSender sender;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        local = new RecordingLocalSender();
        local.addUri("/ws/chat");
        broker = new InMemoryBroker();
        sessionRegistry = new InMemorySessionRegistry();
        presenceRegistry = new InMemoryPresenceRegistry();
        listener = new RecordingListener();
        nodeManager = new ClusterNodeManager("node-A", 3000, 10000, 15000, 0, new NoOpHeartbeat(), sessionRegistry);
        sender = new ClusterMessageSender(local, broker, sessionRegistry, nodeManager, 5000);
        sender.setPresenceRegistry(presenceRegistry);
        sender.setPresenceChangeListener(listener);
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

    @Test
    void firstSet_firesListenerOnce_publishes_andSelfEchoSuppressed() {
        sender.setPresenceFromHook("u", "node-A", "s1", PresenceStatus.ONLINE).toCompletableFuture().join();

        assertEquals(1, listener.events.size(), "local-first listener fires exactly once (echo self-suppressed)");
        assertEquals("u|OFFLINE|ONLINE", listener.events.get(0));
        assertEquals(1, presenceEvents(), "one PRESENCE_CHANGE published on the reserved channel");
        assertEquals(1, sender.getClusterRuntimeStats().getPresenceChanges());
        assertEquals(1, sender.getClusterRuntimeStats().getPresenceEventsPublished());
        // the broker loops the publish back to this node's own subscription → origin self-suppression
        assertEquals(1, sender.getClusterRuntimeStats().getPresenceSelfDeliveryDropped());
    }

    @Test
    void sameAggregate_isNoOp_noPublishNoListener() {
        sender.setPresenceFromHook("u", "node-A", "s1", PresenceStatus.ONLINE).toCompletableFuture().join();
        sender.setPresenceFromHook("u", "node-A", "s2", PresenceStatus.ONLINE).toCompletableFuture().join();

        assertEquals(1, listener.events.size(), "ONLINE->ONLINE second connection is not a transition");
        assertEquals(1, presenceEvents());
        assertEquals(1, sender.getClusterRuntimeStats().getPresenceChanges());
    }

    @Test
    void remoteEvent_firesListener() {
        ClusterEnvelope remote = new ClusterEnvelope("node-B", ClusterMessageSender.PRESENCE_CHANNEL,
                ClusterEnvelope.MessageKind.PRESENCE_CHANGE, "v|OFFLINE|ONLINE".getBytes(StandardCharsets.UTF_8),
                null, null, 1L);
        broker.publish(ClusterMessageSender.PRESENCE_CHANNEL, remote);

        assertEquals(1, listener.events.size());
        assertEquals("v|OFFLINE|ONLINE", listener.events.get(0));
        assertEquals(1, sender.getClusterRuntimeStats().getPresenceEventsReceived());
    }

    @Test
    void getPresence_delegatesToRegistry() {
        sender.setPresenceFromHook("u", "node-A", "s1", PresenceStatus.ONLINE).toCompletableFuture().join();
        assertEquals(PresenceStatus.ONLINE, sender.getPresence("u").toCompletableFuture().join().getAggregate());
    }

    @Test
    void publishChangesFalse_firesListenerLocally_butDoesNotBroadcast() {
        sender.setPresencePublishChanges(false);
        sender.setPresenceFromHook("w", "node-A", "s9", PresenceStatus.ONLINE).toCompletableFuture().join();

        assertEquals(1, listener.events.size(), "local listener still fires");
        assertEquals(0, presenceEvents(), "publish-changes=false → no broadcast");
    }

    @Test
    void presenceDisabled_throwsIllegalState() {
        ClusterMessageSender noPresence = new ClusterMessageSender(local, broker, sessionRegistry, nodeManager, 5000);
        assertThrows(IllegalStateException.class,
                () -> noPresence.setPresenceForUser("u", PresenceStatus.AWAY));
        assertThrows(IllegalStateException.class, () -> noPresence.getPresence("u"));
    }

    @Test
    void setPresenceForUser_setsAllAndTransitions() {
        sender.setPresenceFromHook("u", "node-A", "s1", PresenceStatus.ONLINE).toCompletableFuture().join();
        listener.events.clear();
        sender.setPresenceForUser("u", PresenceStatus.AWAY).toCompletableFuture().join();
        assertTrue(listener.events.contains("u|ONLINE|AWAY"));
        assertEquals(PresenceStatus.AWAY, sender.getPresence("u").toCompletableFuture().join().getAggregate());
    }

    /** Records (userId|old|new) for each callback. */
    private static final class RecordingListener implements PresenceChangeListener {
        final List<String> events = new ArrayList<>();
        @Override
        public synchronized void onPresenceChange(String userId, PresenceStatus oldAgg, PresenceStatus newAgg) {
            events.add(userId + "|" + oldAgg + "|" + newAgg);
        }
    }

    /** Minimal local sender — presence tests only need getRegisteredUri() (start() reads it). */
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
