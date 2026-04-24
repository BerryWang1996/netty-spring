package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import com.github.berrywang1996.netty.spring.web.websocket.support.MessageSenderSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class NettyServerBootstrapConfigureTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    NettyServerBootstrapConfigure.class,
                    NettyServerStartupPropertiesWrapper.class));

    @Test
    void autoConfigurationStartsNettyServerAndPublishesMessageSenderSupport() {
        int port = findAvailablePort();
        this.contextRunner
                .withPropertyValues("server.netty.port=" + port)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(NettyServerStartupPropertiesWrapper.class);
                    assertThat(context).hasSingleBean(NettyServerBootstrap.class);
                    assertThat(context).hasSingleBean(MessageSenderSupport.class);
                    assertThat(context.getBean(NettyServerStartupPropertiesWrapper.class).getPort()).isEqualTo(port);
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
}
