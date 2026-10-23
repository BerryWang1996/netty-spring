package com.github.berrywang1996.netty.spring.web.websocket.context;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonMessageTest {

    // Captured with the Jackson 2.18.12 mapper shipped in 1.10.1.
    private static final String LEGACY_JSON = "{\"createdAt\":0,\"state\":\"READY\",\"optional\":null,"
            + "\"nested\":{\"count\":2},\"node\":{\"message\":\"\u96ea\",\"values\":[1,true,null]}}";

    @Test
    void preserves1101DateEnumNullMapAndJsonNodeWireValues() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("createdAt", new Date(0));
        payload.put("state", State.READY);
        payload.put("optional", null);
        payload.put("nested", Collections.singletonMap("count", 2));
        payload.put("node", JsonMapper.shared().readTree("{\"message\":\"\u96ea\",\"values\":[1,true,null]}"));
        JsonMessage message = new JsonMessage(payload);

        TextWebSocketFrame frame = message.responseMsg();
        ByteBuf sharedPayload = message.serializeSharedPayload(ByteBufAllocator.DEFAULT);
        try {
            assertEquals(LEGACY_JSON, frame.text());
            assertEquals(LEGACY_JSON, sharedPayload.toString(StandardCharsets.UTF_8));
        } finally {
            frame.release();
            sharedPayload.release();
        }
    }

    @Test
    void acceptsImmutableCustomMapperAndRestoresDefaultWhenSetToNull() {
        JsonMapper mapper = JsonMapper.builder().configureForJackson2()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(EnumFeature.WRITE_ENUMS_USING_TO_STRING)
                .build();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("date", new Date(0));
        payload.put("state", State.READY);
        JsonMessage message = new JsonMessage(payload, mapper);
        assertSame(mapper, message.getObjectMapper());
        assertFrame("{\"date\":\"1970-01-01T00:00:00.000+00:00\",\"state\":\"ready\"}", message);

        message.setObjectMapper(null);
        assertFrame("{\"date\":0,\"state\":\"READY\"}", message);
        message.setObjectMapper(mapper);
        assertSame(mapper, message.getObjectMapper());
    }

    @Test
    void preservesSerializationFailureContractForBothSendPaths() {
        JsonMessage message = new JsonMessage(new Object());
        assertInstanceOf(JacksonException.class,
                assertThrows(IllegalStateException.class, message::responseMsg).getCause());
        assertInstanceOf(JacksonException.class,
                assertThrows(IllegalStateException.class,
                        () -> message.serializeSharedPayload(ByteBufAllocator.DEFAULT)).getCause());
    }

    @Test
    void nullContentRemainsJsonNull() {
        assertFrame("null", new JsonMessage(null));
    }

    private static void assertFrame(String expected, JsonMessage message) {
        TextWebSocketFrame frame = message.responseMsg();
        try {
            assertEquals(expected, frame.text());
        } finally {
            frame.release();
        }
    }

    private enum State {
        READY;

        @Override
        public String toString() {
            return "ready";
        }
    }

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
