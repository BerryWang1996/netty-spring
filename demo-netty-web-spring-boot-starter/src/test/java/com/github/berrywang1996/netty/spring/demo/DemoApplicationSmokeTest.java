package com.github.berrywang1996.netty.spring.demo;

import com.github.berrywang1996.netty.spring.demo.controller.DemoHomeController;
import com.github.berrywang1996.netty.spring.demo.controller.WebSocketCryptoDemoController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class DemoApplicationSmokeTest {

    @Test
    void applicationStartsWithNettyStarter() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DemoApplication.class)
                .properties("server.netty.port=" + findAvailablePort())
                .run()) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getBean(DemoApplication.class)).isNotNull();
        }
    }

    @Test
    void cryptoDemoPageIsAvailableFromApplicationContext() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DemoApplication.class)
                .properties("server.netty.port=" + findAvailablePort())
                .run()) {
            WebSocketCryptoDemoController controller = context.getBean(WebSocketCryptoDemoController.class);

            assertThat(controller.cryptoDemo())
                    .contains("WebSocket AES-GCM demo")
                    .contains("crypto.subtle.encrypt")
                    .contains("demo-2026-05")
                    .contains("/ws/test?room=crypto-demo");
        }
    }

    @Test
    void demoHomePageExplainsMainEntrypoints() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DemoApplication.class)
                .properties("server.netty.port=" + findAvailablePort())
                .run()) {
            DemoHomeController controller = context.getBean(DemoHomeController.class);

            assertThat(controller.home())
                    .contains("netty-spring demo cockpit")
                    .contains("/http/get")
                    .contains("/ws/test?room=demo")
                    .contains("/ws/crypto-demo")
                    .contains("/netty/status");
        }
    }

    private static int findAvailablePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
