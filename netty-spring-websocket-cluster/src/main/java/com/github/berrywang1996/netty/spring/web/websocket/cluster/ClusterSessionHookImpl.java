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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceStatus;
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
 * <p><b>Capability flag-split (1.10.0-RC3):</b> the hook now distinguishes three independent capabilities
 * derived from which collaborators are wired:
 * <ul>
 *   <li><b>identity</b> ({@code userIdResolver != null && userRegistry != null}) — resolve a stable
 *       {@code userId} from the handshake (via {@link UserIdResolver}), carry it in the register metadata, and
 *       bind/unbind the session in the {@link UserRegistry} presence index. Required by both offline and presence.</li>
 *   <li><b>offline</b> ({@code identity && offlineStore != null}) — drain+deliver any queued offline messages on
 *       connect (backfill). 1.10.0-RC2.</li>
 *   <li><b>presence</b> ({@code identity && presenceRegistry != null}) — set this connection {@code ONLINE} on
 *       connect and clear it on disconnect via the {@link PresenceRegistry} (which fires aggregate transitions).
 *       1.10.0-RC3.</li>
 * </ul>
 *
 * <p>Pre-RC3 the hook collapsed identity+offline into a single {@code offlineEnabled} flag, so a presence-only
 * config (offline off, presence on) took the else branch and <b>never bound the user</b> — presence could not
 * function. The split fixes that: presence-only now binds. When <b>identity is off</b> (no resolver/registry,
 * the default) the hook behaves <b>byte-identically to RC1/RC2</b> — it passes {@code Collections.emptyMap()} to
 * register and never touches the resolver/registry/store/presence collaborators.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
@Slf4j
public class ClusterSessionHookImpl implements ClusterSessionHook {

    private final CoalescingRegistryWriter registryWriter;
    private final ClusterNodeManager nodeManager;
    private final ClusterMessageSender clusterSender;

    // ---- Identity / offline / presence capabilities (flags derived from wired collaborators, RC3 split) ----
    /** identity = resolver + userRegistry present: gates resolve + register-with-userId + bind/unbind. */
    private final boolean identityEnabled;
    /** offline = identity + offlineStore present: gates drain-on-connect backfill (RC2). */
    private final boolean offlineEnabled;
    /** presence = identity + presenceRegistry present: gates setPresence/clearPresence + publish-on-transition (RC3). */
    private final boolean presenceEnabled;
    private final UserIdResolver userIdResolver;
    private final UserRegistry userRegistry;
    private final OfflineQueueStore offlineStore;
    private final PresenceRegistry presenceRegistry;

    /** RC1-compatible constructor — identity/offline/presence all disabled (byte-identical to RC1). */
    public ClusterSessionHookImpl(CoalescingRegistryWriter registryWriter,
                                  ClusterNodeManager nodeManager,
                                  ClusterMessageSender clusterSender) {
        this(registryWriter, nodeManager, clusterSender, null, null, null, null);
    }

    /**
     * RC2-compatible constructor — offline path; no presence. The {@code offlineEnabled} flag is an explicit
     * master kill-switch for the WHOLE identity+offline path (RC2 semantics): when {@code false}, ALL
     * collaborators are ignored and the hook is byte-identical to RC1 (no resolver call, no bind). When
     * {@code true}, identity+offline are derived from the (then-required) non-null resolver/registry/store.
     * Delegates to the RC3 constructor with {@code presenceRegistry=null}.
     */
    public ClusterSessionHookImpl(CoalescingRegistryWriter registryWriter,
                                  ClusterNodeManager nodeManager,
                                  ClusterMessageSender clusterSender,
                                  boolean offlineEnabled,
                                  UserIdResolver userIdResolver,
                                  UserRegistry userRegistry,
                                  OfflineQueueStore offlineStore) {
        this(registryWriter, nodeManager, clusterSender,
                offlineEnabled ? userIdResolver : null,
                offlineEnabled ? userRegistry : null,
                offlineEnabled ? offlineStore : null,
                null);
    }

    /**
     * Full RC3 constructor. The three capability flags are derived from which collaborators are wired:
     * identity = resolver + userRegistry; offline = identity + offlineStore; presence = identity + presenceRegistry.
     * Any collaborator may be null to disable its capability; all null = RC1-identical (emptyMap register).
     */
    public ClusterSessionHookImpl(CoalescingRegistryWriter registryWriter,
                                  ClusterNodeManager nodeManager,
                                  ClusterMessageSender clusterSender,
                                  UserIdResolver userIdResolver,
                                  UserRegistry userRegistry,
                                  OfflineQueueStore offlineStore,
                                  PresenceRegistry presenceRegistry) {
        this.registryWriter = registryWriter;
        this.nodeManager = nodeManager;
        this.clusterSender = clusterSender;
        this.identityEnabled = userIdResolver != null && userRegistry != null;
        this.offlineEnabled = identityEnabled && offlineStore != null;
        this.presenceEnabled = identityEnabled && presenceRegistry != null;
        this.userIdResolver = userIdResolver;
        this.userRegistry = userRegistry;
        this.offlineStore = offlineStore;
        this.presenceRegistry = presenceRegistry;
    }

    @Override
    public void onSessionRegistered(MessageSession session, String uri) {
        String nodeId = nodeManager.getNodeId();
        String sessionId = session.getSessionId();

        // RC3: resolve the userId ONLY when the identity path is enabled (resolver + userRegistry wired) —
        // otherwise emptyMap, exactly as RC1 (no resolver call, no userId in metadata). Identity is shared by
        // both offline and presence, so a presence-only config still binds (the RC3 flag-split fix).
        String userId = null;
        if (identityEnabled) {
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
            // Bind presence index, THEN run the per-capability follow-ups. Drain after bind completes so any
            // message enqueued in the bind→drain window — which drain reads up to the stream tail — is
            // delivered (and a message arriving after bind reaches the now-online session in realtime).
            userRegistry.bindUser(resolvedUserId, uri, sessionId, nodeId)
                    .thenRun(() -> {
                        if (offlineEnabled) {
                            drainOnConnect(resolvedUserId, uri, sessionId);
                        }
                        if (presenceEnabled) {
                            // Mark this connection ONLINE; fires an aggregate transition (first device → OFFLINE→ONLINE).
                            clusterSender.setPresenceFromHook(resolvedUserId, nodeId, sessionId, PresenceStatus.ONLINE)
                                    .exceptionally(ex -> {
                                        log.warn("setPresence(ONLINE) failed for user {} session {}", resolvedUserId, sessionId, ex);
                                        return null;
                                    });
                        }
                    })
                    .exceptionally(ex -> {
                        log.warn("bindUser({}) failed for session {} — backfill/presence skipped this connect",
                                resolvedUserId, sessionId, ex);
                        return null;
                    });
        } else {
            if (identityEnabled) {
                clusterSender.getClusterRuntimeStats().incUnresolvedSessions();
            }
            // Anonymous (or identity disabled) → emptyMap, exactly as RC1.
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

        // RC3: clear presence + unbind the user from the index (the handshake request is still readable here —
        // see the ClusterSessionHook lifecycle guarantee). Resolve ONLY when identity is enabled (else RC1 path).
        if (identityEnabled) {
            String userId = null;
            try {
                userId = userIdResolver.resolve(session);
            } catch (Exception e) {
                log.warn("UserIdResolver threw on removal for session {} on URI {}", sessionId, uri, e);
            }
            if (userId != null) {
                clusterSender.getClusterRuntimeStats().addUsersOnlineLocal(-1);
                final String resolvedUserId = userId;
                String nodeId = nodeManager.getNodeId();
                // Presence: clear this connection (fires an aggregate transition — last device → ONLINE→OFFLINE).
                if (presenceEnabled) {
                    clusterSender.clearPresenceFromHook(resolvedUserId, nodeId, sessionId).exceptionally(ex -> {
                        log.warn("clearPresence failed for user {} session {}", resolvedUserId, sessionId, ex);
                        return null;
                    });
                }
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
