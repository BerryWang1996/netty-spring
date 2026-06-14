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

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link InMemoryRoomRegistry} test stub — pins the node-set invariant (add on first
 * member, remove on last), removeAllForSession clears all rooms, and the local index queries. These same
 * behaviors are the contract the real {@link com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterRoomRegistry}
 * implementations must honor.
 */
class InMemoryRoomRegistryTest {

    private final InMemoryRoomRegistry registry = new InMemoryRoomRegistry();
    private static final String URI = "/ws/chat";

    private Set<String> nodes(String room) {
        return registry.nodesForRoom(URI, room).toCompletableFuture().join();
    }

    @Test
    void joinAddsNodeToNodeSetOnFirstMember_notAgainOnSecond() {
        registry.join(URI, "r1", "s1", "node-A").toCompletableFuture().join();
        assertEquals(Set.of("node-A"), nodes("r1"));

        // Second member on the SAME node → node-set unchanged (still just node-A).
        registry.join(URI, "r1", "s2", "node-A").toCompletableFuture().join();
        assertEquals(Set.of("node-A"), nodes("r1"));

        // A member on a different node → node-set grows.
        registry.join(URI, "r1", "s3", "node-B").toCompletableFuture().join();
        assertEquals(Set.of("node-A", "node-B"), nodes("r1"));
    }

    @Test
    void leaveRemovesNodeFromNodeSetOnLastMember() {
        registry.join(URI, "r1", "s1", "node-A").toCompletableFuture().join();
        registry.join(URI, "r1", "s2", "node-A").toCompletableFuture().join();

        // Remove one of two members on node-A → node still in the set.
        registry.leave(URI, "r1", "s1", "node-A").toCompletableFuture().join();
        assertEquals(Set.of("node-A"), nodes("r1"));

        // Remove the last member on node-A → node removed from the set (now empty).
        registry.leave(URI, "r1", "s2", "node-A").toCompletableFuture().join();
        assertTrue(nodes("r1").isEmpty(), "node-set must drop node-A when its last member leaves");
    }

    @Test
    void localMembersAndRoomsForSessionReflectMembership() {
        registry.join(URI, "r1", "s1", "node-A").toCompletableFuture().join();
        registry.join(URI, "r2", "s1", "node-A").toCompletableFuture().join();
        registry.join(URI, "r1", "s2", "node-A").toCompletableFuture().join();

        assertEquals(Set.of("s1", "s2"), registry.localMembers(URI, "r1"));
        assertEquals(Set.of("s1"), registry.localMembers(URI, "r2"));
        assertEquals(Set.of("r1", "r2"), registry.roomsForSession(URI, "s1"));
        assertEquals(Set.of("r1"), registry.roomsForSession(URI, "s2"));
    }

    @Test
    void removeAllForSessionClearsEveryRoom() {
        registry.join(URI, "r1", "s1", "node-A").toCompletableFuture().join();
        registry.join(URI, "r2", "s1", "node-A").toCompletableFuture().join();
        registry.join(URI, "r3", "s1", "node-A").toCompletableFuture().join();

        registry.removeAllForSession(URI, "s1", "node-A").toCompletableFuture().join();

        assertTrue(registry.roomsForSession(URI, "s1").isEmpty());
        assertTrue(nodes("r1").isEmpty());
        assertTrue(nodes("r2").isEmpty());
        assertTrue(nodes("r3").isEmpty());
        assertTrue(registry.localMembers(URI, "r1").isEmpty());
    }

    @Test
    void removeAllForSessionLeavesOtherSessionsRoomIntact() {
        registry.join(URI, "r1", "s1", "node-A").toCompletableFuture().join();
        registry.join(URI, "r1", "s2", "node-A").toCompletableFuture().join();

        registry.removeAllForSession(URI, "s1", "node-A").toCompletableFuture().join();

        // s2 is still in r1, so node-A stays in the node-set.
        assertEquals(Set.of("node-A"), nodes("r1"));
        assertEquals(Set.of("s2"), registry.localMembers(URI, "r1"));
    }

    @Test
    void removeAllForNodeDropsNodeFromEveryRoom() {
        registry.join(URI, "r1", "s1", "node-A").toCompletableFuture().join();
        registry.join(URI, "r1", "s2", "node-B").toCompletableFuture().join();
        registry.join(URI, "r2", "s3", "node-A").toCompletableFuture().join();

        registry.removeAllForNode("node-A").toCompletableFuture().join();

        assertEquals(Set.of("node-B"), nodes("r1"), "node-A removed; node-B remains");
        assertTrue(nodes("r2").isEmpty(), "node-A was r2's only host → node-set empty");
    }
}
