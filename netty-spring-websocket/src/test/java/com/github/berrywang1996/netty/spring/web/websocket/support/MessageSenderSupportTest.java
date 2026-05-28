package com.github.berrywang1996.netty.spring.web.websocket.support;

import com.github.berrywang1996.netty.spring.web.handler.ServiceHandler;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.context.DefaultMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSenderRuntimeStats;
import com.github.berrywang1996.netty.spring.web.websocket.context.TextMessage;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static io.netty.handler.codec.http.HttpMethod.GET;

class MessageSenderSupportTest {

    @Test
    void shutdownStopsCachedSender() throws Exception {
        MessageSenderSupport support = new MessageSenderSupport(new NettyServerBootstrap(null));
        NettyServerStartupProperties.WebSocket wsProps = new NettyServerStartupProperties.WebSocket();
        wsProps.setBroadcastMode(NettyServerStartupProperties.WebSocket.BroadcastMode.THREAD_POOL_LEGACY);
        DefaultMessageSender sender =
                new DefaultMessageSender(Collections.<String, MessageMappingResolver>emptyMap(), wsProps);
        setField(MessageSenderSupport.class, support, "messageSender", sender);

        ThreadPoolExecutor executor = extractExecutor(sender);
        support.shutdown();

        assertTrue(executor.isShutdown());
        assertNull(getField(MessageSenderSupport.class, support, "messageSender"));
    }

    @Test
    void shutdownIsSafeWhenSenderWasNeverInitialized() {
        MessageSenderSupport support = new MessageSenderSupport(new NettyServerBootstrap(null));

        assertDoesNotThrow(support::shutdown);
    }

    @Test
    void fallsBackToEmptySenderWhenNoWebsocketMappingsExist() {
        MessageSenderSupport support = new MessageSenderSupport(new NettyServerBootstrap(null));

        assertEquals(0, support.getSessionNums());
        assertEquals(0, support.getSessionNums("/ws/test"));
        assertTrue(support.getSessionIds("/ws/test").isEmpty());
        assertNull(support.getSession("/ws/test", "session-1"));
        assertTrue(support.getSessions("/ws/test").isEmpty());
        assertTrue(support.getRegisteredUri().isEmpty());
        assertFalse(support.isSessionAlive("/ws/test", "session-1"));
        assertFalse(support.closeSession("/ws/test", "session-1"));
        assertEquals(0, support.closeSessions("/ws/test"));
        MessageSenderRuntimeStats stats = support.getRuntimeStats();
        assertEquals(0L, stats.getRejectedBroadcastCount());
        assertTrue(stats.getExecutor().isShutdown());
        MessageUriNotDefinedException sendException = assertThrows(MessageUriNotDefinedException.class,
                () -> support.sendMessage("/ws/test", new TextMessage("hello"), "session-1"));
        assertTrue(sendException.getMessage().contains("No websocket mappings are currently registered"));
        assertTrue(sendException.getMessage().contains("server.netty.websocket.enable"));
        MessageUriNotDefinedException broadcastException = assertThrows(MessageUriNotDefinedException.class,
                () -> support.topicMessage("/ws/test", new TextMessage("hello")));
        assertTrue(broadcastException.getMessage().contains("No websocket mappings are currently registered"));
    }

    @Test
    void rebuildsCachedSenderWhenBootstrapResolverChangesAcrossRestart() throws Exception {
        NettyServerBootstrap bootstrap = new NettyServerBootstrap(null);
        NettyServerStartupProperties startupProps = new NettyServerStartupProperties();
        startupProps.getWebSocket().setBroadcastMode(
                NettyServerStartupProperties.WebSocket.BroadcastMode.THREAD_POOL_LEGACY);
        setField(NettyServerBootstrap.class, bootstrap, "startupProperties", startupProps);
        MessageSenderSupport support = new MessageSenderSupport(bootstrap);
        MessageMappingResolver firstResolver = new MessageMappingResolver(
                "/ws/test",
                Collections.emptyMap(),
                new Object());
        SessionFixture firstSession = addSession(firstResolver, "/ws/test", "old-session");

        try {
            setField(NettyServerBootstrap.class, bootstrap, "webSocketMappingResolverMap",
                    Collections.singletonMap("/ws/test", firstResolver));

            DefaultMessageSender firstSender = (DefaultMessageSender) support.getMessageSender();
            ThreadPoolExecutor firstExecutor = extractExecutor(firstSender);
            assertEquals(1, support.getSessionNums("/ws/test"));

            setField(NettyServerBootstrap.class, bootstrap, "webSocketMappingResolverMap", null);

            assertEquals(0, support.getSessionNums());
            assertTrue(firstExecutor.isShutdown());

            MessageMappingResolver secondResolver = new MessageMappingResolver(
                    "/ws/test",
                    Collections.emptyMap(),
                    new Object());
            SessionFixture secondSession = addSession(secondResolver, "/ws/test", "new-session");
            try {
                Map<String, MessageMappingResolver> restartedMap =
                        Collections.singletonMap("/ws/test", secondResolver);
                setField(NettyServerBootstrap.class, bootstrap, "webSocketMappingResolverMap", restartedMap);

                assertEquals(Collections.singleton("new-session"), support.getSessionIds("/ws/test"));
                assertEquals("new-session", support.getSession("/ws/test", "new-session").getSessionId());
                assertEquals(1, support.getSessions("/ws/test").size());
                assertThrows(UnsupportedOperationException.class, () -> support.getSessions("/ws/test").clear());

                support.sendToSession("/ws/test", new TextMessage("hello"), "new-session");

                Object outbound = secondSession.channel.readOutbound();
                try {
                    assertTrue(outbound instanceof TextWebSocketFrame);
                    assertEquals("hello", ((TextWebSocketFrame) outbound).text());
                } finally {
                    ReferenceCountUtil.release(outbound);
                }

                assertTrue(support.closeSession("/ws/test", "new-session"));
                assertEquals(0, support.getSessionNums("/ws/test"));

                DefaultMessageSender restartedSender = (DefaultMessageSender) support.getMessageSender();
                assertNotSame(firstSender, restartedSender);
                assertNull(firstSession.channel.readOutbound());
            } finally {
                cleanup(secondResolver, secondSession);
            }
        } finally {
            support.shutdown();
            cleanup(firstResolver, firstSession);
        }
    }

    @Test
    void textAndJsonConvenienceMethodsSendExpectedFrames() throws Exception {
        NettyServerBootstrap bootstrap = new NettyServerBootstrap(null);
        MessageSenderSupport support = new MessageSenderSupport(bootstrap);
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/test",
                Collections.emptyMap(),
                new Object());
        SessionFixture session = addSession(resolver, "/ws/test", "session-1");

        try {
            setField(NettyServerBootstrap.class, bootstrap, "webSocketMappingResolverMap",
                    Collections.singletonMap("/ws/test", resolver));

            support.sendTextToSession("/ws/test", "hello-text", "session-1");
            Object textOutbound = session.channel.readOutbound();
            try {
                assertTrue(textOutbound instanceof TextWebSocketFrame);
                assertEquals("hello-text", ((TextWebSocketFrame) textOutbound).text());
            } finally {
                ReferenceCountUtil.release(textOutbound);
            }

            support.sendJsonToSession("/ws/test", Collections.singletonMap("message", "hello-json"), "session-1");
            Object jsonOutbound = session.channel.readOutbound();
            try {
                assertTrue(jsonOutbound instanceof TextWebSocketFrame);
                assertTrue(((TextWebSocketFrame) jsonOutbound).text().contains("\"message\":\"hello-json\""));
            } finally {
                ReferenceCountUtil.release(jsonOutbound);
            }
        } finally {
            support.shutdown();
            cleanup(resolver, session);
        }
    }

    @Test
    void bootstrapStopShutsDownCachedSender() throws Exception {
        NettyServerBootstrap bootstrap = new NettyServerBootstrap(null);
        NettyServerStartupProperties startupProps = new NettyServerStartupProperties();
        startupProps.getWebSocket().setBroadcastMode(
                NettyServerStartupProperties.WebSocket.BroadcastMode.THREAD_POOL_LEGACY);
        setField(NettyServerBootstrap.class, bootstrap, "startupProperties", startupProps);
        MessageSenderSupport support = new MessageSenderSupport(bootstrap);
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/test",
                Collections.emptyMap(),
                new Object());

        setField(NettyServerBootstrap.class, bootstrap, "webSocketMappingResolverMap",
                Collections.singletonMap("/ws/test", resolver));
        ((AtomicBoolean) getField(NettyServerBootstrap.class, bootstrap, "stopped")).set(false);

        DefaultMessageSender sender = (DefaultMessageSender) support.getMessageSender();
        ThreadPoolExecutor executor = extractExecutor(sender);

        bootstrap.stop();

        assertTrue(executor.isShutdown());
        assertNull(getField(MessageSenderSupport.class, support, "messageSender"));
    }

    private static ThreadPoolExecutor extractExecutor(DefaultMessageSender sender) throws Exception {
        Field field = DefaultMessageSender.class.getDeclaredField("executorService");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(sender);
    }

    private static SessionFixture addSession(MessageMappingResolver resolver, String uri, String sessionId) {
        ContextHolder holder = new ContextHolder();
        EmbeddedChannel channel = new EmbeddedChannel(holder);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, GET, uri);
        MessageSession session = new MessageSession(sessionId, holder.ctx, request);
        holder.ctx.channel().attr(ServiceHandler.REQUEST_IN_CHANNEL).set(session.getFirstRequest());
        holder.ctx.channel().attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).set(sessionId);
        request.release();
        resolver.getSessionMap().put(sessionId, session);
        return new SessionFixture(channel, sessionId);
    }

    private static void cleanup(MessageMappingResolver resolver, SessionFixture session) {
        resolver.removeSession(session.sessionId);
        session.channel.attr(ServiceHandler.REQUEST_IN_CHANNEL).set(null);
        session.channel.attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).set(null);
        session.channel.finishAndReleaseAll();
    }

    private static Object getField(Class<?> type, Object target, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Class<?> type, Object target, String fieldName, Object value) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class SessionFixture {
        private final EmbeddedChannel channel;
        private final String sessionId;

        private SessionFixture(EmbeddedChannel channel, String sessionId) {
            this.channel = channel;
            this.sessionId = sessionId;
        }
    }

    private static final class ContextHolder extends ChannelInboundHandlerAdapter {
        private ChannelHandlerContext ctx;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }
    }
}
