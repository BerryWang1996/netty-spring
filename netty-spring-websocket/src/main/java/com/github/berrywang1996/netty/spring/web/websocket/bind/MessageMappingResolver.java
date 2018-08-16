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

package com.github.berrywang1996.netty.spring.web.websocket.bind;

import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import com.github.berrywang1996.netty.spring.web.handler.ServiceHandler;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public class MessageMappingResolver extends AbstractMappingResolver<Object, MessageType> {

    public static final AttributeKey<String> SESSION_ID_IN_CHANNEL = AttributeKey.valueOf("sessionId");

    private static final String WEBSOCKET_UPGRADE_HEADER = "websocket";

    private static final String WEBSOCKET_CONNECTION_HEADER = "upgrade";

    private final Map<String, MessageSession> sessionMap;

    public MessageMappingResolver(String url, Map<MessageType, Method> methods, Object invokeRef) {
        super(url, methods, invokeRef);
        // create new session map, maintain session relations
        this.sessionMap = new HashMap<>();
    }

    @Override
    public void resolve(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (msg instanceof FullHttpRequest) {

            FullHttpRequest request = (FullHttpRequest) msg;

            // Handle a bad request.
            if (!request.decoderResult().isSuccess()) {
                sendHttpResponse(ctx, request, new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST));
                return;
            }

            // Allow only GET methods.
            if (request.method() != GET) {
                sendHttpResponse(ctx, request, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN));
                return;
            }

            // check headers contain Upgrade header
            if (!WEBSOCKET_UPGRADE_HEADER.equals(request.headers().get(HttpHeaderNames.UPGRADE).toLowerCase())
                    || !WEBSOCKET_CONNECTION_HEADER.equals(request.headers().get(HttpHeaderNames.CONNECTION).toLowerCase())) {
                sendHttpResponse(ctx, request, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN));
                return;
            }

            // do handshake
            Method method = getMethod(MessageType.HANDSHAKE);
            if (method != null) {
                try {
                    Object invoke = method.invoke(getInvokeRef());
                    if (invoke instanceof Boolean && !(boolean) invoke) {
                        ctx.close();
                    }
                } catch (IllegalAccessException | InvocationTargetException e) {
                    e.printStackTrace();
                    ctx.close();
                }
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

            // set attribute request in ChannelHandlerContext, used for ServiceHandler get resolver
            ctx.channel().attr(ServiceHandler.REQUEST_IN_CHANNEL).set(request);

            // set attribute session id in ChannelHandlerContext, used for get current session
            String sessionId = UUID.randomUUID().toString();
            ctx.channel().attr(SESSION_ID_IN_CHANNEL).set(sessionId);

            // create session
            if (sessionMap.containsKey(sessionId)) {
                log.warn("Duplicate session id.", sessionId);
                ctx.writeAndFlush(new TextWebSocketFrame("Duplicate session id."));
                ctx.close();
                return;
            }
            sessionMap.put(sessionId, new MessageSession(sessionId, ctx, request));

        } else if (msg instanceof WebSocketFrame) {

            // if message session not exists, close connection
            String sessionId = ctx.channel().attr(SESSION_ID_IN_CHANNEL).get();
            MessageSession messageSession = sessionMap.get(sessionId);
            if (messageSession == null) {
                log.warn("Session id {} has been closed.", sessionId);
                ctx.writeAndFlush(new TextWebSocketFrame("Session has been closed."));
                ctx.close();
                return;
            }

            if (msg instanceof CloseWebSocketFrame) {

                Method method = getMethod(MessageType.CLOSE);
                if (method != null) {
                    try {
                        // TODO 未完成（参数解析）
                        method.invoke(getInvokeRef());
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        e.printStackTrace();
                    }
                }
                ctx.close();
                // remove session from session map
                sessionMap.remove(sessionId);

            } else if (msg instanceof PingWebSocketFrame) {

                Method method = getMethod(MessageType.PING);
                if (method == null) {
                    ctx.channel().write(new PongWebSocketFrame());
                } else {
                    try {
                        // TODO 未完成（参数解析）
                        method.invoke(getInvokeRef());
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        e.printStackTrace();
                    }
                }

            } else if (msg instanceof TextWebSocketFrame) {

                TextWebSocketFrame webSocketFrame = (TextWebSocketFrame) msg;
                // TODO 未完成（参数解析）

            } else if (msg instanceof BinaryWebSocketFrame) {

                BinaryWebSocketFrame webSocketFrame = (BinaryWebSocketFrame) msg;
                // TODO 未完成（参数解析）

            } else {

                log.warn("Unsupported message type.");

            }
            ctx.flush();
        }

    }

    private static void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse res) {
        // Generate an error page if response getStatus code is not OK (200).
        if (res.status().code() != 200) {
            ByteBuf buf = Unpooled.copiedBuffer(res.status().toString(), CharsetUtil.UTF_8);
            res.content().writeBytes(buf);
            buf.release();
            HttpUtil.setContentLength(res, res.content().readableBytes());
        }

        // Send the response and close the connection if necessary.
        ChannelFuture f = ctx.channel().writeAndFlush(res);
        if (!HttpUtil.isKeepAlive(req) || res.status().code() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    public Map<String, MessageSession> getSessionMap() {
        return sessionMap;
    }
}
