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
import org.springframework.context.ApplicationContext;

import java.util.Map;

/**
 * Strategy interface for scanning the Spring application context and producing
 * a map of URL patterns to {@link AbstractMappingResolver} instances.
 *
 * <p>Each protocol module (MVC, WebSocket) provides its own implementation that
 * discovers annotated controllers or message handlers and registers the
 * corresponding mapping resolvers. The {@link WebMappingSupporter} aggregates
 * results from all discovered implementations into a single routing table.
 *
 * @param <T> the concrete mapping resolver type produced by this supporter
 * @author berrywang1996
 * @since V1.0.0
 * @see WebMappingSupporter
 */
public interface MappingSupporter<T extends AbstractMappingResolver> {

    /**
     * Scans the application context and builds a map of URL patterns to mapping resolvers.
     *
     * @param startupProperties  the server startup configuration properties
     * @param applicationContext the Spring application context to scan for annotated beans
     * @return a map of URL patterns to their corresponding mapping resolvers
     */
    Map<String, T> initMappingResolverMap(NettyServerStartupProperties startupProperties,
                                          ApplicationContext applicationContext);

}
