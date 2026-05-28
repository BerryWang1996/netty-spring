package com.github.berrywang1996.netty.spring.web.websocket.context;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.nio.charset.StandardCharsets;

/**
 * WebSocket text frame message type.
 *
 * <p>Wraps a plain {@link String} payload and converts it into a Netty
 * {@link TextWebSocketFrame} via {@link #responseMsg()}. The content field is
 * declared {@code volatile} so that it can be safely mutated and read across
 * threads (e.g. when the same message instance is reused for broadcast).
 *
 * @author berrywang1996
 * @version V1.6.0
 * @since V1.0.0
 */
public class TextMessage extends AbstractMessage<TextWebSocketFrame> {

    private volatile String content;

    /**
     * Creates a new text message with the given payload.
     *
     * @param content the text payload to send over the WebSocket connection
     */
    public TextMessage(String content) {
        this.content = content;
    }

    /**
     * Returns the current text payload.
     *
     * @return the text content
     */
    public String getContent() {
        return content;
    }

    /**
     * Replaces the text payload.
     *
     * @param content the new text content
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each call returns a new {@link TextWebSocketFrame} backed by the
     * current {@link #content} value, which is safe for multi-session broadcast.
     */
    @Override
    public TextWebSocketFrame responseMsg() {
        return new TextWebSocketFrame(content);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Encodes the text content directly to a pooled {@link ByteBuf} using UTF-8.
     *
     * @since V1.6.0
     */
    @Override
    public ByteBuf serializeSharedPayload(ByteBufAllocator allocator) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = allocator.buffer(bytes.length);
        buf.writeBytes(bytes);
        return buf;
    }
}
