package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModeSessionRegistry;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import org.junit.jupiter.api.*;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class RedisClusterIntegrationTest {

    private static RedisClusterClient client;
    private static StatefulRedisClusterConnection<String, String> conn;

    @BeforeAll
    static void up() {
        Assumptions.assumeTrue(ClusterTestRedisCluster.available(), "no single-node Redis Cluster");
        client = ClusterTestRedisCluster.newClient();
        conn = client.connect();
    }

    @AfterAll
    static void down() {
        if (conn != null) try { conn.close(); } catch (Exception ignored) { }
        if (client != null) try { client.shutdown(); } catch (Exception ignored) { }
    }

    @Test
    void registry_registerLookupDeregister() {
        RedisClusterModeSessionRegistry reg = new RedisClusterModeSessionRegistry(conn);
        reg.register("/ws/ic", "sid-1", "node-A", Collections.emptyMap()).toCompletableFuture().join();
        assertEquals("node-A", reg.lookupNode("/ws/ic", "sid-1").toCompletableFuture().join());
        reg.deregister("/ws/ic", "sid-1").toCompletableFuture().join();
        assertNull(reg.lookupNode("/ws/ic", "sid-1").toCompletableFuture().join());
    }
}
