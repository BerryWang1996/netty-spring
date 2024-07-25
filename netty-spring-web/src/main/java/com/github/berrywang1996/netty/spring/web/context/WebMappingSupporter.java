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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
@Getter
public class WebMappingSupporter implements MappingSupporter {

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

    private Map<String, AbstractMappingResolver> webSocketMappingtResolverMap;

    public WebMappingSupporter(NettyServerStartupProperties startupProperties,
                               ApplicationContext applicationContext) {
        this.startupProperties = startupProperties;
        this.pathMatcher = new AntPathMatcher();
        this.applicationContext = applicationContext;
        this.mappingResolverMap = initMappingResolverMap(startupProperties, applicationContext);
        this.executor = initHandlerExecutorThreadPool();
        this.semaphore = initHandlerSemaphore();

    }

    @Override
    public Map<String, AbstractMappingResolver> initMappingResolverMap(NettyServerStartupProperties startupProperties,
                                                                       ApplicationContext applicationContext) {
        Map<String, AbstractMappingResolver> mappingResolverMap = new HashMap<>();
        for (String mappingClass : DEFAULT_MAPPING_CLASSES) {
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
        if (mappingResolverMap.size() == 0) {
            log.warn("No mapping resolvers are mapped.");
        }
        for (AbstractMappingResolver resolver : mappingResolverMap.values()) {
            resolver.setPathMatcher(this.pathMatcher);
        }
        return Collections.unmodifiableMap(mappingResolverMap);
    }

    private ThreadPoolExecutor initHandlerExecutorThreadPool() {
        // TODO 通过配置对象进行配置
        return new ThreadPoolExecutor(
                Runtime.getRuntime().availableProcessors() * 100 * 2,
                Runtime.getRuntime().availableProcessors() * 100 * 3,
                5L,
                TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                new DaemonThreadFactory("handler"));
    }

    private Semaphore initHandlerSemaphore() {
        // TODO 通过配置对象进行配置
        return new Semaphore(Runtime.getRuntime().availableProcessors() * 100 * 2);
    }

    public void submitHandle(final Runnable runnable) {
        try {
            this.semaphore.acquire();
            this.executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        runnable.run();
                    } catch (Exception e) {
                        log.error("Submit handle error.", e);
                    }
                }
            });
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            this.semaphore.release();
        }
    }

}
