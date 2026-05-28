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

package com.github.berrywang1996.netty.spring.web.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import com.github.berrywang1996.netty.spring.web.context.HttpRuntimeRecorder;
import com.github.berrywang1996.netty.spring.web.context.WebMappingSupporter;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.util.ServiceHandlerUtil;
import com.github.berrywang1996.netty.spring.web.util.StringUtil;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Core Netty inbound handler that dispatches incoming HTTP requests and WebSocket frames
 * to the appropriate mapping resolver.
 *
 * <p>This handler sits at the end of the Netty channel pipeline and is responsible for:
 * <ul>
 *   <li>Routing HTTP requests to MVC controller resolvers or serving static files</li>
 *   <li>Routing WebSocket frames to the appropriate message mapping resolver</li>
 *   <li>Serving management/health endpoints when enabled</li>
 *   <li>Handling idle channel timeouts and exception propagation</li>
 *   <li>Managing reference counting for Netty messages to prevent memory leaks</li>
 * </ul>
 *
 * <p>All request handling is offloaded to the {@link WebMappingSupporter}'s thread pool
 * via {@link WebMappingSupporter#submitHandle(Runnable)} to avoid blocking Netty I/O threads.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public class ServiceHandler extends SimpleChannelInboundHandler<Object> {

    /** Channel attribute key for storing the WebSocket session ID. */
    public static final AttributeKey<String> SESSION_ID_IN_CHANNEL = AttributeKey.valueOf("sessionId");

    /** Channel attribute key for storing the original HTTP request (used for WebSocket frame routing). */
    public static final AttributeKey<FullHttpRequest> REQUEST_IN_CHANNEL = AttributeKey.valueOf("request");

    /** Shared ObjectMapper for serializing management endpoint JSON responses. */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WebMappingSupporter supporter;

    /**
     * Creates a new service handler backed by the given mapping supporter.
     *
     * @param supporter the web mapping supporter providing resolvers and the handler thread pool
     */
    public ServiceHandler(WebMappingSupporter supporter) {
        this.supporter = supporter;
    }

    /**
     * Receives an inbound message (HTTP request or WebSocket frame) and offloads
     * processing to the handler thread pool to avoid blocking the Netty I/O thread.
     *
     * <p>The message reference count is retained before submission and released after
     * handler completion (or immediately if the submission is rejected).
     *
     * @param ctx the channel handler context
     * @param msg the inbound message ({@link FullHttpRequest} or {@link WebSocketFrame})
     * @throws Exception if an unexpected error occurs during submission
     */
    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final Object msg) throws Exception {

        try {
            // Retain the message so it survives beyond this method (will be released after handling)
            ReferenceCountUtil.retain(msg);
            this.supporter.submitHandle(new Runnable() {
                @Override
                public void run() {
                    try {
                        handle(ctx, msg);
                    } catch (Exception e) {
                        log.warn("Execute handler failed! Please catch exception in child handler!", e);
                    } finally {
                        // Release the retained reference after processing completes
                        ReferenceCountUtil.release(msg);
                    }
                }
            });

        } catch (RejectedExecutionException e) {
            // Release the retained msg since the task was never submitted to the executor
            ReferenceCountUtil.release(msg);
            log.warn("Too many requests. Close request. reason={}", e.getMessage());
            ctx.close();
        }

        log.debug("Message reference count: {}", ReferenceCountUtil.refCnt(msg));

    }

    /**
     * Handles uncaught exceptions from the channel pipeline. For WebSocket channels,
     * delegates to the WebSocket resolver's exception handler; otherwise sends an HTTP 500 error.
     *
     * @param ctx   the channel handler context
     * @param cause the exception that was caught
     * @throws Exception if the parent handler encounters an error
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Failed to catch exception.", cause);
        if (cause instanceof Exception) {
            Exception exception = (Exception) cause;
            // Try WebSocket-specific exception handling first
            if (handleWebSocketException(ctx, exception)) {
                return;
            }
            // Fall back to generic HTTP error response
            ServiceHandlerUtil.HttpErrorMessage errorMessage =
                    new ServiceHandlerUtil.HttpErrorMessage(
                            HttpResponseStatus.INTERNAL_SERVER_ERROR, null, null, cause);
            ServiceHandlerUtil.sendError(ctx, null, errorMessage);
        } else {
            // For Errors (not Exceptions), close the channel immediately
            ctx.close();
            super.exceptionCaught(ctx, cause);
        }
    }

    /**
     * Invoked when the channel becomes inactive. Cleans up any associated WebSocket session state.
     *
     * @param ctx the channel handler context
     * @throws Exception if the parent handler encounters an error
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cleanupInactiveSession(ctx);
        super.channelInactive(ctx);
    }

    /**
     * Handles user-triggered events. Closes the channel and records metrics on idle timeout events.
     *
     * @param ctx the channel handler context
     * @param evt the triggered event
     * @throws Exception if the parent handler encounters an error
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent idleStateEvent = (IdleStateEvent) evt;
            log.warn("Close idle channel. state={}", idleStateEvent.state());
            recordIdleClose();
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    /**
     * Dispatches the message to either HTTP request handling or WebSocket frame handling
     * based on the message type.
     */
    private void handle(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (msg instanceof FullHttpRequest) {
            handleHttpRequest(ctx, msg);
        } else if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(ctx, msg);
        }
    }

    /**
     * Handles an incoming HTTP request by routing it through management endpoints,
     * registered mapping resolvers, or static file serving (in that priority order).
     */
    private void handleHttpRequest(ChannelHandlerContext ctx, Object msg) throws Exception {

        log.debug("Received a http request.");

        FullHttpRequest request = (FullHttpRequest) msg;
        String baseUri = getBaseUri(request);

        // Check management/health endpoints first (highest priority)
        if (handleManagementRequest(ctx, request, baseUri)) {
            return;
        }

        // Look up a mapping resolver for this URI (exact match, then Ant-style pattern match)
        log.debug("Get request mapping resolver.");
        AbstractMappingResolver mappingResolver = getMappingResolver(baseUri);
        NettyServerStartupProperties.Http httpProperties = supporter.getStartupProperties().getHttp();

        if (mappingResolver != null) {
            // Delegate to the matched MVC or WebSocket handshake resolver
            log.debug("Found mapped resolver {}.", mappingResolver);
            mappingResolver.resolve(ctx, msg);
        } else {
            // No controller mapping found - try static file serving if enabled
            if (httpProperties.isHandleFile()) {
                log.debug("Not found mapped resolver. Try to find a file in root directory.");
                // Reject root path requests for security (directory listing prevention)
                if (StringUtil.isBlank(baseUri) || "/".equals(baseUri)) {
                    recordStaticFileRejected();
                    ServiceHandlerUtil.HttpErrorMessage errorMsg =
                            new ServiceHandlerUtil.HttpErrorMessage(
                                    HttpResponseStatus.FORBIDDEN,
                                    request.uri(),
                                    null,
                                    null);
                    ServiceHandlerUtil.sendError(ctx, request, errorMsg);
                    return;
                }
                // Resolve and validate the static file path (prevents path traversal)
                File localFile = resolveStaticFile(httpProperties.getFileLocation(), baseUri);
                if (localFile == null) {
                    recordStaticFileRejected();
                    ServiceHandlerUtil.HttpErrorMessage errorMsg =
                            new ServiceHandlerUtil.HttpErrorMessage(
                                    HttpResponseStatus.FORBIDDEN,
                                    request.uri(),
                                    null,
                                    null);
                    ServiceHandlerUtil.sendError(ctx, request, errorMsg);
                    return;
                }
                handleFile(ctx, (FullHttpRequest) msg, localFile, getHttpRuntimeRecorder());
            } else {
                // Static file serving is disabled - return 404
                ServiceHandlerUtil.HttpErrorMessage errorMsg =
                        new ServiceHandlerUtil.HttpErrorMessage(
                                HttpResponseStatus.NOT_FOUND,
                                request.uri(),
                                null,
                                null);
                ServiceHandlerUtil.sendError(ctx, (FullHttpRequest) msg, errorMsg);
            }
        }
    }

    /**
     * Attempts to handle the request as a management endpoint (health check or status).
     * Returns true if the request was handled, false if it should continue to normal routing.
     */
    private boolean handleManagementRequest(ChannelHandlerContext ctx,
                                            FullHttpRequest request,
                                            String baseUri) throws JsonProcessingException {
        NettyServerStartupProperties.Management managementProperties = getManagementProperties();
        if (managementProperties == null || !managementProperties.isEnable()) {
            return false;
        }
        if (!isManagementPath(managementProperties, baseUri)) {
            return false;
        }
        // Management endpoints only accept GET requests
        if (!HttpMethod.GET.equals(request.method())) {
            ServiceHandlerUtil.HttpErrorMessage errorMsg =
                    new ServiceHandlerUtil.HttpErrorMessage(
                            HttpResponseStatus.METHOD_NOT_ALLOWED,
                            request.uri(),
                            null,
                            null);
            ServiceHandlerUtil.sendError(ctx, request, errorMsg);
            return true;
        }
        // Route to health or full status endpoint
        if (matchesPath(managementProperties.getHealthPath(), baseUri)) {
            sendJson(ctx, request, healthBody());
            return true;
        }
        sendJson(ctx, request, statusBody());
        return true;
    }

    /** Checks whether the given URI matches any configured management endpoint path. */
    private boolean isManagementPath(NettyServerStartupProperties.Management managementProperties, String baseUri) {
        return matchesPath(managementProperties.getHealthPath(), baseUri)
                || matchesPath(managementProperties.getStatusPath(), baseUri);
    }

    /** Performs an exact string match between a configured path and the request base URI. */
    private boolean matchesPath(String configuredPath, String baseUri) {
        return StringUtil.isNotBlank(configuredPath) && configuredPath.equals(baseUri);
    }

    /** Builds the JSON response body for the health endpoint. */
    private Map<String, Object> healthBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        return body;
    }

    /** Builds the JSON response body for the full status endpoint, including runtime stats. */
    private Map<String, Object> statusBody() {
        Map<String, Object> body = healthBody();
        body.put("handler", supporter == null ? null : supporter.getRuntimeStats());
        body.put("http", supporter == null ? null : supporter.getHttpRuntimeStats());
        body.put("websocket", supporter == null ? null : supporter.getWebSocketRuntimeStats());
        body.put("mappingCount", supporter == null ? 0 : supporter.getMappingResolverMap().size());
        return body;
    }

    /**
     * Serializes the given map as JSON and writes it as an HTTP response.
     * Closes the connection if the request is not keep-alive.
     */
    private void sendJson(ChannelHandlerContext ctx, FullHttpRequest request, Map<String, Object> body)
            throws JsonProcessingException {
        byte[] content = OBJECT_MAPPER.writeValueAsBytes(body);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1,
                OK,
                ctx.alloc().buffer(content.length).writeBytes(content));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        HttpUtil.setContentLength(response, content.length);
        ChannelFuture future = ctx.writeAndFlush(response);
        // Record metrics and close channel if the write fails
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (!future.isSuccess()) {
                    getHttpRuntimeRecorder().recordHttpResponseWriteFailure();
                    log.warn("Write management response failed, close channel. uri={}", request.uri(), future.cause());
                    future.channel().close();
                }
            }
        });
        if (!HttpUtil.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * Handles an incoming WebSocket frame by looking up the mapping resolver from
     * the channel's stored request URI (set during the initial WebSocket handshake).
     */
    private void handleWebSocketFrame(ChannelHandlerContext ctx, Object msg) throws Exception {

        log.debug("Received a websocket frame.");

        // Retrieve the original HTTP request stored during WebSocket handshake
        FullHttpRequest request = ctx.channel().attr(REQUEST_IN_CHANNEL).get();
        if (request == null) {
            log.warn("Missing websocket request context, close channel.");
            ctx.close();
            return;
        }
        String uri = request.uri();

        // Strip query parameters to get the base URI for resolver lookup
        if (uri.contains("?")) {
            uri = uri.substring(0, uri.indexOf("?"));
        }

        // Look up the WebSocket message mapping resolver
        log.debug("Get message mapping resolver.");
        AbstractMappingResolver mappingResolver = getMappingResolver(uri);

        if (mappingResolver != null) {
            mappingResolver.resolve(ctx, msg);
        } else {
            // No resolver found for this URI - close the WebSocket connection
            log.warn("Unknown message from uri {}, close channel.", uri);
            ctx.writeAndFlush(new TextWebSocketFrame("Unknown sources."));
            ctx.close();
        }

    }

    /**
     * Looks up the mapping resolver for the given URI. First attempts an exact match,
     * then falls back to Ant-style pattern matching against all registered patterns.
     *
     * @param uri the request URI to resolve
     * @return the matching resolver, or {@code null} if no match is found
     */
    private AbstractMappingResolver getMappingResolver(String uri) {

        // Try exact match first (O(1) hash lookup)
        AbstractMappingResolver mappingResolver = supporter.getMappingResolverMap().get(uri);

        // Fall back to Ant-style pattern matching (e.g. "/api/{id}")
        if (mappingResolver == null) {
            for (String key : supporter.getMappingResolverMap().keySet()) {
                if (this.supporter.getPathMatcher().match(key, uri)) {
                    mappingResolver = supporter.getMappingResolverMap().get(key);
                    break;
                }
            }
        }

        return mappingResolver;
    }

    /**
     * Attempts to delegate exception handling to the WebSocket resolver associated
     * with this channel's session. Returns true if handling was attempted.
     */
    private boolean handleWebSocketException(ChannelHandlerContext ctx, Exception cause) {
        AbstractMappingResolver mappingResolver = getChannelMappingResolver(ctx);
        if (mappingResolver == null) {
            return false;
        }
        try {
            mappingResolver.resolveException(ctx, cause);
        } catch (Exception e) {
            log.warn("Handle websocket exception failed.", e);
            ctx.close();
        }
        return true;
    }

    /**
     * Cleans up WebSocket session state when the channel becomes inactive
     * (e.g. client disconnect or timeout).
     */
    private void cleanupInactiveSession(ChannelHandlerContext ctx) {
        AbstractMappingResolver mappingResolver = getChannelMappingResolver(ctx);
        if (mappingResolver == null) {
            return;
        }
        try {
            mappingResolver.onChannelInactive(ctx);
        } catch (Exception e) {
            log.warn("Cleanup inactive session failed.", e);
        }
    }

    /**
     * Retrieves the mapping resolver associated with the current channel's WebSocket session,
     * using the session ID and request URI stored in channel attributes.
     */
    private AbstractMappingResolver getChannelMappingResolver(ChannelHandlerContext ctx) {
        String sessionId = ctx.channel().attr(SESSION_ID_IN_CHANNEL).get();
        FullHttpRequest request = ctx.channel().attr(REQUEST_IN_CHANNEL).get();
        if (StringUtil.isBlank(sessionId) || request == null) {
            return null;
        }
        return getMappingResolver(getBaseUri(request));
    }

    /**
     * Extracts the base URI (path without query string) from the given HTTP request.
     *
     * @param request the HTTP request
     * @return the URI path without query parameters
     */
    private static String getBaseUri(FullHttpRequest request) {
        String uri = request.uri();
        if (!uri.contains("?")) {
            return uri;
        }
        return uri.substring(0, uri.indexOf("?"));
    }

    /**
     * Resolves a static file from the configured file location, with path traversal protection.
     *
     * <p>The request path is URL-decoded, normalized, and validated to ensure the resolved
     * file is within (or equal to) the configured root directory. Returns {@code null} if the
     * path is invalid or escapes the root.
     *
     * @param fileLocation the root directory for static files
     * @param requestPath  the URL-encoded request path
     * @return the resolved file, or {@code null} if the path is invalid or traverses outside root
     * @throws IOException if canonical path resolution fails
     */
    static File resolveStaticFile(String fileLocation, String requestPath) throws IOException {
        if (StringUtil.isBlank(fileLocation) || StringUtil.isBlank(requestPath)) {
            return null;
        }
        File root = new File(fileLocation).getCanonicalFile();
        String decodedPath;
        try {
            decodedPath = URLDecoder.decode(requestPath, StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException e) {
            // Malformed percent-encoding in the request path
            return null;
        }
        // Normalize path separators and strip leading slashes
        decodedPath = decodedPath.replace('\\', '/');
        while (decodedPath.startsWith("/")) {
            decodedPath = decodedPath.substring(1);
        }
        File file = new File(root, decodedPath).getCanonicalFile();
        // Path traversal check: ensure resolved file is within the root directory
        if (!isSameOrChild(root, file)) {
            return null;
        }
        return file;
    }

    /** Checks that the given file path is equal to or a child of the root directory. */
    private static boolean isSameOrChild(File root, File file) {
        return file.toPath().equals(root.toPath()) || file.toPath().startsWith(root.toPath());
    }

    /**
     * Serves a static file using chunked transfer encoding with HTTP cache validation.
     *
     * <p>Supports the {@code If-Modified-Since} header for conditional responses (304 Not Modified).
     * File content is streamed using Netty's {@link ChunkedFile} for memory-efficient transfer.
     *
     * @param ctx                 the channel handler context
     * @param msg                 the HTTP request
     * @param file                the local file to serve
     * @param httpRuntimeRecorder the recorder for tracking static file serving metrics
     * @throws Exception if file reading or response writing fails
     */
    private static void handleFile(ChannelHandlerContext ctx,
                                   FullHttpRequest msg,
                                   File file,
                                   HttpRuntimeRecorder httpRuntimeRecorder) throws Exception {

        // Validate file accessibility
        if (file.isHidden() || !file.exists() || file.isDirectory() || !file.isFile()) {
            ServiceHandlerUtil.HttpErrorMessage errorMsg =
                    new ServiceHandlerUtil.HttpErrorMessage(
                            HttpResponseStatus.NOT_FOUND,
                            msg.uri(),
                            null,
                            null);
            ServiceHandlerUtil.sendError(ctx, msg, errorMsg);
            return;
        }

        // HTTP cache validation using If-Modified-Since header
        String ifModifiedSince = msg.headers().get(HttpHeaderNames.IF_MODIFIED_SINCE);
        if (ifModifiedSince != null && !ifModifiedSince.isEmpty()) {
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(ServiceHandlerUtil.HTTP_DATE_FORMAT, Locale.US)
                    .withZone(ZoneId.of(ServiceHandlerUtil.HTTP_DATE_GMT_TIMEZONE));
            ZonedDateTime ifModifiedSinceDate = ZonedDateTime.parse(ifModifiedSince, dateFormatter);

            // Compare at second precision since HTTP date format does not include milliseconds
            long ifModifiedSinceDateSeconds = ifModifiedSinceDate.toEpochSecond();
            long fileLastModifiedSeconds = file.lastModified() / 1000;
            if (ifModifiedSinceDateSeconds == fileLastModifiedSeconds) {
                ServiceHandlerUtil.sendNotModified(ctx, msg.uri());
                return;
            }
        }

        RandomAccessFile raf;
        try {
            raf = new RandomAccessFile(file, "r");
        } catch (FileNotFoundException ignore) {
            ServiceHandlerUtil.HttpErrorMessage errorMsg =
                    new ServiceHandlerUtil.HttpErrorMessage(
                            HttpResponseStatus.NOT_FOUND,
                            msg.uri(),
                            null,
                            null);
            ServiceHandlerUtil.sendError(ctx, msg, errorMsg);
            return;
        }

        try {
            long fileLength = raf.length();

            // Build the HTTP response with content type, cache headers, and keep-alive
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
            HttpUtil.setContentLength(response, fileLength);
            ServiceHandlerUtil.setContentTypeHeader(response, file);
            ServiceHandlerUtil.setDateAndCacheHeaders(response, file);
            if (HttpUtil.isKeepAlive(msg)) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }

            // Write the initial line and the header
            ctx.write(response);

            // Write the file content using chunked transfer (8KB chunks)
            // ChunkedFile takes ownership of the RandomAccessFile and closes it when done
            ChannelFuture sendFileFuture =
                    ctx.write(new HttpChunkedInput(new ChunkedFile(raf, 0, fileLength, 8192)),
                            ctx.newProgressivePromise());
            raf = null; // Ownership transferred to ChunkedFile — do not close manually

            // Write the end marker to signal response completion
            ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

        // Monitor file transfer progress for debugging
        sendFileFuture.addListener(new ChannelProgressiveFutureListener() {
            @Override
            public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {
                if (total < 0) {
                    log.debug("{} Transfer progress: {}", future.channel(), progress);
                } else {
                    log.debug("{} Transfer progress: {} / {}", future.channel(), progress, total);
                }
            }

            @Override
            public void operationComplete(ChannelProgressiveFuture future) {
                if (!future.isSuccess()) {
                    closeChannelOnStaticFileWriteFailure(future, msg.uri(), "file-content", httpRuntimeRecorder);
                    return;
                }
                log.debug("{} Transfer complete.", future.channel());
            }
        });
        addStaticFileWriteFailureListener(lastContentFuture, msg.uri(), "last-content", httpRuntimeRecorder);

        // Close the connection after transfer if the client does not support keep-alive
        if (!HttpUtil.isKeepAlive(msg)) {
            lastContentFuture.addListener(ChannelFutureListener.CLOSE);
        }
        } finally {
            // Close RAF only if ownership was NOT transferred to ChunkedFile
            if (raf != null) {
                raf.close();
            }
        }
    }

    /**
     * Adds a write-failure listener using a no-op runtime recorder.
     *
     * @param future the channel future to monitor
     * @param uri    the request URI (for logging)
     * @param phase  the write phase identifier (for logging)
     */
    static void addStaticFileWriteFailureListener(ChannelFuture future, final String uri, final String phase) {
        addStaticFileWriteFailureListener(future, uri, phase, HttpRuntimeRecorder.noop());
    }

    /**
     * Adds a listener that records metrics and closes the channel on static file write failure.
     *
     * @param future              the channel future to monitor
     * @param uri                 the request URI (for logging)
     * @param phase               the write phase identifier (for logging)
     * @param httpRuntimeRecorder the recorder to track write failures
     */
    static void addStaticFileWriteFailureListener(ChannelFuture future,
                                                  final String uri,
                                                  final String phase,
                                                  final HttpRuntimeRecorder httpRuntimeRecorder) {
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (!future.isSuccess()) {
                    closeChannelOnStaticFileWriteFailure(future, uri, phase, httpRuntimeRecorder);
                }
            }
        });
    }

    /** Records a static file write failure metric and closes the channel. */
    private static void closeChannelOnStaticFileWriteFailure(ChannelFuture future,
                                                            String uri,
                                                            String phase,
                                                            HttpRuntimeRecorder httpRuntimeRecorder) {
        httpRuntimeRecorder.recordStaticFileWriteFailure();
        log.warn("Write static file response failed, close channel. uri={}, phase={}", uri, phase, future.cause());
        future.channel().close();
    }

    /** Records a static file access rejection metric. */
    private void recordStaticFileRejected() {
        getHttpRuntimeRecorder().recordStaticFileRejected();
    }

    /** Records an idle channel close metric. */
    private void recordIdleClose() {
        getHttpRuntimeRecorder().recordIdleClose();
    }

    /** Returns the HTTP runtime recorder, falling back to a no-op instance if unavailable. */
    private HttpRuntimeRecorder getHttpRuntimeRecorder() {
        if (this.supporter == null || this.supporter.getHttpRuntimeRecorder() == null) {
            return HttpRuntimeRecorder.noop();
        }
        return this.supporter.getHttpRuntimeRecorder();
    }

    /** Returns the management properties, or null if the supporter or properties are unavailable. */
    private NettyServerStartupProperties.Management getManagementProperties() {
        return this.supporter == null || this.supporter.getStartupProperties() == null
                ? null
                : this.supporter.getStartupProperties().getManagement();
    }

}
