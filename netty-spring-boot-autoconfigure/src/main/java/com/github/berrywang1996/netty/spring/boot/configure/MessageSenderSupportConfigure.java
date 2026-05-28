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
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.support.MessageSenderSupport;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot auto-configuration for WebSocket message sending support.
 *
 * <p>Registers a {@link MessageSenderSupport} bean that implements the
 * {@link MessageSender} interface, enabling application code to broadcast
 * or send targeted WebSocket messages. The bean is exposed under two names
 * ({@code messageSenderSupport} and {@code messageSender}) so that both
 * the concrete type and the preferred {@link MessageSender} interface can
 * be injected.
 *
 * <p>This configuration is activated only when:
 * <ul>
 *   <li>{@code MessageSenderSupport} is on the classpath (i.e. the WebSocket
 *       module is included)</li>
 *   <li>the property {@code server.netty.websocket.enable} is {@code true}
 *       (which is the default)</li>
 *   <li>no other {@link MessageSender} bean has already been defined</li>
 *   <li>the {@link NettyServerBootstrap} bean exists</li>
 * </ul>
 *
 * <p>The bean's {@code shutdown} method is called on application context
 * close, ensuring a graceful cleanup of WebSocket resources.
 *
 * @author berrywang1996
 * @since V1.1.0
 * @see MessageSender
 * @see MessageSenderSupport
 * @see NettyServerBootstrapConfigure
 */
@Configuration
@AutoConfigureAfter(NettyServerBootstrapConfigure.class)
@ConditionalOnClass(name = "com.github.berrywang1996.netty.spring.web.websocket.support.MessageSenderSupport")
@ConditionalOnProperty(prefix = "server.netty.websocket", name = "enable", havingValue = "true", matchIfMissing = true)
public class MessageSenderSupportConfigure {

    /**
     * Creates a {@link MessageSenderSupport} singleton that wraps the running
     * {@link NettyServerBootstrap} and exposes WebSocket send/broadcast operations.
     *
     * <p>The bean is registered with a {@code destroyMethod} of {@code "shutdown"}
     * so that pending messages are flushed and channels are closed when the
     * application context shuts down.
     *
     * @param nettyServerBootstrap the bootstrapped Netty server instance that
     *                             holds the WebSocket channel mappings
     * @return a new {@link MessageSenderSupport} backed by the given bootstrap
     */
    @Bean(name = {"messageSenderSupport", "messageSender"}, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(MessageSender.class)
    @ConditionalOnBean(NettyServerBootstrap.class)
    public MessageSenderSupport messageSenderSupport(NettyServerBootstrap nettyServerBootstrap) {
        return new MessageSenderSupport(nettyServerBootstrap);
    }

}
