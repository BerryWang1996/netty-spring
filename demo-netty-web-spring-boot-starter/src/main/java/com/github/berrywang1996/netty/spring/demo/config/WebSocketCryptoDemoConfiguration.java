package com.github.berrywang1996.netty.spring.demo.config;

import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import com.github.berrywang1996.netty.spring.web.websocket.crypto.MessageCryptoKeyProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Demo configuration that registers a {@link MessageCryptoKeyProvider} with hardcoded
 * AES-128 keys for testing the WebSocket AES-GCM encryption feature.
 * <p>
 * Two demo keys are registered:
 * <ul>
 *   <li>{@code demo-2026-04} - key material {@code "abcdef0123456789"}</li>
 *   <li>{@code demo-2026-05} - key material {@code "9876543210fedcba"}</li>
 * </ul>
 * These are intentionally toy keys for local testing. Production deployments must
 * use properly generated keys from a secure key management system.
 * <p>
 * Activate this configuration by enabling the crypto-demo Spring profile.
 *
 * @author berrywang1996
 * @since V1.3.0
 * @see MessageCryptoKeyProvider
 */
@Configuration
public class WebSocketCryptoDemoConfiguration {

    /**
     * Creates a {@link MessageCryptoKeyProvider} bean named {@code "demoProvider"} that
     * resolves demo AES secret keys by key ID.
     * <p>
     * The returned provider holds an immutable snapshot of the key map, so keys
     * cannot be modified at runtime.
     *
     * @return a key provider backed by hardcoded demo keys
     */
    @Bean("demoProvider")
    public MessageCryptoKeyProvider demoMessageCryptoKeyProvider() {
        // Build a map of key ID -> AES SecretKey for demo purposes
        Map<String, SecretKey> keys = new HashMap<>();
        keys.put("demo-2026-04", new SecretKeySpec(
                "abcdef0123456789".getBytes(StandardCharsets.UTF_8),
                "AES"));
        keys.put("demo-2026-05", new SecretKeySpec(
                "9876543210fedcba".getBytes(StandardCharsets.UTF_8),
                "AES"));
        // Snapshot the keys into an unmodifiable map for thread safety
        final Map<String, SecretKey> keySnapshot = Collections.unmodifiableMap(keys);
        return new MessageCryptoKeyProvider() {
            /**
             * Resolves an AES secret key by its identifier.
             *
             * @param keyId   the key identifier sent by the client in the envelope header
             * @param session the WebSocket session requesting the key (unused in this demo)
             * @return the matching {@link SecretKey}, or {@code null} if the key ID is unknown
             */
            @Override
            public SecretKey resolveKey(String keyId, MessageSession session) {
                return keySnapshot.get(keyId);
            }
        };
    }
}
