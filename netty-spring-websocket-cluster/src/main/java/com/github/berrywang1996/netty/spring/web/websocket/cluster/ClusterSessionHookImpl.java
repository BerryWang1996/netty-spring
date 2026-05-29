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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.context.ClusterSessionHook;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;

/**
 * Implementation of {@link ClusterSessionHook} that bridges the WebSocket session
 * lifecycle into the distributed session registry and cluster broadcast subscriptions.
 *
 * <p>Instantiated by the cluster auto-configuration and injected into every
 * {@link com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver}
 * via {@code setClusterSessionHook()}.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
@Slf4j
public class ClusterSessionHookImpl implements ClusterSessionHook {

    private final SessionRegistry registry;
    private final ClusterNodeManager nodeManager;
    private final ClusterMessageSender clusterSender;

    public ClusterSessionHookImpl(SessionRegistry registry,
                                  ClusterNodeManager nodeManager,
                                  ClusterMessageSender clusterSender) {
        this.registry = registry;
        this.nodeManager = nodeManager;
        this.clusterSender = clusterSender;
    }

    @Override
    public void onSessionRegistered(MessageSession session, String uri) {
        String nodeId = nodeManager.getNodeId();
        String sessionId = session.getSessionId();

        // Register in distributed session registry (async, fire-and-forget for the hot path)
        registry.register(uri, sessionId, nodeId, Collections.emptyMap())
                .exceptionally(ex -> {
                    log.warn("Failed to register session {} in cluster registry", sessionId, ex);
                    return null;
                });

        // Ensure the URI's broadcast subscription is active
        clusterSender.onLocalUriActive(uri);

        log.debug("Cluster: session {} registered on node {} for URI {}", sessionId, nodeId, uri);
    }

    @Override
    public void onSessionRemoved(MessageSession session, String uri) {
        String sessionId = session.getSessionId();

        // Deregister from distributed session registry
        registry.deregister(uri, sessionId)
                .exceptionally(ex -> {
                    log.warn("Failed to deregister session {} from cluster registry", sessionId, ex);
                    return null;
                });

        // Notify cluster sender (it manages subscription hold logic)
        clusterSender.onLocalUriInactive(uri);

        log.debug("Cluster: session {} removed for URI {}", sessionId, uri);
    }
}
