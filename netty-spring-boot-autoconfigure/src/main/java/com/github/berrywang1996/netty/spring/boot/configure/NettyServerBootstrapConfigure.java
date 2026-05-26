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
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared bootstrap auto-configuration used by the concrete starters.
 *
 * @author berrywang1996
 * @since V1.1.0
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(NettyServerStartupPropertiesWrapper.class)
public class NettyServerBootstrapConfigure {

    private final NettyServerStartupPropertiesWrapper startupProperties;

    private final ApplicationContext applicationContext;

    public NettyServerBootstrapConfigure(NettyServerStartupPropertiesWrapper startupProperties,
                                         ApplicationContext applicationContext) {
        this.startupProperties = startupProperties;
        this.applicationContext = applicationContext;
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    public NettyServerBootstrap nettyServer() {
        final NettyServerBootstrap nettyServerBootstrap = new NettyServerBootstrap(applicationContext);
        try {
            nettyServerBootstrap.start(startupProperties);
        } catch (Exception e) {
            log.error("Netty startup failed!", e);
            throw new IllegalStateException("Netty startup failed.", e);
        }
        return nettyServerBootstrap;
    }

}
