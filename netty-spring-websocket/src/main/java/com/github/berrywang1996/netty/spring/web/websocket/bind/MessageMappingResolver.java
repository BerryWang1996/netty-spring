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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import com.github.berrywang1996.netty.spring.web.context.HandlerSubmitter;
import com.github.berrywang1996.netty.spring.web.context.HandlerSubmitterAware;
import com.github.berrywang1996.netty.spring.web.handler.ServiceHandler;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.util.StringUtil;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import com.github.berrywang1996.netty.spring.web.websocket.crypto.MessageCryptoCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public class MessageMappingResolver extends AbstractMappingResolver<Object, MessageType>
        implements HandlerSubmitterAware {

    private static final int DEFAULT_MAX_FRAME_PAYLOAD_LENGTH = 65536;

    private static final int SHUTDOWN_CLOSE_STATUS_CODE = 1001;

    private static final String SHUTDOWN_CLOSE_REASON = "Server shutting down";

    private static final int HEARTBEAT_CLOSE_STATUS_CODE = 1001;

    private static final String HEARTBEAT_CLOSE_REASON = "Heartbeat timeout";

    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = new ObjectMapper();

    public static final AttributeKey<String> SESSION_ID_IN_CHANNEL = AttributeKey.valueOf("sessionId");

    private final NettyServerStartupProperties.WebSocket webSocketProperties;

    private final Semaphore connectionSemaphore;

    private final Map<String, MessageSession> sessionMap;

    private final MessageCryptoCodec messageCryptoCodec;

    private volatile HandlerSubmitter handlerSubmitter;

    public MessageMappingResolver(String url, Map<MessageType, Method> methods, Object invokeRef) {
        this(url, methods, invokeRef, null, null);
    }

    public MessageMappingResolver(String url,
                                  Map<MessageType, Method> methods,
                                  Object invokeRef,
                                  NettyServerStartupProperties.WebSocket webSocketProperties,
                                  Semaphore connectionSemaphore) {
        super(url, methods, invokeRef);
        this.webSocketProperties = webSocketProperties;
        this.connectionSemaphore = connectionSemaphore;
        this.messageCryptoCodec = null;
        // create new session map, maintain session relations
        this.sessionMap = new ConcurrentHashMap<>();
    }

    public MessageMappingResolver(String url,
                                  Map<MessageType, Method> methods,
                                  Object invokeRef,
                                  NettyServerStartupProperties.WebSocket webSocketProperties,
                                  Semaphore connectionSemaphore,
                                  MessageCryptoCodec messageCryptoCodec) {
        super(url, methods, invokeRef);
        this.webSocketProperties = webSocketProperties;
        this.connectionSemaphore = connectionSemaphore;
        this.messageCryptoCodec = messageCryptoCodec;
        // create new session map, maintain session relations
        this.sessionMap = new ConcurrentHashMap<>();
    }

    public MessageMappingResolver(String url,
                                  Map<MessageType, Method> methods,
                                  ApplicationContext applicationContext,
                                  String invokeBeanName,
                                  NettyServerStartupProperties.WebSocket webSocketProperties,
                                  Semaphore connectionSemaphore) {
        super(url, methods, applicationContext, invokeBeanName);
        this.webSocketProperties = webSocketProperties;
        this.connectionSemaphore = connectionSemaphore;
        this.messageCryptoCodec = null;
        // create new session map, maintain session relations
        this.sessionMap = new ConcurrentHashMap<>();
    }

    public MessageMappingResolver(String url,
                                  Map<MessageType, Method> methods,
                                  ApplicationContext applicationContext,
                                  String invokeBeanName,
                                  NettyServerStartupProperties.WebSocket webSocketProperties,
                                  Semaphore connectionSemaphore,
                                  MessageCryptoCodec messageCryptoCodec) {
        super(url, methods, applicationContext, invokeBeanName);
        this.webSocketProperties = webSocketProperties;
        this.connectionSemaphore = connectionSemaphore;
        this.messageCryptoCodec = messageCryptoCodec;
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
            if (!isOriginAllowed(ctx, request)) {
                return;
            }
            // on handshake
            if (!onHandshake(ctx, request)) {
                return;
            }
            Runnable connectionReleaseAction = tryAcquireConnectionSlot(ctx, request);
            if (connectionReleaseAction == null && connectionSemaphore != null) {
                return;
            }
            MessageSession pendingSession = createMessageSession(ctx, request, connectionReleaseAction);
            // do handshake
            ChannelFuture handshakeFuture = doHandshake(ctx, request);
            if (handshakeFuture == null) {
                pendingSession.release();
                return;
            }
            handshakeFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) {
                    if (!future.isSuccess()) {
                        handleHandshakeFailure(pendingSession, future.cause());
                        return;
                    }
                    registerSession(pendingSession);
                    startHeartbeat(pendingSession);
                    dispatchConnectedLifecycle(pendingSession);
                }
            });

        } else if (msg instanceof WebSocketFrame) {

            // if message session not exists, close connection
            String sessionId = ctx.channel().attr(SESSION_ID_IN_CHANNEL).get();
            MessageSession session = StringUtil.isBlank(sessionId) ? null : sessionMap.get(sessionId);

            if (session == null || session.isClosing()) {
                log.warn("Session {} has been closed.", sessionId);
                // ctx.writeAndFlush(new TextWebSocketFrame("Session has been closed."));
                ctx.close();
                return;
            }
            WebSocketFrame frame = (WebSocketFrame) msg;
            if (isFrameTooLarge(frame)) {
                handleFrameTooLarge(session, frame);
                return;
            }
            session.recordInboundActivity();

            handleInboundFrame(frame, session);
            ctx.flush();
        }
    }

    @Override
    public void resolveException(ChannelHandlerContext ctx, Exception e) throws Exception {
        // if message session not exists, close connection
        String sessionId = ctx.channel().attr(SESSION_ID_IN_CHANNEL).get();
        MessageSession session = StringUtil.isBlank(sessionId) ? null : sessionMap.get(sessionId);

        if (session == null) {
            log.warn("Session {} has been closed.", sessionId);
            // ctx.writeAndFlush(new TextWebSocketFrame("Session has been closed."));
            ctx.close();
            return;
        }
        closeSessionOnTransportError(session, e);
    }

    @Override
    public void removeSession(String sessionId) {
        MessageSession session = sessionMap.remove(sessionId);
        if (session == null) {
            return;
        }
        clearChannelAttributes(session.getChannelHandlerContext());
        releaseSession(session);
    }

    @Override
    public void onChannelInactive(ChannelHandlerContext ctx) {
        MessageSession session = removeSession(ctx);
        if (session == null) {
            return;
        }
        if (!session.startClosing()) {
            return;
        }
        dispatchInactiveCloseLifecycle(session);
    }

    public void closeSessionOnTransportError(MessageSession session, Throwable throwable) {
        if (session == null || !session.startClosing()) {
            return;
        }
        removeSession(session);
        dispatchLifecycleTask("transportError", session, new Runnable() {
            @Override
            public void run() {
                doCloseSessionOnTransportError(session, throwable);
            }
        }, new Runnable() {
            @Override
            public void run() {
                finishDetachedSession(session, null);
            }
        });
    }

    @Override
    public void shutdown() {
        if (sessionMap.isEmpty()) {
            return;
        }
        List<MessageSession> sessions = new ArrayList<>(sessionMap.values());
        log.info("Shutting down websocket resolver {} with {} active sessions.", getUrl(), sessions.size());
        for (MessageSession session : sessions) {
            shutdownSession(session);
        }
    }

    private boolean isMessageRequestLegal(ChannelHandlerContext ctx, FullHttpRequest request) {

        // Handle a bad request.
        if (!request.decoderResult().isSuccess()) {
            log.warn("WS illegal: decoderResult fail");
            getHttpRuntimeRecorder().recordWebSocketHandshakeRejected();
            sendHttpResponse(ctx, request, new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.BAD_REQUEST)); // 400
            return false;
        }
        // Allow only GET methods.
        if (request.method() != GET) {
            log.warn("WS illegal: method={}", request.method());
            getHttpRuntimeRecorder().recordWebSocketHandshakeRejected();
            sendHttpResponse(ctx, request, new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.METHOD_NOT_ALLOWED)); // 405
            return false;
        }

        String upgrade = request.headers().get(HttpHeaderNames.UPGRADE);
        String connection = request.headers().get(HttpHeaderNames.CONNECTION);
        boolean isWs = upgrade != null && "websocket".equalsIgnoreCase(upgrade.trim());
        boolean hasConnUpgrade = false;
        if (connection != null) {
            for (String t : connection.split(",")) {
                if ("upgrade".equalsIgnoreCase(t.trim())) {
                    hasConnUpgrade = true;
                    break;
                }
            }
        }
        if (!isWs || !hasConnUpgrade) {
            getHttpRuntimeRecorder().recordWebSocketHandshakeRejected();
            sendHttpResponse(ctx, request, new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.UPGRADE_REQUIRED));
            return false;
        }

        return true;
    }

    private boolean isOriginAllowed(ChannelHandlerContext ctx, FullHttpRequest request) {
        String allowedOrigins = webSocketProperties == null ? null : webSocketProperties.getAllowedOrigins();
        if (StringUtil.isBlank(allowedOrigins)) {
            return true;
        }
        if (allowsAnyOrigin(allowedOrigins)) {
            return true;
        }
        String origin = request.headers().get(HttpHeaderNames.ORIGIN);
        if (StringUtil.isBlank(origin)) {
            log.warn("Reject websocket handshake because Origin header is missing. uri={}", request.uri());
            getHttpRuntimeRecorder().recordWebSocketOriginRejected();
            return rejectWithHttp(ctx, request, HttpResponseStatus.FORBIDDEN, "Forbidden by origin");
        }
        for (String allowedOrigin : allowedOrigins.split("[,\\s]+")) {
            if (StringUtil.isBlank(allowedOrigin)) {
                continue;
            }
            if ("*".equals(allowedOrigin) || allowedOrigin.equalsIgnoreCase(origin.trim())) {
                return true;
            }
        }
        log.warn("Reject websocket handshake because Origin is not allowed. uri={}, origin={}", request.uri(), origin);
        getHttpRuntimeRecorder().recordWebSocketOriginRejected();
        return rejectWithHttp(ctx, request, HttpResponseStatus.FORBIDDEN, "Forbidden by origin");
    }

    private boolean allowsAnyOrigin(String allowedOrigins) {
        for (String allowedOrigin : allowedOrigins.split("[,\\s]+")) {
            if ("*".equals(allowedOrigin)) {
                return true;
            }
        }
        return false;
    }


    private MessageSession createMessageSession(ChannelHandlerContext ctx,
                                                FullHttpRequest request,
                                                Runnable cleanupAction) {

        // generate session id
        String sessionId = UUID.randomUUID().toString();
        if (sessionMap.containsKey(sessionId)) {
            log.warn("Duplicate session id {}, retry.", sessionId);
            return createMessageSession(ctx, request, cleanupAction);
        }

        // return new message session
        return new MessageSession(sessionId, ctx, request, cleanupAction);
    }

    private ChannelFuture doHandshake(ChannelHandlerContext ctx, FullHttpRequest request) {
        String protocol = ctx.pipeline().get(SslHandler.class) == null ? "ws://" : "wss://";
        String wsUrl = protocol + request.headers().get(HttpHeaderNames.HOST) + request.uri();
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                wsUrl,
                null,
                false,
                resolveMaxFramePayloadLength());
        WebSocketServerHandshaker handShaker = wsFactory.newHandshaker(request);
        if (handShaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            return null;
        }
        return handShaker.handshake(ctx.channel(), request);
    }

    private boolean onHandshake(ChannelHandlerContext ctx, FullHttpRequest msg) {
        Method m = getMethod(MessageType.ON_HANDSHAKE);
        if (m == null) {
            return true;
        }
        try {
            Object ok = m.invoke(getInvokeRef(), getMethodParam(msg, ctx, null, MessageType.ON_HANDSHAKE, null));
            if (ok instanceof Boolean && !(boolean) ok) {
                rejectWithHttp(ctx, msg, HttpResponseStatus.FORBIDDEN, "Forbidden by handshake");
                return false;
            }
            return true;
        } catch (InvocationTargetException e) {
            return rejectHandshake(ctx, msg, extractException(e));
        } catch (Exception e) {
            return rejectHandshake(ctx, msg, extractException(e));
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
            messageSession.getChannelHandlerContext().writeAndFlush(new PongWebSocketFrame(msg.content().retain()));
        } else {
            try {
                method.invoke(getInvokeRef(), getMethodParam(msg, messageSession.getChannelHandlerContext(),
                        messageSession, MessageType.ON_PING, null));
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e1) {
                throw extractException(e1);
            }
        }
    }

    private void onPong(PongWebSocketFrame msg, MessageSession messageSession) throws Exception {
        if (getMethod(MessageType.OTHER) == null) {
            return;
        }
        onOtherMessage(msg, messageSession);
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
                throw extractException(e1);
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
                throw extractException(e1);
            }
        }
    }

    private void onOtherMessage(WebSocketFrame msg, MessageSession messageSession) throws Exception {
        Method method = getMethod(MessageType.OTHER);
        if (method == null) {
            return;
        }
        try {
            method.invoke(getInvokeRef(), getMethodParam(msg, messageSession.getChannelHandlerContext(),
                    messageSession, MessageType.OTHER, null));
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e1) {
            throw extractException(e1);
        }
    }

    private void onClose(CloseWebSocketFrame msg, MessageSession messageSession) throws Exception {
        Method method = getMethod(MessageType.ON_CLOSE);
        if (method != null) {
            try {
                method.invoke(getInvokeRef(), getMethodParam(msg,
                        messageSession.getChannelHandlerContext(), messageSession, MessageType.ON_CLOSE, null));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Invoke close handler failed.", e);
            } catch (InvocationTargetException e) {
                throw extractException(e);
            }
        }
    }

    public void onException(Object msg, MessageSession messageSession, Exception e) throws Exception {
        ChannelHandlerContext ctx = messageSession != null ? messageSession.getChannelHandlerContext() : null;
        onException(msg, ctx, messageSession, e);
    }

    private void onException(Object msg, ChannelHandlerContext ctx, MessageSession messageSession, Exception e) throws Exception {
        Method method = getMethod(MessageType.ON_ERROR);
        if (method != null) {
            e = extractException(e);
            try {
                method.invoke(getInvokeRef(), getMethodParam(msg, ctx,
                        messageSession, MessageType.ON_ERROR, e));
            } catch (InvocationTargetException e1) {
                throw extractException(e1);
            }
        } else {
            throw e;
        }
    }

    private Object[] getMethodParam(Object message, ChannelHandlerContext ctx, MessageSession session,
                                    MessageType messageType, Exception e) throws Exception {
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
                if (session != null) {
                    methodParams.add(session.getFirstRequest());
                } else if (message instanceof HttpRequest) {
                    methodParams.add(message);
                } else {
                    methodParams.add(null);
                }
            } else if (TextWebSocketFrame.class.isAssignableFrom(value) && messageType == MessageType.TEXT_MESSAGE) {
                // TextWebSocketFrame
                methodParams.add(message);
            } else if (String.class == value && messageType == MessageType.TEXT_MESSAGE) {
                // Text payload
                methodParams.add(((TextWebSocketFrame) message).text());
            } else if (isJsonTextPayloadBindingTarget(value) && messageType == MessageType.TEXT_MESSAGE) {
                // JSON text payload converted to business object
                methodParams.add(readJsonTextPayload((TextWebSocketFrame) message, value));
            } else if (BinaryWebSocketFrame.class.isAssignableFrom(value) && messageType == MessageType.BINARY_MESSAGE) {
                // BinaryWebSocketFrame
                methodParams.add(message);
            } else if (ByteBuf.class.isAssignableFrom(value) && messageType == MessageType.BINARY_MESSAGE) {
                // Binary payload, valid during current callback
                methodParams.add(((BinaryWebSocketFrame) message).content());
            } else if (byte[].class == value && messageType == MessageType.BINARY_MESSAGE) {
                // Binary payload copy
                ByteBuf content = ((BinaryWebSocketFrame) message).content();
                byte[] bytes = new byte[content.readableBytes()];
                content.getBytes(content.readerIndex(), bytes);
                methodParams.add(bytes);
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

    private Object readJsonTextPayload(TextWebSocketFrame frame, Class<?> value) throws Exception {
        return DEFAULT_OBJECT_MAPPER.readValue(frame.text(), value);
    }

    private boolean isJsonTextPayloadBindingTarget(Class<?> value) {
        if (value == null || isReservedBindingType(value)) {
            return false;
        }
        if (value.isPrimitive()
                || isPrimitiveWrapper(value)
                || value.isEnum()
                || value.isArray()
                || Map.class.isAssignableFrom(value)
                || Collection.class.isAssignableFrom(value)
                || Object.class == value) {
            return true;
        }
        int modifiers = value.getModifiers();
        return !value.isInterface() && !Modifier.isAbstract(modifiers);
    }

    private boolean isReservedBindingType(Class<?> value) {
        return String.class == value
                || CharSequence.class.isAssignableFrom(value)
                || MessageSession.class.isAssignableFrom(value)
                || ChannelHandlerContext.class.isAssignableFrom(value)
                || HttpRequest.class.isAssignableFrom(value)
                || WebSocketFrame.class.isAssignableFrom(value)
                || ByteBuf.class.isAssignableFrom(value)
                || Exception.class.isAssignableFrom(value);
    }

    private boolean isPrimitiveWrapper(Class<?> value) {
        return Boolean.class == value
                || Byte.class == value
                || Short.class == value
                || Integer.class == value
                || Long.class == value
                || Float.class == value
                || Double.class == value
                || Character.class == value;
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

    public WebSocketFrame encryptOutboundFrame(MessageSession session, WebSocketFrame frame) throws Exception {
        if (!shouldEncryptFrame(frame)) {
            return frame;
        }
        WebSocketFrame encryptedFrame = requireMessageCryptoCodec().encrypt(session, frame);
        if (encryptedFrame == null) {
            throw new IllegalStateException("MessageCryptoCodec returned null encrypted frame.");
        }
        if (encryptedFrame != frame) {
            ReferenceCountUtil.release(frame);
        }
        return encryptedFrame;
    }

    public boolean closeSession(String sessionId, int statusCode, String reasonText) {
        if (StringUtil.isBlank(sessionId)) {
            return false;
        }
        MessageSession session = sessionMap.get(sessionId);
        if (session == null) {
            return false;
        }
        if (!session.getChannelHandlerContext().channel().isActive()) {
            removeSession(sessionId);
            return false;
        }
        CloseWebSocketFrame closeFrame = new CloseWebSocketFrame(statusCode, reasonText == null ? "" : reasonText);
        try {
            return closeSession(session, closeFrame, true);
        } finally {
            ReferenceCountUtil.release(closeFrame);
        }
    }

    private Runnable tryAcquireConnectionSlot(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (connectionSemaphore == null) {
            return null;
        }
        if (!connectionSemaphore.tryAcquire()) {
            rejectWithHttp(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "WebSocket connection limit exceeded");
            return null;
        }
        return new Runnable() {
            @Override
            public void run() {
                connectionSemaphore.release();
            }
        };
    }

    private boolean isFrameTooLarge(WebSocketFrame frame) {
        return frame.content().readableBytes() > resolveMaxFramePayloadLength();
    }

    private void handleInboundFrame(WebSocketFrame frame, MessageSession session) throws Exception {
        WebSocketFrame decodedFrame = null;
        try {
            decodedFrame = decryptInboundFrame(session, frame);
            if (decodedFrame != frame && isFrameTooLarge(decodedFrame)) {
                handleFrameTooLarge(session, decodedFrame);
                return;
            }
            dispatchInboundFrame(decodedFrame, session);
        } catch (Exception e) {
            handleInboundDecodeFailure(decodedFrame == null ? frame : decodedFrame, session, extractException(e));
        } finally {
            if (decodedFrame != null && decodedFrame != frame) {
                ReferenceCountUtil.release(decodedFrame);
            }
        }
    }

    private WebSocketFrame decryptInboundFrame(MessageSession session, WebSocketFrame frame) throws Exception {
        if (!isCryptoEnabled() || !shouldHandleEncryptedInboundFrame(frame)) {
            return frame;
        }
        MessageCryptoCodec cryptoCodec = requireMessageCryptoCodec();
        if (!cryptoCodec.canDecrypt(session, frame)) {
            if (shouldRejectUnencrypted()) {
                throw new IllegalArgumentException(
                        "Unencrypted websocket frame rejected by crypto policy.");
            }
            return frame;
        }
        WebSocketFrame decryptedFrame = cryptoCodec.decrypt(session, frame);
        if (decryptedFrame == null) {
            throw new IllegalStateException("MessageCryptoCodec returned null decrypted frame.");
        }
        return decryptedFrame;
    }

    private void handleInboundDecodeFailure(WebSocketFrame frame, MessageSession session, Exception exception) {
        if (isCryptoEnabled() && shouldCloseOnDecryptFailure()) {
            closeSessionOnTransportError(session, exception);
            return;
        }
        handleLifecycleException(frame, session, exception);
    }

    private void dispatchInboundFrame(WebSocketFrame frame, MessageSession session) {
        if (frame instanceof CloseWebSocketFrame) {
            closeSession(session, (CloseWebSocketFrame) frame, true);
        } else if (frame instanceof PingWebSocketFrame) {
            try {
                onPing((PingWebSocketFrame) frame, session);
            } catch (Exception e) {
                handleLifecycleException(frame, session, e);
            }
        } else if (frame instanceof PongWebSocketFrame) {
            try {
                session.recordPong();
                onPong((PongWebSocketFrame) frame, session);
            } catch (Exception e) {
                handleLifecycleException(frame, session, e);
            }
        } else if (frame instanceof TextWebSocketFrame) {
            try {
                onTextMessage((TextWebSocketFrame) frame, session);
            } catch (Exception e) {
                handleLifecycleException(frame, session, e);
            }
        } else if (frame instanceof BinaryWebSocketFrame) {
            try {
                onBinaryMessage((BinaryWebSocketFrame) frame, session);
            } catch (Exception e) {
                handleLifecycleException(frame, session, e);
            }
        } else {
            try {
                onOtherMessage(frame, session);
            } catch (Exception e) {
                handleLifecycleException(frame, session, e);
            }
        }
    }

    private boolean shouldEncryptFrame(WebSocketFrame frame) {
        if (!isCryptoEnabled()) {
            return false;
        }
        NettyServerStartupProperties.WebSocket.Crypto crypto = getCryptoProperties();
        if (frame instanceof TextWebSocketFrame) {
            return crypto.isEncryptText();
        }
        if (frame instanceof BinaryWebSocketFrame) {
            return crypto.isEncryptBinary();
        }
        return false;
    }

    private boolean shouldHandleEncryptedInboundFrame(WebSocketFrame frame) {
        NettyServerStartupProperties.WebSocket.Crypto crypto = getCryptoProperties();
        if (frame instanceof TextWebSocketFrame) {
            return crypto.isEncryptText();
        }
        if (frame instanceof BinaryWebSocketFrame) {
            return crypto.isEncryptBinary();
        }
        return false;
    }

    private boolean isCryptoEnabled() {
        NettyServerStartupProperties.WebSocket.Crypto crypto = getCryptoProperties();
        return crypto != null && crypto.isEnable();
    }

    private boolean shouldCloseOnDecryptFailure() {
        NettyServerStartupProperties.WebSocket.Crypto crypto = getCryptoProperties();
        return crypto == null || crypto.isCloseOnDecryptFailure();
    }

    private boolean shouldRejectUnencrypted() {
        NettyServerStartupProperties.WebSocket.Crypto crypto = getCryptoProperties();
        return crypto == null || crypto.isRejectUnencrypted();
    }

    private MessageCryptoCodec requireMessageCryptoCodec() {
        if (messageCryptoCodec == null) {
            throw new IllegalStateException(
                    "Websocket crypto is enabled but no MessageCryptoCodec is configured.");
        }
        return messageCryptoCodec;
    }

    private NettyServerStartupProperties.WebSocket.Crypto getCryptoProperties() {
        if (webSocketProperties == null) {
            return null;
        }
        return webSocketProperties.getCrypto();
    }

    private void handleFrameTooLarge(MessageSession session, WebSocketFrame frame) {
        int frameLength = frame.content().readableBytes();
        int maxFramePayloadLength = resolveMaxFramePayloadLength();
        TooLongFrameException exception = new TooLongFrameException(
                "WebSocket frame payload too large: " + frameLength + " > " + maxFramePayloadLength);
        log.warn("Reject websocket frame because payload is too large. sessionId={}, frameLength={}, maxFramePayloadLength={}",
                session.getSessionId(),
                frameLength,
                maxFramePayloadLength);
        closeSessionOnTransportErrorInline(session, exception);
    }

    private int resolveMaxFramePayloadLength() {
        if (webSocketProperties == null || webSocketProperties.getMaxFramePayloadLength() <= 0) {
            return DEFAULT_MAX_FRAME_PAYLOAD_LENGTH;
        }
        return webSocketProperties.getMaxFramePayloadLength();
    }

    private boolean rejectWithHttp(ChannelHandlerContext ctx, FullHttpRequest req, HttpResponseStatus status, String msg) {
        getHttpRuntimeRecorder().recordWebSocketHandshakeRejected();
        DefaultFullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, status,
                Unpooled.copiedBuffer(msg, CharsetUtil.UTF_8));
        HttpUtil.setContentLength(res, res.content().readableBytes());
        ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
        return false;
    }

    private boolean rejectHandshake(ChannelHandlerContext ctx, FullHttpRequest request, Exception e) {
        try {
            onException(request, ctx, null, e);
        } catch (Exception ex) {
            log.warn("Handle handshake exception failed.", ex);
        }
        return rejectWithHttp(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Handshake error");
    }

    private void registerSession(MessageSession session) {
        sessionMap.put(session.getSessionId(), session);
        session.getChannelHandlerContext().channel().attr(ServiceHandler.REQUEST_IN_CHANNEL).set(session.getFirstRequest());
        session.getChannelHandlerContext().channel().attr(SESSION_ID_IN_CHANNEL).set(session.getSessionId());
    }

    private void startHeartbeat(MessageSession session) {
        long intervalMillis = resolveHeartbeatIntervalMillis();
        if (intervalMillis <= 0L || session == null || !session.startHeartbeat()) {
            return;
        }
        scheduleHeartbeat(session, intervalMillis);
    }

    private void scheduleHeartbeat(final MessageSession session, final long intervalMillis) {
        session.getChannelHandlerContext().executor().schedule(new Runnable() {
            @Override
            public void run() {
                doHeartbeat(session, intervalMillis);
            }
        }, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void doHeartbeat(final MessageSession session, long intervalMillis) {
        if (!isRegisteredSession(session)
                || session.isClosing()
                || !session.getChannelHandlerContext().channel().isActive()) {
            return;
        }
        long timeoutMillis = resolveHeartbeatTimeoutMillis();
        long idleMillis = System.currentTimeMillis() - session.getLastReadTimeMillis();
        if (timeoutMillis > 0L && idleMillis > timeoutMillis) {
            closeSessionOnHeartbeatTimeout(session, idleMillis);
            return;
        }
        PingWebSocketFrame pingFrame = new PingWebSocketFrame(
                Unpooled.copiedBuffer(Long.toString(System.currentTimeMillis()), CharsetUtil.UTF_8));
        ChannelFuture future = session.getChannelHandlerContext().writeAndFlush(pingFrame);
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (!future.isSuccess()) {
                    closeSessionOnTransportError(session, future.cause());
                }
            }
        });
        scheduleHeartbeat(session, intervalMillis);
    }

    private long resolveHeartbeatIntervalMillis() {
        if (webSocketProperties == null || webSocketProperties.getHeartbeatIntervalSeconds() <= 0L) {
            return 0L;
        }
        return TimeUnit.SECONDS.toMillis(webSocketProperties.getHeartbeatIntervalSeconds());
    }

    private long resolveHeartbeatTimeoutMillis() {
        if (webSocketProperties == null || webSocketProperties.getHeartbeatTimeoutSeconds() <= 0L) {
            return 0L;
        }
        return TimeUnit.SECONDS.toMillis(webSocketProperties.getHeartbeatTimeoutSeconds());
    }

    private MessageSession removeSession(ChannelHandlerContext ctx) {
        String sessionId = ctx.channel().attr(SESSION_ID_IN_CHANNEL).get();
        clearChannelAttributes(ctx);
        if (StringUtil.isBlank(sessionId)) {
            return null;
        }
        return sessionMap.remove(sessionId);
    }

    private void removeSession(MessageSession session) {
        if (session == null) {
            return;
        }
        clearChannelAttributes(session.getChannelHandlerContext());
        sessionMap.remove(session.getSessionId(), session);
    }

    private void clearChannelAttributes(ChannelHandlerContext ctx) {
        ctx.channel().attr(SESSION_ID_IN_CHANNEL).set(null);
        ctx.channel().attr(ServiceHandler.REQUEST_IN_CHANNEL).set(null);
    }

    private void releaseSession(MessageSession session) {
        if (session != null) {
            session.release();
        }
    }

    @Override
    public void setHandlerSubmitter(HandlerSubmitter handlerSubmitter) {
        this.handlerSubmitter = handlerSubmitter;
    }

    private boolean closeSession(MessageSession session, CloseWebSocketFrame closeFrame, boolean notifyClose) {
        if (session == null || !session.startClosing()) {
            return false;
        }
        try {
            if (notifyClose) {
                notifyCloseLifecycle(closeFrame, session);
            }
        } finally {
            cleanupClosedSession(session, closeFrame);
        }
        return true;
    }

    private void notifyCloseLifecycle(CloseWebSocketFrame closeFrame, MessageSession session) {
        try {
            onClose(closeFrame, session);
        } catch (Exception e) {
            handleLifecycleException(closeFrame, session, e);
        }
    }

    private void handleHandshakeFailure(MessageSession pendingSession, Throwable throwable) {
        dispatchLifecycleTask("handshakeFailure", pendingSession, new Runnable() {
            @Override
            public void run() {
                try {
                    onException(pendingSession.getFirstRequest(),
                            pendingSession.getChannelHandlerContext(),
                            null,
                            extractException(throwable));
                } catch (Exception e) {
                    log.warn("Handle handshake future failure failed.", e);
                } finally {
                    pendingSession.release();
                    pendingSession.getChannelHandlerContext().close();
                }
            }
        }, new Runnable() {
            @Override
            public void run() {
                pendingSession.release();
                pendingSession.getChannelHandlerContext().close();
            }
        });
    }

    private void handleLifecycleException(Object msg, MessageSession session, Exception e) {
        try {
            onException(msg, session, e);
        } catch (Exception ex) {
            log.warn("Handle websocket lifecycle exception failed.", ex);
        }
    }

    private void cleanupClosedSession(MessageSession session, CloseWebSocketFrame closeFrame) {
        removeSession(session);
        releaseSession(session);
        if (closeFrame == null) {
            session.getChannelHandlerContext().close();
        } else {
            session.getChannelHandlerContext()
                    .writeAndFlush(closeFrame.retain())
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void finishDetachedSession(MessageSession session, CloseWebSocketFrame closeFrame) {
        releaseSession(session);
        if (closeFrame == null) {
            session.getChannelHandlerContext().close();
        } else {
            session.getChannelHandlerContext()
                    .writeAndFlush(closeFrame.retain())
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void dispatchConnectedLifecycle(final MessageSession session) {
        dispatchLifecycleTask("onConnected", session, new Runnable() {
            @Override
            public void run() {
                if (!isRegisteredSession(session)) {
                    return;
                }
                try {
                    onConnected(session.getFirstRequest(), session);
                } catch (Exception e) {
                    handleLifecycleException(session.getFirstRequest(), session, e);
                    closeSession(session, null, true);
                }
            }
        }, new Runnable() {
            @Override
            public void run() {
                removeSession(session);
                finishDetachedSession(session, null);
            }
        });
    }

    private void dispatchInactiveCloseLifecycle(final MessageSession session) {
        dispatchLifecycleTask("inactiveClose", session, new Runnable() {
            @Override
            public void run() {
                try {
                    notifyCloseLifecycle(null, session);
                } finally {
                    releaseSession(session);
                }
            }
        }, new Runnable() {
            @Override
            public void run() {
                releaseSession(session);
            }
        });
    }

    private void shutdownSession(final MessageSession session) {
        if (session == null || !session.startClosing()) {
            return;
        }
        dispatchLifecycleTask("shutdownClose", session, new Runnable() {
            @Override
            public void run() {
                doShutdownClose(session);
            }
        }, new Runnable() {
            @Override
            public void run() {
                doShutdownClose(session);
            }
        });
    }

    private void doShutdownClose(MessageSession session) {
        CloseWebSocketFrame lifecycleFrame = newShutdownCloseFrame();
        CloseWebSocketFrame outboundFrame = newShutdownCloseFrame();
        try {
            notifyCloseLifecycle(lifecycleFrame, session);
        } finally {
            ReferenceCountUtil.release(lifecycleFrame);
            removeSession(session);
            releaseSession(session);
            closeChannel(session, outboundFrame);
        }
    }

    private void closeSessionOnTransportErrorInline(MessageSession session, Throwable throwable) {
        if (session == null || !session.startClosing()) {
            return;
        }
        removeSession(session);
        doCloseSessionOnTransportError(session, throwable);
    }

    private void doCloseSessionOnTransportError(MessageSession session, Throwable throwable) {
        try {
            handleLifecycleException(null, session, extractException(throwable));
            notifyCloseLifecycle(null, session);
        } finally {
            finishDetachedSession(session, null);
        }
    }

    private void closeSessionOnHeartbeatTimeout(final MessageSession session, long idleMillis) {
        if (session == null || !session.startClosing()) {
            return;
        }
        removeSession(session);
        final IllegalStateException exception =
                new IllegalStateException("WebSocket heartbeat timeout after " + idleMillis + " ms.");
        dispatchLifecycleTask("heartbeatTimeout", session, new Runnable() {
            @Override
            public void run() {
                doCloseSessionOnHeartbeatTimeout(session, exception);
            }
        }, new Runnable() {
            @Override
            public void run() {
                doCloseSessionOnHeartbeatTimeout(session, exception);
            }
        });
    }

    private void doCloseSessionOnHeartbeatTimeout(MessageSession session, Throwable throwable) {
        CloseWebSocketFrame lifecycleFrame = newHeartbeatCloseFrame();
        CloseWebSocketFrame outboundFrame = newHeartbeatCloseFrame();
        try {
            handleLifecycleException(null, session, extractException(throwable));
            notifyCloseLifecycle(lifecycleFrame, session);
        } finally {
            ReferenceCountUtil.release(lifecycleFrame);
            releaseSession(session);
            closeChannel(session, outboundFrame);
        }
    }

    private boolean isRegisteredSession(MessageSession session) {
        return session != null && sessionMap.get(session.getSessionId()) == session;
    }

    private void dispatchLifecycleTask(String taskName,
                                       MessageSession session,
                                       Runnable task,
                                       Runnable rejectedTask) {
        HandlerSubmitter submitter = this.handlerSubmitter;
        if (submitter == null) {
            task.run();
            return;
        }
        try {
            submitter.submitHandle(task);
        } catch (RejectedExecutionException e) {
            log.warn("Reject websocket lifecycle task {}. sessionId={}, reason={}",
                    taskName,
                    session == null ? null : session.getSessionId(),
                    e.getMessage());
            rejectedTask.run();
        }
    }

    private Exception extractException(Throwable throwable) {
        Throwable target = throwable;
        if (throwable instanceof InvocationTargetException) {
            target = ((InvocationTargetException) throwable).getTargetException();
        }
        if (target instanceof Exception) {
            return (Exception) target;
        }
        return new RuntimeException(target);
    }

    private CloseWebSocketFrame newShutdownCloseFrame() {
        return new CloseWebSocketFrame(SHUTDOWN_CLOSE_STATUS_CODE, SHUTDOWN_CLOSE_REASON);
    }

    private CloseWebSocketFrame newHeartbeatCloseFrame() {
        return new CloseWebSocketFrame(HEARTBEAT_CLOSE_STATUS_CODE, HEARTBEAT_CLOSE_REASON);
    }

    private void closeChannel(MessageSession session, CloseWebSocketFrame closeFrame) {
        if (closeFrame == null) {
            session.getChannelHandlerContext().close();
            return;
        }
        if (!session.getChannelHandlerContext().channel().isActive()) {
            ReferenceCountUtil.release(closeFrame);
            session.getChannelHandlerContext().close();
            return;
        }
        session.getChannelHandlerContext()
                .writeAndFlush(closeFrame)
                .addListener(ChannelFutureListener.CLOSE);
    }

}
