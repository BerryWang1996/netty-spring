package com.github.berrywang1996.netty.spring.web.websocket.crypto;

import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;

import javax.crypto.SecretKey;

/**
 * Provider interface for resolving cryptographic keys at runtime without
 * hard-coding key material in the framework.
 *
 * <p>Implementations are responsible for loading secret keys from a secure store
 * (e.g. environment variables, vaults, key management services) and returning
 * them by key ID. The framework calls this provider during both encrypt and
 * decrypt operations.
 *
 * <p>To support key rotation, the provider should be able to resolve both the
 * current key and any recently-rotated keys simultaneously.
 *
 * @author berrywang1996
 * @since V1.2.0
 * @see MessageCryptoCodec
 * @see AesGcmMessageCryptoCodec
 */
public interface MessageCryptoKeyProvider {

    /**
     * Resolves a secret key by its identifier for the given session.
     *
     * @param keyId   the key identifier (configured via {@code server.netty.websocket.crypto.key-id})
     * @param session the WebSocket session requesting the key (allows per-session key selection)
     * @return the resolved secret key, or {@code null} if no key matches the given ID
     */
    SecretKey resolveKey(String keyId, MessageSession session);
}
