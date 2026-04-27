package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.context.HandlerSubmitter;
import com.github.berrywang1996.netty.spring.web.handler.ServiceHandler;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.crypto.MessageCryptoCodec;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMessageSenderTest {

    @Test
    void aggregatesSessionCountAndAliveStatus() {
        MessageMappingResolver fooResolver = emptyResolver("/ws/foo");
        MessageMappingResolver barResolver = emptyResolver("/ws/bar");
        SessionFixture fooSession1 = addSession(fooResolver, "/ws/foo", "foo-1");
        SessionFixture fooSession2 = addSession(fooResolver, "/ws/foo", "foo-2");
        SessionFixture barSession = addSession(barResolver, "/ws/bar", "bar-1");
        DefaultMessageSender sender = new DefaultMessageSender(mapOf(
                "/ws/foo", fooResolver,
                "/ws/bar", barResolver));

        try {
            assertEquals(3, sender.getSessionNums());
            assertEquals(2, sender.getSessionNums("/ws/foo"));
            assertEquals(new HashSet<>(Arrays.asList("foo-1", "foo-2")), sender.getSessionIds("/ws/foo"));
            assertEquals("foo-1", sender.getSession("/ws/foo", "foo-1").getSessionId());
            assertNull(sender.getSession("/ws/foo", "missing"));
            assertEquals(2, sender.getSessions("/ws/foo").size());
            assertTrue(sender.getSessionIds("/ws/missing").isEmpty());
            assertTrue(sender.getSessions("/ws/missing").isEmpty());
            assertTrue(sender.isSessionAlive("/ws/foo", "foo-1", "foo-2"));
            assertFalse(sender.isSessionAlive("/ws/foo", "foo-1", "missing"));

            Set<String> sessionIds = sender.getSessionIds("/ws/foo");
            Map<String, MessageSession> sessions = sender.getSessions("/ws/foo");
            assertThrows(UnsupportedOperationException.class, () -> sessionIds.add("new-session"));
            assertThrows(UnsupportedOperationException.class, sessions::clear);
        } finally {
            cleanup(fooResolver, fooSession1, fooSession2);
            cleanup(barResolver, barSession);
        }
    }

    @Test
    void sendMessageReportsClosedSessionsAndCleansThemUp() {
        MessageMappingResolver resolver = emptyResolver("/ws/foo");
        SessionFixture activeSession = addSession(resolver, "/ws/foo", "active");
        SessionFixture closedSession = addSession(resolver, "/ws/foo", "closed");
        DefaultMessageSender sender = new DefaultMessageSender(Collections.singletonMap("/ws/foo", resolver));
        closedSession.channel.close();

        try {
            MessageSessionClosedException exception = assertThrows(
                    MessageSessionClosedException.class,
                    () -> sender.sendMessage("/ws/foo", new TextMessage("hello"), "active", "closed"));

            assertEquals(Collections.singletonList("closed"), exception.getSessionIds());
            Object outbound = awaitOutbound(activeSession);
            try {
                assertTrue(outbound instanceof TextWebSocketFrame);
                assertEquals("hello", ((TextWebSocketFrame) outbound).text());
            } finally {
                ReferenceCountUtil.release(outbound);
            }
            assertFalse(resolver.getSessionMap().containsKey("closed"));
            assertTrue(sender.isSessionAlive("/ws/foo", "active"));
        } finally {
            cleanup(resolver, activeSession, closedSession);
        }
    }

    @Test
    void sendMessageEncryptsTextFrameWhenCryptoIsEnabled() throws Exception {
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.getCrypto().setEnable(true);
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/foo",
                Collections.<MessageType, Method>emptyMap(),
                new Object(),
                properties,
                null,
                new PrefixMessageCryptoCodec());
        SessionFixture session = addSession(resolver, "/ws/foo", "active");
        DefaultMessageSender sender = new DefaultMessageSender(Collections.singletonMap("/ws/foo", resolver));

        try {
            sender.sendMessage("/ws/foo", new TextMessage("hello"), "active");

            Object outbound = awaitOutbound(session);
            try {
                assertTrue(outbound instanceof TextWebSocketFrame);
                assertEquals("enc:hello", ((TextWebSocketFrame) outbound).text());
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        } finally {
            cleanup(resolver, session);
        }
    }

    @Test
    void sendMessageSkipsEncryptionWhenSessionPathIsExcludedByCryptoPolicy() throws Exception {
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.getCrypto().setEnable(true);
        properties.getCrypto().setExcludeUris("/ws/foo");
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/foo",
                Collections.<MessageType, Method>emptyMap(),
                new Object(),
                properties,
                null,
                new PrefixMessageCryptoCodec());
        SessionFixture session = addSession(resolver, "/ws/foo?client=legacy", "active");
        DefaultMessageSender sender = new DefaultMessageSender(Collections.singletonMap("/ws/foo", resolver));

        try {
            sender.sendMessage("/ws/foo", new TextMessage("hello"), "active");

            Object outbound = awaitOutbound(session);
            try {
                assertTrue(outbound instanceof TextWebSocketFrame);
                assertEquals("hello", ((TextWebSocketFrame) outbound).text());
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        } finally {
            cleanup(resolver, session);
        }
    }

    @Test
    void sendMessageSkipsEncryptionWhenSessionPathIsNotIncludedByCryptoPolicy() throws Exception {
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.getCrypto().setEnable(true);
        properties.getCrypto().setIncludeUris("/ws/secure");
        MessageMappingResolver resolver = new MessageMappingResolver(
                "/ws/foo",
                Collections.<MessageType, Method>emptyMap(),
                new Object(),
                properties,
                null,
                new PrefixMessageCryptoCodec());
        SessionFixture session = addSession(resolver, "/ws/foo", "active");
        DefaultMessageSender sender = new DefaultMessageSender(Collections.singletonMap("/ws/foo", resolver));

        try {
            sender.sendMessage("/ws/foo", new TextMessage("hello"), "active");

            Object outbound = awaitOutbound(session);
            try {
                assertTrue(outbound instanceof TextWebSocketFrame);
                assertEquals("hello", ((TextWebSocketFrame) outbound).text());
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        } finally {
            cleanup(resolver, session);
        }
    }

    @Test
    void sendMessageWriteFailureDispatchesErrorThenCloseLifecycle() throws Exception {
        LifecycleEndpoint endpoint = new LifecycleEndpoint();
        MessageMappingResolver resolver = lifecycleResolver("/ws/foo", endpoint);
        CountDownLatch failedWriteLatch = new CountDownLatch(1);
        SessionFixture session = addSession(
                resolver,
                "/ws/foo",
                "failing",
                new SignalingWriteHandler(failedWriteLatch, true));
        DefaultMessageSender sender = new DefaultMessageSender(Collections.singletonMap("/ws/foo", resolver));

        try {
            sender.sendMessage("/ws/foo", new TextMessage("hello"), "failing");

            assertTrue(awaitLatch(failedWriteLatch, session));
            assertTrue(awaitLatch(endpoint.errorLatch, session));
            assertTrue(awaitLatch(endpoint.closeLatch, session));
            assertEquals(1, endpoint.errorCount);
            assertEquals(1, endpoint.closeCount);
            assertEquals(1L, sender.getRuntimeStats().getWriteFailureCount());
            assertEquals("write failed", endpoint.lastErrorMessage);
            assertFalse(resolver.getSessionMap().containsKey("failing"));
            assertFalse(session.channel.isActive());
        } finally {
            cleanup(resolver, session);
        }
    }

    @Test
    void sendMessageWriteFailureRunsLifecycleThroughHandlerSubmitter() throws Exception {
        LifecycleEndpoint endpoint = new LifecycleEndpoint();
        MessageMappingResolver resolver = lifecycleResolver("/ws/foo", endpoint);
        RecordingHandlerSubmitter submitter = new RecordingHandlerSubmitter();
        resolver.setHandlerSubmitter(submitter);
        CountDownLatch failedWriteLatch = new CountDownLatch(1);
        SessionFixture session = addSession(
                resolver,
                "/ws/foo",
                "failing",
                new SignalingWriteHandler(failedWriteLatch, true));
        DefaultMessageSender sender = new DefaultMessageSender(Collections.singletonMap("/ws/foo", resolver));

        try {
            sender.sendMessage("/ws/foo", new TextMessage("hello"), "failing");

            assertTrue(awaitLatch(failedWriteLatch, session));
            assertEquals(0, endpoint.errorCount);
            assertEquals(0, endpoint.closeCount);
            assertEquals(1, submitter.size());
            session.channel.close();

            submitter.runNext();

            assertEquals(1, endpoint.errorCount);
            assertEquals(1, endpoint.closeCount);
            assertEquals("write failed", endpoint.lastErrorMessage);
            assertEquals(1, endpoint.lastErrorRequestRefCnt);
            assertFalse(resolver.getSessionMap().containsKey("failing"));
            assertFalse(session.channel.isActive());
        } finally {
            resolver.setHandlerSubmitter(null);
            cleanup(resolver, session);
        }
    }

    @Test
    void topicMessageRemovesSessionWhenWriteFails() throws Exception {
        MessageMappingResolver resolver = emptyResolver("/ws/foo");
        CountDownLatch activeWriteLatch = new CountDownLatch(1);
        CountDownLatch failedWriteLatch = new CountDownLatch(1);
        SessionFixture activeSession = addSession(
                resolver,
                "/ws/foo",
                "active",
                new SignalingWriteHandler(activeWriteLatch, false));
        SessionFixture failingSession = addSession(
                resolver,
                "/ws/foo",
                "failing",
                new SignalingWriteHandler(failedWriteLatch, true));
        DefaultMessageSender sender = new DefaultMessageSender(Collections.singletonMap("/ws/foo", resolver));

        try {
            sender.topicMessage("/ws/foo", new TextMessage("hello"));

            assertTrue(awaitLatch(activeWriteLatch, activeSession, failingSession));
            assertTrue(awaitLatch(failedWriteLatch, activeSession, failingSession));
            awaitSessionAbsent(resolver, "failing", activeSession, failingSession);

            Object outbound = activeSession.channel.readOutbound();
            try {
                assertTrue(outbound instanceof TextWebSocketFrame);
                assertEquals("hello", ((TextWebSocketFrame) outbound).text());
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        } finally {
            cleanup(resolver, activeSession, failingSession);
        }
    }

    @Test
    void topicMessageSkipsNonWritableSessions() throws Exception {
        MessageMappingResolver resolver = emptyResolver("/ws/foo");
        CountDownLatch writableWriteLatch = new CountDownLatch(1);
        SessionFixture writableSession = addSession(
                resolver,
                "/ws/foo",
                "writable",
                new SignalingWriteHandler(writableWriteLatch, false));
        SessionFixture nonWritableSession = addSession(resolver, "/ws/foo", "non-writable", false);
        DefaultMessageSender sender = new DefaultMessageSender(Collections.singletonMap("/ws/foo", resolver));

        try {
            sender.topicMessage("/ws/foo", new TextMessage("hello"));
            assertTrue(awaitLatch(writableWriteLatch, writableSession, nonWritableSession));

            Object writableOutbound = awaitOutbound(writableSession);
            try {
                assertTrue(writableOutbound instanceof TextWebSocketFrame);
                assertEquals("hello", ((TextWebSocketFrame) writableOutbound).text());
            } finally {
                ReferenceCountUtil.release(writableOutbound);
            }
            assertFalse(nonWritableSession.channel.readOutbound() instanceof TextWebSocketFrame);
            assertTrue(resolver.getSessionMap().containsKey("non-writable"));
            assertEquals(1L, sender.getRuntimeStats().getNonWritableSkipCount());
            assertEquals(0L, sender.getRuntimeStats().getNonWritableCloseCount());
        } finally {
            cleanup(resolver, writableSession, nonWritableSession);
        }
    }

    @Test
    void topicMessageClosesNonWritableSessionsWhenConfigured() throws Exception {
        LifecycleEndpoint endpoint = new LifecycleEndpoint();
        MessageMappingResolver resolver = lifecycleResolver("/ws/foo", endpoint);
        CountDownLatch writableWriteLatch = new CountDownLatch(1);
        SessionFixture writableSession = addSession(
                resolver,
                "/ws/foo",
                "writable",
                new SignalingWriteHandler(writableWriteLatch, false));
        SessionFixture nonWritableSession = addSession(resolver, "/ws/foo", "non-writable", false);
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setBroadcastNonWritableChannelPolicy(
                NettyServerStartupProperties.WebSocket.BroadcastNonWritableChannelPolicy.CLOSE);
        DefaultMessageSender sender = new DefaultMessageSender(Collections.singletonMap("/ws/foo", resolver), properties);

        try {
            sender.topicMessage("/ws/foo", new TextMessage("hello"));

            assertTrue(awaitLatch(writableWriteLatch, writableSession, nonWritableSession));
            assertTrue(awaitLatch(endpoint.errorLatch, writableSession, nonWritableSession));
            assertTrue(awaitLatch(endpoint.closeLatch, writableSession, nonWritableSession));
            assertEquals(1, endpoint.errorCount);
            assertEquals(1, endpoint.closeCount);
            assertEquals("Channel is not writable.", endpoint.lastErrorMessage);
            assertEquals(1L, sender.getRuntimeStats().getNonWritableCloseCount());
            assertFalse(resolver.getSessionMap().containsKey("non-writable"));
            assertFalse(nonWritableSession.channel.isActive());
        } finally {
            cleanup(resolver, writableSession, nonWritableSession);
        }
    }

    @Test
    void topicMessageDropsTasksWhenSenderExecutorIsSaturated() throws Exception {
        MessageMappingResolver resolver = emptyResolver("/ws/foo");
        CountDownLatch releaseWrites = new CountDownLatch(1);
        AtomicInteger writeCount = new AtomicInteger();
        SessionFixture session1 = addSession(
                resolver,
                "/ws/foo",
                "s1",
                new BlockingWriteHandler(null, releaseWrites, writeCount));
        SessionFixture session2 = addSession(
                resolver,
                "/ws/foo",
                "s2",
                new BlockingWriteHandler(null, releaseWrites, writeCount));
        SessionFixture session3 = addSession(
                resolver,
                "/ws/foo",
                "s3",
                new BlockingWriteHandler(null, releaseWrites, writeCount));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setCorePoolSize(1);
        properties.setMaxPoolSize(1);
        properties.setQueueCapacity(1);
        DefaultMessageSender sender = new DefaultMessageSender(Collections.singletonMap("/ws/foo", resolver), properties);

        ThreadPoolExecutor executor = extractExecutor(sender);
        try {
            sender.topicMessage("/ws/foo", new TextMessage("hello"));

            awaitQueueSize(executor, 1);
            assertEquals(1, executor.getMaximumPoolSize());
            assertEquals(1, executor.getQueue().size());
            assertEquals(1L, sender.getRuntimeStats().getRejectedBroadcastCount());
            assertEquals(1L, sender.getRuntimeStats().getDroppedBroadcastCount());
            assertEquals(0L, sender.getRuntimeStats().getCallerRunsFallbackCount());

            releaseWrites.countDown();
            awaitWriteCount(writeCount, 2, session1, session2, session3);
            assertEquals(2, writeCount.get());
        } finally {
            releaseWrites.countDown();
            executor.shutdownNow();
            cleanup(resolver, session1, session2, session3);
        }
    }

    @Test
    void topicMessageRunsInCallerWhenConfiguredAndSenderExecutorIsSaturated() throws Exception {
        MessageMappingResolver resolver = emptyResolver("/ws/foo");
        CountDownLatch startedWrites = new CountDownLatch(2);
        CountDownLatch releaseWrites = new CountDownLatch(1);
        AtomicInteger writeCount = new AtomicInteger();
        SessionFixture session1 = addSession(
                resolver,
                "/ws/foo",
                "s1",
                new BlockingWriteHandler(startedWrites, releaseWrites, writeCount));
        SessionFixture session2 = addSession(
                resolver,
                "/ws/foo",
                "s2",
                new BlockingWriteHandler(startedWrites, releaseWrites, writeCount));
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setCorePoolSize(1);
        properties.setMaxPoolSize(1);
        properties.setQueueCapacity(0);
        properties.setBroadcastRejectedExecutionPolicy(
                NettyServerStartupProperties.WebSocket.BroadcastRejectedExecutionPolicy.CALLER_RUNS);
        DefaultMessageSender sender = new DefaultMessageSender(Collections.singletonMap("/ws/foo", resolver), properties);

        ThreadPoolExecutor executor = extractExecutor(sender);
        Thread callerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                sender.topicMessage("/ws/foo", new TextMessage("hello"));
            }
        });

        try {
            callerThread.start();
            assertTrue(awaitLatch(startedWrites, session1, session2));
            assertTrue(callerThread.isAlive());
            assertEquals(1, executor.getMaximumPoolSize());
            assertEquals(0, executor.getQueue().size());
            assertEquals(1L, sender.getRuntimeStats().getRejectedBroadcastCount());
            assertEquals(1L, sender.getRuntimeStats().getCallerRunsFallbackCount());
            assertEquals(0L, sender.getRuntimeStats().getDroppedBroadcastCount());

            releaseWrites.countDown();
            callerThread.join(2000L);
            awaitWriteCount(writeCount, 2, session1, session2);
            assertEquals(2, writeCount.get());
            assertFalse(callerThread.isAlive());
        } finally {
            releaseWrites.countDown();
            executor.shutdownNow();
            cleanup(resolver, session1, session2);
        }
    }

    @Test
    void topicMessageDoesNotReviveClosedSessionsAfterResolverShutdown() throws Exception {
        LifecycleEndpoint endpoint = new LifecycleEndpoint();
        MessageMappingResolver resolver = lifecycleResolver("/ws/foo", endpoint);
        SessionFixture session1 = addSession(resolver, "/ws/foo", "s1");
        SessionFixture session2 = addSession(resolver, "/ws/foo", "s2");
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setCorePoolSize(1);
        properties.setMaxPoolSize(1);
        properties.setQueueCapacity(1);
        DefaultMessageSender sender = new DefaultMessageSender(Collections.singletonMap("/ws/foo", resolver), properties);
        ThreadPoolExecutor executor = extractExecutor(sender);
        CountDownLatch responseStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        AtomicInteger responseCount = new AtomicInteger();

        try {
            sender.topicMessage("/ws/foo", new BlockingTextMessage("hello", responseStarted, releaseResponse, responseCount));

            assertTrue(awaitLatch(responseStarted, session1, session2));
            awaitQueueSize(executor, 1);

            resolver.shutdown();
            drainChannels(session1, session2);

            releaseResponse.countDown();
            awaitExecutorIdle(executor, session1, session2);

            assertEquals(2, endpoint.closeCount);
            assertEquals(0, endpoint.errorCount);
            assertTrue(resolver.getSessionMap().isEmpty());
            assertEquals(1, responseCount.get());
            assertOnlyCloseFrames(session1);
            assertOnlyCloseFrames(session2);
            assertFalse(session1.channel.isActive());
            assertFalse(session2.channel.isActive());
        } finally {
            releaseResponse.countDown();
            sender.shutdown();
            cleanup(resolver, session1, session2);
        }
    }

    @Test
    void closeSessionDispatchesCloseLifecycleAndWritesCloseFrame() throws Exception {
        LifecycleEndpoint endpoint = new LifecycleEndpoint();
        MessageMappingResolver resolver = lifecycleResolver("/ws/foo", endpoint);
        SessionFixture session = addSession(resolver, "/ws/foo", "active");
        DefaultMessageSender sender = new DefaultMessageSender(Collections.singletonMap("/ws/foo", resolver));

        try {
            assertTrue(sender.closeSession("/ws/foo", "active", 4001, "kick"));

            assertEquals(1, endpoint.closeCount);
            assertFalse(resolver.getSessionMap().containsKey("active"));
            Object outbound = awaitOutbound(session);
            try {
                assertTrue(outbound instanceof CloseWebSocketFrame);
                CloseWebSocketFrame closeFrame = (CloseWebSocketFrame) outbound;
                assertEquals(4001, closeFrame.statusCode());
                assertEquals("kick", closeFrame.reasonText());
            } finally {
                ReferenceCountUtil.release(outbound);
            }
            drainChannels(session);
            assertFalse(session.channel.isActive());
        } finally {
            cleanup(resolver, session);
        }
    }

    @Test
    void closeSessionsClosesAllSessionsUnderUri() throws Exception {
        LifecycleEndpoint endpoint = new LifecycleEndpoint();
        MessageMappingResolver resolver = lifecycleResolver("/ws/foo", endpoint);
        SessionFixture session1 = addSession(resolver, "/ws/foo", "s1");
        SessionFixture session2 = addSession(resolver, "/ws/foo", "s2");
        DefaultMessageSender sender = new DefaultMessageSender(Collections.singletonMap("/ws/foo", resolver));

        try {
            assertEquals(2, sender.closeSessions("/ws/foo", 1001, "server restart"));
            assertFalse(sender.closeSession("/ws/foo", "missing"));
            assertThrows(MessageUriNotDefinedException.class,
                    () -> sender.closeSession("/ws/missing", "s1"));

            assertEquals(2, endpoint.closeCount);
            assertTrue(resolver.getSessionMap().isEmpty());
            assertOnlyCloseFrames(session1);
            assertOnlyCloseFrames(session2);
        } finally {
            cleanup(resolver, session1, session2);
        }
    }

    @Test
    void closeSessionReturnsFalseAndCleansUpInactiveSession() throws Exception {
        MessageMappingResolver resolver = emptyResolver("/ws/foo");
        SessionFixture session = addSession(resolver, "/ws/foo", "closed");
        DefaultMessageSender sender = new DefaultMessageSender(Collections.singletonMap("/ws/foo", resolver));
        session.channel.close();

        try {
            assertFalse(sender.closeSession("/ws/foo", "closed"));
            assertFalse(resolver.getSessionMap().containsKey("closed"));
        } finally {
            cleanup(resolver, session);
        }
    }

    @Test
    void usesConfiguredExecutorProperties() throws Exception {
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setCorePoolSize(1);
        properties.setMaxPoolSize(3);
        properties.setKeepAliveTime(7L);
        properties.setQueueCapacity(5);
        DefaultMessageSender sender = new DefaultMessageSender(Collections.<String, MessageMappingResolver>emptyMap(), properties);

        ThreadPoolExecutor executor = extractExecutor(sender);
        try {
            assertEquals(1, executor.getCorePoolSize());
            assertEquals(3, executor.getMaximumPoolSize());
            assertEquals(7L, executor.getKeepAliveTime(TimeUnit.SECONDS));
            assertEquals(5, executor.getQueue().remainingCapacity());
            assertEquals(NettyServerStartupProperties.WebSocket.BroadcastNonWritableChannelPolicy.SKIP,
                    properties.getBroadcastNonWritableChannelPolicy());
            assertEquals(NettyServerStartupProperties.WebSocket.BroadcastRejectedExecutionPolicy.DROP,
                    properties.getBroadcastRejectedExecutionPolicy());
            assertEquals(5, sender.getRuntimeStats().getExecutor().getQueueRemainingCapacity());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void usesSynchronousQueueWhenQueueCapacityIsZero() throws Exception {
        NettyServerStartupProperties.WebSocket properties = new NettyServerStartupProperties.WebSocket();
        properties.setQueueCapacity(0);
        DefaultMessageSender sender = new DefaultMessageSender(Collections.<String, MessageMappingResolver>emptyMap(), properties);

        ThreadPoolExecutor executor = extractExecutor(sender);
        try {
            assertEquals(SynchronousQueue.class, executor.getQueue().getClass());
            assertEquals(0, executor.getQueue().remainingCapacity());
        } finally {
            executor.shutdownNow();
        }
    }

    private static MessageMappingResolver emptyResolver(String uri) {
        return new MessageMappingResolver(uri, Collections.<MessageType, java.lang.reflect.Method>emptyMap(), new Object());
    }

    private static MessageMappingResolver lifecycleResolver(String uri, LifecycleEndpoint endpoint) throws Exception {
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CLOSE, method(endpoint, "onClose", CloseWebSocketFrame.class, MessageSession.class));
        methods.put(MessageType.ON_ERROR, method(endpoint, "onError", HttpRequest.class, MessageSession.class, Exception.class));
        return new MessageMappingResolver(uri, methods, endpoint);
    }

    private static SessionFixture addSession(MessageMappingResolver resolver, String uri, String sessionId) {
        return addSession(resolver, uri, sessionId, new ChannelHandler[0]);
    }

    private static SessionFixture addSession(MessageMappingResolver resolver,
                                             String uri,
                                             String sessionId,
                                             boolean writable,
                                             ChannelHandler... extraHandlers) {
        ContextHolder holder = new ContextHolder();
        ArrayList<ChannelHandler> handlers = new ArrayList<>();
        handlers.addAll(java.util.Arrays.asList(extraHandlers));
        handlers.add(holder);
        EmbeddedChannel channel = writable
                ? new EmbeddedChannel(handlers.toArray(new ChannelHandler[0]))
                : new NonWritableEmbeddedChannel(handlers.toArray(new ChannelHandler[0]));
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, GET, uri);
        MessageSession session = new MessageSession(sessionId, holder.ctx, request);
        holder.ctx.channel().attr(ServiceHandler.REQUEST_IN_CHANNEL).set(session.getFirstRequest());
        holder.ctx.channel().attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).set(sessionId);
        request.release();
        resolver.getSessionMap().put(sessionId, session);
        return new SessionFixture(channel, sessionId);
    }

    private static SessionFixture addSession(MessageMappingResolver resolver,
                                             String uri,
                                             String sessionId,
                                             ChannelHandler... extraHandlers) {
        return addSession(resolver, uri, sessionId, true, extraHandlers);
    }

    private static void cleanup(MessageMappingResolver resolver, SessionFixture... sessions) {
        for (SessionFixture session : sessions) {
            resolver.removeSession(session.sessionId);
            session.channel.attr(ServiceHandler.REQUEST_IN_CHANNEL).set(null);
            session.channel.attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).set(null);
            session.channel.finishAndReleaseAll();
        }
    }

    private static Map<String, MessageMappingResolver> mapOf(String key1, MessageMappingResolver value1,
                                                             String key2, MessageMappingResolver value2) {
        Map<String, MessageMappingResolver> map = new HashMap<>();
        map.put(key1, value1);
        map.put(key2, value2);
        return map;
    }

    private static ThreadPoolExecutor extractExecutor(DefaultMessageSender sender) throws Exception {
        Field field = DefaultMessageSender.class.getDeclaredField("executorService");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(sender);
    }

    private static Method method(Object target, String methodName, Class<?>... parameterTypes) throws Exception {
        return target.getClass().getMethod(methodName, parameterTypes);
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

    private static final class RecordingHandlerSubmitter implements HandlerSubmitter {
        private final ArrayList<Runnable> tasks = new ArrayList<>();

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
        public TextWebSocketFrame decrypt(MessageSession session, io.netty.handler.codec.http.websocketx.WebSocketFrame encryptedFrame) {
            String text = ((TextWebSocketFrame) encryptedFrame).text();
            if (!text.startsWith("enc:")) {
                throw new IllegalArgumentException("Missing encrypted prefix.");
            }
            return new TextWebSocketFrame(text.substring("enc:".length()));
        }
    }

    public static final class LifecycleEndpoint {
        private final CountDownLatch errorLatch = new CountDownLatch(1);
        private final CountDownLatch closeLatch = new CountDownLatch(1);
        private int errorCount;
        private int closeCount;
        private String lastErrorMessage;
        private int lastErrorRequestRefCnt;

        public void onClose(CloseWebSocketFrame frame, MessageSession session) {
            closeCount++;
            closeLatch.countDown();
        }

        public void onError(HttpRequest request, MessageSession session, Exception exception) {
            errorCount++;
            lastErrorMessage = exception.getMessage();
            if (request instanceof FullHttpRequest) {
                lastErrorRequestRefCnt = ((FullHttpRequest) request).refCnt();
            }
            errorLatch.countDown();
        }
    }

    private static final class SignalingWriteHandler extends ChannelOutboundHandlerAdapter {
        private final CountDownLatch latch;
        private final boolean fail;

        private SignalingWriteHandler(CountDownLatch latch, boolean fail) {
            this.latch = latch;
            this.fail = fail;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (fail) {
                ReferenceCountUtil.release(msg);
                promise.setFailure(new IllegalStateException("write failed"));
                latch.countDown();
                return;
            }
            ctx.write(msg, promise);
            latch.countDown();
        }
    }

    private static final class BlockingWriteHandler extends ChannelOutboundHandlerAdapter {
        private final CountDownLatch startedLatch;
        private final CountDownLatch releaseLatch;
        private final AtomicInteger writeCount;

        private BlockingWriteHandler(CountDownLatch startedLatch,
                                     CountDownLatch releaseLatch,
                                     AtomicInteger writeCount) {
            this.startedLatch = startedLatch;
            this.releaseLatch = releaseLatch;
            this.writeCount = writeCount;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (startedLatch != null) {
                startedLatch.countDown();
            }
            await(releaseLatch);
            writeCount.incrementAndGet();
            ctx.write(msg, promise);
        }
    }

    private static final class BlockingTextMessage extends AbstractMessage<TextWebSocketFrame> {
        private final String text;
        private final CountDownLatch startedLatch;
        private final CountDownLatch releaseLatch;
        private final AtomicInteger responseCount;

        private BlockingTextMessage(String text,
                                    CountDownLatch startedLatch,
                                    CountDownLatch releaseLatch,
                                    AtomicInteger responseCount) {
            this.text = text;
            this.startedLatch = startedLatch;
            this.releaseLatch = releaseLatch;
            this.responseCount = responseCount;
        }

        @Override
        public TextWebSocketFrame responseMsg() {
            startedLatch.countDown();
            await(releaseLatch);
            responseCount.incrementAndGet();
            return new TextWebSocketFrame(text);
        }
    }

    private static final class NonWritableEmbeddedChannel extends EmbeddedChannel {
        private NonWritableEmbeddedChannel(ChannelHandler... handlers) {
            super(handlers);
        }

        @Override
        public boolean isWritable() {
            return false;
        }
    }

    private static boolean awaitLatch(CountDownLatch latch, SessionFixture... sessions) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            drainChannels(sessions);
            if (latch.await(10L, TimeUnit.MILLISECONDS)) {
                drainChannels(sessions);
                return true;
            }
        }
        drainChannels(sessions);
        return latch.getCount() == 0L;
    }

    private static void awaitWriteCount(AtomicInteger counter, int expected, SessionFixture... sessions) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            drainChannels(sessions);
            if (counter.get() >= expected) {
                return;
            }
            Thread.sleep(10L);
        }
        drainChannels(sessions);
        assertEquals(expected, counter.get());
    }

    private static void awaitSessionAbsent(MessageMappingResolver resolver,
                                           String sessionId,
                                           SessionFixture... sessions) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            drainChannels(sessions);
            if (!resolver.getSessionMap().containsKey(sessionId)) {
                return;
            }
            Thread.sleep(10L);
        }
        drainChannels(sessions);
        assertFalse(resolver.getSessionMap().containsKey(sessionId));
    }

    private static void awaitQueueSize(ThreadPoolExecutor executor, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (executor.getQueue().size() >= expected) {
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals(expected, executor.getQueue().size());
    }

    private static Object awaitOutbound(SessionFixture session) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            drainChannels(session);
            Object outbound = session.channel.readOutbound();
            if (outbound != null) {
                return outbound;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        drainChannels(session);
        return session.channel.readOutbound();
    }

    private static void awaitExecutorIdle(ThreadPoolExecutor executor, SessionFixture... sessions) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            drainChannels(sessions);
            if (executor.getActiveCount() == 0 && executor.getQueue().isEmpty()) {
                return;
            }
            Thread.sleep(10L);
        }
        drainChannels(sessions);
        assertEquals(0, executor.getActiveCount());
        assertTrue(executor.getQueue().isEmpty());
    }

    private static void assertOnlyCloseFrames(SessionFixture session) {
        int closeFrameCount = 0;
        Object outbound;
        while ((outbound = session.channel.readOutbound()) != null) {
            try {
                assertFalse(outbound instanceof TextWebSocketFrame);
                if (outbound instanceof CloseWebSocketFrame) {
                    closeFrameCount++;
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
        assertEquals(1, closeFrameCount);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void drainChannels(SessionFixture... sessions) {
        for (SessionFixture session : sessions) {
            session.channel.runPendingTasks();
            session.channel.runScheduledPendingTasks();
        }
    }
}
