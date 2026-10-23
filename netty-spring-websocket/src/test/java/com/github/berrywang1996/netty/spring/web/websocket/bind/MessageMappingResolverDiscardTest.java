package com.github.berrywang1996.netty.spring.web.websocket.bind;

import com.github.berrywang1996.netty.spring.web.context.WebMappingSupporter;
import com.github.berrywang1996.netty.spring.web.handler.ServiceHandler;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.context.ClusterSessionHook;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MessageMappingResolverDiscardTest {
    enum Lifecycle { CONNECTED, INACTIVE, TRANSPORT_ERROR, HEARTBEAT_TIMEOUT, HANDSHAKE_FAILURE }

    @ParameterizedTest(name = "submission failure, rejected={0}")
    @ValueSource(booleans = {true, false})
    void failedLifecycleSubmissionReleasesRequestLeaseAndSession(boolean rejected) throws Exception {
        Endpoint endpoint = new Endpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, Endpoint.class.getMethod("connected", HttpRequest.class, MessageSession.class));
        Semaphore connectionPermits = new Semaphore(1);
        MessageMappingResolver resolver = new MessageMappingResolver("/discard", methods, endpoint,
                new NettyServerStartupProperties.WebSocket(), connectionPermits);
        resolver.setHandlerSubmitter(task -> {
            if (rejected) throw new RejectedExecutionException("stopped");
            throw new IllegalStateException("executor failed before accepting work");
        });
        ContextHolder holder = new ContextHolder();
        EmbeddedChannel channel = new EmbeddedChannel(holder, new HttpServerCodec());
        FullHttpRequest request = request();
        try {
            resolver.resolve(holder.context, request);
            channel.runPendingTasks();
            assertEquals(1, request.refCnt(), "only the inbound caller still owns a reference");
            assertEquals(1, connectionPermits.availablePermits());
            assertTrue(resolver.getSessionMap().isEmpty());
            assertFalse(channel.isOpen());
            assertEquals(0, endpoint.connected);
            request.release();
            assertEquals(0, request.refCnt());
        } finally {
            channel.finishAndReleaseAll();
            if (request.refCnt() > 0) request.release(request.refCnt());
        }
    }

    @Test
    void discardedCloseKeepsRequestAliveUntilRunningConnectedCallbackFinishes() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        Semaphore handlerPermits = new Semaphore(2);
        WebMappingSupporter supporter = supporter(executor, handlerPermits);
        BlockingEndpoint endpoint = new BlockingEndpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, BlockingEndpoint.class.getMethod("connected", HttpRequest.class, MessageSession.class));
        Semaphore connectionPermits = new Semaphore(1);
        MessageMappingResolver resolver = new MessageMappingResolver("/discard", methods, endpoint,
                new NettyServerStartupProperties.WebSocket(), connectionPermits);
        resolver.setHandlerSubmitter(supporter);
        ContextHolder holder = new ContextHolder();
        EmbeddedChannel channel = new EmbeddedChannel(holder, new HttpServerCodec());
        FullHttpRequest request = request();
        request.content().writeByte(42);
        Thread shutdown = new Thread(() -> {
            Thread.currentThread().interrupt();
            supporter.shutdown();
        }, "test-lifecycle-discard-with-active-callback");
        try {
            resolver.resolve(holder.context, request);
            channel.runPendingTasks();
            assertTrue(endpoint.started.await(2, TimeUnit.SECONDS));
            request.release();
            MessageSession session = resolver.getSessionMap().values().iterator().next();
            resolver.closeSessionOnTransportError(session, new IllegalStateException("connection lost"));
            assertEquals(1, executor.getQueue().size());

            shutdown.start();
            shutdown.join(3000);
            assertFalse(shutdown.isAlive());
            channel.runPendingTasks();
            assertEquals(1, connectionPermits.availablePermits());
            assertEquals(1, handlerPermits.availablePermits(), "the active callback still owns its handler permit");
            assertEquals(1, request.refCnt(), "the active callback must retain its own request lease after session cleanup");
            assertFalse(channel.isOpen());

            endpoint.finish.countDown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            assertNull(endpoint.failure.get(), "the active callback must still read request content after another lifecycle is discarded");
            assertEquals(42, endpoint.lastByte);
            assertEquals(0, request.refCnt());
            assertEquals(2, handlerPermits.availablePermits());
        } finally {
            endpoint.finish.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
            shutdown.join(2000);
            channel.finishAndReleaseAll();
            if (request.refCnt() > 0) request.release(request.refCnt());
        }
    }

    @ParameterizedTest(name = "discard accepted {0} lifecycle")
    @EnumSource(Lifecycle.class)
    void forcedHandlerShutdownReleasesUnstartedLifecycleWithoutBusinessCallbacks(Lifecycle lifecycle) throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        Semaphore handlerPermits = new Semaphore(2);
        // The resolver owns session cleanup; this real supporter supplies only the
        // shared queue so forced-discard ownership is isolated from inline shutdown callbacks.
        WebMappingSupporter supporter = supporter(executor, handlerPermits);
        Endpoint endpoint = new Endpoint();
        Map<MessageType, Method> methods = new EnumMap<>(MessageType.class);
        methods.put(MessageType.ON_CONNECTED, Endpoint.class.getMethod("connected", HttpRequest.class, MessageSession.class));
        methods.put(MessageType.ON_CLOSE, Endpoint.class.getMethod("closed", CloseWebSocketFrame.class, MessageSession.class));
        methods.put(MessageType.ON_ERROR, Endpoint.class.getMethod("error", HttpRequest.class, MessageSession.class, Exception.class));
        Semaphore connectionPermits = new Semaphore(1);
        MessageMappingResolver resolver = new MessageMappingResolver("/discard", methods, endpoint,
                new NettyServerStartupProperties.WebSocket(), connectionPermits);
        AtomicInteger removed = new AtomicInteger();
        AtomicInteger registered = new AtomicInteger();
        AtomicBoolean removalSawLiveRequest = new AtomicBoolean(true);
        resolver.setClusterSessionHook(new ClusterSessionHook() {
            @Override public void onSessionRegistered(MessageSession session, String uri) { registered.incrementAndGet(); }
            @Override public void onSessionRemoved(MessageSession session, String uri) {
                removed.incrementAndGet();
                if (session.getFirstRequest().refCnt() <= 0) removalSawLiveRequest.set(false);
            }
        });
        ContextHolder holder = new ContextHolder();
        EmbeddedChannel channel = lifecycle == Lifecycle.HANDSHAKE_FAILURE
                ? new EmbeddedChannel(holder, new HttpServerCodec(), new FailingHandshakeWrite())
                : new EmbeddedChannel(holder, new HttpServerCodec());
        FullHttpRequest request = request();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch finishWorker = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread shutdown = new Thread(() -> {
            Thread.currentThread().interrupt();
            supporter.shutdown();
            interruptRestored.set(Thread.currentThread().isInterrupted());
        }, "test-websocket-lifecycle-discard");
        try {
            supporter.submitHandle(() -> {
                workerStarted.countDown();
                try { finishWorker.await(); }
                catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            });
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
            if (lifecycle == Lifecycle.CONNECTED || lifecycle == Lifecycle.HANDSHAKE_FAILURE) {
                resolver.setHandlerSubmitter(supporter);
            }
            resolver.resolve(holder.context, request);
            channel.runPendingTasks();
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) ReferenceCountUtil.release(outbound);
            MessageSession session = resolver.getSessionMap().values().stream().findFirst().orElse(null);
            if (lifecycle != Lifecycle.CONNECTED && lifecycle != Lifecycle.HANDSHAKE_FAILURE) {
                assertNotNull(session);
                resolver.setHandlerSubmitter(supporter);
                switch (lifecycle) {
                    case INACTIVE -> {
                        channel.close();
                        resolver.onChannelInactive(holder.context);
                    }
                    case TRANSPORT_ERROR -> resolver.closeSessionOnTransportError(session, new IllegalStateException("transport stopped"));
                    case HEARTBEAT_TIMEOUT -> {
                        Method timeout = MessageMappingResolver.class.getDeclaredMethod("closeSessionOnHeartbeatTimeout", MessageSession.class, long.class);
                        timeout.setAccessible(true);
                        timeout.invoke(resolver, session, 2000L);
                    }
                    default -> throw new AssertionError(lifecycle);
                }
            }
            // The inbound caller releases its reference. The session and its queued
            // lifecycle each own one reference until their respective cleanup.
            request.release();
            assertEquals(2, request.refCnt(), "session reference plus queued lifecycle lease");
            assertEquals(0, connectionPermits.availablePermits());
            assertEquals(1, executor.getQueue().size());
            int connectedBeforeDiscard = endpoint.connected;
            assertEquals(0, endpoint.closed);
            assertEquals(0, endpoint.errors);

            shutdown.start();
            shutdown.join(3000);
            assertFalse(shutdown.isAlive(), "discard must not dispatch user lifecycle handlers");
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            channel.runPendingTasks();
            assertAll(
                    () -> assertEquals(0, request.refCnt(), "session-owned firstRequest must be released"),
                    () -> assertEquals(1, connectionPermits.availablePermits(), "connection permit must be returned"),
                    () -> assertEquals(2, handlerPermits.availablePermits(), "handler permit must be returned"),
                    () -> assertTrue(resolver.getSessionMap().isEmpty()),
                    () -> assertNull(channel.attr(MessageMappingResolver.SESSION_ID_IN_CHANNEL).get()),
                    () -> assertNull(channel.attr(ServiceHandler.REQUEST_IN_CHANNEL).get()),
                    () -> assertFalse(channel.isOpen(), "a discarded lifecycle cannot leave a client waiting"),
                    () -> assertEquals(connectedBeforeDiscard, endpoint.connected),
                    () -> assertEquals(0, endpoint.closed, "no user @OnClose callback on the discard thread"),
                    () -> assertEquals(0, endpoint.errors, "no user @OnError callback on the discard thread"),
                    () -> assertTrue(interruptRestored.get()),
                    () -> assertTrue(removalSawLiveRequest.get()),
                    () -> assertEquals(lifecycle == Lifecycle.HANDSHAKE_FAILURE ? 0 : 1, registered.get()),
                    // Inactive handling already detaches its mapping without the cluster hook.
                    () -> assertEquals(lifecycle == Lifecycle.HANDSHAKE_FAILURE || lifecycle == Lifecycle.INACTIVE ? 0 : 1, removed.get()));
            resolver.onChannelInactive(holder.context);
            resolver.shutdown();
            assertEquals(1, connectionPermits.availablePermits(), "cleanup is idempotent");
        } finally {
            finishWorker.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
            shutdown.join(2000);
            resolver.setHandlerSubmitter(null);
            // Clean up the intentionally failing pre-fix reproduction as well.
            for (MessageSession remaining : resolver.getSessionMap().values()) remaining.release();
            resolver.getSessionMap().clear();
            channel.finishAndReleaseAll();
            if (request.refCnt() > 0) request.release(request.refCnt());
        }
    }

    private static WebMappingSupporter supporter(ThreadPoolExecutor executor, Semaphore permits) throws Exception {
        Constructor<WebMappingSupporter> constructor = WebMappingSupporter.class.getDeclaredConstructor(
                NettyServerStartupProperties.class, ApplicationContext.class, Map.class,
                ThreadPoolExecutor.class, Semaphore.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new NettyServerStartupProperties(), null, Map.of(), executor, permits);
    }

    private static FullHttpRequest request() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/discard");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        request.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        request.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        return request;
    }
    private static final class ContextHolder extends ChannelInboundHandlerAdapter {
        private ChannelHandlerContext context;
        @Override public void handlerAdded(ChannelHandlerContext context) { this.context = context; }
    }
    private static final class FailingHandshakeWrite extends ChannelOutboundHandlerAdapter {
        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            ReferenceCountUtil.release(message);
            promise.setFailure(new IllegalStateException("handshake write failed"));
        }
    }
    public static final class Endpoint {
        private int connected;
        private int closed;
        private int errors;
        public void connected(HttpRequest request, MessageSession session) { connected++; }
        public void closed(CloseWebSocketFrame frame, MessageSession session) { closed++; }
        public void error(HttpRequest request, MessageSession session, Exception error) { errors++; }
    }
    public static final class BlockingEndpoint {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch finish = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private int lastByte = -1;

        public void connected(HttpRequest request, MessageSession session) {
            started.countDown();
            boolean interrupted = false;
            try {
                while (true) {
                    try { finish.await(); break; }
                    catch (InterruptedException expected) { interrupted = true; }
                }
                lastByte = session.getFirstRequest().content().getUnsignedByte(0);
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
