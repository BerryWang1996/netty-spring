package com.github.berrywang1996.netty.spring.web.websocket.crypto;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AesGcmMessageCryptoCodecTest {

    private static final SecretKey KEY =
            new SecretKeySpec("0123456789abcdef".getBytes(CharsetUtil.UTF_8), "AES");

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

    private static AesGcmMessageCryptoCodec newCodec() {
        NettyServerStartupProperties.WebSocket.Crypto crypto =
                new NettyServerStartupProperties.WebSocket.Crypto();
        crypto.setAlgorithm(AesGcmMessageCryptoCodec.ALGORITHM);
        crypto.setKeyId("main");
        return new AesGcmMessageCryptoCodec(crypto, new MessageCryptoKeyProvider() {
            @Override
            public SecretKey resolveKey(String keyId, MessageSession session) {
                return KEY;
            }
        });
    }
}
