package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.MessageMapping;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.support.MessageSenderSupport;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;

import io.netty.handler.codec.http.HttpRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class NettyServerBootstrapConfigureTest {

    private static final String TEST_WEBSOCKET_URI = "/ws/test";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    NettyServerBootstrapConfigure.class,
                    MessageSenderSupportConfigure.class));

    @Test
    void autoConfigurationStartsNettyServerAndPublishesMessageSenderBeans() {
        int port = findAvailablePort();
        this.contextRunner
                .withPropertyValues("server.netty.port=" + port)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(NettyServerStartupPropertiesWrapper.class);
                    assertThat(context).hasSingleBean(NettyServerBootstrap.class);
                    assertThat(context).hasSingleBean(MessageSender.class);
                    assertThat(context).hasSingleBean(MessageSenderSupport.class);
                    assertThat(context).hasBean("messageSender");
                    assertThat(context.getBean(MessageSender.class))
                            .isSameAs(context.getBean(MessageSenderSupport.class));
                    assertThat(context.getBean(NettyServerStartupPropertiesWrapper.class).getPort()).isEqualTo(port);
                });
    }

    @Test
    void websocketMappingIsRegisteredByDefault() {
        int port = findAvailablePort();
        this.contextRunner
                .withUserConfiguration(PassiveWebSocketControllerConfiguration.class)
                .withPropertyValues("server.netty.port=" + port)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(NettyServerBootstrap.class).getWebSocketMappingResolverMap())
                            .containsKey(TEST_WEBSOCKET_URI);
                });
    }

    @Test
    void websocketControllerCanInjectMessageSenderWithoutLazy() {
        int port = findAvailablePort();
        this.contextRunner
                .withUserConfiguration(TestWebSocketControllerConfiguration.class)
                .withPropertyValues("server.netty.port=" + port)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TestWebSocketController.class);
                    assertThat(context).hasSingleBean(MessageSender.class);
                    assertThat(context).hasSingleBean(MessageSenderSupport.class);
                    assertThat(context.getBean(TestWebSocketController.class).getMessageSender())
                            .isSameAs(context.getBean(MessageSender.class))
                            .isSameAs(context.getBean(MessageSenderSupport.class));
                });
    }

    @Test
    void customMessageSenderBacksOffDefaultMessageSenderSupport() {
        int port = findAvailablePort();
        this.contextRunner
                .withUserConfiguration(CustomMessageSenderConfiguration.class)
                .withPropertyValues("server.netty.port=" + port)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MessageSender.class);
                    assertThat(context).doesNotHaveBean(MessageSenderSupport.class);
                    assertThat(context.getBean(MessageSender.class))
                            .isSameAs(context.getBean(CustomMessageSenderConfiguration.class).messageSender);
                });
    }

    @Test
    void websocketMappingsAreDisabledWhenWebSocketIsDisabled() {
        int port = findAvailablePort();
        this.contextRunner
                .withUserConfiguration(PassiveWebSocketControllerConfiguration.class)
                .withPropertyValues(
                        "server.netty.port=" + port,
                        "server.netty.websocket.enable=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MessageSenderSupport.class);
                    assertThat(context.getBean(NettyServerBootstrap.class).getWebSocketMappingResolverMap()).isEmpty();
                });
    }

    @Test
    void startupFailureIsReportedToSpringContext() {
        this.contextRunner
                .withPropertyValues("server.netty.port=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("Netty startup failed.")
                            .hasStackTraceContaining("Netty port must greater than or equal to 0.");
                });
    }

    private static int findAvailablePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TestWebSocketController.class)
    static class TestWebSocketControllerConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PassiveWebSocketController.class)
    static class PassiveWebSocketControllerConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomMessageSenderConfiguration {

        private final MessageSender messageSender = Mockito.mock(MessageSender.class);

        @Bean
        MessageSender customMessageSender() {
            return this.messageSender;
        }
    }

    @Controller
    static class TestWebSocketController {

        private final MessageSender messageSender;

        TestWebSocketController(MessageSender messageSender) {
            this.messageSender = messageSender;
        }

        MessageSender getMessageSender() {
            return this.messageSender;
        }

        @MessageMapping(value = TEST_WEBSOCKET_URI, messageType = MessageType.ON_HANDSHAKE)
        public void onHandshake(HttpRequest request) {
        }
    }

    @Controller
    static class PassiveWebSocketController {

        @MessageMapping(value = TEST_WEBSOCKET_URI, messageType = MessageType.ON_HANDSHAKE)
        public void onHandshake(HttpRequest request) {
        }
    }
}
