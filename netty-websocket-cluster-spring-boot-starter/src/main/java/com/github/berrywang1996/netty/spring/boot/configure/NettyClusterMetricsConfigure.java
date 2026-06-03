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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer auto-configuration for WebSocket cluster mode. Activated when {@code micrometer-core}
 * is on the classpath, cluster mode is enabled, and the cluster beans are present. Registers a
 * {@link NettyClusterMeterBinder} that bridges cluster runtime counters + node/broker state to the
 * application's {@link MeterRegistry} as {@code netty.cluster.*} meters.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
@Configuration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "server.netty.websocket.cluster", name = "enable", havingValue = "true")
@AutoConfigureAfter(NettyWebSocketClusterConfigure.class)
public class NettyClusterMetricsConfigure {

    @Bean
    @ConditionalOnBean({MeterRegistry.class, ClusterMessageSender.class})
    @ConditionalOnMissingBean(NettyClusterMeterBinder.class)
    public NettyClusterMeterBinder nettyClusterMeterBinder(ClusterMessageSender sender,
                                                           ClusterNodeManager nodeManager,
                                                           ClusterBroker broker,
                                                           MessageAuthenticator authenticator) {
        log.info("Registering WebSocket cluster metrics with Micrometer");
        return new NettyClusterMeterBinder(sender, nodeManager, broker, authenticator);
    }
}
