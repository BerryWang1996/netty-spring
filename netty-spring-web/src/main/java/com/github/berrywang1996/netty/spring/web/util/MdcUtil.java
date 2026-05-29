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

package com.github.berrywang1996.netty.spring.web.util;

import io.netty.channel.ChannelHandlerContext;
import org.slf4j.MDC;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Utility class for managing SLF4J {@link MDC} (Mapped Diagnostic Context) keys
 * during Netty request and WebSocket frame processing.
 *
 * <p>MDC keys are set at the start of handler execution and cleared when the handler
 * completes, providing structured context for all log statements within a request or
 * WebSocket frame processing cycle.
 *
 * <h3>MDC Keys</h3>
 * <ul>
 *   <li>{@code netty.requestId} – unique ID for an HTTP request (UUID)</li>
 *   <li>{@code netty.sessionId} – WebSocket session ID</li>
 *   <li>{@code netty.uri} – request URI or WebSocket mapping URI</li>
 *   <li>{@code netty.remoteAddr} – client IP address</li>
 * </ul>
 *
 * <p>Example logback pattern incorporating these keys:
 * <pre>
 * %d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} [%X{netty.requestId}] [%X{netty.sessionId}] - %msg%n
 * </pre>
 *
 * @author berrywang1996
 * @since V1.7.0
 */
public final class MdcUtil {

    /** MDC key for the unique HTTP request identifier. */
    public static final String KEY_REQUEST_ID = "netty.requestId";

    /** MDC key for the WebSocket session identifier. */
    public static final String KEY_SESSION_ID = "netty.sessionId";

    /** MDC key for the request or WebSocket URI. */
    public static final String KEY_URI = "netty.uri";

    /** MDC key for the client's remote IP address. */
    public static final String KEY_REMOTE_ADDR = "netty.remoteAddr";

    private MdcUtil() {
    }

    /**
     * Sets MDC context for an HTTP request.
     *
     * @param requestId  the unique request identifier
     * @param uri        the request URI
     * @param ctx        the Netty channel handler context (for extracting remote address)
     */
    public static void setHttpContext(String requestId, String uri, ChannelHandlerContext ctx) {
        if (requestId != null) {
            MDC.put(KEY_REQUEST_ID, requestId);
        }
        if (uri != null) {
            MDC.put(KEY_URI, uri);
        }
        String remoteAddr = resolveRemoteAddr(ctx);
        if (remoteAddr != null) {
            MDC.put(KEY_REMOTE_ADDR, remoteAddr);
        }
    }

    /**
     * Sets MDC context for a WebSocket frame or lifecycle event.
     *
     * @param sessionId  the WebSocket session identifier
     * @param uri        the WebSocket mapping URI
     * @param ctx        the Netty channel handler context (for extracting remote address)
     */
    public static void setWebSocketContext(String sessionId, String uri, ChannelHandlerContext ctx) {
        if (sessionId != null) {
            MDC.put(KEY_SESSION_ID, sessionId);
        }
        if (uri != null) {
            MDC.put(KEY_URI, uri);
        }
        String remoteAddr = resolveRemoteAddr(ctx);
        if (remoteAddr != null) {
            MDC.put(KEY_REMOTE_ADDR, remoteAddr);
        }
    }

    /**
     * Removes all Netty MDC keys from the current thread's context.
     * Call this in a {@code finally} block after handler execution completes.
     */
    public static void clear() {
        MDC.remove(KEY_REQUEST_ID);
        MDC.remove(KEY_SESSION_ID);
        MDC.remove(KEY_URI);
        MDC.remove(KEY_REMOTE_ADDR);
    }

    /**
     * Resolves the client's IP address from the Netty channel handler context.
     *
     * @param ctx the channel handler context
     * @return the IP address string, or {@code null} if unavailable
     */
    private static String resolveRemoteAddr(ChannelHandlerContext ctx) {
        if (ctx == null || ctx.channel() == null) {
            return null;
        }
        SocketAddress addr = ctx.channel().remoteAddress();
        if (addr instanceof InetSocketAddress) {
            return ((InetSocketAddress) addr).getAddress().getHostAddress();
        }
        return addr != null ? addr.toString() : null;
    }
}
