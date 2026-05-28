package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

/**
 * WebSocket text message that serializes a Java object as JSON.
 *
 * <p>Uses Jackson's {@link ObjectMapper} to convert the content object into a
 * JSON string, which is then wrapped in a Netty {@link TextWebSocketFrame}.
 * A shared default {@code ObjectMapper} is used unless a custom one is provided.
 *
 * <p>Both the {@code content} and {@code objectMapper} fields are declared
 * {@code volatile} to allow safe mutation and reading across threads during
 * broadcast scenarios.
 *
 * <h3>Zero-copy broadcast (v1.6)</h3>
 * <p>Overrides {@link #serializeSharedPayload(ByteBufAllocator)} to serialize the content
 * directly to a {@link ByteBuf} using {@link ObjectMapper#writeValueAsBytes(Object)},
 * bypassing the intermediate {@code String} allocation that
 * {@link ObjectMapper#writeValueAsString(Object)} produces.
 *
 * @author berrywang1996
 * @version V1.6.0
 * @since V1.0.0
 */
public class JsonMessage extends AbstractMessage<TextWebSocketFrame> {

    /** Shared default ObjectMapper instance used when no custom mapper is provided. */
    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = new ObjectMapper();

    private volatile Object content;

    private volatile ObjectMapper objectMapper;

    /**
     * Creates a new JSON message using the default {@link ObjectMapper}.
     *
     * @param content the object to be serialized as JSON
     */
    public JsonMessage(Object content) {
        this(content, DEFAULT_OBJECT_MAPPER);
    }

    /**
     * Creates a new JSON message with a custom {@link ObjectMapper}.
     *
     * @param content      the object to be serialized as JSON
     * @param objectMapper the Jackson mapper to use; if {@code null}, the default mapper is used
     */
    public JsonMessage(Object content, ObjectMapper objectMapper) {
        this.content = content;
        this.objectMapper = objectMapper == null ? DEFAULT_OBJECT_MAPPER : objectMapper;
    }

    /**
     * Returns the current content object.
     *
     * @return the content to be serialized
     */
    public Object getContent() {
        return content;
    }

    /**
     * Replaces the content object.
     *
     * @param content the new content to be serialized
     */
    public void setContent(Object content) {
        this.content = content;
    }

    /**
     * Returns the {@link ObjectMapper} currently used for serialization.
     *
     * @return the active object mapper
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    /**
     * Replaces the {@link ObjectMapper} used for serialization.
     *
     * @param objectMapper the new mapper; if {@code null}, the default mapper is restored
     */
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? DEFAULT_OBJECT_MAPPER : objectMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Serializes the {@link #content} into a JSON string and wraps it in a
     * new {@link TextWebSocketFrame}.
     *
     * @throws IllegalStateException if JSON serialization fails
     */
    @Override
    public TextWebSocketFrame responseMsg() {
        try {
            return new TextWebSocketFrame(objectMapper.writeValueAsString(content));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialize websocket JSON message failed.", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Serializes the content directly to a pooled {@link ByteBuf} using
     * {@link ObjectMapper#writeValueAsBytes(Object)}, which is more efficient than the
     * {@link #responseMsg()} path that goes through {@code writeValueAsString()} → UTF-8
     * encode → frame construction.
     *
     * @since V1.6.0
     */
    @Override
    public ByteBuf serializeSharedPayload(ByteBufAllocator allocator) {
        try {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(content);
            ByteBuf buf = allocator.buffer(jsonBytes.length);
            buf.writeBytes(jsonBytes);
            return buf;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialize websocket JSON message failed.", e);
        }
    }
}
