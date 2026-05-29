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
 * Cluster-aware {@link MessageSender} implementation that wraps a local
 * {@link DefaultMessageSender} and adds cross-node broadcast/unicast via
 * the {@link ClusterBroker} and {@link SessionRegistry} SPIs.
 *
 * <h3>Design principle: local-first, cluster-second</h3>
 * <ul>
 *   <li>All local-query methods ({@link #getSessionIds}, {@link #getSessions},
 *       {@link #isSessionAlive}) remain <b>local-node only</b> and O(1) — they delegate
 *       directly to the local sender with zero network overhead.</li>
 *   <li>{@link #broadcast} performs local fan-out first, then publishes to the cluster
 *       broker for remote nodes (at-most-once by default).</li>
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
public class ClusterMessageSender implements MessageSender {

    private final MessageSender localSender;
    private final ClusterBroker broker;
    private final SessionRegistry registry;
    private final ClusterNodeManager nodeManager;
    private final MessagePayloadCodec payloadCodec;

    /** Local cache: sessionId → nodeId (short TTL, invalidated on NODE_LEFT). */
    private final ConcurrentHashMap<String, CachedNodeLookup> nodeCache = new ConcurrentHashMap<>();
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

    /**
     * Starts cluster subscriptions: subscribe to unicast channel for this node,
     * and to broadcast channels for all locally registered URIs.
     */
    public void start() {
        String nodeId = nodeManager.getNodeId();

        // Wire reconciliation→cache invalidation (I-3 fix)
        nodeManager.setDeadNodeCallback(this::invalidateCacheForNode);

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

        // 2. Publish to cluster broker (at-most-once) — skip if degraded
        if (nodeManager.getState() == NodeState.ACTIVE) {
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
            log.debug("Cluster broadcast skipped for URI {} — node state is {}",
                    uri, nodeManager.getState());
        }
    }

    // ==================== Targeted send (local or cluster unicast) ====================

    @Override
    public void sendMessage(String uri, AbstractMessage message, String... sessionIds)
            throws MessageUriNotDefinedException, MessageSessionClosedException {

        List<String> localIds = new ArrayList<>();
        List<String> remoteIds = new ArrayList<>();
        List<String> closedIds = new ArrayList<>();

        // Partition: local sessions go to local sender, remote sessions go to broker
        for (String sessionId : sessionIds) {
            MessageSession localSession = localSender.getSession(uri, sessionId);
            if (localSession != null) {
                localIds.add(sessionId);
            } else {
                // Check cluster registry
                String targetNodeId = lookupNodeCached(uri, sessionId);
                if (targetNodeId != null) {
                    remoteIds.add(sessionId);
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

        // Unicast to remote sessions via broker
        if (!remoteIds.isEmpty() && nodeManager.getState() == NodeState.ACTIVE) {
            for (String sessionId : remoteIds) {
                String targetNodeId = lookupNodeCached(uri, sessionId);
                if (targetNodeId != null) {
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
                } else {
                    closedIds.add(sessionId);
                }
            }
        } else if (!remoteIds.isEmpty()) {
            // Broker degraded — treat remote sessions as unreachable
            closedIds.addAll(remoteIds);
        }

        if (!closedIds.isEmpty()) {
            throw new MessageSessionClosedException(uri, closedIds);
        }
    }

    // ==================== Close session (local or cluster) ====================

    @Override
    public boolean closeSession(String uri, String sessionId, int statusCode, String reasonText)
            throws MessageUriNotDefinedException {
        // Try local first
        boolean closedLocally = localSender.closeSession(uri, sessionId, statusCode, reasonText);
        if (closedLocally) {
            return true;
        }

        // Not local — send close intent to the owning node via unicast
        if (nodeManager.getState() == NodeState.ACTIVE) {
            String targetNodeId = lookupNodeCached(uri, sessionId);
            if (targetNodeId != null) {
                try {
                    // Build a CLOSE control envelope (distinct from UNICAST data messages)
                    String closePayload = statusCode + "|" + reasonText;
                    ClusterEnvelope envelope = new ClusterEnvelope(
                            nodeManager.getNodeId(), uri,
                            ClusterEnvelope.MessageKind.CLOSE,
                            closePayload.getBytes(StandardCharsets.UTF_8),
                            sessionId, null, System.currentTimeMillis());
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
        // Unsubscribe all broadcast channels
        for (ClusterSubscription sub : broadcastSubscriptions.values()) {
            sub.unsubscribe();
        }
        broadcastSubscriptions.clear();

        // Unsubscribe unicast
        if (unicastSubscription != null) {
            unicastSubscription.unsubscribe();
        }

        localSender.shutdown();
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
        try {
            AbstractMessage message = deserializePayload(envelope.getPayload());
            localSender.topicMessage(envelope.getUri(), message);
        } catch (Exception e) {
            log.warn("Failed to deliver cluster broadcast for URI {}", envelope.getUri(), e);
        }
    }

    private void onUnicastMessage(ClusterEnvelope envelope) {
        String sessionId = envelope.getTargetSessionId();
        String uri = envelope.getUri();

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

    /** Closes all local sessions across all URIs (onRedisLoss=CLOSE_ALL policy). */
    private void closeAllLocalSessions() {
        int closed = 0;
        for (String uri : localSender.getRegisteredUri()) {
            for (String sessionId : localSender.getSessionIds(uri)) {
                try {
                    localSender.closeSession(uri, sessionId, 1011, "cluster transport lost");
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

    /**
     * Notifies the cluster sender that a new URI has local sessions — triggers
     * a broadcast subscription if not already active.
     */
    public void onLocalUriActive(String uri) {
        subscribeBroadcast(uri);
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
        nodeCache.entrySet().removeIf(e -> nodeId.equals(e.getValue().nodeId));
    }

    private String lookupNodeCached(String uri, String sessionId) {
        String cacheKey = uri + "|" + sessionId;
        CachedNodeLookup cached = nodeCache.get(cacheKey);
        if (cached != null && !cached.isExpired(nodeCacheTtlMs)) {
            clusterStats.cacheHits.incrementAndGet();
            return cached.nodeId;
        }
        clusterStats.cacheMisses.incrementAndGet();

        // Synchronous lookup (blocking on the CompletionStage for simplicity in 1.8.0;
        // can be made fully async in a future version if profiling shows this matters)
        try {
            String nodeId = registry.lookupNode(uri, sessionId).toCompletableFuture().join();
            if (nodeId != null) {
                nodeCache.put(cacheKey, new CachedNodeLookup(nodeId, System.currentTimeMillis()));
            } else {
                nodeCache.remove(cacheKey);
            }
            return nodeId;
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
                null, null, System.currentTimeMillis());
    }

    private ClusterEnvelope buildUnicastEnvelope(String uri, String sessionId, AbstractMessage message) {
        return new ClusterEnvelope(
                nodeManager.getNodeId(), uri,
                ClusterEnvelope.MessageKind.UNICAST,
                serializePayload(message),
                sessionId, null, System.currentTimeMillis());
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
}
