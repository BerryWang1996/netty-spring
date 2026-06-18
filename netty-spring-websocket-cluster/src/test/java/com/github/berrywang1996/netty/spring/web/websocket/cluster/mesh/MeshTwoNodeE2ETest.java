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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.mesh;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterRuntimeStats;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterTestRedis;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RC4a headline E2E: two MeshBrokers on real localhost TCP, discovering each other through a <b>real Redis</b>
 * {@link RedisMeshNodeDirectory} (not the in-memory stub). Proves the full discover→connect→deliver path — broadcast
 * and direct unicast ride TCP, with Redis used only for node-address discovery (off the message hot path).
 */
class MeshTwoNodeE2ETest {

    private RedisClient client;
    private StatefulRedisConnection<String, String> conn;
    private MeshBroker a;
    private MeshBroker b;
    private final List<String> recvA = new CopyOnWriteArrayList<>();

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(ClusterTestRedis.available(), "Redis not available");
        client = RedisClient.create(ClusterTestRedis.uri());
        conn = client.connect();
        // Start from a clean mesh keyspace so stale advertisements from other runs don't add noise.
        for (String k : conn.sync().keys("netty:mesh:addr:*")) {
            conn.sync().del(k);
        }
        a = newBroker("e2e-mesh-A", freePort());
        b = newBroker("e2e-mesh-B", freePort());
        a.start();
        b.start();
    }

    private MeshBroker newBroker(String nodeId, int port) {
        return new MeshBroker(nodeId, new RedisMeshNodeDirectory(conn), new SimpleTextEnvelopeCodec(),
                new NoOpMessageAuthenticator(), new ClusterRuntimeStats(),
                "127.0.0.1", port, "127.0.0.1", 1_048_576, 30000, 32768, 65536);
    }

    @AfterEach
    void tearDown() {
        if (a != null) {
            a.shutdown();
        }
        if (b != null) {
            b.shutdown();
        }
        if (conn != null) {
            conn.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    private static boolean waitFor(BooleanSupplier cond, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return cond.getAsBoolean();
    }

    private static ClusterEnvelope broadcast(String origin, String uri, String body) {
        return new ClusterEnvelope(origin, uri, ClusterEnvelope.MessageKind.BROADCAST,
                body.getBytes(StandardCharsets.UTF_8), null, null, 1L);
    }

    private static ClusterEnvelope unicast(String origin, String uri, String sessionId, String body) {
        return new ClusterEnvelope(origin, uri, ClusterEnvelope.MessageKind.UNICAST,
                body.getBytes(StandardCharsets.UTF_8), sessionId, null, 1L);
    }

    private static String body(ClusterEnvelope e) {
        return new String(e.getPayload(), StandardCharsets.UTF_8);
    }

    @Test
    void broadcastAndUnicast_overTcp_viaRealRedisDirectory() throws Exception {
        a.subscribe("/ws/chat", env -> recvA.add("BC:" + body(env)));
        a.subscribeUnicast("e2e-mesh-A", env -> recvA.add("UC:" + body(env)));

        // wait until B's directory (real Redis) shows A's advertised address
        assertTrue(waitFor(() -> b.directoryForTest().peers("e2e-mesh-B").toCompletableFuture().join()
                .containsKey("e2e-mesh-A"), 5000), "B discovered A via the real Redis directory");

        // broadcast B → A over TCP, retrying the publish until A receives (covers the advertise/connect warm-up)
        assertTrue(waitFor(() -> {
            b.publish("/ws/chat", broadcast("e2e-mesh-B", "/ws/chat", "hello-room"));
            return recvA.contains("BC:hello-room");
        }, 5000), "node-A received the broadcast over TCP (via real Redis discovery)");

        // direct unicast B → A over TCP
        assertTrue(waitFor(() -> {
            b.unicast("e2e-mesh-A", unicast("e2e-mesh-B", "/ws/chat", "s1", "dm-B"));
            return recvA.contains("UC:dm-B");
        }, 5000), "node-A received the direct unicast over TCP");
    }
}
