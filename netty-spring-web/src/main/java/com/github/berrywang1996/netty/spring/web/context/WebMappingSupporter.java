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
import com.github.berrywang1996.netty.spring.web.util.MapUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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

    private final ApplicationContext applicationContext;

    private final Map<String, AbstractMappingResolver> mappingResolverMap;

    public WebMappingSupporter(NettyServerStartupProperties startupProperties,
                               ApplicationContext applicationContext) {
        this.startupProperties = startupProperties;
        this.applicationContext = applicationContext;
        this.mappingResolverMap =
                Collections.unmodifiableMap(initMappingResolverMap(startupProperties, applicationContext));
    }

    @Override
    public Map<String, ? extends AbstractMappingResolver> initMappingResolverMap(NettyServerStartupProperties startupProperties, ApplicationContext applicationContext) {
        Map<String, AbstractMappingResolver> mappingResolverMap = new HashMap<>();
        for (String mappingClass : DEFAULT_MAPPING_CLASSES) {
            if (ClassUtil.isPresent(mappingClass)) {
                log.debug("Init mapping supporter {}", mappingClass);
                MappingSupporter supporter = (MappingSupporter) ClassUtil.newInstance(mappingClass);
                Map<String, ? extends AbstractMappingResolver> resolverMap =
                        supporter.initMappingResolverMap(startupProperties, applicationContext);
                MapUtil.checkDuplicateKey(mappingResolverMap, resolverMap);
                mappingResolverMap.putAll(resolverMap);
            }
        }
        if (mappingResolverMap.size() == 0) {
            log.warn("No mapping resolvers are mapped.");
        }
        return mappingResolverMap;
    }

}
