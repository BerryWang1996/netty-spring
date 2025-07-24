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

import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import com.github.berrywang1996.netty.spring.web.context.WebMappingSupporter;
import com.github.berrywang1996.netty.spring.web.util.ServiceHandlerUtil;
import com.github.berrywang1996.netty.spring.web.util.StringUtil;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
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
            log.warn("Too many requests. Close request.", e);
            ctx.close();
        }

        log.debug("Message reference count: {}", ReferenceCountUtil.refCnt(msg));

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Failed to catch exception.", cause);
        ServiceHandlerUtil.HttpErrorMessage errorMessage =
                new ServiceHandlerUtil.HttpErrorMessage(
                        HttpResponseStatus.INTERNAL_SERVER_ERROR, null, null, cause);
        ServiceHandlerUtil.sendError(ctx, null, errorMessage);
        String sessionId = ctx.channel().attr(SESSION_ID_IN_CHANNEL).get();
        if (StringUtil.isNotBlank(sessionId)) {
            String uri = ctx.channel().attr(REQUEST_IN_CHANNEL).get().uri();
            AbstractMappingResolver mappingResolver = getMappingResolver(uri);
            if (mappingResolver != null && "com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver".equals(mappingResolver.getClass().getName())) {
                mappingResolver.removeSession(sessionId);
            }
        }
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

        // get mapping resolver
        log.debug("Get request mapping resolver.");
        AbstractMappingResolver mappingResolver = getMappingResolver(baseUri);

        if (mappingResolver != null) {
            // if mapped
            log.debug("Found mapped resolver {}.", mappingResolver);
            mappingResolver.resolve(ctx, msg);
        } else {
            // if handle file is true
            if (supporter.getStartupProperties().isHandleFile()) {
                // if not mapped, may be request a file
                log.debug("Not found mapped resolver. Try to find a file in root directory.");
                if (StringUtil.isBlank(baseUri) || "/".equals(baseUri)) {
                    ServiceHandlerUtil.HttpErrorMessage errorMsg =
                            new ServiceHandlerUtil.HttpErrorMessage(
                                    HttpResponseStatus.FORBIDDEN,
                                    request.uri(),
                                    null,
                                    null);
                    ServiceHandlerUtil.sendError(ctx, request, errorMsg);
                    return;
                }
                String localPath = this.supporter.getStartupProperties().getFileLocation() + baseUri;
                localPath = localPath.replace("/", File.separator);
                handleFile(ctx, (FullHttpRequest) msg, localPath);
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

    private void handleWebSocketFrame(ChannelHandlerContext ctx, Object msg) throws Exception {

        log.debug("Received a websocket frame.");

        // get url from channel attribute, set attribute in first handshake
        String uri = ctx.channel().attr(REQUEST_IN_CHANNEL).get().uri();

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

    private static String getBaseUri(FullHttpRequest request) {
        String uri = request.uri();
        if (!uri.contains("?")) {
            return uri;
        }
        return uri.substring(0, uri.indexOf("?"));
    }

    private static void handleFile(ChannelHandlerContext ctx, FullHttpRequest msg, String localPath) throws Exception {

        File file = new File(localPath);
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
                log.debug("{} Transfer complete.", future.channel());
            }
        });

        // Decide whether to close the connection or not.
        if (!HttpUtil.isKeepAlive(msg)) {
            // Close the connection when the whole content is written out.
            lastContentFuture.addListener(ChannelFutureListener.CLOSE);
        }
    }

}
