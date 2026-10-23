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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot Actuator auto-configuration for WebSocket cluster mode.
 *
 * <p>Activated when {@code spring-boot-health} is on the classpath, cluster mode is
 * enabled, and the cluster beans are present. Registers a {@link ClusterHealthIndicator}
 * so operators can observe and alert on node state (DEGRADED/RESYNC) at
 * {@code /actuator/health}.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
@Slf4j
@AutoConfiguration(after = NettyWebSocketClusterConfigure.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "server.netty.websocket.cluster", name = "enable", havingValue = "true")
public class NettyClusterActuatorConfigure {

    @Bean
    @ConditionalOnBean(ClusterNodeManager.class)
    @ConditionalOnMissingBean(ClusterHealthIndicator.class)
    public ClusterHealthIndicator nettyClusterHealthIndicator(ClusterNodeManager nodeManager,
                                                              ClusterBroker broker,
                                                              ClusterMessageSender sender) {
        log.info("Registering WebSocket cluster health indicator with Spring Boot Actuator");
        return new ClusterHealthIndicator(nodeManager, broker, sender);
    }
}
