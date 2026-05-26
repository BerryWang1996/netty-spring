package com.github.berrywang1996.netty.spring.web.websocket.context;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Cached path parsed from the first request URI. Computed once on construction.
     */
    private final String cachedPath;

    /**
     * Cached query parameters parsed from the first request URI. Computed once on construction.
     */
    private final Map<String, List<String>> cachedQueryParams;

    private final AtomicBoolean closing = new AtomicBoolean(false);

    private final AtomicBoolean released = new AtomicBoolean(false);

    private final AtomicBoolean heartbeatStarted = new AtomicBoolean(false);

    private volatile long lastReadTimeMillis = System.currentTimeMillis();

    private volatile long lastPongTimeMillis = lastReadTimeMillis;

    public MessageSession(String sessionId, ChannelHandlerContext ctx, FullHttpRequest request) {
        this(sessionId, ctx, request, null);
    }

    public MessageSession(String sessionId, ChannelHandlerContext ctx, FullHttpRequest request, Runnable cleanupAction) {
        this.sessionId = sessionId;
        this.channelHandlerContext = ctx;
        this.firstRequest = request.retainedDuplicate();
        this.cleanupAction = cleanupAction;
        // Parse URI once and cache results to avoid repeated QueryStringDecoder allocation
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        this.cachedPath = decoder.path();
        Map<String, List<String>> rawParams = decoder.parameters();
        Map<String, List<String>> immutableParams = new HashMap<>(rawParams.size());
        for (Map.Entry<String, List<String>> entry : rawParams.entrySet()) {
            immutableParams.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        this.cachedQueryParams = Collections.unmodifiableMap(immutableParams);
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

    public String getUri() {
        return firstRequest.uri();
    }

    public String getPath() {
        return cachedPath;
    }

    public String getQueryParam(String name) {
        List<String> values = cachedQueryParams.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    public List<String> getQueryParams(String name) {
        List<String> values = cachedQueryParams.get(name);
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return values;
    }

    public Map<String, List<String>> getQueryParams() {
        return cachedQueryParams;
    }

    public String getHeader(String name) {
        return firstRequest.headers().get(name);
    }

    public List<String> getHeaders(String name) {
        List<String> values = firstRequest.headers().getAll(name);
        if (values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public Set<String> getHeaderNames() {
        return Collections.unmodifiableSet(new HashSet<>(firstRequest.headers().names()));
    }

    public boolean startClosing() {
        return closing.compareAndSet(false, true);
    }

    public boolean isClosing() {
        return closing.get();
    }

    public boolean startHeartbeat() {
        return heartbeatStarted.compareAndSet(false, true);
    }

    public void recordInboundActivity() {
        this.lastReadTimeMillis = System.currentTimeMillis();
    }

    public void recordPong() {
        long now = System.currentTimeMillis();
        this.lastPongTimeMillis = now;
        this.lastReadTimeMillis = now;
    }

    public long getLastReadTimeMillis() {
        return lastReadTimeMillis;
    }

    public long getLastPongTimeMillis() {
        return lastPongTimeMillis;
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
