/*
 * Copyright 2018 berrywang1996
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.berrywang1996.netty.spring.web.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Content-type-aware GZIP compression handler for Netty HTTP responses.
 *
 * <p>Extends {@link HttpContentCompressor} to selectively apply GZIP compression
 * only to responses whose {@code Content-Type} matches one of the configured
 * compressible MIME types (e.g. "text/html", "application/json").
 *
 * <p>Responses with content types not in the allowed set are passed through
 * without compression, avoiding unnecessary CPU overhead for binary content
 * such as images or already-compressed files.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public class CompressorHandler extends HttpContentCompressor {

    /** Set of MIME type prefixes eligible for GZIP compression. */
    private final Set<String> gzipTypes;

    /**
     * Creates a new compression handler with the specified compression parameters.
     *
     * @param compressionLevel     the GZIP compression level (1-9, where 9 is maximum compression)
     * @param windowBits           the base-2 logarithm of the compression window size
     * @param memLevel             the memory level for internal compression state
     * @param contentSizeThreshold the minimum content size in bytes before compression is applied
     * @param gzipTypes            space-separated list of MIME type prefixes eligible for compression
     */
    public CompressorHandler(int compressionLevel, int windowBits, int memLevel,
                             int contentSizeThreshold, String gzipTypes) {
        super(compressionLevel, windowBits, memLevel, contentSizeThreshold);
        this.gzipTypes = new HashSet<>(Arrays.asList(gzipTypes.split(" ")));
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    /**
     * Determines whether the response should be compressed based on its Content-Type header.
     *
     * <p>Compression is applied only when the response content type starts with one of
     * the configured GZIP type prefixes. If no match is found, {@code null} is returned
     * to skip compression entirely.
     *
     * @param headers        the HTTP response headers to inspect
     * @param acceptEncoding the client's accepted encoding from the request
     * @return the encoding result if compression should be applied, or {@code null} to skip
     * @throws Exception if the parent encoder encounters an error
     */
    @Override
    protected Result beginEncode(HttpResponse headers, String acceptEncoding) throws Exception {

        boolean shouldEncode = false;

        // Check if the response content type matches any of the configured compressible types
        String contentType = headers.headers().get(HttpHeaderNames.CONTENT_TYPE);
        for (String gzipType : gzipTypes) {
            if (contentType.startsWith(gzipType)) {
                shouldEncode = true;
                break;
            }
        }

        if (shouldEncode) {
            return super.beginEncode(headers, acceptEncoding);
        }
        // Return null to indicate that compression should not be applied
        return null;

    }

}
