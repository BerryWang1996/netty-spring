package com.github.berrywang1996.netty.spring.web.util;

import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupPropertiesUtilTest {

    @TempDir
    Path tempDir;

    @Test
    void checkAndImprovePropertiesUsesHttpNamespaceForStaticFileSettings() throws Exception {
        Path rootPath = tempDir.resolve("public");
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.getHttp().setHandleFile(true);
        properties.getHttp().setFileLocation(rootPath.toString());

        StartupPropertiesUtil.checkAndImproveProperties(properties);

        assertTrue(rootPath.toFile().isDirectory());
        assertTrue(properties.isHandleFile());
        assertEquals(rootPath.toString(), properties.getFileLocation());
    }

    @Test
    void legacyStaticFileSettingsStillFeedHttpNamespaceView() throws Exception {
        Path rootPath = tempDir.resolve("legacy-public");
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.setHandleFile(true);
        properties.setFileLocation(rootPath.toString());

        StartupPropertiesUtil.checkAndImproveProperties(properties);

        assertTrue(rootPath.toFile().isDirectory());
        assertTrue(properties.getHttp().isHandleFile());
        assertEquals(rootPath.toString(), properties.getHttp().getFileLocation());
    }

    @Test
    void staticFileHandlingRequiresFileLocationFromHttpNamespace() {
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.getHttp().setHandleFile(true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StartupPropertiesUtil.checkAndImproveProperties(properties));
        assertTrue(exception.getMessage().contains("File location should not be null"));
    }

    @Test
    void websocketHandlerMaxPoolSizeMustNotBeLessThanCorePoolSize() {
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.getWebSocket().setHandlerCorePoolSize(4);
        properties.getWebSocket().setHandlerMaxPoolSize(2);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StartupPropertiesUtil.checkAndImproveProperties(properties));
        assertTrue(exception.getMessage().contains("websocket handler max pool size"));
    }

    @Test
    void websocketHandlerQueueCapacityMustNotBeNegative() {
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.getWebSocket().setHandlerQueueCapacity(-1);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StartupPropertiesUtil.checkAndImproveProperties(properties));
        assertTrue(exception.getMessage().contains("websocket handler queue capacity"));
    }

    @Test
    void websocketSenderMaxPoolSizeMustNotBeLessThanCorePoolSize() {
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.getWebSocket().setCorePoolSize(8);
        properties.getWebSocket().setMaxPoolSize(4);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StartupPropertiesUtil.checkAndImproveProperties(properties));
        assertTrue(exception.getMessage().contains("websocket sender max pool size"));
    }

    @Test
    void httpTimeoutSecondsMustNotBeNegative() {
        NettyServerStartupProperties readTimeoutProperties = new NettyServerStartupProperties();
        readTimeoutProperties.getHttp().setReadTimeoutSeconds(-1L);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StartupPropertiesUtil.checkAndImproveProperties(readTimeoutProperties));
        assertTrue(exception.getMessage().contains("HTTP read timeout seconds"));

        NettyServerStartupProperties writeTimeoutProperties = new NettyServerStartupProperties();
        writeTimeoutProperties.getHttp().setWriteTimeoutSeconds(-1L);

        exception = assertThrows(
                IllegalArgumentException.class,
                () -> StartupPropertiesUtil.checkAndImproveProperties(writeTimeoutProperties));
        assertTrue(exception.getMessage().contains("HTTP write timeout seconds"));

        NettyServerStartupProperties idleTimeoutProperties = new NettyServerStartupProperties();
        idleTimeoutProperties.getHttp().setIdleTimeoutSeconds(-1L);

        exception = assertThrows(
                IllegalArgumentException.class,
                () -> StartupPropertiesUtil.checkAndImproveProperties(idleTimeoutProperties));
        assertTrue(exception.getMessage().contains("HTTP idle timeout seconds"));
    }

    @Test
    void sslRequiresCertificateAndKeyWhenEnabled() {
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.getHttp().getSsl().setEnable(true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StartupPropertiesUtil.checkAndImproveProperties(properties));
        assertTrue(exception.getMessage().contains("SSL certificate should not be blank"));

        properties.getHttp().getSsl().setCertificate("server.crt");
        exception = assertThrows(
                IllegalArgumentException.class,
                () -> StartupPropertiesUtil.checkAndImproveProperties(properties));
        assertTrue(exception.getMessage().contains("SSL certificate key should not be blank"));
    }

    @Test
    void sslRequiresExistingCertificateFilesWhenEnabled() {
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.getHttp().getSsl().setEnable(true);
        properties.getHttp().getSsl().setCertificate(tempDir.resolve("missing.crt").toString());
        properties.getHttp().getSsl().setCertificateKey(tempDir.resolve("missing.key").toString());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StartupPropertiesUtil.checkAndImproveProperties(properties));
        assertTrue(exception.getMessage().contains("SSL certificate file should exist"));
    }

    @Test
    void sslAcceptsExistingCertificateFilesWhenEnabled() throws Exception {
        Path certificate = tempDir.resolve("server.crt");
        Path certificateKey = tempDir.resolve("server.key");
        Files.write(certificate, "cert".getBytes(StandardCharsets.UTF_8));
        Files.write(certificateKey, "key".getBytes(StandardCharsets.UTF_8));
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.getHttp().getSsl().setEnable(true);
        properties.getHttp().getSsl().setCertificate(certificate.toString());
        properties.getHttp().getSsl().setCertificateKey(certificateKey.toString());

        StartupPropertiesUtil.checkAndImproveProperties(properties);
    }
}
