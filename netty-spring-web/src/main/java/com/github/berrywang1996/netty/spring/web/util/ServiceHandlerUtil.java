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

import javax.activation.MimetypesFileTypeMap;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

import static io.netty.handler.codec.http.HttpResponseStatus.FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_MODIFIED;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public class ServiceHandlerUtil {

    public static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";
    public static final String HTTP_DATE_GMT_TIMEZONE = "GMT";
    public static final int HTTP_CACHE_SECONDS = 60;

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

        // TODO  errorResponseHtml
        return "";

    }

    private static String errorResponseJson(HttpErrorMessage errorMessage) {

        // TODO  errorResponseJson
        return "";

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
        MimetypesFileTypeMap mimeTypesMap = new MimetypesFileTypeMap();
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, mimeTypesMap.getContentType(file.getPath()));
    }

    public static void setDateAndCacheHeaders(HttpResponse response, File file) {
        SimpleDateFormat dateFormatter = new SimpleDateFormat(ServiceHandlerUtil.HTTP_DATE_FORMAT, Locale.US);
        dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));

        // Date header
        Calendar time = new GregorianCalendar();
        response.headers().set(HttpHeaderNames.DATE, dateFormatter.format(time.getTime()));

        // Add cache headers
        time.add(Calendar.SECOND, HTTP_CACHE_SECONDS);
        response.headers().set(HttpHeaderNames.EXPIRES, dateFormatter.format(time.getTime()));
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "private, max-age=" + HTTP_CACHE_SECONDS);
        response.headers().set(
                HttpHeaderNames.LAST_MODIFIED, dateFormatter.format(new Date(file.lastModified())));
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
                                parameterEntry.getValue().get(i));
                    }
                } else {
                    requestParameterMap.put(parameterEntry.getKey(), parameterEntry.getValue().get(0));
                }
            }

        } else {

            // TODO 当请求类型为text/plain或者其他类型的时候无法获取参数
            // if request method is post
            if (request instanceof HttpContent) {
                HttpPostRequestDecoder decoder2 = new HttpPostRequestDecoder(request);
                decoder2.offer((HttpContent) request);
                List<InterfaceHttpData> bodyHttpDatas2 = decoder2.getBodyHttpDatas();
                for (InterfaceHttpData bodyHttpData : bodyHttpDatas2) {
                    try {
                        requestParameterMap.put(bodyHttpData.getName(), ((Attribute) bodyHttpData).getValue());
                    } catch (IOException ignored) {
                    }
                }
            }

        }

        return requestParameterMap;
    }

    public static class HttpErrorMessage {

        private HttpResponseStatus responseStatus;

        private String timestrap;

        private Integer status;

        private String error;

        private String message;

        private String path;

        private Throwable cause;

        public HttpErrorMessage(HttpResponseStatus responseStatus, String path, String message, Throwable cause) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            this.responseStatus = responseStatus;
            this.timestrap = sdf.format(new Date());
            this.status = responseStatus.code();
            this.error = responseStatus.reasonPhrase();
            this.message = message;
            this.path = path;
            this.cause = cause;
        }

        public HttpResponseStatus getResponseStatus() {
            return responseStatus;
        }

        public String getTimestrap() {
            return timestrap;
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
