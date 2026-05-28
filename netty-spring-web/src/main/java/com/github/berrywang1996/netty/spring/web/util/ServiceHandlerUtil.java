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
 * Utility class for common HTTP response operations used by the service handler layer.
 *
 * <p>Provides helper methods for:
 * <ul>
 *   <li>Sending error responses (HTML for browsers, JSON for API clients)</li>
 *   <li>Sending redirect and 304 Not Modified responses</li>
 *   <li>Setting content-type, date, and cache headers for static file responses</li>
 *   <li>Parsing HTTP request parameters from GET query strings and POST form bodies</li>
 * </ul>
 *
 * @author berrywang1996
 * @version V1.0.0
 */
public class ServiceHandlerUtil {

    private static final Logger log = LoggerFactory.getLogger(ServiceHandlerUtil.class);

    /** Standard HTTP date format pattern (RFC 1123). */
    public static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";

    /** GMT timezone identifier used for HTTP date headers. */
    public static final String HTTP_DATE_GMT_TIMEZONE = "GMT";

    /** Default cache duration in seconds for static file responses. */
    public static final int HTTP_CACHE_SECONDS = 60;

    /** Shared MIME type map for determining content types from file extensions. */
    public static final MimetypesFileTypeMap MIME_TYPES_MAP = new MimetypesFileTypeMap();

    /** Pre-built formatter for HTTP date headers in RFC 1123 format. */
    private static final DateTimeFormatter HTTP_DATE_FORMATTER =
            DateTimeFormatter.ofPattern(HTTP_DATE_FORMAT, Locale.US).withZone(ZoneId.of(HTTP_DATE_GMT_TIMEZONE));

    /** Pre-built ISO 8601 formatter for error response timestamps. */
    private static final DateTimeFormatter ISO_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.of("UTC"));

    /**
     * Sends an HTTP error response and closes the connection.
     *
     * <p>Returns HTML for browser GET requests (based on the Accept header), or JSON for
     * API clients and non-GET requests. The connection is always closed after sending.
     *
     * @param ctx          the Netty channel handler context
     * @param msg          the original HTTP request (may be {@code null} for uncaught exceptions)
     * @param errorMessage the error details including status code, path, and message
     */
    public static void sendError(ChannelHandlerContext ctx,
                                 HttpRequest msg,
                                 HttpErrorMessage errorMessage) {

        ByteBuf byteData;
        String contentType;

        // return html when request method is get and request header accept not include application/json
        if (msg == null || ("GET".equals(msg.method().name())
                && msg.headers().get("Accept") != null
                && !msg.headers().get("Accept").contains("application/json"))) {
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

    /** Builds an HTML error page with properly escaped values to prevent XSS. */
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

    /** Builds a JSON error response body with properly escaped string values. */
    private static String errorResponseJson(HttpErrorMessage errorMessage) {
        // Manual JSON construction to avoid ObjectMapper dependency
        return "{\"timestamp\":\"" + escapeJson(errorMessage.getTimestamp()) + "\""
                + ",\"status\":" + errorMessage.getStatus()
                + ",\"error\":\"" + escapeJson(errorMessage.getError() != null ? errorMessage.getError() : "") + "\""
                + ",\"message\":\"" + escapeJson(errorMessage.getMessage() != null ? errorMessage.getMessage() : "") + "\""
                + ",\"path\":\"" + escapeJson(errorMessage.getPath() != null ? errorMessage.getPath() : "") + "\""
                + "}";
    }

    /** Escapes HTML special characters to prevent cross-site scripting (XSS). */
    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Escapes JSON special characters (backslash, double-quote, newlines). */
    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * Sends an HTTP 302 Found redirect response and closes the connection.
     *
     * @param ctx       the Netty channel handler context
     * @param targetUrl the URL to redirect the client to
     */
    public static void sendRedirect(ChannelHandlerContext ctx, String targetUrl) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, FOUND);
        response.headers().set(HttpHeaderNames.LOCATION, targetUrl);

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Sends an HTTP 304 Not Modified response. Respects the client's keep-alive
     * preference: if the request is keep-alive, the connection is left open for
     * subsequent requests; otherwise the connection is closed after the response.
     *
     * @param ctx     the Netty channel handler context
     * @param url     the resource URL (set in the Location header)
     * @param request the original HTTP request (used to determine keep-alive)
     */
    public static void sendNotModified(ChannelHandlerContext ctx, String url, HttpRequest request) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, NOT_MODIFIED);
        response.headers().set(HttpHeaderNames.LOCATION, url);

        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * Sets the Content-Type header on the response based on the file's extension.
     *
     * @param response the HTTP response to set the header on
     * @param file     the file whose MIME type should be determined
     */
    public static void setContentTypeHeader(HttpResponse response, File file) {
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, MIME_TYPES_MAP.getContentType(file));
    }

    /**
     * Sets the Date, Expires, Cache-Control, and Last-Modified headers on the response
     * for static file caching.
     *
     * @param response the HTTP response to set headers on
     * @param file     the file whose last-modified timestamp is used for the Last-Modified header
     */
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

    /**
     * Parses HTTP request parameters into a flat string map.
     *
     * <p>For GET requests, parameters are extracted from the query string. For POST requests,
     * form data is decoded from {@code application/x-www-form-urlencoded} or
     * {@code multipart/form-data} bodies. Multi-valued GET parameters are indexed
     * (e.g. {@code key[0]}, {@code key[1]}).
     *
     * @param request the HTTP request to parse parameters from
     * @return a map of parameter names to their string values
     */
    public static Map<String, String> parseRequestParameters(HttpRequest request) {

        Map<String, String> requestParameterMap = new HashMap<>();

        // Always parse query string parameters from the URI, regardless of HTTP method.
        // Standard HTTP semantics allow query parameters on any method (POST /api?version=2).
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

        // For non-GET requests, also parse form-encoded body parameters.
        // Body parameters take precedence over query string parameters for duplicate keys.
        if (request.method() != HttpMethod.GET) {
            // Note: text/plain and other non-form content types are not supported for parameter parsing;
            // only application/x-www-form-urlencoded and multipart/form-data are handled.
            if (request instanceof HttpContent) {
                HttpPostRequestDecoder decoder2 = null;
                try {
                    decoder2 = new HttpPostRequestDecoder(request);
                    decoder2.offer((HttpContent) request);
                    List<InterfaceHttpData> bodyHttpDatas2 = decoder2.getBodyHttpDatas();
                    for (InterfaceHttpData bodyHttpData : bodyHttpDatas2) {
                        // Only process Attribute data (form fields); skip FileUpload etc.
                        if (bodyHttpData.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
                            try {
                                requestParameterMap.put(bodyHttpData.getName(), ((Attribute) bodyHttpData).getValue());
                            } catch (IOException e) {
                                log.warn("Failed to read POST parameter '{}': {}", bodyHttpData.getName(), e.getMessage());
                            }
                        }
                    }
                } finally {
                    if (decoder2 != null) {
                        decoder2.destroy();
                    }
                }
            }
        }

        return requestParameterMap;
    }

    /**
     * URL-decodes the given string using UTF-8 encoding.
     *
     * @param string the URL-encoded string to decode
     * @return the decoded string
     */
    public static String decodeRequestString(String string) {
        return URLDecoder.decode(string, StandardCharsets.UTF_8);
    }

    /**
     * Encapsulates the details of an HTTP error response, including the status code,
     * human-readable error description, request path, optional message, and optional cause.
     */
    public static class HttpErrorMessage {

        private final HttpResponseStatus responseStatus;

        private final String timestamp;

        private final Integer status;

        private final String error;

        private final String message;

        private final String path;

        private final Throwable cause;

        /**
         * Creates a new HTTP error message with the given details.
         *
         * @param responseStatus the HTTP response status (e.g. 404, 500)
         * @param path           the request URI path (may be {@code null})
         * @param message        an optional human-readable error message (may be {@code null})
         * @param cause          the underlying exception cause (may be {@code null})
         */
        public HttpErrorMessage(HttpResponseStatus responseStatus, String path, String message, Throwable cause) {
            this.responseStatus = responseStatus;
            this.timestamp = ISO_TIMESTAMP_FORMATTER.format(ZonedDateTime.now(ZoneId.of("UTC")));
            this.status = responseStatus.code();
            this.error = responseStatus.reasonPhrase();
            this.message = message;
            this.path = path;
            this.cause = cause;
        }

        /** @return the HTTP response status object */
        public HttpResponseStatus getResponseStatus() {
            return responseStatus;
        }

        /**
         * Returns the ISO 8601 timestamp when this error was created.
         *
         * @return the error timestamp string
         * @deprecated Use {@link #getTimestamp()} instead. This method has a typo in its name.
         */
        @Deprecated
        public String getTimestrap() {
            return timestamp;
        }

        /**
         * Returns the ISO 8601 timestamp when this error was created.
         *
         * @return the error timestamp string
         */
        public String getTimestamp() {
            return timestamp;
        }

        /** @return the HTTP status code (e.g. 404, 500) */
        public Integer getStatus() {
            return status;
        }

        /** @return the HTTP reason phrase (e.g. "Not Found", "Internal Server Error") */
        public String getError() {
            return error;
        }

        /** @return the optional error message, or {@code null} */
        public String getMessage() {
            return message;
        }

        /** @return the request URI path, or {@code null} */
        public String getPath() {
            return path;
        }

        /** @return the underlying exception cause, or {@code null} */
        public Throwable getCause() {
            return cause;
        }

    }

}
