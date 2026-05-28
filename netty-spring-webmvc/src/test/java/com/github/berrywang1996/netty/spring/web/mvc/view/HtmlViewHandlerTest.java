package com.github.berrywang1996.netty.spring.web.mvc.view;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HtmlViewHandlerTest {

    @Test
    void handleViewReturnsHtmlResponse() {
        HtmlViewHandler handler = new HtmlViewHandler();
        FullHttpResponse response = handler.handleView("<h1>Hello</h1>");
        try {
            assertEquals(HttpResponseStatus.OK, response.status());
            assertEquals("text/html", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertEquals("<h1>Hello</h1>", body);
        } finally {
            response.release();
        }
    }

    @Test
    void defaultContentTypeIsHtml() {
        HtmlViewHandler handler = new HtmlViewHandler();
        assertEquals("text/html", handler.getContentType());
        assertEquals("utf-8", handler.getCharset());
        assertEquals(HttpResponseStatus.OK, handler.getStatus());
    }

    @Test
    void handleViewWithEmptyString() {
        HtmlViewHandler handler = new HtmlViewHandler();
        FullHttpResponse response = handler.handleView("");
        try {
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertEquals("", body);
        } finally {
            response.release();
        }
    }

    @Test
    void handleViewWithNullReturnsEmptyBody() {
        HtmlViewHandler handler = new HtmlViewHandler();
        FullHttpResponse response = handler.handleView(null);
        try {
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertEquals("", body);
        } finally {
            response.release();
        }
    }

    @Test
    void handleViewUsesOverriddenStatus() {
        HtmlViewHandler handler = new HtmlViewHandler();
        handler.setStatus(HttpResponseStatus.CREATED);
        FullHttpResponse response = handler.handleView("<p>created</p>");
        try {
            assertEquals(HttpResponseStatus.CREATED, response.status());
        } finally {
            response.release();
        }
    }
}
