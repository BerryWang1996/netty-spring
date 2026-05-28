package com.github.berrywang1996.netty.spring.web.websocket.support;

import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.context.AbstractMessage;
import com.github.berrywang1996.netty.spring.web.websocket.context.DefaultMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSenderRuntimeStats;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Spring-managed {@link MessageSender} implementation with lifecycle hooks.
 *
 * <p>This class acts as a lazy, self-refreshing proxy around a {@link DefaultMessageSender}.
 * It obtains the resolver map from the {@link NettyServerBootstrap} on first access and
 * caches the delegate until the resolver map reference changes (e.g. during hot-reload
 * or restart). When the bootstrap signals a stop, the cached sender is shut down via the
 * registered stop listener.
 *
 * <p>All public {@link MessageSender} methods delegate to the lazily-resolved sender,
 * making this class safe to inject into Spring beans that may be created before the
 * Netty server has finished starting.
 *
 * <p>Thread safety: {@link #getMessageSender()} and {@link #shutdown()} are
 * {@code synchronized} to prevent concurrent cache replacement.
 *
 * @author berrywang1996
 * @since V1.0.0
 * @see DefaultMessageSender
 */
public class MessageSenderSupport implements MessageSender {

    /** Fallback sender used when no WebSocket resolvers are registered yet. */
    private static final MessageSender EMPTY_MESSAGE_SENDER = new MessageSender() {
        @Override
        public int getSessionNums() {
            return 0;
        }

        @Override
        public int getSessionNums(String uri) {
            return 0;
        }

        @Override
        public Set<String> getRegisteredUri() {
            return Collections.emptySet();
        }

        @Override
        public boolean isSessionAlive(String uri, String... sessionIds) {
            return false;
        }

        @Override
        public void sendMessage(String uri, AbstractMessage message, String... sessionIds)
                throws MessageUriNotDefinedException, MessageSessionClosedException {
            throw new MessageUriNotDefinedException(uri, getRegisteredUri());
        }

        @Override
        public void topicMessage(String uri, AbstractMessage message) throws MessageUriNotDefinedException {
            throw new MessageUriNotDefinedException(uri, getRegisteredUri());
        }
    };

    private final NettyServerBootstrap nettyServerBootstrap;

    private MessageSender messageSender;

    private Map<String, AbstractMappingResolver> resolverSource;

    /**
     * Creates the support wrapper and registers a stop listener on the bootstrap
     * so that the cached sender is shut down when the server stops.
     *
     * @param nettyServerBootstrap the server bootstrap that owns the resolver map
     */
    public MessageSenderSupport(NettyServerBootstrap nettyServerBootstrap) {
        this.nettyServerBootstrap = nettyServerBootstrap;
        this.nettyServerBootstrap.addStopListener(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        });
    }

    /**
     * Returns the active {@link MessageSender}, lazily creating or refreshing it
     * when the resolver map reference from the bootstrap changes.
     *
     * <p>Synchronized to prevent concurrent cache replacement during hot-reload.
     *
     * @return the current message sender (never {@code null})
     */
    public synchronized MessageSender getMessageSender() {
        Map<String, AbstractMappingResolver> resolverMap = this.nettyServerBootstrap.getWebSocketMappingResolverMap();
        // Return cached sender if the resolver map reference has not changed
        if (this.messageSender != null && this.resolverSource == resolverMap) {
            return this.messageSender;
        }
        // Resolver map changed -- shut down old sender and build a new one
        resetCachedSender();
        if (resolverMap == null || resolverMap.isEmpty()) {
            this.resolverSource = resolverMap;
            this.messageSender = EMPTY_MESSAGE_SENDER;
            return this.messageSender;
        }
        Map<String, MessageMappingResolver> map = new HashMap<>();
        for (Map.Entry<String, AbstractMappingResolver> entry : resolverMap.entrySet()) {
            map.put(entry.getKey(), (MessageMappingResolver) entry.getValue());
        }
        this.resolverSource = resolverMap;
        this.messageSender = new DefaultMessageSender(
                map,
                this.nettyServerBootstrap.getStartupProperties() == null
                        ? null
                        : this.nettyServerBootstrap.getStartupProperties().getWebSocket());
        return this.messageSender;
    }

    /** {@inheritDoc} */
    @Override
    public int getSessionNums() {
        return this.getMessageSender().getSessionNums();
    }

    /** {@inheritDoc} */
    @Override
    public int getSessionNums(String uri) {
        return this.getMessageSender().getSessionNums(uri);
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getRegisteredUri() {
        return this.getMessageSender().getRegisteredUri();
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getSessionIds(String uri) {
        return this.getMessageSender().getSessionIds(uri);
    }

    /** {@inheritDoc} */
    @Override
    public MessageSession getSession(String uri, String sessionId) {
        return this.getMessageSender().getSession(uri, sessionId);
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, MessageSession> getSessions(String uri) {
        return this.getMessageSender().getSessions(uri);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isSessionAlive(String uri, String... sessionIds) {
        return this.getMessageSender().isSessionAlive(uri, sessionIds);
    }

    /** {@inheritDoc} */
    @Override
    public void sendMessage(String uri, AbstractMessage message, String... sessionIds) throws MessageUriNotDefinedException, MessageSessionClosedException {
        this.getMessageSender().sendMessage(uri, message, sessionIds);
    }

    /** {@inheritDoc} */
    @Override
    public void topicMessage(String uri, AbstractMessage message) throws MessageUriNotDefinedException {
        this.getMessageSender().topicMessage(uri, message);
    }

    /** {@inheritDoc} */
    @Override
    public boolean closeSession(String uri, String sessionId, int statusCode, String reasonText)
            throws MessageUriNotDefinedException {
        return this.getMessageSender().closeSession(uri, sessionId, statusCode, reasonText);
    }

    /** {@inheritDoc} */
    @Override
    public int closeSessions(String uri, int statusCode, String reasonText) throws MessageUriNotDefinedException {
        return this.getMessageSender().closeSessions(uri, statusCode, reasonText);
    }

    /** {@inheritDoc} */
    @Override
    public MessageSenderRuntimeStats getRuntimeStats() {
        return this.getMessageSender().getRuntimeStats();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Shuts down the cached delegate sender and clears references.
     * Synchronized to prevent races with {@link #getMessageSender()}.
     */
    @Override
    public synchronized void shutdown() {
        resetCachedSender();
    }

    /**
     * Shuts down the current cached sender (if any) and clears all cached references,
     * forcing a fresh sender to be created on the next access.
     */
    private void resetCachedSender() {
        if (this.messageSender != null) {
            this.messageSender.shutdown();
            this.messageSender = null;
        }
        this.resolverSource = null;
    }

}
