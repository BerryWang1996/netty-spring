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

package com.github.berrywang1996.netty.spring.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the netty-spring demo application.
 *
 * <p>This Spring Boot application bootstraps an embedded Netty server and
 * registers demo HTTP and WebSocket endpoints that showcase the features
 * provided by the {@code netty-spring-boot-starter}. It is intended for
 * local development, manual testing, and as a reference implementation.
 *
 * <p>Run with optional Spring profiles to enable additional features:
 * <ul>
 *   <li>{@code auth-demo} - enables token-based WebSocket handshake authentication</li>
 *   <li>{@code crypto-demo} - enables AES-GCM encrypted WebSocket messaging</li>
 * </ul>
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@SpringBootApplication
public class DemoApplication {

    /**
     * Application entry point. Starts the Spring Boot application which
     * triggers the Netty server auto-configuration.
     *
     * @param args command-line arguments forwarded to {@link SpringApplication#run}
     */
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

}
