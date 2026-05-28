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
 * Spring Boot auto-configuration for the Netty server bootstrap.
 *
 * <p>Creates and configures the {@link NettyServerBootstrap} bean using properties
 * bound from {@code server.netty.*} configuration keys. The bootstrap is registered
 * as a singleton bean with a destroy method that gracefully shuts down the server
 * when the application context is closed.
 *
 * <p>This is the foundational auto-configuration class that other Netty-related
 * configurations (such as {@link MessageSenderSupportConfigure} and
 * {@link NettyMicrometerConfigure}) depend on via {@code @AutoConfigureAfter}.
 *
 * <p>Configuration properties are bound through
 * {@link NettyServerStartupPropertiesWrapper}, which maps the {@code server.netty.*}
 * namespace to the internal {@code NettyServerStartupProperties} model.
 *
 * @author berrywang1996
 * @since V1.1.0
 * @see NettyServerStartupPropertiesWrapper
 * @see NettyServerBootstrap
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(NettyServerStartupPropertiesWrapper.class)
public class NettyServerBootstrapConfigure {

    /** Bound configuration properties from the {@code server.netty.*} namespace. */
    private final NettyServerStartupPropertiesWrapper startupProperties;

    /** The Spring application context, passed to the bootstrap for bean resolution. */
    private final ApplicationContext applicationContext;

    /**
     * Constructs this auto-configuration with the required dependencies injected
     * by the Spring container.
     *
     * @param startupProperties the Netty server startup properties bound from
     *                          {@code server.netty.*} configuration keys
     * @param applicationContext the Spring application context used by the bootstrap
     *                           to discover annotated handler beans
     */
    public NettyServerBootstrapConfigure(NettyServerStartupPropertiesWrapper startupProperties,
                                         ApplicationContext applicationContext) {
        this.startupProperties = startupProperties;
        this.applicationContext = applicationContext;
    }

    /**
     * Creates and starts the {@link NettyServerBootstrap} singleton bean.
     *
     * <p>The bootstrap is initialized with the current {@link ApplicationContext}
     * and then started with the bound configuration properties. If startup fails,
     * an {@link IllegalStateException} is thrown to prevent the application from
     * starting in a partially broken state.
     *
     * <p>The bean's {@code stop} method is registered as the destroy callback,
     * ensuring the Netty event loops and channels are shut down gracefully.
     *
     * @return a fully started {@link NettyServerBootstrap}
     * @throws IllegalStateException if the Netty server fails to start
     */
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
