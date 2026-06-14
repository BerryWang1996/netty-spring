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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRef;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Unit test for the {@link InMemoryUserRegistry} stub (no-Lettuce-leak proof + drain unit-test fixture). */
class InMemoryUserRegistryTest {

    private static final String URI = "/ws/chat";

    @Test
    void bindUnbindAndOnlineLifecycle() {
        InMemoryUserRegistry reg = new InMemoryUserRegistry();
        assertFalse(reg.isUserOnline("alice").toCompletableFuture().join());

        reg.bindUser("alice", URI, "s1", "node-A").toCompletableFuture().join();
        assertTrue(reg.isUserOnline("alice").toCompletableFuture().join());

        Set<SessionRef> refs = reg.sessionsForUser("alice").toCompletableFuture().join();
        assertEquals(1, refs.size());
        assertTrue(refs.contains(new SessionRef("node-A", URI, "s1")));

        reg.unbindUser("alice", URI, "s1").toCompletableFuture().join();
        assertFalse(reg.isUserOnline("alice").toCompletableFuture().join());
        reg.shutdown();
    }

    @Test
    void multiDeviceTwoSessionsOneUser() {
        InMemoryUserRegistry reg = new InMemoryUserRegistry();
        reg.bindUser("alice", URI, "s1", "node-A").toCompletableFuture().join();
        reg.bindUser("alice", "/ws/notify", "s2", "node-B").toCompletableFuture().join();

        assertEquals(2, reg.sessionsForUser("alice").toCompletableFuture().join().size());
        // Removing one device leaves the other online.
        reg.unbindUser("alice", URI, "s1").toCompletableFuture().join();
        assertTrue(reg.isUserOnline("alice").toCompletableFuture().join());
        assertEquals(1, reg.sessionsForUser("alice").toCompletableFuture().join().size());
        reg.shutdown();
    }

    @Test
    void removeAllForNodePrunesOnlyThatNode() {
        InMemoryUserRegistry reg = new InMemoryUserRegistry();
        reg.bindUser("alice", URI, "s1", "node-A").toCompletableFuture().join();
        reg.bindUser("alice", "/ws/notify", "s2", "node-B").toCompletableFuture().join();

        reg.removeAllForNode("node-A").toCompletableFuture().join();

        Set<SessionRef> refs = reg.sessionsForUser("alice").toCompletableFuture().join();
        assertEquals(1, refs.size());
        assertTrue(refs.contains(new SessionRef("node-B", "/ws/notify", "s2")));
        reg.shutdown();
    }

    @Test
    void sessionsForUserReturnsFreshSnapshot() {
        InMemoryUserRegistry reg = new InMemoryUserRegistry();
        reg.bindUser("alice", URI, "s1", "node-A").toCompletableFuture().join();
        Set<SessionRef> first = reg.sessionsForUser("alice").toCompletableFuture().join();
        // Mutating the returned snapshot must not affect the registry (defensive copy / no cache).
        first.clear();
        assertEquals(1, reg.sessionsForUser("alice").toCompletableFuture().join().size());
        reg.shutdown();
    }
}
