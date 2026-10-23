package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.contributor.Status;
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
import static org.mockito.Mockito.mock;

/** Exercises Boot's imports discovery, including optional classpath and user-bean boundaries. */
class NettyAutoConfigurationDiscoveryTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DiscoveryApplication.class)
            .withPropertyValues("server.netty.port=0");

    @Test
    void discoversHealthAndMetricsAfterServerAndUserRegistry() {
        runner.withUserConfiguration(RegistryConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(NettyServerBootstrap.class).isRunning()).isTrue();
            assertThat(context).hasSingleBean(NettyServerHealthIndicator.class);
            assertThat(context.getBean(NettyServerHealthIndicator.class).health().getStatus())
                    .isEqualTo(Status.UP);
            assertThat(context).hasSingleBean(NettyHttpMeterBinder.class);
            assertThat(context).hasSingleBean(NettyWebSocketMeterBinder.class);
        });
    }

    @Test
    void startsWithoutHealthOrMicrometerLibraries() {
        runner.withClassLoader(missingLibraries("org.springframework.boot.health", "io.micrometer"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(NettyServerBootstrap.class).isRunning()).isTrue();
                    assertThat(context).doesNotHaveBean("nettyServerHealthIndicator");
                    assertThat(context).doesNotHaveBean("nettyHttpMeterBinder");
                    assertThat(context).doesNotHaveBean("nettyWebSocketMeterBinder");
                    assertThatThrownBy(() -> context.getClassLoader().loadClass(
                            "org.springframework.boot.health.contributor.HealthIndicator"))
                            .isInstanceOf(ClassNotFoundException.class);
                    assertThatThrownBy(() -> context.getClassLoader().loadClass(
                            "io.micrometer.core.instrument.MeterRegistry"))
                            .isInstanceOf(ClassNotFoundException.class);
                });
    }

    @Test
    void metricsBackOffWithoutRegistry() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MeterRegistry.class);
            assertThat(context).doesNotHaveBean("nettyHttpMeterBinder");
            assertThat(context).doesNotHaveBean("nettyWebSocketMeterBinder");
        });
    }

    @Test
    void userBootstrapHealthAndMeterBindersReplaceDefaults() {
        runner.withUserConfiguration(RegistryConfiguration.class, UserOverrides.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(NettyServerBootstrap.class);
            assertThat(context).hasSingleBean(NettyServerHealthIndicator.class);
            assertThat(context).hasSingleBean(NettyHttpMeterBinder.class);
            assertThat(context).hasSingleBean(NettyWebSocketMeterBinder.class);
            assertThat(context.getBean(NettyServerBootstrap.class)).isSameAs(context.getBean("customBootstrap"));
            assertThat(context.getBean(NettyServerHealthIndicator.class)).isSameAs(context.getBean("customHealth"));
            assertThat(context.getBean(NettyHttpMeterBinder.class)).isSameAs(context.getBean("customHttpBinder"));
            assertThat(context.getBean(NettyWebSocketMeterBinder.class)).isSameAs(context.getBean("customWebSocketBinder"));
            assertThat(context).doesNotHaveBean("nettyServer");
        });
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
                // A missing spring-boot-health JAR removes its imports as well as its classes.
                // Package filtering alone leaves those now-unloadable Boot candidates visible.
                // Keep every netty-spring import so our optional-dependency conditions are exercised.
                return Collections.enumeration(Collections.list(resources).stream()
                        .filter(url -> !url.toExternalForm().contains("/spring-boot-health-"))
                        .toList());
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    static class RegistryConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserOverrides {
        @Bean
        NettyServerBootstrap customBootstrap() {
            return mock(NettyServerBootstrap.class);
        }

        @Bean
        NettyServerHealthIndicator customHealth(NettyServerBootstrap bootstrap) {
            return new NettyServerHealthIndicator(bootstrap);
        }

        @Bean
        NettyHttpMeterBinder customHttpBinder(NettyServerBootstrap bootstrap) {
            return new NettyHttpMeterBinder(bootstrap);
        }

        @Bean
        NettyWebSocketMeterBinder customWebSocketBinder(NettyServerBootstrap bootstrap) {
            return new NettyWebSocketMeterBinder(bootstrap);
        }
    }
}
