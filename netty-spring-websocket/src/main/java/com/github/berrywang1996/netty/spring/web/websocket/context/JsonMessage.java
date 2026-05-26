package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

/**
 * WebSocket text message that serializes a Java object as JSON.
 */
public class JsonMessage extends AbstractMessage<TextWebSocketFrame> {

    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = new ObjectMapper();

    private volatile Object content;

    private volatile ObjectMapper objectMapper;

    public JsonMessage(Object content) {
        this(content, DEFAULT_OBJECT_MAPPER);
    }

    public JsonMessage(Object content, ObjectMapper objectMapper) {
        this.content = content;
        this.objectMapper = objectMapper == null ? DEFAULT_OBJECT_MAPPER : objectMapper;
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? DEFAULT_OBJECT_MAPPER : objectMapper;
    }

    @Override
    public TextWebSocketFrame responseMsg() {
        try {
            return new TextWebSocketFrame(objectMapper.writeValueAsString(content));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialize websocket JSON message failed.", e);
        }
    }
}
