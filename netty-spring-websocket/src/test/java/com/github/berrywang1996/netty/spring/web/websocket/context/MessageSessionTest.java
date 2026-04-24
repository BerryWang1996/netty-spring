package com.github.berrywang1996.netty.spring.web.websocket.context;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static final class ContextHolder extends ChannelInboundHandlerAdapter {
        private ChannelHandlerContext ctx;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }
    }
}
