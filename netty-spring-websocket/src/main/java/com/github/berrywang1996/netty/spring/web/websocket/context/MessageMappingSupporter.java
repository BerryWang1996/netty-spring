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

package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.context.MappingSupporter;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.Map;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
public class MessageMappingSupporter implements MappingSupporter<MessageMappingResolver> {

    @Override
    public Map<String, MessageMappingResolver> initMappingResolverMap(NettyServerStartupProperties startupProperties,
                                                                      ApplicationContext applicationContext) {

        HashMap<String, MessageMappingResolver> resolverMap = new HashMap<>();


        return resolverMap;
    }

}
