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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.spi;

import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;

/**
 * Identity-extraction SPI (1.10.0-RC2): resolves a stable {@code userId} from a WebSocket session's
 * handshake, used as the recipient identity for user-addressed delivery and the offline queue.
 *
 * <p>The {@code userId} is the key for the offline queue, presence ({@code UserRegistry}), and
 * {@code sendToUser} routing. It must be <b>stable across reconnects</b> (so a returning user drains the
 * queue addressed to them) and <b>distinct per user</b>.
 *
 * <p>Resolved only when {@code server.netty.websocket.cluster.offline.enable=true}; when offline is
 * disabled the resolver is never invoked (no identity surface — see the RC2 backward-compatibility note).
 *
 * @author berrywang1996
 * @since V1.10.0
 */
public interface UserIdResolver {

    /**
     * Resolves the stable user identity for a session, or {@code null} for an anonymous/unauthenticated
     * connection (no offline queue, no presence binding for them).
     *
     * <p><b>SECURITY CONTRACT (load-bearing):</b> the returned userId IS treated as the recipient's
     * identity for offline delivery, presence, and queue ownership. It MUST be validated against the
     * session's <b>authenticated</b> principal — never a raw, client-controllable value.
     * <pre>
     *   WRONG (impersonation hole): return session.getQueryParam("userId");  // ?userId=bob steals bob's queue
     *   RIGHT:                       return verifiedJwt(session.getHeader("Authorization")).getSubject();
     * </pre>
     * The default {@code HandshakeUserIdResolver} is <b>convenience/testing only</b> — it trusts a
     * configured query-param/header verbatim. Production IM MUST supply a resolver that verifies identity
     * (typically in a {@code WebSocketHandshakeInterceptor} that has already authenticated the connection).
     *
     * @param session the WebSocket session (handshake request accessible via {@code getQueryParam}/{@code getHeader})
     * @return the stable userId, or {@code null} for anonymous/unauthenticated
     */
    String resolve(MessageSession session);
}
