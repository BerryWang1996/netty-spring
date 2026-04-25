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
                        "server.netty.http.gzip.enable=true",
                        "server.netty.http.gzip.compression-level=7",
                        "server.netty.http.gzip.window-bits=13",
                        "server.netty.http.gzip.mem-level=6",
                        "server.netty.http.gzip.content-size-threshold=128",
                        "server.netty.http.gzip.types=text/plain application/json",
                        "server.netty.http.ssl.enable=true",
                        "server.netty.http.ssl.certificate=server.crt",
                        "server.netty.http.ssl.certificate-key=server.key")
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
}
