package com.github.berrywang1996.netty.spring.web.mvc.view;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;

/**
 * View handler that renders JSON responses by serializing Java objects with Jackson's {@link ObjectMapper}.
 * <p>
 * This handler is selected when a controller method or its declaring class is annotated with
 * {@link com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.ResponseBody @ResponseBody}
 * or {@link com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RestController @RestController}.
 * The default content type is {@code application/json} with UTF-8 encoding. Dates are formatted
 * as ISO-8601 strings, and empty beans are serialized as empty JSON objects.
 *
 * @author berrywang1996
 * @version V1.0.0
 */
@Slf4j
public class JsonViewHandler extends AbstractViewHandler<Object> {

    /** Shared, pre-configured Jackson ObjectMapper for JSON serialization. */
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            // Preserve the 1.x JSON contract while retaining Jackson 3 stream constraints.
            .configureForJackson2()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

    /**
     * Constructs a {@code JsonViewHandler} with default settings:
     * status 200 OK, content type {@code application/json}, charset UTF-8.
     */
    public JsonViewHandler() {
        setStatus(HttpResponseStatus.OK);
        setContentType("application/json");
        setCharset("utf-8");
    }

    /**
     * Serializes the given data object into a JSON response.
     * <p>
     * If serialization fails, an empty JSON string is written to the response body
     * and a warning is logged.
     *
     * @param data the object to serialize as JSON
     * @return a fully constructed HTTP response with JSON content
     */
    @Override
    public FullHttpResponse handleView(Object data) {

        // Serialize the data object to a JSON string
        String jsonString = "";
        try {
            jsonString = OBJECT_MAPPER.writeValueAsString(data);
        } catch (JacksonException e) {
            log.warn("Failed to serialize response to JSON: {}", e.getMessage(), e);
        }
        // Encode JSON string into a ByteBuf and build the HTTP response
        ByteBuf content = Unpooled.copiedBuffer(jsonString, Charset.forName(getCharset()));
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, getStatus(), content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, getContentType());
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        return response;

    }

}
