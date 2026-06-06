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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.MdcClusterTraceContext;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterTraceContext;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.DefaultMessagePayloadCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.HmacMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.EnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;
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
import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Map;

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

    /** SpEL: cluster-nodes is empty/absent → use the standalone/sentinel Redis transport. */
    static final String STANDALONE_TRANSPORT =
            "'${server.netty.websocket.cluster.redis.cluster-nodes:}'.trim().isEmpty()";
    /** SpEL: cluster-nodes is non-empty → use the Redis Cluster transport. */
    static final String CLUSTER_TRANSPORT =
            "!('${server.netty.websocket.cluster.redis.cluster-nodes:}'.trim().isEmpty())";

    /** SpEL: nats.servers is empty/absent → use a Redis broker. */
    static final String NO_NATS_TRANSPORT =
            "'${server.netty.websocket.cluster.nats.servers:}'.trim().isEmpty()";
    /** SpEL: nats.servers is non-empty → use the NATS broker. */
    static final String NATS_TRANSPORT =
            "!('${server.netty.websocket.cluster.nats.servers:}'.trim().isEmpty())";
    /** Standalone Redis broker: cluster-nodes empty AND nats.servers empty. */
    static final String STANDALONE_REDIS_BROKER = STANDALONE_TRANSPORT + " and " + NO_NATS_TRANSPORT;
    /** Cluster Redis broker: cluster-nodes set AND nats.servers empty. */
    static final String CLUSTER_REDIS_BROKER = CLUSTER_TRANSPORT + " and " + NO_NATS_TRANSPORT;

    /** SpEL: nats.registry == true. */
    static final String NATS_REGISTRY =
            "'${server.netty.websocket.cluster.nats.registry:false}' == 'true'";
    /** All-NATS: nats.servers set AND nats.registry true → NATS-KV registry (no Redis). */
    static final String ALL_NATS = NATS_TRANSPORT + " and " + NATS_REGISTRY;
    /** Not all-NATS (used to suppress the Redis registry/heartbeat/reaper/client beans in all-NATS mode). */
    static final String NOT_ALL_NATS = "!(" + NATS_TRANSPORT + " and " + NATS_REGISTRY + ")";
    /** Standalone Redis registry/infra: cluster-nodes empty AND not all-NATS. */
    static final String STANDALONE_REDIS_REGISTRY = STANDALONE_TRANSPORT + " and " + NOT_ALL_NATS;
    /** Cluster Redis registry/infra: cluster-nodes set AND not all-NATS. */
    static final String CLUSTER_REDIS_REGISTRY = CLUSTER_TRANSPORT + " and " + NOT_ALL_NATS;

    @Bean
    @ConfigurationProperties(prefix = "server.netty.websocket.cluster")
    public ClusterProperties clusterProperties() {
        return new ClusterProperties();
    }

    // ---- Redis connections (only created when no user-provided alternative exists) ----

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnExpression(STANDALONE_REDIS_REGISTRY)
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
        return redactUserinfo(uri);
    }

    /**
     * Masks the {@code [user][:password]@} userinfo segment of a single {@code scheme://...} URI before
     * logging, so inline credentials never leak. Returns the input unchanged when there is no userinfo.
     */
    private static String redactUserinfo(String uri) {
        if (uri == null) {
            return "null";
        }
        // scheme://[user][:password]@host:port -> scheme://***@host:port
        int at = uri.indexOf('@');
        int scheme = uri.indexOf("://");
        if (at > 0 && scheme > 0 && at > scheme) {
            return uri.substring(0, scheme + 3) + "***@" + uri.substring(at + 1);
        }
        return uri;
    }

    /**
     * Redacts a comma-separated list of server URIs (e.g. {@code nats://user:pass@h1:4222,nats://h2:4222})
     * by masking the userinfo of each entry — used so the NATS server list is never logged with inline
     * credentials (parallel to {@link #redactRedisUri}). Returns "null" for null, preserving the per-entry
     * order and the comma separators.
     */
    static String redactServerUris(String csv) {
        if (csv == null) {
            return "null";
        }
        String[] parts = csv.split(",", -1);
        StringBuilder sb = new StringBuilder(csv.length());
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(redactUserinfo(parts[i].trim()));
        }
        return sb.toString();
    }

    /** Warns when the NATS server list carries no TLS scheme and no inline credentials (insecure transport). */
    private static void warnIfInsecureNats(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return;
        }
        boolean anyTls = false;
        boolean anyAuth = false;
        for (String entry : csv.split(",", -1)) {
            String e = entry.trim();
            if (e.startsWith("tls://")) {
                anyTls = true;
            }
            if (e.contains("@")) {
                anyAuth = true;
            }
        }
        if (!anyTls || !anyAuth) {
            String missing = (!anyTls && !anyAuth) ? "no TLS and no credentials"
                    : (!anyTls ? "no TLS" : "no credentials");
            log.warn("Cluster NATS is the WebSocket transport: anyone who can publish to it can inject "
                    + "cross-node messages. The configured NATS server list has {} — for production use "
                    + "TLS (tls://) and authenticated connections. See docs/cluster-design.md §Security.",
                    missing);
        }
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
    @ConditionalOnExpression(STANDALONE_REDIS_REGISTRY)
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

    @Bean
    @ConditionalOnMissingBean(MessageAuthenticator.class)
    public MessageAuthenticator messageAuthenticator(ClusterProperties properties) {
        ClusterProperties.Auth auth = properties.getAuth();
        if (!auth.isEnable()) {
            return new NoOpMessageAuthenticator();
        }
        String secret = auth.getSecret();
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException("server.netty.websocket.cluster.auth.enable=true requires "
                    + "a non-empty server.netty.websocket.cluster.auth.secret");
        }
        if (secret.length() < 32) {
            log.warn("Cluster auth secret is short ({} chars) — use >= 32 chars of high-entropy secret "
                    + "for HMAC-SHA256.", secret.length());
        }
        log.info("Cluster envelope HMAC authentication ENABLED (mode={})",
                auth.isPermissive() ? "permissive" : "strict");
        return new HmacMessageAuthenticator(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                !auth.isPermissive());
    }

    @Bean
    @ConditionalOnMissingBean(ClusterTraceContext.class)
    @ConditionalOnProperty(prefix = "server.netty.websocket.cluster.trace-propagation",
            name = "enable", havingValue = "true")
    public ClusterTraceContext clusterTraceContext() {
        log.info("Cluster W3C TraceContext propagation ENABLED (MDC-based traceparent across nodes)");
        return new MdcClusterTraceContext();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnExpression(STANDALONE_REDIS_BROKER)
    @ConditionalOnMissingBean(ClusterBroker.class)
    public RedisPubSubBroker clusterBroker(RedisClient redisClient, EnvelopeCodec envelopeCodec,
                                           ClusterProperties properties, MessageAuthenticator messageAuthenticator) {
        RedisPubSubBroker broker = new RedisPubSubBroker(redisClient, envelopeCodec, messageAuthenticator,
                properties.getPubsubConnections());
        // Inbound guard: reject received messages larger than the outbound cap + headroom
        // (Base64 ~+33% + envelope metadata). 0 (unlimited) outbound => unlimited inbound.
        int maxOut = properties.getMessageMaxSizeBytes();
        broker.setInboundMaxBytes(maxOut > 0 ? (int) Math.min((long) maxOut * 2L, Integer.MAX_VALUE) : 0);
        return broker;
    }

    // ---- NATS classpath guard: fail fast with an actionable message ----

    /**
     * Fail-fast guard for the misconfiguration where {@code cluster.nats.servers} is set but the
     * {@code io.nats:jnats} dependency is NOT on the classpath. Without this guard, ALL NATS beans are
     * {@code @ConditionalOnClass}-suppressed AND the Redis brokers are suppressed by {@code NO_NATS_TRANSPORT},
     * leaving zero {@link ClusterBroker} → an opaque {@code NoSuchBeanDefinitionException} deep in context
     * startup. This bean activates ONLY in that exact situation (nats.servers set AND
     * {@code io.nats.client.Connection} absent) and throws an actionable {@link IllegalStateException} naming
     * the missing dependency.
     */
    @Bean
    @ConditionalOnExpression(NATS_TRANSPORT)
    @ConditionalOnMissingClass("io.nats.client.Connection")
    public Object natsTransportClasspathGuard(ClusterProperties properties) {
        throw new IllegalStateException(
                "server.netty.websocket.cluster.nats.servers is set (" + redactServerUris(properties.getNats().getServers())
                + ") but io.nats:jnats is not on the classpath — add the io.nats:jnats dependency "
                + "to use the NATS cluster transport, or unset nats.servers to use the Redis transport.");
    }

    // ---- NATS broker (active only when nats.servers is set; ADR-001 scaling tier) ----

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnClass(io.nats.client.Connection.class)
    @ConditionalOnExpression(NATS_TRANSPORT)
    @ConditionalOnMissingBean(ClusterBroker.class)
    public ClusterBroker clusterBrokerNats(ClusterProperties properties, EnvelopeCodec envelopeCodec,
            MessageAuthenticator messageAuthenticator) throws Exception {
        com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsClusterBroker broker =
                new com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsClusterBroker(
                        envelopeCodec, messageAuthenticator);
        // 2-phase init: build the NATS connection with the broker's ConnectionListener (set at build time),
        // then attach it. The broker owns + closes the connection (destroyMethod="shutdown").
        io.nats.client.Connection connection = io.nats.client.Nats.connect(io.nats.client.Options.builder()
                .server(properties.getNats().getServers())
                .connectionListener(broker::onConnectionEvent)
                .maxReconnects(-1)
                .build());
        broker.attach(connection);
        int maxOut = properties.getMessageMaxSizeBytes();
        broker.setInboundMaxBytes(maxOut > 0 ? (int) Math.min((long) maxOut * 2L, Integer.MAX_VALUE) : 0);
        // Never log the raw server list — it may carry inline credentials (nats://user:pass@host).
        warnIfInsecureNats(properties.getNats().getServers());
        log.info("Cluster broker = NATS ({}) — registry/heartbeat remain on Redis (ADR-001 mixed model)",
                redactServerUris(properties.getNats().getServers()));
        return broker;
    }

    // ---- NATS JetStream KV registry/heartbeat/reaper (all-NATS: nats.servers set AND nats.registry=true) ----

    @Bean(name = "nettyClusterNatsKvConnection", destroyMethod = "close")
    @ConditionalOnClass(io.nats.client.Connection.class)
    @ConditionalOnExpression(ALL_NATS)
    @ConditionalOnMissingBean(name = "nettyClusterNatsKvConnection")
    public io.nats.client.Connection nettyClusterNatsKvConnection(ClusterProperties properties) throws Exception {
        io.nats.client.Connection conn = io.nats.client.Nats.connect(io.nats.client.Options.builder()
                .server(properties.getNats().getServers())
                .maxReconnects(-1)
                .build());
        // Idempotent bucket bootstrap (create-if-absent). Requires a JetStream-enabled NATS server (-js).
        ensureBucket(conn, "netty-sessions", null);
        ensureBucket(conn, "netty-nodes", null);
        ensureBucket(conn, "netty-reaping", java.time.Duration.ofSeconds(30)); // claim window
        warnIfInsecureNats(properties.getNats().getServers());
        log.info("Cluster registry = NATS JetStream KV (all-NATS; no Redis) at {}",
                redactServerUris(properties.getNats().getServers()));
        return conn;
    }

    private static void ensureBucket(io.nats.client.Connection conn, String name, java.time.Duration ttl) throws Exception {
        if (conn.keyValueManagement().getBucketNames().contains(name)) {
            return;
        }
        io.nats.client.api.KeyValueConfiguration.Builder b =
                io.nats.client.api.KeyValueConfiguration.builder().name(name);
        if (ttl != null) {
            b.ttl(ttl);
        }
        try {
            conn.keyValueManagement().create(b.build());
        } catch (io.nats.client.JetStreamApiException alreadyExists) {
            // raced with another node — fine.
        }
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnClass(io.nats.client.Connection.class)
    @ConditionalOnExpression(ALL_NATS)
    @ConditionalOnMissingBean(SessionRegistry.class)
    public SessionRegistry natsKvSessionRegistry(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterNatsKvConnection")
            io.nats.client.Connection conn) throws Exception {
        return new com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvSessionRegistry(
                conn.keyValue("netty-sessions"));
    }

    @Bean
    @ConditionalOnClass(io.nats.client.Connection.class)
    @ConditionalOnExpression(ALL_NATS)
    @ConditionalOnMissingBean(ClusterNodeHeartbeat.class)
    public ClusterNodeHeartbeat natsKvNodeHeartbeat(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterNatsKvConnection")
            io.nats.client.Connection conn) throws Exception {
        return new com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvNodeHeartbeat(
                conn.keyValue("netty-nodes"));
    }

    @Bean
    @ConditionalOnClass(io.nats.client.Connection.class)
    @ConditionalOnExpression(ALL_NATS)
    @ConditionalOnMissingBean(ClusterReaper.class)
    public ClusterReaper natsKvReaper(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterNatsKvConnection")
            io.nats.client.Connection conn) throws Exception {
        return new com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvReaper(
                conn.keyValue("netty-reaping"));
    }

    /** SpEL: reliable.enable=true AND nats.registry=true → activate the all-NATS JetStream reliable broker.
     *  The existing {@link #reliableBroker} bean ({@code RedisStreamsReliableBroker}) carries the
     *  {@code STANDALONE_REDIS_REGISTRY} SpEL gate which evaluates to false when {@code nats.registry=true},
     *  so the two beans are mutually exclusive by construction (verified by context test #i + #ii in
     *  {@code NettyWebSocketClusterConfigureTest}). The {@code @ConditionalOnMissingBean(ReliableBroker.class)}
     *  on both beans further allows a user-supplied override to win across every deployment tier. */
    static final String ALL_NATS_RELIABLE =
            "${server.netty.websocket.cluster.reliable.enable:false} and " + ALL_NATS;

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnClass(io.nats.client.Connection.class)
    @ConditionalOnExpression(ALL_NATS_RELIABLE)
    @ConditionalOnMissingBean(ReliableBroker.class)
    public ReliableBroker natsJetStreamReliableBroker(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterNatsKvConnection")
            io.nats.client.Connection nats,
            EnvelopeCodec envelopeCodec,
            ClusterProperties properties,
            MessageAuthenticator messageAuthenticator) {
        ClusterProperties.Reliable r = properties.getReliable();
        log.info("Reliable broadcast ENABLED (NATS JetStream; maxMsgs={}, fetchBlock={}ms, fetchCount={}, groupDestroyIdleMs={})",
                r.getStreamMaxLen(), r.getPollBlockMs(), r.getPollCount(), r.getGroupDestroyIdleMs());
        com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsJetStreamReliableBroker broker =
                new com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsJetStreamReliableBroker(
                        nats, envelopeCodec, messageAuthenticator, properties.getNodeId(),
                        r.getStreamMaxLen(), r.getPollBlockMs(), r.getPollCount(), r.getDedupWindow());
        // Inbound guard: reject envelopes larger than the outbound cap + headroom (Base64 ~+33% +
        // envelope metadata). 0 (unlimited) → unlimited. Mirrors RedisStreamsReliableBroker wiring.
        int maxOut = properties.getMessageMaxSizeBytes();
        broker.setInboundMaxBytes(maxOut > 0 ? (int) Math.min((long) maxOut * 2L, Integer.MAX_VALUE) : 0);
        // Idle window before a dead node's consumer may be reaped (heartbeat-expiry) — protects a
        // crashed-and-restarting node's durable cursor + PEL (and thus its backlog replay).
        broker.setGroupDestroyIdleMs(r.getGroupDestroyIdleMs());
        return broker;
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnExpression(STANDALONE_REDIS_REGISTRY)
    @ConditionalOnMissingBean(ReliableBroker.class)
    @ConditionalOnProperty(prefix = "server.netty.websocket.cluster.reliable", name = "enable", havingValue = "true")
    public ReliableBroker reliableBroker(RedisClient redisClient, EnvelopeCodec envelopeCodec,
                                         ClusterProperties properties, MessageAuthenticator messageAuthenticator) {
        ClusterProperties.Reliable r = properties.getReliable();
        log.info("Reliable broadcast ENABLED (Redis Streams; maxlen={}, block={}ms, groupDestroyIdleMs={})",
                r.getStreamMaxLen(), r.getPollBlockMs(), r.getGroupDestroyIdleMs());
        RedisStreamsReliableBroker reliableBroker = new RedisStreamsReliableBroker(redisClient, envelopeCodec,
                r.getStreamMaxLen(), r.getPollBlockMs(), r.getPollCount(), r.getDedupWindow(), messageAuthenticator);
        // Inbound guard: reject received stream entries larger than the outbound cap + headroom (Base64
        // ~+33% + envelope metadata) — same headroom the pub/sub brokers use. 0 (unlimited) → unlimited.
        int maxOut = properties.getMessageMaxSizeBytes();
        reliableBroker.setInboundMaxBytes(maxOut > 0 ? (int) Math.min((long) maxOut * 2L, Integer.MAX_VALUE) : 0);
        // Idle window before a dead node's consumer group may be destroyed on heartbeat-expiry — protects a
        // crashed-and-restarting node's retained offset/PEL (and thus its backlog replay).
        reliableBroker.setGroupDestroyIdleMs(r.getGroupDestroyIdleMs());
        return reliableBroker;
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnExpression(STANDALONE_REDIS_REGISTRY)
    @ConditionalOnMissingBean(SessionRegistry.class)
    public RedisSessionRegistry sessionRegistry(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterRedisConnection")
            StatefulRedisConnection<String, String> connection) {
        return new RedisSessionRegistry(connection);
    }

    @Bean
    @ConditionalOnExpression(STANDALONE_REDIS_REGISTRY)
    @ConditionalOnMissingBean(ClusterNodeHeartbeat.class)
    public RedisClusterNodeHeartbeat clusterNodeHeartbeat(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterRedisConnection")
            StatefulRedisConnection<String, String> connection) {
        return new RedisClusterNodeHeartbeat(connection);
    }

    @Bean
    @ConditionalOnExpression(STANDALONE_REDIS_REGISTRY)
    @ConditionalOnMissingBean(ClusterReaper.class)
    public ClusterReaper clusterReaper(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterRedisConnection")
            StatefulRedisConnection<String, String> connection) {
        return new RedisClusterReaper(connection);
    }

    // ---- Redis Cluster transport beans (active only when cluster-nodes is set) ----

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnExpression(CLUSTER_REDIS_REGISTRY)
    @ConditionalOnMissingBean(io.lettuce.core.cluster.RedisClusterClient.class)
    public io.lettuce.core.cluster.RedisClusterClient nettyClusterRedisClusterClient(ClusterProperties properties) {
        String clusterNodes = properties.getRedis().getClusterNodes();
        java.util.List<io.lettuce.core.RedisURI> seeds = new java.util.ArrayList<>();
        for (String hp : clusterNodes.split(",")) {
            String node = hp.trim();
            if (node.isEmpty()) {
                continue;
            }
            int idx = node.lastIndexOf(':');
            if (idx <= 0 || idx == node.length() - 1) {
                throw new IllegalStateException("Invalid cluster node (expected host:port): '" + node + "'");
            }
            int port;
            try {
                port = Integer.parseInt(node.substring(idx + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Invalid cluster node port (expected host:port): '" + node + "'", e);
            }
            seeds.add(io.lettuce.core.RedisURI.create(node.substring(0, idx), port));
        }
        if (seeds.isEmpty()) {
            throw new IllegalStateException(
                    "server.netty.websocket.cluster.redis.cluster-nodes resolved to no usable host:port entries");
        }
        // host:port has no scheme/credentials, so this always warns — appropriate: the cluster control
        // plane must be network-isolated. (For TLS/password, supply your own RedisClusterClient bean.)
        warnIfInsecureRedis("redis://" + clusterNodes.split(",")[0].trim());
        log.info("Creating Lettuce RedisClusterClient for {} Redis Cluster seed node(s)", seeds.size());
        io.lettuce.core.cluster.RedisClusterClient client = io.lettuce.core.cluster.RedisClusterClient.create(seeds);
        // Topology pinning + bounded timeout. validateClusterNodeMembership(false) + periodic-refresh-off
        // keep the client usable when nodes advertise addresses the client can't reach directly
        // (containerized / NAT'd clusters — the common production case, and what the single-node test
        // cluster needs). Bounded command timeout mirrors the standalone client (control-plane fast-fail).
        client.setOptions(io.lettuce.core.cluster.ClusterClientOptions.builder()
                .validateClusterNodeMembership(false)
                .topologyRefreshOptions(io.lettuce.core.cluster.ClusterTopologyRefreshOptions.builder()
                        .enablePeriodicRefresh(false)
                        .build())
                .timeoutOptions(io.lettuce.core.TimeoutOptions.enabled(
                        java.time.Duration.ofMillis(properties.getCommandTimeoutMs())))
                .build());
        return client;
    }

    @Bean(name = "nettyClusterRedisClusterConnection", destroyMethod = "close")
    @ConditionalOnExpression(CLUSTER_REDIS_REGISTRY)
    @ConditionalOnMissingBean(name = "nettyClusterRedisClusterConnection")
    public io.lettuce.core.cluster.api.StatefulRedisClusterConnection<String, String> nettyClusterRedisClusterConnection(
            io.lettuce.core.cluster.RedisClusterClient client) {
        return client.connect();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnExpression(CLUSTER_REDIS_BROKER)
    @ConditionalOnMissingBean(ClusterBroker.class)
    public ClusterBroker clusterBrokerCluster(io.lettuce.core.cluster.RedisClusterClient client,
            EnvelopeCodec envelopeCodec, ClusterProperties properties, MessageAuthenticator messageAuthenticator) {
        com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModePubSubBroker broker =
                new com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModePubSubBroker(
                        client, envelopeCodec, messageAuthenticator);
        int maxOut = properties.getMessageMaxSizeBytes();
        broker.setInboundMaxBytes(maxOut > 0 ? (int) Math.min((long) maxOut * 2L, Integer.MAX_VALUE) : 0);
        return broker;
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnExpression(CLUSTER_REDIS_REGISTRY)
    @ConditionalOnMissingBean(SessionRegistry.class)
    public SessionRegistry sessionRegistryCluster(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterRedisClusterConnection")
            io.lettuce.core.cluster.api.StatefulRedisClusterConnection<String, String> connection) {
        return new com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModeSessionRegistry(connection);
    }

    @Bean
    @ConditionalOnExpression(CLUSTER_REDIS_REGISTRY)
    @ConditionalOnMissingBean(ClusterNodeHeartbeat.class)
    public ClusterNodeHeartbeat clusterNodeHeartbeatCluster(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterRedisClusterConnection")
            io.lettuce.core.cluster.api.StatefulRedisClusterConnection<String, String> connection) {
        return new com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModeNodeHeartbeat(connection);
    }

    @Bean
    @ConditionalOnExpression(CLUSTER_REDIS_REGISTRY)
    @ConditionalOnMissingBean(ClusterReaper.class)
    public ClusterReaper clusterReaperCluster(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterRedisClusterConnection")
            io.lettuce.core.cluster.api.StatefulRedisClusterConnection<String, String> connection) {
        return new com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModeReaper(connection);
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
            @org.springframework.beans.factory.annotation.Autowired(required = false) ReliableBroker reliableBroker,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ClusterTraceContext traceContext) {
        ClusterMessageSender sender = new ClusterMessageSender(
                localSender, broker, sessionRegistry, nodeManager,
                properties.getRegistryReadCacheTtlMs(), messagePayloadCodec);
        sender.setMessageMaxSizeBytes(properties.getMessageMaxSizeBytes());
        sender.setOnPublishFailure(properties.getOnPublishFailure());
        sender.setOnRedisLoss(properties.getOnRedisLoss());
        sender.setNodeLookupTimeoutMs(properties.getCommandTimeoutMs());
        sender.setRegistryReadCacheMaxSize(properties.getRegistryReadCacheMaxSize());
        if (reliableBroker != null) {
            sender.setReliableBroker(reliableBroker);
        }
        if (traceContext != null) {
            sender.setTraceContext(traceContext);
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

    /**
     * Wires the {@link ClusterSessionHook} onto the live WebSocket resolvers AFTER all singletons
     * are instantiated. This is required because {@code NettyServerBootstrapConfigure.nettyServer()}
     * eagerly starts the server — running the resolver scan and its one-shot
     * {@code getBeansOfType(ClusterSessionHook.class)} lookup — during its own bean creation, at
     * which point this config's {@code clusterSessionHook} bean is not yet resolvable (it
     * transitively depends on the local {@code messageSender}/server beans then mid-creation, so the
     * lookup returns empty). Without this late wiring the distributed session registry is never
     * populated, so cross-node unicast and targeted-close cannot route. The resolver's hook field is
     * {@code volatile}, and external clients only connect after startup completes, so this set is
     * safely visible with no race in practice.
     */
    @Bean
    public SmartInitializingSingleton clusterSessionHookResolverWiring(
            ObjectProvider<NettyServerBootstrap> nettyServerProvider, ClusterSessionHook clusterSessionHook) {
        return () -> {
            NettyServerBootstrap nettyServer = nettyServerProvider.getIfAvailable();
            if (nettyServer == null) {
                // No embedded Netty server in this context (e.g. a slice/context test) — nothing to wire.
                return;
            }
            Map<String, ?> resolvers = nettyServer.getWebSocketMappingResolverMap();
            if (resolvers == null) {
                return;
            }
            int wired = 0;
            for (Object resolver : resolvers.values()) {
                if (resolver instanceof MessageMappingResolver) {
                    ((MessageMappingResolver) resolver).setClusterSessionHook(clusterSessionHook);
                    wired++;
                }
            }
            log.info("Wired cluster session hook onto {} websocket resolver(s) after startup "
                    + "(the eager server scan runs before the cluster hook bean exists; this closes that gap)", wired);
        };
    }
}
