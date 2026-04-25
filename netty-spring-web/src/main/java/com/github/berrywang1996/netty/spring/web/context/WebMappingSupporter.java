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
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
@Getter
public class WebMappingSupporter implements MappingSupporter, HandlerSubmitter {

    private static final int DEFAULT_HANDLER_CORE_POOL_SIZE =
            Math.max(2, Runtime.getRuntime().availableProcessors());

    private static final int DEFAULT_HANDLER_MAX_POOL_SIZE =
            Math.max(DEFAULT_HANDLER_CORE_POOL_SIZE, Runtime.getRuntime().availableProcessors() * 2);

    private static final long DEFAULT_HANDLER_KEEP_ALIVE_SECONDS = 5L;

    private static final int DEFAULT_HANDLER_PERMIT_LIMIT =
            DEFAULT_HANDLER_MAX_POOL_SIZE * 2;

    private static final String[] DEFAULT_MAPPING_CLASSES =
            new String[]{
                    "com.github.berrywang1996.netty.spring.web.mvc.context.RequestMappingSupporter",
                    "com.github.berrywang1996.netty.spring.web.websocket.context.MessageMappingSupporter"};

    private final NettyServerStartupProperties startupProperties;

    private final PathMatcher pathMatcher;

    private final ApplicationContext applicationContext;

    private final Map<String, AbstractMappingResolver> mappingResolverMap;

    private final ThreadPoolExecutor executor;

    private final Semaphore semaphore;

    private final HttpRuntimeRecorder httpRuntimeRecorder;

    private final int handlerPermitLimit;

    private final AtomicLong permitRejectedCount = new AtomicLong();

    private final AtomicLong executorRejectedCount = new AtomicLong();

    private Map<String, AbstractMappingResolver> webSocketMappingtResolverMap;

    public WebMappingSupporter(NettyServerStartupProperties startupProperties,
                               ApplicationContext applicationContext) {
        this(startupProperties, applicationContext, null, null, null);
    }

    WebMappingSupporter(NettyServerStartupProperties startupProperties,
                        ApplicationContext applicationContext,
                        Map<String, AbstractMappingResolver> mappingResolverMap,
                        ThreadPoolExecutor executor,
                        Semaphore semaphore) {
        this.startupProperties = startupProperties;
        this.pathMatcher = new AntPathMatcher();
        this.applicationContext = applicationContext;
        this.executor = executor == null ? initHandlerExecutorThreadPool() : executor;
        this.semaphore = semaphore == null ? initHandlerSemaphore() : semaphore;
        this.httpRuntimeRecorder = new HttpRuntimeRecorder();
        this.handlerPermitLimit = Math.max(0, this.semaphore.availablePermits());
        this.mappingResolverMap = mappingResolverMap == null
                ? initMappingResolverMap(startupProperties, applicationContext)
                : adaptMappingResolverMap(mappingResolverMap);
        configureHttpRuntimeRecorder(this.mappingResolverMap);
        configureHandlerSubmitter(this.mappingResolverMap);
    }

    @Override
    public Map<String, AbstractMappingResolver> initMappingResolverMap(NettyServerStartupProperties startupProperties,
                                                                       ApplicationContext applicationContext) {
        Map<String, AbstractMappingResolver> mappingResolverMap = new HashMap<>();
        for (String mappingClass : DEFAULT_MAPPING_CLASSES) {
            if (!isMappingSupporterEnabled(mappingClass)) {
                log.debug("Skip mapping supporter {} because it is disabled by startup properties.", mappingClass);
                continue;
            }
            if (ClassUtil.isPresent(mappingClass)) {
                log.debug("Init mapping supporter {}", mappingClass);
                MappingSupporter supporter = (MappingSupporter) ClassUtil.newInstance(mappingClass);
                Map<String, ? extends AbstractMappingResolver> resolverMap =
                        supporter.initMappingResolverMap(startupProperties, applicationContext);
                // if websocket
                if ("com.github.berrywang1996.netty.spring.web.websocket.context.MessageMappingSupporter".equals(mappingClass)) {
                    this.webSocketMappingtResolverMap = Collections.unmodifiableMap(resolverMap);
                }
                MapUtil.checkDuplicateKey(mappingResolverMap, resolverMap);
                mappingResolverMap.putAll(resolverMap);
            }
        }
        if (this.webSocketMappingtResolverMap == null) {
            this.webSocketMappingtResolverMap = Collections.emptyMap();
        }
        if (mappingResolverMap.size() == 0) {
            log.warn("No mapping resolvers are mapped.");
        }
        for (AbstractMappingResolver resolver : mappingResolverMap.values()) {
            resolver.setPathMatcher(this.pathMatcher);
        }
        return Collections.unmodifiableMap(mappingResolverMap);
    }

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

    private Semaphore initHandlerSemaphore() {
        return new Semaphore(resolveHandlerPermitLimit(getWebSocketProperties()));
    }

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

    private void configureHandlerSubmitter(Map<String, AbstractMappingResolver> resolverMap) {
        for (AbstractMappingResolver resolver : resolverMap.values()) {
            if (resolver instanceof HandlerSubmitterAware) {
                ((HandlerSubmitterAware) resolver).setHandlerSubmitter(this);
            }
        }
    }

    private void configureHttpRuntimeRecorder(Map<String, AbstractMappingResolver> resolverMap) {
        for (AbstractMappingResolver resolver : resolverMap.values()) {
            resolver.setHttpRuntimeRecorder(this.httpRuntimeRecorder);
        }
    }

    private boolean isMappingSupporterEnabled(String mappingClass) {
        if ("com.github.berrywang1996.netty.spring.web.mvc.context.RequestMappingSupporter".equals(mappingClass)) {
            return isMvcEnabled();
        }
        if ("com.github.berrywang1996.netty.spring.web.websocket.context.MessageMappingSupporter".equals(mappingClass)) {
            return isWebSocketEnabled();
        }
        return true;
    }

    private boolean isMvcEnabled() {
        return this.startupProperties == null
                || this.startupProperties.getMvc() == null
                || this.startupProperties.getMvc().isEnable();
    }

    private boolean isWebSocketEnabled() {
        return this.startupProperties == null
                || this.startupProperties.getWebSocket() == null
                || this.startupProperties.getWebSocket().isEnable();
    }

    @Override
    public void submitHandle(final Runnable runnable) {
        if (!this.semaphore.tryAcquire()) {
            this.permitRejectedCount.incrementAndGet();
            throw new RejectedExecutionException("No handler permits available. " + getRuntimeStats());
        }
        try {
            this.executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        runnable.run();
                    } catch (Exception e) {
                        log.error("Submit handle error.", e);
                    } finally {
                        semaphore.release();
                    }
                }
            });
        } catch (RuntimeException e) {
            this.semaphore.release();
            if (e instanceof RejectedExecutionException) {
                this.executorRejectedCount.incrementAndGet();
                throw new RejectedExecutionException("Handler executor rejected task. " + getRuntimeStats(), e);
            }
            throw e;
        }
    }

    public HandlerRuntimeStats getRuntimeStats() {
        return new HandlerRuntimeStats(
                ExecutorRuntimeInfo.from(this.executor),
                this.handlerPermitLimit,
                this.semaphore.availablePermits(),
                this.permitRejectedCount.get(),
                this.executorRejectedCount.get());
    }

    public HttpRuntimeStats getHttpRuntimeStats() {
        return this.httpRuntimeRecorder.getRuntimeStats();
    }

    public void shutdown() {
        shutdownResolvers();
        if (this.executor.isShutdown()) {
            return;
        }
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void shutdownResolvers() {
        for (AbstractMappingResolver resolver : this.mappingResolverMap.values()) {
            try {
                resolver.shutdown();
            } catch (Exception e) {
                log.warn("Shutdown mapping resolver failed. resolver={}", resolver.getClass().getName(), e);
            }
        }
    }

    private NettyServerStartupProperties.WebSocket getWebSocketProperties() {
        return this.startupProperties == null ? null : this.startupProperties.getWebSocket();
    }

    private java.util.concurrent.BlockingQueue<Runnable> initHandlerQueue(int queueCapacity) {
        if (queueCapacity <= 0) {
            return new SynchronousQueue<Runnable>();
        }
        return new ArrayBlockingQueue<Runnable>(queueCapacity);
    }

    private int resolveHandlerCorePoolSize(NettyServerStartupProperties.WebSocket webSocketProperties) {
        if (webSocketProperties == null || webSocketProperties.getHandlerCorePoolSize() <= 0) {
            return DEFAULT_HANDLER_CORE_POOL_SIZE;
        }
        return webSocketProperties.getHandlerCorePoolSize();
    }

    private int resolveHandlerMaxPoolSize(NettyServerStartupProperties.WebSocket webSocketProperties) {
        if (webSocketProperties == null || webSocketProperties.getHandlerMaxPoolSize() <= 0) {
            return DEFAULT_HANDLER_MAX_POOL_SIZE;
        }
        return webSocketProperties.getHandlerMaxPoolSize();
    }

    private long resolveHandlerKeepAliveTime(NettyServerStartupProperties.WebSocket webSocketProperties) {
        if (webSocketProperties == null || webSocketProperties.getHandlerKeepAliveTime() <= 0L) {
            return DEFAULT_HANDLER_KEEP_ALIVE_SECONDS;
        }
        return webSocketProperties.getHandlerKeepAliveTime();
    }

    private int resolveHandlerQueueCapacity(NettyServerStartupProperties.WebSocket webSocketProperties) {
        if (webSocketProperties == null || webSocketProperties.getHandlerQueueCapacity() < 0) {
            return 0;
        }
        return webSocketProperties.getHandlerQueueCapacity();
    }

    private int resolveHandlerPermitLimit(NettyServerStartupProperties.WebSocket webSocketProperties) {
        if (webSocketProperties == null || webSocketProperties.getHandlerPermitLimit() <= 0) {
            return DEFAULT_HANDLER_PERMIT_LIMIT;
        }
        return webSocketProperties.getHandlerPermitLimit();
    }

}
