package com.github.berrywang1996.netty.spring.web.websocket.bind;

import com.github.berrywang1996.netty.spring.web.context.HandlerSubmitter;
import com.github.berrywang1996.netty.spring.web.handler.ServiceHandler;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
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
    }
}
