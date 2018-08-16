package com.github.berrywang1996.netty.spring.web.websocket.context;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public class MessageSession {

    private String sessionId;

    private ChannelHandlerContext channelHandlerContext;

    private FullHttpRequest firstRequest;

    public MessageSession(String sessionId, ChannelHandlerContext ctx, FullHttpRequest request) {
        this.sessionId = sessionId;
        this.channelHandlerContext = ctx;
        this.firstRequest = request;
    }

    public String getSessionId() {
        return sessionId;
    }

    public ChannelHandlerContext getChannelHandlerContext() {
        return channelHandlerContext;
    }

    public FullHttpRequest getFirstRequest() {
        return firstRequest;
    }
}
