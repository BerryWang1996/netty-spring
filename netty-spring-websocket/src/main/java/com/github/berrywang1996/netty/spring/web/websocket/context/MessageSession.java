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
 * Represents a single WebSocket session with caching and lifecycle tracking.
 *
 * <p>A {@code MessageSession} is created after a successful WebSocket handshake and
 * holds all state associated with one client connection:
 * <ul>
 *   <li>A unique {@code sessionId} (UUID-based)</li>
 *   <li>The Netty {@link ChannelHandlerContext} for writing frames</li>
 *   <li>A retained copy of the original HTTP upgrade request for header/query access</li>
 *   <li>Cached path and query parameters (parsed once on construction to avoid repeated allocation)</li>
 *   <li>Lifecycle flags for closing state, heartbeat tracking, and resource release</li>
 * </ul>
 *
 * <p>Thread safety: lifecycle flags ({@code closing}, {@code released}, {@code heartbeatStarted})
 * are backed by {@link AtomicBoolean} for safe concurrent access from Netty I/O threads and
 * the application thread pool. Timestamp fields are {@code volatile}.
 *
 * @author berrywang1996
 * @version V1.0.0
 * @since V1.0.0
 */
public class MessageSession {

    private final String sessionId;

    private final ChannelHandlerContext channelHandlerContext;

    private final FullHttpRequest firstRequest;

    /** Optional cleanup action executed on release (e.g. releasing a connection semaphore permit). */
    private final Runnable cleanupAction;

    /**
     * Cached path parsed from the first request URI. Computed once on construction.
     */
    private final String cachedPath;

    /**
     * Cached query parameters parsed from the first request URI. Computed once on construction.
     */
    private final Map<String, List<String>> cachedQueryParams;

    /** CAS flag ensuring the close lifecycle runs at most once. */
    private final AtomicBoolean closing = new AtomicBoolean(false);

    /** CAS flag ensuring resources are released at most once. */
    private final AtomicBoolean released = new AtomicBoolean(false);

    /** CAS flag ensuring the heartbeat scheduler is started at most once. */
    private final AtomicBoolean heartbeatStarted = new AtomicBoolean(false);

    /** High-resolution timestamp captured at session creation. Used for connection duration metrics. */
    private final long createdAtNanos = System.nanoTime();

    /** Timestamp of the last inbound frame (data or pong). Used for idle detection. */
    private volatile long lastReadTimeMillis = System.currentTimeMillis();

    /** Timestamp of the last pong response. Used for heartbeat timeout detection. */
    private volatile long lastPongTimeMillis = lastReadTimeMillis;

    /**
     * Creates a new session without a cleanup action.
     *
     * @param sessionId unique identifier for this session
     * @param ctx       the Netty channel handler context
     * @param request   the original HTTP upgrade request (a retained duplicate is kept internally)
     */
    public MessageSession(String sessionId, ChannelHandlerContext ctx, FullHttpRequest request) {
        this(sessionId, ctx, request, null);
    }

    /**
     * Creates a new session with an optional cleanup action.
     *
     * @param sessionId     unique identifier for this session
     * @param ctx           the Netty channel handler context
     * @param request       the original HTTP upgrade request (a retained duplicate is kept internally)
     * @param cleanupAction optional runnable executed on {@link #release()}, e.g. to release a semaphore permit
     */
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

    /**
     * Returns the unique session identifier.
     *
     * @return the session ID (UUID string)
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Returns the Netty channel handler context associated with this session.
     *
     * @return the channel handler context for writing frames
     */
    public ChannelHandlerContext getChannelHandlerContext() {
        return channelHandlerContext;
    }

    /**
     * Returns a retained duplicate of the original HTTP upgrade request.
     *
     * @return the first HTTP request that initiated the WebSocket handshake
     */
    public FullHttpRequest getFirstRequest() {
        return firstRequest;
    }

    /**
     * Returns the full URI (path + query string) from the original handshake request.
     *
     * @return the raw request URI
     */
    public String getUri() {
        return firstRequest.uri();
    }

    /**
     * Returns the cached path component of the request URI (without query string).
     *
     * @return the request path
     */
    public String getPath() {
        return cachedPath;
    }

    /**
     * Returns the first value for the specified query parameter, or {@code null}
     * if the parameter is not present.
     *
     * @param name the query parameter name
     * @return the first parameter value, or {@code null}
     */
    public String getQueryParam(String name) {
        List<String> values = cachedQueryParams.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    /**
     * Returns all values for the specified query parameter.
     *
     * @param name the query parameter name
     * @return an unmodifiable list of values, or an empty list if the parameter is absent
     */
    public List<String> getQueryParams(String name) {
        List<String> values = cachedQueryParams.get(name);
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return values;
    }

    /**
     * Returns all cached query parameters as an unmodifiable map.
     *
     * @return immutable map of parameter name to list of values
     */
    public Map<String, List<String>> getQueryParams() {
        return cachedQueryParams;
    }

    /**
     * Returns the first value of the specified HTTP header from the handshake request.
     *
     * @param name the header name (case-insensitive)
     * @return the header value, or {@code null} if not present
     */
    public String getHeader(String name) {
        return firstRequest.headers().get(name);
    }

    /**
     * Returns all values of the specified HTTP header from the handshake request.
     *
     * @param name the header name (case-insensitive)
     * @return an unmodifiable list of header values, or an empty list if not present
     */
    public List<String> getHeaders(String name) {
        List<String> values = firstRequest.headers().getAll(name);
        if (values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * Returns all header names from the handshake request.
     *
     * @return an unmodifiable set of header names
     */
    public Set<String> getHeaderNames() {
        return Collections.unmodifiableSet(new HashSet<>(firstRequest.headers().names()));
    }

    /**
     * Atomically transitions this session into the closing state.
     * Only the first caller wins; subsequent calls return {@code false}.
     *
     * @return {@code true} if this call initiated the close, {@code false} if already closing
     */
    public boolean startClosing() {
        return closing.compareAndSet(false, true);
    }

    /**
     * Returns whether this session is in the process of being closed.
     *
     * @return {@code true} if the close lifecycle has been started
     */
    public boolean isClosing() {
        return closing.get();
    }

    /**
     * Atomically marks that the heartbeat scheduler has been started for this session.
     * Ensures the heartbeat is only started once per session.
     *
     * @return {@code true} if this call started the heartbeat, {@code false} if already started
     */
    public boolean startHeartbeat() {
        return heartbeatStarted.compareAndSet(false, true);
    }

    /**
     * Records inbound activity (any data frame received) by updating the last-read timestamp.
     */
    public void recordInboundActivity() {
        this.lastReadTimeMillis = System.currentTimeMillis();
    }

    /**
     * Records a pong frame received, updating both the pong timestamp and last-read timestamp.
     */
    public void recordPong() {
        long now = System.currentTimeMillis();
        this.lastPongTimeMillis = now;
        this.lastReadTimeMillis = now;
    }

    /**
     * Returns the timestamp of the last inbound activity (any frame type).
     *
     * @return epoch milliseconds of the last read
     */
    public long getLastReadTimeMillis() {
        return lastReadTimeMillis;
    }

    /**
     * Returns the timestamp of the last pong frame received.
     *
     * @return epoch milliseconds of the last pong
     */
    public long getLastPongTimeMillis() {
        return lastPongTimeMillis;
    }

    /**
     * Returns the high-resolution timestamp captured at session creation.
     * Use with {@code System.nanoTime()} to compute connection duration.
     *
     * @return the session creation time in nanoseconds (monotonic clock)
     */
    public long getCreatedAtNanos() {
        return createdAtNanos;
    }

    /**
     * Releases resources held by this session, including the retained HTTP request
     * and the optional cleanup action (e.g. semaphore release).
     *
     * <p>This method is idempotent -- only the first invocation performs cleanup.
     * Backed by a CAS on {@code released} to guarantee thread safety.
     */
    public void release() {
        // CAS ensures release runs exactly once even if called from multiple threads
        if (released.compareAndSet(false, true)) {
            if (cleanupAction != null) {
                cleanupAction.run();
            }
            ReferenceCountUtil.release(firstRequest);
        }
    }
}
