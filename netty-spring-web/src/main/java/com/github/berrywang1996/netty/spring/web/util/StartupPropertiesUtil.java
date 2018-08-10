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
import com.sun.istack.internal.NotNull;
import lombok.extern.slf4j.Slf4j;

import java.io.File;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
@Slf4j
public class StartupPropertiesUtil {

    public static void checkProperties(@NotNull NettyServerStartupProperties properties) throws Exception {

        if (StringUtil.isBlank(properties.getApplicationName())) {
            throw new IllegalArgumentException("Netty application name should not blank.");
        }
        if (properties.getPort() == null || properties.getPort() <= 0) {
            throw new IllegalArgumentException("Netty port must greater than 0.");
        }
        if (properties.getConfigLocation() == null) {
            throw new IllegalArgumentException("Spring config location should not be null.");
        }
        if (properties.getRootLocation() == null) {
            throw new IllegalArgumentException("Netty root location should not be null.");
        }
        if (properties.getInfoLocation() == null) {
            throw new IllegalArgumentException("Netty info location should not be null.");
        }

        createDirectory(properties.getRootLocation());
        log.info("Netty server {} directory is \"{}\"", "root", properties.getRootLocation());

        createDirectory(properties.getInfoLocation());
        log.info("Netty server {} directory is \"{}\"", "info", properties.getInfoLocation());

    }

    private static void createDirectory(String path) {

        File root = new File(path);
        boolean mkdirs = root.mkdirs();
        if (mkdirs) {
            log.info("Directory \"{}\" has been created successfully, caused directory is not exists.", root.getPath());
        }

    }

}