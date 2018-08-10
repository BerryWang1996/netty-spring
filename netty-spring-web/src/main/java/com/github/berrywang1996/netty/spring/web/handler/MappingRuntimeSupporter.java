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

package com.github.berrywang1996.netty.spring.web.handler;

import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.util.ClassUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
@Slf4j
@Getter
public class MappingRuntimeSupporter implements RuntimeSupporter {

    private final boolean initRequestMappingResolver =
            ClassUtil.isPresent(
                    "com.github.berrywang1996.netty.spring.web.mvc.handler.RequestMappingRuntimeSupporter");

    private final boolean initMessageMappingResolver =
            ClassUtil.isPresent(
                    "com.github.berrywang1996.netty.spring.web.socket.bind.handler.MessageMappingRuntimeSupporter");

    private final NettyServerStartupProperties startupProperties;

    private final Map<String, MappingResolver> mappingResolverMap;

    public MappingRuntimeSupporter(NettyServerStartupProperties startupProperties) {
        this.startupProperties = startupProperties;
        this.mappingResolverMap = Collections.unmodifiableMap(initMappingResolverMap());

    }

    @Override
    public Map<? extends String, ? extends MappingResolver> initMappingResolverMap() {
        HashMap<String, MappingResolver> mappingResolverMap = new HashMap<>();
        if (initRequestMappingResolver) {
            mappingResolverMap.putAll(initRequestMappingResolverMap());
        }
        if (initMessageMappingResolver) {
            mappingResolverMap.putAll(initMessageMappingResolverMap());
        }
        return mappingResolverMap;
    }

    private Map<? extends String, ? extends MappingResolver> initRequestMappingResolverMap() {
        RuntimeSupporter supporter = (RuntimeSupporter) ClassUtil.newInstance(
                "com.github.berrywang1996.netty.spring.web.mvc.handler.RequestMappingRuntimeSupporter");
        return supporter.initMappingResolverMap();
    }

    private Map<? extends String, ? extends MappingResolver> initMessageMappingResolverMap() {
        RuntimeSupporter supporter = (RuntimeSupporter) ClassUtil.newInstance(
                "com.github.berrywang1996.netty.spring.web.socket.bind.handler.MessageMappingRuntimeSupporter");
        return supporter.initMappingResolverMap();
    }

}
