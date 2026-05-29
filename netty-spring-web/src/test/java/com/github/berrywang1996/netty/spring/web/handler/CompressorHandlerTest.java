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
     * v1.7.1: Verifies that comma-separated and whitespace-mixed gzipTypes configuration
     * actually parses AND that compression actually runs end-to-end on the wire (pre-1.7.1
     * `split(" ")` collapsed `text/html,application/json` into one unmatchable token and
     * compression silently never triggered — `assertDoesNotThrow` alone would not catch
     * the regression). Also verifies Content-Type comparison is case-insensitive
     * (RFC 7231 §3.1.1.1).
     */
    @Test
    void gzipTypesParserAcceptsCommaAndWhitespaceSeparators() throws Exception {
        CompressorHandler handler = new CompressorHandler(6, 15, 8, 0,
                "text/html, application/json\ttext/plain ,application/xml");
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpServerCodec(),
                handler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        channel.writeInbound(request);
        // Drain the inbound side so the request doesn't linger.
        Object inbound;
        while ((inbound = channel.readInbound()) != null) {
            io.netty.util.ReferenceCountUtil.release(inbound);
        }

        // Large, highly-compressible body so the encoder's output is visible on the wire.
        byte[] body = new byte[1024];
        java.util.Arrays.fill(body, (byte) 'A');
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
        // Mixed-case Content-Type to exercise the case-insensitive path.
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "Application/XML; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        channel.writeOutbound(response);

        // Collect all encoded outbound bytes and look for the Content-Encoding: gzip header.
        StringBuilder wire = new StringBuilder();
        Object out;
        while ((out = channel.readOutbound()) != null) {
            if (out instanceof io.netty.buffer.ByteBuf) {
                io.netty.buffer.ByteBuf buf = (io.netty.buffer.ByteBuf) out;
                wire.append(buf.toString(io.netty.util.CharsetUtil.ISO_8859_1));
                buf.release();
            }
        }
        assertTrue(wire.toString().toLowerCase().contains("content-encoding: gzip"),
                "Response must carry `Content-Encoding: gzip` — if absent, the gzipTypes "
                        + "comma/whitespace parser or case-insensitive Content-Type match "
                        + "has regressed. Outbound wire bytes were: " + wire);

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
