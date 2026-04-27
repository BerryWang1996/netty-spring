package com.github.berrywang1996.netty.spring.web.websocket.context;

import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonMessageTest {

    @Test
    void createsTextWebSocketFrameFromObjectJson() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("room", "alpha");
        payload.put("count", 2);

        TextWebSocketFrame frame = new JsonMessage(payload).responseMsg();

        try {
            assertEquals("{\"room\":\"alpha\",\"count\":2}", frame.text());
        } finally {
            ReferenceCountUtil.release(frame);
        }
    }

    @Test
    void usesUpdatedContentForRepeatedSends() {
        JsonMessage message = new JsonMessage(new Payload("first"));
        message.setContent(new Payload("second"));

        TextWebSocketFrame frame = message.responseMsg();

        try {
            assertEquals("{\"name\":\"second\"}", frame.text());
        } finally {
            ReferenceCountUtil.release(frame);
        }
    }

    public static final class Payload {
        private final String name;

        public Payload(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }
}
