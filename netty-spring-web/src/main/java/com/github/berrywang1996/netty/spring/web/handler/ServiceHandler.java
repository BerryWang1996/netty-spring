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

import com.github.berrywang1996.netty.spring.web.context.MappingResolver;
import com.github.berrywang1996.netty.spring.web.context.WebMappingSupporter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public class ServiceHandler extends SimpleChannelInboundHandler<Object> {

    private WebMappingSupporter mappingRuntimeSupporter;

    private PathMatcher pathMatcher;

    public ServiceHandler(WebMappingSupporter mappingRuntimeSupporter) {
        this.mappingRuntimeSupporter = mappingRuntimeSupporter;
        this.pathMatcher = new AntPathMatcher();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (msg instanceof FullHttpRequest) {

            FullHttpRequest request = (FullHttpRequest) msg;
            String baseUri = getBaseUri(request);

            // get mapping resolver
            MappingResolver mappingResolver = getMappingResolver(baseUri);

            if (mappingResolver != null) {
                // if mapped
                mappingResolver.resolve(ctx, msg);
            } else {
                // if not mapped, may be request a file
                handleFile(ctx, (FullHttpRequest) msg);
            }

        } else if (msg instanceof WebSocketFrame) {

            // TODO
            WebSocketFrame webSocketFrame = (WebSocketFrame) msg;

        }

    }

    private void handleFile(ChannelHandlerContext ctx, FullHttpRequest msg) {

    }

    private MappingResolver getMappingResolver(String uri) {

        // get resolver from map
        MappingResolver mappingResolver = mappingRuntimeSupporter.getMappingResolverMap().get(uri);

        // if not mapped, try to match uri
        if (mappingResolver == null) {
            for (String key : mappingRuntimeSupporter.getMappingResolverMap().keySet()) {
                if (pathMatcher.match(key, uri)) {
                    mappingResolver = mappingRuntimeSupporter.getMappingResolverMap().get(key);
                    mappingResolver.setPathMatcher(pathMatcher);
                    break;
                }
            }
        }

        return mappingResolver;
    }

    private static String getBaseUri(FullHttpRequest request) {
        String uri = request.uri();
        if (uri.contains("&")) {
            return uri;
        }
        return uri.substring(0, uri.indexOf("&"));
    }

}
