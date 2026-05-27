package com.github.berrywang1996.netty.spring.web.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.activation.MimetypesFileTypeMap;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static io.netty.handler.codec.http.HttpResponseStatus.FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_MODIFIED;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public class ServiceHandlerUtil {

    private static final Logger log = LoggerFactory.getLogger(ServiceHandlerUtil.class);

    public static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";

    public static final String HTTP_DATE_GMT_TIMEZONE = "GMT";

    public static final int HTTP_CACHE_SECONDS = 60;

    public static final MimetypesFileTypeMap MIME_TYPES_MAP = new MimetypesFileTypeMap();

    private static final DateTimeFormatter HTTP_DATE_FORMATTER =
            DateTimeFormatter.ofPattern(HTTP_DATE_FORMAT, Locale.US).withZone(ZoneId.of(HTTP_DATE_GMT_TIMEZONE));

    private static final DateTimeFormatter ISO_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.of("UTC"));

    public static void sendError(ChannelHandlerContext ctx,
                                 HttpRequest msg,
                                 HttpErrorMessage errorMessage) {

        ByteBuf byteData;
        String contentType;

        // return html when request method is get and request header accept not include application/json
        if (msg == null || "GET".equals(msg.method().name())
                && msg.headers().get("Accept") != null
                && !msg.headers().get("Accept").contains("application/json")) {
            byteData = Unpooled.copiedBuffer(errorResponseHtml(errorMessage), CharsetUtil.UTF_8);
            contentType = "text/html; charset=UTF-8";
        } else {
            byteData = Unpooled.copiedBuffer(errorResponseJson(errorMessage), CharsetUtil.UTF_8);
            contentType = "application/json; charset=UTF-8";
        }

        FullHttpResponse response =
                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, errorMessage.responseStatus, byteData);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static String errorResponseHtml(HttpErrorMessage errorMessage) {
        String escapedPath = escapeHtml(errorMessage.getPath() != null ? errorMessage.getPath() : "");
        String escapedMessage = escapeHtml(errorMessage.getMessage() != null ? errorMessage.getMessage() : "");
        String escapedError = escapeHtml(errorMessage.getError() != null ? errorMessage.getError() : "");
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/>"
                + "<title>" + errorMessage.getStatus() + " " + escapedError + "</title>"
                + "<style>body{font-family:sans-serif;margin:40px;color:#333}"
                + "h1{color:#c00}pre{background:#f5f5f5;padding:12px;border-radius:4px}</style>"
                + "</head><body>"
                + "<h1>" + errorMessage.getStatus() + " " + escapedError + "</h1>"
                + "<p><b>Timestamp:</b> " + errorMessage.getTimestamp() + "</p>"
                + "<p><b>Path:</b> " + escapedPath + "</p>"
                + (escapedMessage.isEmpty() ? "" : "<p><b>Message:</b> " + escapedMessage + "</p>")
                + "</body></html>";
    }

    private static String errorResponseJson(HttpErrorMessage errorMessage) {
        // Manual JSON construction to avoid ObjectMapper dependency
        return "{\"timestamp\":\"" + escapeJson(errorMessage.getTimestamp()) + "\""
                + ",\"status\":" + errorMessage.getStatus()
                + ",\"error\":\"" + escapeJson(errorMessage.getError() != null ? errorMessage.getError() : "") + "\""
                + ",\"message\":\"" + escapeJson(errorMessage.getMessage() != null ? errorMessage.getMessage() : "") + "\""
                + ",\"path\":\"" + escapeJson(errorMessage.getPath() != null ? errorMessage.getPath() : "") + "\""
                + "}";
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    public static void sendRedirect(ChannelHandlerContext ctx, String targetUrl) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, FOUND);
        response.headers().set(HttpHeaderNames.LOCATION, targetUrl);

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    public static void sendNotModified(ChannelHandlerContext ctx, String url) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, NOT_MODIFIED);
        response.headers().set(HttpHeaderNames.LOCATION, url);

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    public static void setContentTypeHeader(HttpResponse response, File file) {
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, MIME_TYPES_MAP.getContentType(file));
    }

    public static void setDateAndCacheHeaders(HttpResponse response, File file) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(HTTP_DATE_GMT_TIMEZONE));

        // Date header
        response.headers().set(HttpHeaderNames.DATE, HTTP_DATE_FORMATTER.format(now));

        // Add cache headers
        ZonedDateTime expires = now.plusSeconds(HTTP_CACHE_SECONDS);
        response.headers().set(HttpHeaderNames.EXPIRES, HTTP_DATE_FORMATTER.format(expires));
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "private, max-age=" + HTTP_CACHE_SECONDS);
        response.headers().set(HttpHeaderNames.LAST_MODIFIED,
                HTTP_DATE_FORMATTER.format(java.time.Instant.ofEpochMilli(file.lastModified()).atZone(ZoneId.of(HTTP_DATE_GMT_TIMEZONE))));
    }

    public static Map<String, String> parseRequestParameters(HttpRequest request) {

        Map<String, String> requestParameterMap = new HashMap<>();

        if (request.method() == HttpMethod.GET) {

            // if request method is get
            QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
            Map<String, List<String>> parameters = queryStringDecoder.parameters();
            for (Map.Entry<String, List<String>> parameterEntry : parameters.entrySet()) {
                if (parameterEntry.getValue().size() > 1) {
                    for (int i = 0; i < parameterEntry.getValue().size(); i++) {
                        requestParameterMap.put(parameterEntry.getKey() + "[" + i + "]",
                                decodeRequestString(parameterEntry.getValue().get(i)));
                    }
                } else {
                    requestParameterMap.put(parameterEntry.getKey(),
                            decodeRequestString(parameterEntry.getValue().get(0)));
                }
            }

        } else {

            // Note: text/plain and other non-form content types are not supported for parameter parsing;
            // only application/x-www-form-urlencoded and multipart/form-data are handled.
            // if request method is post
            if (request instanceof HttpContent) {
                HttpPostRequestDecoder decoder2 = new HttpPostRequestDecoder(request);
                decoder2.offer((HttpContent) request);
                List<InterfaceHttpData> bodyHttpDatas2 = decoder2.getBodyHttpDatas();
                for (InterfaceHttpData bodyHttpData : bodyHttpDatas2) {
                    try {
                        requestParameterMap.put(bodyHttpData.getName(), ((Attribute) bodyHttpData).getValue());
                    } catch (IOException e) {
                        log.warn("Failed to read POST parameter '{}': {}", bodyHttpData.getName(), e.getMessage());
                    }
                }
            }

        }

        return requestParameterMap;
    }

    public static String decodeRequestString(String string) {
        return URLDecoder.decode(string, StandardCharsets.UTF_8);
    }

    public static class HttpErrorMessage {

        private final HttpResponseStatus responseStatus;

        private final String timestamp;

        private final Integer status;

        private final String error;

        private final String message;

        private final String path;

        private final Throwable cause;

        public HttpErrorMessage(HttpResponseStatus responseStatus, String path, String message, Throwable cause) {
            this.responseStatus = responseStatus;
            this.timestamp = ISO_TIMESTAMP_FORMATTER.format(ZonedDateTime.now(ZoneId.of("UTC")));
            this.status = responseStatus.code();
            this.error = responseStatus.reasonPhrase();
            this.message = message;
            this.path = path;
            this.cause = cause;
        }

        public HttpResponseStatus getResponseStatus() {
            return responseStatus;
        }

        /**
         * @deprecated Use {@link #getTimestamp()} instead. This method has a typo in its name.
         */
        @Deprecated
        public String getTimestrap() {
            return timestamp;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public Integer getStatus() {
            return status;
        }

        public String getError() {
            return error;
        }

        public String getMessage() {
            return message;
        }

        public String getPath() {
            return path;
        }

        public Throwable getCause() {
            return cause;
        }

    }

}
