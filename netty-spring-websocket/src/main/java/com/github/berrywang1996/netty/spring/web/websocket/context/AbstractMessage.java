package com.github.berrywang1996.netty.spring.web.websocket.context;

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
 * @param <T> the Netty {@code WebSocketFrame} subtype produced by this message
 * @author berrywang1996
 * @version V1.0.0
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

}
