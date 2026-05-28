package com.github.berrywang1996.netty.spring.web.websocket.crypto;

import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;

/**
 * Optional session-level policy that determines whether application-level WebSocket
 * message encryption should be applied to a given session.
 *
 * <p>When a {@code MessageCryptoPolicy} bean is registered, the framework invokes
 * {@link #shouldUseCrypto} for each session before encrypting outbound frames or
 * attempting to decrypt inbound frames. This allows fine-grained, per-session
 * opt-in or opt-out of the crypto pipeline (e.g. disabling encryption for
 * internal health-check connections while encrypting user-facing sessions).
 *
 * <p>If no policy bean is registered, all sessions matching the configured
 * include/exclude URI patterns will use crypto.
 *
 * @author berrywang1996
 * @since V1.2.0
 * @see MessageCryptoCodec
 */
public interface MessageCryptoPolicy {

    /**
     * Determines whether the given session should use application-level crypto.
     *
     * @param session the WebSocket session to evaluate
     * @return {@code true} if the session should encrypt/decrypt frames, {@code false} to bypass crypto
     */
    boolean shouldUseCrypto(MessageSession session);
}
