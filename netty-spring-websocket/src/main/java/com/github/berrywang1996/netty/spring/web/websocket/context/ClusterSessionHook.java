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

package com.github.berrywang1996.netty.spring.web.websocket.context;

/**
 * Optional callback hook for cluster integration points in the WebSocket session lifecycle.
 *
 * <p>When cluster mode is enabled, the cluster layer provides an implementation that
 * registers/deregisters sessions in the distributed session registry and manages
 * per-URI broadcast subscriptions. When cluster is disabled, this hook is {@code null}
 * and the resolver skips the calls — zero overhead in single-node mode.
 *
 * <p>This interface lives in the {@code netty-spring-websocket} module (not the cluster
 * module) to avoid circular dependencies: the resolver calls into the hook; the cluster
 * module provides the implementation.
 *
 * <p><b>Handshake-request lifecycle guarantee (1.10.0-RC2):</b> in BOTH callbacks the
 * session's handshake {@link MessageSession#getFirstRequest() FullHttpRequest} (and thus
 * {@link MessageSession#getQueryParam}/{@link MessageSession#getHeader}) is guaranteed
 * readable — {@code onSessionRegistered} fires after a successful handshake (the request is
 * retained for the session's lifetime), and {@code onSessionRemoved} fires before the
 * session's {@code release()} drops the retained request. This lets a cluster implementation
 * resolve a stable {@code userId} from the handshake at BOTH connect and disconnect (e.g. to
 * bind on connect and unbind on disconnect) without caching it elsewhere.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
public interface ClusterSessionHook {

    /**
     * Called after a session is successfully registered locally (handshake complete,
     * session in the local map). The cluster implementation should register the
     * session in the distributed registry and ensure the URI's broadcast subscription
     * is active.
     *
     * @param session the newly connected session
     * @param uri     the WebSocket mapping URI
     */
    void onSessionRegistered(MessageSession session, String uri);

    /**
     * Called after a session is removed from the local map (close lifecycle complete).
     * The cluster implementation should deregister the session from the distributed
     * registry. If no more local sessions exist for the URI, it may (after a hold
     * period) unsubscribe from the broadcast channel.
     *
     * @param session the closed session
     * @param uri     the WebSocket mapping URI
     */
    void onSessionRemoved(MessageSession session, String uri);
}
