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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterProperties;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterSessionHookImpl;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.CoalescingRegistryWriter;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.DefaultMessagePayloadCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.EnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessagePayloadCodec;
import com.github.berrywang1996.netty.spring.web.websocket.context.ClusterSessionHook;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterReaper;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterReaper;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisPubSubBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisSessionRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisStreamsReliableBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ReliableBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring Boot auto-configuration for WebSocket cluster support.
 *
 * <p>Activated only when:
 * <ul>
 *   <li>The cluster module is on the classpath ({@code ClusterBroker.class})</li>
 *   <li>{@code server.netty.websocket.cluster.enable=true} is set</li>
 * </ul>
 *
 * <p>When active, this configuration replaces the default {@link MessageSender} with
 * a {@link ClusterMessageSender} that adds cross-node broadcast and unicast via the
 * {@link ClusterBroker} and {@link SessionRegistry} SPIs.
 *
 * <p>The SPI beans ({@code ClusterBroker}, {@code SessionRegistry}) default to Redis
 * implementations but can be overridden by the user via {@code @ConditionalOnMissingBean}.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
@Slf4j
@Configuration
@ConditionalOnClass(ClusterBroker.class)
@ConditionalOnProperty(prefix = "server.netty.websocket.cluster", name = "enable", havingValue = "true")
// MUST be ordered AFTER MessageSenderSupportConfigure: that config registers the local
// `messageSender` bean under @ConditionalOnMissingBean(MessageSender). Since this cluster
// config's @Primary ClusterMessageSender is ALSO a MessageSender, the local bean must be
// created first — otherwise @ConditionalOnMissingBean would suppress it and the
// @Qualifier("messageSender") injection below would fail. Explicit ordering removes the
// (previously alphabetical-accident) fragility.
@AutoConfigureAfter({NettyServerBootstrapConfigure.class, MessageSenderSupportConfigure.class})
public class NettyWebSocketClusterConfigure {

    @Bean
    @ConfigurationProperties(prefix = "server.netty.websocket.cluster")
    public ClusterProperties clusterProperties() {
        return new ClusterProperties();
    }

    // ---- Redis connections (only created when no user-provided alternative exists) ----

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedisClient.class)
    public RedisClient nettyClusterRedisClient(ClusterProperties properties) {
        String uri = properties.getRedis().getUri();
        // Never log the raw URI — it may carry inline credentials (redis://:password@host).
        log.info("Creating Lettuce RedisClient for cluster at {}", redactRedisUri(uri));
        warnIfInsecureRedis(uri);
        RedisClient client = RedisClient.create(uri);
        // Bound command timeout for the control plane — much lower than Lettuce's 60s default, so a
        // Redis stall can't hang the unicast hot path. Auto-reconnect stays on (default) so the
        // node recovers automatically. See S2 hardening.
        client.setOptions(io.lettuce.core.ClientOptions.builder()
                .autoReconnect(true)
                .timeoutOptions(io.lettuce.core.TimeoutOptions.enabled(
                        java.time.Duration.ofMillis(properties.getCommandTimeoutMs())))
                .build());
        return client;
    }

    /** Masks userinfo (password) in a Redis URI before logging. */
    private static String redactRedisUri(String uri) {
        if (uri == null) {
            return "null";
        }
        // redis[s]://[user][:password]@host:port -> redis[s]://***@host:port
        int at = uri.indexOf('@');
        int scheme = uri.indexOf("://");
        if (at > 0 && scheme > 0 && at > scheme) {
            return uri.substring(0, scheme + 3) + "***@" + uri.substring(at + 1);
        }
        return uri;
    }

    /** Warns when the cluster control-plane Redis is configured without TLS or a password. */
    private static void warnIfInsecureRedis(String uri) {
        if (uri == null) {
            return;
        }
        boolean tls = uri.startsWith("rediss://");
        boolean hasAuth = uri.contains("@");
        if (!tls || !hasAuth) {
            String missing = (!tls && !hasAuth) ? "no TLS and no password"
                    : (!tls ? "no TLS" : "no password");
            log.warn("Cluster Redis is the WebSocket control plane: anyone who can PUBLISH to it "
                    + "can inject into or close any session. The configured Redis URI has {} — for "
                    + "production use a DEDICATED, network-isolated Redis with a password "
                    + "(redis://:secret@host) and TLS (rediss://). See docs/cluster-design.md §Security.",
                    missing);
        }
    }

    @Bean(name = "nettyClusterRedisConnection", destroyMethod = "close")
    @ConditionalOnMissingBean(name = "nettyClusterRedisConnection")
    public StatefulRedisConnection<String, String> nettyClusterRedisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    // ---- SPI beans (user can override with @ConditionalOnMissingBean) ----

    @Bean
    @ConditionalOnMissingBean(EnvelopeCodec.class)
    public EnvelopeCodec envelopeCodec() {
        log.info("Using default SimpleTextEnvelopeCodec (zero-dependency, pipe-delimited). "
                + "Override with your own EnvelopeCodec bean for JSON/Protobuf/etc.");
        return new SimpleTextEnvelopeCodec();
    }

    @Bean
    @ConditionalOnMissingBean(MessagePayloadCodec.class)
    public MessagePayloadCodec messagePayloadCodec() {
        log.info("Using default DefaultMessagePayloadCodec (T:/J:/B: prefix). "
                + "Override with your own MessagePayloadCodec bean for custom serialization.");
        return new DefaultMessagePayloadCodec();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(ClusterBroker.class)
    public RedisPubSubBroker clusterBroker(RedisClient redisClient, EnvelopeCodec envelopeCodec,
                                           ClusterProperties properties) {
        RedisPubSubBroker broker = new RedisPubSubBroker(redisClient, envelopeCodec);
        // Inbound guard: reject received messages larger than the outbound cap + headroom
        // (Base64 ~+33% + envelope metadata). 0 (unlimited) outbound => unlimited inbound.
        int maxOut = properties.getMessageMaxSizeBytes();
        broker.setInboundMaxBytes(maxOut > 0 ? (int) Math.min((long) maxOut * 2L, Integer.MAX_VALUE) : 0);
        return broker;
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(ReliableBroker.class)
    @ConditionalOnProperty(prefix = "server.netty.websocket.cluster.reliable", name = "enable", havingValue = "true")
    public ReliableBroker reliableBroker(RedisClient redisClient, EnvelopeCodec envelopeCodec,
                                         ClusterProperties properties) {
        ClusterProperties.Reliable r = properties.getReliable();
        log.info("Reliable broadcast ENABLED (Redis Streams; maxlen={}, block={}ms)",
                r.getStreamMaxLen(), r.getPollBlockMs());
        return new RedisStreamsReliableBroker(redisClient, envelopeCodec,
                r.getStreamMaxLen(), r.getPollBlockMs(), r.getPollCount(), r.getDedupWindow());
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(SessionRegistry.class)
    public RedisSessionRegistry sessionRegistry(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterRedisConnection")
            StatefulRedisConnection<String, String> connection) {
        return new RedisSessionRegistry(connection);
    }

    @Bean
    @ConditionalOnMissingBean(ClusterNodeHeartbeat.class)
    public RedisClusterNodeHeartbeat clusterNodeHeartbeat(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterRedisConnection")
            StatefulRedisConnection<String, String> connection) {
        return new RedisClusterNodeHeartbeat(connection);
    }

    @Bean
    @ConditionalOnMissingBean(ClusterReaper.class)
    public ClusterReaper clusterReaper(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterRedisConnection")
            StatefulRedisConnection<String, String> connection) {
        return new RedisClusterReaper(connection);
    }

    // ---- Core cluster beans ----

    @Bean(destroyMethod = "shutdown")
    public ClusterNodeManager clusterNodeManager(
            ClusterProperties properties,
            ClusterNodeHeartbeat heartbeat,
            SessionRegistry sessionRegistry,
            ClusterReaper clusterReaper) {
        ClusterNodeManager manager = new ClusterNodeManager(
                properties.getNodeId(),
                properties.getHeartbeatIntervalSeconds() * 1000,
                properties.getHeartbeatTimeoutSeconds() * 1000,
                properties.getReconciliationIntervalSeconds() * 1000,
                properties.getDrainTimeoutSeconds() * 1000,
                heartbeat,
                sessionRegistry);
        manager.setReconnectJitterMaxMs(properties.getReconnectJitterMaxSeconds() * 1000);
        manager.setRedisLossGracePeriodMs(properties.getRedisLossGracePeriodMs());
        manager.setReaper(clusterReaper);
        manager.start();
        log.info("Cluster node manager started (nodeId={})", manager.getNodeId());
        return manager;
    }

    @Bean(destroyMethod = "shutdown")
    @Primary
    public ClusterMessageSender clusterMessageSender(
            @org.springframework.beans.factory.annotation.Qualifier("messageSender") MessageSender localSender,
            ClusterBroker broker,
            SessionRegistry sessionRegistry,
            ClusterNodeManager nodeManager,
            ClusterProperties properties,
            MessagePayloadCodec messagePayloadCodec,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ReliableBroker reliableBroker) {
        ClusterMessageSender sender = new ClusterMessageSender(
                localSender, broker, sessionRegistry, nodeManager,
                properties.getRegistryReadCacheTtlMs(), messagePayloadCodec);
        sender.setMessageMaxSizeBytes(properties.getMessageMaxSizeBytes());
        sender.setOnPublishFailure(properties.getOnPublishFailure());
        sender.setOnRedisLoss(properties.getOnRedisLoss());
        sender.setNodeLookupTimeoutMs(properties.getCommandTimeoutMs());
        if (reliableBroker != null) {
            sender.setReliableBroker(reliableBroker);
        }
        sender.start();
        log.info("ClusterMessageSender started — cluster mode is ACTIVE (onRedisLoss={}, onPublishFailure={}, maxMsgBytes={}, reliable={})",
                properties.getOnRedisLoss(), properties.getOnPublishFailure(), properties.getMessageMaxSizeBytes(),
                reliableBroker != null);
        return sender;
    }

    @Bean(destroyMethod = "shutdown")
    public CoalescingRegistryWriter clusterRegistryWriter(
            SessionRegistry sessionRegistry, ClusterProperties properties, ClusterNodeManager nodeManager) {
        CoalescingRegistryWriter writer = new CoalescingRegistryWriter(
                sessionRegistry, properties.getSessionRegistryWriteRate(), 50L, nodeManager.getNodeId());
        writer.start();
        log.info("Cluster registry writer started (rate={} ops/s/node)", properties.getSessionRegistryWriteRate());
        return writer;
    }

    @Bean
    public ClusterSessionHook clusterSessionHook(
            CoalescingRegistryWriter clusterRegistryWriter,
            ClusterNodeManager nodeManager,
            ClusterMessageSender clusterSender) {
        log.info("Registering cluster session hook for distributed session lifecycle");
        return new ClusterSessionHookImpl(clusterRegistryWriter, nodeManager, clusterSender);
    }
}
