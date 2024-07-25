package com.github.berrywang1996.netty.spring.web.websocket.support;

import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.context.AbstractMessage;
import com.github.berrywang1996.netty.spring.web.websocket.context.DefaultMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class MessageSenderSupport implements MessageSender {

    private final NettyServerBootstrap nettyServerBootstrap;

    private MessageSender messageSender;

    public MessageSenderSupport(NettyServerBootstrap nettyServerBootstrap) {
        this.nettyServerBootstrap = nettyServerBootstrap;
    }

    public synchronized MessageSender getMessageSender() {
        if (this.messageSender != null) {
            return this.messageSender;
        }
        Map<String, AbstractMappingResolver> resolverMap = this.nettyServerBootstrap.getWebSockeMappingtResolverMap();
        if (resolverMap.isEmpty()) {
            return null;
        }
        Map<String, MessageMappingResolver> map = new HashMap<>();
        for (Map.Entry<String, AbstractMappingResolver> entry : resolverMap.entrySet()) {
            map.put(entry.getKey(), (MessageMappingResolver) entry.getValue());
        }
        this.messageSender = new DefaultMessageSender(map);
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

}
