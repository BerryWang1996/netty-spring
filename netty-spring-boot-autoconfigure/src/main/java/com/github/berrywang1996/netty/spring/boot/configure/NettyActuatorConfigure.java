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
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot Actuator auto-configuration for the Netty server runtime.
 *
 * <p>Activated when {@code spring-boot-actuator} is on the classpath and a
 * {@link NettyServerBootstrap} bean is present. Registers a
 * {@link NettyServerHealthIndicator} that exposes server health at
 * {@code /actuator/health}.
 *
 * @author berrywang1996
 * @since V1.7.0
 */
@Slf4j
@Configuration
@ConditionalOnClass(HealthIndicator.class)
@AutoConfigureAfter(NettyServerBootstrapConfigure.class)
public class NettyActuatorConfigure {

    /**
     * Registers the Netty server health indicator.
     *
     * @param bootstrap the running Netty server bootstrap
     * @return a {@link NettyServerHealthIndicator} bound to the given bootstrap
     */
    @Bean
    @ConditionalOnBean(NettyServerBootstrap.class)
    public NettyServerHealthIndicator nettyServerHealthIndicator(NettyServerBootstrap bootstrap) {
        log.info("Registering Netty server health indicator with Spring Boot Actuator");
        return new NettyServerHealthIndicator(bootstrap);
    }
}
