package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.support.MessageSenderSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real imports discovery with user SPI replacements, without requiring external middleware. */
class ClusterAutoConfigurationDiscoveryTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DiscoveryApplication.class)
            .withPropertyValues("server.netty.port=0", "server.netty.websocket.cluster.drain-timeout-seconds=0");

    @Test
    void disabledClusterStartsWithoutTransportHealthOrMetricsLibraries() {
        runner.withClassLoader(missingLibraries(
                        "io.nats", "io.lettuce", "org.springframework.boot.health", "io.micrometer"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(NettyServerBootstrap.class).isRunning()).isTrue();
                    assertThat(context).hasSingleBean(MessageSenderSupport.class);
                    assertThat(context).doesNotHaveBean(ClusterMessageSender.class);
                    assertThat(context).doesNotHaveBean("nettyClusterHealthIndicator");
                    assertThat(context).doesNotHaveBean("nettyClusterMeterBinder");
                    assertThatThrownBy(() -> context.getClassLoader().loadClass("io.lettuce.core.RedisClient"))
                            .isInstanceOf(ClassNotFoundException.class);
                    assertThatThrownBy(() -> context.getClassLoader().loadClass("io.nats.client.Connection"))
                            .isInstanceOf(ClassNotFoundException.class);
                });
    }

    @Test
    void enabledClusterKeepsLocalDelegateAndUsesUserSpisWithoutJnats() {
        enabledRunner().withClassLoader(new FilteredClassLoader("io.nats"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(NettyServerBootstrap.class).isRunning()).isTrue();
                    assertThat(context.getBean("messageSender")).isInstanceOf(MessageSenderSupport.class);
                    assertThat(context.getBean(MessageSender.class)).isInstanceOf(ClusterMessageSender.class);
                    assertThat(context).hasSingleBean(ClusterNodeManager.class);
                    assertThat(context.getBean(ClusterBroker.class)).isSameAs(context.getBean("customClusterBroker"));
                    assertThat(context).hasSingleBean(ClusterHealthIndicator.class);
                    assertThat(context).hasSingleBean(MeterRegistry.class);
                    assertThat(context).hasSingleBean(NettyClusterMeterBinder.class);
                    assertThat(context).hasSingleBean(NettyHttpMeterBinder.class);
                    assertThat(context).hasSingleBean(NettyWebSocketMeterBinder.class);
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find("netty.allocator.used.heap").gauge()).isNotNull();
                    assertThat(registry.find("netty.http.response.write.failures").functionCounter()).isNotNull();
                    assertThat(context).doesNotHaveBean("nettyClusterRedisClient");
                    assertThat(context).doesNotHaveBean("nettyClusterNatsKvConnection");
                });
    }

    @Test
    void enabledClusterStartsWithoutHealthOrMetricsLibraries() {
        enabledRunner().withClassLoader(missingLibraries("org.springframework.boot.health", "io.micrometer"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ClusterMessageSender.class);
                    assertThat(context).doesNotHaveBean("nettyClusterHealthIndicator");
                    assertThat(context).doesNotHaveBean("nettyClusterMeterBinder");
                });
    }

    @Test
    void userClusterHealthIndicatorReplacesDefault() {
        enabledRunner().withUserConfiguration(CustomHealthConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ClusterHealthIndicator.class);
            assertThat(context.getBean(ClusterHealthIndicator.class)).isSameAs(context.getBean("customClusterHealth"));
        });
    }

    private ApplicationContextRunner enabledRunner() {
        return runner.withUserConfiguration(NettyWebSocketClusterConfigureTest.AllFourSpiOverridesConfig.class)
                .withPropertyValues("server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.node-id=discovery-test",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class DiscoveryApplication {
    }

    private static FilteredClassLoader missingLibraries(String... packages) {
        return new FilteredClassLoader(packages) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                Enumeration<URL> resources = super.getResources(name);
                if (!name.equals("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
                    return resources;
                }
                // Real absence of spring-boot-health removes this JAR's imports too. Keep the
                // shared and cluster imports: their own conditions must still handle missing APIs.
                return Collections.enumeration(Collections.list(resources).stream()
                        .filter(url -> !url.toExternalForm().contains("/spring-boot-health-"))
                        .toList());
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomHealthConfiguration {
        @Bean
        ClusterHealthIndicator customClusterHealth(ClusterNodeManager nodeManager, ClusterBroker broker,
                                                  ClusterMessageSender sender) {
            return new ClusterHealthIndicator(nodeManager, broker, sender);
        }
    }
}
