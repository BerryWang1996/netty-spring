package com.github.berrywang1996.netty.spring.web.websocket.context;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageSessionTest {

    @Test
    void releaseIsIdempotent() {
        ContextHolder holder = new ContextHolder();
        EmbeddedChannel channel = new EmbeddedChannel(holder);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, GET, "/ws/test");
        MessageSession session = new MessageSession("session-1", holder.ctx, request);
        request.release();

        try {
            assertEquals(1, session.getFirstRequest().refCnt());
            assertDoesNotThrow(session::release);
            assertEquals(0, session.getFirstRequest().refCnt());
            assertDoesNotThrow(session::release);
            assertEquals(0, session.getFirstRequest().refCnt());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void exposesRequestQueryAndHeaderSnapshots() {
        ContextHolder holder = new ContextHolder();
        EmbeddedChannel channel = new EmbeddedChannel(holder);
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                GET,
                "/ws/chat?room=alpha&tag=one&tag=two&encoded=a%20b");
        request.headers().add("X-Trace-Id", "trace-1");
        request.headers().add("X-Trace-Id", "trace-2");
        request.headers().add("X-Client", "demo");
        MessageSession session = new MessageSession("session-1", holder.ctx, request);
        request.release();

        try {
            assertEquals("/ws/chat?room=alpha&tag=one&tag=two&encoded=a%20b", session.getUri());
            assertEquals("/ws/chat", session.getPath());
            assertEquals("alpha", session.getQueryParam("room"));
            assertEquals("a b", session.getQueryParam("encoded"));
            assertNull(session.getQueryParam("missing"));
            assertEquals(Arrays.asList("one", "two"), session.getQueryParams("tag"));
            assertEquals(Collections.emptyList(), session.getQueryParams("missing"));

            Map<String, List<String>> queryParams = session.getQueryParams();
            assertEquals(Arrays.asList("one", "two"), queryParams.get("tag"));
            assertThrows(UnsupportedOperationException.class, () -> queryParams.put("new", Collections.singletonList("value")));
            assertThrows(UnsupportedOperationException.class, () -> queryParams.get("tag").add("three"));

            assertEquals("trace-1", session.getHeader("X-Trace-Id"));
            assertEquals(Arrays.asList("trace-1", "trace-2"), session.getHeaders("X-Trace-Id"));
            assertEquals(Collections.emptyList(), session.getHeaders("Missing"));
            assertTrue(session.getHeaderNames().contains("X-Client"));
            assertThrows(UnsupportedOperationException.class, () -> session.getHeaderNames().add("Other"));
        } finally {
            session.release();
            channel.finishAndReleaseAll();
        }
    }

    private static final class ContextHolder extends ChannelInboundHandlerAdapter {
        private ChannelHandlerContext ctx;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }
    }
}
