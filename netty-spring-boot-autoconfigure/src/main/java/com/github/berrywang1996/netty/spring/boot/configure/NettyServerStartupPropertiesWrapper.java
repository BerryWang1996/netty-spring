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

import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot {@link ConfigurationProperties @ConfigurationProperties} wrapper that
 * binds the {@code server.netty.*} configuration namespace to the internal
 * {@link NettyServerStartupProperties} model.
 *
 * <p>This thin subclass exists solely to attach the
 * {@code @ConfigurationProperties(prefix = "server.netty")} annotation, keeping the
 * core properties class in the {@code netty-spring-web} module free of any Spring Boot
 * dependency. All actual property fields and their defaults are inherited from
 * {@link NettyServerStartupProperties}.
 *
 * <p>Typical properties include:
 * <ul>
 *   <li>{@code server.netty.port} - the port the Netty server listens on</li>
 *   <li>{@code server.netty.websocket.enable} - whether WebSocket support is enabled</li>
 *   <li>{@code server.netty.management.enable} - whether management endpoints are exposed</li>
 * </ul>
 *
 * @author berrywang1996
 * @since V1.1.0
 * @see NettyServerStartupProperties
 * @see NettyServerBootstrapConfigure
 */
@ConfigurationProperties(prefix = "server.netty")
public class NettyServerStartupPropertiesWrapper extends NettyServerStartupProperties {
}
