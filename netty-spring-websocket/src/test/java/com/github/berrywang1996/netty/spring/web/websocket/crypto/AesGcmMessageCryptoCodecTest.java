package com.github.berrywang1996.netty.spring.web.websocket.crypto;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AesGcmMessageCryptoCodecTest {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().configureForJackson2().build();

    private static final SecretKey KEY =
            new SecretKeySpec("0123456789abcdef".getBytes(CharsetUtil.UTF_8), "AES");

    private static final SecretKey OLD_KEY =
            new SecretKeySpec("abcdef0123456789".getBytes(CharsetUtil.UTF_8), "AES");

    private static final SecretKey NEW_KEY =
            new SecretKeySpec("9876543210fedcba".getBytes(CharsetUtil.UTF_8), "AES");

    // Produced with 1.10.1's Jackson 2.18.12 settings, the test KEY, and IV bytes 0..11.
    private static final String LEGACY_JSON = "{\"createdAt\":0,\"state\":\"READY\",\"optional\":null,"
            + "\"nested\":{\"count\":2},\"node\":{\"message\":\"\u96ea\",\"values\":[1,true,null]}}";
    private static final String LEGACY_ENVELOPE = "{\"alg\":\"AES-GCM\",\"kid\":\"main\",\"typ\":\"text\","
            + "\"iv\":\"AAECAwQFBgcICQoL\",\"ciphertext\":\""
            + "hh2nb3sVT1OMZ3M4+qucD3OjNo5ikXBpJSyrt7aJh04xZ+uHn4D5q0gdyesSAXEDDQd6WlEVYUx5i+sLMhyraIp8hGMpviWK+8ia7L0SffY8imoJPWRS6DTm4+Rn5fCHl1g/JKyWsu6uaBKQOK3gz7cCOvVuzpCP8UJsvFT72ysaKkX8\"}";

    @Test
    void decrypts1101EnvelopeAndPreservesJsonPayloadBytes() throws Exception {
        AesGcmMessageCryptoCodec codec = newCodec();
        TextWebSocketFrame encrypted = new TextWebSocketFrame(LEGACY_ENVELOPE);
        WebSocketFrame plaintext = null;
        try {
            assertTrue(codec.canDecrypt(null, encrypted));
            plaintext = codec.decrypt(null, encrypted);
            assertEquals(LEGACY_JSON, ((TextWebSocketFrame) plaintext).text());
        } finally {
            ReferenceCountUtil.release(plaintext);
            encrypted.release();
        }
    }

    @Test
    void writesThe1101EnvelopeWithAnIdenticalIvAndPayload() throws Exception {
        NettyServerStartupProperties.WebSocket.Crypto properties =
                new NettyServerStartupProperties.WebSocket.Crypto();
        properties.setKeyId("main");
        // Determinism is restricted to this compatibility fixture; production still uses SecureRandom.
        SecureRandom fixtureRandom = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = (byte) i;
                }
            }
        };
        AesGcmMessageCryptoCodec codec = new AesGcmMessageCryptoCodec(
                properties, (keyId, session) -> KEY, fixtureRandom, OBJECT_MAPPER);
        TextWebSocketFrame plaintext = new TextWebSocketFrame(LEGACY_JSON);
        WebSocketFrame encrypted = null;
        try {
            encrypted = codec.encrypt(null, plaintext);
            assertEquals(LEGACY_ENVELOPE, ((TextWebSocketFrame) encrypted).text());
        } finally {
            plaintext.release();
            ReferenceCountUtil.release(encrypted);
        }
    }

    @Test
    void unknownEnvelopeFieldsRemainRejected() {
        AesGcmMessageCryptoCodec codec = newCodec();
        TextWebSocketFrame encrypted = new TextWebSocketFrame(
                LEGACY_ENVELOPE.substring(0, LEGACY_ENVELOPE.length() - 1) + ",\"unexpected\":true}");
        try {
            assertFalse(codec.canDecrypt(null, encrypted));
            assertThrows(tools.jackson.databind.exc.UnrecognizedPropertyException.class,
                    () -> codec.decrypt(null, encrypted));
        } finally {
            encrypted.release();
        }
    }

    @Test
    void encryptsAndDecryptsTextFrame() throws Exception {
        AesGcmMessageCryptoCodec codec = newCodec();
        TextWebSocketFrame plainFrame = new TextWebSocketFrame("hello");
        WebSocketFrame encryptedFrame = null;
        WebSocketFrame decryptedFrame = null;
        try {
            encryptedFrame = codec.encrypt(null, plainFrame);

            assertTrue(encryptedFrame instanceof TextWebSocketFrame);
            assertTrue(codec.canDecrypt(null, encryptedFrame));

            decryptedFrame = codec.decrypt(null, encryptedFrame);

            assertTrue(decryptedFrame instanceof TextWebSocketFrame);
            assertEquals("hello", ((TextWebSocketFrame) decryptedFrame).text());
        } finally {
            ReferenceCountUtil.release(plainFrame);
            ReferenceCountUtil.release(encryptedFrame);
            ReferenceCountUtil.release(decryptedFrame);
        }
    }

    @Test
    void encryptsAndDecryptsBinaryFrame() throws Exception {
        AesGcmMessageCryptoCodec codec = newCodec();
        BinaryWebSocketFrame plainFrame =
                new BinaryWebSocketFrame(Unpooled.copiedBuffer("binary", CharsetUtil.UTF_8));
        WebSocketFrame encryptedFrame = null;
        WebSocketFrame decryptedFrame = null;
        try {
            encryptedFrame = codec.encrypt(null, plainFrame);
            decryptedFrame = codec.decrypt(null, encryptedFrame);

            assertTrue(decryptedFrame instanceof BinaryWebSocketFrame);
            assertEquals("binary", decryptedFrame.content().toString(CharsetUtil.UTF_8));
        } finally {
            ReferenceCountUtil.release(plainFrame);
            ReferenceCountUtil.release(encryptedFrame);
            ReferenceCountUtil.release(decryptedFrame);
        }
    }

    @Test
    void rejectsTamperedCipherText() throws Exception {
        AesGcmMessageCryptoCodec codec = newCodec();
        TextWebSocketFrame plainFrame = new TextWebSocketFrame("hello");
        WebSocketFrame encryptedFrame = null;
        TextWebSocketFrame tamperedFrame = null;
        try {
            encryptedFrame = codec.encrypt(null, plainFrame);
            String envelope = ((TextWebSocketFrame) encryptedFrame).text();
            tamperedFrame = new TextWebSocketFrame(envelope.replaceFirst(".$", "A"));
            final TextWebSocketFrame finalTamperedFrame = tamperedFrame;

            assertThrows(Exception.class, () -> codec.decrypt(null, finalTamperedFrame));
        } finally {
            ReferenceCountUtil.release(plainFrame);
            ReferenceCountUtil.release(encryptedFrame);
            ReferenceCountUtil.release(tamperedFrame);
        }
    }

    @Test
    void doesNotTreatPlainTextAsEncryptedEnvelope() {
        AesGcmMessageCryptoCodec codec = newCodec();
        TextWebSocketFrame plainFrame = new TextWebSocketFrame("hello");
        try {
            assertFalse(codec.canDecrypt(null, plainFrame));
        } finally {
            ReferenceCountUtil.release(plainFrame);
        }
    }

    @Test
    void decryptsPreviousKeyEnvelopeAfterConfiguredKeyIdRotates() throws Exception {
        MessageCryptoKeyProvider rotatingKeyProvider = newMapKeyProvider(mapOf(
                "demo-2026-04", OLD_KEY,
                "demo-2026-05", NEW_KEY));
        AesGcmMessageCryptoCodec oldCodec = newCodec("demo-2026-04", rotatingKeyProvider);
        AesGcmMessageCryptoCodec newCodec = newCodec("demo-2026-05", rotatingKeyProvider);
        TextWebSocketFrame oldPlainFrame = new TextWebSocketFrame("old message");
        TextWebSocketFrame newPlainFrame = new TextWebSocketFrame("new message");
        WebSocketFrame oldEncryptedFrame = null;
        WebSocketFrame newEncryptedFrame = null;
        WebSocketFrame oldDecryptedFrame = null;
        WebSocketFrame newDecryptedFrame = null;
        try {
            oldEncryptedFrame = oldCodec.encrypt(null, oldPlainFrame);
            newEncryptedFrame = newCodec.encrypt(null, newPlainFrame);

            assertEquals("demo-2026-04", readEnvelope(oldEncryptedFrame).kid);
            assertEquals("demo-2026-05", readEnvelope(newEncryptedFrame).kid);

            oldDecryptedFrame = newCodec.decrypt(null, oldEncryptedFrame);
            newDecryptedFrame = newCodec.decrypt(null, newEncryptedFrame);

            assertEquals("old message", ((TextWebSocketFrame) oldDecryptedFrame).text());
            assertEquals("new message", ((TextWebSocketFrame) newDecryptedFrame).text());
        } finally {
            ReferenceCountUtil.release(oldPlainFrame);
            ReferenceCountUtil.release(newPlainFrame);
            ReferenceCountUtil.release(oldEncryptedFrame);
            ReferenceCountUtil.release(newEncryptedFrame);
            ReferenceCountUtil.release(oldDecryptedFrame);
            ReferenceCountUtil.release(newDecryptedFrame);
        }
    }

    // ---- v1.7.1 Fix #4: reject AES-GCM envelopes with non-96-bit IV ----
    //
    // NIST SP 800-38D §8.2 standardizes on a 96-bit IV for AES-GCM; non-96-bit IVs
    // trigger a GHASH-based derivation with weaker security margins, and an
    // attacker-controlled length is also a useful signal for "this envelope
    // wasn't produced by our encrypt path". Lock decrypt to 12 bytes explicitly.

    @Test
    void rejectsEnvelopeWithNon96BitIv() throws Exception {
        AesGcmMessageCryptoCodec codec = newCodec();
        // Craft an envelope with an 8-byte IV (base64 of 8 NUL bytes) — would have
        // been silently accepted pre-1.7.1.
        AesGcmMessageCryptoCodec.CryptoEnvelope envelope = new AesGcmMessageCryptoCodec.CryptoEnvelope();
        envelope.alg = AesGcmMessageCryptoCodec.ALGORITHM;
        envelope.kid = "main";
        envelope.typ = "text";
        envelope.iv = java.util.Base64.getEncoder().encodeToString(new byte[8]);
        envelope.ciphertext = java.util.Base64.getEncoder().encodeToString(new byte[16]);
        TextWebSocketFrame malformedFrame = new TextWebSocketFrame(OBJECT_MAPPER.writeValueAsString(envelope));
        try {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> codec.decrypt(null, malformedFrame));
            assertTrue(ex.getMessage().contains("IV"));
        } finally {
            ReferenceCountUtil.release(malformedFrame);
        }
    }

    private static AesGcmMessageCryptoCodec newCodec() {
        return newCodec("main", new MessageCryptoKeyProvider() {
            @Override
            public SecretKey resolveKey(String keyId, MessageSession session) {
                return KEY;
            }
        });
    }

    private static AesGcmMessageCryptoCodec newCodec(String keyId, MessageCryptoKeyProvider keyProvider) {
        NettyServerStartupProperties.WebSocket.Crypto crypto =
                new NettyServerStartupProperties.WebSocket.Crypto();
        crypto.setAlgorithm(AesGcmMessageCryptoCodec.ALGORITHM);
        crypto.setKeyId(keyId);
        return new AesGcmMessageCryptoCodec(crypto, keyProvider);
    }

    private static MessageCryptoKeyProvider newMapKeyProvider(final Map<String, SecretKey> keys) {
        return new MessageCryptoKeyProvider() {
            @Override
            public SecretKey resolveKey(String keyId, MessageSession session) {
                return keys.get(keyId);
            }
        };
    }

    private static Map<String, SecretKey> mapOf(String firstKeyId,
                                                SecretKey firstKey,
                                                String secondKeyId,
                                                SecretKey secondKey) {
        Map<String, SecretKey> keys = new HashMap<>();
        keys.put(firstKeyId, firstKey);
        keys.put(secondKeyId, secondKey);
        return keys;
    }

    private static AesGcmMessageCryptoCodec.CryptoEnvelope readEnvelope(WebSocketFrame frame) throws Exception {
        return OBJECT_MAPPER.readValue(
                ((TextWebSocketFrame) frame).text(),
                AesGcmMessageCryptoCodec.CryptoEnvelope.class);
    }
}
