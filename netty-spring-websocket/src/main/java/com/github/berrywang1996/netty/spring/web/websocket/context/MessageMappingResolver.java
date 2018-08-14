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

package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslHandler;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
public class MessageMappingResolver extends AbstractMappingResolver<Object, String> {

    private static final String WEBSOCKET_UPGRADE_HEADER = "websocket";

    private static final String WEBSOCKET_CONNECTION_HEADER = "upgrade";

    public MessageMappingResolver(String url, Map<String, Method> methods, Object invokeRef) {
        super(url, methods, invokeRef);
    }

    @Override
    public void resolve(ChannelHandlerContext ctx, Object msg) {

        if (msg instanceof FullHttpRequest) {

            FullHttpRequest request = (FullHttpRequest) msg;

            // check headers contain Upgrade header
            if (!WEBSOCKET_UPGRADE_HEADER.equals(request.headers().get(HttpHeaderNames.UPGRADE).toLowerCase())
                    || !WEBSOCKET_CONNECTION_HEADER.equals(request.headers().get(HttpHeaderNames.CONNECTION).toLowerCase())) {
                return;
            }

            // update http to websocket
            String protocol = ctx.pipeline().get(SslHandler.class) == null ? "ws://" : "wss://";
            String wsUrl = protocol + request.headers().get(HttpHeaderNames.HOST) + request.uri();
            WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(wsUrl, null, false);
            WebSocketServerHandshaker handShaker = wsFactory.newHandshaker(request);
            if (handShaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
                return;
            }
            handShaker.handshake(ctx.channel(), request);

        } else if (msg instanceof WebSocketFrame) {

            if (msg instanceof CloseWebSocketFrame) {

                CloseWebSocketFrame webSocketFrame = (CloseWebSocketFrame) msg;

            } else if (msg instanceof PingWebSocketFrame) {

                PingWebSocketFrame webSocketFrame = (PingWebSocketFrame) msg;

            } else if (msg instanceof TextWebSocketFrame) {

                TextWebSocketFrame webSocketFrame = (TextWebSocketFrame) msg;

            } else if (msg instanceof BinaryWebSocketFrame) {

                BinaryWebSocketFrame webSocketFrame = (BinaryWebSocketFrame) msg;

            } else if (msg instanceof ContinuationWebSocketFrame) {

                ContinuationWebSocketFrame webSocketFrame = (ContinuationWebSocketFrame) msg;

            }

        }

    }

}
