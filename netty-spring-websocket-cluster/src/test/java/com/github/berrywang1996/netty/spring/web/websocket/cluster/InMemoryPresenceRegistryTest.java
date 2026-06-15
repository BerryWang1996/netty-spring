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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceStatus;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceTransition;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserPresence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPresenceRegistryTest {

    private final InMemoryPresenceRegistry reg = new InMemoryPresenceRegistry();

    private <T> T get(java.util.concurrent.CompletionStage<T> cs) throws ExecutionException, InterruptedException {
        return cs.toCompletableFuture().get();
    }

    @Test
    void firstConnect_offlineToOnline() throws Exception {
        PresenceTransition t = get(reg.setPresence("u", "nA", "s1", PresenceStatus.ONLINE));
        assertEquals(PresenceStatus.OFFLINE, t.getOldAggregate());
        assertEquals(PresenceStatus.ONLINE, t.getNewAggregate());
        assertTrue(t.changed());
    }

    @Test
    void secondConnect_noTransition() throws Exception {
        get(reg.setPresence("u", "nA", "s1", PresenceStatus.ONLINE));
        PresenceTransition t = get(reg.setPresence("u", "nA", "s2", PresenceStatus.ONLINE));
        assertEquals(PresenceStatus.ONLINE, t.getOldAggregate());
        assertEquals(PresenceStatus.ONLINE, t.getNewAggregate());
        assertFalse(t.changed());
    }

    @Test
    void allAway_aggregateAway() throws Exception {
        get(reg.setPresence("u", "nA", "s1", PresenceStatus.AWAY));
        get(reg.setPresence("u", "nA", "s2", PresenceStatus.AWAY));
        UserPresence p = get(reg.getPresence("u"));
        assertEquals(PresenceStatus.AWAY, p.getAggregate());
        assertEquals(0, p.getOnlineConnections());
        assertEquals(2, p.getAwayConnections());
    }

    @Test
    void lastClear_onlineToOffline() throws Exception {
        get(reg.setPresence("u", "nA", "s1", PresenceStatus.ONLINE));
        get(reg.setPresence("u", "nA", "s2", PresenceStatus.ONLINE));
        get(reg.clearPresence("u", "nA", "s1"));
        PresenceTransition last = get(reg.clearPresence("u", "nA", "s2"));
        assertEquals(PresenceStatus.ONLINE, last.getOldAggregate());
        assertEquals(PresenceStatus.OFFLINE, last.getNewAggregate());
        assertTrue(last.changed());
    }

    @Test
    void setPresenceForUser_allAway() throws Exception {
        get(reg.setPresence("u", "nA", "s1", PresenceStatus.ONLINE));
        get(reg.setPresence("u", "nA", "s2", PresenceStatus.ONLINE));
        PresenceTransition t = get(reg.setPresenceForUser("u", PresenceStatus.AWAY));
        assertEquals(PresenceStatus.ONLINE, t.getOldAggregate());
        assertEquals(PresenceStatus.AWAY, t.getNewAggregate());
        UserPresence p = get(reg.getPresence("u"));
        assertEquals(PresenceStatus.AWAY, p.getAggregate());
        assertEquals(2, p.getAwayConnections());
    }

    @Test
    void removeAllForNode_emitsTransitions() throws Exception {
        get(reg.setPresence("u", "nodeA", "s1", PresenceStatus.ONLINE));   // u only on nodeA
        get(reg.setPresence("v", "nodeA", "s2", PresenceStatus.ONLINE));   // v on nodeA ...
        get(reg.setPresence("v", "nodeB", "s3", PresenceStatus.ONLINE));   // ... and nodeB
        List<PresenceTransition> changed = get(reg.removeAllForNode("nodeA"));
        assertEquals(1, changed.size());
        PresenceTransition t = changed.get(0);
        assertEquals("u", t.getUserId());
        assertEquals(PresenceStatus.ONLINE, t.getOldAggregate());
        assertEquals(PresenceStatus.OFFLINE, t.getNewAggregate());
        // v still ONLINE via nodeB
        assertEquals(PresenceStatus.ONLINE, get(reg.getPresence("v")).getAggregate());
    }

    @Test
    void getPresence_unknownUser_offline() throws Exception {
        UserPresence p = get(reg.getPresence("nobody"));
        assertEquals(PresenceStatus.OFFLINE, p.getAggregate());
        assertEquals(0, p.getTotalConnections());
    }
}
