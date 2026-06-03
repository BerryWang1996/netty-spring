package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClusterTestRedisSelfTest {

    @Test
    void resolvesAPingableRedis() {
        Assumptions.assumeTrue(ClusterTestRedis.available(), "no Redis and no Docker — nothing to resolve");
        String uri = ClusterTestRedis.uri();
        assertTrue(uri.startsWith("redis://"), "resolved uri must be a redis URI: " + uri);
        RedisClient c = ClusterTestRedis.newClient();
        try (StatefulRedisConnection<String, String> conn = c.connect()) {
            assertEquals("PONG", conn.sync().ping().toUpperCase());
        } finally {
            c.shutdown();
        }
    }
}
