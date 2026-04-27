package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.context.ExecutorRuntimeInfo;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.util.DaemonThreadFactory;
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
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
 * @author berrywang1996
 * @version V1.0.0
 */
@Slf4j
public class DefaultMessageSender implements MessageSender {

    private static final int DEFAULT_CORE_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());

    private static final int DEFAULT_MAX_POOL_SIZE =
            Math.max(DEFAULT_CORE_POOL_SIZE, Runtime.getRuntime().availableProcessors() * 2);

    private static final long DEFAULT_KEEP_ALIVE_SECONDS = 60L;

    private static final int DEFAULT_QUEUE_CAPACITY = 1024;

    private final NettyServerStartupProperties.WebSocket webSocketProperties;

    private final ThreadPoolExecutor executorService;

    private final Map<String, MessageMappingResolver> resolverMap;

    private final AtomicLong rejectedBroadcastCount = new AtomicLong();

    private final AtomicLong callerRunsFallbackCount = new AtomicLong();

    private final AtomicLong droppedBroadcastCount = new AtomicLong();

    private final AtomicLong nonWritableSkipCount = new AtomicLong();

    private final AtomicLong nonWritableCloseCount = new AtomicLong();

    private final AtomicLong writeFailureCount = new AtomicLong();

    public DefaultMessageSender(Map<String, MessageMappingResolver> resolverMap) {
        this(resolverMap, null);
    }

    public DefaultMessageSender(Map<String, MessageMappingResolver> resolverMap,
                                NettyServerStartupProperties.WebSocket webSocketProperties) {
        this.resolverMap = resolverMap;
        this.webSocketProperties = webSocketProperties;
        this.executorService = initHandlerExecutorThreadPool(webSocketProperties);
    }

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

    @Override
    public int getSessionNums() {
        int total = 0;
        for (MessageMappingResolver resolver : resolverMap.values()) {
            total += resolver.getSessionMap().size();
        }
        return total;
    }

    @Override
    public int getSessionNums(String uri) {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null) {
            return 0;
        }
        return resolver.getSessionMap().size();
    }

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

    @Override
    public MessageSession getSession(String uri, String sessionId) {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null || !isSessionActive(resolver, sessionId)) {
            return null;
        }
        return resolver.getSessionMap().get(sessionId);
    }

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

    @Override
    public Set<String> getRegisteredUri() {
        return resolverMap.keySet();
    }

    @Override
    public boolean isSessionAlive(String uri, String... sessionIds) {
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

    @Override
    public void sendMessage(String uri, AbstractMessage message, String... sessionIds)
            throws MessageUriNotDefinedException, MessageSessionClosedException {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null) {
            throw new MessageUriNotDefinedException(uri);
        }
        Map<String, MessageSession> sessionMap = resolver.getSessionMap();
        List<String> closedSessionIds = null;
        for (String sessionId : sessionIds) {
            MessageSession session = sessionMap.get(sessionId);
            if (session == null || !session.getChannelHandlerContext().channel().isActive()) {
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
        if (closedSessionIds != null && closedSessionIds.size() > 0) {
            throw new MessageSessionClosedException(closedSessionIds);
        }
    }

    @Override
    public void topicMessage(String uri, final AbstractMessage message) throws MessageUriNotDefinedException {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null) {
            throw new MessageUriNotDefinedException(uri);
        }
        Map<String, MessageSession> sessionMap = resolver.getSessionMap();
        for (final MessageSession session : sessionMap.values()) {
            if (!prepareSessionForBroadcast(resolver, session)) {
                continue;
            }
            submitBroadcastTask(resolver, session, message);
        }
    }

    @Override
    public boolean closeSession(String uri, String sessionId, int statusCode, String reasonText)
            throws MessageUriNotDefinedException {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null) {
            throw new MessageUriNotDefinedException(uri);
        }
        return resolver.closeSession(sessionId, statusCode, reasonText);
    }

    @Override
    public int closeSessions(String uri, int statusCode, String reasonText) throws MessageUriNotDefinedException {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null) {
            throw new MessageUriNotDefinedException(uri);
        }
        int closed = 0;
        for (String sessionId : new ArrayList<>(resolver.getSessionMap().keySet())) {
            if (resolver.closeSession(sessionId, statusCode, reasonText)) {
                closed++;
            }
        }
        return closed;
    }

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

    @Override
    public void shutdown() {
        if (executorService.isShutdown()) {
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
        ChannelFuture future = session.getChannelHandlerContext().writeAndFlush(message.responseMsg());
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (!future.isSuccess()) {
                    handleWriteFailure(resolver, session, future.cause());
                }
            }
        });
    }

    private void handleWriteFailure(MessageMappingResolver resolver, MessageSession session, Throwable cause) {
        this.writeFailureCount.incrementAndGet();
        log.warn("Send websocket message failed, close session {} through websocket lifecycle. stats={}",
                session.getSessionId(),
                getRuntimeStats(),
                cause);
        resolver.closeSessionOnTransportError(session, cause);
    }

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

    private void handleNonWritableBroadcastSession(MessageMappingResolver resolver, MessageSession session) {
        if (resolveBroadcastNonWritableChannelPolicy()
                == NettyServerStartupProperties.WebSocket.BroadcastNonWritableChannelPolicy.CLOSE) {
            this.nonWritableCloseCount.incrementAndGet();
            log.warn("Close websocket session because channel is not writable during broadcast. sessionId={}, stats={}",
                    session.getSessionId(),
                    getRuntimeStats());
            resolver.closeSessionOnTransportError(session, new IllegalStateException("Channel is not writable."));
            return;
        }
        this.nonWritableSkipCount.incrementAndGet();
        log.warn("Skip websocket broadcast because channel is not writable. sessionId={}, stats={}",
                session.getSessionId(),
                getRuntimeStats());
    }

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

    private BlockingQueue<Runnable> initExecutorQueue(int queueCapacity) {
        if (queueCapacity == 0) {
            return new SynchronousQueue<Runnable>();
        }
        return new ArrayBlockingQueue<Runnable>(queueCapacity);
    }
}
