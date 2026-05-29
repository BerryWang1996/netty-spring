package com.github.berrywang1996.netty.spring.web.mvc.bind;

import com.github.berrywang1996.netty.spring.web.context.HttpRuntimeRecorder;
import com.github.berrywang1996.netty.spring.web.mvc.consts.HttpRequestMethod;
import com.github.berrywang1996.netty.spring.web.mvc.context.ResponseEntity;
import com.github.berrywang1996.netty.spring.web.mvc.view.HtmlViewHandler;
import com.github.berrywang1996.netty.spring.web.mvc.view.JsonViewHandler;
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
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestMappingResolverTest {

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

    public static class TestController {

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
