package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Resolves a usable Redis for cluster integration tests, once per JVM, in order:
 * <ol>
 *   <li>{@code CLUSTER_TEST_REDIS_URI} env var (if reachable),</li>
 *   <li>{@code redis://localhost:16379} (if reachable) — preserves the fast local loop,</li>
 *   <li>a Testcontainers {@code redis:7-alpine} singleton — makes CI run these tests,</li>
 *   <li>none → {@link #available()} is false and tests skip.</li>
 * </ol>
 *
 * <p>Intentionally duplicated in the cluster module and the cluster-starter test trees: a Maven
 * {@code test-jar} binds to the {@code package} phase and would break the project's {@code mvn test}
 * workflow, so a small copy in each module is the lower-risk choice.
 */
public final class ClusterTestRedis {

    private static volatile boolean resolved;
    private static volatile String uri;
    @SuppressWarnings("resource")
    private static GenericContainer<?> container; // singleton; reaped by Testcontainers Ryuk at JVM exit

    private ClusterTestRedis() {
    }

    public static synchronized boolean available() {
        resolve();
        return uri != null;
    }

    public static synchronized String uri() {
        resolve();
        if (uri == null) {
            throw new IllegalStateException("No Redis available (no localhost:16379 and no Docker)");
        }
        return uri;
    }

    public static RedisClient newClient() {
        return RedisClient.create(uri());
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        String env = System.getenv("CLUSTER_TEST_REDIS_URI");
        if (env != null && !env.isBlank() && pingable(env)) {
            uri = env;
            return;
        }
        if (pingable("redis://localhost:16379")) {
            uri = "redis://localhost:16379";
            return;
        }
        if (dockerAvailable()) {
            container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
            container.start();
            uri = "redis://" + container.getHost() + ":" + container.getMappedPort(6379);
        }
    }

    private static boolean pingable(String redisUri) {
        RedisClient c = null;
        try {
            c = RedisClient.create(redisUri);
            c.setDefaultTimeout(Duration.ofSeconds(2));
            try (StatefulRedisConnection<String, String> conn = c.connect()) {
                return "PONG".equalsIgnoreCase(conn.sync().ping());
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) {
                try {
                    c.shutdown();
                } catch (Exception ignored) {
                }
            }
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
