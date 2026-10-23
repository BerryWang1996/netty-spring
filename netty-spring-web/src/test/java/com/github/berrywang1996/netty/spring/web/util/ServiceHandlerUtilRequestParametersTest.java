package com.github.berrywang1996.netty.spring.web.util;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ServiceHandlerUtilRequestParametersTest {

    @Test
    void leavesNonFormBodiesUnreadWhileParsingQueryParameters() {
        String json = "{\"createdAt\":0,\"name\":\"a=b&c=d\"}";
        for (String contentType : new String[] {
                "application/json; charset=UTF-8", "application/problem+json", "text/plain", null}) {
            DefaultFullHttpRequest request = request(HttpMethod.POST,
                    "/test?version=2&tag=a&tag=b", contentType, "prefix" + json);
            request.content().readerIndex("prefix".length());
            try {
                Map<String, String> parameters = ServiceHandlerUtil.parseRequestParameters(request);
                assertEquals(Map.of("version", "2", "tag[0]", "a", "tag[1]", "b"), parameters);
                assertEquals("prefix".length(), request.content().readerIndex());
                assertEquals(json, request.content().toString(StandardCharsets.UTF_8));
                assertEquals(1, request.refCnt());
            } finally {
                request.release();
            }
        }
    }

    @Test
    void decodesCompleteUrlEncodedBodyOnceAndPreservesIt() {
        String body = "name=hello+world&value=%252B&shared=body";
        DefaultFullHttpRequest request = request(HttpMethod.POST, "/test?shared=query&version=2",
                "application/x-www-form-urlencoded; charset=UTF-8", body);
        try {
            Map<String, String> parameters = ServiceHandlerUtil.parseRequestParameters(request);
            assertEquals(Map.of("name", "hello world", "value", "%2B", "shared", "body", "version", "2"),
                    parameters);
            assertEquals(0, request.content().readerIndex());
            assertEquals(body, request.content().toString(StandardCharsets.UTF_8));
            assertEquals(1, request.refCnt());
        } finally {
            request.release();
        }
    }

    @Test
    void decodesMultipartFieldsAndLeavesFileBodyForMultipartBinding() {
        String body = "--fixture\r\nContent-Disposition: form-data; name=\"shared\"\r\n\r\nbody\r\n"
                + "--fixture\r\nContent-Disposition: form-data; name=\"upload\"; filename=\"a.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\nfile contents\r\n--fixture--\r\n";
        DefaultFullHttpRequest request = request(HttpMethod.POST, "/test?shared=query&version=2",
                "multipart/form-data; boundary=fixture", body);
        try {
            Map<String, String> parameters = ServiceHandlerUtil.parseRequestParameters(request);
            assertEquals(Map.of("shared", "body", "version", "2"), parameters);
            assertFalse(parameters.containsKey("upload"));
            assertEquals(0, request.content().readerIndex());
            assertEquals(body, request.content().toString(StandardCharsets.UTF_8));
            assertEquals(1, request.refCnt());
        } finally {
            request.release();
        }
    }

    @Test
    void getStillParsesOnlyQueryParametersEvenWithAFormContentType() {
        DefaultFullHttpRequest request = request(HttpMethod.GET, "/test?name=query",
                "application/x-www-form-urlencoded", "name=body");
        try {
            assertEquals(Map.of("name", "query"), ServiceHandlerUtil.parseRequestParameters(request));
            assertEquals(0, request.content().readerIndex());
        } finally {
            request.release();
        }
    }

    private static DefaultFullHttpRequest request(HttpMethod method, String uri,
                                                   String contentType, String body) {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                method, uri, Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        if (contentType != null) {
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
        request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, request.content().readableBytes());
        return request;
    }
}
