package com.github.berrywang1996.netty.spring.web.mvc.view;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonViewHandlerTest {

    @Test
    void handleViewReturnsJsonResponse() {
        JsonViewHandler handler = new JsonViewHandler();
        Map<String, Object> data = new HashMap<>();
        data.put("key", "value");

        FullHttpResponse response = handler.handleView(data);
        try {
            assertEquals(HttpResponseStatus.OK, response.status());
            String contentType = response.headers().get(HttpHeaderNames.CONTENT_TYPE);
            assertEquals("application/json", contentType);
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"key\""));
            assertTrue(body.contains("\"value\""));
        } finally {
            response.release();
        }
    }

    @Test
    void handleViewWithNullReturnsNullString() {
        JsonViewHandler handler = new JsonViewHandler();
        FullHttpResponse response = handler.handleView(null);
        try {
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertEquals("null", body);
        } finally {
            response.release();
        }
    }

    @Test
    void handleViewWithStringReturnsQuotedString() {
        JsonViewHandler handler = new JsonViewHandler();
        FullHttpResponse response = handler.handleView("hello");
        try {
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertEquals("\"hello\"", body);
        } finally {
            response.release();
        }
    }

    @Test
    void defaultContentTypeIsJson() {
        JsonViewHandler handler = new JsonViewHandler();
        assertEquals("application/json", handler.getContentType());
        assertEquals("utf-8", handler.getCharset());
        assertEquals(HttpResponseStatus.OK, handler.getStatus());
    }

    @Test
    void handleViewUsesOverriddenStatus() {
        JsonViewHandler handler = new JsonViewHandler();
        handler.setStatus(HttpResponseStatus.CREATED);
        FullHttpResponse response = handler.handleView("created");
        try {
            assertEquals(HttpResponseStatus.CREATED, response.status());
        } finally {
            response.release();
        }
    }
}
