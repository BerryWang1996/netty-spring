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
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import lombok.extern.slf4j.Slf4j;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
@Slf4j
public class ServiceHandler extends SimpleChannelInboundHandler<Object> {

    private MappingRuntimeSupporter mappingRuntimeSupporter;

    public ServiceHandler(MappingRuntimeSupporter mappingRuntimeSupporter) {
        this.mappingRuntimeSupporter = mappingRuntimeSupporter;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (msg instanceof FullHttpRequest) {

            FullHttpRequest request = (FullHttpRequest) msg;
            String baseUri = request.uri();
            MappingResolver mappingResolver = mappingRuntimeSupporter.getMappingResolverMap().get(baseUri);

            if (mappingResolver != null) {
                // if mapped
                mappingResolver.resolve(ctx, msg);
            } else {
                // if not mapped, may be request a file
                handleFile(ctx, (FullHttpRequest) msg);
            }

        } else if (msg instanceof WebSocketFrame) {

            WebSocketFrame webSocketFrame = (WebSocketFrame) msg;

        }

    }

    private void handleFile(ChannelHandlerContext ctx, FullHttpRequest msg) {

    }

}
