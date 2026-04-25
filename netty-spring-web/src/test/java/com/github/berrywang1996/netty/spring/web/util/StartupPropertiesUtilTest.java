package com.github.berrywang1996.netty.spring.web.util;

import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
