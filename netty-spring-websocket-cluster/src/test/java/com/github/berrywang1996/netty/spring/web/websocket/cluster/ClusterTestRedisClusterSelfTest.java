package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClusterTestRedisClusterSelfTest {

    @Test
    void connectsToSingleNodeCluster() {
        Assumptions.assumeTrue(ClusterTestRedisCluster.available(), "no Redis Cluster and no Docker");
        RedisClusterClient client = ClusterTestRedisCluster.newClient();
        try (StatefulRedisClusterConnection<String, String> conn = client.connect()) {
            assertEquals("PONG", conn.sync().ping().toUpperCase());
            conn.sync().set("netty:clustertest:probe", "v");
            assertEquals("v", conn.sync().get("netty:clustertest:probe"));
        } finally {
            client.shutdown();
        }
    }
}
