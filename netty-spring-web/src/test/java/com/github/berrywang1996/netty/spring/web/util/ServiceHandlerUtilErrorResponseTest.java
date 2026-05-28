package com.github.berrywang1996.netty.spring.web.util;

import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServiceHandlerUtilErrorResponseTest {

    @Test
    void httpErrorMessageTimestampIsNotNull() {
        ServiceHandlerUtil.HttpErrorMessage msg = new ServiceHandlerUtil.HttpErrorMessage(
                HttpResponseStatus.NOT_FOUND, "/test", "not found", null);
        assertNotNull(msg.getTimestamp());
        assertFalse(msg.getTimestamp().isEmpty());
    }

    @Test
    void httpErrorMessageFieldsAreCorrect() {
        ServiceHandlerUtil.HttpErrorMessage msg = new ServiceHandlerUtil.HttpErrorMessage(
                HttpResponseStatus.INTERNAL_SERVER_ERROR, "/api/data", "something broke",
                new RuntimeException("boom"));
        assertEquals(500, msg.getStatus());
        assertEquals("Internal Server Error", msg.getError());
        assertEquals("/api/data", msg.getPath());
        assertEquals("something broke", msg.getMessage());
        assertNotNull(msg.getCause());
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, msg.getResponseStatus());
    }

    @Test
    void deprecatedGetTimestrapStillWorks() {
        ServiceHandlerUtil.HttpErrorMessage msg = new ServiceHandlerUtil.HttpErrorMessage(
                HttpResponseStatus.OK, "/", null, null);
        // The deprecated method should return the same value as the new method
        assertEquals(msg.getTimestamp(), msg.getTimestrap());
    }

    @Test
    void decodeRequestStringDecodesUtf8() {
        assertEquals("hello world", ServiceHandlerUtil.decodeRequestString("hello+world"));
        assertEquals("a=b", ServiceHandlerUtil.decodeRequestString("a%3Db"));
    }

    @Test
    void decodeRequestStringHandlesPlainText() {
        assertEquals("simple", ServiceHandlerUtil.decodeRequestString("simple"));
    }

    @Test
    void parseRequestParametersFromGetRequest() {
        io.netty.handler.codec.http.DefaultFullHttpRequest request =
                new io.netty.handler.codec.http.DefaultFullHttpRequest(
                        io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                        io.netty.handler.codec.http.HttpMethod.GET,
                        "/test?name=hello&age=25");
        java.util.Map<String, String> params = ServiceHandlerUtil.parseRequestParameters(request);
        assertEquals("hello", params.get("name"));
        assertEquals("25", params.get("age"));
        request.release();
    }

    @Test
    void parseRequestParametersFromGetWithMultipleValues() {
        io.netty.handler.codec.http.DefaultFullHttpRequest request =
                new io.netty.handler.codec.http.DefaultFullHttpRequest(
                        io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                        io.netty.handler.codec.http.HttpMethod.GET,
                        "/test?tag=a&tag=b");
        java.util.Map<String, String> params = ServiceHandlerUtil.parseRequestParameters(request);
        // Multiple values get indexed
        assertEquals("a", params.get("tag[0]"));
        assertEquals("b", params.get("tag[1]"));
        request.release();
    }

    @Test
    void parseRequestParametersDoesNotDoubleDecodeQueryValues() {
        io.netty.handler.codec.http.DefaultFullHttpRequest request =
                new io.netty.handler.codec.http.DefaultFullHttpRequest(
                        io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                        io.netty.handler.codec.http.HttpMethod.GET,
                        "/redirect?next=%252Fadmin");
        java.util.Map<String, String> params = ServiceHandlerUtil.parseRequestParameters(request);
        assertEquals("%2Fadmin", params.get("next"));
        request.release();
    }

    @Test
    void parseRequestParametersFromGetWithNoParams() {
        io.netty.handler.codec.http.DefaultFullHttpRequest request =
                new io.netty.handler.codec.http.DefaultFullHttpRequest(
                        io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                        io.netty.handler.codec.http.HttpMethod.GET,
                        "/test");
        java.util.Map<String, String> params = ServiceHandlerUtil.parseRequestParameters(request);
        assertTrue(params.isEmpty());
        request.release();
    }
}
