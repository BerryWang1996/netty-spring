package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.MessageMapping;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.support.MessageSenderSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
                    NettyServerStartupPropertiesWrapper.class,
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
    void httpNamespaceBindsToHttpRelatedProperties() {
        int port = findAvailablePort();
        this.contextRunner
                .withPropertyValues(
                        "server.netty.port=" + port,
                        "server.netty.http.file-location=public",
                        "server.netty.http.gzip.enable=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    NettyServerStartupPropertiesWrapper properties =
                            context.getBean(NettyServerStartupPropertiesWrapper.class);
                    assertThat(properties.getHttp().getFileLocation()).isEqualTo("public");
                    assertThat(properties.getFileLocation()).isEqualTo("public");
                    assertThat(properties.getHttp().getGzip().isEnable()).isTrue();
                    assertThat(properties.getGzip().isEnable()).isTrue();
                });
    }

    @Test
    void legacyHttpPropertiesRemainCompatibleAfterHttpNamespaceIsIntroduced() {
        int port = findAvailablePort();
        this.contextRunner
                .withPropertyValues(
                        "server.netty.port=" + port,
                        "server.netty.file-location=public",
                        "server.netty.gzip.enable=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    NettyServerStartupPropertiesWrapper properties =
                            context.getBean(NettyServerStartupPropertiesWrapper.class);
                    assertThat(properties.getHttp().getFileLocation()).isEqualTo("public");
                    assertThat(properties.getFileLocation()).isEqualTo("public");
                    assertThat(properties.getHttp().getGzip().isEnable()).isTrue();
                    assertThat(properties.getGzip().isEnable()).isTrue();
                });
    }

    @Test
    void websocketAutoConfigurationCanBeDisabled() {
        int port = findAvailablePort();
        this.contextRunner
                .withUserConfiguration(PassiveWebSocketControllerConfiguration.class)
                .withPropertyValues(
                        "server.netty.port=" + port,
                        "server.netty.websocket.enable=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MessageSenderSupport.class);
                    assertThat(context.getBean(NettyServerBootstrap.class).getWebSockeMappingtResolverMap()).isEmpty();
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

    @Configuration(proxyBeanMethods = false)
    @Import(TestWebSocketController.class)
    static class TestWebSocketControllerConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PassiveWebSocketController.class)
    static class PassiveWebSocketControllerConfiguration {
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
