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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.NodeState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.*;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterTraceContext;
import java.util.concurrent.atomic.AtomicLong;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cluster-aware {@link MessageSender} implementation that wraps the local (single-node)
 * {@code MessageSender} and adds cross-node broadcast/unicast via the {@link ClusterBroker}
 * and {@link SessionRegistry} SPIs.
 *
 * <h3>Design principle: local-first, cluster-second</h3>
 * <ul>
 *   <li>All local-query methods ({@link #getSessionIds}, {@link #getSessions},
 *       {@link #isSessionAlive}) remain <b>local-node only</b> and O(1) — they delegate
 *       directly to the local sender with zero network overhead.</li>
 *   <li>{@link #topicMessage} (broadcast) performs local fan-out first, then publishes to
 *       the cluster broker for remote nodes (at-most-once by default).</li>
 *   <li>{@link #sendMessage} checks if the target session is local; if not, it looks up
 *       the owning node via the registry and unicasts through the broker.</li>
 *   <li>Cluster-wide queries are exposed as <b>new async methods</b> that return
 *       {@link CompletionStage} — never hidden inside synchronous signatures.</li>
 * </ul>
 *
 * <h3>Self-delivery suppression</h3>
 * <p>Every broadcast envelope carries this node's {@code originNodeId}. When the
 * broker delivers a message back to the origin (Redis Pub/Sub always does this),
 * the subscription callback compares the envelope's originNodeId with the local
 * nodeId and <b>discards</b> the message — preventing local users from receiving
 * duplicates.
 *
 * @author berrywang1996
 * @since V1.8.0
 * @see ClusterBroker
 * @see SessionRegistry
 */
@Slf4j
public class ClusterMessageSender implements MessageSender, RoomOperations, UserOperations, PresenceOperations {

    private final MessageSender localSender;
    private final ClusterBroker broker;
    private final SessionRegistry registry;
    private final ClusterNodeManager nodeManager;
    private final MessagePayloadCodec payloadCodec;

    /** Default LRU cap for {@link #nodeCache} when none is configured (legacy constructor / unset knob). */
    private static final int DEFAULT_NODE_CACHE_MAX_SIZE = 100_000;

    /**
     * Local cache: (uri|sessionId) → nodeId. The TTL governs only REUSE, not eviction — entries for LIVE
     * remote sessions are removed only on NODE_LEFT or a miss, so a node unicasting to many distinct remote
     * sessions would otherwise grow this map without bound (memory leak on the unicast hot path). Bounding it
     * as an access-order LRU caps the footprint; the oldest (least-recently-used) entry is evicted past the
     * cap. A wrongly-evicted live entry simply triggers one extra registry lookup — correctness is preserved.
     * Guarded by its own monitor via {@code synchronizedMap}; {@code entrySet().removeIf} in
     * {@link #invalidateCacheForNode} synchronizes on the map explicitly (required for the iterator).
     */
    private volatile Map<String, CachedNodeLookup> nodeCache = newBoundedNodeCache(DEFAULT_NODE_CACHE_MAX_SIZE);
    private final long nodeCacheTtlMs;

    /** Active broadcast subscriptions keyed by URI. */
    private final ConcurrentHashMap<String, ClusterSubscription> broadcastSubscriptions = new ConcurrentHashMap<>();

    /** Unicast subscription for this node. */
    private ClusterSubscription unicastSubscription;

    /** Cluster-specific runtime statistics. */
    private final ClusterRuntimeStats clusterStats = new ClusterRuntimeStats();

    /** Max serialized payload size (bytes) eligible for cluster publish. 0 = unlimited. Default 1 MiB. */
    private volatile int messageMaxSizeBytes = 1048576;

    /** Policy when a cluster publish/unicast fails or a message is too large. Default LOG. */
    private volatile ClusterProperties.OnPublishFailure onPublishFailure = ClusterProperties.OnPublishFailure.LOG;

    /** Policy when the cluster transport is lost. Default DEGRADE_TO_LOCAL. */
    private volatile ClusterProperties.OnRedisLoss onRedisLoss = ClusterProperties.OnRedisLoss.DEGRADE_TO_LOCAL;

    /** Max time (ms) the synchronous unicast-path registry lookup may block. Default 2000. */
    private volatile long nodeLookupTimeoutMs = 2000;

    /** Reliable (at-least-once) broadcast broker; null when reliable.enable=false. */
    private volatile ReliableBroker reliableBroker;
    /** Optional W3C trace propagation (null = disabled). */
    private volatile ClusterTraceContext traceContext;
    /** Active reliable subscriptions keyed by URI. */
    private final ConcurrentHashMap<String, ClusterSubscription>
            reliableSubscriptions = new ConcurrentHashMap<>();

    /** Room registry; null when room.enable=false (no room path; runtime behavior identical to 1.9.0,
     *  though the envelope wire is globally v2 since 1.10.0 — 1.9.0 nodes discard v2 on the version gate). */
    private volatile ClusterRoomRegistry roomRegistry;
    /** TTL (ms) for the per-room node-set send-path cache (mirrors {@link #nodeCacheTtlMs}). */
    private volatile long roomNodeSetCacheTtlMs = 5000;
    /** Send-path cache: (uri|room) → cached node-set. TTL governs reuse; invalidated wholesale on NODE_LEFT
     *  (any room could have hosted the departed node). Bounded by distinct (uri,room) seen on the send path. */
    private final ConcurrentHashMap<String, CachedNodeSet> roomNodeSetCache = new ConcurrentHashMap<>();

    /** userId→sessions presence index; null when offline.enable=false (no user-addressed path). 1.10.0-RC2. */
    private volatile UserRegistry userRegistry;
    /** Per-user durable offline queue; null when offline.enable=false. 1.10.0-RC2. */
    private volatile OfflineQueueStore offlineStore;

    /** Reserved broker channel for presence-change events (1.10.0-RC3). Forbidden as an app {@code @MessageMapping}
     *  URI (the auto-config fails fast if one collides). Only RC3 nodes subscribe to it, so an RC2 node never
     *  decodes a {@code PRESENCE_CHANGE} kind — that is the rolling-upgrade safety, not a version bump. */
    public static final String PRESENCE_CHANNEL = "__netty_cluster_presence__";

    /** Per-device presence index; null when presence.enable=false. 1.10.0-RC3. */
    private volatile PresenceRegistry presenceRegistry;
    /** App callback on aggregate presence transitions; null = no app listener. 1.10.0-RC3. */
    private volatile PresenceChangeListener presenceChangeListener;
    /** Whether transitions are broadcast cluster-wide (presence.publish-changes). 1.10.0-RC3. */
    private volatile boolean presencePublishChanges = true;
    /** Resolves a session's userId for the {@code setPresence(session,...)} API; null when identity is off. RC3. */
    private volatile UserIdResolver userIdResolver;

    public ClusterMessageSender(MessageSender localSender,
                                ClusterBroker broker,
                                SessionRegistry registry,
                                ClusterNodeManager nodeManager,
                                long nodeCacheTtlMs,
                                MessagePayloadCodec payloadCodec) {
        this.localSender = localSender;
        this.broker = broker;
        this.registry = registry;
        this.nodeManager = nodeManager;
        this.nodeCacheTtlMs = nodeCacheTtlMs;
        this.payloadCodec = payloadCodec;
    }

    /**
     * Legacy constructor for backward compatibility (uses DefaultMessagePayloadCodec).
     */
    public ClusterMessageSender(MessageSender localSender,
                                ClusterBroker broker,
                                SessionRegistry registry,
                                ClusterNodeManager nodeManager,
                                long nodeCacheTtlMs) {
        this(localSender, broker, registry, nodeManager, nodeCacheTtlMs,
                new com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.DefaultMessagePayloadCodec());
    }

    // ---- Optional behavioral knobs (injected by auto-config from ClusterProperties) ----

    /** Sets the max serialized payload size in bytes eligible for cluster publish (0 = unlimited). */
    public void setMessageMaxSizeBytes(int messageMaxSizeBytes) {
        this.messageMaxSizeBytes = messageMaxSizeBytes;
    }

    /** Sets the policy applied when a cluster publish/unicast fails or a message is too large. */
    public void setOnPublishFailure(ClusterProperties.OnPublishFailure onPublishFailure) {
        if (onPublishFailure != null) {
            this.onPublishFailure = onPublishFailure;
        }
    }

    /** Sets the policy applied when the cluster transport is lost. */
    public void setOnRedisLoss(ClusterProperties.OnRedisLoss onRedisLoss) {
        if (onRedisLoss != null) {
            this.onRedisLoss = onRedisLoss;
        }
    }

    /** Sets the max time (ms) the synchronous unicast-path registry lookup may block. */
    public void setNodeLookupTimeoutMs(long nodeLookupTimeoutMs) {
        if (nodeLookupTimeoutMs > 0) {
            this.nodeLookupTimeoutMs = nodeLookupTimeoutMs;
        }
    }

    /**
     * Sets the max number of entries retained in the unicast-path node-lookup cache (LRU eviction past the
     * cap). Must be called before {@link #start()} / first use. {@code <= 0} = unbounded (legacy behavior).
     * Rebuilds the backing map; safe at config time (no concurrent traffic yet).
     */
    public void setRegistryReadCacheMaxSize(int maxSize) {
        Map<String, CachedNodeLookup> rebuilt = maxSize > 0
                ? newBoundedNodeCache(maxSize)
                : Collections.synchronizedMap(new HashMap<>());
        rebuilt.putAll(this.nodeCache);
        this.nodeCache = rebuilt;
    }

    /** Test/diagnostic hook: current number of entries in the unicast node-lookup cache. */
    int nodeCacheSize() {
        return nodeCache.size();
    }

    /** Builds an access-order LRU map (synchronized) that evicts the eldest entry past {@code maxSize}. */
    private static Map<String, CachedNodeLookup> newBoundedNodeCache(int maxSize) {
        return Collections.synchronizedMap(new LinkedHashMap<String, CachedNodeLookup>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedNodeLookup> eldest) {
                return size() > maxSize;
            }
        });
    }

    /** Injects the reliable broker (enables reliableBroadcast). Null = reliable delivery disabled. */
    public void setReliableBroker(ReliableBroker reliableBroker) {
        this.reliableBroker = reliableBroker;
    }

    /** Inject the W3C trace context (cross-node traceparent propagation). Null disables it. */
    public void setTraceContext(ClusterTraceContext traceContext) {
        this.traceContext = traceContext;
    }

    /** Injects the room registry (enables {@link RoomOperations}). Null = room routing disabled
     *  ({@code room.enable=false} — no room path; runtime behavior identical to 1.9.0, though the
     *  envelope wire is globally v2 since 1.10.0 and 1.9.0 nodes discard v2 on the version gate). */
    public void setRoomRegistry(ClusterRoomRegistry roomRegistry) {
        this.roomRegistry = roomRegistry;
    }

    /** Sets the per-room node-set send-path cache TTL (ms). Bound from
     *  {@code server.netty.websocket.cluster.room.node-set-cache-ttl-ms}. */
    public void setRoomNodeSetCacheTtlMs(long roomNodeSetCacheTtlMs) {
        if (roomNodeSetCacheTtlMs >= 0) {
            this.roomNodeSetCacheTtlMs = roomNodeSetCacheTtlMs;
        }
    }

    /** Whether room routing is active (a {@link ClusterRoomRegistry} is wired). */
    public boolean isRoomEnabled() {
        return roomRegistry != null;
    }

    /** Injects the user presence index (enables {@link UserOperations}). Null = user-addressed path disabled
     *  ({@code offline.enable=false}). 1.10.0-RC2. */
    public void setUserRegistry(UserRegistry userRegistry) {
        this.userRegistry = userRegistry;
    }

    /** Injects the offline queue store (the {@code sendToUser} backfill fallback). Null = disabled. 1.10.0-RC2. */
    public void setOfflineQueueStore(OfflineQueueStore offlineStore) {
        this.offlineStore = offlineStore;
    }

    /** Whether the user-addressed / offline path is active (a {@link UserRegistry} is wired). */
    public boolean isOfflineEnabled() {
        return userRegistry != null && offlineStore != null;
    }

    /** Injects the presence index (enables {@link PresenceOperations}). Null = presence disabled. 1.10.0-RC3. */
    public void setPresenceRegistry(PresenceRegistry presenceRegistry) {
        this.presenceRegistry = presenceRegistry;
    }

    /** Injects the app presence-change listener (null = no app listener). 1.10.0-RC3. */
    public void setPresenceChangeListener(PresenceChangeListener presenceChangeListener) {
        this.presenceChangeListener = presenceChangeListener;
    }

    /** Whether aggregate transitions are broadcast cluster-wide (presence.publish-changes). 1.10.0-RC3. */
    public void setPresencePublishChanges(boolean presencePublishChanges) {
        this.presencePublishChanges = presencePublishChanges;
    }

    /** Injects the userId resolver used by {@link #setPresence(MessageSession, PresenceStatus)}. 1.10.0-RC3. */
    public void setUserIdResolver(UserIdResolver userIdResolver) {
        this.userIdResolver = userIdResolver;
    }

    /** Whether presence is active (a {@link PresenceRegistry} is wired). */
    public boolean isPresenceEnabled() {
        return presenceRegistry != null;
    }

    /** Current traceparent for an outgoing envelope, or null when propagation is off. */
    private String currentTraceparent() {
        ClusterTraceContext tc = this.traceContext;
        return tc != null ? tc.currentTraceparent() : null;
    }

    /** Restore scope for an incoming envelope's traceparent, or NOOP when propagation is off. */
    private ClusterTraceContext.Scope traceScope(ClusterEnvelope envelope) {
        ClusterTraceContext tc = this.traceContext;
        return tc != null ? tc.restore(envelope.getTraceparent()) : ClusterTraceContext.NOOP;
    }

    /**
     * Starts cluster subscriptions: subscribe to unicast channel for this node,
     * and to broadcast channels for all locally registered URIs.
     */
    public void start() {
        String nodeId = nodeManager.getNodeId();

        // Wire reconciliation→cache invalidation (I-3 fix)
        nodeManager.setDeadNodeCallback(this::invalidateCacheForNode);

        // Event-driven degradation (S1): the broker notifies us the instant the transport
        // connection drops/recovers, so we degrade/recover immediately instead of waiting up to
        // a heartbeat interval. The heartbeat probe remains as a backstop for logical failures
        // (transport up but auth/role wrong) that a connection event would miss.
        broker.setTransportStateListener(new ClusterBroker.TransportStateListener() {
            @Override
            public void onTransportLost() {
                nodeManager.onTransportLost();
            }
            @Override
            public void onTransportRestored() {
                nodeManager.onTransportRestored();
            }
        });

        // Wire onRedisLoss=CLOSE_ALL: when the node degrades (transport lost), optionally
        // close all local sessions instead of keeping them alive (DEGRADE_TO_LOCAL default).
        nodeManager.addStateListener((node, from, to) -> {
            if (to == NodeState.DEGRADED && from == NodeState.ACTIVE
                    && onRedisLoss == ClusterProperties.OnRedisLoss.CLOSE_ALL) {
                closeAllLocalSessions();
            }
        });

        // Subscribe to unicast messages targeted at this node
        unicastSubscription = broker.subscribeUnicast(nodeId, this::onUnicastMessage);

        // Subscribe to broadcast channels for URIs that have local sessions
        for (String uri : localSender.getRegisteredUri()) {
            subscribeBroadcast(uri);
        }

        // Reliable (Streams) subscriptions for locally-active URIs (only when reliable.enable wired a broker)
        if (reliableBroker != null) {
            for (String uri : localSender.getRegisteredUri()) {
                subscribeReliable(uri);
            }
        }

        // Presence: subscribe to the reserved presence channel UNCONDITIONALLY (not driven by getRegisteredUri) so a
        // node with zero local sessions still receives presence events for users it watches (RC3). Dedicated
        // listener — presence is NOT routed through onBroadcastMessage (which would mis-deliver it as an app message).
        if (presenceRegistry != null) {
            broker.subscribe(PRESENCE_CHANNEL, this::onPresenceMessage);
        }

        log.info("ClusterMessageSender started for node {} — {} URI broadcast subscriptions",
                nodeId, broadcastSubscriptions.size());
    }

    // ==================== MessageSender — local-only queries (unchanged semantics) ====================

    @Override
    public int getSessionNums() {
        return localSender.getSessionNums();
    }

    @Override
    public int getSessionNums(String uri) {
        return localSender.getSessionNums(uri);
    }

    @Override
    public Set<String> getSessionIds(String uri) {
        return localSender.getSessionIds(uri);
    }

    @Override
    public MessageSession getSession(String uri, String sessionId) {
        return localSender.getSession(uri, sessionId);
    }

    @Override
    public Map<String, MessageSession> getSessions(String uri) {
        return localSender.getSessions(uri);
    }

    @Override
    public Set<String> getRegisteredUri() {
        return localSender.getRegisteredUri();
    }

    @Override
    public boolean isSessionAlive(String uri, String... sessionIds) {
        return localSender.isSessionAlive(uri, sessionIds);
    }

    @Override
    public MessageSenderRuntimeStats getRuntimeStats() {
        return localSender.getRuntimeStats();
    }

    // ==================== Cluster-wide async queries (NEW in 1.8.0) ====================

    /**
     * Returns all session ids for a URI across the entire cluster (async, network call).
     */
    public CompletionStage<Set<String>> getClusterSessionIds(String uri) {
        return registry.clusterSessionIds(uri);
    }

    /**
     * Checks if a session is alive anywhere in the cluster (async, network call).
     */
    public CompletionStage<Boolean> isSessionAliveCluster(String uri, String sessionId) {
        return registry.lookupNode(uri, sessionId)
                .thenApply(nodeId -> nodeId != null);
    }

    // ==================== Broadcast (local + cluster fan-out) ====================

    @Override
    public void topicMessage(String uri, AbstractMessage message) throws MessageUriNotDefinedException {
        // 1. Local fan-out first (always, even if broker is degraded)
        localSender.topicMessage(uri, message);

        // 2. Publish to cluster broker (at-most-once) — skip if degraded.
        // P1 (RC14): also gate on broker.state()==ACTIVE so during the redis-loss-grace-period-ms
        // debounce window (transport lost but node state machine still ACTIVE) we don't issue a
        // publish the broker would refuse anyway. Mirrors the L6 gate in sendMessage().
        if (nodeManager.getState() == NodeState.ACTIVE && broker.state() == BrokerState.ACTIVE) {
            ClusterEnvelope envelope = buildBroadcastEnvelope(uri, message);
            if (exceedsSizeLimit(envelope)) {
                handlePublishFailure("broadcast for URI " + uri + " exceeds messageMaxSizeBytes ("
                        + envelope.getPayload().length + " > " + messageMaxSizeBytes + ")", null);
                return;
            }
            try {
                broker.publish(uri, envelope);
                clusterStats.broadcastPublished.incrementAndGet();
            } catch (ClusterBrokerException e) {
                handlePublishFailure("broadcast for URI " + uri
                        + " — local delivery succeeded, remote nodes may not have received it", e);
            }
        } else {
            // Node not ACTIVE (DEGRADED/RESYNC/...): local fan-out already happened above,
            // but the cross-node copy is dropped (at-most-once, degrade-to-local). Count it
            // so the loss is visible/quantifiable rather than silent.
            clusterStats.broadcastsSkippedDegraded.incrementAndGet();
            log.debug("Cluster broadcast cross-node copy skipped for URI {} — node state is {}, broker state is {} "
                    + "(local delivery succeeded; remote nodes will not receive this message)",
                    uri, nodeManager.getState(), broker.state());
        }
    }

    // ==================== Targeted send (local or cluster unicast) ====================

    @Override
    public void sendMessage(String uri, AbstractMessage message, String... sessionIds)
            throws MessageUriNotDefinedException, MessageSessionClosedException {

        List<String> localIds = new ArrayList<>();
        // Resolve each remote session's owning node EXACTLY ONCE here (no second lookup in the
        // send loop) — avoids a TOCTOU where a cache expiry/NODE_LEFT between the two lookups
        // would reclassify a live remote session as closed, and halves registry read load.
        Map<String, String> remoteSessionToNode = new LinkedHashMap<>();
        List<String> closedIds = new ArrayList<>();

        // S2: when degraded (Redis lost) the broker can't deliver cross-node anyway — short-circuit
        // remote sessions to "closed" WITHOUT a registry lookup, so a Redis outage never blocks the
        // unicast hot path on a registry round-trip.
        // L6: also gate on broker.state()==ACTIVE so during the redis-loss-grace-period-ms debounce
        // window (transport lost but node state machine still ACTIVE) we don't waste a bounded
        // registry lookup the broker can't act on anyway.
        boolean active = nodeManager.getState() == NodeState.ACTIVE
                      && broker.state() == BrokerState.ACTIVE;

        for (String sessionId : sessionIds) {
            MessageSession localSession = localSender.getSession(uri, sessionId);
            if (localSession != null) {
                localIds.add(sessionId);
            } else if (!active) {
                closedIds.add(sessionId);
            } else {
                String targetNodeId = lookupNodeCached(uri, sessionId);
                if (targetNodeId != null) {
                    remoteSessionToNode.put(sessionId, targetNodeId);
                } else {
                    closedIds.add(sessionId);
                }
            }
        }

        // Send to local sessions
        if (!localIds.isEmpty()) {
            try {
                localSender.sendMessage(uri, message, localIds.toArray(new String[0]));
            } catch (MessageSessionClosedException e) {
                closedIds.addAll(e.getSessionIds());
            }
        }

        // Unicast to remote sessions via broker (using the nodeId resolved above)
        if (!remoteSessionToNode.isEmpty()
                && nodeManager.getState() == NodeState.ACTIVE
                && broker.state() == BrokerState.ACTIVE) {
            for (Map.Entry<String, String> entry : remoteSessionToNode.entrySet()) {
                String sessionId = entry.getKey();
                String targetNodeId = entry.getValue();
                ClusterEnvelope envelope = buildUnicastEnvelope(uri, sessionId, message);
                if (exceedsSizeLimit(envelope)) {
                    handlePublishFailure("unicast for session " + sessionId
                            + " exceeds messageMaxSizeBytes (" + envelope.getPayload().length
                            + " > " + messageMaxSizeBytes + ")", null);
                    closedIds.add(sessionId);
                    continue;
                }
                try {
                    broker.unicast(targetNodeId, envelope);
                    clusterStats.unicastSent.incrementAndGet();
                } catch (ClusterBrokerException e) {
                    handlePublishFailure("unicast for session " + sessionId + " on node " + targetNodeId, e);
                    closedIds.add(sessionId);
                }
            }
        } else if (!remoteSessionToNode.isEmpty()) {
            // Broker degraded — treat remote sessions as unreachable
            closedIds.addAll(remoteSessionToNode.keySet());
        }

        if (!closedIds.isEmpty()) {
            throw new MessageSessionClosedException(uri, closedIds);
        }
    }

    // ==================== Close session (local or cluster) ====================

    /**
     * Closes a WebSocket session by id. Tries local first; if not local and the cluster transport is ACTIVE,
     * looks up the owning node and dispatches a CLOSE control message.
     *
     * @return {@code true} if the close was definitely actioned (locally or via cross-node CLOSE);
     *         {@code false} otherwise. <strong>A {@code false} return is overloaded:</strong> it means
     *         EITHER no such session exists locally AND no remote owner was found, OR the cluster
     *         transport is degraded (Redis-loss grace period, broker DEGRADED) and the cross-node
     *         lookup was short-circuited. Callers cannot distinguish these cases — mirrors the L6
     *         {@link #sendMessage} semantics from 1.9.0-RC12.
     */
    @Override
    public boolean closeSession(String uri, String sessionId, int statusCode, String reasonText)
            throws MessageUriNotDefinedException {
        // Try local first
        boolean closedLocally = localSender.closeSession(uri, sessionId, statusCode, reasonText);
        if (closedLocally) {
            return true;
        }

        // Not local — send close intent to the owning node via unicast.
        // P1 (RC14): also gate on broker.state()==ACTIVE so during the redis-loss-grace-period-ms
        // debounce window (transport lost but node state machine still ACTIVE) we don't waste a
        // bounded registry lookup the broker can't unicast anyway. Mirrors the L6 gate in sendMessage().
        if (nodeManager.getState() == NodeState.ACTIVE && broker.state() == BrokerState.ACTIVE) {
            String targetNodeId = lookupNodeCached(uri, sessionId);
            if (targetNodeId != null) {
                try {
                    // Build a CLOSE control envelope (distinct from UNICAST data messages)
                    String closePayload = statusCode + "|" + reasonText;
                    ClusterEnvelope envelope = new ClusterEnvelope(
                            nodeManager.getNodeId(), uri,
                            ClusterEnvelope.MessageKind.CLOSE,
                            closePayload.getBytes(StandardCharsets.UTF_8),
                            sessionId, currentTraceparent(), System.currentTimeMillis());
                    broker.unicast(targetNodeId, envelope);
                    return true;
                } catch (ClusterBrokerException e) {
                    log.warn("Failed to send cluster close for session {}", sessionId, e);
                }
            }
        }
        return false;
    }

    // ==================== Shutdown ====================

    @Override
    public void shutdown() {
        // Tear down only what this sender owns: the cluster subscriptions.
        // The local sender, broker, registry and node manager are independent Spring
        // beans with their own destroyMethod lifecycle — shutting them down here would
        // double-shut-down them (Spring also calls their destroy methods).
        for (ClusterSubscription sub : broadcastSubscriptions.values()) {
            sub.unsubscribe();
        }
        broadcastSubscriptions.clear();

        for (ClusterSubscription sub : reliableSubscriptions.values()) {
            sub.unsubscribe();
        }
        reliableSubscriptions.clear();

        if (unicastSubscription != null) {
            unicastSubscription.unsubscribe();
        }

        // The room registry, user registry and offline store are independent Spring beans
        // (destroyMethod="shutdown") with their own lifecycle — shutting them down here would
        // double-shut-down them. Only drop our own cache.
        roomNodeSetCache.clear();

        log.info("ClusterMessageSender shut down for node {}", nodeManager.getNodeId());
    }

    // ==================== Subscription callbacks ====================

    private void onBroadcastMessage(ClusterEnvelope envelope) {
        // Self-delivery suppression (CRITICAL — prevents duplicate delivery)
        if (nodeManager.getNodeId().equals(envelope.getOriginNodeId())) {
            // This node already did local fan-out before publishing; discard the echo.
            clusterStats.selfDeliveryDropped.incrementAndGet();
            return;
        }
        clusterStats.crossNodeBroadcastReceived.incrementAndGet();

        // Deserialize and deliver to all local sessions for this URI
        try (ClusterTraceContext.Scope ts = traceScope(envelope)) {
            AbstractMessage message = deserializePayload(envelope.getPayload());
            localSender.topicMessage(envelope.getUri(), message);
        } catch (Exception e) {
            log.warn("Failed to deliver cluster broadcast for URI {}", envelope.getUri(), e);
        }
    }

    private void onUnicastMessage(ClusterEnvelope envelope) {
        String sessionId = envelope.getTargetSessionId();
        String uri = envelope.getUri();
        try (ClusterTraceContext.Scope ts = traceScope(envelope)) {
            // Dispatch based on envelope kind: room broadcast, data message, or control command.
            // ROOM_BROADCAST rides the same per-node unicast channel (no separate subscription) — the
            // sender targeted this node because it hosts ≥1 member of the room.
            if (envelope.getKind() == ClusterEnvelope.MessageKind.ROOM_BROADCAST) {
                onRoomMessage(envelope);
                return;
            }

            // Dispatch based on envelope kind: data message vs control command
            if (envelope.getKind() == ClusterEnvelope.MessageKind.CLOSE) {
                // Remote close command — parse "statusCode|reasonText" and close locally
                try {
                    String closePayload = new String(envelope.getPayload(), StandardCharsets.UTF_8);
                    int sep = closePayload.indexOf('|');
                    int statusCode = sep > 0 ? Integer.parseInt(closePayload.substring(0, sep)) : 1000;
                    String reasonText = sep > 0 ? closePayload.substring(sep + 1) : "Remote close";
                    localSender.closeSession(uri, sessionId, statusCode, reasonText);
                } catch (Exception e) {
                    log.warn("Failed to execute remote close for session {}", sessionId, e);
                }
                return;
            }

            // Regular data message (UNICAST kind)
            try {
                AbstractMessage message = deserializePayload(envelope.getPayload());
                localSender.sendMessage(uri, message, sessionId);
            } catch (MessageSessionClosedException e) {
                log.debug("Unicast target session {} not found locally — may have disconnected", sessionId);
            } catch (Exception e) {
                log.warn("Failed to deliver cluster unicast for session {}", sessionId, e);
            }
        }
    }

    // ==================== Room-scoped routing (1.10.0) ====================

    @Override
    public void joinRoom(String uri, String room, String sessionId) {
        ClusterRoomRegistry rr = requireRoomRegistry();
        rr.join(uri, room, sessionId, nodeManager.getNodeId());
        clusterStats.addRoomLocalMemberships(1);
    }

    @Override
    public void leaveRoom(String uri, String room, String sessionId) {
        ClusterRoomRegistry rr = requireRoomRegistry();
        rr.leave(uri, room, sessionId, nodeManager.getNodeId());
        clusterStats.addRoomLocalMemberships(-1);
    }

    /**
     * Removes a local session from ALL its rooms (single distributed call). Called on local disconnect.
     * No-op when room routing is disabled.
     */
    public void removeAllRoomsForSession(String uri, String sessionId) {
        ClusterRoomRegistry rr = this.roomRegistry;
        if (rr != null) {
            // Count the session's rooms (local index, no I/O) BEFORE the removal so the gauge decrements
            // by the right amount.
            int wasIn = rr.roomsForSession(uri, sessionId).size();
            rr.removeAllForSession(uri, sessionId, nodeManager.getNodeId());
            if (wasIn > 0) {
                clusterStats.addRoomLocalMemberships(-wasIn);
            }
        }
    }

    @Override
    public void roomMessage(String uri, String room, AbstractMessage message)
            throws MessageUriNotDefinedException {
        ClusterRoomRegistry rr = requireRoomRegistry();

        // 1. Local fan-out FIRST (always, even when degraded) — same contract as topicMessage.
        deliverToLocalRoomMembers(uri, room, message, rr.localMembers(uri, room));

        // 2. Gate on node + broker ACTIVE (mirrors topicMessage's L6/P1 gate). When degraded, the
        //    cross-node copy is dropped (at-most-once, degrade-to-local) — local fan-out already happened.
        if (nodeManager.getState() != NodeState.ACTIVE || broker.state() != BrokerState.ACTIVE) {
            clusterStats.broadcastsSkippedDegraded.incrementAndGet();
            log.debug("Room broadcast cross-node copy skipped for URI {} room {} — node state {}, broker state {}",
                    uri, room, nodeManager.getState(), broker.state());
            return;
        }

        // 3. Resolve the room's node-set (cached, minus self) — the routing primitive.
        Set<String> targets = nodesForRoomCached(uri, room);

        // 4. Build the ROOM_BROADCAST envelope once (HMAC + traceparent applied per-broker on publish).
        ClusterEnvelope envelope = buildRoomEnvelope(uri, room, message);
        if (exceedsSizeLimit(envelope)) {
            handlePublishFailure("room broadcast for URI " + uri + " room " + room
                    + " exceeds messageMaxSizeBytes (" + envelope.getPayload().length
                    + " > " + messageMaxSizeBytes + ")", null);
            return;
        }

        // 5. Target only the nodes hosting members — reuse the per-node unicast channel (the same channel
        //    subscribeUnicast listens on). Self is excluded (we already did local fan-out above).
        String self = nodeManager.getNodeId();
        int sent = 0;
        for (String targetNodeId : targets) {
            if (self.equals(targetNodeId)) {
                continue; // origin self-suppression: local fan-out already covered this node
            }
            try {
                broker.unicast(targetNodeId, envelope);
                sent++;
            } catch (ClusterBrokerException e) {
                handlePublishFailure("room broadcast for URI " + uri + " room " + room
                        + " to node " + targetNodeId, e);
            }
        }
        clusterStats.roomBroadcastPublished.incrementAndGet();
        clusterStats.recordRoomFanoutTargets(sent);
    }

    /** Receive side: a ROOM_BROADCAST arrived on this node's unicast channel. Suppress the origin echo,
     *  then fan out to LOCAL members of the room only. A node that no longer hosts members (membership
     *  churned in-flight) fans out to an empty set — counted as a stale-target waste meter. */
    private void onRoomMessage(ClusterEnvelope envelope) {
        if (nodeManager.getNodeId().equals(envelope.getOriginNodeId())) {
            // Shouldn't normally happen (we exclude self when targeting), but guard against an echo.
            clusterStats.selfDeliveryDropped.incrementAndGet();
            return;
        }
        ClusterRoomRegistry rr = this.roomRegistry;
        if (rr == null) {
            // Room routing disabled locally but a ROOM_BROADCAST arrived — nothing to fan out to.
            return;
        }
        clusterStats.roomBroadcastReceived.incrementAndGet();
        String uri = envelope.getUri();
        String room = envelope.getRoom();
        Set<String> localSessionIds = rr.localMembers(uri, room);
        if (localSessionIds.isEmpty()) {
            // Membership churned in-flight: we were targeted but no longer host members. Honest waste meter.
            clusterStats.roomFanoutStaleTarget.incrementAndGet();
            return;
        }
        try {
            AbstractMessage message = deserializePayload(envelope.getPayload());
            deliverToLocalRoomMembers(uri, room, message, localSessionIds);
        } catch (Exception e) {
            log.warn("Failed to deliver room broadcast for URI {} room {}", uri, room, e);
        }
    }

    /** Local-only fan-out of a room message to the given member sessionIds via the local sender. Closed
     *  members are tolerated (they may have just disconnected). No-op on an empty set. */
    private void deliverToLocalRoomMembers(String uri, String room, AbstractMessage message,
                                           Set<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        try {
            localSender.sendMessage(uri, message, sessionIds.toArray(new String[0]));
        } catch (MessageSessionClosedException e) {
            log.debug("Some room members on URI {} room {} were closed mid-delivery: {}",
                    uri, room, e.getSessionIds());
        }
    }

    /** Returns the room's node-set from the short-TTL send-path cache, refreshing on miss/expiry. */
    private Set<String> nodesForRoomCached(String uri, String room) {
        ClusterRoomRegistry rr = this.roomRegistry;
        String key = uri + "|" + room;
        CachedNodeSet cached = roomNodeSetCache.get(key);
        if (cached != null && !cached.isExpired(roomNodeSetCacheTtlMs)) {
            return cached.nodes;
        }
        try {
            Set<String> nodes = rr.nodesForRoom(uri, room).toCompletableFuture()
                    .get(nodeLookupTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            Set<String> snapshot = nodes == null ? java.util.Collections.emptySet()
                    : new java.util.HashSet<>(nodes);
            roomNodeSetCache.put(key, new CachedNodeSet(snapshot, System.currentTimeMillis()));
            return snapshot;
        } catch (Exception e) {
            log.warn("nodesForRoom lookup failed for URI {} room {} — targeting no remote nodes this round",
                    uri, room, e);
            return java.util.Collections.emptySet();
        }
    }

    private ClusterEnvelope buildRoomEnvelope(String uri, String room, AbstractMessage message) {
        return new ClusterEnvelope(
                nodeManager.getNodeId(), uri,
                ClusterEnvelope.MessageKind.ROOM_BROADCAST,
                serializePayload(message),
                null, currentTraceparent(), System.currentTimeMillis(), room);
    }

    private ClusterRoomRegistry requireRoomRegistry() {
        ClusterRoomRegistry rr = this.roomRegistry;
        if (rr == null) {
            throw new IllegalStateException("Room routing is disabled; set "
                    + "server.netty.websocket.cluster.room.enable=true to use room operations");
        }
        return rr;
    }

    // ==================== User-addressed delivery + offline queue (1.10.0-RC2) ====================

    @Override
    public void sendToUser(String userId, AbstractMessage message) {
        UserRegistry ur = this.userRegistry;
        OfflineQueueStore store = this.offlineStore;
        if (ur == null || store == null) {
            throw new IllegalStateException("User-addressed delivery is disabled; set "
                    + "server.netty.websocket.cluster.offline.enable=true to use sendToUser()");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }

        // FRESH presence lookup — NOT cached (caching would let a just-disconnected user read "online" →
        // fire-and-forget unicast to a dead session → no exception → no fallback → silent loss). The
        // sendToUser path is comparatively cold, so the round-trip is affordable. (Spec §5/§6 + decision record.)
        Set<SessionRef> sessions;
        try {
            sessions = ur.sessionsForUser(userId).toCompletableFuture()
                    .get(nodeLookupTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Presence lookup failed/timed out — treat the user as offline and enqueue (never lose the message).
            log.warn("sessionsForUser({}) failed — enqueuing to the offline queue", userId, e);
            sessions = java.util.Collections.emptySet();
        }

        if (sessions == null || sessions.isEmpty()) {
            // Offline cluster-wide → store for backfill on reconnect.
            enqueueOffline(userId, message);
            return;
        }

        // Online → unicast to each live session (reuse the RC1/1.9.0 per-node unicast path). A LOCAL
        // MessageSessionClosedException at send time means that session just went away; track whether ANY
        // delivery was attempted-without-local-close so we know if a send-time fallback enqueue is needed.
        boolean anyDelivered = false;
        for (SessionRef ref : sessions) {
            try {
                sendMessage(ref.getUri(), message, ref.getSessionId());
                anyDelivered = true;
            } catch (MessageSessionClosedException e) {
                // Send-time local close on a bound session — counted distinctly from post-accept loss.
                clusterStats.incUnicastFailures();
                log.debug("sendToUser({}): session {} on URI {} closed at send time", userId, ref.getSessionId(), ref.getUri());
            } catch (Exception e) {
                clusterStats.incUnicastFailures();
                log.warn("sendToUser({}): unicast to session {} on URI {} failed", userId, ref.getSessionId(), ref.getUri(), e);
            }
        }

        if (anyDelivered) {
            clusterStats.incSendToUserRealtime();
        } else {
            // NO session remained reachable (all bound sessions closed at send time) → send-time fallback enqueue.
            enqueueOffline(userId, message);
        }
    }

    /** Enqueues a message to the user's offline queue; on enqueue failure counts {@code fallbackEnqueueFailures}
     *  and logs ERROR (never a silent drop). Increments the {@code queued} meter on success. */
    private void enqueueOffline(String userId, AbstractMessage message) {
        OfflineQueueStore store = this.offlineStore;
        if (store == null) {
            clusterStats.incFallbackEnqueueFailures();
            log.error("sendToUser({}): offline store unavailable — message NOT queued (dropped)", userId);
            return;
        }
        ClusterEnvelope envelope = buildUnicastEnvelope(null, null, message);
        try {
            store.enqueue(userId, envelope).toCompletableFuture()
                    .get(nodeLookupTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            clusterStats.incOfflineEnqueued();
            clusterStats.incSendToUserQueued();
        } catch (Exception e) {
            // Enqueue itself failed after all unicast paths — surface loudly; never silently drop.
            clusterStats.incFallbackEnqueueFailures();
            log.error("sendToUser({}): offline enqueue FAILED — message NOT delivered and NOT queued", userId, e);
        }
    }

    @Override
    public CompletionStage<Boolean> isUserOnline(String userId) {
        UserRegistry ur = this.userRegistry;
        if (ur == null) {
            throw new IllegalStateException("User-addressed delivery is disabled; set "
                    + "server.netty.websocket.cluster.offline.enable=true to use isUserOnline()");
        }
        return ur.isUserOnline(userId);
    }

    // ==================== PresenceOperations (1.10.0-RC3) ====================

    @Override
    public CompletionStage<Void> setPresence(MessageSession session, PresenceStatus status) {
        requirePresence();
        String userId = userIdResolver != null ? safeResolve(session) : null;
        if (userId == null) {
            // Anonymous (no resolver / unresolved): no presence identity — no-op (not an error).
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        return setPresenceFromHook(userId, nodeManager.getNodeId(), session.getSessionId(), status);
    }

    @Override
    public CompletionStage<Void> setPresenceForUser(String userId, PresenceStatus status) {
        requirePresence();
        return presenceRegistry.setPresenceForUser(userId, status)
                .thenAccept(t -> firePresenceTransition(userId, t));
    }

    @Override
    public CompletionStage<UserPresence> getPresence(String userId) {
        requirePresence();
        return presenceRegistry.getPresence(userId);
    }

    private void requirePresence() {
        if (presenceRegistry == null) {
            throw new IllegalStateException("Presence is disabled; set "
                    + "server.netty.websocket.cluster.presence.enable=true to use PresenceOperations");
        }
    }

    private String safeResolve(MessageSession session) {
        try {
            return userIdResolver.resolve(session);
        } catch (Exception e) {
            log.warn("UserIdResolver threw during setPresence for session {}", session.getSessionId(), e);
            return null;
        }
    }

    /** Internal: set a connection's status on connect or via the API; fires the transition. Called by the hook. */
    public CompletionStage<Void> setPresenceFromHook(String userId, String nodeId, String sessionId, PresenceStatus status) {
        if (presenceRegistry == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        clusterStats.incPresenceSet();
        return presenceRegistry.setPresence(userId, nodeId, sessionId, status)
                .thenAccept(t -> firePresenceTransition(userId, t));
    }

    /** Internal: clear a connection on disconnect; fires the transition. Called by the hook. */
    public CompletionStage<Void> clearPresenceFromHook(String userId, String nodeId, String sessionId) {
        if (presenceRegistry == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        return presenceRegistry.clearPresence(userId, nodeId, sessionId)
                .thenAccept(t -> firePresenceTransition(userId, t));
    }

    /**
     * Fires an aggregate presence transition (only when it actually changed): local-first the app listener, then
     * publish to the reserved channel for OTHER nodes (the origin self-suppresses its own echo in
     * {@link #onPresenceMessage}). This is the single fire-site, reused by the connect hook and the dead-node reap.
     */
    private void firePresenceTransition(String userId, PresenceTransition t) {
        if (t == null || !t.changed()) {
            return;
        }
        clusterStats.incPresenceChanges();
        PresenceChangeListener l = this.presenceChangeListener;
        if (l != null) {
            try {
                l.onPresenceChange(userId, t.getOldAggregate(), t.getNewAggregate());
            } catch (Exception e) {
                log.warn("PresenceChangeListener threw for user {} ({}->{})",
                        userId, t.getOldAggregate(), t.getNewAggregate(), e);
            }
        }
        if (presencePublishChanges) {
            // base64url-encode the userId (the only field that can contain '|') so a userId like "tenant|alice"
            // does not corrupt the pipe-delimited event body and silently drop the event on remote nodes — mirrors
            // the storage-side delimiter-safe encoding (RedisPresenceRegistry/RedisUserRegistry). RC3 impl-review fix.
            String payload = b64UserId(userId) + "|" + t.getOldAggregate().name() + "|" + t.getNewAggregate().name();
            ClusterEnvelope env = new ClusterEnvelope(nodeManager.getNodeId(), PRESENCE_CHANNEL,
                    ClusterEnvelope.MessageKind.PRESENCE_CHANGE, payload.getBytes(StandardCharsets.UTF_8),
                    null, currentTraceparent(), System.currentTimeMillis());
            try {
                broker.publish(PRESENCE_CHANNEL, env);
                clusterStats.incPresenceEventsPublished();
            } catch (Exception e) {
                log.warn("Failed to publish presence change for user {}", userId, e);
            }
        }
    }

    /** Receives a PRESENCE_CHANGE from another node and fires the local app listener (origin self-suppressed). */
    private void onPresenceMessage(ClusterEnvelope envelope) {
        if (nodeManager.getNodeId().equals(envelope.getOriginNodeId())) {
            clusterStats.incPresenceSelfDeliveryDropped();
            return;
        }
        String payload = envelope.getPayload() == null ? "" : new String(envelope.getPayload(), StandardCharsets.UTF_8);
        String[] parts = payload.split("\\|", 3);
        if (parts.length != 3) {
            log.warn("Malformed presence event payload: {}", payload);
            return;
        }
        clusterStats.incPresenceEventsReceived();
        PresenceChangeListener l = this.presenceChangeListener;
        if (l != null) {
            try {
                // parts[0] is the base64url-encoded userId (see firePresenceTransition) — decode before delivering.
                l.onPresenceChange(unb64UserId(parts[0]), PresenceStatus.valueOf(parts[1]), PresenceStatus.valueOf(parts[2]));
            } catch (Exception e) {
                log.warn("PresenceChangeListener threw for received event {}", payload, e);
            }
        }
    }

    /** Base64url (no padding) encode — delimiter-safe so a '|'-bearing userId can't corrupt the presence event body. */
    private static String b64UserId(String userId) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(userId.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64UserId(String b64) {
        return new String(java.util.Base64.getUrlDecoder().decode(b64), StandardCharsets.UTF_8);
    }

    /**
     * Leader-side dead-node presence reap (RC3 BLOCKER fix): removes a crashed node's connections from every user's
     * presence and emits the resulting OFFLINE/AWAY transitions — the authoritative source of OFFLINE events on the
     * dominant crash path (a hard crash never calls clearPresence). Called from the reconciliation reaper on the one
     * leader-elected node, so each affected user emits exactly one transition.
     */
    public CompletionStage<Void> reapPresenceForDeadNode(String deadNodeId) {
        PresenceRegistry pr = this.presenceRegistry;
        if (pr == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        return pr.removeAllForNode(deadNodeId).thenAccept(transitions -> {
            if (transitions == null) {
                return;
            }
            for (PresenceTransition t : transitions) {
                clusterStats.incPresenceReapOffline();
                firePresenceTransition(t.getUserId(), t); // local listener + publish to watcher nodes
            }
        }).toCompletableFuture();
    }

    /** MDC key carrying the offline message's store id during its delivery on reconnect, so handler logs
     *  (and an MDC-reading handler) can dedup on the infra id. Exposed as {@code X-Offline-Message-Id}. */
    public static final String MDC_OFFLINE_MESSAGE_ID = "netty.offlineMessageId";

    /**
     * Delivers a drained offline message to a freshly-reconnected LOCAL session on backfill (called by the
     * cluster session hook's drain-on-connect). The store id is bound to MDC ({@link #MDC_OFFLINE_MESSAGE_ID},
     * the {@code X-Offline-Message-Id} metadata) for the duration of the dispatch so handlers/logs can dedup
     * on the at-least-once infra id. Decodes the envelope payload via this sender's payload codec.
     *
     * @return {@code true} if delivered; {@code false} if the local session was already gone (skipped)
     */
    public boolean deliverOfflineMessage(String uri, String sessionId, StoredMessage stored) {
        org.slf4j.MDC.put(MDC_OFFLINE_MESSAGE_ID, stored.getId());
        try (ClusterTraceContext.Scope ts = traceScope(stored.getEnvelope())) {
            AbstractMessage message = deserializePayload(stored.getEnvelope().getPayload());
            localSender.sendMessage(uri, message, sessionId);
            clusterStats.addOfflineDrained(1);
            return true;
        } catch (MessageSessionClosedException e) {
            log.debug("Offline backfill: session {} on URI {} already gone — message {} not delivered",
                    sessionId, uri, stored.getId());
            return false;
        } catch (Exception e) {
            log.warn("Offline backfill: failed to deliver message {} to session {} on URI {}",
                    stored.getId(), sessionId, uri, e);
            return false;
        } finally {
            org.slf4j.MDC.remove(MDC_OFFLINE_MESSAGE_ID);
        }
    }

    // ==================== Cluster runtime stats (R-6) ====================

    /**
     * Returns cluster-specific runtime statistics.
     */
    public ClusterRuntimeStats getClusterRuntimeStats() {
        return clusterStats;
    }

    // ==================== Helpers ====================

    /** Returns true if the envelope payload exceeds the configured max size (0 = unlimited). */
    private boolean exceedsSizeLimit(ClusterEnvelope envelope) {
        return messageMaxSizeBytes > 0 && envelope.getPayload().length > messageMaxSizeBytes;
    }

    /** Applies the configured {@link ClusterProperties.OnPublishFailure} policy. */
    private void handlePublishFailure(String context, Throwable cause) {
        clusterStats.publishFailures.incrementAndGet();
        if (onPublishFailure == ClusterProperties.OnPublishFailure.LOG) {
            if (cause != null) {
                log.warn("Cluster publish failed: {}", context, cause);
            } else {
                log.warn("Cluster publish dropped: {}", context);
            }
        }
        // DROP: silent (only the counter is incremented)
    }

    /** WebSocket close status code 1011 = "internal/server error" (RFC 6455 §7.4.1) —
     *  used when CLOSE_ALL sheds local sessions because the cluster transport was lost. */
    private static final int CLOSE_CODE_TRANSPORT_LOST = 1011;

    /** Closes all local sessions across all URIs (onRedisLoss=CLOSE_ALL policy). */
    private void closeAllLocalSessions() {
        int closed = 0;
        for (String uri : localSender.getRegisteredUri()) {
            for (String sessionId : localSender.getSessionIds(uri)) {
                try {
                    localSender.closeSession(uri, sessionId, CLOSE_CODE_TRANSPORT_LOST, "cluster transport lost");
                    closed++;
                } catch (Exception e) {
                    log.debug("Failed to close session {} on URI {} during CLOSE_ALL", sessionId, uri, e);
                }
            }
        }
        log.warn("onRedisLoss=CLOSE_ALL — closed {} local sessions after transport loss", closed);
    }

    private void subscribeBroadcast(String uri) {
        broadcastSubscriptions.computeIfAbsent(uri,
                u -> broker.subscribe(u, this::onBroadcastMessage));
    }

    private void subscribeReliable(String uri) {
        reliableSubscriptions.computeIfAbsent(uri,
                u -> reliableBroker.reliableSubscribe(u, nodeManager.getNodeId(), this::onReliableMessage));
    }

    /**
     * Publishes a broadcast with at-least-once cross-node delivery (Redis Streams). Local fan-out
     * happens first (origin's own echo is suppressed in {@link #onReliableMessage}); then the envelope
     * is durably appended. Requires {@code reliable.enable=true}.
     *
     * @throws IllegalStateException if reliable delivery is disabled
     */
    public void reliableBroadcast(String uri, AbstractMessage message) throws MessageUriNotDefinedException {
        ReliableBroker rb = reliableBroker;
        if (rb == null) {
            throw new IllegalStateException("Reliable delivery is disabled; set "
                    + "server.netty.websocket.cluster.reliable.enable=true to use reliableBroadcast()");
        }
        // 1. Local fan-out first (always) — same contract as topicMessage.
        localSender.topicMessage(uri, message);
        // 2. Durable append for remote nodes.
        ClusterEnvelope envelope = buildBroadcastEnvelope(uri, message);
        if (exceedsSizeLimit(envelope)) {
            handlePublishFailure("reliable broadcast for URI " + uri + " exceeds messageMaxSizeBytes ("
                    + envelope.getPayload().length + " > " + messageMaxSizeBytes + ")", null);
            return;
        }
        try {
            rb.reliablePublish(uri, envelope);
            clusterStats.reliablePublished.incrementAndGet();
        } catch (Exception e) {
            handlePublishFailure("reliable broadcast for URI " + uri
                    + " — local delivery succeeded, but durable append failed (not persisted for remotes)", e);
        }
    }

    /** Consume callback for the reliable stream: suppress origin echo, then deliver locally. */
    private void onReliableMessage(ClusterEnvelope envelope) {
        if (nodeManager.getNodeId().equals(envelope.getOriginNodeId())) {
            return; // origin already did local fan-out before publishing — suppress the echo
        }
        clusterStats.reliableReceived.incrementAndGet();
        try (ClusterTraceContext.Scope ts = traceScope(envelope)) {
            AbstractMessage message = deserializePayload(envelope.getPayload());
            localSender.topicMessage(envelope.getUri(), message);
        } catch (Exception e) {
            log.warn("Failed to deliver reliable broadcast for URI {}", envelope.getUri(), e);
        }
    }

    /**
     * Notifies the cluster sender that a new URI has local sessions — triggers
     * a broadcast subscription if not already active.
     */
    public void onLocalUriActive(String uri) {
        subscribeBroadcast(uri);
        if (reliableBroker != null) {
            subscribeReliable(uri);
        }
    }

    /**
     * Notifies the cluster sender that a URI no longer has local sessions.
     * The subscription is kept alive for the hold duration (configured in broker).
     */
    public void onLocalUriInactive(String uri) {
        // Subscription hold is handled by the broker implementation, not here.
        // We could unsubscribe after hold, but for now keep it simple.
    }

    /** Invalidates the node lookup cache for a specific node (called on NODE_LEFT). */
    public void invalidateCacheForNode(String nodeId) {
        // synchronizedMap requires explicit synchronization on the map's monitor when iterating
        // (entrySet().removeIf uses an iterator). Snapshot the reference once for atomicity vs a
        // concurrent setRegistryReadCacheMaxSize() rebuild.
        Map<String, CachedNodeLookup> cache = this.nodeCache;
        synchronized (cache) {
            cache.entrySet().removeIf(e -> nodeId.equals(e.getValue().nodeId));
        }
        ReliableBroker rb = reliableBroker;
        if (rb != null) {
            try { rb.destroyConsumerGroupsForNode(nodeId); }
            catch (Exception e) { log.debug("reliable group cleanup for dead node {} failed", nodeId, e); }
        }
        // Room routing: clear the room node-set send cache wholesale (any room could have hosted the
        // departed node — the cache is keyed by room, not node, so a targeted purge isn't possible) and
        // scrub the dead node from every room's node-set + member sets (parallels SessionRegistry cleanup).
        ClusterRoomRegistry rr = this.roomRegistry;
        if (rr != null) {
            roomNodeSetCache.clear();
            try { rr.removeAllForNode(nodeId); }
            catch (Exception e) { log.debug("room registry cleanup for dead node {} failed", nodeId, e); }
        }
    }

    private String lookupNodeCached(String uri, String sessionId) {
        String cacheKey = uri + "|" + sessionId;
        CachedNodeLookup cached = nodeCache.get(cacheKey);
        if (cached != null && !cached.isExpired(nodeCacheTtlMs)) {
            clusterStats.cacheHits.incrementAndGet();
            return cached.nodeId;
        }
        clusterStats.cacheMisses.incrementAndGet();

        // Bounded blocking lookup (S2): wait at most nodeLookupTimeoutMs so a Redis stall can never
        // hang the unicast hot path (the default RedisClient command timeout already bounds this,
        // but this guards against a custom SessionRegistry that ignores timeouts). On timeout/error
        // the session is treated as not-found (→ closed), which is the correct degraded behavior.
        try {
            String nodeId = registry.lookupNode(uri, sessionId).toCompletableFuture()
                    .get(nodeLookupTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (nodeId != null) {
                nodeCache.put(cacheKey, new CachedNodeLookup(nodeId, System.currentTimeMillis()));
            } else {
                nodeCache.remove(cacheKey);
            }
            return nodeId;
        } catch (java.util.concurrent.TimeoutException te) {
            log.warn("Registry lookup for session {} on URI {} timed out after {}ms — treating as unreachable",
                    sessionId, uri, nodeLookupTimeoutMs);
            nodeCache.remove(cacheKey);
            return null;
        } catch (Exception e) {
            log.warn("Registry lookup failed for session {} on URI {}", sessionId, uri, e);
            nodeCache.remove(cacheKey);
            return null;
        }
    }

    private ClusterEnvelope buildBroadcastEnvelope(String uri, AbstractMessage message) {
        return new ClusterEnvelope(
                nodeManager.getNodeId(), uri,
                ClusterEnvelope.MessageKind.BROADCAST,
                serializePayload(message),
                null, currentTraceparent(), System.currentTimeMillis());
    }

    private ClusterEnvelope buildUnicastEnvelope(String uri, String sessionId, AbstractMessage message) {
        return new ClusterEnvelope(
                nodeManager.getNodeId(), uri,
                ClusterEnvelope.MessageKind.UNICAST,
                serializePayload(message),
                sessionId, currentTraceparent(), System.currentTimeMillis());
    }

    // Payload serialization delegated to MessagePayloadCodec SPI (R-1 refactor)

    private byte[] serializePayload(AbstractMessage message) {
        return payloadCodec.encode(message);
    }

    private AbstractMessage deserializePayload(byte[] payload) {
        return payloadCodec.decode(payload);
    }

    /** Short-lived node lookup cache entry. */
    private static final class CachedNodeLookup {
        final String nodeId;
        final long cachedAtMs;

        CachedNodeLookup(String nodeId, long cachedAtMs) {
            this.nodeId = nodeId;
            this.cachedAtMs = cachedAtMs;
        }

        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - cachedAtMs > ttlMs;
        }
    }

    /** Short-lived per-room node-set cache entry (the room send-path routing primitive). */
    private static final class CachedNodeSet {
        final Set<String> nodes;
        final long cachedAtMs;

        CachedNodeSet(Set<String> nodes, long cachedAtMs) {
            this.nodes = nodes;
            this.cachedAtMs = cachedAtMs;
        }

        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - cachedAtMs > ttlMs;
        }
    }
}
