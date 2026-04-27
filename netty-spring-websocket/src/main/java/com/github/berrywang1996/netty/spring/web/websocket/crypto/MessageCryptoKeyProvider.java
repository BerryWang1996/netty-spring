package com.github.berrywang1996.netty.spring.web.websocket.crypto;

import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;

import javax.crypto.SecretKey;

/**
 * Resolves crypto keys without hard-coding key material in the framework.
 */
public interface MessageCryptoKeyProvider {

    SecretKey resolveKey(String keyId, MessageSession session);
}
