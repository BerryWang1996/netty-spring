package com.github.berrywang1996.netty.spring.web.websocket.context;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.util.ReferenceCountUtil;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public class MessageSession {

    private final String sessionId;

    private final ChannelHandlerContext channelHandlerContext;

    private final FullHttpRequest firstRequest;

    private final Runnable cleanupAction;

    private final AtomicBoolean closing = new AtomicBoolean(false);

    private final AtomicBoolean released = new AtomicBoolean(false);

    public MessageSession(String sessionId, ChannelHandlerContext ctx, FullHttpRequest request) {
        this(sessionId, ctx, request, null);
    }

    public MessageSession(String sessionId, ChannelHandlerContext ctx, FullHttpRequest request, Runnable cleanupAction) {
        this.sessionId = sessionId;
        this.channelHandlerContext = ctx;
        this.firstRequest = request.retainedDuplicate();
        this.cleanupAction = cleanupAction;
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

    public boolean startClosing() {
        return closing.compareAndSet(false, true);
    }

    public boolean isClosing() {
        return closing.get();
    }

    public void release() {
        if (released.compareAndSet(false, true)) {
            if (cleanupAction != null) {
                cleanupAction.run();
            }
            ReferenceCountUtil.release(firstRequest);
        }
    }
}
