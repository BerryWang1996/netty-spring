package com.github.berrywang1996.netty.spring.web.websocket.crypto;

import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;

/**
 * Optional session-level policy for application-level websocket message crypto.
 */
public interface MessageCryptoPolicy {

    /**
     * Return {@code true} when the matched websocket session should use crypto.
     */
    boolean shouldUseCrypto(MessageSession session);
}
