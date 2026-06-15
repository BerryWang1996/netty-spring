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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.NodeState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Bridges WebSocket-cluster runtime signals to a Micrometer {@link MeterRegistry} as
 * {@code netty.cluster.*} meters (read-throughs over the existing counters — no hot-path cost).
 *
 * <p>Counters: broadcast published/received/self_dropped/skipped_degraded, unicast sent, publish
 * failures, reliable published/received, cache hits/misses, auth rejected. Gauges: per-state up/down
 * for node state and broker state (tagged {@code state}). Aggregate-only (no per-URI tags).
 *
 * @author berrywang1996
 * @since V1.9.0
 * @see NettyClusterMetricsConfigure
 */
public class NettyClusterMeterBinder implements MeterBinder {

    private final ClusterMessageSender sender;
    private final ClusterNodeManager nodeManager;
    private final ClusterBroker broker;
    private final MessageAuthenticator authenticator;

    /** Registries already bound, by identity, so a repeated bindTo does not duplicate meters. */
    private final Set<MeterRegistry> boundRegistries =
            Collections.newSetFromMap(new IdentityHashMap<MeterRegistry, Boolean>());

    public NettyClusterMeterBinder(ClusterMessageSender sender, ClusterNodeManager nodeManager,
                                   ClusterBroker broker, MessageAuthenticator authenticator) {
        this.sender = sender;
        this.nodeManager = nodeManager;
        this.broker = broker;
        this.authenticator = authenticator;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        synchronized (boundRegistries) {
            if (!boundRegistries.add(registry)) {
                return;
            }
        }
        ClusterRuntimeStats stats = sender.getClusterRuntimeStats();

        counter(registry, "netty.cluster.broadcast.published", stats,
                ClusterRuntimeStats::getBroadcastPublished, "Broadcasts handed to the cluster broker for publish");
        counter(registry, "netty.cluster.broadcast.received", stats,
                ClusterRuntimeStats::getCrossNodeBroadcastReceived, "Broadcasts received from other nodes and delivered locally");
        counter(registry, "netty.cluster.broadcast.self_dropped", stats,
                ClusterRuntimeStats::getSelfDeliveryDropped, "Self-delivered broadcasts suppressed (origin == local node)");
        counter(registry, "netty.cluster.broadcast.skipped_degraded", stats,
                ClusterRuntimeStats::getBroadcastsSkippedDegraded, "Cross-node broadcasts skipped because the node was not ACTIVE");
        counter(registry, "netty.cluster.unicast.sent", stats,
                ClusterRuntimeStats::getUnicastSent, "Unicast messages sent to remote nodes");
        counter(registry, "netty.cluster.publish.failures", stats,
                ClusterRuntimeStats::getPublishFailures, "Cluster publishes that failed or were dropped");
        counter(registry, "netty.cluster.reliable.published", stats,
                ClusterRuntimeStats::getReliablePublished, "Reliable broadcasts published (XADD)");
        counter(registry, "netty.cluster.reliable.received", stats,
                ClusterRuntimeStats::getReliableReceived, "Reliable broadcasts received and delivered locally");
        counter(registry, "netty.cluster.cache.hits", stats,
                ClusterRuntimeStats::getCacheHits, "Node lookup cache hits");
        counter(registry, "netty.cluster.cache.misses", stats,
                ClusterRuntimeStats::getCacheMisses, "Node lookup cache misses");

        // ---- Room-scoped routing (1.10.0) ----
        counter(registry, "netty.cluster.room.broadcast.published", stats,
                ClusterRuntimeStats::getRoomBroadcastPublished, "Room broadcasts sent (per-room node-targeted)");
        counter(registry, "netty.cluster.room.broadcast.received", stats,
                ClusterRuntimeStats::getRoomBroadcastReceived, "Room broadcasts received and locally delivered");
        counter(registry, "netty.cluster.room.fanout.stale_target", stats,
                ClusterRuntimeStats::getRoomFanoutStaleTarget,
                "Room broadcasts received with zero local members (membership churned in-flight = wasted delivery)");
        // The reduction meter: average nodes targeted per room broadcast — compare to the cluster size to
        // SEE the N/k reduction (1 = no reduction / hot room). Pass-through gauge (no hot-path cost).
        Gauge.builder("netty.cluster.room.fanout.target_nodes", stats,
                        ClusterRuntimeStats::getRoomFanoutTargetsAvg)
                .description("Average number of nodes targeted per room broadcast (the fan-out reduction meter)")
                .register(registry);
        Gauge.builder("netty.cluster.room.members.local", stats,
                        s -> (double) s.getRoomLocalMemberships())
                .description("Total local room memberships on this node")
                .register(registry);

        // ---- Offline queue / user-addressed delivery (1.10.0-RC2) ----
        counter(registry, "netty.cluster.offline.enqueued", stats,
                ClusterRuntimeStats::getOfflineEnqueued, "Messages enqueued to a user's offline queue");
        counter(registry, "netty.cluster.offline.drained", stats,
                ClusterRuntimeStats::getOfflineDrained, "Offline messages drained + delivered on reconnect (backfill)");
        counter(registry, "netty.cluster.offline.dropped_retention", stats,
                ClusterRuntimeStats::getOfflineDroppedRetention,
                "Offline entries dropped by retention on the TTL-drop path (entry older than ttl-seconds, reaped "
                        + "on drain) — the bounded-gap honesty meter. Server-side MAXLEN ~ trim is not separately metered");
        counter(registry, "netty.cluster.offline.send_to_user.realtime", stats,
                ClusterRuntimeStats::getSendToUserRealtime, "sendToUser delivered in realtime (user online)");
        counter(registry, "netty.cluster.offline.send_to_user.queued", stats,
                ClusterRuntimeStats::getSendToUserQueued, "sendToUser that fell through to the offline queue");
        counter(registry, "netty.cluster.offline.unicast_failures", stats,
                ClusterRuntimeStats::getUnicastFailures,
                "sendToUser send-time unicast failures (local MessageSessionClosedException on a bound session)");
        counter(registry, "netty.cluster.offline.fallback_enqueue_failures", stats,
                ClusterRuntimeStats::getFallbackEnqueueFailures,
                "Fallback enqueue itself failed after all unicast paths (never a silent drop — logged ERROR)");
        counter(registry, "netty.cluster.offline.resolved_identities", stats,
                ClusterRuntimeStats::getResolvedIdentities, "Handshakes that resolved to a userId (identified)");
        counter(registry, "netty.cluster.offline.unresolved_sessions", stats,
                ClusterRuntimeStats::getUnresolvedSessions, "Handshakes that resolved to null (anonymous)");
        Gauge.builder("netty.cluster.offline.users.online", stats,
                        s -> (double) s.getUsersOnlineLocal())
                .description("Local bound-user sessions on this node (identified live sessions; per-node, not distinct cluster-wide)")
                .register(registry);

        // ---- Multi-device presence (1.10.0-RC3) ----
        counter(registry, "netty.cluster.presence.changes", stats,
                ClusterRuntimeStats::getPresenceChanges,
                "Aggregate presence transitions detected locally (old != new) — the only case that fires an event");
        counter(registry, "netty.cluster.presence.events_published", stats,
                ClusterRuntimeStats::getPresenceEventsPublished, "PRESENCE_CHANGE events published to the reserved channel");
        counter(registry, "netty.cluster.presence.events_received", stats,
                ClusterRuntimeStats::getPresenceEventsReceived,
                "PRESENCE_CHANGE events received from other nodes (after origin self-suppression)");
        counter(registry, "netty.cluster.presence.self_delivery_dropped", stats,
                ClusterRuntimeStats::getPresenceSelfDeliveryDropped,
                "Own-origin PRESENCE_CHANGE echoes dropped on receive (self-suppression)");
        counter(registry, "netty.cluster.presence.set", stats,
                ClusterRuntimeStats::getPresenceSet, "Connection-level presence writes (setPresence)");
        counter(registry, "netty.cluster.presence.reap_offline", stats,
                ClusterRuntimeStats::getPresenceReapOffline,
                "OFFLINE/AWAY transitions emitted by the dead-node reap — the crash-path correction meter");

        FunctionCounter.builder("netty.cluster.auth.rejected", authenticator,
                        a -> (a instanceof HmacMessageAuthenticator)
                                ? (double) ((HmacMessageAuthenticator) a).getRejectedCount() : 0.0)
                .description("Inbound cluster envelopes rejected for a missing/invalid HMAC tag")
                .register(registry);

        for (NodeState s : NodeState.values()) {
            Gauge.builder("netty.cluster.node.state", nodeManager, nm -> nm.getState() == s ? 1.0 : 0.0)
                    .tag("state", s.name().toLowerCase())
                    .description("1.0 when this node is in the tagged state, else 0.0")
                    .register(registry);
        }
        for (BrokerState s : BrokerState.values()) {
            Gauge.builder("netty.cluster.broker.state", broker, b -> b.state() == s ? 1.0 : 0.0)
                    .tag("state", s.name().toLowerCase())
                    .description("1.0 when the cluster broker is in the tagged state, else 0.0")
                    .register(registry);
        }
    }

    private static void counter(MeterRegistry registry, String name, ClusterRuntimeStats stats,
                                ToDoubleFunction<ClusterRuntimeStats> f, String description) {
        FunctionCounter.builder(name, stats, f).description(description).register(registry);
    }
}
