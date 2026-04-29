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

public class MessageSenderSupport implements MessageSender {

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

    public MessageSenderSupport(NettyServerBootstrap nettyServerBootstrap) {
        this.nettyServerBootstrap = nettyServerBootstrap;
        this.nettyServerBootstrap.addStopListener(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        });
    }

    public synchronized MessageSender getMessageSender() {
        Map<String, AbstractMappingResolver> resolverMap = this.nettyServerBootstrap.getWebSockeMappingtResolverMap();
        if (this.messageSender != null && this.resolverSource == resolverMap) {
            return this.messageSender;
        }
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

    @Override
    public int getSessionNums() {
        return this.getMessageSender().getSessionNums();
    }

    @Override
    public int getSessionNums(String uri) {
        return this.getMessageSender().getSessionNums(uri);
    }

    @Override
    public Set<String> getRegisteredUri() {
        return this.getMessageSender().getRegisteredUri();
    }

    @Override
    public Set<String> getSessionIds(String uri) {
        return this.getMessageSender().getSessionIds(uri);
    }

    @Override
    public MessageSession getSession(String uri, String sessionId) {
        return this.getMessageSender().getSession(uri, sessionId);
    }

    @Override
    public Map<String, MessageSession> getSessions(String uri) {
        return this.getMessageSender().getSessions(uri);
    }

    @Override
    public boolean isSessionAlive(String uri, String... sessionIds) {
        return this.getMessageSender().isSessionAlive(uri, sessionIds);
    }

    @Override
    public void sendMessage(String uri, AbstractMessage message, String... sessionIds) throws MessageUriNotDefinedException, MessageSessionClosedException {
        this.getMessageSender().sendMessage(uri, message, sessionIds);
    }

    @Override
    public void topicMessage(String uri, AbstractMessage message) throws MessageUriNotDefinedException {
        this.getMessageSender().topicMessage(uri, message);
    }

    @Override
    public boolean closeSession(String uri, String sessionId, int statusCode, String reasonText)
            throws MessageUriNotDefinedException {
        return this.getMessageSender().closeSession(uri, sessionId, statusCode, reasonText);
    }

    @Override
    public int closeSessions(String uri, int statusCode, String reasonText) throws MessageUriNotDefinedException {
        return this.getMessageSender().closeSessions(uri, statusCode, reasonText);
    }

    @Override
    public MessageSenderRuntimeStats getRuntimeStats() {
        return this.getMessageSender().getRuntimeStats();
    }

    @Override
    public synchronized void shutdown() {
        resetCachedSender();
    }

    private void resetCachedSender() {
        if (this.messageSender != null) {
            this.messageSender.shutdown();
            this.messageSender = null;
        }
        this.resolverSource = null;
    }

}
