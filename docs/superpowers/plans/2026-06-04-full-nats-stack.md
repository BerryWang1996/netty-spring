# Full NATS Stack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** NATS JetStream-KV implementations of `SessionRegistry`/`ClusterNodeHeartbeat`/`ClusterReaper`, selected by `nats.registry=true`, so a deployment can run NATS-only (no Redis) — completing all-NATS / mixed / all-Redis.

**Architecture:** Three additive `…cluster.nats` impls over NATS JetStream KV buckets (`netty-sessions`, `netty-nodes` timestamp-heartbeat, `netty-reaping` create-if-absent). A separate JetStream connection (RC9's broker connection is left untouched → no init-order race). Auto-config re-gates every Redis registry/heartbeat/reaper/client bean with `&& NOT_ALL_NATS` and adds the NATS-KV beans on `ALL_NATS`, order-independent across the 5-deployment matrix.

**Tech Stack:** Java 17, `io.nats:jnats` 2.20.4 (already optional, RC9) — JetStream KV; Spring Boot 2.7; JUnit 5 + Mockito; NATS Testcontainers (`nats:2.10 -js`).

---

## Environment notes
- Repo `C:\Users\qq951\IdeaProjects\netty-spring`; `./mvnw`; Java 17; Docker live (Testcontainers: JetStream NATS + core NATS + Redis).
- Branch `feature/1.9.x-full-nats` from `master` @ `1bb4e27`. No push/deploy. **Cuts v1.9.0-RC10** (Task 8).
- Cluster module tests = **JUnit 5 + Mockito, NO AssertJ**.
- **NATS KV key legality:** keys allow only `[-/_=.a-zA-Z0-9]`. Use **base64url-no-padding** (`[A-Za-z0-9_-]`) for uri/nodeId tokens that get prefix-filtered, and `.` as the separator. **Never `|`.** sessionId (UUID) is safe raw. Heartbeat keys are **raw nodeId** (exact-key only — `findExpiredNodes` returns them as nodeIds).

## ⚠️ jnats JetStream KV API verification mandate (read first)
The KV code is from jnats 2.20.4 API recall and **must be verified against the jar** — `NatsKvIntegrationTest` (Task 6, over real `nats:2.10 -js`) is the oracle. Expected API: `connection.keyValueManagement()` → `KeyValueManagement.create(io.nats.client.api.KeyValueConfiguration)`; `KeyValueConfiguration.builder().name(String).ttl(java.time.Duration).build()`; `connection.keyValue(String bucket)` → `KeyValue`; `KeyValue.put(String,byte[])`→long, `get(String)`→`KeyValueEntry` (null if absent), `delete(String)`, `create(String,byte[])`→long (throws on existing key — **verify the exact exception**, likely `io.nats.client.JetStreamApiException`), `keys()`→`List<String>`; `KeyValueEntry.getValueAsString()`. If a round-trip fails, fix the API until it passes — **never weaken the IT.**

## File Structure
- New (main): `…cluster/nats/NatsKvSessionRegistry.java`, `NatsKvNodeHeartbeat.java`, `NatsKvReaper.java`.
- Modified (main): `ClusterProperties.java` (`Nats.registry`); `NettyWebSocketClusterConfigure.java` (matrix + KV connection + 3 beans).
- New (test): `…cluster/nats/NatsKv{SessionRegistry,NodeHeartbeat,Reaper}Test.java`; `…cluster/ClusterTestNatsJetStream.java` + `NatsKvIntegrationTest.java`; starter `…boot/configure/ClusterTestNatsJetStream.java`.
- Modified (test): starter `NettyWebSocketClusterConfigureTest.java`; metadata json.
- Docs: `cluster-design.md` (ADR-001 + scope), `api-guide.md`, `release-notes-1.9.0.md` (Task 8).

---

## Task 0: Branch
- [ ] `git checkout master && git checkout -b feature/1.9.x-full-nats && git branch --show-current` → `feature/1.9.x-full-nats`. Confirm `git log --oneline -1` shows `1bb4e27` (or later).

---

## Task 1: `NatsKvSessionRegistry`

**Files:** Create `netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/nats/NatsKvSessionRegistry.java`; test `…/nats/NatsKvSessionRegistryTest.java`

(Read `…cluster/redis/RedisSessionRegistry.java` + `RedisClusterModeSessionRegistry.java` for the contract + the non-atomic-deregister pattern + the `supplyAsync` style; read the `SessionRegistry` SPI.)

- [ ] **Step 1: Write the impl** (Apache header from a sibling, then):
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.nats;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;
import io.nats.client.KeyValue;
import io.nats.client.KeyValueEntry;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * NATS JetStream-KV implementation of {@link SessionRegistry} (bucket {@code netty-sessions}). Used in the
 * all-NATS deployment (no Redis). Keys are NATS-KV-legal: {@code s.<b64url(uri)>.<sessionId>} for the session
 * → nodeId entry, plus {@code n.<b64url(nodeId)>.<b64url(uri)>.<sessionId>} membership keys for
 * {@link #removeAllForNode}. Deregister is NON-ATOMIC (read owner, then delete the two keys) — same theoretical
 * race as the Redis-Cluster impl under UUID sessionIds. KV ops are blocking, so methods wrap them in async stages
 * (mirroring {@code RedisSessionRegistry.clusterSessionIds}).
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class NatsKvSessionRegistry implements SessionRegistry {

    private final KeyValue kv;

    public NatsKvSessionRegistry(KeyValue kv) {
        this.kv = kv;
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
    private static String sessionKey(String uri, String sessionId) { return "s." + b64(uri) + "." + sessionId; }
    private static String memberKey(String nodeId, String uri, String sessionId) {
        return "n." + b64(nodeId) + "." + b64(uri) + "." + sessionId;
    }

    @Override
    public CompletionStage<Void> register(String uri, String sessionId, String nodeId, Map<String, String> metadata) {
        return CompletableFuture.runAsync(() -> {
            try {
                kv.put(sessionKey(uri, sessionId), nodeId.getBytes(StandardCharsets.UTF_8));
                kv.put(memberKey(nodeId, uri, sessionId), new byte[0]);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException("NATS KV register failed", e);
            }
        });
    }

    @Override
    public CompletionStage<Void> deregister(String uri, String sessionId) {
        return CompletableFuture.runAsync(() -> {
            try {
                KeyValueEntry e = kv.get(sessionKey(uri, sessionId));
                if (e == null) {
                    return;
                }
                String nodeId = e.getValueAsString();
                kv.delete(sessionKey(uri, sessionId));
                if (nodeId != null) {
                    kv.delete(memberKey(nodeId, uri, sessionId));
                }
            } catch (Exception ex) {
                throw new java.util.concurrent.CompletionException("NATS KV deregister failed", ex);
            }
        });
    }

    @Override
    public CompletionStage<String> lookupNode(String uri, String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                KeyValueEntry e = kv.get(sessionKey(uri, sessionId));
                return e == null ? null : e.getValueAsString();
            } catch (Exception ex) {
                throw new java.util.concurrent.CompletionException("NATS KV lookup failed", ex);
            }
        });
    }

    @Override
    public CompletionStage<Set<String>> clusterSessionIds(String uri) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String prefix = "s." + b64(uri) + ".";
                Set<String> ids = new HashSet<>();
                for (String key : kv.keys()) {
                    if (key.startsWith(prefix)) {
                        ids.add(key.substring(prefix.length()));
                    }
                }
                return ids;
            } catch (Exception ex) {
                throw new java.util.concurrent.CompletionException("NATS KV clusterSessionIds failed", ex);
            }
        });
    }

    @Override
    public CompletionStage<Void> removeAllForNode(String nodeId) {
        return CompletableFuture.runAsync(() -> {
            try {
                String prefix = "n." + b64(nodeId) + ".";
                for (String key : kv.keys()) {
                    if (key.startsWith(prefix)) {
                        // key = n.<b64(nodeId)>.<b64(uri)>.<sessionId>
                        String rest = key.substring(prefix.length());
                        int dot = rest.lastIndexOf('.');
                        if (dot > 0) {
                            String b64uri = rest.substring(0, dot);
                            String sessionId = rest.substring(dot + 1);
                            kv.delete("s." + b64uri + "." + sessionId);
                        }
                        kv.delete(key);
                    }
                }
            } catch (Exception ex) {
                throw new java.util.concurrent.CompletionException("NATS KV removeAllForNode failed", ex);
            }
        });
    }

    @Override
    public void shutdown() {
        log.info("NatsKvSessionRegistry shut down");
    }
}
```
- [ ] **Step 2: Unit test** `NatsKvSessionRegistryTest` — mock `KeyValue`; stub `get(...)` → a mock `KeyValueEntry` (`when(entry.getValueAsString()).thenReturn("node-A")`). Assert: `register("/ws/x","s1","node-A",Map.of())` → `verify(kv).put(eq("s." + b64("/ws/x") + ".s1"), any())` + the member key put; `deregister` → `get` then `delete` of both keys, and `verify(kv, never())` of any atomic/eval-like op (there is none — just confirm only get+delete); `lookupNode` → "node-A". Helper `b64` mirrors the impl. (`KeyValue`/`KeyValueEntry` are jnats interfaces → mockable.)
- [ ] **Step 3: Run** `./mvnw -B -ntp -pl netty-spring-websocket-cluster -am test -Dtest=NatsKvSessionRegistryTest -Dsurefire.failIfNoSpecifiedTests=false` → pass. (Adjust to the real jnats `KeyValue`/`KeyValueEntry` method names if they differ — verify against the jar.)
- [ ] **Step 4: Commit** (`feat(cluster): NatsKvSessionRegistry (JetStream KV)`).

---

## Task 2: `NatsKvNodeHeartbeat`

**Files:** Create `…/nats/NatsKvNodeHeartbeat.java`; test `…/nats/NatsKvNodeHeartbeatTest.java`

(Read `…cluster/redis/RedisClusterNodeHeartbeat.java` + the `ClusterNodeHeartbeat` SPI — note it is a **synchronous** SPI: `void register(String,long)`, `void renewHeartbeat(String,long)`, `void deregister(String)`, `List<String> findExpiredNodes(long)`.)

- [ ] **Step 1: Write the impl:**
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.nats;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import io.nats.client.KeyValue;
import io.nats.client.KeyValueEntry;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * NATS JetStream-KV implementation of {@link ClusterNodeHeartbeat} (bucket {@code netty-nodes}). Liveness is
 * timestamp-based (no reliance on KV maxAge purge timing): each key is {@code nodeId} → last-seen millis. A node
 * whose timestamp is older than the timeout is reported expired (and reaped/cleaned by reconciliation, exactly
 * like the Redis nodes-hash timestamp check). Keys are raw nodeIds (exact-key ops only).
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class NatsKvNodeHeartbeat implements ClusterNodeHeartbeat {

    private final KeyValue kv;

    public NatsKvNodeHeartbeat(KeyValue kv) {
        this.kv = kv;
    }

    @Override
    public void register(String nodeId, long timeoutMs) {
        renewHeartbeat(nodeId, timeoutMs);
    }

    @Override
    public void renewHeartbeat(String nodeId, long timeoutMs) {
        try {
            kv.put(nodeId, String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("NATS KV heartbeat put failed for node {}", nodeId, e);
        }
    }

    @Override
    public void deregister(String nodeId) {
        try {
            kv.delete(nodeId);
        } catch (Exception e) {
            log.warn("NATS KV heartbeat delete failed for node {}", nodeId, e);
        }
    }

    @Override
    public List<String> findExpiredNodes(long timeoutMs) {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        try {
            for (String nodeId : kv.keys()) {
                KeyValueEntry e = kv.get(nodeId);
                if (e == null) {
                    continue;
                }
                try {
                    if (now - Long.parseLong(e.getValueAsString()) > timeoutMs) {
                        expired.add(nodeId);
                    }
                } catch (NumberFormatException nfe) {
                    log.warn("Invalid heartbeat timestamp for node {}: {}", nodeId, e.getValueAsString());
                }
            }
        } catch (Exception ex) {
            log.warn("NATS KV findExpiredNodes failed", ex);
        }
        return expired;
    }
}
```
- [ ] **Step 2: Unit test** — mock `KeyValue`: `register("n1",10000)` → `verify(kv).put(eq("n1"), any())`. For `findExpiredNodes`: stub `kv.keys()` → `List.of("stale","fresh")`, `kv.get("stale")` → entry with value `String.valueOf(System.currentTimeMillis()-60000)`, `kv.get("fresh")` → `String.valueOf(System.currentTimeMillis())`; assert `findExpiredNodes(10000)` contains `"stale"` not `"fresh"`.
- [ ] **Step 3: Run** (`-Dtest=NatsKvNodeHeartbeatTest`) → pass.
- [ ] **Step 4: Commit** (`feat(cluster): NatsKvNodeHeartbeat (JetStream KV, timestamp liveness)`).

---

## Task 3: `NatsKvReaper`

**Files:** Create `…/nats/NatsKvReaper.java`; test `…/nats/NatsKvReaperTest.java`

(Read `…cluster/redis/RedisClusterReaper.java` + the `ClusterReaper` SPI — `boolean tryClaim(String deadNodeId, String reaperNodeId, long claimWindowMs)`.)

- [ ] **Step 1: Write the impl:**
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.nats;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterReaper;
import io.nats.client.KeyValue;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * NATS JetStream-KV implementation of {@link ClusterReaper} (bucket {@code netty-reaping}, with a bounded maxAge =
 * claim window). {@code tryClaim} uses KV {@code create} (atomic create-if-absent — the {@code SET NX} analog):
 * only the first caller within the window succeeds. On any other error it reaps anyway (cleanup is idempotent),
 * matching {@code RedisClusterReaper}.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class NatsKvReaper implements ClusterReaper {

    private final KeyValue kv;

    public NatsKvReaper(KeyValue kv) {
        this.kv = kv;
    }

    @Override
    public boolean tryClaim(String deadNodeId, String reaperNodeId, long claimWindowMs) {
        try {
            kv.create("r." + deadNodeId, reaperNodeId.getBytes(StandardCharsets.UTF_8));
            log.debug("Node {} claimed reaping of dead node {} (NATS KV)", reaperNodeId, deadNodeId);
            return true;
        } catch (io.nats.client.JetStreamApiException existsOrApi) {
            // create() on an existing key → another node already claimed within the window.
            return false;
        } catch (Exception e) {
            // On any other error prefer correctness over dedup: reap anyway (cleanup is idempotent).
            log.debug("Reap-claim for dead node {} errored; proceeding with cleanup", deadNodeId, e);
            return true;
        }
    }
}
```
**VERIFY:** confirm `KeyValue.create(String, byte[])` throws `io.nats.client.JetStreamApiException` (or which type) when the key exists — adjust the catch to the real type. The IT (Task 6) exercises the two-claimant path.
- [ ] **Step 2: Unit test** — mock `KeyValue`: first `create(...)` returns a revision → `tryClaim` true; stub `create(...)` to throw the exists exception → `tryClaim` false.
- [ ] **Step 3: Run** (`-Dtest=NatsKvReaperTest`) → pass.
- [ ] **Step 4: Commit** (`feat(cluster): NatsKvReaper (JetStream KV create-if-absent)`).

---

## Task 4: `ClusterProperties.Nats.registry`

**Files:** Modify `…cluster/ClusterProperties.java`

- [ ] **Step 1:** In the existing `Nats` nested class (added in RC9, has `servers`) add:
```java
        /** When {@code true} (and {@code servers} is set), the SessionRegistry/heartbeat/reaper run on NATS
         *  JetStream KV instead of Redis — a fully NATS-only deployment (no Redis). Requires a JetStream-enabled
         *  NATS server ({@code nats-server -js}). Default {@code false} (mixed: NATS broker + Redis registry). */
        private boolean registry = false;

        public boolean isRegistry() { return registry; }
        public void setRegistry(boolean registry) { this.registry = registry; }
```
- [ ] **Step 2: Compile** (`-pl netty-spring-websocket-cluster -am -DskipTests compile`) → BUILD SUCCESS.
- [ ] **Step 3: Commit** (`feat(cluster): nats.registry flag (all-NATS opt-in)`).

---

## Task 5: Auto-config — the 5-deployment matrix + the NATS-KV beans

**Files:** Modify `netty-websocket-cluster-spring-boot-starter/.../NettyWebSocketClusterConfigure.java`

- [ ] **Step 1: Constants.** After the existing `STANDALONE_REDIS_BROKER`/`CLUSTER_REDIS_BROKER` constants add:
```java
    /** SpEL: nats.registry == true. */
    static final String NATS_REGISTRY =
            "'${server.netty.websocket.cluster.nats.registry:false}' == 'true'";
    /** All-NATS: nats.servers set AND nats.registry true → NATS-KV registry (no Redis). */
    static final String ALL_NATS = NATS_TRANSPORT + " and " + NATS_REGISTRY;
    /** Not all-NATS (used to suppress the Redis registry/heartbeat/reaper/client beans in all-NATS mode). */
    static final String NOT_ALL_NATS = "!(" + NATS_TRANSPORT + " and " + NATS_REGISTRY + ")";
    /** Standalone Redis registry/infra: cluster-nodes empty AND not all-NATS. */
    static final String STANDALONE_REDIS_REGISTRY = STANDALONE_TRANSPORT + " and " + NOT_ALL_NATS;
    /** Cluster Redis registry/infra: cluster-nodes set AND not all-NATS. */
    static final String CLUSTER_REDIS_REGISTRY = CLUSTER_TRANSPORT + " and " + NOT_ALL_NATS;
```
- [ ] **Step 2: Re-gate the standalone Redis infra/registry beans.** Change the `@ConditionalOnExpression(STANDALONE_TRANSPORT)` on these to `@ConditionalOnExpression(STANDALONE_REDIS_REGISTRY)`: `nettyClusterRedisClient`, `nettyClusterRedisConnection`, `sessionRegistry`, `clusterNodeHeartbeat`, `clusterReaper`. Also `reliableBroker` (keep its `@ConditionalOnProperty(reliable.enable)` and `@ConditionalOnMissingBean`; change its `@ConditionalOnExpression` to `STANDALONE_REDIS_REGISTRY` so reliable is off in all-NATS).
- [ ] **Step 3: Re-gate the cluster-mode Redis beans.** Change the `@ConditionalOnExpression(CLUSTER_TRANSPORT)` on these to `@ConditionalOnExpression(CLUSTER_REDIS_REGISTRY)`: `nettyClusterRedisClusterClient`, `nettyClusterRedisClusterConnection`, `sessionRegistryCluster`, `clusterNodeHeartbeatCluster`, `clusterReaperCluster`. (Leave the two Redis BROKER beans — `clusterBroker`/`clusterBrokerCluster` on `STANDALONE_REDIS_BROKER`/`CLUSTER_REDIS_BROKER` — unchanged: `nats.servers` set already suppresses them.)
- [ ] **Step 4: Add the JetStream KV connection + bucket bootstrap + the 3 NATS-KV beans.** Add a private helper + the beans (next to the RC9 `clusterBrokerNats`):
```java
    @Bean(name = "nettyClusterNatsKvConnection", destroyMethod = "close")
    @ConditionalOnClass(io.nats.client.Connection.class)
    @ConditionalOnExpression(ALL_NATS)
    @ConditionalOnMissingBean(name = "nettyClusterNatsKvConnection")
    public io.nats.client.Connection nettyClusterNatsKvConnection(ClusterProperties properties) throws Exception {
        io.nats.client.Connection conn = io.nats.client.Nats.connect(io.nats.client.Options.builder()
                .server(properties.getNats().getServers())
                .maxReconnects(-1)
                .build());
        // Idempotent bucket bootstrap (create-if-absent). Requires a JetStream-enabled NATS server (-js).
        ensureBucket(conn, "netty-sessions", null);
        ensureBucket(conn, "netty-nodes", null);
        ensureBucket(conn, "netty-reaping", java.time.Duration.ofSeconds(30)); // claim window
        log.info("Cluster registry = NATS JetStream KV (all-NATS; no Redis) at {}", properties.getNats().getServers());
        return conn;
    }

    private static void ensureBucket(io.nats.client.Connection conn, String name, java.time.Duration ttl) throws Exception {
        if (conn.keyValueManagement().getBucketNames().contains(name)) {
            return;
        }
        io.nats.client.api.KeyValueConfiguration.Builder b =
                io.nats.client.api.KeyValueConfiguration.builder().name(name);
        if (ttl != null) {
            b.ttl(ttl);
        }
        try {
            conn.keyValueManagement().create(b.build());
        } catch (io.nats.client.JetStreamApiException alreadyExists) {
            // raced with another node — fine.
        }
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnClass(io.nats.client.Connection.class)
    @ConditionalOnExpression(ALL_NATS)
    @ConditionalOnMissingBean(SessionRegistry.class)
    public SessionRegistry natsKvSessionRegistry(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterNatsKvConnection")
            io.nats.client.Connection conn) throws Exception {
        return new com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvSessionRegistry(
                conn.keyValue("netty-sessions"));
    }

    @Bean
    @ConditionalOnClass(io.nats.client.Connection.class)
    @ConditionalOnExpression(ALL_NATS)
    @ConditionalOnMissingBean(ClusterNodeHeartbeat.class)
    public ClusterNodeHeartbeat natsKvNodeHeartbeat(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterNatsKvConnection")
            io.nats.client.Connection conn) throws Exception {
        return new com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvNodeHeartbeat(
                conn.keyValue("netty-nodes"));
    }

    @Bean
    @ConditionalOnClass(io.nats.client.Connection.class)
    @ConditionalOnExpression(ALL_NATS)
    @ConditionalOnMissingBean(ClusterReaper.class)
    public ClusterReaper natsKvReaper(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterNatsKvConnection")
            io.nats.client.Connection conn) throws Exception {
        return new com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvReaper(
                conn.keyValue("netty-reaping"));
    }
```
(`SessionRegistry`, `ClusterNodeHeartbeat`, `ClusterReaper` are already imported. `sessionRegistry` returns the interface so the bean type matches across impls.)
- [ ] **Step 5: Verify the truth table (reason it through, then compile).** For each of {all-Redis-standalone (nats empty, cn empty), all-Redis-cluster (nats empty, cn set), mixed-standalone (nats set, registry=false, cn empty), mixed-cluster (nats set, registry=false, cn set), all-NATS (nats set, registry=true)} exactly one `SessionRegistry`/`ClusterNodeHeartbeat`/`ClusterReaper` bean activates, and no `RedisClient` exists in all-NATS. Then `./mvnw -B -ntp -pl netty-websocket-cluster-spring-boot-starter -am -DskipTests compile` → BUILD SUCCESS.
- [ ] **Step 6: Commit** (`feat(cluster): all-NATS registry selection (nats.registry) + JetStream KV beans`).

---

## Task 6: JetStream Testcontainers resolver + integration oracle

**Files:** Create `…cluster/ClusterTestNatsJetStream.java`, `…cluster/NatsKvIntegrationTest.java`

- [ ] **Step 1: Resolver** — copy `…cluster/ClusterTestNats.java` (RC9) and adapt: same `api.version=1.43` static initializer; env var `CLUSTER_TEST_NATS_JS_URL`; the container is `new GenericContainer<>(DockerImageName.parse("nats:2.10")).withExposedPorts(4222).withCommand("-js").waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)))`; `available()`, `url()`, `newConnection()` (`Nats.connect(Options.builder().server(url()).build())`). Name the class `ClusterTestNatsJetStream`.
- [ ] **Step 2: IT (the oracle)** `NatsKvIntegrationTest`:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvReaper;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvSessionRegistry;
import io.nats.client.Connection;
import io.nats.client.api.KeyValueConfiguration;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NatsKvIntegrationTest {

    private static Connection conn;
    private static boolean available;

    @BeforeAll
    static void up() throws Exception {
        available = ClusterTestNatsJetStream.available();
        Assumptions.assumeTrue(available, "no JetStream NATS (no env + no Docker)");
        conn = ClusterTestNatsJetStream.newConnection();
        for (String b : List.of("netty-sessions", "netty-nodes", "netty-reaping")) {
            if (!conn.keyValueManagement().getBucketNames().contains(b)) {
                conn.keyValueManagement().create(KeyValueConfiguration.builder().name(b).build());
            }
        }
    }

    @AfterAll
    static void down() throws Exception {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    void registry_registerLookupDeregister() throws Exception {
        NatsKvSessionRegistry reg = new NatsKvSessionRegistry(conn.keyValue("netty-sessions"));
        reg.register("/ws/kv", "kid-1", "node-A", Collections.emptyMap()).toCompletableFuture().join();
        assertEquals("node-A", reg.lookupNode("/ws/kv", "kid-1").toCompletableFuture().join());
        assertTrue(reg.clusterSessionIds("/ws/kv").toCompletableFuture().join().contains("kid-1"));
        reg.deregister("/ws/kv", "kid-1").toCompletableFuture().join();
        assertNull(reg.lookupNode("/ws/kv", "kid-1").toCompletableFuture().join());
    }

    @Test
    void heartbeat_staleDetected_freshExcluded() throws Exception {
        NatsKvNodeHeartbeat hb = new NatsKvNodeHeartbeat(conn.keyValue("netty-nodes"));
        hb.register("kv-stale", 200);
        Thread.sleep(400); // let kv-stale's timestamp age past 200ms
        hb.register("kv-fresh", 60000);
        List<String> expired = hb.findExpiredNodes(200);
        assertTrue(expired.contains("kv-stale"), "stale node detected via timestamp");
        assertFalse(expired.contains("kv-fresh"), "freshly-registered node excluded");
        hb.deregister("kv-stale");
        hb.deregister("kv-fresh");
    }

    @Test
    void reaper_claimOnceSingleWinner() {
        NatsKvReaper r1 = new NatsKvReaper(conn.keyValue("netty-reaping"));
        NatsKvReaper r2 = new NatsKvReaper(conn.keyValue("netty-reaping"));
        boolean w1 = r1.tryClaim("kv-dead", "node-1", 5000);
        boolean w2 = r2.tryClaim("kv-dead", "node-2", 5000);
        assertTrue(w1 ^ w2, "exactly one winner");
        assertTrue(w1, "first claimant wins");
        assertFalse(w2, "second locked out");
    }
}
```
- [ ] **Step 3: Run** `./mvnw -B -ntp -pl netty-spring-websocket-cluster -am test -Dtest=ClusterTestNatsJetStream*,NatsKv*Test,NatsKvIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` → all pass, the IT **NOT skipped**. **Iterate the jnats KV API here until the round-trips pass** (per the mandate) — verify `keyValueManagement()`, `KeyValueConfiguration.builder().name().ttl()`, `keyValue()`, `put/get/delete/create/keys`, `KeyValueEntry.getValueAsString()`, and the `create`-on-existing exception type. Do NOT weaken.
- [ ] **Step 4: Commit** (`test(cluster): JetStream NATS resolver + KV registry/heartbeat/reaper IT (oracle)`).

---

## Task 7: Context test + metadata + docs + ADR-001

- [ ] **Step 1:** Duplicate `ClusterTestNatsJetStream` into `netty-websocket-cluster-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/boot/configure/ClusterTestNatsJetStream.java` (package line only differs; mirrors the `ClusterTestNats` duplication).
- [ ] **Step 2:** In `…boot/configure/NettyWebSocketClusterConfigureTest.java` add:
```java
    @Test
    void natsRegistry_allNats_noRedis() {
        Assumptions.assumeTrue(ClusterTestNatsJetStream.available(), "no JetStream NATS");
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.nats.servers=" + ClusterTestNatsJetStream.url(),
                        "server.netty.websocket.cluster.nats.registry=true",
                        "server.netty.websocket.cluster.node-id=ctx-allnats-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvSessionRegistry.class);
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvNodeHeartbeat.class);
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterReaper.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsKvReaper.class);
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsClusterBroker.class);
                    assertThat(context).doesNotHaveBean(io.lettuce.core.RedisClient.class);
                    assertThat(context.getBean(MessageSender.class)).isInstanceOf(ClusterMessageSender.class);
                });
    }
```
- [ ] **Step 3: metadata** — add to `additional-spring-configuration-metadata.json`:
```json
    {
      "name": "server.netty.websocket.cluster.nats.registry",
      "type": "java.lang.Boolean",
      "description": "When true (and nats.servers is set), run the SessionRegistry/heartbeat/reaper on NATS JetStream KV instead of Redis — a fully NATS-only deployment (no Redis). Requires a JetStream-enabled NATS server (nats-server -js). Default false (mixed: NATS broker + Redis registry).",
      "defaultValue": false
    },
```
- [ ] **Step 4: Docs** (Edit tool only; U+FFFD scan after). `docs/cluster-design.md`: update the ADR-001 "NATS-first / NATS-only" rejected row/text to "NATS-only **opt-in** via `nats.registry=true` (JetStream KV registry/heartbeat/reaper); mixed (NATS broker + Redis registry) and all-Redis remain the defaults; the registry was never the scaling wall, so all-NATS is operational, not performance — requires a JetStream-enabled NATS server"; reflect it in the scope table. `docs/api-guide.md`: add a `cluster.nats.registry` config row (default false; true = all-NATS, requires JetStream).
- [ ] **Step 5: Run** `./mvnw -B -ntp -pl netty-websocket-cluster-spring-boot-starter -am test -Dtest=NettyWebSocketClusterConfigureTest -Dsurefire.failIfNoSpecifiedTests=false` → passes; the new test runs (gated on JetStream NATS availability). U+FFFD scan on both docs = 0.
- [ ] **Step 6: Commit** (`feat(cluster): all-NATS context test + metadata + docs + ADR-001 update`).

---

## Task 8: Full test + cut v1.9.0-RC10

- [ ] **Step 1:** `./mvnw -B -ntp test` (Docker live) → BUILD SUCCESS, 11 modules; `NatsKvIntegrationTest` + the all-NATS context test run, NOT skipped. Capture the total test count. STOP on any failure/unexpected skip.
- [ ] **Step 2: Release-notes** (`docs/release-notes-1.9.0.md`, Edit tool, UTF-8): add `### ⑮ 全 NATS 栈（NATS-only 选项）/ Full NATS Stack` after ⑭ — `NatsKvSessionRegistry`/`NatsKvNodeHeartbeat`/`NatsKvReaper` over JetStream KV; `nats.registry=true` → all-NATS (no Redis); **needs a JetStream-enabled NATS server**; timestamp heartbeat; ADR-001 updated (NATS-only now opt-in). Status `RC9`→`RC10` + clause; bump the test-count to Step-1's total; add an RC10 test bullet (`NatsKv*Test` + `NatsKvIntegrationTest` + the all-NATS context test). Verify U+FFFD = 0.
- [ ] **Step 3: Bump poms** — `for f in $(grep -rl "1.9.0-RC9" --include=pom.xml .); do sed -i 's|<version>1.9.0-RC9</version>|<version>1.9.0-RC10</version>|g' "$f"; done`; verify 0 RC9 / 11 RC10.
- [ ] **Step 4: Re-test** — `./mvnw -B -ntp -q test` → BUILD SUCCESS.
- [ ] **Step 5: Commit + tag**
```bash
git add -A   # only if `git status` shows just poms + release-notes; else stage explicit paths
git commit -m "release: 1.9.0-RC10 - full NATS stack (NATS-only via nats.registry)

NatsKv{SessionRegistry,NodeHeartbeat,Reaper} over JetStream KV; nats.registry=true makes a
deployment fully NATS-only (no Redis; requires a JetStream-enabled NATS server). Additive/opt-in;
mixed + all-Redis remain defaults. ADR-001 updated. Part of the 1.9.0 cycle.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git tag -a v1.9.0-RC10 -m "v1.9.0-RC10 - full NATS stack (1.9.0 cycle, in development)"
```
- [ ] **Step 6: Finish** — superpowers:finishing-a-development-branch: FF-merge `feature/1.9.x-full-nats` into `master`, keep the `v1.9.0-RC10` tag, delete the branch, **NO push, NO deploy**.

---

## Notes for the implementer
- **The `NatsKvIntegrationTest` over real `nats:2.10 -js` is the verification of the JetStream KV API.** A failing/skipped KV IT is a blocker to investigate, not to weaken.
- **5-deployment matrix:** exactly one registry/heartbeat/reaper bean per deployment, order-independent SpEL. No `RedisClient` in all-NATS. Don't introduce `@ConditionalOnMissingBean`-ordering reliance.
- **NATS KV keys:** base64url uri/nodeId tokens + `.` separators; sessionId raw; heartbeat keys raw nodeId. Never `|` or other illegal chars.
- **Don't touch** RC9's `NatsClusterBroker` (it owns its own core connection), the Redis impls, or the broker beans.
