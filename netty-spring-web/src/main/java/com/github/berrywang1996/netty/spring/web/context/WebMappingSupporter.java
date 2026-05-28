/*
 * Copyright 2018 berrywang1996
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.berrywang1996.netty.spring.web.context;

import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.util.ClassUtil;
import com.github.berrywang1996.netty.spring.web.util.DaemonThreadFactory;
import com.github.berrywang1996.netty.spring.web.util.MapUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central manager for MVC and WebSocket mapping resolvers, and the handler execution thread pool.
 *
 * <p>This class is the core runtime component responsible for:
 * <ul>
 *   <li>Scanning Spring application context for controllers and registering URL mapping resolvers</li>
 *   <li>Managing a bounded thread pool for asynchronous handler execution</li>
 *   <li>Providing semaphore-based admission control to prevent overload</li>
 *   <li>Collecting runtime statistics for handler execution, HTTP requests, and WebSocket sessions</li>
 * </ul>
 *
 * <p>It dynamically discovers mapping supporter implementations (MVC and WebSocket) via reflection,
 * allowing each module to be independently enabled or disabled through startup properties.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
@Getter
public class WebMappingSupporter implements MappingSupporter, HandlerSubmitter {

    /** Default core pool size based on available processors (minimum 2). */
    private static final int DEFAULT_HANDLER_CORE_POOL_SIZE =
            Math.max(2, Runtime.getRuntime().availableProcessors());

    /** Default max pool size: twice the available processors, at least as large as core size. */
    private static final int DEFAULT_HANDLER_MAX_POOL_SIZE =
            Math.max(DEFAULT_HANDLER_CORE_POOL_SIZE, Runtime.getRuntime().availableProcessors() * 2);

    /** Default keep-alive time in seconds for idle handler threads. */
    private static final long DEFAULT_HANDLER_KEEP_ALIVE_SECONDS = 5L;

    /** Default semaphore permit limit: twice the max pool size. */
    private static final int DEFAULT_HANDLER_PERMIT_LIMIT =
            DEFAULT_HANDLER_MAX_POOL_SIZE * 2;

    /** Fully qualified class names of mapping supporter implementations discovered via reflection. */
    private static final String[] DEFAULT_MAPPING_CLASSES =
            new String[]{
                    "com.github.berrywang1996.netty.spring.web.mvc.context.RequestMappingSupporter",
                    "com.github.berrywang1996.netty.spring.web.websocket.context.MessageMappingSupporter"};

    private final NettyServerStartupProperties startupProperties;

    /** Ant-style path matcher used for URL pattern matching against registered mappings. */
    private final PathMatcher pathMatcher;

    private final ApplicationContext applicationContext;

    /** Immutable map of URL patterns to their corresponding mapping resolvers. */
    private final Map<String, AbstractMappingResolver> mappingResolverMap;

    /** Thread pool for executing request handlers asynchronously off the Netty I/O threads. */
    private final ThreadPoolExecutor executor;

    /** Admission-control semaphore limiting concurrent handler executions to prevent overload. */
    private final Semaphore semaphore;

    /** Recorder for HTTP-level runtime metrics (static file stats, idle closes, etc.). */
    private final HttpRuntimeRecorder httpRuntimeRecorder;

    /** The initial permit limit captured at construction time, used for stats reporting. */
    private final int handlerPermitLimit;

    /** Counter for tasks rejected because no semaphore permits were available. */
    private final AtomicLong permitRejectedCount = new AtomicLong();

    /** Counter for tasks rejected by the thread pool executor itself (queue full). */
    private final AtomicLong executorRejectedCount = new AtomicLong();

    /** Subset of resolvers handling WebSocket endpoints, extracted during initialization. */
    private Map<String, AbstractMappingResolver> webSocketMappingResolverMap;

    /**
     * Creates a new supporter by scanning the application context for controller mappings
     * and initializing the handler thread pool and semaphore with default or configured values.
     *
     * @param startupProperties  the server startup configuration
     * @param applicationContext the Spring application context to scan for controllers
     */
    public WebMappingSupporter(NettyServerStartupProperties startupProperties,
                               ApplicationContext applicationContext) {
        this(startupProperties, applicationContext, null, null, null);
    }

    /**
     * Internal constructor allowing injection of pre-built resolver map, executor, and semaphore
     * (primarily used for testing).
     *
     * @param startupProperties  the server startup configuration
     * @param applicationContext the Spring application context
     * @param mappingResolverMap pre-built resolver map, or {@code null} to scan controllers
     * @param executor           pre-built thread pool, or {@code null} to create one from properties
     * @param semaphore          pre-built semaphore, or {@code null} to create one from properties
     */
    WebMappingSupporter(NettyServerStartupProperties startupProperties,
                        ApplicationContext applicationContext,
                        Map<String, AbstractMappingResolver> mappingResolverMap,
                        ThreadPoolExecutor executor,
                        Semaphore semaphore) {
        this.startupProperties = startupProperties;
        this.pathMatcher = new AntPathMatcher();
        this.applicationContext = applicationContext;
        // Use provided executor/semaphore or create new ones from startup properties
        this.executor = executor == null ? initHandlerExecutorThreadPool() : executor;
        this.semaphore = semaphore == null ? initHandlerSemaphore() : semaphore;
        this.httpRuntimeRecorder = new HttpRuntimeRecorder();
        this.handlerPermitLimit = Math.max(0, this.semaphore.availablePermits());
        // Either scan controllers or adapt the provided map
        this.mappingResolverMap = mappingResolverMap == null
                ? initMappingResolverMap(startupProperties, applicationContext)
                : adaptMappingResolverMap(mappingResolverMap);
        if (this.webSocketMappingResolverMap == null) {
            this.webSocketMappingResolverMap = Collections.emptyMap();
        }
        // Wire the runtime recorder and handler submitter into all resolvers
        configureHttpRuntimeRecorder(this.mappingResolverMap);
        configureHandlerSubmitter(this.mappingResolverMap);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Discovers and instantiates mapping supporter implementations (MVC and WebSocket)
     * via reflection, then merges their resolver maps. Duplicate URL patterns across
     * modules cause a {@link com.github.berrywang1996.netty.spring.web.exception.DuplicateKeyException}.
     */
    @Override
    public Map<String, AbstractMappingResolver> initMappingResolverMap(NettyServerStartupProperties startupProperties,
                                                                       ApplicationContext applicationContext) {
        Map<String, AbstractMappingResolver> mappingResolverMap = new HashMap<>();
        for (String mappingClass : DEFAULT_MAPPING_CLASSES) {
            // Skip disabled modules (MVC or WebSocket can be individually toggled off)
            if (!isMappingSupporterEnabled(mappingClass)) {
                log.debug("Skip mapping supporter {} because it is disabled by startup properties.", mappingClass);
                continue;
            }
            // Only instantiate if the class is on the classpath (optional module dependency)
            if (ClassUtil.isPresent(mappingClass)) {
                log.debug("Init mapping supporter {}", mappingClass);
                MappingSupporter supporter = (MappingSupporter) ClassUtil.newInstance(mappingClass);
                Map<String, ? extends AbstractMappingResolver> resolverMap =
                        supporter.initMappingResolverMap(startupProperties, applicationContext);
                // Capture WebSocket resolvers separately for session tracking
                if ("com.github.berrywang1996.netty.spring.web.websocket.context.MessageMappingSupporter".equals(mappingClass)) {
                    this.webSocketMappingResolverMap = Collections.unmodifiableMap(resolverMap);
                }
                // Fail fast on duplicate URL patterns across different modules
                MapUtil.checkDuplicateKey(mappingResolverMap, resolverMap);
                mappingResolverMap.putAll(resolverMap);
            }
        }
        if (this.webSocketMappingResolverMap == null) {
            this.webSocketMappingResolverMap = Collections.emptyMap();
        }
        if (mappingResolverMap.size() == 0) {
            log.warn("No mapping resolvers are mapped.");
        }
        // Inject the shared path matcher into all resolvers for Ant-style URL matching
        for (AbstractMappingResolver resolver : mappingResolverMap.values()) {
            resolver.setPathMatcher(this.pathMatcher);
        }
        return Collections.unmodifiableMap(mappingResolverMap);
    }

    /**
     * Initializes the handler thread pool executor from WebSocket startup properties.
     * Uses daemon threads so the pool does not prevent JVM shutdown.
     */
    private ThreadPoolExecutor initHandlerExecutorThreadPool() {
        NettyServerStartupProperties.WebSocket webSocketProperties = getWebSocketProperties();
        int corePoolSize = resolveHandlerCorePoolSize(webSocketProperties);
        int maxPoolSize = Math.max(corePoolSize, resolveHandlerMaxPoolSize(webSocketProperties));
        long keepAliveTime = resolveHandlerKeepAliveTime(webSocketProperties);
        int queueCapacity = resolveHandlerQueueCapacity(webSocketProperties);
        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveTime,
                TimeUnit.SECONDS,
                initHandlerQueue(queueCapacity),
                new DaemonThreadFactory("handler"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Initializes the admission-control semaphore with the configured or default permit limit.
     */
    private Semaphore initHandlerSemaphore() {
        return new Semaphore(resolveHandlerPermitLimit(getWebSocketProperties()));
    }

    /**
     * Adapts a pre-built resolver map by copying it, injecting the path matcher,
     * and wrapping it as unmodifiable.
     */
    private Map<String, AbstractMappingResolver> adaptMappingResolverMap(Map<String, AbstractMappingResolver> mappingResolverMap) {
        Map<String, AbstractMappingResolver> copiedResolverMap = new HashMap<>(mappingResolverMap);
        if (copiedResolverMap.size() == 0) {
            log.warn("No mapping resolvers are mapped.");
        }
        for (AbstractMappingResolver resolver : copiedResolverMap.values()) {
            resolver.setPathMatcher(this.pathMatcher);
        }
        return Collections.unmodifiableMap(copiedResolverMap);
    }

    /**
     * Injects this supporter as the handler submitter into all resolvers that implement
     * {@link HandlerSubmitterAware}, enabling them to submit tasks to the shared thread pool.
     */
    private void configureHandlerSubmitter(Map<String, AbstractMappingResolver> resolverMap) {
        for (AbstractMappingResolver resolver : resolverMap.values()) {
            if (resolver instanceof HandlerSubmitterAware) {
                ((HandlerSubmitterAware) resolver).setHandlerSubmitter(this);
            }
        }
    }

    /**
     * Injects the shared HTTP runtime recorder into all resolvers for metrics collection.
     */
    private void configureHttpRuntimeRecorder(Map<String, AbstractMappingResolver> resolverMap) {
        for (AbstractMappingResolver resolver : resolverMap.values()) {
            resolver.setHttpRuntimeRecorder(this.httpRuntimeRecorder);
        }
    }

    /**
     * Checks whether a given mapping supporter class is enabled based on startup properties.
     */
    private boolean isMappingSupporterEnabled(String mappingClass) {
        if ("com.github.berrywang1996.netty.spring.web.mvc.context.RequestMappingSupporter".equals(mappingClass)) {
            return isMvcEnabled();
        }
        if ("com.github.berrywang1996.netty.spring.web.websocket.context.MessageMappingSupporter".equals(mappingClass)) {
            return isWebSocketEnabled();
        }
        return true;
    }

    /** Returns true if MVC handling is enabled (defaults to true when properties are absent). */
    private boolean isMvcEnabled() {
        return this.startupProperties == null
                || this.startupProperties.getMvc() == null
                || this.startupProperties.getMvc().isEnable();
    }

    /** Returns true if WebSocket handling is enabled (defaults to true when properties are absent). */
    private boolean isWebSocketEnabled() {
        return this.startupProperties == null
                || this.startupProperties.getWebSocket() == null
                || this.startupProperties.getWebSocket().isEnable();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Implements two-level admission control: first acquires a semaphore permit to limit
     * total concurrency, then submits the task to the thread pool executor. If either
     * level rejects the task, a {@link RejectedExecutionException} is thrown with
     * diagnostic runtime stats to aid troubleshooting.
     *
     * @param runnable the handler task to execute asynchronously
     * @throws RejectedExecutionException if no semaphore permits are available or the executor rejects the task
     */
    @Override
    public void submitHandle(final Runnable runnable) {
        // First level: semaphore-based admission control
        if (!this.semaphore.tryAcquire()) {
            this.permitRejectedCount.incrementAndGet();
            throw new RejectedExecutionException("No handler permits available. " + getRuntimeStats()
                    + " Action: increase server.netty.websocket.handler-permit-limit, reduce handler latency, "
                    + "or add client-side retry/backoff.");
        }
        try {
            // Second level: thread pool execution with guaranteed semaphore release
            this.executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        runnable.run();
                    } catch (Exception e) {
                        log.error("Submit handle error.", e);
                    } finally {
                        // Always release the permit after handler completes
                        semaphore.release();
                    }
                }
            });
        } catch (RuntimeException e) {
            // Release the permit since the task was never executed
            this.semaphore.release();
            if (e instanceof RejectedExecutionException) {
                this.executorRejectedCount.incrementAndGet();
                throw new RejectedExecutionException("Handler executor rejected task. " + getRuntimeStats()
                        + " Action: tune server.netty.websocket.handler-core-pool-size, "
                        + "handler-max-pool-size or handler-queue-capacity.", e);
            }
            throw e;
        }
    }

    /**
     * Returns a snapshot of the handler thread pool and semaphore runtime statistics.
     *
     * @return a {@link HandlerRuntimeStats} capturing the current executor state, permit usage, and rejection counts
     */
    public HandlerRuntimeStats getRuntimeStats() {
        return new HandlerRuntimeStats(
                ExecutorRuntimeInfo.from(this.executor),
                this.handlerPermitLimit,
                this.semaphore.availablePermits(),
                this.permitRejectedCount.get(),
                this.executorRejectedCount.get());
    }

    /**
     * Returns a snapshot of HTTP-level runtime statistics (static file events, idle closes, etc.).
     *
     * @return the current {@link HttpRuntimeStats}
     */
    public HttpRuntimeStats getHttpRuntimeStats() {
        return this.httpRuntimeRecorder.getRuntimeStats();
    }

    /**
     * Returns a snapshot of WebSocket runtime statistics aggregated across all WebSocket resolvers.
     *
     * @return the current {@link WebSocketRuntimeStats}, or an empty instance if no WebSocket resolvers exist
     */
    public WebSocketRuntimeStats getWebSocketRuntimeStats() {
        if (this.webSocketMappingResolverMap == null || this.webSocketMappingResolverMap.isEmpty()) {
            return WebSocketRuntimeStats.empty();
        }
        int activeSessionCount = 0;
        Map<String, Object> aggregatedEventCounters = null;
        // Aggregate session counts and event counters across all WebSocket resolvers
        for (AbstractMappingResolver resolver : this.webSocketMappingResolverMap.values()) {
            activeSessionCount += Math.max(0, resolver.getActiveSessionCount());
            Map<String, Object> resolverCounters = resolver.getEventCounters();
            if (!resolverCounters.isEmpty()) {
                aggregatedEventCounters = resolverCounters;
            }
        }
        return new WebSocketRuntimeStats(this.webSocketMappingResolverMap.size(), activeSessionCount,
                aggregatedEventCounters);
    }

    /**
     * Gracefully shuts down all mapping resolvers and the handler thread pool.
     *
     * <p>Resolvers are shut down first to stop accepting new work, then the executor
     * is given 5 seconds to complete in-flight tasks before being forcibly terminated.
     */
    public void shutdown() {
        shutdownResolvers();
        if (this.executor.isShutdown()) {
            return;
        }
        this.executor.shutdown();
        try {
            // Wait up to 5 seconds for in-flight tasks to complete
            if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Shuts down each mapping resolver individually, logging but not propagating failures.
     */
    private void shutdownResolvers() {
        for (AbstractMappingResolver resolver : this.mappingResolverMap.values()) {
            try {
                resolver.shutdown();
            } catch (Exception e) {
                log.warn("Shutdown mapping resolver failed. resolver={}", resolver.getClass().getName(), e);
            }
        }
    }

    /** Safely retrieves WebSocket properties, returning null if startup properties are absent. */
    private NettyServerStartupProperties.WebSocket getWebSocketProperties() {
        return this.startupProperties == null ? null : this.startupProperties.getWebSocket();
    }

    /**
     * Creates the appropriate blocking queue for the handler thread pool.
     * Uses a {@link SynchronousQueue} for zero capacity (direct handoff) or an
     * {@link ArrayBlockingQueue} for bounded capacity.
     */
    private java.util.concurrent.BlockingQueue<Runnable> initHandlerQueue(int queueCapacity) {
        if (queueCapacity <= 0) {
            return new SynchronousQueue<Runnable>();
        }
        return new ArrayBlockingQueue<Runnable>(queueCapacity);
    }

    /**
     * Resolves the handler core pool size from properties, falling back to the default.
     */
    private int resolveHandlerCorePoolSize(NettyServerStartupProperties.WebSocket webSocketProperties) {
        if (webSocketProperties == null || webSocketProperties.getHandlerCorePoolSize() <= 0) {
            return DEFAULT_HANDLER_CORE_POOL_SIZE;
        }
        return webSocketProperties.getHandlerCorePoolSize();
    }

    /**
     * Resolves the handler max pool size from properties, falling back to the default.
     */
    private int resolveHandlerMaxPoolSize(NettyServerStartupProperties.WebSocket webSocketProperties) {
        if (webSocketProperties == null || webSocketProperties.getHandlerMaxPoolSize() <= 0) {
            return DEFAULT_HANDLER_MAX_POOL_SIZE;
        }
        return webSocketProperties.getHandlerMaxPoolSize();
    }

    /**
     * Resolves the handler keep-alive time in seconds from properties, falling back to the default.
     */
    private long resolveHandlerKeepAliveTime(NettyServerStartupProperties.WebSocket webSocketProperties) {
        if (webSocketProperties == null || webSocketProperties.getHandlerKeepAliveTime() <= 0L) {
            return DEFAULT_HANDLER_KEEP_ALIVE_SECONDS;
        }
        return webSocketProperties.getHandlerKeepAliveTime();
    }

    /**
     * Resolves the handler queue capacity from properties. Returns 0 (direct handoff) if not configured.
     */
    private int resolveHandlerQueueCapacity(NettyServerStartupProperties.WebSocket webSocketProperties) {
        if (webSocketProperties == null || webSocketProperties.getHandlerQueueCapacity() < 0) {
            return 0;
        }
        return webSocketProperties.getHandlerQueueCapacity();
    }

    /**
     * Resolves the semaphore permit limit from properties, falling back to the default.
     */
    private int resolveHandlerPermitLimit(NettyServerStartupProperties.WebSocket webSocketProperties) {
        if (webSocketProperties == null || webSocketProperties.getHandlerPermitLimit() <= 0) {
            return DEFAULT_HANDLER_PERMIT_LIMIT;
        }
        return webSocketProperties.getHandlerPermitLimit();
    }

}
