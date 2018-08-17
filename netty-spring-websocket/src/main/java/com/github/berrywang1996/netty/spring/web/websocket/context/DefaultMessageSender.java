package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public class DefaultMessageSender implements MessageSender {

    private final Map<String, MessageMappingResolver> resolverMap;

    DefaultMessageSender(Map<String, MessageMappingResolver> resolverMap) {
        this.resolverMap = resolverMap;
    }

    @Override
    public int getSessionNums() {
        int total = 0;
        for (MessageMappingResolver resolver : resolverMap.values()) {
            total = resolver.getSessionMap().size();
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
            boolean contains = resolver.getSessionMap().keySet().contains(sessionId);
            if (contains) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void sendMessage(String uri, AbstractMessage message, String... sessionIds) throws MessageUriNotDefinedException, MessageSessionClosedException {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null) {
            throw new MessageUriNotDefinedException(uri);
        }
        Map<String, MessageSession> sessionMap = resolver.getSessionMap();
        List<String> closedSessionIds = null;
        for (String sessionId : sessionIds) {
            MessageSession session = sessionMap.get(sessionId);
            if (session == null) {
                if (closedSessionIds == null) {
                    closedSessionIds = new ArrayList<>();
                }
                closedSessionIds.add(sessionId);
            } else {
                session.getChannelHandlerContext().writeAndFlush(message.responseMsg());
            }
        }
        if (closedSessionIds != null && closedSessionIds.size() > 0) {
            throw new MessageSessionClosedException(closedSessionIds);
        }
    }

    @Override
    public void topicMessage(String uri, AbstractMessage message) throws MessageUriNotDefinedException {
        MessageMappingResolver resolver = resolverMap.get(uri);
        if (resolver == null) {
            throw new MessageUriNotDefinedException(uri);
        }
        Map<String, MessageSession> sessionMap = resolver.getSessionMap();
        for (MessageSession session : sessionMap.values()) {
            session.getChannelHandlerContext().writeAndFlush(message.responseMsg());
        }
    }

}