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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterRoomRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link ClusterMessageSender}'s room path with in-memory SPI stubs (no Lettuce). Pins the headline
 * reduction property: {@code roomMessage} targets ONLY the nodes in the room's node-set (not all N nodes),
 * does local fan-out first, suppresses the origin, and counts a stale-target when a targeted node hosts no
 * members. Uses a shared in-memory room store with a per-node local-index view to model the Redis design
 * (shared node-set, per-node local index).
 */
class ClusterRoomSenderTest {

    private static final String URI = "/ws/chat";

    private SharedRoomStore store;
    private InMemoryBroker broker;     // shared transport so unicast routes across the "nodes"
    private InMemorySessionRegistry sessionRegistry;

    private Node nodeA;
    private Node nodeB;
    private Node nodeC;

    @BeforeEach
    void setUp() {
        store = new SharedRoomStore();
        broker = new InMemoryBroker();
        sessionRegistry = new InMemorySessionRegistry();
        nodeA = new Node("node-A");
        nodeB = new Node("node-B");
        nodeC = new Node("node-C");
    }

    @AfterEach
    void tearDown() {
        nodeA.shutdown();
        nodeB.shutdown();
        nodeC.shutdown();
    }

    @Test
    void roomMessageTargetsOnlyNodeSet_localFanOutHappens_thirdNodeNotTargeted() {
        // r1 has a member on A (origin) and on B, but NOT on C.
        nodeA.join("r1", "sA");
        nodeB.join("r1", "sB");

        nodeA.sender.roomMessage(URI, "r1", new TextMessage("hi r1"));

        // Origin (A) delivered locally to sA.
        assertEquals(1, nodeA.local.deliveries(URI, "r1"), "origin must local-fan-out to its members");
        // B was in the node-set → received via the per-node unicast channel and delivered to sB.
        assertEquals(1, nodeB.local.deliveries(URI, "r1"), "node B (hosts a member) must receive");
        // C was NOT in the node-set → received nothing. THE REDUCTION ASSERTION.
        assertEquals(0, nodeC.local.deliveries(URI, "r1"), "node C (no members) must NOT be targeted");

        // Fan-out meter: A targeted exactly 1 remote node (B), excluding self.
        assertEquals(1, nodeA.sender.getClusterRuntimeStats().getRoomBroadcastPublished());
        assertEquals(1, nodeA.sender.getClusterRuntimeStats().getRoomFanoutTargetsLast(),
                "A should have targeted exactly 1 remote node (B), not C");
    }

    @Test
    void originSelfSuppression_originDoesNotDoubleDeliver() {
        nodeA.join("r1", "sA");
        // A is the only member; node-set = {A}. roomMessage targets the node-set MINUS self → no remote send.
        nodeA.sender.roomMessage(URI, "r1", new TextMessage("solo"));

        // Exactly one local delivery (the local-first fan-out); the origin is excluded from targeting so
        // there is no echo back through the unicast channel.
        assertEquals(1, nodeA.local.deliveries(URI, "r1"));
        assertEquals(0, nodeA.sender.getClusterRuntimeStats().getRoomFanoutTargetsLast(),
                "solo room → 0 remote targets (self excluded)");
    }

    @Test
    void staleTargetCounted_whenTargetedNodeHasNoLocalMembers() {
        // Seed the SHARED node-set to claim B hosts r1, but give B NO local member (membership churned).
        store.nodeSet(URI, "r1").add("node-B");
        nodeA.join("r1", "sA"); // A also in the set, has a real member

        nodeA.sender.roomMessage(URI, "r1", new TextMessage("hi"));

        // B was targeted (in the node-set) but fans out to an empty local set → stale-target meter.
        assertEquals(0, nodeB.local.deliveries(URI, "r1"));
        assertEquals(1, nodeB.sender.getClusterRuntimeStats().getRoomFanoutStaleTarget(),
                "B received a room broadcast with zero local members → stale-target");
    }

    @Test
    void roomDisabled_roomMessageThrows() {
        Node plain = new Node("node-X", false); // no room registry wired
        try {
            assertThrows(IllegalStateException.class,
                    () -> plain.sender.roomMessage(URI, "r1", new TextMessage("x")));
        } finally {
            plain.shutdown();
        }
    }

    // ============================== Test scaffolding ==============================

    /** A node = local recording sender + cluster sender + node-aware room registry view over the shared store. */
    private final class Node {
        final String id;
        final RecordingLocalSender local = new RecordingLocalSender();
        final ClusterNodeManager nodeManager;
        final ClusterMessageSender sender;

        Node(String id) {
            this(id, true);
        }

        Node(String id, boolean rooms) {
            this.id = id;
            this.nodeManager = new ClusterNodeManager(id, 3000, 10000, 15000, 0, new NoOpHeartbeat(), sessionRegistry);
            this.sender = new ClusterMessageSender(local, broker, sessionRegistry, nodeManager, 5000);
            if (rooms) {
                sender.setRoomRegistry(new NodeRoomView(id));
            }
            local.addUri(URI);
            nodeManager.start();
            sender.start();
        }

        void join(String room, String sessionId) {
            local.addLocalSession(URI, sessionId);
            new NodeRoomView(id).join(URI, room, sessionId, id).toCompletableFuture().join();
        }

        void shutdown() {
            sender.shutdown();
            nodeManager.shutdown();
        }
    }

    /** Shared distributed state: per-(uri,room) node-set + per-(uri,room,node) member set. Mirrors Redis. */
    static final class SharedRoomStore {
        final ConcurrentHashMap<String, Set<String>> nodeSet = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Set<String>> membersByNode = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Set<String>> roomsBySession = new ConcurrentHashMap<>();

        Set<String> nodeSet(String uri, String room) {
            return nodeSet.computeIfAbsent(uri + " " + room, k -> ConcurrentHashMap.newKeySet());
        }

        Set<String> members(String uri, String room, String node) {
            return membersByNode.computeIfAbsent(uri + " " + room + " " + node, k -> ConcurrentHashMap.newKeySet());
        }
    }

    /** A per-node view of {@link SharedRoomStore}: nodesForRoom reads the shared node-set; localMembers
     *  reads only THIS node's member set (the per-node local index). */
    private final class NodeRoomView implements ClusterRoomRegistry {
        final String localNode;

        NodeRoomView(String localNode) {
            this.localNode = localNode;
        }

        @Override
        public synchronized CompletionStage<Void> join(String uri, String room, String sessionId, String nodeId) {
            Set<String> m = store.members(uri, room, nodeId);
            boolean wasEmpty = m.isEmpty();
            m.add(sessionId);
            if (wasEmpty) {
                store.nodeSet(uri, room).add(nodeId);
            }
            store.roomsBySession.computeIfAbsent(uri + " " + sessionId, k -> ConcurrentHashMap.newKeySet()).add(room);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public synchronized CompletionStage<Void> leave(String uri, String room, String sessionId, String nodeId) {
            Set<String> m = store.members(uri, room, nodeId);
            m.remove(sessionId);
            if (m.isEmpty()) {
                store.nodeSet(uri, room).remove(nodeId);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Set<String>> nodesForRoom(String uri, String room) {
            return CompletableFuture.completedFuture(new HashSet<>(store.nodeSet(uri, room)));
        }

        @Override
        public Set<String> localMembers(String uri, String room) {
            return new HashSet<>(store.members(uri, room, localNode));
        }

        @Override
        public Set<String> roomsForSession(String uri, String sessionId) {
            Set<String> r = store.roomsBySession.get(uri + " " + sessionId);
            return r == null ? Collections.emptySet() : new HashSet<>(r);
        }

        @Override
        public CompletionStage<Void> removeAllForSession(String uri, String sessionId, String nodeId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> removeAllForNode(String nodeId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void shutdown() {
        }
    }

    /** Local sender that records per-(uri,room... by sessionId) deliveries. Room is inferred by membership. */
    static final class RecordingLocalSender implements MessageSender {
        final Set<String> uris = new HashSet<>();
        /** (uri) → set of local session ids. */
        final Map<String, Set<String>> localSessions = new ConcurrentHashMap<>();
        /** (uri|sessionId) → delivery count. */
        final Map<String, Integer> deliveriesBySession = new ConcurrentHashMap<>();

        void addUri(String uri) { uris.add(uri); }
        void addLocalSession(String uri, String sessionId) {
            localSessions.computeIfAbsent(uri, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
        }

        /** Total deliveries to members of (uri,room) — but since the local sender doesn't know rooms, we
         *  count deliveries to ANY local session of the uri (the room test joins one session per room). */
        int deliveries(String uri, String room) {
            int sum = 0;
            for (var e : deliveriesBySession.entrySet()) {
                if (e.getKey().startsWith(uri + "|")) {
                    sum += e.getValue();
                }
            }
            return sum;
        }

        @Override public int getSessionNums() { return 0; }
        @Override public int getSessionNums(String uri) { return 0; }
        @Override public Set<String> getRegisteredUri() { return Collections.unmodifiableSet(uris); }
        @Override public boolean isSessionAlive(String uri, String... sessionIds) { return true; }

        @Override
        public void sendMessage(String uri, AbstractMessage message, String... sessionIds)
                throws MessageUriNotDefinedException, MessageSessionClosedException {
            Set<String> local = localSessions.getOrDefault(uri, Collections.emptySet());
            List<String> closed = new ArrayList<>();
            for (String sid : sessionIds) {
                if (local.contains(sid)) {
                    deliveriesBySession.merge(uri + "|" + sid, 1, Integer::sum);
                } else {
                    closed.add(sid);
                }
            }
            if (!closed.isEmpty()) {
                throw new MessageSessionClosedException(uri, closed);
            }
        }

        @Override
        public void topicMessage(String uri, AbstractMessage message) throws MessageUriNotDefinedException {
        }
    }

    static final class NoOpHeartbeat implements ClusterNodeHeartbeat {
        @Override public void register(String nodeId, long timeoutMs) {}
        @Override public void renewHeartbeat(String nodeId, long timeoutMs) {}
        @Override public void deregister(String nodeId) {}
        @Override public List<String> findExpiredNodes(long timeoutMs) { return Collections.emptyList(); }
    }
}
