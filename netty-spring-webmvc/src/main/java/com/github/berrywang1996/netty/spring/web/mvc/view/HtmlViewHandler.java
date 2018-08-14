package com.github.berrywang1996.netty.spring.web.mvc.view;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
@Slf4j
public class HtmlViewHandler extends AbstractViewHandler<String> {

    public HtmlViewHandler() {
        setStatus(HttpResponseStatus.OK);
        setContentType("text/html");
        setCharset("utf-8");
    }

    @Override
    public FullHttpResponse handleView(String data) {

        ByteBuf content = Unpooled.copiedBuffer(data, Charset.forName(getCharset()));
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, getContentType());
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        response.headers().get(HttpHeaderNames.COOKIE, "");
        return response;

    }

}
