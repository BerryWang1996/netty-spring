package com.github.berrywang1996.netty.spring.web.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CompressorHandler} to verify content-type-aware GZIP compression.
 */
class CompressorHandlerTest {

    /**
     * Verifies that a response with no Content-Type header does not cause a NullPointerException.
     * This can happen with 204 No Content or 304 Not Modified responses.
     */
    @Test
    void responseWithoutContentTypeDoesNotThrowNPE() {
        CompressorHandler handler = new CompressorHandler(6, 15, 8, 0, "text/html application/json");
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpServerCodec(),
                handler,
                new HttpObjectAggregator(65536));

        // Send a request with Accept-Encoding: gzip to trigger compression logic
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        channel.writeInbound(request);

        // Send a response WITHOUT Content-Type header (e.g. 204 No Content)
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT, Unpooled.EMPTY_BUFFER);
        // Deliberately no Content-Type set

        // This should not throw NullPointerException
        assertDoesNotThrow(() -> channel.writeOutbound(response));

        channel.finishAndReleaseAll();
    }

    /**
     * Verifies that a response with a compressible Content-Type is processed without error.
     */
    @Test
    void responseWithCompressibleContentTypeIsAccepted() {
        CompressorHandler handler = new CompressorHandler(6, 15, 8, 0, "text/html application/json");
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpServerCodec(),
                handler,
                new HttpObjectAggregator(65536));

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        channel.writeInbound(request);

        byte[] body = "Hello World".getBytes();
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);

        assertDoesNotThrow(() -> channel.writeOutbound(response));

        channel.finishAndReleaseAll();
    }

    /**
     * Verifies that a response with a non-compressible Content-Type passes through
     * without compression.
     */
    @Test
    void responseWithNonCompressibleContentTypeSkipsCompression() {
        CompressorHandler handler = new CompressorHandler(6, 15, 8, 0, "text/html application/json");
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpServerCodec(),
                handler,
                new HttpObjectAggregator(65536));

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/image.png");
        request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        channel.writeInbound(request);

        byte[] body = new byte[]{0x00, 0x01, 0x02};
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "image/png");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);

        assertDoesNotThrow(() -> channel.writeOutbound(response));

        channel.finishAndReleaseAll();
    }
}
