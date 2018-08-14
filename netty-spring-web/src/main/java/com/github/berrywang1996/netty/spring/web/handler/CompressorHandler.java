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
 * @author berrywang1996
 * @since V1.0.0
 */
public class CompressorHandler extends HttpContentCompressor {

    private final Set<String> gzipTypes;

    public CompressorHandler(int compressionLevel, int windowBits, int memLevel,
                             int contentSizeThreshold, String gzipTypes) {
        super(compressionLevel, windowBits, memLevel, contentSizeThreshold);
        this.gzipTypes = new HashSet<>(Arrays.asList(gzipTypes.split(" ")));
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    @Override
    protected Result beginEncode(HttpResponse headers, String acceptEncoding) throws Exception {

        boolean shouldEncode = false;

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
        return null;

    }

}
