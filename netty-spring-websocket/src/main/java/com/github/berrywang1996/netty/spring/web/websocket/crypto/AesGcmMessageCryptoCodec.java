package com.github.berrywang1996.netty.spring.web.websocket.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.util.StringUtil;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.CharsetUtil;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Built-in AES-GCM implementation for application-level websocket payload crypto.
 */
public class AesGcmMessageCryptoCodec implements MessageCryptoCodec {

    public static final String ALGORITHM = "AES-GCM";

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int IV_LENGTH_BYTES = 12;

    private static final int TAG_LENGTH_BITS = 128;

    private static final String TYPE_TEXT = "text";

    private static final String TYPE_BINARY = "binary";

    private final NettyServerStartupProperties.WebSocket.Crypto cryptoProperties;

    private final MessageCryptoKeyProvider keyProvider;

    private final SecureRandom secureRandom;

    private final ObjectMapper objectMapper;

    public AesGcmMessageCryptoCodec(NettyServerStartupProperties.WebSocket.Crypto cryptoProperties,
                                    MessageCryptoKeyProvider keyProvider) {
        this(cryptoProperties, keyProvider, new SecureRandom(), new ObjectMapper());
    }

    AesGcmMessageCryptoCodec(NettyServerStartupProperties.WebSocket.Crypto cryptoProperties,
                             MessageCryptoKeyProvider keyProvider,
                             SecureRandom secureRandom,
                             ObjectMapper objectMapper) {
        this.cryptoProperties = cryptoProperties;
        this.keyProvider = keyProvider;
        this.secureRandom = secureRandom;
        this.objectMapper = objectMapper;
    }

    @Override
    public WebSocketFrame encrypt(MessageSession session, WebSocketFrame plainFrame) throws Exception {
        String type = resolveFrameType(plainFrame);
        byte[] plainBytes = readPlainBytes(plainFrame, type);
        String keyId = resolveConfiguredKeyId();
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, resolveKey(keyId, session), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        cipher.updateAAD(aad(keyId, type));
        byte[] cipherText = cipher.doFinal(plainBytes);

        CryptoEnvelope envelope = new CryptoEnvelope();
        envelope.alg = ALGORITHM;
        envelope.kid = keyId;
        envelope.typ = type;
        envelope.iv = Base64.getEncoder().encodeToString(iv);
        envelope.ciphertext = Base64.getEncoder().encodeToString(cipherText);
        return new TextWebSocketFrame(objectMapper.writeValueAsString(envelope));
    }

    @Override
    public boolean canDecrypt(MessageSession session, WebSocketFrame encryptedFrame) {
        if (!(encryptedFrame instanceof TextWebSocketFrame)) {
            return false;
        }
        try {
            CryptoEnvelope envelope = readEnvelope((TextWebSocketFrame) encryptedFrame);
            return ALGORITHM.equalsIgnoreCase(envelope.alg)
                    && !StringUtil.isBlank(envelope.kid)
                    && !StringUtil.isBlank(envelope.typ)
                    && !StringUtil.isBlank(envelope.iv)
                    && !StringUtil.isBlank(envelope.ciphertext);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public WebSocketFrame decrypt(MessageSession session, WebSocketFrame encryptedFrame) throws Exception {
        if (!(encryptedFrame instanceof TextWebSocketFrame)) {
            throw new IllegalArgumentException("AES-GCM websocket crypto envelope must be a text frame.");
        }
        CryptoEnvelope envelope = readEnvelope((TextWebSocketFrame) encryptedFrame);
        validateEnvelope(envelope);

        byte[] iv = Base64.getDecoder().decode(envelope.iv);
        byte[] cipherText = Base64.getDecoder().decode(envelope.ciphertext);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE,
                resolveKey(envelope.kid, session),
                new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        cipher.updateAAD(aad(envelope.kid, envelope.typ));
        byte[] plainBytes = cipher.doFinal(cipherText);

        if (TYPE_TEXT.equals(envelope.typ)) {
            return new TextWebSocketFrame(new String(plainBytes, CharsetUtil.UTF_8));
        }
        if (TYPE_BINARY.equals(envelope.typ)) {
            return new BinaryWebSocketFrame(Unpooled.wrappedBuffer(plainBytes));
        }
        throw new IllegalArgumentException("Unsupported websocket crypto envelope type: " + envelope.typ);
    }

    private CryptoEnvelope readEnvelope(TextWebSocketFrame frame) throws Exception {
        return objectMapper.readValue(frame.text(), CryptoEnvelope.class);
    }

    private void validateEnvelope(CryptoEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("Websocket crypto envelope is empty.");
        }
        if (!ALGORITHM.equalsIgnoreCase(envelope.alg)) {
            throw new IllegalArgumentException("Unsupported websocket crypto algorithm: " + envelope.alg);
        }
        if (StringUtil.isBlank(envelope.kid)) {
            throw new IllegalArgumentException("Websocket crypto envelope key id is blank.");
        }
        if (!TYPE_TEXT.equals(envelope.typ) && !TYPE_BINARY.equals(envelope.typ)) {
            throw new IllegalArgumentException("Unsupported websocket crypto envelope type: " + envelope.typ);
        }
        if (TYPE_TEXT.equals(envelope.typ) && !isEncryptTextEnabled()) {
            throw new IllegalArgumentException("Websocket text crypto is disabled.");
        }
        if (TYPE_BINARY.equals(envelope.typ) && !isEncryptBinaryEnabled()) {
            throw new IllegalArgumentException("Websocket binary crypto is disabled.");
        }
        if (StringUtil.isBlank(envelope.iv) || StringUtil.isBlank(envelope.ciphertext)) {
            throw new IllegalArgumentException("Websocket crypto envelope is incomplete.");
        }
    }

    private String resolveFrameType(WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame) {
            return TYPE_TEXT;
        }
        if (frame instanceof BinaryWebSocketFrame) {
            return TYPE_BINARY;
        }
        throw new IllegalArgumentException("Unsupported websocket frame type for AES-GCM crypto: "
                + frame.getClass().getName());
    }

    private byte[] readPlainBytes(WebSocketFrame frame, String type) {
        if (TYPE_TEXT.equals(type)) {
            return ((TextWebSocketFrame) frame).text().getBytes(CharsetUtil.UTF_8);
        }
        ByteBuf content = frame.content();
        byte[] bytes = new byte[content.readableBytes()];
        content.getBytes(content.readerIndex(), bytes);
        return bytes;
    }

    private String resolveConfiguredKeyId() {
        if (cryptoProperties == null || StringUtil.isBlank(cryptoProperties.getKeyId())) {
            throw new IllegalStateException("AES-GCM websocket crypto requires server.netty.websocket.crypto.key-id.");
        }
        return cryptoProperties.getKeyId();
    }

    private SecretKey resolveKey(String keyId, MessageSession session) {
        if (keyProvider == null) {
            throw new IllegalStateException("AES-GCM websocket crypto requires a MessageCryptoKeyProvider.");
        }
        SecretKey key = keyProvider.resolveKey(keyId, session);
        if (key == null) {
            throw new IllegalStateException("No websocket crypto key resolved for key id: " + keyId);
        }
        return key;
    }

    private boolean isEncryptTextEnabled() {
        return cryptoProperties == null || cryptoProperties.isEncryptText();
    }

    private boolean isEncryptBinaryEnabled() {
        return cryptoProperties == null || cryptoProperties.isEncryptBinary();
    }

    private byte[] aad(String keyId, String type) {
        return (ALGORITHM + '|' + keyId + '|' + type).getBytes(CharsetUtil.UTF_8);
    }

    public static class CryptoEnvelope {
        public String alg;
        public String kid;
        public String typ;
        public String iv;
        public String ciphertext;
    }
}
