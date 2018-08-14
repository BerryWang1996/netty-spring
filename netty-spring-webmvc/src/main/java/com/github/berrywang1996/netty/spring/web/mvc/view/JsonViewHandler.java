package com.github.berrywang1996.netty.spring.web.mvc.view;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import java.text.SimpleDateFormat;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
@Slf4j
public class JsonViewHandler extends AbstractViewHandler<Object> {

    public JsonViewHandler() {
        setStatus(HttpResponseStatus.OK);
        setContentType("application/json");
        setCharset("utf-8");
    }

    @Override
    public FullHttpResponse handleView(Object data) {

        // TODO 对象是否能优化？
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd"));

        String jsonString = "";
        try {
            jsonString = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("Parse json value failed! {}", e);
        }
        ByteBuf content = Unpooled.copiedBuffer(jsonString, Charset.forName(getCharset()));
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, getContentType());
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        response.headers().get(HttpHeaderNames.COOKIE, "");
        return response;

    }

}
