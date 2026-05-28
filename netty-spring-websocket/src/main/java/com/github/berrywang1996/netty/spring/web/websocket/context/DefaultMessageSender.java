package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.context.ExecutorRuntimeInfo;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.util.DaemonThreadFactory;
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.consts.CloseReason;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of the {@link MessageSender} interface.
 *
 * <p>This class provides the core logic for sending targeted messages, broadcasting
 * to all sessions on a URI, querying session state, and closing sessions. It manages
 * its own {@link ThreadPoolExecutor} for asynchronous broadcast delivery and tracks
 * runtime statistics (rejected broadcasts, write failures, non-writable channels, etc.)
 * via atomic counters.
 *
 * <h3>Threading model</h3>
 * <ul>
 *   <li>Targeted sends ({@link #sendMessage}) run synchronously on the caller thread.</li>
 *   <li>Broadcasts ({@link #topicMessage}) submit per-session tasks to the internal thread pool.
 *       When the pool is saturated, the configurable rejection policy determines whether
 *       the task falls back to the caller thread or is dropped.</li>
 *   <li>Non-writable channels during broadcast are either skipped or force-closed,
 *       depending on the configured {@code BroadcastNonWritableChannelPolicy}.</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * The sender must be shut down via {@link #shutdown()} when no longer needed.
 * {@link com.github.berrywang1996.netty.spring.web.websocket.support.MessageSenderSupport}
 * handles this automatically via its server stop listener.
 *
 * @author berrywang1996
 * @version V1.0.0
 * @since V1.0.0
 * @see MessageSender
 */
@Slf4j
public class DefaultMessageSender implements MessageSender {

    private static final int DEFAULT_CORE_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());

    private static final int DEFAULT_MAX_POOL_SIZE =
            Math.max(DEFAULT_CORE_POOL_SIZE, Runtime.getRuntime().availableProcessors() * 2);

    private static final long DEFAULT_KEEP_ALIVE_SECONDS = 60L;

    private static final int DEFAULT_QUEUE_CAPACITY = 1024;

    private final NettyServerStartupProperties.WebSocket webSocketProperties;

    /** Thread pool used for asynchronous broadcast delivery. */
    private final ThreadPoolExecutor executorService;

    /** URI-to-resolver mapping; each resolver owns a session map for one WebSocket URI. */
    private final Map<String, MessageMappingResolver> resolverMap;

    // ---- Runtime statistics counters (all atomic for lock-free thread safety) ----
    private final AtomicLong rejectedBroadcastCount = new AtomicLong();
    private final AtomicLong callerRunsFallbackCount = new AtomicLong();
    private final AtomicLong droppedBroadcastCount = new AtomicLong();
    private final AtomicLong nonWritableSkipCount = new AtomicLong();
    private final AtomicLong nonWritableCloseCount = new AtomicLong();
    private final AtomicLong writeFailureCount = new AtomicLong();

    /**
     * Creates a sender with default WebSocket properties.
     *
     * @param resolverMap URI-to-resolver mapping
     */
    public DefaultMessageSender(Map<String, MessageMappingResolver> resolverMap) {
        this(resolverMap, null);
    }

    /**
     * Creates a sender with explicit WebSocket properties for thread pool and policy tuning.
     *
     * @param resolverMap        URI-to-resolver mapping
     * @param webSocketProperties configuration properties (may be {@code null} for defaults)
     */
    public DefaultMessageSender(Map<String, MessageMappingResolver> resolverMap,
                                NettyServerStartupProperties.WebSocket webSocketProperties) {
        this.resolverMap = resolverMap;
        this.webSocketProperties = webSocketProperties;
        this.executorService = initHandlerExecutorThreadPool(webSocketProperties);
    }

    /**
     * Initializes the broadcast thread pool with configurable sizes and an AbortPolicy
     * so that rejected tasks can be caught and handled by the broadcast rejection policy.
     */
    private ThreadPoolExecutor initHandlerExecutorThreadPool(NettyServerStartupProperties.WebSocket webSocketProperties) {
        int corePoolSize = resolveCorePoolSize(webSocketProperties);
        int maxPoolSize = Math.max(corePoolSize, resolveMaxPoolSize(webSocketProperties));
        long keepAliveTime = resolveKeepAliveTime(webSocketProperties);
        int queueCapacity = resolveQueueCapacity(webSocketProperties);
        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveTime,
                TimeUnit.SECONDS,
                initExecutorQueue(queueCapacity),
                new DaemonThreadFactory("messageSender"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** {@inheritDoc} */
    @Override
    public int getSessionNums() {
        int total = 0;
        for (MessageMappingResolver resolver : resolverMap.values()) {
            total += resolver.getSessionMap().size();
        }
        return total;
    }

    /** {@inheritDoc} */
    @Override
    public int getSessionNums(String uri) {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null) {
            return 0;
        }
        return resolver.getSessionMap().size();
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getSessionIds(String uri) {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null) {
            return Collections.emptySet();
        }
        Set<String> sessionIds = new HashSet<>();
        for (String sessionId : resolver.getSessionMap().keySet()) {
            if (isSessionActive(resolver, sessionId)) {
                sessionIds.add(sessionId);
            }
        }
        return Collections.unmodifiableSet(sessionIds);
    }

    /** {@inheritDoc} */
    @Override
    public MessageSession getSession(String uri, String sessionId) {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null || !isSessionActive(resolver, sessionId)) {
            return null;
        }
        return resolver.getSessionMap().get(sessionId);
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, MessageSession> getSessions(String uri) {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null) {
            return Collections.emptyMap();
        }
        Map<String, MessageSession> sessions = new HashMap<>();
        for (Map.Entry<String, MessageSession> entry : resolver.getSessionMap().entrySet()) {
            if (isSessionActive(resolver, entry.getKey())) {
                sessions.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(sessions);
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getRegisteredUri() {
        return resolverMap.keySet();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isSessionAlive(String uri, String... sessionIds) {
        if (sessionIds == null || sessionIds.length == 0) {
            return false;
        }
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null) {
            return false;
        }
        for (String sessionId : sessionIds) {
            if (!isSessionActive(resolver, sessionId)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sends the message synchronously on the caller thread to each specified session.
     * Sessions that are closed or whose channel is no longer active are collected, and a
     * {@link MessageSessionClosedException} is thrown after all remaining sessions have
     * been sent to.
     */
    @Override
    public void sendMessage(String uri, AbstractMessage message, String... sessionIds)
            throws MessageUriNotDefinedException, MessageSessionClosedException {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null) {
            throw new MessageUriNotDefinedException(uri, resolverMap.keySet());
        }
        Map<String, MessageSession> sessionMap = resolver.getSessionMap();
        // Collect closed session IDs lazily to avoid allocation on the happy path
        List<String> closedSessionIds = null;
        for (String sessionId : sessionIds) {
            MessageSession session = sessionMap.get(sessionId);
            if (session == null || !session.getChannelHandlerContext().channel().isActive()) {
                // Clean up stale session from the map if the channel is dead
                if (session != null) {
                    resolver.removeSession(sessionId);
                }
                if (closedSessionIds == null) {
                    closedSessionIds = new ArrayList<>();
                }
                closedSessionIds.add(sessionId);
            } else {
                sendMessageFrame(resolver, session, message);
            }
        }
        // Throw after attempting all sends so partial delivery still occurs
        if (closedSessionIds != null && closedSessionIds.size() > 0) {
            throw new MessageSessionClosedException(uri, closedSessionIds);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Broadcasts the message to all active sessions on the given URI.
     * Each session's send is submitted as an asynchronous task to the internal thread
     * pool. Sessions that are closing, inactive, or whose channel is not writable are
     * filtered out according to the configured policies.
     */
    @Override
    public void topicMessage(String uri, final AbstractMessage message) throws MessageUriNotDefinedException {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null) {
            throw new MessageUriNotDefinedException(uri, resolverMap.keySet());
        }
        Map<String, MessageSession> sessionMap = resolver.getSessionMap();
        for (final MessageSession session : sessionMap.values()) {
            // Skip sessions that are closing, inactive, or not writable
            if (!prepareSessionForBroadcast(resolver, session)) {
                continue;
            }
            submitBroadcastTask(resolver, session, message);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean closeSession(String uri, String sessionId, int statusCode, String reasonText)
            throws MessageUriNotDefinedException {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null) {
            throw new MessageUriNotDefinedException(uri, resolverMap.keySet());
        }
        return resolver.closeSession(sessionId, statusCode, reasonText);
    }

    /** {@inheritDoc} */
    @Override
    public int closeSessions(String uri, int statusCode, String reasonText) throws MessageUriNotDefinedException {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null) {
            throw new MessageUriNotDefinedException(uri, resolverMap.keySet());
        }
        int closed = 0;
        for (String sessionId : new ArrayList<>(resolver.getSessionMap().keySet())) {
            if (resolver.closeSession(sessionId, statusCode, reasonText)) {
                closed++;
            }
        }
        return closed;
    }

    /** {@inheritDoc} */
    @Override
    public MessageSenderRuntimeStats getRuntimeStats() {
        return new MessageSenderRuntimeStats(
                ExecutorRuntimeInfo.from(this.executorService),
                this.rejectedBroadcastCount.get(),
                this.callerRunsFallbackCount.get(),
                this.droppedBroadcastCount.get(),
                this.nonWritableSkipCount.get(),
                this.nonWritableCloseCount.get(),
                this.writeFailureCount.get());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Initiates a graceful shutdown of the broadcast thread pool, waiting up to
     * 5 seconds for in-flight tasks before forcing termination. Re-interrupts the
     * calling thread if the wait is interrupted.
     */
    @Override
    public void shutdown() {
        if (executorService.isShutdown()) {
            return;
        }
        executorService.shutdown();
        try {
            // Wait briefly for in-flight broadcast tasks to complete
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            // Preserve interrupt status for upstream callers
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Submits a broadcast send task to the thread pool for one session.
     * If the pool rejects the task (saturated), delegates to the rejection policy handler.
     */
    private void submitBroadcastTask(final MessageMappingResolver resolver,
                                     final MessageSession session,
                                     final AbstractMessage message) {
        try {
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        sendMessageFrame(resolver, session, message, true);
                    } catch (RuntimeException e) {
                        log.warn("Skip websocket broadcast because send task failed. sessionId={}", session.getSessionId(), e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            handleRejectedBroadcastTask(resolver, session, message, e);
        }
    }

    private void sendMessageFrame(final MessageMappingResolver resolver,
                                  final MessageSession session,
                                  final AbstractMessage message) {
        sendMessageFrame(resolver, session, message, false);
    }

    /**
     * Core frame-level send logic: converts the message to a frame, optionally encrypts it,
     * writes it to the channel, and handles write failures.
     *
     * @param resolver                 the resolver owning the session
     * @param session                  the target session
     * @param message                  the application-level message
     * @param dropIfChannelNotWritable if {@code true}, skip the send when the channel is not writable
     *                                 (used during broadcast to avoid backpressure buildup)
     */
    private void sendMessageFrame(final MessageMappingResolver resolver,
                                  final MessageSession session,
                                  final AbstractMessage message,
                                  boolean dropIfChannelNotWritable) {
        if (!isSessionActive(resolver, session.getSessionId())) {
            return;
        }
        if (dropIfChannelNotWritable && !prepareSessionForBroadcast(resolver, session)) {
            return;
        }
        // Convert application message to a Netty frame and encrypt if needed
        Object outboundMessage = null;
        try {
            outboundMessage = message.responseMsg();
            if (outboundMessage instanceof WebSocketFrame) {
                outboundMessage = resolver.encryptOutboundFrame(session, (WebSocketFrame) outboundMessage);
            }
        } catch (Exception e) {
            // Release the partially-built frame to prevent ByteBuf leaks
            ReferenceCountUtil.release(outboundMessage);
            handleWriteFailure(resolver, session, e);
            return;
        }
        // Write and flush the frame to the channel
        ChannelFuture future;
        try {
            future = session.getChannelHandlerContext().writeAndFlush(outboundMessage);
        } catch (RuntimeException e) {
            ReferenceCountUtil.release(outboundMessage);
            handleWriteFailure(resolver, session, e);
            return;
        }
        // Track success/failure asynchronously via the channel future listener
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (future.isSuccess()) {
                    resolver.getEventRecorder().recordMessageSent();
                } else {
                    handleWriteFailure(resolver, session, future.cause());
                }
            }
        });
    }

    /** Increments the write failure counter and closes the session via the transport error lifecycle. */
    private void handleWriteFailure(MessageMappingResolver resolver, MessageSession session, Throwable cause) {
        this.writeFailureCount.incrementAndGet();
        log.warn("Send websocket message failed, close session {} through websocket lifecycle. stats={}",
                session.getSessionId(),
                getRuntimeStats(),
                cause);
        resolver.closeSessionOnTransportError(session, cause, CloseReason.WRITE_FAILURE);
    }

    /** Checks whether a session exists, is not closing, and its channel is still active. */
    private boolean isSessionActive(MessageMappingResolver resolver, String sessionId) {
        MessageSession session = resolver.getSessionMap().get(sessionId);
        if (session == null) {
            return false;
        }
        if (session.isClosing()) {
            return false;
        }
        if (!session.getChannelHandlerContext().channel().isActive()) {
            resolver.removeSession(sessionId);
            return false;
        }
        return true;
    }

    /** Pre-flight check for broadcast: verifies the session is active and the channel is writable. */
    private boolean prepareSessionForBroadcast(MessageMappingResolver resolver, MessageSession session) {
        if (!isSessionActive(resolver, session.getSessionId())) {
            return false;
        }
        if (isSessionWritable(session)) {
            return true;
        }
        handleNonWritableBroadcastSession(resolver, session);
        return false;
    }

    /**
     * Handles a non-writable channel during broadcast according to the configured policy:
     * either CLOSE (force-close the session) or SKIP (drop the message for this session).
     */
    private void handleNonWritableBroadcastSession(MessageMappingResolver resolver, MessageSession session) {
        if (resolveBroadcastNonWritableChannelPolicy()
                == NettyServerStartupProperties.WebSocket.BroadcastNonWritableChannelPolicy.CLOSE) {
            this.nonWritableCloseCount.incrementAndGet();
            log.warn("Close websocket session because channel is not writable during broadcast. sessionId={}, stats={}",
                    session.getSessionId(),
                    getRuntimeStats());
            resolver.closeSessionOnTransportError(session,
                    new IllegalStateException("Channel is not writable."), CloseReason.CHANNEL_NOT_WRITABLE);
            return;
        }
        this.nonWritableSkipCount.incrementAndGet();
        log.warn("Skip websocket broadcast because channel is not writable. sessionId={}, stats={}",
                session.getSessionId(),
                getRuntimeStats());
    }

    /**
     * Handles a rejected broadcast task when the thread pool is saturated.
     * Depending on the configured policy: CALLER_RUNS sends on the caller thread,
     * DROP silently discards the message for this session.
     */
    private void handleRejectedBroadcastTask(MessageMappingResolver resolver,
                                             MessageSession session,
                                             AbstractMessage message,
                                             RejectedExecutionException exception) {
        this.rejectedBroadcastCount.incrementAndGet();
        if (resolveBroadcastRejectedExecutionPolicy()
                == NettyServerStartupProperties.WebSocket.BroadcastRejectedExecutionPolicy.CALLER_RUNS) {
            this.callerRunsFallbackCount.incrementAndGet();
            log.warn("Run websocket broadcast in caller thread because sender executor is saturated. sessionId={}, stats={}, reason={}",
                    session.getSessionId(),
                    getRuntimeStats(),
                    exception.getMessage());
            sendMessageFrame(resolver, session, message, true);
            return;
        }
        this.droppedBroadcastCount.incrementAndGet();
        log.warn("Drop websocket broadcast because sender executor is saturated. sessionId={}, stats={}, reason={}",
                session.getSessionId(),
                getRuntimeStats(),
                exception.getMessage());
    }

    private boolean isSessionWritable(MessageSession session) {
        return session != null && session.getChannelHandlerContext().channel().isWritable();
    }

    private NettyServerStartupProperties.WebSocket.BroadcastNonWritableChannelPolicy resolveBroadcastNonWritableChannelPolicy() {
        if (webSocketProperties == null || webSocketProperties.getBroadcastNonWritableChannelPolicy() == null) {
            return NettyServerStartupProperties.WebSocket.BroadcastNonWritableChannelPolicy.SKIP;
        }
        return webSocketProperties.getBroadcastNonWritableChannelPolicy();
    }

    private NettyServerStartupProperties.WebSocket.BroadcastRejectedExecutionPolicy resolveBroadcastRejectedExecutionPolicy() {
        if (webSocketProperties == null || webSocketProperties.getBroadcastRejectedExecutionPolicy() == null) {
            return NettyServerStartupProperties.WebSocket.BroadcastRejectedExecutionPolicy.DROP;
        }
        return webSocketProperties.getBroadcastRejectedExecutionPolicy();
    }

    private int resolveCorePoolSize(NettyServerStartupProperties.WebSocket webSocketProperties) {
        if (webSocketProperties == null || webSocketProperties.getCorePoolSize() <= 0) {
            return DEFAULT_CORE_POOL_SIZE;
        }
        return webSocketProperties.getCorePoolSize();
    }

    private int resolveMaxPoolSize(NettyServerStartupProperties.WebSocket webSocketProperties) {
        if (webSocketProperties == null || webSocketProperties.getMaxPoolSize() <= 0) {
            return DEFAULT_MAX_POOL_SIZE;
        }
        return webSocketProperties.getMaxPoolSize();
    }

    private long resolveKeepAliveTime(NettyServerStartupProperties.WebSocket webSocketProperties) {
        if (webSocketProperties == null || webSocketProperties.getKeepAliveTime() <= 0L) {
            return DEFAULT_KEEP_ALIVE_SECONDS;
        }
        return webSocketProperties.getKeepAliveTime();
    }

    private int resolveQueueCapacity(NettyServerStartupProperties.WebSocket webSocketProperties) {
        if (webSocketProperties == null || webSocketProperties.getQueueCapacity() < 0) {
            return DEFAULT_QUEUE_CAPACITY;
        }
        return webSocketProperties.getQueueCapacity();
    }

    /**
     * Creates the work queue for the broadcast executor. A capacity of 0 uses a
     * {@link SynchronousQueue} (direct handoff), otherwise an {@link ArrayBlockingQueue}.
     */
    private BlockingQueue<Runnable> initExecutorQueue(int queueCapacity) {
        if (queueCapacity == 0) {
            return new SynchronousQueue<Runnable>();
        }
        return new ArrayBlockingQueue<Runnable>(queueCapacity);
    }
}
