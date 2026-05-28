package com.github.berrywang1996.netty.spring.web.mvc.view;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;

/**
 * View handler that renders plain HTML string responses.
 * <p>
 * Converts a {@link String} containing HTML markup into a Netty {@link FullHttpResponse}
 * with content type {@code text/html} and UTF-8 encoding. This handler is selected
 * automatically when a controller method is not annotated with
 * {@link com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.ResponseBody @ResponseBody}
 * or {@link com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RestController @RestController}.
 *
 * @author berrywang1996
 * @version V1.0.0
 */
@Slf4j
public class HtmlViewHandler extends AbstractViewHandler<String> {

    /**
     * Constructs an {@code HtmlViewHandler} with default settings:
     * status 200 OK, content type {@code text/html}, charset UTF-8.
     */
    public HtmlViewHandler() {
        setStatus(HttpResponseStatus.OK);
        setContentType("text/html");
        setCharset("utf-8");
    }

    /**
     * Renders the given HTML string into a Netty {@link FullHttpResponse}.
     *
     * @param data the HTML markup to write into the response body
     * @return a fully constructed HTTP response with HTML content
     */
    @Override
    public FullHttpResponse handleView(String data) {

        // Guard against null data (e.g. controller returning null)
        if (data == null) {
            data = "";
        }

        // Encode the HTML string into a ByteBuf using the configured charset
        ByteBuf content = Unpooled.copiedBuffer(data, Charset.forName(getCharset()));
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        // Set standard response headers
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, getContentType());
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        return response;

    }

}
