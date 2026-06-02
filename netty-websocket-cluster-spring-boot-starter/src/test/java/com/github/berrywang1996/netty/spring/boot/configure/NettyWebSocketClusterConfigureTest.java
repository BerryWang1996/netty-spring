package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static final String REDIS_URI = "redis://localhost:16379";
    private static boolean redisAvailable;

    @BeforeAll
    static void checkRedis() {
        try {
            RedisClient c = RedisClient.create(REDIS_URI);
            StatefulRedisConnection<String, String> conn = c.connect();
            conn.sync().ping();
            conn.close();
            c.shutdown();
            redisAvailable = true;
        } catch (Exception e) {
            redisAvailable = false;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, // enables @ConfigurationProperties binding
                    NettyWebSocketClusterConfigure.class,
                    NettyClusterActuatorConfigure.class))
            .withUserConfiguration(LocalSenderConfig.class);

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
                });
        // Context close here exercises the destroyMethod lifecycle (B1) without error.
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
