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
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public class ServiceHandler extends SimpleChannelInboundHandler<Object> {

    public static final AttributeKey<String> SESSION_ID_IN_CHANNEL = AttributeKey.valueOf("sessionId");

    public static final AttributeKey<FullHttpRequest> REQUEST_IN_CHANNEL = AttributeKey.valueOf("request");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WebMappingSupporter supporter;

    public ServiceHandler(WebMappingSupporter supporter) {
        this.supporter = supporter;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final Object msg) throws Exception {

        try {
            ReferenceCountUtil.retain(msg);
            this.supporter.submitHandle(new Runnable() {
                @Override
                public void run() {
                    try {
                        handle(ctx, msg);
                    } catch (Exception e) {
                        log.warn("Execute handler failed! Please catch exception in child handler!", e);
                    } finally {
                        ReferenceCountUtil.release(msg);
                    }
                }
            });

        } catch (RejectedExecutionException e) {
            log.warn("Too many requests. Close request. reason={}", e.getMessage());
            ctx.close();
        }

        log.debug("Message reference count: {}", ReferenceCountUtil.refCnt(msg));

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Failed to catch exception.", cause);
        if (cause instanceof Exception) {
            Exception exception = (Exception) cause;
            if (handleWebSocketException(ctx, exception)) {
                return;
            }
            ServiceHandlerUtil.HttpErrorMessage errorMessage =
                    new ServiceHandlerUtil.HttpErrorMessage(
                            HttpResponseStatus.INTERNAL_SERVER_ERROR, null, null, cause);
            ServiceHandlerUtil.sendError(ctx, null, errorMessage);
        } else {
            ctx.close();
            super.exceptionCaught(ctx, cause);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cleanupInactiveSession(ctx);
        super.channelInactive(ctx);
    }

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

    private void handle(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (msg instanceof FullHttpRequest) {
            handleHttpRequest(ctx, msg);
        } else if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(ctx, msg);
        }
    }

    private void handleHttpRequest(ChannelHandlerContext ctx, Object msg) throws Exception {

        log.debug("Received a http request.");

        FullHttpRequest request = (FullHttpRequest) msg;
        String baseUri = getBaseUri(request);

        if (handleManagementRequest(ctx, request, baseUri)) {
            return;
        }

        // get mapping resolver
        log.debug("Get request mapping resolver.");
        AbstractMappingResolver mappingResolver = getMappingResolver(baseUri);
        NettyServerStartupProperties.Http httpProperties = supporter.getStartupProperties().getHttp();

        if (mappingResolver != null) {
            // if mapped
            log.debug("Found mapped resolver {}.", mappingResolver);
            mappingResolver.resolve(ctx, msg);
        } else {
            // if handle file is true
            if (httpProperties.isHandleFile()) {
                // if not mapped, may be request a file
                log.debug("Not found mapped resolver. Try to find a file in root directory.");
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
        if (matchesPath(managementProperties.getHealthPath(), baseUri)) {
            sendJson(ctx, request, healthBody());
            return true;
        }
        sendJson(ctx, request, statusBody());
        return true;
    }

    private boolean isManagementPath(NettyServerStartupProperties.Management managementProperties, String baseUri) {
        return matchesPath(managementProperties.getHealthPath(), baseUri)
                || matchesPath(managementProperties.getStatusPath(), baseUri);
    }

    private boolean matchesPath(String configuredPath, String baseUri) {
        return StringUtil.isNotBlank(configuredPath) && configuredPath.equals(baseUri);
    }

    private Map<String, Object> healthBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        return body;
    }

    private Map<String, Object> statusBody() {
        Map<String, Object> body = healthBody();
        body.put("handler", supporter == null ? null : supporter.getRuntimeStats());
        body.put("http", supporter == null ? null : supporter.getHttpRuntimeStats());
        body.put("mappingCount", supporter == null ? 0 : supporter.getMappingResolverMap().size());
        return body;
    }

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

    private void handleWebSocketFrame(ChannelHandlerContext ctx, Object msg) throws Exception {

        log.debug("Received a websocket frame.");

        // get url from channel attribute, set attribute in first handshake
        FullHttpRequest request = ctx.channel().attr(REQUEST_IN_CHANNEL).get();
        if (request == null) {
            log.warn("Missing websocket request context, close channel.");
            ctx.close();
            return;
        }
        String uri = request.uri();

        // get base uri
        if (uri.contains("?")) {
            uri = uri.substring(0, uri.indexOf("?"));
        }

        // get mapping resolver
        log.debug("Get message mapping resolver.");
        AbstractMappingResolver mappingResolver = getMappingResolver(uri);

        if (mappingResolver != null) {
            // if mapped
            mappingResolver.resolve(ctx, msg);
        } else {
            // if not mapped, close websocket
            log.warn("Unknown message from uri {}, close channel.", uri);
            ctx.writeAndFlush(new TextWebSocketFrame("Unknown sources."));
            ctx.close();
        }

    }

    private AbstractMappingResolver getMappingResolver(String uri) {

        // get resolver from map
        AbstractMappingResolver mappingResolver = supporter.getMappingResolverMap().get(uri);

        // if not mapped, try to match
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

    private AbstractMappingResolver getChannelMappingResolver(ChannelHandlerContext ctx) {
        String sessionId = ctx.channel().attr(SESSION_ID_IN_CHANNEL).get();
        FullHttpRequest request = ctx.channel().attr(REQUEST_IN_CHANNEL).get();
        if (StringUtil.isBlank(sessionId) || request == null) {
            return null;
        }
        return getMappingResolver(getBaseUri(request));
    }

    private static String getBaseUri(FullHttpRequest request) {
        String uri = request.uri();
        if (!uri.contains("?")) {
            return uri;
        }
        return uri.substring(0, uri.indexOf("?"));
    }

    static File resolveStaticFile(String fileLocation, String requestPath) throws IOException {
        if (StringUtil.isBlank(fileLocation) || StringUtil.isBlank(requestPath)) {
            return null;
        }
        File root = new File(fileLocation).getCanonicalFile();
        String decodedPath;
        try {
            decodedPath = URLDecoder.decode(requestPath, StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException e) {
            return null;
        }
        decodedPath = decodedPath.replace('\\', '/');
        while (decodedPath.startsWith("/")) {
            decodedPath = decodedPath.substring(1);
        }
        File file = new File(root, decodedPath).getCanonicalFile();
        if (!isSameOrChild(root, file)) {
            return null;
        }
        return file;
    }

    private static boolean isSameOrChild(File root, File file) {
        return file.toPath().equals(root.toPath()) || file.toPath().startsWith(root.toPath());
    }

    private static void handleFile(ChannelHandlerContext ctx,
                                   FullHttpRequest msg,
                                   File file,
                                   HttpRuntimeRecorder httpRuntimeRecorder) throws Exception {

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
        // Cache Validation
        String ifModifiedSince = msg.headers().get(HttpHeaderNames.IF_MODIFIED_SINCE);
        if (ifModifiedSince != null && !ifModifiedSince.isEmpty()) {
            SimpleDateFormat dateFormatter = new SimpleDateFormat(ServiceHandlerUtil.HTTP_DATE_FORMAT, Locale.US);
            Date ifModifiedSinceDate = dateFormatter.parse(ifModifiedSince);

            // Only compare up to the second because the datetime format we send to the client
            // does not have milliseconds
            long ifModifiedSinceDateSeconds = ifModifiedSinceDate.getTime() / 1000;
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
        long fileLength = raf.length();

        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        HttpUtil.setContentLength(response, fileLength);
        ServiceHandlerUtil.setContentTypeHeader(response, file);
        ServiceHandlerUtil.setDateAndCacheHeaders(response, file);
        response.headers().set(HttpHeaderNames.CONTENT_BASE, HttpHeaderValues.KEEP_ALIVE);
        if (HttpUtil.isKeepAlive(msg)) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }

        // Write the initial line and the header.
        ctx.write(response);

        // Write the content.
        ChannelFuture sendFileFuture =
                ctx.write(new HttpChunkedInput(new ChunkedFile(raf, 0, fileLength, 8192)),
                        ctx.newProgressivePromise());

        // Write the end marker.
        ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

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

        // Decide whether to close the connection or not.
        if (!HttpUtil.isKeepAlive(msg)) {
            // Close the connection when the whole content is written out.
            lastContentFuture.addListener(ChannelFutureListener.CLOSE);
        }
    }

    static void addStaticFileWriteFailureListener(ChannelFuture future, final String uri, final String phase) {
        addStaticFileWriteFailureListener(future, uri, phase, HttpRuntimeRecorder.noop());
    }

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

    private static void closeChannelOnStaticFileWriteFailure(ChannelFuture future,
                                                            String uri,
                                                            String phase,
                                                            HttpRuntimeRecorder httpRuntimeRecorder) {
        httpRuntimeRecorder.recordStaticFileWriteFailure();
        log.warn("Write static file response failed, close channel. uri={}, phase={}", uri, phase, future.cause());
        future.channel().close();
    }

    private void recordStaticFileRejected() {
        getHttpRuntimeRecorder().recordStaticFileRejected();
    }

    private void recordIdleClose() {
        getHttpRuntimeRecorder().recordIdleClose();
    }

    private HttpRuntimeRecorder getHttpRuntimeRecorder() {
        if (this.supporter == null || this.supporter.getHttpRuntimeRecorder() == null) {
            return HttpRuntimeRecorder.noop();
        }
        return this.supporter.getHttpRuntimeRecorder();
    }

    private NettyServerStartupProperties.Management getManagementProperties() {
        return this.supporter == null || this.supporter.getStartupProperties() == null
                ? null
                : this.supporter.getStartupProperties().getManagement();
    }

}
