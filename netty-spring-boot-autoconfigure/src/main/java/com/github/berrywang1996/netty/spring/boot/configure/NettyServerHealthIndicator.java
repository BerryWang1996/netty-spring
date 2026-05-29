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

import com.github.berrywang1996.netty.spring.web.context.HandlerRuntimeStats;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

/**
 * Spring Boot Actuator {@link org.springframework.boot.actuate.health.HealthIndicator}
 * for the Netty server.
 *
 * <p>Reports {@code UP} when the Netty server is running, and {@code DOWN} otherwise.
 * Includes details such as the listening port, handler thread pool state, and
 * admission control permit availability.
 *
 * <p>This indicator is automatically registered when Spring Boot Actuator is on the
 * classpath and the Netty server bootstrap bean is present.
 *
 * <p>Access via {@code GET /actuator/health} — the indicator appears as {@code nettyServer}.
 *
 * @author berrywang1996
 * @since V1.7.0
 * @see NettyActuatorConfigure
 */
public class NettyServerHealthIndicator extends AbstractHealthIndicator {

    private final NettyServerBootstrap bootstrap;

    /**
     * Creates a new health indicator that checks the given bootstrap's running state.
     *
     * @param bootstrap the Netty server bootstrap instance
     */
    public NettyServerHealthIndicator(NettyServerBootstrap bootstrap) {
        super("Netty server health check failed");
        this.bootstrap = bootstrap;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        if (bootstrap.isRunning()) {
            HandlerRuntimeStats stats = bootstrap.getHandlerRuntimeStats();
            builder.up()
                    .withDetail("port", bootstrap.getPort())
                    .withDetail("handlerPoolSize", stats.getExecutor().getPoolSize())
                    .withDetail("handlerActiveThreads", stats.getExecutor().getActiveCount())
                    .withDetail("handlerQueueSize", stats.getExecutor().getQueueSize())
                    .withDetail("permitsAvailable", stats.getAvailablePermits())
                    .withDetail("permitsLimit", stats.getPermitLimit());
        } else {
            builder.down().withDetail("reason", "Netty server is not running");
        }
    }
}
