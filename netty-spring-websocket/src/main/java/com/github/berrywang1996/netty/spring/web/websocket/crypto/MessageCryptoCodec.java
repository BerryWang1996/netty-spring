package com.github.berrywang1996.netty.spring.web.websocket.crypto;

import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

/**
 * Extension point for application-level WebSocket message encryption and decryption.
 *
 * <p>Implementations provide the encrypt/decrypt logic for outbound and inbound
 * WebSocket frames. The framework calls {@link #encrypt} before writing a frame to the
 * channel and {@link #decrypt} after reading an encrypted frame from the channel.
 *
 * <p>The built-in implementation is {@link AesGcmMessageCryptoCodec}. Custom codecs
 * can be registered as Spring beans; the framework will discover them automatically
 * when crypto is enabled.
 *
 * @author berrywang1996
 * @since V1.2.0
 * @see AesGcmMessageCryptoCodec
 * @see MessageCryptoKeyProvider
 */
public interface MessageCryptoCodec {

    /**
     * Encrypts a plaintext WebSocket frame for the given session.
     *
     * @param session    the WebSocket session (can be used for per-session key selection)
     * @param plainFrame the plaintext frame to encrypt
     * @return a new frame containing the encrypted payload (must not be {@code null})
     * @throws Exception if encryption fails
     */
    WebSocketFrame encrypt(MessageSession session, WebSocketFrame plainFrame) throws Exception;

    /**
     * Determines whether the given frame can be decrypted by this codec.
     *
     * <p>Used to distinguish encrypted frames from plaintext frames during the
     * inbound pipeline. The default implementation returns {@code true}, meaning
     * all frames are treated as encrypted.
     *
     * @param session        the WebSocket session
     * @param encryptedFrame the inbound frame to inspect
     * @return {@code true} if this codec can decrypt the frame
     */
    default boolean canDecrypt(MessageSession session, WebSocketFrame encryptedFrame) {
        return true;
    }

    /**
     * Decrypts an encrypted WebSocket frame for the given session.
     *
     * @param session        the WebSocket session (can be used for per-session key selection)
     * @param encryptedFrame the encrypted frame to decrypt
     * @return a new frame containing the decrypted payload (must not be {@code null})
     * @throws Exception if decryption fails
     */
    WebSocketFrame decrypt(MessageSession session, WebSocketFrame encryptedFrame) throws Exception;
}
