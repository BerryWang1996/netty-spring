# Redis Cluster Client Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Ship first-class `RedisClusterMode*` impls of `ClusterBroker` / `SessionRegistry` / `ClusterNodeHeartbeat` (slot-safe, Lettuce `RedisClusterClient`), selected when `cluster-nodes` is configured — so the WebSocket cluster runs against a Redis Cluster (HA + slot-distributed session data, regular cluster pub/sub).

**Architecture:** Three additive `@ConditionalOnMissingBean` impls in `…cluster.redis`, parallel to the standalone `RedisPubSubBroker`/`RedisSessionRegistry`/`RedisClusterNodeHeartbeat`, built on `StatefulRedisClusterConnection`/`StatefulRedisClusterPubSubConnection`. Slot-safe deltas vs standalone: **non-atomic deregister** (no cross-slot Lua), **cluster-aware SCAN**. Auto-config picks the transport by `cluster-nodes`. Verified by a **single-node Redis Cluster** integration test (sidesteps multi-node gossip; exercises the real cluster-client path).

**Tech Stack:** Java 17, Lettuce **6.1.10** (Boot 2.7.18 managed — NO sharded pub/sub, that's 6.2+/2.0.0), Testcontainers, JUnit 5 + Mockito. Spec: `docs/superpowers/specs/2026-06-04-redis-cluster-client-design.md`. Develops on `1.9.0-RC6`.

---

## Environment notes for every task
- Repo: `C:\Users\qq951\IdeaProjects\netty-spring`; Maven 3.9.9; Java 17. Docker live. Redis live on `localhost:16379` (**standalone, NOT a cluster** — the cluster IT brings its own single-node cluster).
- Branch `feature/1.9.0-redis-cluster` (Task 0). Do NOT push/deploy.
- `-pl … -am -Dtest=…` always with `-Dsurefire.failIfNoSpecifiedTests=false`.
- Docs: Edit tool only; verify UTF-8 + U+FFFD after.

## ⚠️ Lettuce-cluster-API verification mandate (read first)
The cluster code below is written from Lettuce 6.1 API knowledge and **must be verified empirically** — the single-node-cluster IT (Task 1) is the safety net. Each impl task ends by running its IT against the real single-node cluster; **if a Lettuce API call doesn't compile or the IT fails, fix the API against the actual Lettuce 6.1.10 classes** (read the jar's sources/javadoc; `RedisClusterClient`, `StatefulRedisClusterConnection`, `StatefulRedisClusterPubSubConnection`, `RedisClusterPubSubAdapter`, `RedisAdvancedClusterCommands`/`…AsyncCommands`, `ClusterClientOptions`). Do NOT mark a task DONE on a SKIPPED IT — investigate why the cluster didn't come up.

## Confirmed facts (verified)
- Standalone siblings to mirror: `RedisPubSubBroker` (publish via `publishConnection.async().publish(channel,data)`; receive via `subscribeConnection.addListener(RedisPubSubAdapter)` + `subscribeConnection.async().subscribe(channel)`; transport health via `redisClient.addListener(RedisConnectionStateListener)`; `EnvelopeCodec`+`MessageAuthenticator`+inbound-cap+`TransportStateListener` contract), `RedisSessionRegistry` (key schemes `netty:session:{uri}:{sessionId}` Hash + `netty:node:{nodeId}:sessions` Set; the `DEREGISTER_LUA` is **cross-slot-unsafe** — do NOT use on cluster), `RedisClusterNodeHeartbeat` (**already all single-key ops** incl. per-key `async.exists(...)` — slot-safe as-is).
- **Naming:** the existing standalone heartbeat is `RedisClusterNodeHeartbeat` (a netty-cluster concept). New Redis-Cluster-topology impls use the `RedisClusterMode` prefix to avoid collision: `RedisClusterModePubSubBroker`, `RedisClusterModeSessionRegistry`, `RedisClusterModeNodeHeartbeat`.
- `NettyWebSocketClusterConfigure` beans to gate (add the complementary condition): `nettyClusterRedisClient` (`@ConditionalOnMissingBean(RedisClient.class)`), `nettyClusterRedisConnection`, `clusterBroker` (`RedisPubSubBroker`, `@ConditionalOnMissingBean(ClusterBroker.class)`), `sessionRegistry` (`@ConditionalOnMissingBean(SessionRegistry.class)`), `clusterNodeHeartbeat` (`@ConditionalOnMissingBean(ClusterNodeHeartbeat.class)`). `ClusterProperties.Redis` currently has only `uri`.
- The `testcontainers` test dep is already on the cluster module (added in RC5).

## File Structure
- New (main): `…cluster/redis/RedisClusterModeSessionRegistry.java`, `RedisClusterModeNodeHeartbeat.java`, `RedisClusterModePubSubBroker.java`.
- New (test): `…cluster/ClusterTestRedisCluster.java` (resolver) + `ClusterTestRedisClusterSelfTest.java`; `…cluster/redis/RedisClusterModeSessionRegistryTest.java` / `…NodeHeartbeatTest.java` / `…PubSubBrokerTest.java` (Mockito unit) + `RedisClusterIntegrationTest.java` (single-node cluster).
- Modified: `ClusterProperties.java` (`Redis.clusterNodes`); `NettyWebSocketClusterConfigure.java` (cluster beans + complementary conditions); `NettyWebSocketClusterConfigureTest.java` (selection context test); docs + poms.

---

## Task 0: Branch
- [ ] `git checkout -b feature/1.9.0-redis-cluster` (from `master` at RC6); confirm `git branch --show-current`.

---

## Task 1: `ClusterTestRedisCluster` single-node-cluster resolver (+ self-test) — THE LINCHPIN

**Files:** create `ClusterTestRedisCluster.java`, `ClusterTestRedisClusterSelfTest.java` (cluster module test).

- [ ] **Step 1: Create the resolver**
Create `netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterTestRedisCluster.java`:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Resolves a usable SINGLE-NODE Redis Cluster for cluster-client integration tests, once per JVM:
 *   1. env CLUSTER_TEST_REDIS_CLUSTER_NODES (host:port) if a cluster is reachable there,
 *   2. a Testcontainers redis:7 started with --cluster-enabled yes, with all 16384 slots assigned
 *      to the single node (so cluster state = ok and a client connects directly — no gossip/announce-ip),
 *   3. none → available() == false (tests skip).
 *
 * A single-node cluster avoids the multi-node announce-ip problem while exercising the real
 * RedisClusterClient code path (cluster connection, cluster pub/sub, slot-routed commands).
 */
public final class ClusterTestRedisCluster {

    private static volatile boolean resolved;
    private static volatile String nodes; // "host:port"
    @SuppressWarnings("resource")
    private static GenericContainer<?> container;

    private ClusterTestRedisCluster() {
    }

    public static synchronized boolean available() {
        resolve();
        return nodes != null;
    }

    /** "host:port" seed for the single-node cluster. */
    public static synchronized String nodes() {
        resolve();
        if (nodes == null) {
            throw new IllegalStateException("No single-node Redis Cluster available");
        }
        return nodes;
    }

    public static RedisClusterClient newClient() {
        String[] hp = nodes().split(":");
        return RedisClusterClient.create(Collections.singletonList(
                RedisURI.create(hp[0], Integer.parseInt(hp[1]))));
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
        // Single-node cluster: cluster-enabled, fixed port so the gossip-advertised port matches the
        // mapped port (we publish 6379 and map it; single node has no gossip partners anyway).
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse("redis:7"))
                .withExposedPorts(6379)
                .withCommand("redis-server", "--cluster-enabled", "yes", "--cluster-node-timeout", "2000",
                        "--appendonly", "no", "--save", "")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));
        c.start();
        try {
            // Assign all slots to this single node, then wait until CLUSTER INFO reports state:ok.
            c.execInContainer("redis-cli", "cluster", "addslotsrange", "0", "16383");
            long deadline = java.lang.System.currentTimeMillis() + 15000;
            boolean ok = false;
            while (java.lang.System.currentTimeMillis() < deadline) {
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
            try { c.stop(); } catch (Exception ignored) { }
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
```
**VERIFY (Lettuce API):** confirm `RedisClusterClient.create(List<RedisURI>)`, `StatefulRedisClusterConnection.sync().ping()`, and the mapped-port connection all work against the real container. If `connect()` against the mapped port fails because the cluster advertises an internal port, set `client.setOptions(io.lettuce.core.cluster.ClusterClientOptions.builder().build())` and/or connect with `RedisURI` that disables topology refresh — adjust until the self-test (Step 3) is green.

- [ ] **Step 2: Self-test (proves the harness)**
Create `ClusterTestRedisClusterSelfTest.java` (same package):
```java
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
```

- [ ] **Step 3: Run** — `mvn -pl netty-spring-websocket-cluster -am test -Dtest=ClusterTestRedisClusterSelfTest -Dsurefire.failIfNoSpecifiedTests=false` → 1 PASS, **NOT skipped** (Docker live). If skipped/failed, fix the resolver/container setup until the single-node cluster comes up + the client connects. This task gates all the ITs below.

- [ ] **Step 4: Commit**
```
git add netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterTestRedisCluster.java netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterTestRedisClusterSelfTest.java
git commit -m "test(cluster): single-node Redis Cluster resolver (verifies the cluster-client path)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: `RedisClusterModeSessionRegistry` (+ unit + IT)

**Files:** create the impl + `RedisClusterModeSessionRegistryTest.java` (Mockito) + add to `RedisClusterIntegrationTest.java`.

- [ ] **Step 1: Create the impl**
Create `…cluster/redis/RedisClusterModeSessionRegistry.java` (Apache header from a sibling). It mirrors `RedisSessionRegistry` but takes a cluster connection and uses **non-atomic deregister** + **cluster SCAN**:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.redis;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Redis-Cluster (topology) implementation of {@link SessionRegistry}. Same key design as
 * {@code RedisSessionRegistry}, but: deregister is NON-ATOMIC (HGET -> DEL + SREM as separately
 * slot-routed commands, since the session key and the node-set key live in different slots and an
 * EVAL touching both would be CROSSSLOT — the race it would close is theoretical under UUID
 * sessionIds), and {@code clusterSessionIds} uses the cluster-aware SCAN (all masters).
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class RedisClusterModeSessionRegistry implements SessionRegistry {

    private static final String SESSION_PREFIX = "netty:session:";
    private static final String NODE_PREFIX = "netty:node:";
    private static final String NODE_ID_FIELD = "nodeId";

    private final StatefulRedisClusterConnection<String, String> connection;

    public RedisClusterModeSessionRegistry(StatefulRedisClusterConnection<String, String> connection) {
        this.connection = connection;
    }

    @Override
    public CompletionStage<Void> register(String uri, String sessionId, String nodeId, Map<String, String> metadata) {
        RedisAdvancedClusterAsyncCommands<String, String> async = connection.async();
        Map<String, String> hash = new HashMap<>(metadata);
        hash.put(NODE_ID_FIELD, nodeId);
        CompletableFuture<String> hset = async.hmset(sessionKey(uri, sessionId), hash).toCompletableFuture();
        CompletableFuture<Long> sadd = async.sadd(nodeSetKey(nodeId), uri + "|" + sessionId).toCompletableFuture();
        return CompletableFuture.allOf(hset, sadd).thenRun(() ->
                log.debug("Registered session {} on node {} for URI {}", sessionId, nodeId, uri));
    }

    @Override
    public CompletionStage<Void> deregister(String uri, String sessionId) {
        RedisAdvancedClusterAsyncCommands<String, String> async = connection.async();
        String sessionKey = sessionKey(uri, sessionId);
        String member = uri + "|" + sessionId;
        // Non-atomic on cluster: HGET owner, then DEL + SREM (each routed to its own slot).
        return async.hget(sessionKey, NODE_ID_FIELD).thenCompose(nodeId -> {
            if (nodeId == null) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Long> del = async.del(sessionKey).toCompletableFuture();
            CompletableFuture<Long> srem = async.srem(nodeSetKey(nodeId), member).toCompletableFuture();
            return CompletableFuture.allOf(del, srem);
        }).thenRun(() -> log.debug("Deregistered session {} for URI {}", sessionId, uri)).toCompletableFuture();
    }

    @Override
    public CompletionStage<String> lookupNode(String uri, String sessionId) {
        return connection.async().hget(sessionKey(uri, sessionId), NODE_ID_FIELD).toCompletableFuture();
    }

    @Override
    public CompletionStage<Set<String>> clusterSessionIds(String uri) {
        return CompletableFuture.supplyAsync(() -> {
            RedisAdvancedClusterCommands<String, String> sync = connection.sync();
            String prefix = SESSION_PREFIX + uri + ":";
            Set<String> ids = new HashSet<>();
            ScanArgs args = ScanArgs.Builder.matches(prefix + "*").limit(100);
            ScanCursor cursor = ScanCursor.INITIAL;
            do {
                // RedisAdvancedClusterCommands.scan() fans out across all masters and merges.
                var res = sync.scan(cursor, args);
                for (String key : res.getKeys()) {
                    if (key.startsWith(prefix)) {
                        ids.add(key.substring(prefix.length()));
                    }
                }
                cursor = res;
            } while (!cursor.isFinished());
            return ids;
        });
    }

    @Override
    public CompletionStage<Void> removeAllForNode(String nodeId) {
        RedisAdvancedClusterAsyncCommands<String, String> async = connection.async();
        String nodeSetKey = nodeSetKey(nodeId);
        return async.smembers(nodeSetKey).thenCompose(members -> {
            if (members == null || members.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            List<CompletableFuture<?>> futures = new ArrayList<>();
            for (String member : members) {
                int sep = member.indexOf('|');
                if (sep > 0) {
                    futures.add(async.del(sessionKey(member.substring(0, sep), member.substring(sep + 1)))
                            .toCompletableFuture());
                }
            }
            futures.add(async.del(nodeSetKey).toCompletableFuture());
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        }).thenRun(() -> log.info("Removed all sessions for dead node {}", nodeId)).toCompletableFuture();
    }

    @Override
    public void shutdown() {
        log.info("RedisClusterModeSessionRegistry shut down");
    }

    private static String sessionKey(String uri, String sessionId) {
        return SESSION_PREFIX + uri + ":" + sessionId;
    }

    private static String nodeSetKey(String nodeId) {
        return NODE_PREFIX + nodeId + ":sessions";
    }
}
```
**VERIFY:** `RedisAdvancedClusterCommands.scan(ScanCursor, ScanArgs)` exists in 6.1 and fans out across masters; if the signature differs, adjust. `hmset`/`sadd`/`hget`/`del`/`srem`/`smembers` are on the cluster async commands (they extend the base command interfaces).

- [ ] **Step 2: Unit test (Mockito over the cluster commands)**
Create `RedisClusterModeSessionRegistryTest.java`:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.redis;

import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisClusterModeSessionRegistryTest {

    @Test
    @SuppressWarnings("unchecked")
    void deregisterIsNonAtomic_hgetThenDelAndSrem_noEval() {
        StatefulRedisClusterConnection<String, String> conn = mock(StatefulRedisClusterConnection.class);
        RedisAdvancedClusterAsyncCommands<String, String> async = mock(RedisAdvancedClusterAsyncCommands.class);
        when(conn.async()).thenReturn(async);
        when(async.hget("netty:session:/ws/x:s1", "nodeId"))
                .thenReturn(redisFuture("node-A"));
        when(async.del(any(String[].class))).thenReturn(redisFuture(1L));
        when(async.srem(eq("netty:node:node-A:sessions"), eq("/ws/x|s1"))).thenReturn(redisFuture(1L));

        new RedisClusterModeSessionRegistry(conn).deregister("/ws/x", "s1").toCompletableFuture().join();

        verify(async).hget("netty:session:/ws/x:s1", "nodeId");
        verify(async).del("netty:session:/ws/x:s1");
        verify(async).srem("netty:node:node-A:sessions", "/ws/x|s1");
        verify(async, never()).eval(anyString(), any(), any(String[].class), any(String[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void registerIssuesHmsetAndSadd() {
        StatefulRedisClusterConnection<String, String> conn = mock(StatefulRedisClusterConnection.class);
        RedisAdvancedClusterAsyncCommands<String, String> async = mock(RedisAdvancedClusterAsyncCommands.class);
        when(conn.async()).thenReturn(async);
        when(async.hmset(anyString(), anyMap())).thenReturn(redisFuture("OK"));
        when(async.sadd(anyString(), any(String[].class))).thenReturn(redisFuture(1L));

        new RedisClusterModeSessionRegistry(conn)
                .register("/ws/x", "s1", "node-A", Collections.emptyMap()).toCompletableFuture().join();

        verify(async).hmset(eq("netty:session:/ws/x:s1"), anyMap());
        verify(async).sadd("netty:node:node-A:sessions", "/ws/x|s1");
    }

    // Lettuce RedisFuture is a CompletionStage + Future; a CompletableFuture-backed stub is enough here.
    @SuppressWarnings("unchecked")
    private static <T> io.lettuce.core.RedisFuture<T> redisFuture(T value) {
        io.lettuce.core.RedisFuture<T> f = mock(io.lettuce.core.RedisFuture.class);
        CompletableFuture<T> cf = CompletableFuture.completedFuture(value);
        when(f.toCompletableFuture()).thenReturn(cf);
        when(f.thenCompose(any())).thenAnswer(inv -> cf.thenCompose(inv.getArgument(0)));
        when(f.thenAccept(any())).thenAnswer(inv -> cf.thenAccept(inv.getArgument(0)));
        return f;
    }
}
```
**VERIFY:** if mocking `RedisFuture`'s `CompletionStage` methods is awkward, simplify by having the stub `toCompletableFuture()` return a completed future and only assert the command calls (the impl chains on `toCompletableFuture()`/`thenCompose` — adjust the stub so `.join()` completes). The goal is to assert **HGET→DEL+SREM, no eval** + **HMSET+SADD**.

- [ ] **Step 3: Integration test (single-node cluster)** — create `RedisClusterIntegrationTest.java` with a registry round-trip:
```java
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
```

- [ ] **Step 4: Run** — `mvn -pl netty-spring-websocket-cluster -am test -Dtest=RedisClusterModeSessionRegistryTest,RedisClusterIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` → unit + IT pass, IT NOT skipped.

- [ ] **Step 5: Commit**
```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/redis/RedisClusterModeSessionRegistry.java netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/redis/RedisClusterModeSessionRegistryTest.java netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/RedisClusterIntegrationTest.java
git commit -m "feat(cluster): RedisClusterModeSessionRegistry (slot-safe, non-atomic deregister)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: `RedisClusterModeNodeHeartbeat` (+ unit + IT)

**Files:** create the impl + unit test; extend `RedisClusterIntegrationTest`.

- [ ] **Step 1: Create the impl** — a near-copy of the standalone `RedisClusterNodeHeartbeat` (all ops are single-key/slot-safe) with the cluster connection type:
Create `…cluster/redis/RedisClusterModeNodeHeartbeat.java` (Apache header), then:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.redis;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis-Cluster (topology) implementation of {@link ClusterNodeHeartbeat}. Identical key design and
 * logic to {@code RedisClusterNodeHeartbeat} — every operation is single-key (slot-routed) including
 * the per-key {@code EXISTS} reconciliation, so it is slot-safe as-is; only the connection type differs.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class RedisClusterModeNodeHeartbeat implements ClusterNodeHeartbeat {

    private static final String HEARTBEAT_PREFIX = "netty:cluster:heartbeat:";
    private static final String NODES_KEY = "netty:cluster:nodes";

    private final StatefulRedisClusterConnection<String, String> connection;

    public RedisClusterModeNodeHeartbeat(StatefulRedisClusterConnection<String, String> connection) {
        this.connection = connection;
    }

    @Override
    public void register(String nodeId, long timeoutMs) {
        RedisAdvancedClusterCommands<String, String> sync = connection.sync();
        String now = String.valueOf(System.currentTimeMillis());
        sync.set(HEARTBEAT_PREFIX + nodeId, now, SetArgs.Builder.px(timeoutMs));
        sync.hset(NODES_KEY, nodeId, now);
        log.debug("Node {} registered (cluster mode) with heartbeat TTL {}ms", nodeId, timeoutMs);
    }

    @Override
    public void renewHeartbeat(String nodeId, long timeoutMs) {
        RedisAdvancedClusterCommands<String, String> sync = connection.sync();
        String now = String.valueOf(System.currentTimeMillis());
        sync.set(HEARTBEAT_PREFIX + nodeId, now, SetArgs.Builder.px(timeoutMs));
        sync.hset(NODES_KEY, nodeId, now);
    }

    @Override
    public void deregister(String nodeId) {
        RedisAdvancedClusterCommands<String, String> sync = connection.sync();
        sync.del(HEARTBEAT_PREFIX + nodeId);
        sync.hdel(NODES_KEY, nodeId);
        log.debug("Node {} deregistered (cluster mode)", nodeId);
    }

    @Override
    public List<String> findExpiredNodes(long timeoutMs) {
        RedisAdvancedClusterCommands<String, String> sync = connection.sync();
        Map<String, String> nodes = sync.hgetall(NODES_KEY);
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, String> e : nodes.entrySet()) {
            try {
                if (now - Long.parseLong(e.getValue()) > timeoutMs) {
                    candidates.add(e.getKey());
                }
            } catch (NumberFormatException ex) {
                log.warn("Invalid heartbeat timestamp for node {}: {}", e.getKey(), e.getValue());
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        RedisAdvancedClusterAsyncCommands<String, String> async = connection.async();
        Map<String, RedisFuture<Long>> existsFutures = new LinkedHashMap<>();
        for (String nodeId : candidates) {
            existsFutures.put(nodeId, async.exists(HEARTBEAT_PREFIX + nodeId)); // per-key EXISTS — slot-safe
        }
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, RedisFuture<Long>> e : existsFutures.entrySet()) {
            try {
                Long exists = e.getValue().get(timeoutMs, TimeUnit.MILLISECONDS);
                if (exists != null && exists == 0L) {
                    expired.add(e.getKey());
                }
            } catch (Exception ex) {
                log.debug("EXISTS check failed for candidate node {} (cluster mode)", e.getKey(), ex);
            }
        }
        return expired;
    }
}
```

- [ ] **Step 2: Unit test** — `RedisClusterModeNodeHeartbeatTest.java`: mock `StatefulRedisClusterConnection` + `RedisAdvancedClusterCommands`, verify `register` issues `set(...PX...)` + `hset`; and (mock `.async()` → `RedisAdvancedClusterAsyncCommands`, `hgetall` returning a stale node) verify `findExpiredNodes` calls **per-key** `async.exists(heartbeatKey)` (not a multi-key exists). (Mirror the registry unit-test's RedisFuture stub.)
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.redis;

import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisClusterModeNodeHeartbeatTest {

    @Test
    @SuppressWarnings("unchecked")
    void registerSetsTtlKeyAndNodesHash() {
        StatefulRedisClusterConnection<String, String> conn = mock(StatefulRedisClusterConnection.class);
        RedisAdvancedClusterCommands<String, String> sync = mock(RedisAdvancedClusterCommands.class);
        when(conn.sync()).thenReturn(sync);

        new RedisClusterModeNodeHeartbeat(conn).register("node-A", 10000);

        verify(sync).set(eq("netty:cluster:heartbeat:node-A"), anyString(), any(SetArgs.class));
        verify(sync).hset(eq("netty:cluster:nodes"), eq("node-A"), anyString());
    }
}
```

- [ ] **Step 3: Add a heartbeat IT method to `RedisClusterIntegrationTest`:**
```java
    @Test
    void heartbeat_registerFindRenew() {
        com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModeNodeHeartbeat hb =
                new com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModeNodeHeartbeat(conn);
        hb.register("hb-node", 200);
        try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertTrue(hb.findExpiredNodes(100).contains("hb-node"), "stale node detected via per-key EXISTS");
        hb.deregister("hb-node");
    }
```

- [ ] **Step 4: Run** — `mvn -pl netty-spring-websocket-cluster -am test -Dtest=RedisClusterModeNodeHeartbeatTest,RedisClusterIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` → pass, IT not skipped.

- [ ] **Step 5: Commit** (`git add` the impl + unit test + the modified IT; message `feat(cluster): RedisClusterModeNodeHeartbeat (slot-safe per-key EXISTS)`).

---

## Task 4: `RedisClusterModePubSubBroker` (+ unit + IT) — the riskiest; the IT proves the pub/sub API

**Files:** create the impl + unit test; extend `RedisClusterIntegrationTest`.

- [ ] **Step 1: Create the impl** — mirror `RedisPubSubBroker` exactly, swapping `RedisClient`→`RedisClusterClient`, `StatefulRedisConnection`→`StatefulRedisClusterConnection`, `StatefulRedisPubSubConnection`→`StatefulRedisClusterPubSubConnection`, `RedisPubSubAdapter`→`RedisClusterPubSubAdapter`. Keep the full contract (codec/auth/inbound-cap/`TransportStateListener`, `publish`/`unicast`/`subscribe`/`subscribeUnicast`/`state`/`shutdown`). Create `…cluster/redis/RedisClusterModePubSubBroker.java` (Apache header). Use the standalone broker (read `RedisPubSubBroker.java` in full) as the line-by-line template; the cluster equivalents:
  - `RedisClusterClient` constructor arg; `client.connect()` → `StatefulRedisClusterConnection` (publish), `client.connectPubSub()` → `StatefulRedisClusterPubSubConnection` (subscribe).
  - Listener: `subscribeConnection.addListener(new RedisClusterPubSubAdapter<String,String>(){ @Override public void message(RedisClusterNode node, String channel, String message){ /* same body as standalone message(channel,message) */ } })` — **note the cluster adapter's `message` overload includes the node**; also implement/override the non-node `message(String,String)` if present. **VERIFY which `message(...)` overload fires** on the single-node cluster IT.
  - Transport health: `redisClusterClient.addListener(new RedisConnectionStateListener(){...})` — same as standalone (verify `RedisClusterClient.addListener` exists; if not, attach via `ClusterClientOptions`/the connection).
  - **Cluster pub/sub receive:** regular cluster `PUBLISH` propagates cluster-wide; on the single-node cluster a `subscribe(channel)` on the pub/sub connection then `publish(channel, data)` MUST deliver to the listener. **If the listener does not fire, set `subscribeConnection.setNodeMessagePropagation(true)` before subscribing** (this makes the cluster pub/sub connection surface messages from all upstream nodes). The IT (Step 3) is the oracle.
- [ ] **Step 2: Unit test** `RedisClusterModePubSubBrokerTest.java`: this needs a constructed broker, which connects in its constructor — so unit-test only the parts not requiring a live connection, OR keep the unit test minimal (e.g. verify `state()` is `ACTIVE` after construction against a mocked `RedisClusterClient` whose `connect()`/`connectPubSub()` return mocked connections). Mock `RedisClusterClient.connect()` → mock `StatefulRedisClusterConnection`, `connectPubSub()` → mock `StatefulRedisClusterPubSubConnection` (stub `.addListener(...)`), then assert `publish(...)` calls `mockConn.async().publish(channel, wrappedData)`. (Mirror the existing standalone broker's testability; if the standalone `RedisPubSubBrokerTest` exists, follow its mocking pattern.)
- [ ] **Step 3: Add the headline pub/sub IT to `RedisClusterIntegrationTest`** — the real proof the cluster pub/sub receive path works:
```java
    @Test
    void broker_publishReachesSubscriberOnSingleNodeCluster() throws Exception {
        com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModePubSubBroker a =
                new com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModePubSubBroker(
                        ClusterTestRedisCluster.newClient(),
                        new com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec());
        com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModePubSubBroker b =
                new com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterModePubSubBroker(
                        ClusterTestRedisCluster.newClient(),
                        new com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec());
        java.util.List<com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope> got =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        b.subscribe("/ws/bc", got::add);
        Thread.sleep(400);
        a.publish("/ws/bc", new com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope(
                "node-A", "/ws/bc",
                com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope.MessageKind.BROADCAST,
                "T:hi".getBytes(), null, null, System.currentTimeMillis()));
        long deadline = System.currentTimeMillis() + 5000;
        while (got.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(1, got.size(), "subscriber on the cluster must receive the publish");
        a.shutdown();
        b.shutdown();
    }
```
(Provide the 2-arg `RedisClusterModePubSubBroker(RedisClusterClient, EnvelopeCodec)` constructor, mirroring the standalone broker's 2-arg + 3-arg-with-authenticator pair.)
- [ ] **Step 4: Run** — `mvn -pl netty-spring-websocket-cluster -am test -Dtest=RedisClusterModePubSubBrokerTest,RedisClusterIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` → pass, IT not skipped. **If `broker_publishReachesSubscriberOnSingleNodeCluster` fails (no receipt), this is the Lettuce cluster-pubsub API issue — fix per Step 1's `setNodeMessagePropagation`/adapter-overload guidance until it receives. Do NOT weaken the assertion.**
- [ ] **Step 5: Commit** (`feat(cluster): RedisClusterModePubSubBroker (regular cluster pub/sub)`).

---

## Task 5: Config property + auto-config transport selection + context test

**Files:** `ClusterProperties.java`, `NettyWebSocketClusterConfigure.java`, `NettyWebSocketClusterConfigureTest.java`.

- [ ] **Step 1: `ClusterProperties.Redis.clusterNodes`** — in the `Redis` nested class add `private String clusterNodes;` (default null/empty) + `getClusterNodes()/setClusterNodes(String)`. Document: comma-separated `host:port,host:port`; when set, the cluster transport is used.
- [ ] **Step 2: Auto-config** in `NettyWebSocketClusterConfigure`:
  - Add a constant for the property key. Gate the **standalone** beans (`nettyClusterRedisClient`, `clusterBroker`, `sessionRegistry`, `clusterNodeHeartbeat`) with `@ConditionalOnProperty(prefix = "server.netty.websocket.cluster.redis", name = "cluster-nodes", matchIfMissing = true)` — i.e. active only when `cluster-nodes` is ABSENT. (Keep their existing `@ConditionalOnMissingBean`.)
  - Add **cluster** beans, each `@ConditionalOnProperty(prefix = "server.netty.websocket.cluster.redis", name = "cluster-nodes")` (active only when set) + `@ConditionalOnMissingBean(<the SPI / RedisClusterClient>)`:
    - `RedisClusterClient nettyClusterRedisClusterClient(ClusterProperties p)` — parse `p.getRedis().getClusterNodes()` (split on `,`) into `List<RedisURI>` via `RedisURI.create(host, port)`; `RedisClusterClient.create(list)`; set a bounded command timeout via `ClusterClientOptions.builder().timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(p.getCommandTimeoutMs()))).build()`; reuse `redactRedisUri`/`warnIfInsecureRedis` on each node for logging. `destroyMethod = "shutdown"`.
    - `StatefulRedisClusterConnection<String,String> nettyClusterRedisClusterConnection(RedisClusterClient c)` (`destroyMethod="close"`).
    - `ClusterBroker clusterBrokerCluster(RedisClusterClient client, EnvelopeCodec codec, ClusterProperties p, MessageAuthenticator auth)` → `new RedisClusterModePubSubBroker(client, codec, auth)` + `setInboundMaxBytes(...)` (mirror the standalone `clusterBroker` bean).
    - `SessionRegistry sessionRegistryCluster(@Qualifier("nettyClusterRedisClusterConnection") StatefulRedisClusterConnection<String,String> conn)` → `new RedisClusterModeSessionRegistry(conn)`.
    - `ClusterNodeHeartbeat clusterNodeHeartbeatCluster(@Qualifier("nettyClusterRedisClusterConnection") StatefulRedisClusterConnection<String,String> conn)` → `new RedisClusterModeNodeHeartbeat(conn)`.
  - (The `nettyClusterRedisConnection`/`ClusterReaper`/`RedisStreamsReliableBroker` beans that need a standalone connection keep their `matchIfMissing=true` gate too; reliable-stream + reaper on cluster are out of scope — document that enabling `cluster-nodes` with `reliable.enable=true` is unsupported in RC7, or also gate the reaper. Keep it simple: gate all standalone-connection beans with the same `matchIfMissing=true` condition so they don't activate in cluster mode, and note reliable/reaper-on-cluster as a follow-up.)
- [ ] **Step 3: Context test** in `NettyWebSocketClusterConfigureTest` — two tests (Redis standalone available for the non-cluster one; the cluster one can assert bean wiring without a live cluster IF `RedisClusterClient.create` is lazy — if any bean eagerly connects, gate with `Assumptions.assumeTrue(ClusterTestRedisCluster.available())` and point `cluster-nodes` at `ClusterTestRedisCluster.nodes()`):
  - `clusterNodesSet_usesRedisClusterTransport`: with `cluster-nodes=<single-node cluster>` (+ enable + node-id), assert `context.hasSingleBean(RedisClusterClient.class)` and the `ClusterBroker`/`SessionRegistry` beans are the `RedisClusterMode*` types, and `doesNotHaveBean(RedisClient.class)`.
  - `noClusterNodes_usesStandalone` (existing enabled test already covers standalone — optionally assert `doesNotHaveBean(RedisClusterClient.class)`).
- [ ] **Step 4: Run** `mvn -pl netty-websocket-cluster-spring-boot-starter -am test -Dtest=NettyWebSocketClusterConfigureTest -Dsurefire.failIfNoSpecifiedTests=false` → pass; the new test runs (gate on a cluster if it eagerly connects).
- [ ] **Step 5: Commit** (`feat(cluster): cluster-nodes config selects the Redis Cluster transport`).

---

## Task 6: Documentation
- [ ] **Step 1** (Edit tool only; verify UTF-8 + U+FFFD after): release-notes-1.9.0 RC7 section — Redis Cluster **client** foundation (`RedisClusterMode*` impls, `cluster-nodes` config, slot-safe: non-atomic deregister + cluster SCAN + per-key EXISTS + regular cluster pub/sub); **explicit caveat: regular cluster pub/sub still fans out to all nodes — sharded pub/sub (broadcast fan-out reduction) needs Lettuce 6.2+ and is deferred to 2.0.0; the cluster IT is single-node (verifies the client path, not multi-node distribution)**. api-guide: `cluster-nodes` config row. cluster-design + development-plan: move "Redis Cluster 客户端一等支持" to ✅ RC7 (client level); sharded pub/sub stays ⏳ → 2.0.0. release-checklist: note RC7.
- [ ] **Step 2: Commit** (`docs(cluster): Redis Cluster client foundation (RC7)`).

---

## Task 7: Full test + cut v1.9.0-RC7
- [ ] **Step 1** `mvn test` (Docker + Redis up) → BUILD SUCCESS, 11 modules. Capture the total (was 336; +~10: self-test 1 + 3 unit + the IT's 3 methods + 2 context). STOP if any fail or the cluster ITs unexpectedly skip (the single-node cluster must come up).
- [ ] **Step 2** release-notes count + status → `1.9.0-RC7` (+ RC7 clause); add the RC7 test bullet. Verify UTF-8.
- [ ] **Step 3** `for f in $(grep -rl "1.9.0-RC6" --include=pom.xml .); do sed -i 's|<version>1.9.0-RC6</version>|<version>1.9.0-RC7</version>|g' "$f"; done`; verify 0 RC6 / 11 RC7.
- [ ] **Step 4** `mvn -q test` → BUILD SUCCESS.
- [ ] **Step 5** commit + tag:
```
git add -A
git commit -m "release: 1.9.0-RC7 — Redis Cluster client foundation

RedisClusterMode* broker/registry/heartbeat (slot-safe; non-atomic deregister, per-key
EXISTS, cluster SCAN, regular cluster pub/sub), selected by cluster-nodes; verified on a
single-node Redis Cluster. Additive/opt-in. Sharded pub/sub (fan-out reduction) -> 2.0.0
(needs Lettuce 6.2+). Part of the 1.9.0 cycle.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git tag -a v1.9.0-RC7 -m "v1.9.0-RC7 — Redis Cluster client foundation (1.9.0 cycle, in development)"
```
- [ ] **Step 6** Report — RC7 cut locally (not pushed/deployed).

---

## Notes for the implementer
- **The single-node-cluster IT is the verification of the Lettuce cluster API.** Treat a failing/ skipped cluster IT as a blocker to investigate, not to weaken.
- **`RedisClusterMode` prefix** avoids colliding with the existing standalone `RedisClusterNodeHeartbeat`.
- **Exactly one transport:** standalone beans `matchIfMissing=true` on `cluster-nodes`, cluster beans active only when it's set — no duplicate `ClusterBroker`/`SessionRegistry`.
- **Scope honesty in docs:** regular cluster pub/sub = no fan-out reduction; sharded pub/sub + multi-node cluster testing → 2.0.0.
- Reliable-stream (`ReliableBroker`) and `ClusterReaper` on cluster are out of scope — gate their standalone-connection beans off in cluster mode and note as follow-ups.
