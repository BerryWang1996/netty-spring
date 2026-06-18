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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MeshInterestRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryMeshInterestRegistryTest {

    private final MeshInterestRegistry reg = new InMemoryMeshInterestRegistry();

    private Set<String> nodes(String uri) {
        return reg.nodesForUri(uri).toCompletableFuture().join();
    }

    @Test
    void nodeJoinsOnFirstSession_leavesOnLast() {
        reg.subscribe("/ws/a", "s1", "node-A").toCompletableFuture().join();
        reg.subscribe("/ws/a", "s2", "node-A").toCompletableFuture().join(); // 2nd session, same node
        assertEquals(Set.of("node-A"), nodes("/ws/a"));

        reg.unsubscribe("/ws/a", "s1", "node-A").toCompletableFuture().join(); // not last → node stays
        assertEquals(Set.of("node-A"), nodes("/ws/a"));

        reg.unsubscribe("/ws/a", "s2", "node-A").toCompletableFuture().join(); // last → node leaves
        assertTrue(nodes("/ws/a").isEmpty());
    }

    @Test
    void removeAllForNode_dropsTheNodeFromEveryUri() {
        reg.subscribe("/ws/a", "s1", "node-A").toCompletableFuture().join();
        reg.subscribe("/ws/b", "s2", "node-A").toCompletableFuture().join();
        reg.subscribe("/ws/a", "s3", "node-B").toCompletableFuture().join();

        reg.removeAllForNode("node-A").toCompletableFuture().join();

        assertEquals(Set.of("node-B"), nodes("/ws/a"));
        assertTrue(nodes("/ws/b").isEmpty());
    }
}
