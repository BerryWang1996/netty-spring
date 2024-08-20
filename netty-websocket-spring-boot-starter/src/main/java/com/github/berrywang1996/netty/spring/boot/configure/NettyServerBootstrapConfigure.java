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

package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import com.github.berrywang1996.netty.spring.web.websocket.support.MessageSenderSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
@Configuration
public class NettyServerBootstrapConfigure {

    @Autowired
    private NettyServerStartupPropertiesWrapper startupProperties;

    @Autowired
    private ApplicationContext applicationContext;

    @Bean
    @ConditionalOnMissingBean
    public NettyServerBootstrap nettyServer() {
        final NettyServerBootstrap nettyServerBootstrap = new NettyServerBootstrap(applicationContext);
        try {
            nettyServerBootstrap.start(startupProperties);
        } catch (Exception e) {
            log.error("Netty startup failed!", e);
            System.exit(1);
        }
        return nettyServerBootstrap;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(NettyServerBootstrap.class)
    public MessageSenderSupport messageSenderSupport(NettyServerBootstrap nettyServerBootstrap) {
        return new MessageSenderSupport(nettyServerBootstrap);
    }

}
