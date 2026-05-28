/*
 * Copyright 2018 berrywang1996
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.berrywang1996.netty.spring.web.util;

import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.File;

/**
 * Validation utility for {@link NettyServerStartupProperties}.
 *
 * <p>Performs startup-time validation of all server configuration properties,
 * including port, file locations, SSL certificates, HTTP timeouts, WebSocket
 * executor settings, heartbeat intervals, crypto configuration, and management
 * endpoint paths. Also creates required directories if they do not exist.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public class StartupPropertiesUtil {

    /**
     * Validates and normalizes the given startup properties, throwing descriptive
     * exceptions for any invalid configuration. Creates required directories if needed.
     *
     * @param properties the startup properties to validate
     * @throws NullPointerException     if properties are {@code null}
     * @throws IllegalArgumentException if any property value is invalid
     * @throws Exception                if directory creation or other I/O operations fail
     */
    public static void checkAndImproveProperties(NettyServerStartupProperties properties) throws Exception {
        NettyServerStartupProperties.Http httpProperties = properties == null ? null : properties.getHttp();

        if (properties == null) {
            throw new NullPointerException("Netty server startup properties properties name should not null.");
        }
        if (StringUtil.isBlank(properties.getApplicationName())) {
            throw new IllegalArgumentException("Netty application name should not blank.");
        }
        if (properties.getPort() == null || properties.getPort() <= 0) {
            throw new IllegalArgumentException("Netty port must greater than 0.");
        }
        if (properties.getConfigLocation() == null) {
            throw new IllegalArgumentException("Spring config location should not be null.");
        }
        // Reserved for future template engine support.
//        if (properties.getInfoLocation() == null) {
//            throw new IllegalArgumentException("Info location should not be null, please set your WEB-INF location.");
//        }
        if (httpProperties.getFileLocation() == null && httpProperties.isHandleFile()) {
            throw new IllegalArgumentException("File location should not be null, please set your file location.");
        }
        if (httpProperties.isHandleFile()) {
            createDirectory(httpProperties.getFileLocation());
            log.info("Netty server {} directory is \"{}\"", "root", httpProperties.getFileLocation());
        }
        validateSslProperties(httpProperties.getSsl());
        validateHttpTimeoutProperties(httpProperties);
        validateWebSocketExecutorProperties(properties.getWebSocket());
        validateManagementProperties(properties.getManagement());

//        createDirectory(properties.getInfoLocation());
//        log.info("Netty server {} directory is \"{}\"", "info", properties.getInfoLocation());

    }

    /** Validates SSL certificate and key paths when SSL is enabled. */
    private static void validateSslProperties(NettyServerStartupProperties.Ssl sslProperties) {
        if (sslProperties == null || !sslProperties.isEnable()) {
            return;
        }
        if (StringUtil.isBlank(sslProperties.getCertificate())) {
            throw new IllegalArgumentException("SSL certificate should not be blank when ssl is enabled.");
        }
        if (StringUtil.isBlank(sslProperties.getCertificateKey())) {
            throw new IllegalArgumentException("SSL certificate key should not be blank when ssl is enabled.");
        }
        validateFileExists("SSL certificate", sslProperties.getCertificate());
        validateFileExists("SSL certificate key", sslProperties.getCertificateKey());
    }

    /** Verifies that a file exists, is a regular file, and is readable. */
    private static void validateFileExists(String name, String path) {
        File file = new File(path);
        if (!file.exists() || !file.isFile() || !file.canRead()) {
            throw new IllegalArgumentException(name + " file should exist: " + path);
        }
    }

    /** Validates that HTTP read, write, and idle timeout values are non-negative. */
    private static void validateHttpTimeoutProperties(NettyServerStartupProperties.Http httpProperties) {
        if (httpProperties == null) {
            return;
        }
        if (httpProperties.getReadTimeoutSeconds() < 0L) {
            throw new IllegalArgumentException("HTTP read timeout seconds must greater than or equal to 0.");
        }
        if (httpProperties.getWriteTimeoutSeconds() < 0L) {
            throw new IllegalArgumentException("HTTP write timeout seconds must greater than or equal to 0.");
        }
        if (httpProperties.getIdleTimeoutSeconds() < 0L) {
            throw new IllegalArgumentException("HTTP idle timeout seconds must greater than or equal to 0.");
        }
    }

    /** Validates WebSocket thread pool, permit, heartbeat, and crypto configuration. */
    private static void validateWebSocketExecutorProperties(NettyServerStartupProperties.WebSocket webSocketProperties) {
        if (webSocketProperties == null) {
            return;
        }
        validateExecutorProperties(
                "websocket sender",
                webSocketProperties.getCorePoolSize(),
                webSocketProperties.getMaxPoolSize(),
                webSocketProperties.getKeepAliveTime(),
                webSocketProperties.getQueueCapacity());
        validateExecutorProperties(
                "websocket handler",
                webSocketProperties.getHandlerCorePoolSize(),
                webSocketProperties.getHandlerMaxPoolSize(),
                webSocketProperties.getHandlerKeepAliveTime(),
                webSocketProperties.getHandlerQueueCapacity());
        if (webSocketProperties.getHandlerPermitLimit() < 0) {
            throw new IllegalArgumentException("Websocket handler permit limit must greater than or equal to 0.");
        }
        if (webSocketProperties.getHeartbeatIntervalSeconds() < 0L) {
            throw new IllegalArgumentException("Websocket heartbeat interval seconds must greater than or equal to 0.");
        }
        if (webSocketProperties.getHeartbeatTimeoutSeconds() < 0L) {
            throw new IllegalArgumentException("Websocket heartbeat timeout seconds must greater than or equal to 0.");
        }
        if (webSocketProperties.getHeartbeatIntervalSeconds() > 0L
                && webSocketProperties.getHeartbeatTimeoutSeconds() > 0L
                && webSocketProperties.getHeartbeatTimeoutSeconds() < webSocketProperties.getHeartbeatIntervalSeconds()) {
            throw new IllegalArgumentException(
                    "Websocket heartbeat timeout seconds must greater than or equal to heartbeat interval seconds.");
        }
        NettyServerStartupProperties.WebSocket.Crypto cryptoProperties = webSocketProperties.getCrypto();
        if (cryptoProperties != null && cryptoProperties.isEnable()) {
            if (StringUtil.isBlank(cryptoProperties.getAlgorithm())) {
                throw new IllegalArgumentException("Websocket crypto algorithm should not be blank.");
            }
            if (!cryptoProperties.isEncryptText() && !cryptoProperties.isEncryptBinary()) {
                throw new IllegalArgumentException(
                        "Websocket crypto must enable text or binary frame encryption when crypto is enabled.");
            }
            if ("AES-GCM".equalsIgnoreCase(cryptoProperties.getAlgorithm())
                    && StringUtil.isBlank(cryptoProperties.getKeyId())) {
                throw new IllegalArgumentException("Websocket AES-GCM crypto key id should not be blank.");
            }
        }
    }

    /** Validates management endpoint paths are non-blank, start with '/', and are distinct. */
    private static void validateManagementProperties(NettyServerStartupProperties.Management managementProperties) {
        if (managementProperties == null || !managementProperties.isEnable()) {
            return;
        }
        validateManagementPath("management health path", managementProperties.getHealthPath());
        validateManagementPath("management status path", managementProperties.getStatusPath());
        if (managementProperties.getHealthPath().equals(managementProperties.getStatusPath())) {
            throw new IllegalArgumentException("management health path and status path must not be same.");
        }
    }

    /** Validates that a management endpoint path is non-blank and starts with '/'. */
    private static void validateManagementPath(String name, String path) {
        if (StringUtil.isBlank(path)) {
            throw new IllegalArgumentException(name + " should not be blank when management is enabled.");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException(name + " should start with '/'.");
        }
    }

    /** Validates that thread pool executor properties have consistent non-negative values. */
    private static void validateExecutorProperties(String name,
                                                   int corePoolSize,
                                                   int maxPoolSize,
                                                   long keepAliveTime,
                                                   int queueCapacity) {
        if (corePoolSize < 0) {
            throw new IllegalArgumentException(name + " core pool size must greater than or equal to 0.");
        }
        if (maxPoolSize < 0) {
            throw new IllegalArgumentException(name + " max pool size must greater than or equal to 0.");
        }
        if (corePoolSize > 0 && maxPoolSize > 0 && maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException(name + " max pool size must greater than or equal to core pool size.");
        }
        if (keepAliveTime < 0L) {
            throw new IllegalArgumentException(name + " keep alive time must greater than or equal to 0.");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException(name + " queue capacity must greater than or equal to 0.");
        }
    }

    /**
     * Creates the directory at the given path (including parents) if it does not already exist.
     *
     * @param path the directory path to create
     */
    private static void createDirectory(String path) {

        File root = new File(path);
        boolean mkdirs = root.mkdirs();
        if (mkdirs) {
            log.info("Directory \"{}\" has been created successfully, caused directory is not exists.", root.getPath());
        }

    }

}
