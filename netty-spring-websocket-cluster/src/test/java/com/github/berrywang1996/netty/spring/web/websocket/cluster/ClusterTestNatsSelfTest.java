package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import io.nats.client.Connection;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ClusterTestNatsSelfTest {
    @Test
    void connectsToNats() throws Exception {
        Assumptions.assumeTrue(ClusterTestNats.available(), "no NATS and no Docker");
        try (Connection conn = ClusterTestNats.newConnection()) {
            assertNotNull(conn.getStatus());
            conn.flush(java.time.Duration.ofSeconds(2));
        }
    }
}
