package com.github.berrywang1996.netty.spring.web.websocket.context;

import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextMessageTest {

    @Test
    void createsTextWebSocketFrameFromContent() {
        TextWebSocketFrame frame = new TextMessage("hello").responseMsg();
        try {
            assertEquals("hello", frame.text());
        } finally {
            frame.release();
        }
    }
}
