package com.github.berrywang1996.netty.spring.web.websocket.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AesGcmMessageCryptoCodecTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final SecretKey KEY =
            new SecretKeySpec("0123456789abcdef".getBytes(CharsetUtil.UTF_8), "AES");

    private static final SecretKey OLD_KEY =
            new SecretKeySpec("abcdef0123456789".getBytes(CharsetUtil.UTF_8), "AES");

    private static final SecretKey NEW_KEY =
            new SecretKeySpec("9876543210fedcba".getBytes(CharsetUtil.UTF_8), "AES");

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
