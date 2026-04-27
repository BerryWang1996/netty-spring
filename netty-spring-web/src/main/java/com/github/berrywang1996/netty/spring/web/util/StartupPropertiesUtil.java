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
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public class StartupPropertiesUtil {

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

    private static void validateFileExists(String name, String path) {
        File file = new File(path);
        if (!file.exists() || !file.isFile() || !file.canRead()) {
            throw new IllegalArgumentException(name + " file should exist: " + path);
        }
    }

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
    }

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

    private static void validateManagementPath(String name, String path) {
        if (StringUtil.isBlank(path)) {
            throw new IllegalArgumentException(name + " should not be blank when management is enabled.");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException(name + " should start with '/'.");
        }
    }

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

    private static void createDirectory(String path) {

        File root = new File(path);
        boolean mkdirs = root.mkdirs();
        if (mkdirs) {
            log.info("Directory \"{}\" has been created successfully, caused directory is not exists.", root.getPath());
        }

    }

}
