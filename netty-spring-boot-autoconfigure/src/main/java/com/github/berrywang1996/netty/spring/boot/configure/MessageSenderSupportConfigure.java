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
 * Shared websocket sender auto-configuration used by starters that bring websocket support.
 * The same bean is exposed as both {@code messageSenderSupport} and {@code messageSender}
 * so business code can prefer the {@code MessageSender} interface while keeping the
 * legacy concrete type available.
 *
 * @author berrywang1996
 * @since V1.1.0
 */
@Configuration
@AutoConfigureAfter(NettyServerBootstrapConfigure.class)
@ConditionalOnClass(name = "com.github.berrywang1996.netty.spring.web.websocket.support.MessageSenderSupport")
@ConditionalOnProperty(prefix = "server.netty.websocket", name = "enable", havingValue = "true", matchIfMissing = true)
public class MessageSenderSupportConfigure {

    @Bean(name = {"messageSenderSupport", "messageSender"}, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(MessageSender.class)
    @ConditionalOnBean(NettyServerBootstrap.class)
    public MessageSenderSupport messageSenderSupport(NettyServerBootstrap nettyServerBootstrap) {
        return new MessageSenderSupport(nettyServerBootstrap);
    }

}
