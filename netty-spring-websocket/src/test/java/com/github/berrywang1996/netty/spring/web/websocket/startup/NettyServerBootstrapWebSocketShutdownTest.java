package com.github.berrywang1996.netty.spring.web.websocket.startup;

import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import com.github.berrywang1996.netty.spring.web.context.WebMappingSupporter;
import com.github.berrywang1996.netty.spring.web.handler.ServiceHandler;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyServerBootstrapWebSocketShutdownTest {

    @Test
    void stopClosesActiveWebsocketSessions() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        NettyServerStartupProperties startupProperties = new NettyServerStartupProperties();
        NettyServerStartupProperties.WebSocket webSocket = new NettyServerStartupProperties.WebSocket();
        startupProperties.setWebSocket(webSocket);
        Semaphore connectionSemaphore = new Semaphore(0);
        ShutdownEndpoint endpoint = new ShutdownEndpoint();
        MessageMappingResolver resolver = lifecycleResolver("/ws/test", endpoint, webSocket, connectionSemaphore);
        SessionFixture session = addSession(resolver, "/ws/test", "session-1", connectionSemaphore);
        WebMappingSupporter supporter = newSupporter(
                startupProperties,
                executor,
                Collections.<String, AbstractMappingResolver>singletonMap("/ws/test", resolver),
                new Semaphore(8));
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
        NioEventLoopGroup workerGroup = new NioEventLoopGroup(1);
        EmbeddedChannel serverChannel = new EmbeddedChannel();
        NettyServerBootstrap bootstrap = new NettyServerBootstrap(null);

        try {
            setField(NettyServerBootstrap.class, bootstrap, "bossGroup", bossGroup);
            setField(NettyServerBootstrap.class, bootstrap, "workerGroup", workerGroup);
            setField(NettyServerBootstrap.class, bootstrap, "serverChannel", serverChannel);
            setField(NettyServerBootstrap.class, bootstrap, "webMappingSupporter", supporter);
            ((AtomicBoolean) getField(NettyServerBootstrap.class, bootstrap, "stopped")).set(false);

            bootstrap.stop();
            session.channel.runPendingTasks();
            session.channel.runScheduledPendingTasks();

            Object outbound = session.channel.readOutbound();
            try {
                assertTrue(outbound instanceof CloseWebSocketFrame);
                assertEquals(1001, ((CloseWebSocketFrame) outbound).statusCode());
                assertEquals("Server shutting down", ((CloseWebSocketFrame) outbound).reasonText());
            } finally {
                if (outbound != null) {
                    ReferenceCountUtil.release(outbound);
                }
            }

            assertEquals(1, endpoint.closeCount);
            assertTrue(resolver.getSessionMap().isEmpty());
            assertEquals(1, connectionSemaphore.availablePermits());
            assertNull(session.channel.attr(ServiceHandler.REQUEST_IN_CHANNEL).get());
            assertNull(session.channel.attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).get());
            assertFalse(session.channel.isActive());
            assertTrue(executor.isShutdown());
        } finally {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup.shutdownGracefully().syncUninterruptibly();
            session.channel.finishAndReleaseAll();
            serverChannel.finishAndReleaseAll();
        }
    }

    @Test
    void repeatedStopDoesNotDuplicateWebsocketShutdownLifecycle() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        NettyServerStartupProperties startupProperties = new NettyServerStartupProperties();
        NettyServerStartupProperties.WebSocket webSocket = new NettyServerStartupProperties.WebSocket();
        startupProperties.setWebSocket(webSocket);
        Semaphore connectionSemaphore = new Semaphore(0);
        ShutdownEndpoint endpoint = new ShutdownEndpoint();
        MessageMappingResolver resolver = lifecycleResolver("/ws/test", endpoint, webSocket, connectionSemaphore);
        SessionFixture session = addSession(resolver, "/ws/test", "session-1", connectionSemaphore);
        WebMappingSupporter supporter = newSupporter(
                startupProperties,
                executor,
                Collections.<String, AbstractMappingResolver>singletonMap("/ws/test", resolver),
                new Semaphore(8));
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
        NioEventLoopGroup workerGroup = new NioEventLoopGroup(1);
        EmbeddedChannel serverChannel = new EmbeddedChannel();
        NettyServerBootstrap bootstrap = new NettyServerBootstrap(null);
        AtomicInteger stopListenerCount = new AtomicInteger();

        try {
            bootstrap.addStopListener(new Runnable() {
                @Override
                public void run() {
                    stopListenerCount.incrementAndGet();
                }
            });
            setField(NettyServerBootstrap.class, bootstrap, "bossGroup", bossGroup);
            setField(NettyServerBootstrap.class, bootstrap, "workerGroup", workerGroup);
            setField(NettyServerBootstrap.class, bootstrap, "serverChannel", serverChannel);
            setField(NettyServerBootstrap.class, bootstrap, "webMappingSupporter", supporter);
            ((AtomicBoolean) getField(NettyServerBootstrap.class, bootstrap, "stopped")).set(false);

            bootstrap.stop();
            session.channel.runPendingTasks();
            session.channel.runScheduledPendingTasks();

            Object outbound = session.channel.readOutbound();
            try {
                assertTrue(outbound instanceof CloseWebSocketFrame);
                assertEquals(1001, ((CloseWebSocketFrame) outbound).statusCode());
            } finally {
                if (outbound != null) {
                    ReferenceCountUtil.release(outbound);
                }
            }

            bootstrap.stop();
            session.channel.runPendingTasks();
            session.channel.runScheduledPendingTasks();

            assertNull(session.channel.readOutbound());
            assertEquals(1, endpoint.closeCount);
            assertEquals(1, stopListenerCount.get());
            assertTrue(resolver.getSessionMap().isEmpty());
            assertEquals(1, connectionSemaphore.availablePermits());
            assertFalse(session.channel.isActive());
            assertTrue(executor.isShutdown());
        } finally {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup.shutdownGracefully().syncUninterruptibly();
            session.channel.finishAndReleaseAll();
            serverChannel.finishAndReleaseAll();
        }
    }

    private static WebMappingSupporter newSupporter(NettyServerStartupProperties startupProperties,
                                                    ThreadPoolExecutor executor,
                                                    Map<String, AbstractMappingResolver> mappingResolverMap,
                                                    Semaphore semaphore) throws Exception {
        Constructor<WebMappingSupporter> constructor = WebMappingSupporter.class.getDeclaredConstructor(
                NettyServerStartupProperties.class,
                org.springframework.context.ApplicationContext.class,
                java.util.Map.class,
                ThreadPoolExecutor.class,
                Semaphore.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                startupProperties,
                null,
                mappingResolverMap,
                executor,
                semaphore);
    }

    private static MessageMappingResolver lifecycleResolver(String uri,
                                                            ShutdownEndpoint endpoint,
                                                            NettyServerStartupProperties.WebSocket webSocket,
                                                            Semaphore connectionSemaphore) throws Exception {
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CLOSE, method(endpoint, "onClose", CloseWebSocketFrame.class, MessageSession.class));
        return new MessageMappingResolver(uri, methods, endpoint, webSocket, connectionSemaphore);
    }

    private static SessionFixture addSession(MessageMappingResolver resolver,
                                             String uri,
                                             String sessionId,
                                             Semaphore connectionSemaphore) {
        ContextHolder holder = new ContextHolder();
        EmbeddedChannel channel = new EmbeddedChannel(holder);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, GET, uri);
        MessageSession session = new MessageSession(sessionId, holder.ctx, request, new Runnable() {
            @Override
            public void run() {
                connectionSemaphore.release();
            }
        });
        holder.ctx.channel().attr(ServiceHandler.REQUEST_IN_CHANNEL).set(session.getFirstRequest());
        holder.ctx.channel().attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).set(sessionId);
        resolver.getSessionMap().put(sessionId, session);
        request.release();
        return new SessionFixture(channel);
    }

    private static Method method(Object target, String methodName, Class<?>... parameterTypes) throws Exception {
        return target.getClass().getMethod(methodName, parameterTypes);
    }

    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class SessionFixture {
        private final EmbeddedChannel channel;

        private SessionFixture(EmbeddedChannel channel) {
            this.channel = channel;
        }
    }

    private static final class ContextHolder extends ChannelInboundHandlerAdapter {
        private ChannelHandlerContext ctx;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }
    }

    public static final class ShutdownEndpoint {
        private int closeCount;

        public void onClose(CloseWebSocketFrame frame, MessageSession session) {
            closeCount++;
        }
    }
}
