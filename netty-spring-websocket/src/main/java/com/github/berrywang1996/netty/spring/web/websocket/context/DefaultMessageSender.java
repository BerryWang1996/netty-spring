package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.context.ExecutorRuntimeInfo;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.util.DaemonThreadFactory;
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.consts.CloseReason;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * to all sessions on a URI, querying session state, and closing sessions.
 *
 * <h3>Threading model (v1.6)</h3>
 * <p>Two broadcast modes are supported, controlled by
 * {@link NettyServerStartupProperties.WebSocket.BroadcastMode}:
 *
 * <ul>
 *   <li><strong>EVENT_LOOP_DIRECT</strong> (default): The message payload is serialized
 *       once via {@link AbstractMessage#serializeSharedPayload} and the resulting
 *       {@link ByteBuf} is shared across all target sessions using
 *       {@code ByteBuf.retainedDuplicate()}. Sessions are grouped by their Netty
 *       {@link EventLoop}, and a single task per EventLoop performs write + batch flush.
 *       This eliminates N-1 redundant serializations, minimizes cross-thread scheduling,
 *       and reduces syscall overhead via batch flush.</li>
 *   <li><strong>THREAD_POOL_LEGACY</strong>: v1.5.x behavior — each session's broadcast
 *       is submitted as an individual task to the internal thread pool with per-task
 *       serialization. Provided for backward compatibility.</li>
 * </ul>
 *
 * <p>Targeted sends ({@link #sendMessage}) always run synchronously on the caller thread,
 * regardless of the broadcast mode.
 *
 * <h3>Lifecycle</h3>
 * The sender must be shut down via {@link #shutdown()} when no longer needed.
 * {@link com.github.berrywang1996.netty.spring.web.websocket.support.MessageSenderSupport}
 * handles this automatically via its server stop listener.
 *
 * @author berrywang1996
 * @version V1.6.0
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

    /**
     * Thread pool used for asynchronous broadcast delivery in THREAD_POOL_LEGACY mode.
     * May be {@code null} if EVENT_LOOP_DIRECT mode is active.
     */
    private final ThreadPoolExecutor executorService;

    /** URI-to-resolver mapping; each resolver owns a session map for one WebSocket URI. */
    private final Map<String, MessageMappingResolver> resolverMap;

    /** The active broadcast mode. */
    private final NettyServerStartupProperties.WebSocket.BroadcastMode broadcastMode;

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
        this.broadcastMode = resolveBroadcastMode(webSocketProperties);

        // Only create the thread pool if legacy mode is active; EVENT_LOOP_DIRECT doesn't need it
        if (this.broadcastMode == NettyServerStartupProperties.WebSocket.BroadcastMode.THREAD_POOL_LEGACY) {
            this.executorService = initHandlerExecutorThreadPool(webSocketProperties);
            log.info("MessageSender initialized in THREAD_POOL_LEGACY mode.");
        } else {
            this.executorService = null;
            log.info("MessageSender initialized in EVENT_LOOP_DIRECT mode (zero-copy broadcast).");
        }
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

    // ==================== Session Query Methods ====================

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

    // ==================== Targeted Send ====================

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
                // Close the session via the full lifecycle path so @MessageMapping(ON_CLOSE)
                // fires and metrics are recorded. Plain removeSession() bypasses both, which
                // silently drops user close handlers when a stale channel is observed here
                // before Netty's own channelInactive runs on the EventLoop.
                if (session != null) {
                    resolver.closeSessionOnTransportError(session,
                            new IllegalStateException("Channel inactive on send"),
                            CloseReason.CHANNEL_INACTIVE);
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

    // ==================== Broadcast ====================

    /**
     * {@inheritDoc}
     *
     * <p>Broadcasts the message to all active sessions on the given URI.
     * Delegates to the active broadcast mode implementation.
     */
    @Override
    public void topicMessage(String uri, final AbstractMessage message) throws MessageUriNotDefinedException {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null) {
            throw new MessageUriNotDefinedException(uri, resolverMap.keySet());
        }
        if (broadcastMode == NettyServerStartupProperties.WebSocket.BroadcastMode.EVENT_LOOP_DIRECT) {
            topicMessageEventLoopDirect(resolver, message);
        } else {
            topicMessageLegacy(resolver, message);
        }
    }

    // ==================== EVENT_LOOP_DIRECT Broadcast (v1.6) ====================

    /**
     * Zero-copy, EventLoop-direct broadcast implementation.
     *
     * <p>This method:
     * <ol>
     *   <li>Serializes the message payload once into a shared {@link ByteBuf}</li>
     *   <li>Groups all active sessions by their Netty {@link EventLoop}</li>
     *   <li>Submits one task per EventLoop that:
     *       <ul>
     *         <li>For each session: creates a frame wrapping a {@code retainedDuplicate()}
     *             of the shared payload, optionally encrypts it, writes it without flushing</li>
     *         <li>After all writes: flushes each channel once (batch flush)</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p>Performance characteristics on a 2-core machine with 500 sessions:
     * <ul>
     *   <li>Serialization: 1 call (was 500)</li>
     *   <li>EventLoop tasks: ~4 (was 500 thread pool tasks)</li>
     *   <li>Context switches: ~0 (tasks execute on the target EventLoop)</li>
     * </ul>
     */
    private void topicMessageEventLoopDirect(final MessageMappingResolver resolver,
                                             final AbstractMessage message) {
        Map<String, MessageSession> sessionMap = resolver.getSessionMap();
        if (sessionMap.isEmpty()) {
            return;
        }

        // Step 1: Serialize once — caller thread pays the cost exactly once
        final boolean isText = message.isTextFrame();
        final ByteBuf sharedPayload;
        try {
            sharedPayload = message.serializeSharedPayload(PooledByteBufAllocator.DEFAULT);
        } catch (Exception e) {
            log.error("Failed to serialize broadcast message payload.", e);
            return;
        }

        try {
            // Step 2: Group sessions by EventLoop
            Map<EventLoop, List<SessionContext>> groups = groupSessionsByEventLoop(resolver, sessionMap);

            if (groups.isEmpty()) {
                return;
            }

            // Record broadcast fanout (number of sessions targeted)
            int fanoutCount = 0;
            for (List<SessionContext> group : groups.values()) {
                fanoutCount += group.size();
            }
            resolver.getEventRecorder().recordBroadcastFanout(fanoutCount);

            // Step 3: Submit one task per EventLoop
            for (Map.Entry<EventLoop, List<SessionContext>> entry : groups.entrySet()) {
                EventLoop eventLoop = entry.getKey();
                final List<SessionContext> sessions = entry.getValue();
                // Each EventLoop task gets its own retained duplicate of the shared payload
                final ByteBuf elPayload = sharedPayload.retainedDuplicate();

                try {
                    eventLoop.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                broadcastOnEventLoop(resolver, sessions, elPayload, isText);
                            } catch (Exception e) {
                                log.error("Unexpected error in EventLoop broadcast task.", e);
                            } finally {
                                elPayload.release();
                            }
                        }
                    });
                } catch (Exception e) {
                    // EventLoop rejected the task (e.g. shutting down) — release the payload
                    // to prevent a direct memory leak
                    elPayload.release();
                    log.warn("EventLoop rejected broadcast task, released payload. sessions={}",
                            sessions.size(), e);
                }
            }
        } finally {
            // Release the original shared payload (EventLoop tasks hold retained duplicates)
            sharedPayload.release();
        }
    }

    /**
     * Groups active, writable sessions by their channel's {@link EventLoop}.
     * Sessions that are closing, inactive, or non-writable are filtered out.
     *
     * @return a map of EventLoop to the list of session contexts in that loop
     */
    private Map<EventLoop, List<SessionContext>> groupSessionsByEventLoop(
            MessageMappingResolver resolver, Map<String, MessageSession> sessionMap) {

        Map<EventLoop, List<SessionContext>> groups = new LinkedHashMap<>();

        for (MessageSession session : sessionMap.values()) {
            if (!isSessionActiveWithCleanup(resolver, session.getSessionId())) {
                continue;
            }
            if (!isSessionWritable(session)) {
                handleNonWritableBroadcastSession(resolver, session);
                continue;
            }
            EventLoop eventLoop = session.getChannelHandlerContext().channel().eventLoop();
            List<SessionContext> group = groups.get(eventLoop);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(eventLoop, group);
            }
            group.add(new SessionContext(session, resolver));
        }
        return groups;
    }

    /**
     * Executes on a Netty EventLoop thread: writes a shared-payload frame to each
     * session in the group, then batch-flushes all channels.
     *
     * @param resolver      the resolver for session lifecycle operations
     * @param sessions      the sessions assigned to this EventLoop
     * @param sharedPayload the serialized payload (caller retains ownership)
     * @param isText        whether to wrap as TextWebSocketFrame or BinaryWebSocketFrame
     */
    private void broadcastOnEventLoop(MessageMappingResolver resolver,
                                      List<SessionContext> sessions,
                                      ByteBuf sharedPayload,
                                      boolean isText) {
        // Track contexts that need flushing
        List<ChannelHandlerContext> toFlush = new ArrayList<>(sessions.size());

        for (SessionContext sc : sessions) {
            MessageSession session = sc.session;
            // Re-check session state (may have changed since grouping)
            if (!isSessionActiveWithCleanup(resolver, session.getSessionId())) {
                continue;
            }
            if (!session.getChannelHandlerContext().channel().isWritable()) {
                handleNonWritableBroadcastSession(resolver, session);
                continue;
            }

            ChannelHandlerContext ctx = session.getChannelHandlerContext();
            WebSocketFrame frame = null;
            try {
                // Create frame from a retained duplicate — zero-copy, independent refcount
                ByteBuf frameBuf = sharedPayload.retainedDuplicate();
                if (isText) {
                    frame = new TextWebSocketFrame(frameBuf);
                } else {
                    frame = new BinaryWebSocketFrame(frameBuf);
                }

                // Encrypt if needed (per-session; may replace the frame)
                frame = resolver.encryptOutboundFrame(session, frame);

                // write() without flush — queues the frame in the channel pipeline
                final MessageSession finalSession = session;
                ctx.write(frame).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) {
                        if (future.isSuccess()) {
                            resolver.getEventRecorder().recordMessageSent();
                        } else {
                            handleWriteFailure(resolver, finalSession, future.cause());
                        }
                    }
                });
                frame = null; // ownership transferred to pipeline
                toFlush.add(ctx);
            } catch (Exception e) {
                // Release the frame if it wasn't handed to the pipeline
                if (frame != null) {
                    ReferenceCountUtil.release(frame);
                }
                handleWriteFailure(resolver, session, e);
            }
        }

        // Batch flush: one syscall per channel (instead of per-message writeAndFlush)
        for (ChannelHandlerContext ctx : toFlush) {
            ctx.flush();
        }
    }

    // ==================== THREAD_POOL_LEGACY Broadcast (v1.5.x compat) ====================

    /**
     * Legacy broadcast implementation: submits per-session tasks to the thread pool.
     * Each task serializes the message independently. Kept for backward compatibility.
     */
    private void topicMessageLegacy(final MessageMappingResolver resolver,
                                    final AbstractMessage message) {
        Map<String, MessageSession> sessionMap = resolver.getSessionMap();
        int fanoutCount = 0;
        for (final MessageSession session : sessionMap.values()) {
            if (!prepareSessionForBroadcast(resolver, session)) {
                continue;
            }
            submitBroadcastTask(resolver, session, message);
            fanoutCount++;
        }
        resolver.getEventRecorder().recordBroadcastFanout(fanoutCount);
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

    // ==================== Frame-Level Send ====================

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
        if (!isSessionActiveWithCleanup(resolver, session.getSessionId())) {
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

    // ==================== Session Close ====================

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

    // ==================== Statistics & Lifecycle ====================

    /** {@inheritDoc} */
    @Override
    public MessageSenderRuntimeStats getRuntimeStats() {
        return new MessageSenderRuntimeStats(
                this.executorService != null
                        ? ExecutorRuntimeInfo.from(this.executorService)
                        : ExecutorRuntimeInfo.empty(),
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
     * <p>Initiates a graceful shutdown of the broadcast thread pool (if active), waiting
     * up to 5 seconds for in-flight tasks before forcing termination.
     */
    @Override
    public void shutdown() {
        if (executorService == null || executorService.isShutdown()) {
            return;
        }
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== Error Handling ====================

    /** Increments the write failure counter and closes the session via the transport error lifecycle. */
    private void handleWriteFailure(MessageMappingResolver resolver, MessageSession session, Throwable cause) {
        this.writeFailureCount.incrementAndGet();
        log.warn("Send websocket message failed, close session {} through websocket lifecycle. stats={}",
                session.getSessionId(),
                getRuntimeStats(),
                cause);
        resolver.closeSessionOnTransportError(session, cause, CloseReason.WRITE_FAILURE);
    }

    /**
     * Checks whether a session exists, is not closing, and its channel is still active.
     * This is a pure predicate with no side effects — safe for query methods like
     * {@link #getSessionIds(String)} and {@link #getSessions(String)}.
     */
    private boolean isSessionActive(MessageMappingResolver resolver, String sessionId) {
        MessageSession session = resolver.getSessionMap().get(sessionId);
        if (session == null) {
            return false;
        }
        if (session.isClosing()) {
            return false;
        }
        return session.getChannelHandlerContext().channel().isActive();
    }

    /**
     * Same as {@link #isSessionActive} but also cleans up stale sessions that are no longer
     * active. Used in write paths (send/broadcast) where cleanup is appropriate.
     */
    private boolean isSessionActiveWithCleanup(MessageMappingResolver resolver, String sessionId) {
        MessageSession session = resolver.getSessionMap().get(sessionId);
        if (session == null) {
            return false;
        }
        if (session.isClosing()) {
            return false;
        }
        if (!session.getChannelHandlerContext().channel().isActive()) {
            // Use the full close-lifecycle path so @MessageMapping(ON_CLOSE) fires; the
            // CAS in startClosing() makes this idempotent with Netty's own channelInactive.
            resolver.closeSessionOnTransportError(session,
                    new IllegalStateException("Channel inactive during broadcast"),
                    CloseReason.CHANNEL_INACTIVE);
            return false;
        }
        return true;
    }

    /** Pre-flight check for broadcast: verifies the session is active and the channel is writable. */
    private boolean prepareSessionForBroadcast(MessageMappingResolver resolver, MessageSession session) {
        if (!isSessionActiveWithCleanup(resolver, session.getSessionId())) {
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
        if (log.isDebugEnabled()) {
            log.debug("Skip websocket broadcast because channel is not writable. sessionId={}", session.getSessionId());
        }
    }

    /**
     * Handles a rejected broadcast task when the thread pool is saturated (THREAD_POOL_LEGACY mode only).
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

    // ==================== Configuration Resolution ====================

    private NettyServerStartupProperties.WebSocket.BroadcastMode resolveBroadcastMode(
            NettyServerStartupProperties.WebSocket webSocketProperties) {
        if (webSocketProperties == null || webSocketProperties.getBroadcastMode() == null) {
            return NettyServerStartupProperties.WebSocket.BroadcastMode.EVENT_LOOP_DIRECT;
        }
        return webSocketProperties.getBroadcastMode();
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

    // ==================== Inner Classes ====================

    /**
     * Lightweight holder pairing a session with its resolver, avoiding repeated
     * map lookups during EventLoop-direct broadcast.
     */
    private static final class SessionContext {
        final MessageSession session;
        final MessageMappingResolver resolver;

        SessionContext(MessageSession session, MessageMappingResolver resolver) {
            this.session = session;
            this.resolver = resolver;
        }
    }
}
