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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    protected static final AttributeKey<String> SESSION_ID_IN_CHANNEL = AttributeKey.valueOf("sessionId");

    private static final String WEBSOCKET_UPGRADE_HEADER = "websocket";

    private static final String WEBSOCKET_CONNECTION_HEADER = "upgrade";

    private final Map<String, MessageSession> sessionMap;

    public MessageMappingResolver(String url, Map<MessageType, Method> methods, Object invokeRef) {
        super(url, methods, invokeRef);
        // create new session map, maintain session relations
        this.sessionMap = new ConcurrentHashMap<>();
    }

    @Override
    public void resolve(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (msg instanceof FullHttpRequest) {

            FullHttpRequest request = (FullHttpRequest) msg;
            // check message request
            if (!isMessageRequestLegal(ctx, request)) {
                return;
            }
            // on handshake
            onHandshake(ctx, request);
            // create session
            MessageSession session = crateMessageSession(ctx, request);
            // do handshake
            if (!doHandshake(ctx, request)) {
                return;
            }
            // put session into session map
            sessionMap.put(session.getSessionId(), session);
            // on connected
            try {
                onConnected(request, session);
            } catch (Exception e) {
                onException(msg, session, e);
            }

        } else if (msg instanceof WebSocketFrame) {

            // if message session not exists, close connection
            String sessionId = ctx.channel().attr(SESSION_ID_IN_CHANNEL).get();
            MessageSession session = sessionMap.get(sessionId);

            if (session == null) {
                log.warn("Session {} has been closed.", sessionId);
                // ctx.writeAndFlush(new TextWebSocketFrame("Session has been closed."));
                ctx.close();
                return;
            }

            if (msg instanceof CloseWebSocketFrame) {
                try {
                    onClose((CloseWebSocketFrame) msg, session);
                    // close session
                    session.getChannelHandlerContext().close();
                    // remove session from session map
                    sessionMap.remove(sessionId);
                } catch (Exception e) {
                    sessionMap.remove(sessionId);
                    onException(msg, session, e);
                }
            } else if (msg instanceof PingWebSocketFrame) {
                try {
                    onPing((PingWebSocketFrame) msg, session);
                } catch (Exception e) {
                    onException(msg, session, e);
                }
            } else if (msg instanceof TextWebSocketFrame) {
                try {
                    onTextMessage((TextWebSocketFrame) msg, session);
                } catch (Exception e) {
                    onException(msg, session, e);
                }
            } else if (msg instanceof BinaryWebSocketFrame) {
                try {
                    onBinaryMessage((BinaryWebSocketFrame) msg, session);
                } catch (Exception e) {
                    onException(msg, session, e);
                }
            } else {
                try {
                    onOtherMessage((WebSocketFrame) msg, session);
                } catch (Exception e) {
                    onException(msg, session, e);
                }
            }
            ctx.flush();
        }
    }

    @Override
    public void removeSession(String sessionId) {
        sessionMap.remove(sessionId);
    }

    private boolean isMessageRequestLegal(ChannelHandlerContext ctx, FullHttpRequest request) {

        // Handle a bad request.
        if (!request.decoderResult().isSuccess()) {
            sendHttpResponse(ctx, request, new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST));
            return false;
        }
        // Allow only GET methods.
        if (request.method() != GET) {
            sendHttpResponse(ctx, request, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN));
            return false;
        }
        // check headers contain Upgrade header
        if (request.headers().get(HttpHeaderNames.UPGRADE) == null
                || request.headers().get(HttpHeaderNames.CONNECTION) == null
                || !WEBSOCKET_UPGRADE_HEADER.equalsIgnoreCase(request.headers().get(HttpHeaderNames.UPGRADE))
                || !WEBSOCKET_CONNECTION_HEADER.equalsIgnoreCase(request.headers().get(HttpHeaderNames.CONNECTION))) {
            sendHttpResponse(ctx, request, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN));
            return false;
        }
        return true;
    }

    private MessageSession crateMessageSession(ChannelHandlerContext ctx, FullHttpRequest request) {

        // generate session id
        String sessionId = UUID.randomUUID().toString();
        if (sessionMap.containsKey(sessionId)) {
            log.warn("Duplicate session id {}, retry.", sessionId);
            return crateMessageSession(ctx, request);
        }

        // set attribute request in ChannelHandlerContext, used for ServiceHandler get resolver
        ctx.channel().attr(ServiceHandler.REQUEST_IN_CHANNEL).set(request);

        // set attribute session id in ChannelHandlerContext, used for get current session
        ctx.channel().attr(SESSION_ID_IN_CHANNEL).set(sessionId);

        // return new message session
        return new MessageSession(sessionId, ctx, request);
    }

    private boolean doHandshake(ChannelHandlerContext ctx, FullHttpRequest request) {
        String protocol = ctx.pipeline().get(SslHandler.class) == null ? "ws://" : "wss://";
        String wsUrl = protocol + request.headers().get(HttpHeaderNames.HOST) + request.uri();
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(wsUrl, null, false);
        WebSocketServerHandshaker handShaker = wsFactory.newHandshaker(request);
        if (handShaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            return false;
        }
        handShaker.handshake(ctx.channel(), request);
        return true;
    }

    private void onHandshake(ChannelHandlerContext ctx, FullHttpRequest msg) {
        Method handshakeMethod = getMethod(MessageType.ON_HANDSHAKE);
        if (handshakeMethod != null) {
            try {
                Object invoke = handshakeMethod.invoke(getInvokeRef(), getMethodParam(msg, ctx, null,
                        MessageType.ON_HANDSHAKE, null));
                if (invoke instanceof Boolean && !(boolean) invoke) {
                    ctx.close();
                }
            } catch (Exception e) {
                log.debug("Caught exception when invoke handshake method: {}", e);
                ctx.close();
            }
        }
    }

    private void onConnected(FullHttpRequest msg, MessageSession messageSession) throws Exception {
        Method connectedMethod = getMethod(MessageType.ON_CONNECTED);
        if (connectedMethod != null) {
            connectedMethod.invoke(
                    getInvokeRef(), getMethodParam(msg,
                            messageSession.getChannelHandlerContext(),
                            messageSession,
                            MessageType.ON_CONNECTED,
                            null));
        }
    }

    private void onPing(PingWebSocketFrame msg, MessageSession messageSession) throws Exception {
        Method method = getMethod(MessageType.ON_PING);
        if (method == null) {
            messageSession.getChannelHandlerContext().channel().write(new PongWebSocketFrame(msg.content().retain()));
        } else {
            try {
                method.invoke(getInvokeRef(), getMethodParam(msg, messageSession.getChannelHandlerContext(),
                        messageSession, MessageType.ON_PING, null));
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e1) {
                throw (Exception) e1.getCause();
            }
        }
    }

    private void onTextMessage(TextWebSocketFrame msg, MessageSession messageSession) throws Exception {
        Method method = getMethod(MessageType.TEXT_MESSAGE);
        if (method != null) {
            try {
                method.invoke(getInvokeRef(), getMethodParam(msg, messageSession.getChannelHandlerContext(),
                        messageSession, MessageType.TEXT_MESSAGE, null));
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e1) {
                throw (Exception) e1.getCause();
            }
        }
    }

    private void onBinaryMessage(BinaryWebSocketFrame msg, MessageSession messageSession) throws Exception {
        Method method = getMethod(MessageType.BINARY_MESSAGE);
        if (method != null) {
            try {
                method.invoke(getInvokeRef(), getMethodParam(msg, messageSession.getChannelHandlerContext(),
                        messageSession, MessageType.BINARY_MESSAGE, null));
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e1) {
                throw (Exception) e1.getCause();
            }
        }
    }

    private void onOtherMessage(WebSocketFrame msg, MessageSession messageSession) throws Exception {
        Method method = getMethod(MessageType.OTHER);
        try {
            method.invoke(getInvokeRef(), getMethodParam(msg, messageSession.getChannelHandlerContext(),
                    messageSession, MessageType.OTHER, null));
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e1) {
            throw (Exception) e1.getCause();
        }
    }

    private void onClose(CloseWebSocketFrame msg, MessageSession messageSession) {
        Method method = getMethod(MessageType.ON_CLOSE);
        if (method != null) {
            try {
                method.invoke(getInvokeRef(), getMethodParam(msg,
                        messageSession.getChannelHandlerContext(), messageSession, MessageType.ON_CLOSE, null));
            } catch (IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            }
        }
    }

    private void onException(Object msg, MessageSession messageSession, Exception e) throws Exception {
        Method method = getMethod(MessageType.ON_ERROR);
        if (method != null) {
            if (e instanceof InvocationTargetException) {
                e = (Exception) ((InvocationTargetException) e).getTargetException();
            }
            try {
                method.invoke(getInvokeRef(), getMethodParam(msg, messageSession.getChannelHandlerContext(),
                        messageSession, MessageType.ON_ERROR, e));
            } catch (InvocationTargetException e1) {
                throw (Exception) e1.getTargetException();
            }
        } else {
            throw e;
        }
    }

    private Object[] getMethodParam(Object message, ChannelHandlerContext ctx, MessageSession session,
                                    MessageType messageType, Exception e) {
        List<Object> methodParams = new ArrayList<>();
        Map<String, Class> methodParamType = getMethodParamType(messageType);
        for (Map.Entry<String, Class> methodEntry : methodParamType.entrySet()) {
            Class value = methodEntry.getValue();
            if (value == MessageSession.class) {
                // MessageSession
                methodParams.add(session);
            } else if (ChannelHandlerContext.class.isAssignableFrom(value)) {
                // ChannelHandlerContext
                methodParams.add(ctx);
            } else if (HttpRequest.class.isAssignableFrom(value) && (messageType == MessageType.ON_HANDSHAKE)) {
                // HttpRequest
                methodParams.add(message);
            } else if (HttpRequest.class.isAssignableFrom(value) && (messageType == MessageType.ON_CONNECTED)) {
                // HttpRequest
                methodParams.add(message);
            } else if (HttpRequest.class.isAssignableFrom(value)) {
                // HttpRequest
                methodParams.add(session.getFirstRequest());
            } else if (TextWebSocketFrame.class.isAssignableFrom(value) && messageType == MessageType.TEXT_MESSAGE) {
                // TextWebSocketFrame
                methodParams.add(message);
            } else if (BinaryWebSocketFrame.class.isAssignableFrom(value) && messageType == MessageType.BINARY_MESSAGE) {
                // BinaryWebSocketFrame
                methodParams.add(message);
            } else if (WebSocketFrame.class.isAssignableFrom(value) && messageType == MessageType.OTHER) {
                // WebSocketFrame
                methodParams.add(message);
            } else if (PingWebSocketFrame.class.isAssignableFrom(value) && messageType == MessageType.ON_PING) {
                // PingWebSocketFrame
                methodParams.add(message);
            } else if (CloseWebSocketFrame.class.isAssignableFrom(value) && messageType == MessageType.ON_CLOSE) {
                // CloseWebSocketFrame
                methodParams.add(message);
            } else if (Exception.class.isAssignableFrom(value) && messageType == MessageType.ON_ERROR) {
                // Exception
                methodParams.add(e);
            } else {
                methodParams.add(null);
            }
        }
        return methodParams.toArray();
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
