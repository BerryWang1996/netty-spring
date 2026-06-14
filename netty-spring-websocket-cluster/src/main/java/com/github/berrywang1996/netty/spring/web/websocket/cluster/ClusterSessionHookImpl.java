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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.OfflineQueueStore;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.StoredMessage;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserIdResolver;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.context.ClusterSessionHook;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Implementation of {@link ClusterSessionHook} that bridges the WebSocket session
 * lifecycle into the distributed session registry and cluster broadcast subscriptions.
 *
 * <p>Instantiated by the cluster auto-configuration and injected into every
 * {@link com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver}
 * via {@code setClusterSessionHook()}.
 *
 * <p><b>Offline queue + user-addressed delivery (1.10.0-RC2):</b> when
 * {@code server.netty.websocket.cluster.offline.enable=true}, the hook also resolves a stable {@code userId}
 * from the handshake (via {@link UserIdResolver}), carries it in the register metadata, binds the session in
 * the {@link UserRegistry} presence index, and drains+delivers any queued offline messages on connect
 * (backfill). When offline is disabled (the default), the hook behaves <b>byte-identically to RC1</b> — it
 * passes {@code Collections.emptyMap()} to register and never touches the resolver/registry/store.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
@Slf4j
public class ClusterSessionHookImpl implements ClusterSessionHook {

    private final CoalescingRegistryWriter registryWriter;
    private final ClusterNodeManager nodeManager;
    private final ClusterMessageSender clusterSender;

    // ---- Offline / user-addressed path (1.10.0-RC2); all null when offline.enable=false ----
    private final boolean offlineEnabled;
    private final UserIdResolver userIdResolver;
    private final UserRegistry userRegistry;
    private final OfflineQueueStore offlineStore;

    /** RC1-compatible constructor — offline path disabled (byte-identical to RC1). */
    public ClusterSessionHookImpl(CoalescingRegistryWriter registryWriter,
                                  ClusterNodeManager nodeManager,
                                  ClusterMessageSender clusterSender) {
        this(registryWriter, nodeManager, clusterSender, false, null, null, null);
    }

    /**
     * Full constructor. When {@code offlineEnabled} is true the resolver/registry/store MUST be non-null;
     * when false the offline collaborators are ignored and the hook is RC1-identical.
     */
    public ClusterSessionHookImpl(CoalescingRegistryWriter registryWriter,
                                  ClusterNodeManager nodeManager,
                                  ClusterMessageSender clusterSender,
                                  boolean offlineEnabled,
                                  UserIdResolver userIdResolver,
                                  UserRegistry userRegistry,
                                  OfflineQueueStore offlineStore) {
        this.registryWriter = registryWriter;
        this.nodeManager = nodeManager;
        this.clusterSender = clusterSender;
        this.offlineEnabled = offlineEnabled && userIdResolver != null && userRegistry != null && offlineStore != null;
        this.userIdResolver = userIdResolver;
        this.userRegistry = userRegistry;
        this.offlineStore = offlineStore;
    }

    @Override
    public void onSessionRegistered(MessageSession session, String uri) {
        String nodeId = nodeManager.getNodeId();
        String sessionId = session.getSessionId();

        // RC2: resolve the userId ONLY when the offline path is enabled — otherwise emptyMap, exactly as RC1
        // (so offline.enable=false is byte-identical: no resolver call, no userId in metadata).
        String userId = null;
        if (offlineEnabled) {
            try {
                userId = userIdResolver.resolve(session);
            } catch (Exception e) {
                log.warn("UserIdResolver threw for session {} on URI {} — treating as anonymous", sessionId, uri, e);
            }
        }

        if (userId != null) {
            clusterSender.getClusterRuntimeStats().incResolvedIdentities();
            clusterSender.getClusterRuntimeStats().addUsersOnlineLocal(1);
            // Register WITH userId metadata (was emptyMap in RC1).
            registryWriter.register(uri, sessionId, nodeId, Collections.singletonMap("userId", userId));
            final String resolvedUserId = userId;
            // Bind presence, THEN drain the offline queue (backfill). Drain after bind completes so any
            // message enqueued in the bind→drain window — which drain reads up to the stream tail — is
            // delivered (and a message arriving after bind reaches the now-online session in realtime).
            userRegistry.bindUser(resolvedUserId, uri, sessionId, nodeId)
                    .thenRun(() -> drainOnConnect(resolvedUserId, uri, sessionId))
                    .exceptionally(ex -> {
                        log.warn("bindUser({}) failed for session {} — offline backfill skipped this connect",
                                resolvedUserId, sessionId, ex);
                        return null;
                    });
        } else {
            if (offlineEnabled) {
                clusterSender.getClusterRuntimeStats().incUnresolvedSessions();
            }
            // Anonymous (or offline disabled) → emptyMap, exactly as RC1.
            registryWriter.register(uri, sessionId, nodeId, Collections.emptyMap());
        }

        // Ensure the URI's broadcast subscription is active
        clusterSender.onLocalUriActive(uri);

        log.debug("Cluster: session {} registered on node {} for URI {} (userId={})",
                sessionId, nodeId, uri, userId);
    }

    /** Drains the user's offline queue and delivers FIFO to the just-connected local session, then acks the
     *  delivered ids (which releases the per-userId drain lock). Returns empty if another device holds the
     *  drain lock (the holder delivers). */
    private void drainOnConnect(String userId, String uri, String sessionId) {
        offlineStore.drain(userId).thenAccept(messages -> {
            if (messages == null || messages.isEmpty()) {
                return; // nothing queued, or another device is draining (lock not acquired)
            }
            List<String> deliveredIds = new ArrayList<>(messages.size());
            for (StoredMessage m : messages) {
                // Deliver to the URI the message was addressed to (the offline envelope carries it). For a
                // multi-URI user the new session belongs to one URI; cross-URI offline delivery routes to the
                // local session of the envelope's URI (best-effort — undelivered ids are still acked since the
                // queue is per-user, and a redelivery would re-attempt). We deliver on the envelope's URI.
                String targetUri = m.getEnvelope().getUri() != null ? m.getEnvelope().getUri() : uri;
                clusterSender.deliverOfflineMessage(targetUri, sessionId, m);
                deliveredIds.add(m.getId());
            }
            // Ack ALL drained ids (delivered or skipped-because-gone) so the queue drains and the lock releases;
            // at-least-once means a still-undelivered message would be re-enqueued by the app layer if needed.
            offlineStore.delete(userId, deliveredIds).exceptionally(ex -> {
                log.warn("Offline delete(ack) for user {} failed — messages may redeliver on next connect", userId, ex);
                return null;
            });
            log.debug("Offline backfill: delivered {} message(s) to user {} session {} on URI {}",
                    deliveredIds.size(), userId, sessionId, uri);
        }).exceptionally(ex -> {
            log.warn("Offline drain for user {} failed on connect — backfill skipped this connect", userId, ex);
            return null;
        });
    }

    @Override
    public void onSessionRemoved(MessageSession session, String uri) {
        String sessionId = session.getSessionId();

        // RC2: unbind the user from the presence index (the handshake request is still readable here — see
        // the ClusterSessionHook lifecycle guarantee). Resolve ONLY when offline is enabled (else RC1 path).
        if (offlineEnabled) {
            String userId = null;
            try {
                userId = userIdResolver.resolve(session);
            } catch (Exception e) {
                log.warn("UserIdResolver threw on removal for session {} on URI {}", sessionId, uri, e);
            }
            if (userId != null) {
                clusterSender.getClusterRuntimeStats().addUsersOnlineLocal(-1);
                final String resolvedUserId = userId;
                userRegistry.unbindUser(resolvedUserId, uri, sessionId).exceptionally(ex -> {
                    log.warn("unbindUser({}) failed for session {}", resolvedUserId, sessionId, ex);
                    return null;
                });
            }
        }

        // Deregister from distributed session registry (rate-limited/coalesced)
        registryWriter.deregister(uri, sessionId);

        // Notify cluster sender (it manages subscription hold logic)
        clusterSender.onLocalUriInactive(uri);

        log.debug("Cluster: session {} removed for URI {}", sessionId, uri);
    }
}
