package com.github.berrywang1996.netty.spring.web.mvc.bind;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import com.github.berrywang1996.netty.spring.web.context.HttpRuntimeRecorder;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.CrossOrigin;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestBody;
import com.github.berrywang1996.netty.spring.web.mvc.consts.HttpRequestMethod;
import com.github.berrywang1996.netty.spring.web.mvc.context.ResponseEntity;
import com.github.berrywang1996.netty.spring.web.mvc.view.HtmlViewHandler;
import com.github.berrywang1996.netty.spring.web.mvc.view.JsonViewHandler;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestMappingResolverTest {

    @Test
    void requestBodyPreserves1101DateEnumNullAndAnnotationBinding() throws Exception {
        JsonController controller = new JsonController();
        assertJsonRequest("{\"createdAt\":0,\"state\":\"READY\",\"count\":null,\"optional\":null,"
                        + "\"nested\":{\"count\":2},\"node\":{\"values\":[1,true,null]},\"external_name\":\"alpha\"}",
                HttpResponseStatus.OK, controller);
        assertNotNull(controller.received);
        assertEquals(new Date(0), controller.received.createdAt);
        assertEquals(State.READY, controller.received.state);
        assertEquals(0, controller.received.count);
        assertNull(controller.received.optional);
        assertEquals(2, controller.received.nested.get("count"));
        assertTrue(controller.received.node.get("values").get(2).isNull());
        assertEquals("alpha", controller.received.name);
    }

    @Test
    void requestBodyStillRejectsUnknownFields() throws Exception {
        JsonController controller = new JsonController();
        String error = assertJsonRequest("{\"unexpected\":true}", HttpResponseStatus.BAD_REQUEST, controller);
        assertTrue(error.contains("Failed to parse request body as JsonPayload"), error);
        assertTrue(error.contains("unexpected"), error);
        assertNull(controller.received);
    }

    @Test
    void requestBodyStillUsesEnumNameInsteadOfToString() throws Exception {
        JsonController controller = new JsonController();
        String error = assertJsonRequest("{\"state\":\"ready\"}", HttpResponseStatus.BAD_REQUEST, controller);
        assertTrue(error.contains("Failed to parse request body as JsonPayload"), error);
        assertTrue(error.contains("ready"), error);
        assertNull(controller.received);
    }

    private static String assertJsonRequest(String json, HttpResponseStatus expectedStatus,
                                          JsonController controller) throws Exception {
        Method method = JsonController.class.getMethod("handle", JsonPayload.class);
        RequestMappingResolver resolver = new RequestMappingResolver("/json",
                Collections.singletonMap(HttpRequestMethod.POST, method), controller, new JsonViewHandler());
        EmbeddedChannel channel = new EmbeddedChannel(new SimpleChannelInboundHandler<FullHttpRequest>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
                resolver.resolve(ctx, msg);
            }
        });
        try {
            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                    HttpMethod.POST, "/json", Unpooled.copiedBuffer(json, StandardCharsets.UTF_8));
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            channel.writeInbound(request);
            FullHttpResponse response = channel.readOutbound();
            assertNotNull(response);
            try {
                String body = response.content().toString(StandardCharsets.UTF_8);
                assertEquals(expectedStatus, response.status(), body);
                return body;
            } finally {
                response.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    public static class JsonController {
        private JsonPayload received;

        public JsonPayload handle(@RequestBody JsonPayload payload) {
            received = payload;
            return payload;
        }
    }

    public static class JsonPayload {
        public Date createdAt;
        public State state;
        public int count;
        public String optional;
        public Map<String, Object> nested;
        public JsonNode node;
        @JsonProperty("external_name")
        public String name;
    }

    public enum State {
        READY;

        @Override
        public String toString() {
            return "ready";
        }
    }

    @Test
    void resolveClosesChannelWhenHttpResponseWriteFails() throws Exception {
        Method method = TestController.class.getMethod("handle");
        RequestMappingResolver resolver = new RequestMappingResolver(
                "/test",
                Collections.singletonMap(HttpRequestMethod.GET, method),
                new TestController(),
                new HtmlViewHandler());
        HttpRuntimeRecorder recorder = new HttpRuntimeRecorder();
        resolver.setHttpRuntimeRecorder(recorder);
        AtomicBoolean writeFailed = new AtomicBoolean();
        EmbeddedChannel channel = new EmbeddedChannel(
                new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                        ReferenceCountUtil.release(msg);
                        writeFailed.set(true);
                        promise.setFailure(new IOException("boom"));
                    }
                },
                new SimpleChannelInboundHandler<FullHttpRequest>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
                        resolver.resolve(ctx, msg);
                    }
                });

        try {
            channel.writeInbound(new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.GET,
                    "/test"));
            channel.runPendingTasks();

            assertTrue(writeFailed.get());
            assertFalse(channel.isOpen());
            assertEquals(1L, recorder.getRuntimeStats().getHttpResponseWriteFailureCount());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    // ---- v1.7.0 Fix #5: ResponseEntity header CRLF sanitization ----

    @Test
    void responseEntityHeadersCrlfIsSanitized() throws Exception {
        Method method = CrlfTestController.class.getMethod("handleWithCrlf");
        RequestMappingResolver resolver = new RequestMappingResolver(
                "/crlf",
                Collections.singletonMap(HttpRequestMethod.GET, method),
                new CrlfTestController(),
                new JsonViewHandler());
        HttpRuntimeRecorder recorder = new HttpRuntimeRecorder();
        resolver.setHttpRuntimeRecorder(recorder);
        AtomicReference<FullHttpResponse> capturedResponse = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(
                new SimpleChannelInboundHandler<FullHttpRequest>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
                        resolver.resolve(ctx, msg);
                    }
                });

        try {
            channel.writeInbound(new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.GET,
                    "/crlf"));
            channel.runPendingTasks();

            FullHttpResponse response = channel.readOutbound();
            assertFalse(response == null, "Response should not be null");
            // The X-Custom header value must have CRLF stripped
            String customHeader = response.headers().get("X-Custom");
            assertTrue(customHeader != null && !customHeader.contains("\r") && !customHeader.contains("\n"),
                    "CRLF characters must be stripped from ResponseEntity headers. Got: " + customHeader);
            ReferenceCountUtil.release(response);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    // ---- v1.7.1 Fix #1: CORS wildcard + allowCredentials must not echo Origin ----

    @Test
    void corsWildcardWithAllowCredentialsRefusesToEchoOrigin() throws Exception {
        // Misconfigured: origins="*" + allowCredentials=true. Pre-1.7.1 echoed the
        // request Origin verbatim, effectively allowing any origin with credentials —
        // a CSRF / cross-origin credential leak surface. The fix: serve `*` and
        // suppress Allow-Credentials with a warning log.
        Method method = WildcardCredentialsController.class.getMethod("handle");
        RequestMappingResolver resolver = new RequestMappingResolver(
                "/cors-wildcard",
                Collections.singletonMap(HttpRequestMethod.GET, method),
                new WildcardCredentialsController(),
                new JsonViewHandler());
        resolver.setCrossOrigin(method.getAnnotation(CrossOrigin.class));
        EmbeddedChannel channel = new EmbeddedChannel(
                new SimpleChannelInboundHandler<FullHttpRequest>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
                        resolver.resolve(ctx, msg);
                    }
                });

        try {
            DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, "/cors-wildcard");
            request.headers().set(HttpHeaderNames.ORIGIN, "https://evil.example");
            channel.writeInbound(request);
            channel.runPendingTasks();

            FullHttpResponse response = channel.readOutbound();
            assertNotNull(response);
            try {
                // Allow-Origin must be the safe wildcard, NOT the attacker-controlled Origin.
                assertEquals("*", response.headers().get("Access-Control-Allow-Origin"));
                // Allow-Credentials must NOT be emitted alongside wildcard origin.
                assertNull(response.headers().get("Access-Control-Allow-Credentials"),
                        "Allow-Credentials must not be set when Allow-Origin is wildcard");
            } finally {
                ReferenceCountUtil.release(response);
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void corsExplicitOriginWithCredentialsStillWorks() throws Exception {
        // The fix must not regress the legitimate case: explicit origin + credentials.
        Method method = ExplicitOriginCredentialsController.class.getMethod("handle");
        RequestMappingResolver resolver = new RequestMappingResolver(
                "/cors-explicit",
                Collections.singletonMap(HttpRequestMethod.GET, method),
                new ExplicitOriginCredentialsController(),
                new JsonViewHandler());
        resolver.setCrossOrigin(method.getAnnotation(CrossOrigin.class));
        EmbeddedChannel channel = new EmbeddedChannel(
                new SimpleChannelInboundHandler<FullHttpRequest>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
                        resolver.resolve(ctx, msg);
                    }
                });

        try {
            DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, "/cors-explicit");
            request.headers().set(HttpHeaderNames.ORIGIN, "https://trusted.example");
            channel.writeInbound(request);
            channel.runPendingTasks();

            FullHttpResponse response = channel.readOutbound();
            assertNotNull(response);
            try {
                assertEquals("https://trusted.example", response.headers().get("Access-Control-Allow-Origin"));
                assertEquals("true", response.headers().get("Access-Control-Allow-Credentials"));
                assertEquals("Origin", response.headers().get("Vary"));
            } finally {
                ReferenceCountUtil.release(response);
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    public static class TestController {

        public String handle() {
            return "ok";
        }
    }

    public static class WildcardCredentialsController {
        @CrossOrigin(origins = "*", allowCredentials = true)
        public String handle() {
            return "ok";
        }
    }

    public static class ExplicitOriginCredentialsController {
        @CrossOrigin(origins = "https://trusted.example", allowCredentials = true)
        public String handle() {
            return "ok";
        }
    }

    public static class CrlfTestController {

        public ResponseEntity<String> handleWithCrlf() {
            return ResponseEntity.status(200)
                    .header("X-Custom", "value\r\nX-Injected: malicious")
                    .body("data");
        }
    }
}
