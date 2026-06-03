# Multi-Node Cluster E2E + Testcontainers CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the cluster integration tests actually run in CI (they silently skip today — CI has no Redis) via a Testcontainers-Redis resolver, and add one in-process two-node end-to-end test proving cross-node broadcast + unicast over real WebSockets with HMAC on and a metric assertion.

**Architecture:** A `ClusterTestRedis` test-support resolver picks a usable Redis once per JVM (env override → `localhost:16379` → Testcontainers `redis:7-alpine` → skip). The 3 existing cluster ITs + the starter context test are retrofitted to it (assertions unchanged). A new `ClusterMultiNodeE2ETest` boots two full `SpringApplicationBuilder` nodes (real Netty WS servers + `ClusterMessageSender`, cluster on, HMAC on, shared Redis) and drives them with a JDK `HttpClient` WebSocket client.

**Tech Stack:** Java 17, Spring Boot 2.7.18 (`SpringApplicationBuilder`), Testcontainers (Boot-managed version), Lettuce, JUnit 5, JDK `java.net.http.WebSocket`, Micrometer (`SimpleMeterRegistry`). Spec: `docs/superpowers/specs/2026-06-03-multinode-e2e-testcontainers-design.md`. Develops on `1.9.0-RC4`.

---

## Environment notes for every task
- Repo root: `C:\Users\qq951\IdeaProjects\netty-spring`; Windows (PowerShell + Bash); Maven 3.9.9 (Aliyun mirror); Java 17. **Docker is live (29.5.2); Redis is live on `localhost:16379`.**
- Git: work on branch `feature/1.9.0-multinode-e2e` (Task 0). Do NOT push/deploy.
- For `mvn -pl <module> -am -Dtest=<name>`, ALWAYS add `-Dsurefire.failIfNoSpecifiedTests=false` (the `-am` reactor pulls sibling modules with no matching test).
- Docs edits: **Edit tool ONLY, never PowerShell redirection**; after editing, verify UTF-8 + scan for U+FFFD.
- Match on quoted code, not line numbers.

## Confirmed facts (verified — do not re-derive)
- The parent imports `spring-boot-dependencies` 2.7.18, which **manages Testcontainers versions** → declare `org.testcontainers:testcontainers` with **no `<version>`**.
- A `test-jar` is NOT used (its `package`-phase binding breaks the project's `mvn test` flow) — `ClusterTestRedis` is **duplicated** in both modules' test trees (a ~60-line test util; deliberate).
- Starting a real node: `new SpringApplicationBuilder(App.class).run("--server.netty.port=N")` (see `DemoApplicationSmokeTest`). Free port: `try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }` (see `NettyServerBootstrapConfigureTest.findAvailablePort`).
- WS endpoint URL = `ws://host:port/<mapping-uri>` (path is the `@MessageMapping` value; query optional — see the demo's `/ws/chat?nickname=`).
- `MessageType.ON_CONNECTED` handler signature `(MessageSession session)` → `session.getSessionId()` captures the connected session id. `MessageType.TEXT_MESSAGE` → `(String text, MessageSession session)`.
- A WS handler bean is discovered when it is a `@Controller` registered as a bean (e.g. `@Import(TheController.class)` — see `NettyServerBootstrapConfigureTest`).
- `MessageSender` (the `@Primary` bean in cluster mode is `ClusterMessageSender`) has default methods `broadcastText(uri, text)` (→ `topicMessage`) and `sendTextToSession(uri, text, sessionId)` (→ `sendMessage`), plus `getSessionNums(uri)`.
- Cluster props: `server.netty.websocket.cluster.{enable,redis.uri,node-id,heartbeat-interval-seconds,auth.enable,auth.secret}`. Server port: `server.netty.port`.
- `NodeState` (`...cluster.node.NodeState`) values include `ACTIVE`; `ClusterNodeManager` (`...cluster.node.ClusterNodeManager`) has `getState()`.
- The 4 tests to retrofit all currently declare `private static final String REDIS_URI = "redis://localhost:16379";` and a `@BeforeAll` that pings it and sets a `redisAvailable` boolean.

## File Structure
- New: `netty-spring-websocket-cluster/src/test/java/.../cluster/ClusterTestRedis.java`; the **same file duplicated** at `netty-websocket-cluster-spring-boot-starter/src/test/java/.../boot/configure/ClusterTestRedis.java` (different package).
- New: `netty-spring-websocket-cluster/src/test/java/.../cluster/ClusterTestRedisSelfTest.java`.
- New: `netty-websocket-cluster-spring-boot-starter/src/test/java/.../boot/configure/ClusterMultiNodeE2ETest.java`.
- Modified (test-only): `RedisIntegrationTest`, `ReliableBroadcastIntegrationTest`, `ClusterAuthIntegrationTest` (cluster module); `NettyWebSocketClusterConfigureTest` (starter).
- Modified (poms): `netty-spring-websocket-cluster/pom.xml`, `netty-websocket-cluster-spring-boot-starter/pom.xml` (add testcontainers test dep).
- Modified (CI/docs): `.github/workflows/ci.yml`; `docs/release-notes-1.9.0.md`, `docs/cluster-design.md`, `docs/development-plan.md`, `docs/release-checklist.md`.

---

## Task 0: Branch setup
- [ ] **Step 1:** `git checkout -b feature/1.9.0-multinode-e2e` (from `master` at RC4). Confirm `git branch --show-current`.

---

## Task 1: Testcontainers dep + ClusterTestRedis resolver (+ self-test)

**Files:** both module poms; `ClusterTestRedis.java` ×2 (duplicate); `ClusterTestRedisSelfTest.java`.

- [ ] **Step 1: Add the Testcontainers test dependency to BOTH modules**
In `netty-spring-websocket-cluster/pom.xml`, inside `<dependencies>` after the JUnit test dep, add:
```xml
        <!-- Testcontainers (test): fallback Redis when no localhost Redis is reachable (CI) -->
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
```
In `netty-websocket-cluster-spring-boot-starter/pom.xml`, inside `<dependencies>` after the `spring-boot-starter-test` dep, add the identical block.
(No `<version>` — managed by `spring-boot-dependencies`.)

- [ ] **Step 2: Create the resolver in the cluster module test tree**
Create `netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterTestRedis.java`:
```java
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
```

- [ ] **Step 3: Duplicate the resolver in the starter test tree** (different package)
Create `netty-websocket-cluster-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/boot/configure/ClusterTestRedis.java` — **identical body** to Step 2 EXCEPT the first line is:
```java
package com.github.berrywang1996.netty.spring.boot.configure;
```
(Everything else — imports, class, methods — is byte-for-byte the same.)

- [ ] **Step 4: Write a self-test for the resolver**
Create `netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterTestRedisSelfTest.java`:
```java
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
```

- [ ] **Step 5: Run the self-test** (Redis live on localhost:16379, so it resolves localhost)
`mvn -pl netty-spring-websocket-cluster -am test -Dtest=ClusterTestRedisSelfTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS, 1 test passes (NOT skipped — Redis is up).

- [ ] **Step 6: Commit**
```
git add netty-spring-websocket-cluster/pom.xml netty-websocket-cluster-spring-boot-starter/pom.xml netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterTestRedis.java netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterTestRedisSelfTest.java netty-websocket-cluster-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/boot/configure/ClusterTestRedis.java
git commit -m "test(cluster): ClusterTestRedis resolver (localhost-first, Testcontainers fallback)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Retrofit the existing integration tests to ClusterTestRedis

**Files:** `RedisIntegrationTest.java`, `ReliableBroadcastIntegrationTest.java`, `ClusterAuthIntegrationTest.java` (cluster module), `NettyWebSocketClusterConfigureTest.java` (starter). The retrofit is uniform: stop hardcoding the URI; acquire Redis from `ClusterTestRedis`; **leave every assertion unchanged**. (All 4 are in the same package as their module's `ClusterTestRedis` copy, so no import is needed.)

- [ ] **Step 1: `RedisIntegrationTest`**
Change the field:
```java
    private static final String REDIS_URI = "redis://localhost:16379";
```
to:
```java
    private static String REDIS_URI;
```
Replace the `@BeforeAll checkRedis()` body:
```java
    @BeforeAll
    static void checkRedis() {
        try {
            redisClient = RedisClient.create(REDIS_URI);
            connection = redisClient.connect();
            connection.sync().ping();
            redisAvailable = true;
            // Clean up any leftover keys from previous test runs
            connection.sync().eval("for _,k in ipairs(redis.call('keys','netty:*')) do redis.call('del',k) end",
                    io.lettuce.core.ScriptOutputType.INTEGER);
        } catch (Exception e) {
            System.out.println("Redis not available at " + REDIS_URI + " — skipping integration tests: " + e.getMessage());
            redisAvailable = false;
        }
```
with:
```java
    @BeforeAll
    static void checkRedis() {
        redisAvailable = ClusterTestRedis.available();
        if (!redisAvailable) {
            System.out.println("No Redis and no Docker — skipping integration tests");
            return;
        }
        REDIS_URI = ClusterTestRedis.uri();
        redisClient = RedisClient.create(REDIS_URI);
        connection = redisClient.connect();
        // Clean up any leftover keys from previous test runs
        connection.sync().eval("for _,k in ipairs(redis.call('keys','netty:*')) do redis.call('del',k) end",
                io.lettuce.core.ScriptOutputType.INTEGER);
```
(Keep the closing `}` and the rest of the method/`@AfterAll` as-is. Every `RedisClient.create(REDIS_URI)` inside the 12 test bodies keeps working because `REDIS_URI` is assigned before any test runs.)

- [ ] **Step 2: `ClusterAuthIntegrationTest`**
Change `private static final String REDIS_URI = "redis://localhost:16379";` to `private static String REDIS_URI;`. Replace the `@BeforeAll check()`:
```java
    @BeforeAll
    static void check() {
        try { probe = RedisClient.create(REDIS_URI); StatefulRedisConnection<String,String> c = probe.connect();
            c.sync().ping(); c.close(); redisAvailable = true; } catch (Exception e) { redisAvailable = false; }
    }
```
with:
```java
    @BeforeAll
    static void check() {
        redisAvailable = ClusterTestRedis.available();
        if (redisAvailable) {
            REDIS_URI = ClusterTestRedis.uri();
            probe = RedisClient.create(REDIS_URI);
        }
    }
```
(The test bodies' `RedisClient.create(REDIS_URI)` calls keep working.)

- [ ] **Step 3: `ReliableBroadcastIntegrationTest`**
This test's bodies use the shared `client`, not `REDIS_URI`. Delete the line `private static final String REDIS_URI = "redis://localhost:16379";`. Replace the `@BeforeAll check()`:
```java
    @BeforeAll
    static void check() {
        try {
            client = RedisClient.create(REDIS_URI);
            conn = client.connect();
            conn.sync().ping();
            redisAvailable = true;
            wipe();
        } catch (Exception e) { redisAvailable = false; }
    }
```
with:
```java
    @BeforeAll
    static void check() {
        redisAvailable = ClusterTestRedis.available();
        if (!redisAvailable) {
            return;
        }
        client = ClusterTestRedis.newClient();
        conn = client.connect();
        wipe();
    }
```

- [ ] **Step 4: `NettyWebSocketClusterConfigureTest`** (starter module)
Change `private static final String REDIS_URI = "redis://localhost:16379";` to:
```java
    private static String REDIS_URI = "redis://localhost:16379";
```
Replace the `@BeforeAll checkRedis()`:
```java
    @BeforeAll
    static void checkRedis() {
        try {
            RedisClient c = RedisClient.create(REDIS_URI);
            StatefulRedisConnection<String, String> conn = c.connect();
            conn.sync().ping();
            conn.close();
            c.shutdown();
            redisAvailable = true;
        } catch (Exception e) {
            redisAvailable = false;
        }
    }
```
with:
```java
    @BeforeAll
    static void checkRedis() {
        redisAvailable = ClusterTestRedis.available();
        if (redisAvailable) {
            REDIS_URI = ClusterTestRedis.uri();
        }
    }
```
(The `RedisClient` / `StatefulRedisConnection` imports may now be unused — if so, delete those two import lines to avoid an unused-import warning. The enabled tests already `assumeTrue(redisAvailable)` before using `REDIS_URI`.)

- [ ] **Step 5: Run the retrofitted tests** (Redis live → they execute, not skip)
```
mvn -pl netty-spring-websocket-cluster -am test -Dtest=RedisIntegrationTest,ReliableBroadcastIntegrationTest,ClusterAuthIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl netty-websocket-cluster-spring-boot-starter -am test -Dtest=NettyWebSocketClusterConfigureTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: BUILD SUCCESS for both; every test runs with 0 skipped (same counts as before the retrofit). If any are skipped, Redis/Docker resolution failed — STOP and report.

- [ ] **Step 6: Commit**
```
git add -A
git commit -m "test(cluster): run the 4 cluster integration tests via ClusterTestRedis (CI-ready)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Multi-node E2E test (headline)

**Files:** Create `netty-websocket-cluster-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/boot/configure/ClusterMultiNodeE2ETest.java`.

- [ ] **Step 1: Write the E2E test** (it is the implementation; no separate prod code)
```java
package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.MessageMapping;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.NodeState;
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
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    @Test
    void unicastFromNodeBRoutesToSessionOnNodeA() throws Exception {
        assertTrue(ready);
        List<String> received = new CopyOnWriteArrayList<>();
        WebSocket ws = connectTo(portA, received);
        try {
            String sid = awaitSessionOnNodeA();
            assertNotNull(sid, "node A must have registered the connecting session");
            Thread.sleep(500);

            nodeB.getBean(MessageSender.class).sendTextToSession(URI_PATH, "dm-from-B", sid);

            waitUntil(() -> received.stream().anyMatch(s -> s.contains("dm-from-B")), 8000);
            assertTrue(received.stream().anyMatch(s -> s.contains("dm-from-B")),
                    "client on node A must receive the cross-node unicast from node B; got=" + received);
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
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
                        "server.netty.websocket.cluster.auth.enable=true",
                        "server.netty.websocket.cluster.auth.secret=" + SECRET,
                        "logging.level.root=warn")
                .run();
    }

    /** Connect a JDK WebSocket client to node A's URI_PATH; received text frames are appended to sink. */
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
    static class E2EController {
        private volatile String lastSessionId;

        String lastSessionId() {
            return lastSessionId;
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
```

- [ ] **Step 2: Run the E2E test** (Redis + Docker live)
`mvn -pl netty-websocket-cluster-spring-boot-starter -am test -Dtest=ClusterMultiNodeE2ETest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS, 2 tests pass (NOT skipped). It starts two Netty servers + joins a 2-node cluster over Redis and drives a real WS client — allow up to ~60s.
**If a test fails on timing** (message not received before the deadline): first confirm both nodes reached ACTIVE (the `@BeforeAll` asserts it); then increase the per-assertion `waitUntil` deadlines (8000 → 12000) and the settle `Thread.sleep(500)` (→ 1000). Do NOT weaken an assertion to make it pass. If broadcast works but unicast doesn't, recheck the captured `sid` is node A's session id. Report BLOCKED with the `got=` list if it still fails after the timing bumps.

- [ ] **Step 3: Commit**
```
git add netty-websocket-cluster-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/boot/configure/ClusterMultiNodeE2ETest.java
git commit -m "test(cluster): two-node E2E — cross-node broadcast + unicast over real WS, HMAC on, metric assert

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: CI comment + docs

**Files:** `.github/workflows/ci.yml`; `docs/release-notes-1.9.0.md`, `docs/cluster-design.md`, `docs/development-plan.md`, `docs/release-checklist.md`.

- [ ] **Step 1: CI clarifying comment**
In `.github/workflows/ci.yml`, change the `Run tests` step to document that the cluster ITs self-provision Redis:
```yaml
      - name: Run tests
        # Cluster integration tests self-provision Redis via Testcontainers (uses the runner's
        # Docker). Do NOT add a Redis service here — ClusterTestRedis prefers a reachable
        # localhost:16379 and otherwise starts a redis:7-alpine container automatically.
        run: bash ./mvnw $MAVEN_ARGS test
```

- [ ] **Step 2: Docs** (Edit tool ONLY; preserve CJK)
Accurately reflect what shipped (the Testcontainers-CI + in-process two-node E2E half) and keep the **runnable Docker-Compose demo deferred**:
- `docs/release-notes-1.9.0.md`: add a `### ⑨ 多节点 E2E + Testcontainers CI` section (RC5) describing the resolver (localhost-first, Testcontainers fallback), the 3 ITs + context test now running in CI, and `ClusterMultiNodeE2ETest` (two in-process nodes, cross-node broadcast/unicast, HMAC on, metric assert). In the `已知限制` list, change the existing "多节点 demo + Testcontainers 端到端 CI" entry to keep only the **runnable 多节点 Docker 示例（Compose + 负载均衡 + 浏览器）** as deferred (Testcontainers E2E is now shipped).
- `docs/cluster-design.md`: the scope-table row `| 多节点 demo + Docker Compose + Testcontainers | ⏳ 1.9.x | … |` → split: mark `Testcontainers 端到端 CI + 多节点 E2E` as `✅ 1.9.0 RC5`, and keep `运行时多节点 Docker 示例（Compose）` as `⏳`.
- `docs/development-plan.md`: in the `1.9.x` backlog list and the roadmap table row, mark the Testcontainers/E2E half shipped (RC5) and keep the runnable Docker demo deferred. Update the line-7 / line-14 / line-212 RC framing and the line-10 "下一步" the way RC4 was recorded (RC4 → RC5; add "多节点 E2E + Testcontainers CI").
- `docs/release-checklist.md`: in the `1.9.x+`「仍推迟」line, drop "多节点 demo + Testcontainers" and add a parenthetical "（多节点 E2E + Testcontainers CI → RC5；运行时 Docker 示例仍推迟）".
After editing, verify: `for f in docs/release-notes-1.9.0.md docs/cluster-design.md docs/development-plan.md docs/release-checklist.md; do iconv -f UTF-8 -t UTF-8 "$f" >/dev/null 2>&1 && echo "$f ok" || echo "$f BAD"; done` and `LC_ALL=C grep -l $'\xef\xbf\xbd' docs/*.md || echo "no U+FFFD"`.

- [ ] **Step 3: Commit**
```
git add .github/workflows/ci.yml docs/
git commit -m "docs(cluster): multi-node E2E + Testcontainers CI — release notes, design, roadmap, CI note

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Full test + cut v1.9.0-RC5

**Files:** all 11 poms (`1.9.0-RC4` → `1.9.0-RC5`); `docs/release-notes-1.9.0.md` (count + status).

- [ ] **Step 1: Full reactor test** — `mvn test` (Docker + Redis up) → BUILD SUCCESS, 11 modules. Capture the exact total (was 324; +~6: `ClusterTestRedisSelfTest` 1 + `ClusterMultiNodeE2ETest` 2 + any newly-unskipped count is unchanged since localhost Redis was already up locally). STOP + report if anything fails or if cluster ITs are unexpectedly skipped.
- [ ] **Step 2: Update release-notes** (Edit tool only) — set the `## 测试覆盖` count line to the real Step-1 total; set the status line to `1.9.0-RC5`; add a `（RC5）` bullet for `ClusterTestRedisSelfTest` + `ClusterMultiNodeE2ETest`. Verify UTF-8 + U+FFFD as in Task 4.
- [ ] **Step 3: Bump to RC5** — `for f in $(grep -rl "1.9.0-RC4" --include=pom.xml .); do sed -i 's|<version>1.9.0-RC4</version>|<version>1.9.0-RC5</version>|g' "$f"; done`; verify `grep -rl "1.9.0-RC4" --include=pom.xml . | wc -l` = 0 and `grep -rl "1.9.0-RC5" --include=pom.xml . | wc -l` = 11.
- [ ] **Step 4: Re-test** — `mvn -q test` → BUILD SUCCESS.
- [ ] **Step 5: Commit + tag**
```
git add -A
git commit -m "release: 1.9.0-RC5 — multi-node E2E + Testcontainers CI

Cluster integration tests now run in CI via a Testcontainers-Redis resolver
(localhost-first, container fallback); a new in-process two-node E2E proves
cross-node broadcast + unicast over real WebSockets with HMAC on + a metric
assertion. Test-only, additive. Part of the 1.9.0 cycle; final 1.9.0 when the
cycle completes.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git tag -a v1.9.0-RC5 -m "v1.9.0-RC5 — multi-node E2E + Testcontainers CI (1.9.0 cycle, in development)"
```
- [ ] **Step 6: Report** — RC5 cut locally (not pushed/deployed). The 1.9.0 cycle continues until the user says it's complete.

---

## Notes for the implementer
- **`mvn test` must stay green** — that's why the resolver is duplicated (no test-jar). Do not introduce a `test-jar` dependency.
- **No `src/main` change** — everything is test sources + 2 test-dep lines + a CI comment + docs.
- **The E2E is the one heavyweight test** — two full apps + a 2-node cluster. Keep it reliable: ACTIVE-state gate in `@BeforeAll`, generous `waitUntil` deadlines, contexts closed in `@AfterAll`. If flaky, bump timeouts — never weaken assertions.
- **Metric binding:** `netty.cluster.broadcast.published` exists on node B's `SimpleMeterRegistry` because `spring-boot-starter-actuator` (on the test classpath) makes Spring Boot bind `MeterBinder` beans to the registry, and `NettyClusterMetricsConfigure` registers `NettyClusterMeterBinder` (cluster enabled + a `MeterRegistry` bean present). If the meter is somehow absent, register the binder explicitly in `E2ETestApp` — but verify the auto-binding first.
- **Docs honesty:** the runnable multi-node **Docker demo** (Compose + LB + browser) remains deferred — only the Testcontainers CI + in-process E2E ship in RC5.
```
