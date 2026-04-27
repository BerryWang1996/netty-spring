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

@Configuration
public class WebSocketCryptoDemoConfiguration {

    @Bean("demoProvider")
    public MessageCryptoKeyProvider demoMessageCryptoKeyProvider() {
        Map<String, SecretKey> keys = new HashMap<>();
        keys.put("demo-2026-04", new SecretKeySpec(
                "abcdef0123456789".getBytes(StandardCharsets.UTF_8),
                "AES"));
        keys.put("demo-2026-05", new SecretKeySpec(
                "9876543210fedcba".getBytes(StandardCharsets.UTF_8),
                "AES"));
        final Map<String, SecretKey> keySnapshot = Collections.unmodifiableMap(keys);
        return new MessageCryptoKeyProvider() {
            @Override
            public SecretKey resolveKey(String keyId, MessageSession session) {
                return keySnapshot.get(keyId);
            }
        };
    }
}
