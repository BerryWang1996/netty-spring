package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import com.github.berrywang1996.netty.spring.web.context.WebMappingSupporter;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestMapping;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;

import java.lang.reflect.Field;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class NettyServerBootstrapConfigureTest {

    private static final String TEST_MVC_URI = "/mvc/test";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    NettyServerBootstrapConfigure.class,
                    NettyServerStartupPropertiesWrapper.class,
                    MessageSenderSupportConfigure.class));

    @Test
    void autoConfigurationStartsNettyServer() {
        int port = findAvailablePort();
        this.contextRunner
                .withPropertyValues("server.netty.port=" + port)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(NettyServerStartupPropertiesWrapper.class);
                    assertThat(context).hasSingleBean(NettyServerBootstrap.class);
                    assertThat(context.getBean(NettyServerStartupPropertiesWrapper.class).getPort()).isEqualTo(port);
                });
    }

    @Test
    void requestMappingIsRegisteredByDefault() {
        int port = findAvailablePort();
        this.contextRunner
                .withUserConfiguration(TestMvcControllerConfiguration.class)
                .withPropertyValues("server.netty.port=" + port)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    WebMappingSupporter supporter = extractSupporter(context.getBean(NettyServerBootstrap.class));
                    assertThat(supporter.getMappingResolverMap()).containsKey(TEST_MVC_URI);
                });
    }

    @Test
    void requestMappingsAreDisabledWhenMvcIsDisabled() {
        int port = findAvailablePort();
        this.contextRunner
                .withUserConfiguration(TestMvcControllerConfiguration.class)
                .withPropertyValues(
                        "server.netty.port=" + port,
                        "server.netty.mvc.enable=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    WebMappingSupporter supporter = extractSupporter(context.getBean(NettyServerBootstrap.class));
                    assertThat(supporter.getMappingResolverMap()).isEmpty();
                });
    }

    @Test
    void websocketSenderAutoConfigurationBacksOffWhenWebsocketSupportIsAbsent() {
        int port = findAvailablePort();
        this.contextRunner
                .withPropertyValues("server.netty.port=" + port)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.containsBean("messageSenderSupport")).isFalse();
                    assertThat(context.containsBean("messageSender")).isFalse();
                });
    }

    @Test
    void startupFailureIsReportedToSpringContext() {
        this.contextRunner
                .withPropertyValues("server.netty.port=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("Netty startup failed.")
                            .hasStackTraceContaining("Netty port must greater than 0.");
                });
    }

    private static int findAvailablePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static WebMappingSupporter extractSupporter(NettyServerBootstrap bootstrap) throws Exception {
        Field field = NettyServerBootstrap.class.getDeclaredField("webMappingSupporter");
        field.setAccessible(true);
        return (WebMappingSupporter) field.get(bootstrap);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TestMvcController.class)
    static class TestMvcControllerConfiguration {
    }

    @Controller
    static class TestMvcController {

        @RequestMapping(TEST_MVC_URI)
        public String handle() {
            return "ok";
        }
    }
}
