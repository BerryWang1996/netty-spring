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

package com.github.berrywang1996.netty.spring.web.core;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public class NettyServerHttpContentCompressor extends HttpContentCompressor {

    private final int compressionLevel;
    private final int windowBits;
    private final int memLevel;
    private final int contentSizeThreshold;
    private ChannelHandlerContext ctx;

    private Set<String> gzip_types;

    public NettyServerHttpContentCompressor(int compressionLevel, int windowBits, int memLevel,
                                            int contentSizeThreshold, String gzip_types) {
        super(compressionLevel, windowBits, memLevel, contentSizeThreshold);
        this.compressionLevel = compressionLevel;
        this.windowBits = windowBits;
        this.memLevel = memLevel;
        this.contentSizeThreshold = contentSizeThreshold;
        this.gzip_types = new HashSet<>(Arrays.asList(gzip_types.split(" ")));
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        super.channelActive(ctx);
    }

    @Override
    protected Result beginEncode(HttpResponse headers, String acceptEncoding) throws Exception {

        boolean shouldEncode = false;

        String contentType = headers.headers().get(HttpHeaderNames.CONTENT_TYPE);
        for (String gzip_type : gzip_types) {
            if (contentType.startsWith(gzip_type)) {
                shouldEncode = true;
                break;
            }
        }

        if (shouldEncode) {
            return super.beginEncode(headers, acceptEncoding);
        }
        return null;

    }

}
