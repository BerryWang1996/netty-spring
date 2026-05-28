package com.github.berrywang1996.netty.spring.web.websocket.context;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.ReferenceCountUtil;

/**
 * Generic base class for all WebSocket messages in the framework.
 *
 * <p>Each concrete subclass (e.g. {@link TextMessage}, {@link JsonMessage},
 * {@link BinaryMessage}) defines how the application-level payload is converted
 * into a Netty {@code WebSocketFrame} that can be written to a channel.
 *
 * <p>The type parameter {@code T} represents the specific Netty frame type
 * produced by {@link #responseMsg()}.
 *
 * <h3>Zero-copy broadcast support (v1.6)</h3>
 * <p>For broadcast scenarios, the framework serializes the message payload once via
 * {@link #serializeSharedPayload(ByteBufAllocator)} and shares the resulting {@link ByteBuf}
 * across all target sessions using {@code retainedDuplicate()}. Subclasses should override
 * this method to provide an optimized serialization path that avoids creating intermediate
 * frame objects.
 *
 * @param <T> the Netty {@code WebSocketFrame} subtype produced by this message
 * @author berrywang1996
 * @version V1.6.0
 * @since V1.0.0
 */
public abstract class AbstractMessage<T> {

    /**
     * Converts this application-level message into a Netty {@code WebSocketFrame}
     * suitable for writing to a channel.
     *
     * <p>Implementations must return a new frame instance on each call so that
     * the message can be safely sent to multiple sessions (e.g. during broadcast).
     *
     * @return a new Netty frame representing this message's payload
     */
    public abstract T responseMsg();

    /**
     * Serializes this message's payload into a shared {@link ByteBuf} for zero-copy broadcast.
     *
     * <p>The returned {@link ByteBuf} is intended to be shared across multiple sessions
     * via {@code retainedDuplicate()}. Each session receives a lightweight reference to
     * the same underlying memory, eliminating redundant serialization. The caller is
     * responsible for releasing the returned buffer when broadcast is complete.
     *
     * <p>The default implementation falls back to {@link #responseMsg()}, extracts the
     * content {@link ByteBuf}, copies it into a pooled buffer, and releases the temporary
     * frame. Subclasses should override this for optimal performance (e.g. serializing
     * directly to a {@link ByteBuf} without creating an intermediate frame).
     *
     * @param allocator the {@link ByteBufAllocator} to use for buffer creation
     * @return a new {@link ByteBuf} containing the serialized payload; caller must release
     * @throws UnsupportedOperationException if the message type does not produce a {@code WebSocketFrame}
     * @since V1.6.0
     */
    public ByteBuf serializeSharedPayload(ByteBufAllocator allocator) {
        T frame = responseMsg();
        try {
            if (frame instanceof WebSocketFrame) {
                ByteBuf content = ((WebSocketFrame) frame).content();
                ByteBuf shared = allocator.buffer(content.readableBytes());
                try {
                    shared.writeBytes(content);
                } catch (Exception e) {
                    shared.release();
                    throw e;
                }
                return shared;
            }
            throw new UnsupportedOperationException(
                    "Cannot serialize " + getClass().getSimpleName() + " for shared broadcast");
        } finally {
            ReferenceCountUtil.release(frame);
        }
    }

    /**
     * Returns whether this message produces a text frame ({@code true}) or a binary
     * frame ({@code false}).
     *
     * <p>Used by the zero-copy broadcast path to wrap the shared {@link ByteBuf} in the
     * correct frame type ({@code TextWebSocketFrame} or {@code BinaryWebSocketFrame}).
     *
     * @return {@code true} for text-based messages, {@code false} for binary messages
     * @since V1.6.0
     */
    public boolean isTextFrame() {
        return true;
    }

}
