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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer auto-configuration for the Netty server runtime.
 *
 * <p>This configuration is activated when both {@code micrometer-core} and the
 * Netty server bootstrap are available. It registers {@link MeterBinder} beans
 * that bridge internal runtime counters to the application's {@link MeterRegistry}.
 *
 * <p>Metrics are available at the standard Actuator endpoints ({@code /actuator/metrics},
 * {@code /actuator/prometheus}, etc.) when Spring Boot Actuator is on the classpath.
 *
 * @author berrywang1996
 * @since V1.3.0
 */
@Slf4j
@Configuration
@ConditionalOnClass(MeterRegistry.class)
@AutoConfigureAfter(NettyServerBootstrapConfigure.class)
public class NettyMicrometerConfigure {

    /**
     * HTTP runtime metrics binder. Always registered when Micrometer and the
     * Netty bootstrap are present — HTTP counters come from {@code netty-spring-web}
     * which is a required dependency.
     */
    @Bean
    @ConditionalOnBean({NettyServerBootstrap.class, MeterRegistry.class})
    public NettyHttpMeterBinder nettyHttpMeterBinder(NettyServerBootstrap bootstrap) {
        log.info("Registering Netty HTTP runtime metrics with Micrometer");
        return new NettyHttpMeterBinder(bootstrap);
    }

    /**
     * WebSocket metrics binder. Only registered when the websocket module is
     * on the classpath and WebSocket support is enabled.
     */
    @Configuration
    @ConditionalOnClass(name = "com.github.berrywang1996.netty.spring.web.websocket.context.WebSocketEventRecorder")
    @ConditionalOnProperty(prefix = "server.netty.websocket", name = "enable", havingValue = "true", matchIfMissing = true)
    static class WebSocketMetricsConfiguration {

        @Bean
        @ConditionalOnBean({NettyServerBootstrap.class, MeterRegistry.class})
        public NettyWebSocketMeterBinder nettyWebSocketMeterBinder(NettyServerBootstrap bootstrap) {
            log.info("Registering Netty WebSocket metrics with Micrometer");
            return new NettyWebSocketMeterBinder(bootstrap);
        }
    }
}
