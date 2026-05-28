/*
 * Copyright 2018 berrywang1996
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.berrywang1996.netty.spring.web.websocket.consts;

/**
 * Enumeration of WebSocket message types and lifecycle events that can be handled
 * by {@code @MessageMapping}-annotated methods.
 *
 * <p>Each constant corresponds to a specific point in the WebSocket session lifecycle
 * or a particular kind of inbound frame. The framework uses this enum to route
 * incoming events to the correct handler method.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public enum MessageType {

    /**
     * Pre-handshake hook. The handler is invoked before the WebSocket upgrade completes.
     *
     * <p>If the handler returns {@code false} (as a {@code Boolean}) or throws an
     * exception, the handshake is rejected and the connection is closed. Any exception
     * is forwarded to the {@link #ON_ERROR} handler if one is defined.
     */
    ON_HANDSHAKE,

    /**
     * Post-handshake hook. The handler is invoked after a successful WebSocket upgrade
     * and session registration.
     *
     * <p>The return value is ignored. If the handler throws an exception, the
     * {@link #ON_ERROR} handler is invoked and the session is closed.
     */
    ON_CONNECTED,

    /**
     * Ping frame handler. Invoked when a {@code PingWebSocketFrame} is received.
     *
     * <p>If no handler is registered for this type, the server automatically responds
     * with a {@code PongWebSocketFrame}. The return value is ignored. Exceptions are
     * forwarded to the {@link #ON_ERROR} handler.
     */
    ON_PING,

    /**
     * Text message handler. Invoked when a {@code TextWebSocketFrame} is received.
     *
     * <p>The handler method can accept the raw frame, a {@code String} payload, or
     * a JSON-deserialized object as its parameter. The return value is ignored.
     * Exceptions are forwarded to the {@link #ON_ERROR} handler.
     */
    TEXT_MESSAGE,

    /**
     * Binary message handler. Invoked when a {@code BinaryWebSocketFrame} is received.
     *
     * <p>The handler method can accept the raw frame, a {@code ByteBuf}, or a
     * {@code byte[]} as its parameter. The return value is ignored.
     * Exceptions are forwarded to the {@link #ON_ERROR} handler.
     */
    BINARY_MESSAGE,

    /**
     * Error handler. Invoked when any other lifecycle handler throws an exception.
     *
     * <p>If no {@code ON_ERROR} handler is defined, the exception propagates up to
     * Netty's exception handling pipeline.
     */
    ON_ERROR,

    /**
     * Close handler. Invoked when the session is being closed (client close frame,
     * server-initiated close, or channel inactive).
     *
     * <p>The return value and any exceptions thrown by this handler are ignored.
     */
    ON_CLOSE,

    /**
     * Catch-all handler for WebSocket frame types not covered by the other constants
     * (e.g. {@code PongWebSocketFrame} when no specific pong handler is needed).
     *
     * <p>The return value is ignored. Exceptions are forwarded to the
     * {@link #ON_ERROR} handler.
     */
    OTHER

}
