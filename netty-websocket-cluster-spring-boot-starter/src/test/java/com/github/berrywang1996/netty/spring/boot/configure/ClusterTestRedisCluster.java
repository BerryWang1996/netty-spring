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

import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Collections;

/**
 * Resolves a usable SINGLE-NODE Redis Cluster for cluster-client integration tests, once per JVM:
 * <ol>
 *   <li>env {@code CLUSTER_TEST_REDIS_CLUSTER_NODES} (host:port) if a cluster is reachable there,</li>
 *   <li>a Testcontainers {@code redis:7} started with {@code --cluster-enabled yes}, all 16384 slots
 *       assigned to the single node ({@code cluster_state:ok}), reachable directly at the mapped port
 *       (single node = no gossip),</li>
 *   <li>none -&gt; {@link #available()} == false (tests skip).</li>
 * </ol>
 *
 * <p>Connection-options note (critical for the later cluster ITs): a single-node cluster started in a
 * Testcontainer advertises its own container-internal {@code host:6379} in the cluster topology
 * ({@code CLUSTER NODES} / {@code CLUSTER SLOTS}), which is NOT reachable from the host. Lettuce's
 * {@link RedisClusterClient} would normally refresh its topology from that gossiped address and then
 * fail to reconnect. To keep the client pinned to the reachable seed (the mapped port), every client
 * minted here is configured via {@link #clusterClientOptions()} with:
 * <ul>
 *   <li>periodic + adaptive topology refresh DISABLED — Lettuce never re-resolves to the internal port,</li>
 *   <li>{@code validateClusterNodeMembership(false)} — Lettuce does not reject the seed for not appearing
 *       under its advertised address.</li>
 * </ul>
 * The single owning node holds all 16384 slots, so the seed connection can serve every key; no
 * MOVED/redirect bouncing to an unreachable node occurs.
 *
 * <p>Intentionally a self-contained test-support class (no Maven {@code test-jar}, mirroring
 * {@link ClusterTestRedis}): a {@code test-jar} binds to the {@code package} phase and would break the
 * project's {@code mvn test} workflow.
 */
public final class ClusterTestRedisCluster {

    static {
        // docker-java 3.4.0 (bundled by Testcontainers 1.20.4) negotiates the Docker API with a
        // hardcoded fallback of /v1.32/ for its first probe. A modern daemon (Docker 29.x advertises
        // ApiVersion 1.54, MinAPIVersion 1.40) rejects /v1.32/ with HTTP 400 Bad Request, so
        // DockerClientFactory reports "no valid Docker environment" and these tests would SKIP even
        // though Docker is live. Pinning docker-java's negotiated API version to one inside
        // [MinAPIVersion, ApiVersion] makes the probe (and all subsequent calls) succeed. 1.43 is well
        // within range and fully supported by docker-java 3.4.0. Set as a system property because
        // docker-java's DefaultDockerClientConfig reads the "api.version" property; do this in a static
        // initializer so it is applied before the first DockerClientFactory.instance() call. Respect an
        // explicit override if the operator already set one (e.g. for a remote daemon).
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", "1.43");
        }
    }

    private static volatile boolean resolved;
    private static volatile String nodes;
    @SuppressWarnings("resource")
    private static GenericContainer<?> container; // singleton; reaped by Testcontainers Ryuk at JVM exit

    private ClusterTestRedisCluster() {
    }

    public static synchronized boolean available() {
        resolve();
        return nodes != null;
    }

    public static synchronized String nodes() {
        resolve();
        if (nodes == null) {
            throw new IllegalStateException("No single-node Redis Cluster available");
        }
        return nodes;
    }

    public static RedisClusterClient newClient() {
        String[] hp = nodes().split(":");
        RedisClusterClient client = RedisClusterClient.create(Collections.singletonList(
                RedisURI.create(hp[0], Integer.parseInt(hp[1]))));
        client.setOptions(clusterClientOptions());
        return client;
    }

    /**
     * Cluster client options that keep Lettuce pinned to the reachable seed address for a single-node
     * cluster behind a Testcontainers port mapping (see class javadoc). Reused by the ITs so they
     * inherit the same topology-pinning behaviour.
     */
    public static ClusterClientOptions clusterClientOptions() {
        // Adaptive refresh triggers and periodic refresh are BOTH off by default in
        // ClusterTopologyRefreshOptions; we make periodic-off explicit and simply never enable the
        // adaptive triggers, so Lettuce never re-resolves the topology to the container-internal port.
        return ClusterClientOptions.builder()
                .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                        .enablePeriodicRefresh(false)
                        .build())
                .validateClusterNodeMembership(false)
                .build();
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        String env = System.getenv("CLUSTER_TEST_REDIS_CLUSTER_NODES");
        if (env != null && !env.isBlank() && clusterReachable(env)) {
            nodes = env.trim();
            return;
        }
        if (!dockerAvailable()) {
            return;
        }
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse("redis:7"))
                .withExposedPorts(6379)
                .withCommand("redis-server", "--cluster-enabled", "yes", "--cluster-node-timeout", "2000",
                        "--appendonly", "no", "--save", "")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));
        c.start();
        try {
            c.execInContainer("redis-cli", "cluster", "addslotsrange", "0", "16383");
            long deadline = System.currentTimeMillis() + 15000;
            boolean ok = false;
            while (System.currentTimeMillis() < deadline) {
                var res = c.execInContainer("redis-cli", "cluster", "info");
                if (res.getStdout() != null && res.getStdout().contains("cluster_state:ok")) {
                    ok = true;
                    break;
                }
                Thread.sleep(200);
            }
            if (!ok) {
                c.stop();
                return;
            }
        } catch (Exception e) {
            try {
                c.stop();
            } catch (Exception ignored) {
            }
            return;
        }
        container = c;
        nodes = c.getHost() + ":" + c.getMappedPort(6379);
    }

    private static boolean clusterReachable(String hostPort) {
        try {
            String[] hp = hostPort.trim().split(":");
            RedisClusterClient client = RedisClusterClient.create(
                    Collections.singletonList(RedisURI.create(hp[0], Integer.parseInt(hp[1]))));
            client.setOptions(clusterClientOptions());
            try (StatefulRedisClusterConnection<String, String> conn = client.connect()) {
                return "PONG".equalsIgnoreCase(conn.sync().ping());
            } finally {
                client.shutdown();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
