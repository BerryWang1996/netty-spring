package com.github.berrywang1996.netty.spring.web.websocket.bind;

import com.github.berrywang1996.netty.spring.web.context.HandlerSubmitter;
import com.github.berrywang1996.netty.spring.web.context.HttpRuntimeRecorder;
import com.github.berrywang1996.netty.spring.web.handler.ServiceHandler;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.websocket.consts.CloseReason;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import com.github.berrywang1996.netty.spring.web.websocket.context.WebSocketEventRecorder;
import com.github.berrywang1996.netty.spring.web.websocket.context.WebSocketEventStats;
import com.github.berrywang1996.netty.spring.web.websocket.context.WebSocketHandshakeInterceptor;
import com.github.berrywang1996.netty.spring.web.websocket.crypto.MessageCryptoCodec;
import com.github.berrywang1996.netty.spring.web.websocket.crypto.MessageCryptoPolicy;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageMappingResolverTest {

    @Test
    void rejectsHandshakeWithoutCreatingSession() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        endpoint.handshakeAllowed = false;
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_HANDSHAKE, method(endpoint, "onHandshake", HttpRequest.class));
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);

        Object outbound = testChannel.channel.readOutbound();
        try {
            assertTrue(outbound instanceof FullHttpResponse);
            assertEquals(FORBIDDEN, ((FullHttpResponse) outbound).status());
            assertTrue(resolver.getSessionMap().isEmpty());
            assertEquals(0, endpoint.connectedCount);
            assertNull(testChannel.channel.attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).get());
        } finally {
            ReferenceCountUtil.release(outbound);
            request.release();
            testChannel.finish();
        }
    }

    @Test
    void rejectsHandshakeWhenBusinessLogicThrowsWithoutErrorHandler() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        endpoint.throwOnHandshake = true;
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_HANDSHAKE, method(endpoint, "onHandshake", HttpRequest.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);

        Object outbound = testChannel.channel.readOutbound();
        try {
            assertTrue(outbound instanceof FullHttpResponse);
            assertEquals(INTERNAL_SERVER_ERROR, ((FullHttpResponse) outbound).status());
            assertTrue(resolver.getSessionMap().isEmpty());
            assertNull(testChannel.channel.attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).get());
        } finally {
            ReferenceCountUtil.release(outbound);
            request.release();
            testChannel.finish();
        }
    }

    @Test
    void rejectsHandshakeEvenWhenErrorHandlerThrows() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        endpoint.throwOnHandshake = true;
        endpoint.throwOnError = true;
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_HANDSHAKE, method(endpoint, "onHandshake", HttpRequest.class));
        methods.put(MessageType.ON_ERROR, method(endpoint, "onError", HttpRequest.class, MessageSession.class, Exception.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);

        Object outbound = testChannel.channel.readOutbound();
        try {
            assertTrue(outbound instanceof FullHttpResponse);
            assertEquals(INTERNAL_SERVER_ERROR, ((FullHttpResponse) outbound).status());
            assertEquals(1, endpoint.errorCount);
            assertEquals("handshake boom", endpoint.lastErrorMessage);
            assertTrue(resolver.getSessionMap().isEmpty());
        } finally {
            ReferenceCountUtil.release(outbound);
            request.release();
            testChannel.finish();
        }
    }

    @Test
    void rejectsHandshakeWhenOriginIsNotAllowed() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setAllowedOrigins("https://trusted.example");
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/test",
                new EnumMap<MessageType, Method>(MessageType.class),
                endpoint,
                properties,
                null);
        HttpRuntimeRecorder recorder = new HttpRuntimeRecorder();
        resolver.setHttpRuntimeRecorder(recorder);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");
        request.headers().set(HttpHeaderNames.ORIGIN, "https://evil.example");

        resolver.resolve(testChannel.ctx, request);

        Object outbound = testChannel.channel.readOutbound();
        try {
            assertTrue(outbound instanceof FullHttpResponse);
            assertEquals(FORBIDDEN, ((FullHttpResponse) outbound).status());
            assertTrue(((FullHttpResponse) outbound).content()
                    .toString(CharsetUtil.UTF_8)
                    .contains("server.netty.websocket.allowed-origins"));
            assertTrue(resolver.getSessionMap().isEmpty());
            assertNull(testChannel.channel.attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).get());
            assertEquals(1L, recorder.getRuntimeStats().getWebSocketOriginRejectedCount());
            assertEquals(1L, recorder.getRuntimeStats().getWebSocketHandshakeRejectedCount());
        } finally {
            ReferenceCountUtil.release(outbound);
            request.release();
            testChannel.finish();
        }
    }

    @Test
    void acceptsHandshakeWhenOriginMatchesAllowedOrigins() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setAllowedOrigins("https://trusted.example, https://other.example");
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint, properties, null);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");
        request.headers().set(HttpHeaderNames.ORIGIN, "https://trusted.example");

        try {
            resolver.resolve(testChannel.ctx, request);
            Object outbound = testChannel.channel.readOutbound();
            try {
                assertNotNull(outbound);
                assertFalse(outbound instanceof FullHttpResponse
                        && ((FullHttpResponse) outbound).status().equals(FORBIDDEN));
            } finally {
                ReferenceCountUtil.release(outbound);
            }
            drainChannel(testChannel.channel);

            assertEquals(1, endpoint.connectedCount);
            assertEquals(1, resolver.getSessionMap().size());
        } finally {
            request.release();
            resolver.onChannelInactive(testChannel.ctx);
            testChannel.finish();
        }
    }

    @Test
    void wildcardAllowedOriginAcceptsMissingOrigin() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setAllowedOrigins("*");
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/test",
                new EnumMap<MessageType, Method>(MessageType.class),
                endpoint,
                properties,
                null);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        try {
            resolver.resolve(testChannel.ctx, request);
            Object outbound = testChannel.channel.readOutbound();
            try {
                assertNotNull(outbound);
                assertFalse(outbound instanceof FullHttpResponse
                        && ((FullHttpResponse) outbound).status().equals(FORBIDDEN));
            } finally {
                ReferenceCountUtil.release(outbound);
            }
            drainChannel(testChannel.channel);

            assertEquals(1, resolver.getSessionMap().size());
        } finally {
            request.release();
            resolver.onChannelInactive(testChannel.ctx);
            testChannel.finish();
        }
    }

    @Test
    void handlesPingPongAndCloseLifecycle() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        methods.put(MessageType.ON_CLOSE, method(endpoint, "onClose", CloseWebSocketFrame.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        assertEquals(1, resolver.getSessionMap().size());
        PingWebSocketFrame pingFrame = new PingWebSocketFrame(Unpooled.copiedBuffer("ping", CharsetUtil.UTF_8));
        resolver.resolve(testChannel.ctx, pingFrame);
        pingFrame.release();
        Object pong = testChannel.channel.readOutbound();
        try {
            assertTrue(pong instanceof PongWebSocketFrame);
            assertEquals("ping", ((PongWebSocketFrame) pong).content().toString(CharsetUtil.UTF_8));
        } finally {
            ReferenceCountUtil.release(pong);
        }

        PongWebSocketFrame inboundPong = new PongWebSocketFrame();
        resolver.resolve(testChannel.ctx, inboundPong);
        inboundPong.release();
        assertNull(testChannel.channel.readOutbound());

        CloseWebSocketFrame closeFrame = new CloseWebSocketFrame(1000, "bye");
        resolver.resolve(testChannel.ctx, closeFrame);
        closeFrame.release();
        Object closeResponse = testChannel.channel.readOutbound();
        try {
            assertTrue(closeResponse instanceof CloseWebSocketFrame);
            assertEquals("bye", ((CloseWebSocketFrame) closeResponse).reasonText());
        } finally {
            ReferenceCountUtil.release(closeResponse);
        }
        testChannel.channel.runPendingTasks();

        assertEquals(1, endpoint.connectedCount);
        assertEquals(1, endpoint.closeCount);
        assertTrue(resolver.getSessionMap().isEmpty());
        assertNull(testChannel.channel.attr(ServiceHandler.REQUEST_IN_CHANNEL).get());
        assertNull(testChannel.channel.attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).get());
        testChannel.finish();
    }

    @Test
    void heartbeatSendsPingWhenConfigured() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setHeartbeatIntervalSeconds(1L);
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/test",
                new EnumMap<MessageType, Method>(MessageType.class),
                endpoint,
                properties,
                null);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        try {
            resolver.resolve(testChannel.ctx, request);
            Object handshakeResponse = testChannel.channel.readOutbound();
            ReferenceCountUtil.release(handshakeResponse);
            drainChannel(testChannel.channel);
            request.release();

            Thread.sleep(1100L);
            testChannel.channel.runScheduledPendingTasks();

            Object outbound = testChannel.channel.readOutbound();
            try {
                assertTrue(outbound instanceof PingWebSocketFrame);
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        } finally {
            resolver.onChannelInactive(testChannel.ctx);
            testChannel.finish();
        }
    }

    @Test
    void heartbeatTimeoutDispatchesErrorAndCloseLifecycle() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_ERROR, method(endpoint, "onError", HttpRequest.class, MessageSession.class, Exception.class));
        methods.put(MessageType.ON_CLOSE, method(endpoint, "onClose", CloseWebSocketFrame.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setHeartbeatIntervalSeconds(1L);
        properties.setHeartbeatTimeoutSeconds(1L);
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint, properties, null);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        try {
            resolver.resolve(testChannel.ctx, request);
            Object handshakeResponse = testChannel.channel.readOutbound();
            ReferenceCountUtil.release(handshakeResponse);
            drainChannel(testChannel.channel);
            request.release();

            Thread.sleep(1200L);
            testChannel.channel.runScheduledPendingTasks();
            testChannel.channel.runPendingTasks();

            Object outbound = testChannel.channel.readOutbound();
            try {
                assertTrue(outbound instanceof CloseWebSocketFrame);
                assertEquals("Heartbeat timeout", ((CloseWebSocketFrame) outbound).reasonText());
            } finally {
                ReferenceCountUtil.release(outbound);
            }
            assertEquals(1, endpoint.errorCount);
            assertEquals(1, endpoint.closeCount);
            assertTrue(resolver.getSessionMap().isEmpty());
            assertNull(testChannel.channel.attr(ServiceHandler.REQUEST_IN_CHANNEL).get());
            assertNull(testChannel.channel.attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).get());
        } finally {
            testChannel.finish();
        }
    }

    @Test
    void doesNotPublishSessionWhenHandshakeWriteFails() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setMaxConnections(1);
        Semaphore connectionSemaphore = new Semaphore(1);
        MessageMappingResolver resolver =
                new MessageMappingResolver("/ws/test", methods, endpoint, properties, connectionSemaphore);
        TestChannel testChannel = new TestChannel(new FailingHandshakeWriteHandler());
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        assertNull(testChannel.channel.attr(ServiceHandler.REQUEST_IN_CHANNEL).get());
        assertNull(testChannel.channel.attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).get());
        testChannel.channel.runPendingTasks();

        assertEquals(0, endpoint.connectedCount);
        assertTrue(resolver.getSessionMap().isEmpty());
        assertEquals(1, connectionSemaphore.availablePermits());
        assertNull(testChannel.channel.attr(ServiceHandler.REQUEST_IN_CHANNEL).get());
        assertNull(testChannel.channel.attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).get());

        request.release();
        testChannel.finish();
    }

    @Test
    void dispatchesConnectedLifecycleThroughHandlerSubmitter() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        RecordingHandlerSubmitter submitter = new RecordingHandlerSubmitter();
        resolver.setHandlerSubmitter(submitter);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        try {
            resolver.resolve(testChannel.ctx, request);
            Object handshakeResponse = testChannel.channel.readOutbound();
            ReferenceCountUtil.release(handshakeResponse);
            drainChannel(testChannel.channel);

            assertEquals(0, endpoint.connectedCount);
            assertEquals(1, resolver.getSessionMap().size());
            assertEquals(1, submitter.size());

            submitter.runNext();

            assertEquals(1, endpoint.connectedCount);
            assertEquals(1, resolver.getSessionMap().size());
        } finally {
            resolver.setHandlerSubmitter(null);
            request.release();
            resolver.onChannelInactive(testChannel.ctx);
            testChannel.finish();
        }
    }

    @Test
    void activeSessionCountReflectsSuccessfulHandshakeAndClose() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/test",
                new EnumMap<>(MessageType.class),
                endpoint);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        assertEquals(1, resolver.getActiveSessionCount());

        CloseWebSocketFrame closeFrame = new CloseWebSocketFrame();
        try {
            resolver.resolve(testChannel.ctx, closeFrame);

            assertEquals(0, resolver.getActiveSessionCount());
        } finally {
            closeFrame.release();
            testChannel.finish();
        }
    }

    @Test
    void resolveExceptionDispatchesErrorThenCloseLifecycle() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        methods.put(MessageType.ON_ERROR, method(endpoint, "onError", HttpRequest.class, MessageSession.class, Exception.class));
        methods.put(MessageType.ON_CLOSE, method(endpoint, "onClose", CloseWebSocketFrame.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        resolver.resolveException(testChannel.ctx, new IllegalStateException("boom"));

        assertEquals(1, endpoint.errorCount);
        assertEquals(1, endpoint.closeCount);
        assertEquals("boom", endpoint.lastErrorMessage);
        assertEquals(1, endpoint.lastErrorRequestRefCnt);
        assertTrue(resolver.getSessionMap().isEmpty());
        assertNull(testChannel.channel.attr(ServiceHandler.REQUEST_IN_CHANNEL).get());
        assertNull(testChannel.channel.attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).get());
        testChannel.finish();
    }

    @Test
    void closeErrorsFlowIntoErrorLifecycle() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        endpoint.throwOnClose = true;
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        methods.put(MessageType.ON_CLOSE, method(endpoint, "onClose", CloseWebSocketFrame.class, MessageSession.class));
        methods.put(MessageType.ON_ERROR, method(endpoint, "onError", HttpRequest.class, MessageSession.class, Exception.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        CloseWebSocketFrame closeFrame = new CloseWebSocketFrame(1000, "bye");
        resolver.resolve(testChannel.ctx, closeFrame);
        closeFrame.release();

        Object closeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(closeResponse);
        assertEquals(1, endpoint.closeCount);
        assertEquals(1, endpoint.errorCount);
        assertEquals("close boom", endpoint.lastErrorMessage);
        assertTrue(resolver.getSessionMap().isEmpty());
        testChannel.finish();
    }

    @Test
    void inactiveCloseErrorsFlowIntoErrorLifecycle() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        endpoint.throwOnClose = true;
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        methods.put(MessageType.ON_CLOSE, method(endpoint, "onClose", CloseWebSocketFrame.class, MessageSession.class));
        methods.put(MessageType.ON_ERROR, method(endpoint, "onError", HttpRequest.class, MessageSession.class, Exception.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        resolver.onChannelInactive(testChannel.ctx);

        assertEquals(1, endpoint.closeCount);
        assertEquals(1, endpoint.errorCount);
        assertEquals("close boom", endpoint.lastErrorMessage);
        assertTrue(resolver.getSessionMap().isEmpty());
        assertNull(testChannel.channel.attr(ServiceHandler.REQUEST_IN_CHANNEL).get());
        assertNull(testChannel.channel.attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).get());
        testChannel.finish();
    }

    @Test
    void rejectsHandshakeWhenConnectionLimitExceeded() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setMaxConnections(1);
        Semaphore connectionSemaphore = new Semaphore(1);
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint, properties, connectionSemaphore);

        TestChannel firstChannel = new TestChannel();
        FullHttpRequest firstRequest = websocketRequest("/ws/test");
        resolver.resolve(firstChannel.ctx, firstRequest);
        Object firstHandshake = firstChannel.channel.readOutbound();
        ReferenceCountUtil.release(firstHandshake);
        drainChannel(firstChannel.channel);
        firstRequest.release();

        TestChannel secondChannel = new TestChannel();
        FullHttpRequest secondRequest = websocketRequest("/ws/test");
        resolver.resolve(secondChannel.ctx, secondRequest);

        Object outbound = secondChannel.channel.readOutbound();
        try {
            assertTrue(outbound instanceof FullHttpResponse);
            assertEquals(SERVICE_UNAVAILABLE, ((FullHttpResponse) outbound).status());
            assertTrue(((FullHttpResponse) outbound).content()
                    .toString(CharsetUtil.UTF_8)
                    .contains("server.netty.websocket.max-connections"));
            assertEquals(1, resolver.getSessionMap().size());
            assertNull(secondChannel.channel.attr(ServiceHandler.REQUEST_IN_CHANNEL).get());
            assertNull(secondChannel.channel.attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).get());
        } finally {
            ReferenceCountUtil.release(outbound);
            secondRequest.release();
            resolver.onChannelInactive(firstChannel.ctx);
            firstChannel.finish();
            secondChannel.finish();
        }
    }

    @Test
    void releasesConnectionSlotWhenSessionCloses() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setMaxConnections(1);
        Semaphore connectionSemaphore = new Semaphore(1);
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint, properties, connectionSemaphore);

        TestChannel firstChannel = new TestChannel();
        FullHttpRequest firstRequest = websocketRequest("/ws/test");
        resolver.resolve(firstChannel.ctx, firstRequest);
        Object firstHandshake = firstChannel.channel.readOutbound();
        ReferenceCountUtil.release(firstHandshake);
        drainChannel(firstChannel.channel);
        firstRequest.release();

        assertEquals(0, connectionSemaphore.availablePermits());
        assertEquals(1, resolver.getSessionMap().size());

        resolver.onChannelInactive(firstChannel.ctx);

        assertEquals(1, connectionSemaphore.availablePermits());
        assertTrue(resolver.getSessionMap().isEmpty());

        TestChannel secondChannel = new TestChannel();
        FullHttpRequest secondRequest = websocketRequest("/ws/test");
        try {
            resolver.resolve(secondChannel.ctx, secondRequest);

            Object secondHandshake = secondChannel.channel.readOutbound();
            try {
                assertNotNull(secondHandshake);
                assertFalse(secondHandshake instanceof FullHttpResponse
                        && ((FullHttpResponse) secondHandshake).status().equals(SERVICE_UNAVAILABLE));
            } finally {
                ReferenceCountUtil.release(secondHandshake);
            }
            drainChannel(secondChannel.channel);

            assertEquals(0, connectionSemaphore.availablePermits());
            assertEquals(1, resolver.getSessionMap().size());
        } finally {
            secondRequest.release();
            resolver.onChannelInactive(secondChannel.ctx);
            firstChannel.finish();
            secondChannel.finish();
        }
    }

    @Test
    void shutdownClosesActiveSessionsAndReleasesConnectionSlots() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        methods.put(MessageType.ON_CLOSE, method(endpoint, "onClose", CloseWebSocketFrame.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setMaxConnections(1);
        Semaphore connectionSemaphore = new Semaphore(1);
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint, properties, connectionSemaphore);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        assertEquals(0, connectionSemaphore.availablePermits());
        assertEquals(1, resolver.getSessionMap().size());

        resolver.shutdown();
        drainChannel(testChannel.channel);

        Object closeResponse = testChannel.channel.readOutbound();
        try {
            assertTrue(closeResponse instanceof CloseWebSocketFrame);
            assertEquals(1001, ((CloseWebSocketFrame) closeResponse).statusCode());
            assertEquals("Server shutting down", ((CloseWebSocketFrame) closeResponse).reasonText());
        } finally {
            ReferenceCountUtil.release(closeResponse);
        }

        assertEquals(1, endpoint.closeCount);
        assertTrue(resolver.getSessionMap().isEmpty());
        assertEquals(1, connectionSemaphore.availablePermits());
        assertNull(testChannel.channel.attr(ServiceHandler.REQUEST_IN_CHANNEL).get());
        assertNull(testChannel.channel.attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).get());
        assertFalse(testChannel.channel.isActive());
        testChannel.finish();
    }

    @Test
    void oversizedFrameDispatchesErrorThenCloseLifecycle() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        methods.put(MessageType.ON_ERROR, method(endpoint, "onError", HttpRequest.class, MessageSession.class, Exception.class));
        methods.put(MessageType.ON_CLOSE, method(endpoint, "onClose", CloseWebSocketFrame.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setMaxFramePayloadLength(4);
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint, properties, null);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        TextWebSocketFrame frame = new TextWebSocketFrame("hello");
        resolver.resolve(testChannel.ctx, frame);
        frame.release();

        assertEquals(1, endpoint.errorCount);
        assertEquals(1, endpoint.closeCount);
        assertTrue(endpoint.lastErrorMessage.contains("too large"));
        assertTrue(resolver.getSessionMap().isEmpty());
        testChannel.finish();
    }

    @Test
    void textMessageCanBindStringPayload() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.TEXT_MESSAGE, method(endpoint, "onTextPayload", String.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        TextWebSocketFrame frame = new TextWebSocketFrame("hello");
        try {
            resolver.resolve(testChannel.ctx, frame);

            assertEquals("hello", endpoint.lastTextPayload);
            assertNotNull(endpoint.lastMessageSession);
            assertEquals(1, endpoint.textPayloadCount);
        } finally {
            frame.release();
            testChannel.finish();
        }
    }

    @Test
    void encryptedTextMessageIsDecodedBeforePayloadBinding() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.TEXT_MESSAGE, method(endpoint, "onTextPayload", String.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.getCrypto().setEnable(true);
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/test",
                methods,
                endpoint,
                properties,
                null,
                new PrefixMessageCryptoCodec());
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        TextWebSocketFrame frame = new TextWebSocketFrame("enc:hello");
        try {
            resolver.resolve(testChannel.ctx, frame);

            assertEquals("hello", endpoint.lastTextPayload);
            assertEquals(1, endpoint.textPayloadCount);
        } finally {
            frame.release();
            testChannel.finish();
        }
    }

    @Test
    void unencryptedTextFrameClosesSessionByDefaultWhenCryptoIsEnabled() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.TEXT_MESSAGE, method(endpoint, "onTextPayload", String.class, MessageSession.class));
        methods.put(MessageType.ON_ERROR, method(endpoint, "onError", HttpRequest.class, MessageSession.class, Exception.class));
        methods.put(MessageType.ON_CLOSE, method(endpoint, "onClose", CloseWebSocketFrame.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.getCrypto().setEnable(true);
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/test",
                methods,
                endpoint,
                properties,
                null,
                new PrefixMessageCryptoCodec());
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        TextWebSocketFrame frame = new TextWebSocketFrame("tampered");
        try {
            resolver.resolve(testChannel.ctx, frame);

            assertEquals(1, endpoint.errorCount);
            assertEquals(1, endpoint.closeCount);
            assertTrue(resolver.getSessionMap().isEmpty());
            assertFalse(testChannel.channel.isActive());
        } finally {
            frame.release();
            testChannel.finish();
        }
    }

    @Test
    void unencryptedTextFrameCanPassThroughWhenRejectUnencryptedIsDisabled() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.TEXT_MESSAGE, method(endpoint, "onTextPayload", String.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.getCrypto().setEnable(true);
        properties.getCrypto().setRejectUnencrypted(false);
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/test",
                methods,
                endpoint,
                properties,
                null,
                new PrefixMessageCryptoCodec());
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        TextWebSocketFrame frame = new TextWebSocketFrame("hello");
        try {
            resolver.resolve(testChannel.ctx, frame);

            assertEquals("hello", endpoint.lastTextPayload);
            assertEquals(1, endpoint.textPayloadCount);
            assertTrue(testChannel.channel.isActive());
        } finally {
            frame.release();
            testChannel.finish();
        }
    }

    @Test
    void unencryptedTextFrameCanPassThroughWhenSessionPathIsExcludedByCryptoPolicy() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.TEXT_MESSAGE, method(endpoint, "onTextPayload", String.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.getCrypto().setEnable(true);
        properties.getCrypto().setExcludeUris("/ws/test");
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/test",
                methods,
                endpoint,
                properties,
                null,
                new PrefixMessageCryptoCodec());
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test?client=legacy");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        TextWebSocketFrame frame = new TextWebSocketFrame("hello");
        try {
            resolver.resolve(testChannel.ctx, frame);

            assertEquals("hello", endpoint.lastTextPayload);
            assertEquals(1, endpoint.textPayloadCount);
            assertTrue(testChannel.channel.isActive());
        } finally {
            frame.release();
            testChannel.finish();
        }
    }

    @Test
    void unencryptedTextFrameCanPassThroughWhenSessionPathIsNotIncludedByCryptoPolicy() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.TEXT_MESSAGE, method(endpoint, "onTextPayload", String.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.getCrypto().setEnable(true);
        properties.getCrypto().setIncludeUris("/ws/secure");
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/test",
                methods,
                endpoint,
                properties,
                null,
                new PrefixMessageCryptoCodec());
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test?client=legacy");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        TextWebSocketFrame frame = new TextWebSocketFrame("hello");
        try {
            resolver.resolve(testChannel.ctx, frame);

            assertEquals("hello", endpoint.lastTextPayload);
            assertEquals(1, endpoint.textPayloadCount);
            assertTrue(testChannel.channel.isActive());
        } finally {
            frame.release();
            testChannel.finish();
        }
    }

    @Test
    void unencryptedTextFrameCanPassThroughWhenSessionPolicyDisablesCrypto() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.TEXT_MESSAGE, method(endpoint, "onTextPayload", String.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.getCrypto().setEnable(true);
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/test",
                methods,
                endpoint,
                properties,
                null,
                new PrefixMessageCryptoCodec(),
                new LegacyClientPlainCryptoPolicy());
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test?client=legacy");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        TextWebSocketFrame frame = new TextWebSocketFrame("hello");
        try {
            resolver.resolve(testChannel.ctx, frame);

            assertEquals("hello", endpoint.lastTextPayload);
            assertEquals(1, endpoint.textPayloadCount);
            assertTrue(testChannel.channel.isActive());
        } finally {
            frame.release();
            testChannel.finish();
        }
    }

    @Test
    void textMessageCanBindJsonPojoPayload() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.TEXT_MESSAGE,
                method(endpoint, "onJsonPayload", TextJsonPayload.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        TextWebSocketFrame frame = new TextWebSocketFrame("{\"name\":\"alpha\",\"count\":2}");
        try {
            resolver.resolve(testChannel.ctx, frame);

            assertNotNull(endpoint.lastJsonPayload);
            assertEquals("alpha", endpoint.lastJsonPayload.name);
            assertEquals(2, endpoint.lastJsonPayload.count);
            assertNotNull(endpoint.lastMessageSession);
            assertEquals(1, endpoint.jsonPayloadCount);
        } finally {
            frame.release();
            testChannel.finish();
        }
    }

    @Test
    void invalidJsonPayloadDispatchesErrorLifecycle() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.TEXT_MESSAGE,
                method(endpoint, "onJsonPayload", TextJsonPayload.class, MessageSession.class));
        methods.put(MessageType.ON_ERROR,
                method(endpoint, "onError", HttpRequest.class, MessageSession.class, Exception.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        TextWebSocketFrame frame = new TextWebSocketFrame("{");
        try {
            resolver.resolve(testChannel.ctx, frame);

            assertEquals(1, endpoint.errorCount);
            assertNotNull(endpoint.lastErrorMessage);
            assertTrue(endpoint.lastErrorMessage.contains("Failed to deserialize websocket text payload"));
            assertTrue(endpoint.lastErrorMessage.contains("Action: verify the incoming JSON shape"));
            assertEquals(0, endpoint.jsonPayloadCount);
            assertFalse(resolver.getSessionMap().isEmpty());
        } finally {
            frame.release();
            testChannel.finish();
        }
    }

    @Test
    void binaryMessageCanBindByteBufAndByteArrayPayload() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.BINARY_MESSAGE,
                method(endpoint, "onBinaryPayload", ByteBuf.class, byte[].class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        BinaryWebSocketFrame frame =
                new BinaryWebSocketFrame(Unpooled.copiedBuffer("binary", CharsetUtil.UTF_8));
        try {
            resolver.resolve(testChannel.ctx, frame);

            assertEquals("binary", endpoint.lastBinaryPayloadFromByteBuf);
            assertEquals("binary", endpoint.lastBinaryPayloadFromBytes);
            assertNotNull(endpoint.lastMessageSession);
            assertEquals(1, endpoint.binaryPayloadCount);
        } finally {
            frame.release();
            testChannel.finish();
        }
    }

    // ---- P6 Event Recording Tests ----

    @Test
    void eventRecorderCountsHandshakeAndCloseOnClientClose() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        resolver.setEventRecorder(recorder);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        WebSocketEventStats stats = recorder.getStats();
        assertEquals(1, stats.getHandshakeTotal());
        assertEquals(1, stats.getHandshakeSuccess());

        CloseWebSocketFrame closeFrame = new CloseWebSocketFrame(1000, "bye");
        try {
            resolver.resolve(testChannel.ctx, closeFrame);

            stats = recorder.getStats();
            assertEquals(1, stats.getCloseCount(CloseReason.CLIENT_CLOSE));
            assertEquals(1, stats.getTotalCloses());
        } finally {
            closeFrame.release();
            testChannel.finish();
        }
    }

    @Test
    void eventRecorderCountsMessagesReceivedOnTextFrame() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.TEXT_MESSAGE, method(endpoint, "onTextPayload", String.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        resolver.setEventRecorder(recorder);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        TextWebSocketFrame frame1 = new TextWebSocketFrame("hello");
        TextWebSocketFrame frame2 = new TextWebSocketFrame("world");
        try {
            resolver.resolve(testChannel.ctx, frame1);
            resolver.resolve(testChannel.ctx, frame2);

            WebSocketEventStats stats = recorder.getStats();
            assertEquals(2, stats.getMessagesReceived());
        } finally {
            frame1.release();
            frame2.release();
            resolver.onChannelInactive(testChannel.ctx);
            testChannel.finish();
        }
    }

    @Test
    void eventRecorderCountsChannelInactiveClose() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        resolver.setEventRecorder(recorder);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        resolver.onChannelInactive(testChannel.ctx);

        WebSocketEventStats stats = recorder.getStats();
        assertEquals(1, stats.getCloseCount(CloseReason.CHANNEL_INACTIVE));
        assertEquals(1, stats.getTotalCloses());
        testChannel.finish();
    }

    @Test
    void eventRecorderCountsShutdownClose() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        resolver.setEventRecorder(recorder);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        resolver.shutdown();
        drainChannel(testChannel.channel);

        WebSocketEventStats stats = recorder.getStats();
        assertEquals(1, stats.getCloseCount(CloseReason.SERVER_SHUTDOWN));
        assertEquals(1, stats.getTotalCloses());
        testChannel.finish();
    }

    @Test
    void eventRecorderCountsTransportErrorClose() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        methods.put(MessageType.ON_ERROR, method(endpoint, "onError", HttpRequest.class, MessageSession.class, Exception.class));
        methods.put(MessageType.ON_CLOSE, method(endpoint, "onClose", CloseWebSocketFrame.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        resolver.setEventRecorder(recorder);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        resolver.resolveException(testChannel.ctx, new IllegalStateException("transport error"));

        WebSocketEventStats stats = recorder.getStats();
        assertEquals(1, stats.getCloseCount(CloseReason.TRANSPORT_ERROR));
        assertEquals(1, stats.getTotalCloses());
        testChannel.finish();
    }

    @Test
    void eventRecorderCountsFrameTooLargeClose() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        methods.put(MessageType.ON_ERROR, method(endpoint, "onError", HttpRequest.class, MessageSession.class, Exception.class));
        methods.put(MessageType.ON_CLOSE, method(endpoint, "onClose", CloseWebSocketFrame.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setMaxFramePayloadLength(4);
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint, properties, null);
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        resolver.setEventRecorder(recorder);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        TextWebSocketFrame frame = new TextWebSocketFrame("hello");
        resolver.resolve(testChannel.ctx, frame);
        frame.release();

        WebSocketEventStats stats = recorder.getStats();
        assertEquals(1, stats.getCloseCount(CloseReason.FRAME_TOO_LARGE));
        assertEquals(1, stats.getTotalCloses());
        testChannel.finish();
    }

    @Test
    void eventRecorderCountsHandshakeFailure() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setMaxConnections(1);
        Semaphore connectionSemaphore = new Semaphore(1);
        MessageMappingResolver resolver =
                new MessageMappingResolver("/ws/test", methods, endpoint, properties, connectionSemaphore);
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        resolver.setEventRecorder(recorder);
        TestChannel testChannel = new TestChannel(new FailingHandshakeWriteHandler());
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        testChannel.channel.runPendingTasks();

        WebSocketEventStats stats = recorder.getStats();
        assertEquals(1, stats.getHandshakeTotal());
        assertEquals(0, stats.getHandshakeSuccess());
        assertEquals(1, stats.getCloseCount(CloseReason.HANDSHAKE_FAILURE));

        request.release();
        testChannel.finish();
    }

    @Test
    void handshakeInterceptorRejectsConnection() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        HttpRuntimeRecorder httpRecorder = new HttpRuntimeRecorder();
        resolver.setHttpRuntimeRecorder(httpRecorder);
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        resolver.setEventRecorder(recorder);
        resolver.setHandshakeInterceptor(new WebSocketHandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(FullHttpRequest request, String uri) {
                return false;
            }

            @Override
            public String rejectionReason() {
                return "Invalid token";
            }
        });
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);

        Object outbound = testChannel.channel.readOutbound();
        try {
            assertTrue(outbound instanceof FullHttpResponse);
            assertEquals(FORBIDDEN, ((FullHttpResponse) outbound).status());
            String body = ((FullHttpResponse) outbound).content().toString(CharsetUtil.UTF_8);
            assertTrue(body.contains("Invalid token"));
            assertTrue(resolver.getSessionMap().isEmpty());
            assertEquals(0, endpoint.connectedCount);
        } finally {
            ReferenceCountUtil.release(outbound);
            request.release();
            testChannel.finish();
        }

        WebSocketEventStats stats = recorder.getStats();
        assertEquals(1, stats.getHandshakeTotal());
        assertEquals(0, stats.getHandshakeSuccess());
        assertEquals(1, stats.getHandshakeRejectedByInterceptor());
    }

    @Test
    void handshakeInterceptorExceptionRejectsConnection() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/test", new EnumMap<>(MessageType.class), endpoint);
        HttpRuntimeRecorder httpRecorder = new HttpRuntimeRecorder();
        resolver.setHttpRuntimeRecorder(httpRecorder);
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        resolver.setEventRecorder(recorder);
        resolver.setHandshakeInterceptor(new WebSocketHandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(FullHttpRequest request, String uri) {
                throw new RuntimeException("interceptor exploded");
            }
        });
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);

        Object outbound = testChannel.channel.readOutbound();
        try {
            assertTrue(outbound instanceof FullHttpResponse);
            assertEquals(INTERNAL_SERVER_ERROR, ((FullHttpResponse) outbound).status());
        } finally {
            ReferenceCountUtil.release(outbound);
            request.release();
            testChannel.finish();
        }

        WebSocketEventStats stats = recorder.getStats();
        assertEquals(1, stats.getHandshakeTotal());
        assertEquals(1, stats.getHandshakeRejectedByInterceptor());
    }

    @Test
    void handshakeInterceptorAllowsConnectionWhenReturnsTrue() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        resolver.setEventRecorder(recorder);
        resolver.setHandshakeInterceptor(new WebSocketHandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(FullHttpRequest request, String uri) {
                return true;
            }
        });
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        try {
            resolver.resolve(testChannel.ctx, request);
            Object handshakeResponse = testChannel.channel.readOutbound();
            ReferenceCountUtil.release(handshakeResponse);
            drainChannel(testChannel.channel);

            assertEquals(1, endpoint.connectedCount);
            assertEquals(1, resolver.getSessionMap().size());

            WebSocketEventStats stats = recorder.getStats();
            assertEquals(1, stats.getHandshakeTotal());
            assertEquals(1, stats.getHandshakeSuccess());
            assertEquals(0, stats.getHandshakeRejectedByInterceptor());
        } finally {
            request.release();
            resolver.onChannelInactive(testChannel.ctx);
            testChannel.finish();
        }
    }

    // ---- v1.7.0 Fix #1: shutdown runs onClose lifecycle inline ----

    @Test
    void shutdownRunsOnCloseLifecycleInline() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        methods.put(MessageType.ON_CLOSE, method(endpoint, "onClose", CloseWebSocketFrame.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        assertEquals(0, endpoint.closeCount);

        // shutdown() should call onClose synchronously (inline) before returning
        resolver.shutdown();
        drainChannel(testChannel.channel);

        // onClose must have been called by the time shutdown() returns
        assertEquals(1, endpoint.closeCount);
        assertTrue(resolver.getSessionMap().isEmpty());
        testChannel.finish();
    }

    // ---- v1.7.0 Fix #3: closeSession TOCTOU race ----

    @Test
    void closeSessionWorksForActiveChannel() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        methods.put(MessageType.ON_CLOSE, method(endpoint, "onClose", CloseWebSocketFrame.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        resolver.setEventRecorder(recorder);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        String sessionId = testChannel.channel.attr(ServiceHandler.SESSION_ID_IN_CHANNEL).get();
        assertNotNull(sessionId);

        boolean closed = resolver.closeSession(sessionId, 1000, "normal");
        drainChannel(testChannel.channel);

        assertTrue(closed);
        assertEquals(1, endpoint.closeCount);
        assertTrue(resolver.getSessionMap().isEmpty());
        assertEquals(1, recorder.getStats().getCloseCount(CloseReason.API_CLOSE));
        testChannel.finish();
    }

    @Test
    void closeSessionReturnsFalseForUnknownSessionId() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/test", new EnumMap<>(MessageType.class), endpoint);
        assertFalse(resolver.closeSession("nonexistent", 1000, "bye"));
        assertFalse(resolver.closeSession(null, 1000, "bye"));
        assertFalse(resolver.closeSession("", 1000, "bye"));
    }

    // ---- v1.7.0 Fix #4: Host header validation ----

    @Test
    void rejectsHandshakeWithCrlfInHostHeader() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        TestChannel testChannel = new TestChannel();

        // Use DefaultHttpHeaders(false) to disable Netty's built-in header validation,
        // allowing the CRLF-injected Host value to reach our resolver's own validation.
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, GET, "/ws/test",
                Unpooled.EMPTY_BUFFER, new DefaultHttpHeaders(false), new DefaultHttpHeaders(false));
        request.headers().set(HttpHeaderNames.HOST, "localhost\r\nX-Injected: true");
        request.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        request.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");

        resolver.resolve(testChannel.ctx, request);

        try {
            // No session should be registered — CRLF Host must be rejected
            assertTrue(resolver.getSessionMap().isEmpty());
            assertEquals(0, endpoint.connectedCount);
            // The rejection response is written via ctx.channel().writeAndFlush() in doHandshake(),
            // which routes through HttpServerCodec and arrives as encoded ByteBuf.
            Object outbound = testChannel.channel.readOutbound();
            assertNotNull(outbound, "A rejection response should be written");
            if (outbound instanceof ByteBuf) {
                String encoded = ((ByteBuf) outbound).toString(CharsetUtil.UTF_8);
                assertTrue(encoded.contains("400"), "Encoded response should contain 400 status");
            } else if (outbound instanceof FullHttpResponse) {
                assertEquals(HttpResponseStatus.BAD_REQUEST, ((FullHttpResponse) outbound).status());
            }
            ReferenceCountUtil.release(outbound);
        } finally {
            request.release();
            testChannel.finish();
        }
    }

    @Test
    void rejectsHandshakeWithMissingHostHeader() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        TestChannel testChannel = new TestChannel();

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, GET, "/ws/test");
        // Deliberately omit Host header
        request.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        request.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");

        resolver.resolve(testChannel.ctx, request);

        try {
            // No session should be registered — missing Host must be rejected
            assertTrue(resolver.getSessionMap().isEmpty());
            // The rejection response is written via ctx.channel().writeAndFlush() in doHandshake(),
            // which routes through HttpServerCodec and arrives as encoded ByteBuf.
            Object outbound = testChannel.channel.readOutbound();
            assertNotNull(outbound, "A rejection response should be written");
            if (outbound instanceof ByteBuf) {
                String encoded = ((ByteBuf) outbound).toString(CharsetUtil.UTF_8);
                assertTrue(encoded.contains("400"), "Encoded response should contain 400 status");
            } else if (outbound instanceof FullHttpResponse) {
                assertEquals(HttpResponseStatus.BAD_REQUEST, ((FullHttpResponse) outbound).status());
            }
            ReferenceCountUtil.release(outbound);
        } finally {
            request.release();
            testChannel.finish();
        }
    }

    @Test
    void eventCountersMapIncludesAllMetrics() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        resolver.setEventRecorder(recorder);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        resolver.resolve(testChannel.ctx, request);
        Object handshakeResponse = testChannel.channel.readOutbound();
        ReferenceCountUtil.release(handshakeResponse);
        drainChannel(testChannel.channel);
        request.release();

        resolver.onChannelInactive(testChannel.ctx);

        Map<String, Object> counters = resolver.getEventCounters();
        assertEquals(1L, counters.get("handshakeTotal"));
        assertEquals(1L, counters.get("handshakeSuccess"));
        assertEquals(0L, counters.get("handshakeRejectedByInterceptor"));
        assertEquals(0L, counters.get("messagesReceived"));
        assertEquals(0L, counters.get("messagesSent"));
        assertEquals(1L, counters.get("totalCloses"));
        @SuppressWarnings("unchecked")
        Map<String, Long> closesByReason = (Map<String, Long>) counters.get("closesByReason");
        assertNotNull(closesByReason);
        assertEquals(1L, closesByReason.get("channel_inactive"));
        testChannel.finish();
    }

    // ---- v1.7.0 第四刀: WebSocket frame aggregation ----

    @Test
    void frameAggregatorAddedToPipelineWhenConfigured() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setMaxFrameAggregationBufferSize(65536);
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint, properties, null);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        try {
            resolver.resolve(testChannel.ctx, request);
            Object handshakeResponse = testChannel.channel.readOutbound();
            ReferenceCountUtil.release(handshakeResponse);
            drainChannel(testChannel.channel);

            assertEquals(1, endpoint.connectedCount);
            assertEquals(1, resolver.getSessionMap().size());
            // The WebSocketFrameAggregator should have been added to the pipeline
            assertNotNull(testChannel.channel.pipeline().get("wsFrameAggregator"),
                    "WebSocketFrameAggregator should be in the pipeline when maxFrameAggregationBufferSize > 0");
            assertTrue(testChannel.channel.pipeline().get("wsFrameAggregator") instanceof WebSocketFrameAggregator);
        } finally {
            request.release();
            resolver.onChannelInactive(testChannel.ctx);
            testChannel.finish();
        }
    }

    @Test
    void noFrameAggregatorWhenNotConfigured() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        try {
            resolver.resolve(testChannel.ctx, request);
            Object handshakeResponse = testChannel.channel.readOutbound();
            ReferenceCountUtil.release(handshakeResponse);
            drainChannel(testChannel.channel);

            assertEquals(1, endpoint.connectedCount);
            // No aggregator should be in the pipeline when not configured
            assertNull(testChannel.channel.pipeline().get("wsFrameAggregator"),
                    "WebSocketFrameAggregator should NOT be in the pipeline when maxFrameAggregationBufferSize is not set");
        } finally {
            request.release();
            resolver.onChannelInactive(testChannel.ctx);
            testChannel.finish();
        }
    }

    @Test
    void noFrameAggregatorWhenBufferSizeIsZero() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setMaxFrameAggregationBufferSize(0);
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint, properties, null);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        try {
            resolver.resolve(testChannel.ctx, request);
            Object handshakeResponse = testChannel.channel.readOutbound();
            ReferenceCountUtil.release(handshakeResponse);
            drainChannel(testChannel.channel);

            assertEquals(1, endpoint.connectedCount);
            assertNull(testChannel.channel.pipeline().get("wsFrameAggregator"),
                    "WebSocketFrameAggregator should NOT be in the pipeline when maxFrameAggregationBufferSize is 0");
        } finally {
            request.release();
            resolver.onChannelInactive(testChannel.ctx);
            testChannel.finish();
        }
    }

    @Test
    void continuationFrameDiscardedWithWarningWhenAggregationDisabled() throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, method(endpoint, "onConnected", HttpRequest.class, MessageSession.class));
        methods.put(MessageType.TEXT_MESSAGE, method(endpoint, "onTextPayload", String.class, MessageSession.class));
        MessageMappingResolver resolver = new MessageMappingResolver("/ws/test", methods, endpoint);
        TestChannel testChannel = new TestChannel();
        FullHttpRequest request = websocketRequest("/ws/test");

        try {
            resolver.resolve(testChannel.ctx, request);
            Object handshakeResponse = testChannel.channel.readOutbound();
            ReferenceCountUtil.release(handshakeResponse);
            drainChannel(testChannel.channel);

            // Send a ContinuationWebSocketFrame without aggregation enabled — should be silently discarded
            ContinuationWebSocketFrame contFrame =
                    new ContinuationWebSocketFrame(Unpooled.copiedBuffer("fragment", CharsetUtil.UTF_8));
            resolver.resolve(testChannel.ctx, contFrame);
            contFrame.release();

            // No text message handler should have been called
            assertEquals(0, endpoint.textPayloadCount);
            // Session should remain active (not closed on continuation frame)
            assertEquals(1, resolver.getSessionMap().size());
        } finally {
            request.release();
            resolver.onChannelInactive(testChannel.ctx);
            testChannel.finish();
        }
    }

    private static Method method(Object target, String methodName, Class<?>... parameterTypes) throws Exception {
        return target.getClass().getMethod(methodName, parameterTypes);
    }

    private static FullHttpRequest websocketRequest(String uri) {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, GET, uri);
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        request.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        request.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        return request;
    }

    private static void drainChannel(EmbeddedChannel channel) {
        channel.runPendingTasks();
        channel.runScheduledPendingTasks();
    }

    private static final class TestChannel {
        private final EmbeddedChannel channel;
        private final ChannelHandlerContext ctx;

        private TestChannel(ChannelHandler... extraHandlers) {
            ContextHolder holder = new ContextHolder();
            List<ChannelHandler> handlers = new ArrayList<>();
            handlers.add(holder);
            handlers.add(new HttpServerCodec());
            for (ChannelHandler extraHandler : extraHandlers) {
                handlers.add(extraHandler);
            }
            this.channel = new EmbeddedChannel(handlers.toArray(new ChannelHandler[0]));
            this.ctx = holder.ctx;
            assertNotNull(this.ctx);
        }

        private void finish() {
            channel.finishAndReleaseAll();
        }
    }

    private static final class FailingHandshakeWriteHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            promise.setFailure(new IllegalStateException("handshake write failed"));
        }
    }

    private static final class ContextHolder extends ChannelInboundHandlerAdapter {
        private ChannelHandlerContext ctx;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }
    }

    private static final class RecordingHandlerSubmitter implements HandlerSubmitter {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void submitHandle(Runnable runnable) {
            tasks.add(runnable);
        }

        private int size() {
            return tasks.size();
        }

        private void runNext() {
            Runnable task = tasks.remove(0);
            task.run();
        }
    }

    private static final class PrefixMessageCryptoCodec implements MessageCryptoCodec {
        @Override
        public TextWebSocketFrame encrypt(MessageSession session, io.netty.handler.codec.http.websocketx.WebSocketFrame plainFrame) {
            return new TextWebSocketFrame("enc:" + ((TextWebSocketFrame) plainFrame).text());
        }

        @Override
        public boolean canDecrypt(MessageSession session, io.netty.handler.codec.http.websocketx.WebSocketFrame encryptedFrame) {
            return encryptedFrame instanceof TextWebSocketFrame
                    && ((TextWebSocketFrame) encryptedFrame).text().startsWith("enc:");
        }

        @Override
        public TextWebSocketFrame decrypt(MessageSession session, io.netty.handler.codec.http.websocketx.WebSocketFrame encryptedFrame) {
            String text = ((TextWebSocketFrame) encryptedFrame).text();
            if (!text.startsWith("enc:")) {
                throw new IllegalArgumentException("Missing encrypted prefix.");
            }
            return new TextWebSocketFrame(text.substring("enc:".length()));
        }
    }

    private static final class LegacyClientPlainCryptoPolicy implements MessageCryptoPolicy {
        @Override
        public boolean shouldUseCrypto(MessageSession session) {
            return !"legacy".equals(session.getQueryParam("client"));
        }
    }

    public static final class RecordingEndpoint {
        private boolean handshakeAllowed = true;
        private boolean throwOnHandshake;
        private boolean throwOnError;
        private boolean throwOnClose;
        private int connectedCount;
        private int closeCount;
        private int errorCount;
        private String lastErrorMessage;
        private int lastErrorRequestRefCnt;
        private int textPayloadCount;
        private int binaryPayloadCount;
        private int jsonPayloadCount;
        private String lastTextPayload;
        private String lastBinaryPayloadFromByteBuf;
        private String lastBinaryPayloadFromBytes;
        private TextJsonPayload lastJsonPayload;
        private MessageSession lastMessageSession;

        public Boolean onHandshake(HttpRequest request) {
            if (throwOnHandshake) {
                throw new IllegalStateException("handshake boom");
            }
            return handshakeAllowed;
        }

        public void onConnected(HttpRequest request, MessageSession session) {
            connectedCount++;
        }

        public void onClose(CloseWebSocketFrame frame, MessageSession session) {
            closeCount++;
            if (throwOnClose) {
                throw new IllegalStateException("close boom");
            }
        }

        public void onError(HttpRequest request, MessageSession session, Exception exception) {
            errorCount++;
            lastErrorMessage = exception.getMessage();
            if (request instanceof FullHttpRequest) {
                lastErrorRequestRefCnt = ((FullHttpRequest) request).refCnt();
            }
            if (throwOnError) {
                throw new IllegalStateException("error handler failed");
            }
        }

        public void onTextPayload(String payload, MessageSession session) {
            textPayloadCount++;
            lastTextPayload = payload;
            lastMessageSession = session;
        }

        public void onJsonPayload(TextJsonPayload payload, MessageSession session) {
            jsonPayloadCount++;
            lastJsonPayload = payload;
            lastMessageSession = session;
        }

        public void onBinaryPayload(ByteBuf payload, byte[] bytes, MessageSession session) {
            binaryPayloadCount++;
            lastBinaryPayloadFromByteBuf = payload.toString(CharsetUtil.UTF_8);
            lastBinaryPayloadFromBytes = new String(bytes, CharsetUtil.UTF_8);
            lastMessageSession = session;
        }
    }

    public static final class TextJsonPayload {
        public String name;
        public int count;
    }
}
