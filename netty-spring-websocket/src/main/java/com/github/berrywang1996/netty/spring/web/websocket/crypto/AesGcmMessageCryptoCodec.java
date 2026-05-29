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
 * Built-in AES-GCM implementation for application-level WebSocket payload encryption
 * and decryption.
 *
 * <p>This codec wraps plaintext or binary WebSocket frames into a JSON
 * {@link CryptoEnvelope} containing the algorithm identifier, key ID, frame type,
 * initialization vector (IV), and Base64-encoded ciphertext. On the inbound side it
 * parses the envelope, validates its fields, and decrypts the payload back into the
 * original frame type.
 *
 * <h3>Security design</h3>
 * <ul>
 *   <li>AES-GCM with a 128-bit authentication tag provides both confidentiality and integrity</li>
 *   <li>A fresh 12-byte IV is generated via {@link SecureRandom} for every encrypt call</li>
 *   <li>Additional Authenticated Data (AAD) binds the algorithm, key ID and frame type
 *       to the ciphertext to prevent cross-context replay</li>
 *   <li>Key material is never stored in this class; it is resolved at runtime via the
 *       pluggable {@link MessageCryptoKeyProvider}</li>
 * </ul>
 *
 * @author berrywang1996
 * @since V1.2.0
 * @see MessageCryptoCodec
 * @see MessageCryptoKeyProvider
 */
public class AesGcmMessageCryptoCodec implements MessageCryptoCodec {

    /** Algorithm identifier used in the crypto envelope and for AAD binding. */
    public static final String ALGORITHM = "AES-GCM";

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    /** Standard GCM IV length (96 bits). */
    private static final int IV_LENGTH_BYTES = 12;

    /** GCM authentication tag length (128 bits). */
    private static final int TAG_LENGTH_BITS = 128;

    private static final String TYPE_TEXT = "text";

    private static final String TYPE_BINARY = "binary";

    private final NettyServerStartupProperties.WebSocket.Crypto cryptoProperties;

    private final MessageCryptoKeyProvider keyProvider;

    private final SecureRandom secureRandom;

    private final ObjectMapper objectMapper;

    /**
     * Creates an AES-GCM codec with a default {@link SecureRandom} and {@link ObjectMapper}.
     *
     * @param cryptoProperties WebSocket crypto configuration properties
     * @param keyProvider      provider that resolves encryption keys by key ID
     */
    public AesGcmMessageCryptoCodec(NettyServerStartupProperties.WebSocket.Crypto cryptoProperties,
                                    MessageCryptoKeyProvider keyProvider) {
        this(cryptoProperties, keyProvider, new SecureRandom(), new ObjectMapper());
    }

    /**
     * Package-private constructor for testing with injectable random and mapper.
     *
     * @param cryptoProperties WebSocket crypto configuration properties
     * @param keyProvider      provider that resolves encryption keys by key ID
     * @param secureRandom     RNG for IV generation
     * @param objectMapper     Jackson mapper for envelope serialization
     */
    AesGcmMessageCryptoCodec(NettyServerStartupProperties.WebSocket.Crypto cryptoProperties,
                             MessageCryptoKeyProvider keyProvider,
                             SecureRandom secureRandom,
                             ObjectMapper objectMapper) {
        this.cryptoProperties = cryptoProperties;
        this.keyProvider = keyProvider;
        this.secureRandom = secureRandom;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Encrypts the given plain frame into a JSON {@link CryptoEnvelope} wrapped in a
     * {@link TextWebSocketFrame}. A fresh random IV is generated for each call.
     *
     * @param session    the WebSocket session (used for key resolution)
     * @param plainFrame the plaintext frame to encrypt (text or binary)
     * @return a new text frame containing the JSON crypto envelope
     * @throws Exception if encryption or key resolution fails
     */
    @Override
    public WebSocketFrame encrypt(MessageSession session, WebSocketFrame plainFrame) throws Exception {
        String type = resolveFrameType(plainFrame);
        byte[] plainBytes = readPlainBytes(plainFrame, type);
        String keyId = resolveConfiguredKeyId();

        // Generate a fresh 12-byte IV for each encrypt operation to ensure uniqueness
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, resolveKey(keyId, session), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        // AAD binds algorithm + key ID + frame type to prevent cross-context replay
        cipher.updateAAD(aad(keyId, type));
        byte[] cipherText = cipher.doFinal(plainBytes);

        // Build the JSON envelope that the client will parse and decrypt
        CryptoEnvelope envelope = new CryptoEnvelope();
        envelope.alg = ALGORITHM;
        envelope.kid = keyId;
        envelope.typ = type;
        envelope.iv = Base64.getEncoder().encodeToString(iv);
        envelope.ciphertext = Base64.getEncoder().encodeToString(cipherText);
        return new TextWebSocketFrame(objectMapper.writeValueAsString(envelope));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Determines whether the given frame looks like a valid AES-GCM crypto envelope
     * by attempting to parse it as JSON and checking for required fields.
     *
     * @param session        the WebSocket session
     * @param encryptedFrame the inbound frame to inspect
     * @return {@code true} if the frame appears to be an AES-GCM envelope
     */
    @Override
    public boolean canDecrypt(MessageSession session, WebSocketFrame encryptedFrame) {
        // Only text frames can carry JSON envelopes
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

    /**
     * {@inheritDoc}
     *
     * <p>Decrypts an inbound AES-GCM crypto envelope and reconstructs the original
     * WebSocket frame (text or binary) from the plaintext bytes.
     *
     * @param session        the WebSocket session (used for key resolution)
     * @param encryptedFrame the inbound text frame containing the JSON crypto envelope
     * @return a new frame with the decrypted payload
     * @throws Exception              if decryption, key resolution, or envelope validation fails
     * @throws IllegalArgumentException if the frame is not a text frame or the envelope type is unsupported
     */
    @Override
    public WebSocketFrame decrypt(MessageSession session, WebSocketFrame encryptedFrame) throws Exception {
        if (!(encryptedFrame instanceof TextWebSocketFrame)) {
            throw new IllegalArgumentException("AES-GCM websocket crypto envelope must be a text frame.");
        }
        CryptoEnvelope envelope = readEnvelope((TextWebSocketFrame) encryptedFrame);
        validateEnvelope(envelope);

        byte[] iv = Base64.getDecoder().decode(envelope.iv);
        // Reject any IV that doesn't match our 96-bit nonce convention: AES-GCM
        // mathematically tolerates other lengths but they trigger GHASH-based IV derivation
        // with weaker security margins, and an attacker-controlled IV length is a useful
        // signal for "this envelope wasn't produced by our encrypt path".
        if (iv.length != IV_LENGTH_BYTES) {
            throw new IllegalArgumentException("Invalid AES-GCM IV length.");
        }
        byte[] cipherText = Base64.getDecoder().decode(envelope.ciphertext);

        // Initialize cipher for decryption with the same GCM parameters
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE,
                resolveKey(envelope.kid, session),
                new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        // AAD must match what was used during encryption for the tag to verify
        cipher.updateAAD(aad(envelope.kid, envelope.typ));
        byte[] plainBytes = cipher.doFinal(cipherText);

        // Reconstruct the original frame type based on the envelope's type field
        if (TYPE_TEXT.equals(envelope.typ)) {
            return new TextWebSocketFrame(new String(plainBytes, CharsetUtil.UTF_8));
        }
        if (TYPE_BINARY.equals(envelope.typ)) {
            return new BinaryWebSocketFrame(Unpooled.wrappedBuffer(plainBytes));
        }
        throw new IllegalArgumentException("Unsupported websocket crypto envelope type: " + envelope.typ);
    }

    /** Deserializes the JSON text of a text frame into a {@link CryptoEnvelope}. */
    private CryptoEnvelope readEnvelope(TextWebSocketFrame frame) throws Exception {
        return objectMapper.readValue(frame.text(), CryptoEnvelope.class);
    }

    /** Validates all required fields of a crypto envelope before decryption. */
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

    /** Maps a WebSocket frame class to its envelope type string ("text" or "binary"). */
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

    /** Extracts plaintext bytes from a frame: UTF-8 for text, raw bytes for binary. */
    private byte[] readPlainBytes(WebSocketFrame frame, String type) {
        if (TYPE_TEXT.equals(type)) {
            return ((TextWebSocketFrame) frame).text().getBytes(CharsetUtil.UTF_8);
        }
        ByteBuf content = frame.content();
        byte[] bytes = new byte[content.readableBytes()];
        content.getBytes(content.readerIndex(), bytes);
        return bytes;
    }

    /** Reads the key ID from crypto properties; throws if not configured. */
    private String resolveConfiguredKeyId() {
        if (cryptoProperties == null || StringUtil.isBlank(cryptoProperties.getKeyId())) {
            throw new IllegalStateException("AES-GCM websocket crypto requires server.netty.websocket.crypto.key-id. "
                    + "Action: set a key id that your MessageCryptoKeyProvider can resolve.");
        }
        return cryptoProperties.getKeyId();
    }

    /** Resolves the AES secret key via the key provider; throws if unavailable or null. */
    private SecretKey resolveKey(String keyId, MessageSession session) {
        if (keyProvider == null) {
            throw new IllegalStateException("AES-GCM websocket crypto requires a MessageCryptoKeyProvider. "
                    + "Action: define a provider bean or disable server.netty.websocket.crypto.enable.");
        }
        SecretKey key = keyProvider.resolveKey(keyId, session);
        if (key == null) {
            throw new IllegalStateException("No websocket crypto key resolved for key id: " + keyId
                    + ". Action: check server.netty.websocket.crypto.key-id and make sure the provider keeps "
                    + "both current and rolling keys during migration.");
        }
        return key;
    }

    private boolean isEncryptTextEnabled() {
        return cryptoProperties == null || cryptoProperties.isEncryptText();
    }

    private boolean isEncryptBinaryEnabled() {
        return cryptoProperties == null || cryptoProperties.isEncryptBinary();
    }

    /**
     * Constructs the Additional Authenticated Data (AAD) bytes for GCM.
     * Format: "AES-GCM|{keyId}|{type}" encoded as UTF-8.
     */
    private byte[] aad(String keyId, String type) {
        return (ALGORITHM + '|' + keyId + '|' + type).getBytes(CharsetUtil.UTF_8);
    }

    /**
     * JSON-serializable envelope that wraps an AES-GCM encrypted WebSocket payload.
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code alg} - algorithm identifier (e.g. "AES-GCM")</li>
     *   <li>{@code kid} - key identifier for key rotation support</li>
     *   <li>{@code typ} - original frame type ("text" or "binary")</li>
     *   <li>{@code iv} - Base64-encoded initialization vector</li>
     *   <li>{@code ciphertext} - Base64-encoded ciphertext including the GCM auth tag</li>
     * </ul>
     */
    public static class CryptoEnvelope {
        public String alg;
        public String kid;
        public String typ;
        public String iv;
        public String ciphertext;
    }
}
