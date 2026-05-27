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

import io.netty.handler.codec.http.FullHttpRequest;

/**
 * Extension point for custom WebSocket handshake authentication and authorization.
 *
 * <p>Implementations are invoked <em>after</em> the Origin check but <em>before</em>
 * the {@code @MessageMapping(messageType=ON_HANDSHAKE)} callback. If
 * {@link #beforeHandshake} returns {@code false}, the connection is rejected with
 * HTTP 401/403 and the session is never created.
 *
 * <p>Typical use cases:
 * <ul>
 *   <li>Validate a JWT or session token from query parameters or headers</li>
 *   <li>Check IP allow-lists</li>
 *   <li>Rate-limit per-user connection attempts</li>
 * </ul>
 *
 * <p>Register as a Spring bean; the autoconfigure module will pick it up
 * and pass it to the resolver.
 *
 * <pre>{@code
 * @Component
 * public class TokenHandshakeInterceptor implements WebSocketHandshakeInterceptor {
 *     @Override
 *     public boolean beforeHandshake(FullHttpRequest request, String uri) {
 *         String token = parseToken(request);
 *         return tokenService.isValid(token);
 *     }
 * }
 * }</pre>
 *
 * @author berrywang1996
 * @since V1.3.0
 */
public interface WebSocketHandshakeInterceptor {

    /**
     * Called before the WebSocket upgrade handshake.
     *
     * @param request the full HTTP upgrade request (headers, query params, etc.)
     * @param uri     the WebSocket mapping URI (path only, no query string)
     * @return {@code true} to allow the handshake to proceed,
     *         {@code false} to reject with HTTP 403 Forbidden
     */
    boolean beforeHandshake(FullHttpRequest request, String uri);

    /**
     * Optional rejection message returned to the client when
     * {@link #beforeHandshake} returns {@code false}.
     *
     * @return a short reason string, or {@code null} for a generic 403 body
     */
    default String rejectionReason() {
        return null;
    }
}
