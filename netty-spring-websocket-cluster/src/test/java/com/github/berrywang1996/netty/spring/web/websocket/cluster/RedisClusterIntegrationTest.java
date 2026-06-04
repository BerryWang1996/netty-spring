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

    @Test
    void heartbeat_registerFindExpiredDeregister() throws InterruptedException {
        com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModeNodeHeartbeat hb =
                new com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModeNodeHeartbeat(conn);
        hb.register("hb-node", 200);
        Thread.sleep(400); // let the heartbeat key TTL-expire
        assertTrue(hb.findExpiredNodes(100).contains("hb-node"), "stale node detected via per-key EXISTS");
        hb.deregister("hb-node");
        assertFalse(hb.findExpiredNodes(100).contains("hb-node"), "deregistered node gone from the nodes hash");
    }
}
