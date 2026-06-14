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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.support;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterReaper;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterRoomRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.OfflineQueueStore;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserRegistry;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when at least one of the 4 Redis-backed cluster SPI interfaces will be created by
 * the default auto-config — i.e. the user has NOT provided a {@code @Bean} for it. Used to gate
 * the eager-init Redis client/connection beans so a fully-custom-SPI deployment doesn't pay for
 * an idle Redis connection.
 *
 * <p>The 4 SPI interfaces inspected:
 * <ul>
 *   <li>{@link SessionRegistry}</li>
 *   <li>{@link ClusterBroker}</li>
 *   <li>{@link ClusterNodeHeartbeat}</li>
 *   <li>{@link ClusterReaper}</li>
 * </ul>
 *
 * <p>If ALL 4 are user-provided, neither {@code nettyClusterRedisClient} nor
 * {@code nettyClusterRedisConnection} is created. If even one of them is still going to default
 * to a Redis-backed impl, the Redis client bean stays — the other SPI still needs it.
 *
 * <p>Evaluated at {@link ConfigurationPhase#REGISTER_BEAN} so user-supplied {@code @Bean}
 * definitions are visible: {@code REGISTER_BEAN} runs AFTER bean definitions are loaded
 * (including {@code @Bean} methods on user {@code @Configuration} classes), so
 * {@code getBeanNamesForType} sees both the auto-config's defaults and user overrides as
 * definitions before any instantiation.
 *
 * <p>{@code getBeanNamesForType(type, true, false)} uses {@code includeNonSingletons=true,
 * allowEagerInit=false} — catches user {@code @Bean} definitions without triggering
 * instantiation, the standard pattern for {@code REGISTER_BEAN}-phase SPI-bean lookups.
 *
 * @author berrywang1996
 * @since V1.9.0-RC16
 */
public class OnAnyRedisSpiRequired implements ConfigurationCondition {

    @Override
    public ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.REGISTER_BEAN;
    }

    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata md) {
        ConfigurableListableBeanFactory bf = (ConfigurableListableBeanFactory) ctx.getBeanFactory();
        if (!hasBean(bf, SessionRegistry.class)
                || !hasBean(bf, ClusterBroker.class)
                || !hasBean(bf, ClusterNodeHeartbeat.class)
                || !hasBean(bf, ClusterReaper.class)) {
            return true;
        }
        // Room routing (1.10.0): when room.enable=true and the user has NOT supplied their own
        // ClusterRoomRegistry, the default RedisRoomRegistry will be created and needs the Redis
        // connection — so the client/connection beans must NOT be gated off.
        boolean roomEnabled = Boolean.parseBoolean(ctx.getEnvironment()
                .getProperty("server.netty.websocket.cluster.room.enable", "false"));
        if (roomEnabled && !hasBean(bf, ClusterRoomRegistry.class)) {
            return true;
        }
        // Offline / user-addressed delivery (1.10.0-RC2): when offline.enable=true and the user has NOT
        // supplied their own UserRegistry/OfflineQueueStore, the default Redis impls will be created and
        // need the Redis connection — so the client/connection beans must stay.
        boolean offlineEnabled = Boolean.parseBoolean(ctx.getEnvironment()
                .getProperty("server.netty.websocket.cluster.offline.enable", "false"));
        return offlineEnabled
                && (!hasBean(bf, UserRegistry.class) || !hasBean(bf, OfflineQueueStore.class));
    }

    private static boolean hasBean(ConfigurableListableBeanFactory bf, Class<?> type) {
        return bf.getBeanNamesForType(type, true, false).length > 0;
    }
}
