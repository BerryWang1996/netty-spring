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

package com.github.berrywang1996.netty.spring.boot.configure;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Resolves a usable NATS server for the NATS broker integration tests, once per JVM:
 *   env CLUSTER_TEST_NATS_URL → a Testcontainers nats:2.10 → none (tests skip).
 */
public final class ClusterTestNats {

    static {
        // Same docker-java API-probe workaround as ClusterTestRedisCluster (modern Docker rejects the
        // hardcoded /v1.32 probe). Pin a supported negotiated API version before the first Docker call.
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", "1.43");
        }
    }

    private static volatile boolean resolved;
    private static volatile String url;
    @SuppressWarnings("resource")
    private static GenericContainer<?> container; // singleton; reaped by Ryuk at JVM exit

    private ClusterTestNats() {
    }

    public static synchronized boolean available() {
        resolve();
        return url != null;
    }

    public static synchronized String url() {
        resolve();
        if (url == null) {
            throw new IllegalStateException("No NATS server available (no env and no Docker)");
        }
        return url;
    }

    /** A fresh NATS connection to the resolved server (caller closes it). */
    public static Connection newConnection() throws Exception {
        return Nats.connect(Options.builder().server(url()).build());
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        String env = System.getenv("CLUSTER_TEST_NATS_URL");
        if (env != null && !env.isBlank()) {
            url = env.trim();
            return;
        }
        if (!dockerAvailable()) {
            return;
        }
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse("nats:2.10"))
                .withExposedPorts(4222)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));
        c.start();
        container = c;
        url = "nats://" + c.getHost() + ":" + c.getMappedPort(4222);
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
