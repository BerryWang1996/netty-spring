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

package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterRuntimeStats;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.HmacMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.mesh.MeshBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.NodeState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NettyClusterMeterBinderTest {

    @Test
    void bindsCountersAndPerStateGauges() {
        ClusterRuntimeStats stats = mock(ClusterRuntimeStats.class);
        when(stats.getBroadcastPublished()).thenReturn(5L);
        when(stats.getCrossNodeBroadcastReceived()).thenReturn(3L);
        when(stats.getSelfDeliveryDropped()).thenReturn(4L);
        when(stats.getUnicastSent()).thenReturn(2L);
        when(stats.getReliablePublished()).thenReturn(7L);
        when(stats.getPublishFailures()).thenReturn(1L);
        // unstubbed long getters default to 0 (Mockito) -> those meters read 0.0

        ClusterMessageSender sender = mock(ClusterMessageSender.class);
        when(sender.getClusterRuntimeStats()).thenReturn(stats);
        ClusterNodeManager nodeManager = mock(ClusterNodeManager.class);
        when(nodeManager.getState()).thenReturn(NodeState.ACTIVE);
        ClusterBroker broker = mock(ClusterBroker.class);
        when(broker.state()).thenReturn(BrokerState.ACTIVE);

        // a real HMAC authenticator with 2 rejections (strict rejects untagged input)
        HmacMessageAuthenticator auth =
                new HmacMessageAuthenticator("a-32-char-secret-for-the-test!!!".getBytes(), true);
        auth.unwrap("untagged-1");
        auth.unwrap("untagged-2");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NettyClusterMeterBinder binder = new NettyClusterMeterBinder(sender, nodeManager, broker, auth);
        binder.bindTo(registry);

        assertEquals(5.0, registry.get("netty.cluster.broadcast.published").functionCounter().count());
        assertEquals(3.0, registry.get("netty.cluster.broadcast.received").functionCounter().count());
        assertEquals(4.0, registry.get("netty.cluster.broadcast.self_dropped").functionCounter().count());
        assertEquals(2.0, registry.get("netty.cluster.unicast.sent").functionCounter().count());
        assertEquals(7.0, registry.get("netty.cluster.reliable.published").functionCounter().count());
        assertEquals(1.0, registry.get("netty.cluster.publish.failures").functionCounter().count());
        assertEquals(0.0, registry.get("netty.cluster.cache.hits").functionCounter().count());
        assertEquals(2.0, registry.get("netty.cluster.auth.rejected").functionCounter().count());

        // per-state gauges: ACTIVE=1.0, others=0.0
        assertEquals(1.0, registry.get("netty.cluster.node.state").tag("state", "active").gauge().value());
        assertEquals(0.0, registry.get("netty.cluster.node.state").tag("state", "degraded").gauge().value());
        assertEquals(0.0, registry.get("netty.cluster.node.state").tag("state", "left").gauge().value());
        assertEquals(1.0, registry.get("netty.cluster.broker.state").tag("state", "active").gauge().value());
        assertEquals(0.0, registry.get("netty.cluster.broker.state").tag("state", "shutdown").gauge().value());

        // idempotent re-bind: no duplicate meters
        int before = registry.getMeters().size();
        binder.bindTo(registry);
        assertEquals(before, registry.getMeters().size(), "re-binding the same registry must not duplicate meters");
    }

    @Test
    void gaugesTrackNonDefaultState() {
        ClusterMessageSender sender = mock(ClusterMessageSender.class);
        when(sender.getClusterRuntimeStats()).thenReturn(mock(ClusterRuntimeStats.class));
        ClusterNodeManager nodeManager = mock(ClusterNodeManager.class);
        when(nodeManager.getState()).thenReturn(NodeState.DEGRADED);
        ClusterBroker broker = mock(ClusterBroker.class);
        when(broker.state()).thenReturn(BrokerState.RESYNC);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new NettyClusterMeterBinder(sender, nodeManager, broker, new NoOpMessageAuthenticator())
                .bindTo(registry);

        // the tagged gauge for the current (non-default) state reads 1.0, all others 0.0
        assertEquals(1.0, registry.get("netty.cluster.node.state").tag("state", "degraded").gauge().value());
        assertEquals(0.0, registry.get("netty.cluster.node.state").tag("state", "active").gauge().value());
        assertEquals(1.0, registry.get("netty.cluster.broker.state").tag("state", "resync").gauge().value());
        assertEquals(0.0, registry.get("netty.cluster.broker.state").tag("state", "active").gauge().value());
    }

    @Test
    void bindsRoomMeters() {
        ClusterRuntimeStats stats = mock(ClusterRuntimeStats.class);
        when(stats.getRoomBroadcastPublished()).thenReturn(9L);
        when(stats.getRoomBroadcastReceived()).thenReturn(8L);
        when(stats.getRoomFanoutStaleTarget()).thenReturn(2L);
        when(stats.getRoomFanoutTargetsAvg()).thenReturn(4.5);
        when(stats.getRoomLocalMemberships()).thenReturn(11L);

        ClusterMessageSender sender = mock(ClusterMessageSender.class);
        when(sender.getClusterRuntimeStats()).thenReturn(stats);
        ClusterNodeManager nodeManager = mock(ClusterNodeManager.class);
        when(nodeManager.getState()).thenReturn(NodeState.ACTIVE);
        ClusterBroker broker = mock(ClusterBroker.class);
        when(broker.state()).thenReturn(BrokerState.ACTIVE);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new NettyClusterMeterBinder(sender, nodeManager, broker, new NoOpMessageAuthenticator())
                .bindTo(registry);

        assertEquals(9.0, registry.get("netty.cluster.room.broadcast.published").functionCounter().count());
        assertEquals(8.0, registry.get("netty.cluster.room.broadcast.received").functionCounter().count());
        assertEquals(2.0, registry.get("netty.cluster.room.fanout.stale_target").functionCounter().count());
        assertEquals(4.5, registry.get("netty.cluster.room.fanout.target_nodes").gauge().value());
        assertEquals(11.0, registry.get("netty.cluster.room.members.local").gauge().value());
    }

    @Test
    void bindsPresenceMeters() {
        ClusterRuntimeStats stats = mock(ClusterRuntimeStats.class);
        when(stats.getPresenceChanges()).thenReturn(6L);
        when(stats.getPresenceEventsPublished()).thenReturn(5L);
        when(stats.getPresenceEventsReceived()).thenReturn(4L);
        when(stats.getPresenceSelfDeliveryDropped()).thenReturn(3L);
        when(stats.getPresenceSet()).thenReturn(7L);
        when(stats.getPresenceReapOffline()).thenReturn(2L);

        ClusterMessageSender sender = mock(ClusterMessageSender.class);
        when(sender.getClusterRuntimeStats()).thenReturn(stats);
        ClusterNodeManager nodeManager = mock(ClusterNodeManager.class);
        when(nodeManager.getState()).thenReturn(NodeState.ACTIVE);
        ClusterBroker broker = mock(ClusterBroker.class);
        when(broker.state()).thenReturn(BrokerState.ACTIVE);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new NettyClusterMeterBinder(sender, nodeManager, broker, new NoOpMessageAuthenticator())
                .bindTo(registry);

        assertEquals(6.0, registry.get("netty.cluster.presence.changes").functionCounter().count());
        assertEquals(5.0, registry.get("netty.cluster.presence.events_published").functionCounter().count());
        assertEquals(4.0, registry.get("netty.cluster.presence.events_received").functionCounter().count());
        assertEquals(3.0, registry.get("netty.cluster.presence.self_delivery_dropped").functionCounter().count());
        assertEquals(7.0, registry.get("netty.cluster.presence.set").functionCounter().count());
        assertEquals(2.0, registry.get("netty.cluster.presence.reap_offline").functionCounter().count());
    }

    @Test
    void bindsMeshMetersOnlyWhenBrokerIsMesh() {
        // RC4d: the mesh meters read the BROKER's own stats (not the sender's), and appear only for a MeshBroker.
        ClusterRuntimeStats meshStats = mock(ClusterRuntimeStats.class);
        when(meshStats.getMeshFramesReceived()).thenReturn(10L);
        when(meshStats.getMeshFramesSent()).thenReturn(9L);
        when(meshStats.getMeshSendFailures()).thenReturn(2L);
        when(meshStats.getMeshSendDroppedBackpressure()).thenReturn(1L);
        when(meshStats.getMeshIdleReaps()).thenReturn(3L);
        when(meshStats.getMeshReconnectBackoffSkips()).thenReturn(4L);
        when(meshStats.getMeshFanoutTargetsAvg()).thenReturn(2.5);

        MeshBroker mesh = mock(MeshBroker.class);
        when(mesh.state()).thenReturn(BrokerState.ACTIVE);
        when(mesh.runtimeStats()).thenReturn(meshStats);
        when(mesh.activeOutboundConnections()).thenReturn(6);
        when(mesh.knownPeerCount()).thenReturn(8);

        ClusterMessageSender sender = mock(ClusterMessageSender.class);
        when(sender.getClusterRuntimeStats()).thenReturn(mock(ClusterRuntimeStats.class));   // sender's own (non-mesh) stats
        ClusterNodeManager nodeManager = mock(ClusterNodeManager.class);
        when(nodeManager.getState()).thenReturn(NodeState.ACTIVE);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new NettyClusterMeterBinder(sender, nodeManager, mesh, new NoOpMessageAuthenticator()).bindTo(registry);

        assertEquals(10.0, registry.get("netty.cluster.mesh.frames.received").functionCounter().count());
        assertEquals(9.0, registry.get("netty.cluster.mesh.frames.sent").functionCounter().count());
        assertEquals(2.0, registry.get("netty.cluster.mesh.send.failures").functionCounter().count());
        assertEquals(1.0, registry.get("netty.cluster.mesh.send.dropped_backpressure").functionCounter().count());
        assertEquals(3.0, registry.get("netty.cluster.mesh.idle.reaps").functionCounter().count());
        assertEquals(4.0, registry.get("netty.cluster.mesh.reconnect.backoff_skips").functionCounter().count());
        assertEquals(2.5, registry.get("netty.cluster.mesh.fanout.target_nodes").gauge().value());
        assertEquals(6.0, registry.get("netty.cluster.mesh.connections.active").gauge().value());
        assertEquals(8.0, registry.get("netty.cluster.mesh.peers.known").gauge().value());
    }

    @Test
    void nonMeshBrokerEmitsNoMeshMeters() {
        ClusterMessageSender sender = mock(ClusterMessageSender.class);
        when(sender.getClusterRuntimeStats()).thenReturn(mock(ClusterRuntimeStats.class));
        ClusterNodeManager nodeManager = mock(ClusterNodeManager.class);
        when(nodeManager.getState()).thenReturn(NodeState.ACTIVE);
        ClusterBroker broker = mock(ClusterBroker.class);   // the standalone Redis path — NOT a MeshBroker
        when(broker.state()).thenReturn(BrokerState.ACTIVE);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new NettyClusterMeterBinder(sender, nodeManager, broker, new NoOpMessageAuthenticator()).bindTo(registry);

        assertNull(registry.find("netty.cluster.mesh.frames.received").meter(), "no mesh meters on the Redis path");
        assertNull(registry.find("netty.cluster.mesh.fanout.target_nodes").meter());
        assertNull(registry.find("netty.cluster.mesh.peers.known").meter());
    }

    @Test
    void noOpAuthenticatorReportsZeroRejections() {
        ClusterMessageSender sender = mock(ClusterMessageSender.class);
        when(sender.getClusterRuntimeStats()).thenReturn(mock(ClusterRuntimeStats.class));
        ClusterNodeManager nodeManager = mock(ClusterNodeManager.class);
        when(nodeManager.getState()).thenReturn(NodeState.ACTIVE);
        ClusterBroker broker = mock(ClusterBroker.class);
        when(broker.state()).thenReturn(BrokerState.ACTIVE);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new NettyClusterMeterBinder(sender, nodeManager, broker, new NoOpMessageAuthenticator())
                .bindTo(registry);

        assertEquals(0.0, registry.get("netty.cluster.auth.rejected").functionCounter().count());
    }
}
