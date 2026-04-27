package com.github.berrywang1996.netty.spring.boot.configure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class NettyServerStartupPropertiesWrapperTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    NettyServerStartupPropertiesWrapper.class));

    @Test
    void httpNamespaceBindsStaticFileGzipAndSslProperties() {
        this.contextRunner
                .withPropertyValues(
                        "server.netty.http.handle-file=true",
                        "server.netty.http.file-location=public",
                        "server.netty.http.info-location=WEB-INF",
                        "server.netty.http.max-initial-line-length=2048",
                        "server.netty.http.max-header-size=4096",
                        "server.netty.http.max-chunk-size=16384",
                        "server.netty.http.max-content-length=131072",
                        "server.netty.http.read-timeout-seconds=11",
                        "server.netty.http.write-timeout-seconds=12",
                        "server.netty.http.idle-timeout-seconds=13",
                        "server.netty.http.gzip.enable=true",
                        "server.netty.http.gzip.compression-level=7",
                        "server.netty.http.gzip.window-bits=13",
                        "server.netty.http.gzip.mem-level=6",
                        "server.netty.http.gzip.content-size-threshold=128",
                        "server.netty.http.gzip.types=text/plain application/json",
                        "server.netty.http.ssl.enable=true",
                        "server.netty.http.ssl.certificate=server.crt",
                        "server.netty.http.ssl.certificate-key=server.key",
                        "server.netty.http.ssl.protocols=TLSv1.2,TLSv1.3",
                        "server.netty.http.ssl.ciphers=TLS_AES_128_GCM_SHA256 TLS_AES_256_GCM_SHA384")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    NettyServerStartupPropertiesWrapper properties =
                            context.getBean(NettyServerStartupPropertiesWrapper.class);

                    assertThat(properties.getHttp().isHandleFile()).isTrue();
                    assertThat(properties.isHandleFile()).isTrue();
                    assertThat(properties.getHttp().getFileLocation()).isEqualTo("public");
                    assertThat(properties.getFileLocation()).isEqualTo("public");
                    assertThat(properties.getHttp().getInfoLocation()).isEqualTo("WEB-INF");
                    assertThat(properties.getInfoLocation()).isEqualTo("WEB-INF");
                    assertThat(properties.getHttp().getMaxInitialLineLength()).isEqualTo(2048);
                    assertThat(properties.getHttp().getMaxHeaderSize()).isEqualTo(4096);
                    assertThat(properties.getHttp().getMaxChunkSize()).isEqualTo(16384);
                    assertThat(properties.getHttp().getMaxContentLength()).isEqualTo(131072);
                    assertThat(properties.getHttp().getReadTimeoutSeconds()).isEqualTo(11L);
                    assertThat(properties.getHttp().getWriteTimeoutSeconds()).isEqualTo(12L);
                    assertThat(properties.getHttp().getIdleTimeoutSeconds()).isEqualTo(13L);
                    assertThat(properties.getHttp().getGzip().isEnable()).isTrue();
                    assertThat(properties.getGzip().isEnable()).isTrue();
                    assertThat(properties.getHttp().getGzip().getCompressionLevel()).isEqualTo(7);
                    assertThat(properties.getHttp().getGzip().getWindowBits()).isEqualTo(13);
                    assertThat(properties.getHttp().getGzip().getMemLevel()).isEqualTo(6);
                    assertThat(properties.getHttp().getGzip().getContentSizeThreshold()).isEqualTo(128);
                    assertThat(properties.getHttp().getGzip().getTypes()).isEqualTo("text/plain application/json");
                    assertThat(properties.getHttp().getSsl().isEnable()).isTrue();
                    assertThat(properties.getSsl().isEnable()).isTrue();
                    assertThat(properties.getHttp().getSsl().getCertificate()).isEqualTo("server.crt");
                    assertThat(properties.getSsl().getCertificate()).isEqualTo("server.crt");
                    assertThat(properties.getHttp().getSsl().getCertificateKey()).isEqualTo("server.key");
                    assertThat(properties.getSsl().getCertificateKey()).isEqualTo("server.key");
                    assertThat(properties.getHttp().getSsl().getProtocols()).isEqualTo("TLSv1.2,TLSv1.3");
                    assertThat(properties.getSsl().getProtocols()).isEqualTo("TLSv1.2,TLSv1.3");
                    assertThat(properties.getHttp().getSsl().getCiphers())
                            .isEqualTo("TLS_AES_128_GCM_SHA256 TLS_AES_256_GCM_SHA384");
                    assertThat(properties.getSsl().getCiphers())
                            .isEqualTo("TLS_AES_128_GCM_SHA256 TLS_AES_256_GCM_SHA384");
                });
    }

    @Test
    void legacyHttpRelatedPropertiesRemainCompatible() {
        this.contextRunner
                .withPropertyValues(
                        "server.netty.handle-file=true",
                        "server.netty.file-location=legacy-public",
                        "server.netty.info-location=legacy-WEB-INF",
                        "server.netty.gzip.enable=true",
                        "server.netty.gzip.compression-level=5",
                        "server.netty.ssl.enable=true",
                        "server.netty.ssl.certificate=legacy.crt",
                        "server.netty.ssl.certificate-key=legacy.key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    NettyServerStartupPropertiesWrapper properties =
                            context.getBean(NettyServerStartupPropertiesWrapper.class);

                    assertThat(properties.getHttp().isHandleFile()).isTrue();
                    assertThat(properties.getHttp().getFileLocation()).isEqualTo("legacy-public");
                    assertThat(properties.getHttp().getInfoLocation()).isEqualTo("legacy-WEB-INF");
                    assertThat(properties.getHttp().getGzip().isEnable()).isTrue();
                    assertThat(properties.getHttp().getGzip().getCompressionLevel()).isEqualTo(5);
                    assertThat(properties.getHttp().getSsl().isEnable()).isTrue();
                    assertThat(properties.getHttp().getSsl().getCertificate()).isEqualTo("legacy.crt");
                    assertThat(properties.getHttp().getSsl().getCertificateKey()).isEqualTo("legacy.key");
                });
    }

    @Test
    void websocketNamespaceBindsAllowedOrigins() {
        this.contextRunner
                .withPropertyValues(
                        "server.netty.websocket.allowed-origins=https://a.example,https://b.example",
                        "server.netty.websocket.heartbeat-interval-seconds=30",
                        "server.netty.websocket.heartbeat-timeout-seconds=90",
                        "server.netty.websocket.crypto.enable=true",
                        "server.netty.websocket.crypto.algorithm=AES-GCM",
                        "server.netty.websocket.crypto.key-id=main",
                        "server.netty.websocket.crypto.key-provider=demoProvider",
                        "server.netty.websocket.crypto.encrypt-text=true",
                        "server.netty.websocket.crypto.encrypt-binary=false",
                        "server.netty.websocket.crypto.close-on-decrypt-failure=false",
                        "server.netty.websocket.crypto.reject-unencrypted=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    NettyServerStartupPropertiesWrapper properties =
                            context.getBean(NettyServerStartupPropertiesWrapper.class);

                    assertThat(properties.getWebSocket().getAllowedOrigins())
                            .isEqualTo("https://a.example,https://b.example");
                    assertThat(properties.getWebSocket().getHeartbeatIntervalSeconds()).isEqualTo(30L);
                    assertThat(properties.getWebSocket().getHeartbeatTimeoutSeconds()).isEqualTo(90L);
                    assertThat(properties.getWebSocket().getCrypto().isEnable()).isTrue();
                    assertThat(properties.getWebSocket().getCrypto().getAlgorithm()).isEqualTo("AES-GCM");
                    assertThat(properties.getWebSocket().getCrypto().getKeyId()).isEqualTo("main");
                    assertThat(properties.getWebSocket().getCrypto().getKeyProvider()).isEqualTo("demoProvider");
                    assertThat(properties.getWebSocket().getCrypto().isEncryptText()).isTrue();
                    assertThat(properties.getWebSocket().getCrypto().isEncryptBinary()).isFalse();
                    assertThat(properties.getWebSocket().getCrypto().isCloseOnDecryptFailure()).isFalse();
                    assertThat(properties.getWebSocket().getCrypto().isRejectUnencrypted()).isFalse();
                });
    }

    @Test
    void managementNamespaceBindsHealthAndStatusPaths() {
        this.contextRunner
                .withPropertyValues(
                        "server.netty.management.enable=true",
                        "server.netty.management.health-path=/internal/health",
                        "server.netty.management.status-path=/internal/status")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    NettyServerStartupPropertiesWrapper properties =
                            context.getBean(NettyServerStartupPropertiesWrapper.class);

                    assertThat(properties.getManagement().isEnable()).isTrue();
                    assertThat(properties.getManagement().getHealthPath()).isEqualTo("/internal/health");
                    assertThat(properties.getManagement().getStatusPath()).isEqualTo("/internal/status");
                });
    }
}
