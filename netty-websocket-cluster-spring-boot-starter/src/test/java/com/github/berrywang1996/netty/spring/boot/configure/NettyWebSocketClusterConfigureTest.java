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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterReaper;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Spring-context wiring tests for the cluster auto-configuration — verifies the headline
 * bean graph (@Primary + @ConditionalOnMissingBean + @Qualifier + ordering) actually
 * resolves in a real {@link org.springframework.context.ApplicationContext}, and that the
 * disabled path is a true no-op.
 *
 * <p>The enable=true case uses a real Redis on localhost:16379 (the same instance the
 * integration tests use) and is skipped (not failed) when Redis is unavailable.
 */
class NettyWebSocketClusterConfigureTest {

    private static String REDIS_URI = "redis://localhost:16379";
    private static boolean redisAvailable;

    @BeforeAll
    static void checkRedis() {
        redisAvailable = ClusterTestRedis.available();
        if (redisAvailable) {
            REDIS_URI = ClusterTestRedis.uri();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, // enables @ConfigurationProperties binding
                    NettyWebSocketClusterConfigure.class,
                    NettyClusterActuatorConfigure.class))
            .withUserConfiguration(LocalSenderConfig.class)
            // drain-timeout=0 so each enabled context's graceful shutdown (which now folds a bounded
            // drainTimeoutMs grace into shutdown, FIX D) closes immediately instead of sleeping the
            // 60s default on every context teardown.
            .withPropertyValues("server.netty.websocket.cluster.drain-timeout-seconds=0");

    @Test
    void disabledByDefault_noClusterBeans_localSenderRemains() {
        // No enable property → cluster auto-config must not activate at all.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ClusterMessageSender.class);
            assertThat(context).doesNotHaveBean(ClusterNodeManager.class);
            assertThat(context).doesNotHaveBean(ClusterHealthIndicator.class);
            // The injected MessageSender is the local stub, unchanged.
            assertThat(context.getBean(MessageSender.class)).isInstanceOf(LocalStubSender.class);
        });
    }

    @Test
    void explicitlyDisabled_noClusterBeans() {
        runner.withPropertyValues("server.netty.websocket.cluster.enable=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ClusterMessageSender.class);
                    assertThat(context).doesNotHaveBean(ClusterNodeManager.class);
                });
    }

    @Test
    void enabled_primaryMessageSenderIsClusterSender_andHealthIndicatorRegistered() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);

        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-test-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // @Primary must resolve to the cluster sender (the wiring B2 fix verified).
                    assertThat(context.getBean(MessageSender.class)).isInstanceOf(ClusterMessageSender.class);
                    assertThat(context).hasSingleBean(ClusterNodeManager.class);
                    // The local delegate bean still exists (was NOT suppressed by @ConditionalOnMissingBean).
                    assertThat(context.getBean("messageSender")).isInstanceOf(LocalStubSender.class);
                    // Health indicator registered (actuator on classpath via the starter).
                    assertThat(context).hasSingleBean(ClusterHealthIndicator.class);
                    assertThat(context.getBean(ClusterHealthIndicator.class).health().getDetails())
                            .containsKey("nodeState");
                    // 1.9.0 reliability beans are wired.
                    assertThat(context).hasSingleBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterReaper.class);
                    assertThat(context).hasSingleBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.CoalescingRegistryWriter.class);
                    // reliable broadcast is OFF by default → no ReliableBroker bean
                    assertThat(context).doesNotHaveBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ReliableBroker.class);
                    // auth off by default → the NoOp authenticator is wired
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator.class);
                    // standalone path (no cluster-nodes) must NOT pull in the Redis Cluster client.
                    assertThat(context).doesNotHaveBean(io.lettuce.core.cluster.RedisClusterClient.class);
                });
        // Context close here exercises the destroyMethod lifecycle (B1) without error.
    }

    @Test
    void clusterNodesSet_usesRedisClusterTransport_notStandalone() {
        Assumptions.assumeTrue(ClusterTestRedisCluster.available(),
                "no single-node Redis Cluster (no env cluster + no Docker)");
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.cluster-nodes=" + ClusterTestRedisCluster.nodes(),
                        "server.netty.websocket.cluster.node-id=ctx-cluster-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // cluster transport selected:
                    assertThat(context).hasSingleBean(io.lettuce.core.cluster.RedisClusterClient.class);
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModePubSubBroker.class);
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModeSessionRegistry.class);
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModeNodeHeartbeat.class);
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterReaper.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModeReaper.class);
                    // standalone transport suppressed:
                    assertThat(context).doesNotHaveBean(io.lettuce.core.RedisClient.class);
                    // @Primary sender still the cluster sender:
                    assertThat(context.getBean(MessageSender.class)).isInstanceOf(ClusterMessageSender.class);
                });
    }

    @Test
    void natsServersSet_usesNatsBroker_redisRegistryStays() {
        Assumptions.assumeTrue(redisAvailable, "Redis needed for the registry");
        Assumptions.assumeTrue(ClusterTestNats.available(), "no NATS (no env + no Docker)");
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.nats.servers=" + ClusterTestNats.url(),
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-nats-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // broker is NATS:
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsClusterBroker.class);
                    // registry stays Redis (the mixed model):
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisSessionRegistry.class);
                    assertThat(context.getBean(MessageSender.class)).isInstanceOf(ClusterMessageSender.class);
                });
    }

    @Test
    void natsRegistry_allNats_noRedis() {
        Assumptions.assumeTrue(ClusterTestNatsJetStream.available(), "no JetStream NATS");
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.nats.servers=" + ClusterTestNatsJetStream.url(),
                        "server.netty.websocket.cluster.nats.registry=true",
                        "server.netty.websocket.cluster.node-id=ctx-allnats-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvSessionRegistry.class);
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvNodeHeartbeat.class);
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterReaper.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvReaper.class);
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsClusterBroker.class);
                    assertThat(context).doesNotHaveBean(io.lettuce.core.RedisClient.class);
                    assertThat(context.getBean(MessageSender.class)).isInstanceOf(ClusterMessageSender.class);
                });
    }

    @Test
    void clusterMetrics_binderRegisteredWhenMicrometerPresent() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withConfiguration(AutoConfigurations.of(NettyClusterMetricsConfigure.class))
                .withBean(SimpleMeterRegistry.class)
                .withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-metrics-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(NettyClusterMeterBinder.class);
                });
    }

    @Test
    void reliableEnabled_createsReliableBrokerBean() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-reliable-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30",
                        "server.netty.websocket.cluster.reliable.enable=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ReliableBroker.class);
                    assertThat(context.getBean(MessageSender.class)).isInstanceOf(ClusterMessageSender.class);
                });
    }

    /** RC13 case (i): all-NATS (nats.servers + nats.registry=true) AND reliable.enable=true →
     *  the {@link com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsJetStreamReliableBroker}
     *  bean is selected; the Redis Streams reliable bean is suppressed by its standalone-Redis-registry gate. */
    @Test
    void allNats_reliableEnabled_selectsNatsJetStreamReliableBroker() {
        Assumptions.assumeTrue(ClusterTestNatsJetStream.available(), "no JetStream NATS");
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.nats.servers=" + ClusterTestNatsJetStream.url(),
                        "server.netty.websocket.cluster.nats.registry=true",
                        "server.netty.websocket.cluster.node-id=ctx-nats-reliable-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30",
                        "server.netty.websocket.cluster.reliable.enable=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ReliableBroker.class);
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ReliableBroker.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsJetStreamReliableBroker.class);
                    // Redis client is suppressed in the all-NATS path.
                    assertThat(context).doesNotHaveBean(io.lettuce.core.RedisClient.class);
                });
    }

    /** RC13 case (ii): all-NATS, reliable.enable=false → existing all-NATS behavior preserved, no
     *  ReliableBroker bean (this case was the existing pre-RC13 default and stays byte-level identical). */
    @Test
    void allNats_reliableDisabled_noReliableBroker() {
        Assumptions.assumeTrue(ClusterTestNatsJetStream.available(), "no JetStream NATS");
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.nats.servers=" + ClusterTestNatsJetStream.url(),
                        "server.netty.websocket.cluster.nats.registry=true",
                        "server.netty.websocket.cluster.node-id=ctx-allnats-noreliable-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ReliableBroker.class);
                });
    }

    /** RC13 case (iii): mixed deployment (NATS broker + Redis registry) AND reliable.enable=true →
     *  the Redis Streams reliable bean is selected (mixed-mode regression — must not accidentally
     *  activate the NATS JetStream reliable broker). */
    @Test
    void mixed_natsBroker_redisRegistry_reliable_stillUsesRedisStreams() {
        Assumptions.assumeTrue(redisAvailable, "Redis needed for the registry");
        Assumptions.assumeTrue(ClusterTestNats.available(), "no NATS (no env + no Docker)");
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.nats.servers=" + ClusterTestNats.url(),
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-mixed-reliable-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30",
                        "server.netty.websocket.cluster.reliable.enable=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ReliableBroker.class);
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ReliableBroker.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisStreamsReliableBroker.class);
                });
    }

    /** RC13 case (iv): all-NATS + reliable.enable=true but jnats absent from the classpath → the
     *  existing RC11 FIX E classpath guard fires before anything else can fail (no silent broken
     *  bean state). Gated on Redis: with nats.servers set + Redis present, the failure is
     *  deterministically the guard's {@link IllegalStateException} naming the missing dependency. */
    @Test
    void allNats_reliableEnabled_jnatsAbsent_failsFastWithActionableMessage() {
        Assumptions.assumeTrue(redisAvailable, "Redis needed to isolate the failure to the classpath guard");
        runner.withClassLoader(new FilteredClassLoader("io.nats.client.Connection"))
                .withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.nats.servers=nats://user:secret@localhost:4222",
                        "server.netty.websocket.cluster.nats.registry=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-allnats-reliable-missing",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30",
                        "server.netty.websocket.cluster.reliable.enable=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable root = context.getStartupFailure();
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(root).hasMessageContaining("io.nats:jnats");
                });
    }

    @Test
    void authEnabled_wiresHmacAuthenticator() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-auth-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30",
                        "server.netty.websocket.cluster.auth.enable=true",
                        "server.netty.websocket.cluster.auth.secret=this-is-a-32+char-cluster-secret!!")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.HmacMessageAuthenticator.class);
                });
    }

    @Test
    void authEnabledWithoutSecret_failsContext() {
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-auth-nosecret",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30",
                        "server.netty.websocket.cluster.auth.enable=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void natsServersSetButJnatsAbsent_failsFastWithActionableMessage() {
        // Hide io.nats.client.Connection so the @ConditionalOnClass NATS beans are suppressed AND
        // (without the guard) the Redis brokers would be suppressed by NO_NATS_TRANSPORT → zero
        // ClusterBroker → opaque failure. The guard bean must instead fail fast with an actionable msg.
        // Gated on Redis: with nats.servers set + nats.registry=false the Redis *registry* beans are still
        // active, so a Redis-less box could fail on the Redis connection bean instead (different error).
        // With Redis present the failure is deterministically the guard's IllegalStateException.
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withClassLoader(new FilteredClassLoader("io.nats.client.Connection"))
                .withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.nats.servers=nats://user:secret@localhost:4222",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-nats-missing",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                    Throwable root = context.getStartupFailure();
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(root).hasMessageContaining("io.nats:jnats");
                });
    }

    @Test
    void tracePropagationEnabled_wiresClusterTraceContext() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-trace-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30",
                        "server.netty.websocket.cluster.trace-propagation.enable=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterTraceContext.class);
                });
    }

    @Test
    void tracePropagationDisabledByDefault_noClusterTraceContextBean() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-notrace-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterTraceContext.class);
                });
    }

    @Test
    void roomEnabled_wiresRoomRegistry() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-room-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30",
                        "server.netty.websocket.cluster.room.enable=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterRoomRegistry.class);
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterRoomRegistry.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisRoomRegistry.class);
                    // wired into the cluster sender (room routing active)
                    assertThat(((ClusterMessageSender) context.getBean(MessageSender.class)).isRoomEnabled())
                            .as("room registry must be wired into the cluster sender").isTrue();
                });
    }

    @Test
    void roomDisabledByDefault_noRoomRegistryBean_byteIdenticalPath() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-noroom-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterRoomRegistry.class);
                    // no room path on the sender
                    assertThat(((ClusterMessageSender) context.getBean(MessageSender.class)).isRoomEnabled())
                            .as("room routing must be off when room.enable is unset").isFalse();
                });
    }

    // ---- Offline queue / user-addressed delivery (1.10.0-RC2): the 3-path context test ----

    /** Path 1 (OFF — default): offline.enable unset → no offline beans, the sender's offline path is OFF,
     *  the hook is the RC1 emptyMap path (byte-identical). */
    @Test
    void offlineDisabledByDefault_noOfflineBeans_byteIdenticalHookPath() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-nooffline-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserRegistry.class);
                    assertThat(context).doesNotHaveBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.OfflineQueueStore.class);
                    assertThat(context).doesNotHaveBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserIdResolver.class);
                    // The sender's offline path is OFF (emptyMap register path on the hook).
                    assertThat(((ClusterMessageSender) context.getBean(MessageSender.class)).isOfflineEnabled())
                            .as("offline must be off when offline.enable is unset").isFalse();
                });
    }

    /** Path 2 (ON + anonymous): offline.enable=true with the DEFAULT HandshakeUserIdResolver → all three
     *  offline beans wired (default Redis impls), sender offline path active. (A session with no userId
     *  resolves to anonymous at runtime; here we assert the bean graph for the enabled path.) */
    @Test
    void offlineEnabled_defaultResolver_allBeansWiredIntoSender() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-offline-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30",
                        "server.netty.websocket.cluster.offline.enable=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserRegistry.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisUserRegistry.class);
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.OfflineQueueStore.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisOfflineQueueStore.class);
                    // Default resolver = the testing-only HandshakeUserIdResolver.
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserIdResolver.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.room.HandshakeUserIdResolver.class);
                    // Wired into the cluster sender (offline path active).
                    assertThat(((ClusterMessageSender) context.getBean(MessageSender.class)).isOfflineEnabled())
                            .as("offline beans must be wired into the cluster sender").isTrue();
                });
    }

    /** Path 3 (ON + authenticated): a user-supplied {@code UserIdResolver} bean REPLACES the testing-only
     *  default (@ConditionalOnMissingBean) — the production identity-validation path. */
    @Test
    void offlineEnabled_customResolver_replacesDefaultTestingResolver() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withUserConfiguration(CustomUserIdResolverConfig.class)
                .withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-offline-auth-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30",
                        "server.netty.websocket.cluster.offline.enable=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // The user's authenticated resolver wins over the default HandshakeUserIdResolver.
                    assertThat(context).hasBean("authenticatedUserIdResolver");
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserIdResolver.class))
                            .isNotInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.room.HandshakeUserIdResolver.class);
                    assertThat(((ClusterMessageSender) context.getBean(MessageSender.class)).isOfflineEnabled()).isTrue();
                });
    }

    /** FIX 7 — ORPHAN-RESOLVER-NON-STANDALONE: on a non-standalone (Redis-Cluster) transport with
     *  offline.enable=true, the userIdResolver must NOT be created — it carries the same
     *  {@code STANDALONE_REDIS_REGISTRY} gate as its userRegistry/offlineQueueStore collaborators, so all three
     *  gate together (offline is Redis-standalone-only in RC2; the loud testing-only warn never fires here). */
    @Test
    void offlineEnabled_onClusterTransport_noUserIdResolverBean() {
        Assumptions.assumeTrue(ClusterTestRedisCluster.available(),
                "no single-node Redis Cluster (no env cluster + no Docker)");
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.cluster-nodes=" + ClusterTestRedisCluster.nodes(),
                        "server.netty.websocket.cluster.node-id=ctx-offline-cluster-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30",
                        "server.netty.websocket.cluster.offline.enable=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // None of the three offline beans on the non-standalone path:
                    assertThat(context).doesNotHaveBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserIdResolver.class);
                    assertThat(context).doesNotHaveBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserRegistry.class);
                    assertThat(context).doesNotHaveBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.OfflineQueueStore.class);
                    // Offline silently no-ops on the cluster transport (sender's offline path off).
                    assertThat(((ClusterMessageSender) context.getBean(MessageSender.class)).isOfflineEnabled())
                            .as("offline must be off on the Redis-Cluster transport in RC2").isFalse();
                });
    }

    /** Helper @Configuration supplying a production-style (authenticated) UserIdResolver override. */
    @Configuration
    static class CustomUserIdResolverConfig {
        @Bean(name = "authenticatedUserIdResolver")
        com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserIdResolver authenticatedUserIdResolver() {
            // Stands in for a resolver that derives userId from a verified principal (e.g. JWT sub).
            return session -> "verified-subject";
        }
    }

    // ---- L1 (RC16): OnAnyRedisSpiRequired gates Redis client/connection ----
    // The Condition matches when AT LEAST ONE of the 4 Redis-backed SPI beans
    // (SessionRegistry, ClusterBroker, ClusterNodeHeartbeat, ClusterReaper) is NOT
    // user-overridden — i.e. an auto-config default would still need the Redis client.
    // When all 4 are user-overridden, the Redis client + connection beans are gated off.

    /** L1 (i): all 4 SPI overridden — auto-config no longer creates RedisClient/connection. */
    @Test
    void clusterMode_allFourSpiOverridden_doesNotCreateRedisClient() {
        runner.withUserConfiguration(AllFourSpiOverridesConfig.class)
                .withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.node-id=ctx-l1-all-overrides",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // No Redis client / connection created — the gate worked.
                    assertThat(context).doesNotHaveBean(io.lettuce.core.RedisClient.class);
                    assertThat(context).doesNotHaveBean("nettyClusterRedisConnection");
                    // All 4 user-supplied SPI beans are present.
                    assertThat(context).hasBean("customSessionRegistry");
                    assertThat(context).hasBean("customClusterBroker");
                    assertThat(context).hasBean("customClusterNodeHeartbeat");
                    assertThat(context).hasBean("customClusterReaper");
                    // Sanity: cluster mode is still wired (cluster sender is @Primary).
                    assertThat(context.getBean(MessageSender.class)).isInstanceOf(ClusterMessageSender.class);
                });
    }

    /** L1 (ii): only SessionRegistry overridden — RedisClient still created (the other 3 SPI
     *  default to Redis-backed impls and need it). Partial-override regression guard. */
    @Test
    void clusterMode_onlySessionRegistryOverridden_stillCreatesRedisClient() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withUserConfiguration(OnlySessionRegistryOverrideConfig.class)
                .withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-l1-only-registry",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // RedisClient still needed — broker / heartbeat / reaper still default to Redis impls.
                    assertThat(context).hasSingleBean(io.lettuce.core.RedisClient.class);
                    // User's SessionRegistry override won.
                    assertThat(context).hasBean("customSessionRegistry");
                });
    }

    /** L1 (iii): zero overrides — RedisClient IS created (RC15 behavior preserved). */
    @Test
    void clusterMode_zeroOverrides_createsRedisClient_regression() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-l1-no-overrides",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(io.lettuce.core.RedisClient.class);
                });
    }

    /** Helper @Configuration that supplies user @Bean overrides for ALL 4 Redis-backed SPI interfaces.
     *  Mocks (not real impls) — these context tests are NOT integration tests and do not exercise the
     *  cluster runtime; they only verify the auto-config bean-graph wiring. */
    @Configuration
    static class AllFourSpiOverridesConfig {
        @Bean(name = "customSessionRegistry")
        SessionRegistry customSessionRegistry() { return mock(SessionRegistry.class); }
        @Bean(name = "customClusterBroker")
        ClusterBroker customClusterBroker() { return mock(ClusterBroker.class); }
        @Bean(name = "customClusterNodeHeartbeat")
        ClusterNodeHeartbeat customClusterNodeHeartbeat() { return mock(ClusterNodeHeartbeat.class); }
        @Bean(name = "customClusterReaper")
        ClusterReaper customClusterReaper() { return mock(ClusterReaper.class); }
    }

    /** Helper @Configuration with ONE user override (partial-override path). */
    @Configuration
    static class OnlySessionRegistryOverrideConfig {
        @Bean(name = "customSessionRegistry")
        SessionRegistry customSessionRegistry() { return mock(SessionRegistry.class); }
    }

    @Configuration
    static class LocalSenderConfig {
        /** Stands in for MessageSenderSupportConfigure's local "messageSender" bean. */
        @Bean(name = "messageSender")
        MessageSender messageSender() {
            return new LocalStubSender();
        }
    }

    /** Minimal local MessageSender stub (no Netty). */
    static class LocalStubSender implements MessageSender {
        @Override public int getSessionNums() { return 0; }
        @Override public int getSessionNums(String uri) { return 0; }
        @Override public Set<String> getRegisteredUri() { return Collections.emptySet(); }
        @Override public boolean isSessionAlive(String uri, String... sessionIds) { return false; }
        @Override public void sendMessage(String uri, AbstractMessage message, String... sessionIds) {}
        @Override public void topicMessage(String uri, AbstractMessage message) {}
    }
}
