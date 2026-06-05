package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.MessageMapping;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.NodeState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In-process two-node end-to-end test: two full Spring Boot nodes (real Netty WebSocket servers +
 * ClusterMessageSender, cluster mode on, HMAC on) share one Redis. A real WebSocket client connects
 * to node A; node B broadcasts/unicasts; the client on A must receive it (cross-node). Proves the
 * full MessageSender -> broker -> registry -> live session path across nodes, plus a metric.
 */
class ClusterMultiNodeE2ETest {

    private static final String URI_PATH = "/ws/e2e";
    private static final String SECRET = "e2e-cluster-shared-secret-32-chars!!";

    private static boolean ready;
    private static int portA;
    private static ConfigurableApplicationContext nodeA;
    private static ConfigurableApplicationContext nodeB;

    @BeforeAll
    static void startNodes() throws Exception {
        Assumptions.assumeTrue(ClusterTestRedis.available(), "no Redis and no Docker");
        String redisUri = ClusterTestRedis.uri();
        wipe(redisUri);
        portA = freePort();
        int portB = freePort();
        nodeA = startNode("e2e-node-A", portA, redisUri);
        nodeB = startNode("e2e-node-B", portB, redisUri);
        // Wait until both nodes are ACTIVE so cross-node broadcasts are not skipped-degraded.
        waitUntil(() -> state(nodeA) == NodeState.ACTIVE && state(nodeB) == NodeState.ACTIVE, 15000);
        assertEquals(NodeState.ACTIVE, state(nodeA), "node A must reach ACTIVE");
        assertEquals(NodeState.ACTIVE, state(nodeB), "node B must reach ACTIVE");
        ready = true;
    }

    @AfterAll
    static void stopNodes() {
        if (nodeA != null) {
            nodeA.close();
        }
        if (nodeB != null) {
            nodeB.close();
        }
    }

    @Test
    void broadcastFromNodeBReachesClientOnNodeA() throws Exception {
        assertTrue(ready);
        nodeA.getBean(E2EController.class).reset();
        List<String> received = new CopyOnWriteArrayList<>();
        WebSocket ws = connectTo(portA, received);
        try {
            String sid = awaitSessionOnNodeA();
            assertNotNull(sid, "node A must have registered the connecting session");
            Thread.sleep(500); // let the cross-node subscription settle

            nodeB.getBean(MessageSender.class).broadcastText(URI_PATH, "hello-from-B");

            waitUntil(() -> received.stream().anyMatch(s -> s.contains("hello-from-B")), 8000);
            assertTrue(received.stream().anyMatch(s -> s.contains("hello-from-B")),
                    "client on node A must receive the broadcast published from node B; got=" + received);

            MeterRegistry regB = nodeB.getBean(MeterRegistry.class);
            assertTrue(regB.get("netty.cluster.broadcast.published").functionCounter().count() >= 1.0,
                    "node B netty.cluster.broadcast.published must increment on the real broadcast path");
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void unicastFromNodeBRoutesToSessionOnNodeA() throws Exception {
        assertTrue(ready);
        nodeA.getBean(E2EController.class).reset();
        List<String> received = new CopyOnWriteArrayList<>();
        WebSocket ws = connectTo(portA, received);
        try {
            String sid = awaitSessionOnNodeA();
            assertNotNull(sid, "node A must have registered the connecting session");

            // The distributed session registry is written asynchronously (CoalescingRegistryWriter),
            // so node B may not see the just-connected session immediately. Wait until node B can
            // resolve it through the registry (queried directly here as ground truth) before issuing
            // the unicast, so the single send routes deterministically instead of racing the async
            // registration. (ClusterMessageSender does NOT cache lookup misses — it self-heals on the
            // next send — but gating on the registry keeps this test from flaking on timing.)
            SessionRegistry registryB = nodeB.getBean(SessionRegistry.class);
            waitUntil(() -> "e2e-node-A".equals(lookupNode(registryB, sid)), 8000);
            assertEquals("e2e-node-A", lookupNode(registryB, sid),
                    "node B must resolve node A's session through the distributed registry");

            nodeB.getBean(MessageSender.class).sendTextToSession(URI_PATH, "dm-from-B", sid);

            waitUntil(() -> received.stream().anyMatch(s -> s.contains("dm-from-B")), 8000);
            assertTrue(received.stream().anyMatch(s -> s.contains("dm-from-B")),
                    "client on node A must receive the cross-node unicast from node B; got=" + received);
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    // ----- helpers -----

    private static ConfigurableApplicationContext startNode(String nodeId, int port, String redisUri) {
        return new SpringApplicationBuilder(E2ETestApp.class)
                .properties(
                        "server.netty.port=" + port,
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + redisUri,
                        "server.netty.websocket.cluster.node-id=" + nodeId,
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=2",
                        // drain-timeout=0 so node context shutdown is immediate (FIX D folds a bounded
                        // drain grace into shutdown; the 60s default would make @AfterAll teardown crawl).
                        "server.netty.websocket.cluster.drain-timeout-seconds=0",
                        "server.netty.websocket.cluster.auth.enable=true",
                        "server.netty.websocket.cluster.auth.secret=" + SECRET,
                        "logging.level.root=warn")
                .run();
    }

    private static WebSocket connectTo(int port, List<String> sink) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + URI_PATH), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(Long.MAX_VALUE);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        sink.add(data.toString());
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);
    }

    private static String awaitSessionOnNodeA() throws InterruptedException {
        E2EController ctrl = nodeA.getBean(E2EController.class);
        waitUntil(() -> ctrl.lastSessionId() != null, 5000);
        return ctrl.lastSessionId();
    }

    private static NodeState state(ConfigurableApplicationContext ctx) {
        return ctx.getBean(ClusterNodeManager.class).getState();
    }

    /** Direct registry lookup (bypasses the sender cache); returns the owning nodeId or null. */
    private static String lookupNode(SessionRegistry registry, String sessionId) {
        try {
            return registry.lookupNode(URI_PATH, sessionId).toCompletableFuture().get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void waitUntil(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    private static void wipe(String redisUri) {
        RedisClient c = RedisClient.create(redisUri);
        try (StatefulRedisConnection<String, String> conn = c.connect()) {
            conn.sync().eval("for _,k in ipairs(redis.call('keys','netty:*')) do redis.call('del',k) end",
                    ScriptOutputType.INTEGER);
        } catch (Exception ignored) {
        } finally {
            c.shutdown();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(E2EController.class)
    static class E2ETestApp {
        @Bean
        SimpleMeterRegistry simpleMeterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Controller
    public static class E2EController {
        private volatile String lastSessionId;

        String lastSessionId() {
            return lastSessionId;
        }

        void reset() {
            this.lastSessionId = null;
        }

        @MessageMapping(value = URI_PATH, messageType = MessageType.ON_CONNECTED)
        public void onConnected(MessageSession session) {
            this.lastSessionId = session.getSessionId();
        }

        @MessageMapping(value = URI_PATH, messageType = MessageType.TEXT_MESSAGE)
        public void onText(String text, MessageSession session) {
            // inbound from client not needed for the assertions; handler presence registers the URI
        }
    }
}
