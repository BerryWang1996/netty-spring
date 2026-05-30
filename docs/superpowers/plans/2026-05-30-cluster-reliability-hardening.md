# Cluster Reliability Hardening (1.9.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the five resilience/correctness items 1.8.0 deferred, so the existing Redis WebSocket cluster is bulletproof within its supported envelope (≤~10 nodes + dedicated Redis), with no new modules, no breaking SPI changes, and only one intentional default behavior change.

**Architecture:** In-place hardening of `ClusterNodeManager`, `RedisClusterNodeHeartbeat`, `RedisSessionRegistry`, `ClusterSessionHookImpl`, and the cluster auto-config. Two new small types: a `ClusterReaper` interface (+ `RedisClusterReaper` SET-NX impl) for reconciliation leader-election, and a `CoalescingRegistryWriter` for storm-throttled registry writes. Re-introduces two config keys removed as dead config in 1.8.0 (`redis-loss-grace-period-ms`, `session-registry-write-rate`), now backed by real behavior.

**Tech Stack:** Java 17, Spring Boot 2.7.18 auto-config, Lettuce 6.1.x (Redis), JUnit 5, Maven 3.9 (11 modules). Source spec: `docs/superpowers/specs/2026-05-30-cluster-reliability-hardening-design.md`.

---

## Implementation order & rationale

1. **Task 1** — bump to `1.9.0-SNAPSHOT` (develop on a snapshot).
2. **Task 2 (item ②a)** — split `ClusterNodeManager` into two schedulers. *Must precede item ① — the grace timer runs on the new reconciliation scheduler.*
3. **Task 3 (item ②b)** — batched `EXISTS` in `RedisClusterNodeHeartbeat`.
4. **Task 4 (item ①)** — redis-loss grace period (debounced degradation) + config.
5. **Task 5 (item ④)** — reconciliation leader-election (`ClusterReaper`).
6. **Task 6 (item ③)** — atomic Lua `deregister`.
7. **Task 7 (item ⑤)** — registry write coalescing + config.
8. **Task 8** — docs (cluster-design, api-guide §9, release notes, dev-plan, checklist, CLAUDE.md).
9. **Task 9** — full `mvn test` (11 modules) green; integration-test note.
10. **Task 10** — flip `1.9.0-SNAPSHOT` → `1.9.0`, commit, tag `v1.9.0` (deploy is user-driven — see note).

Items ②③④ also get real-Redis integration tests (skipped when Redis is absent, never failed — the existing `RedisIntegrationTest` pattern).

---

## File Structure

**Modified — `netty-spring-websocket-cluster`:**
- `.../cluster/node/ClusterNodeManager.java` — two schedulers (②a); grace-period debounce (①); reaper gating in `doReconciliation` (④).
- `.../cluster/redis/RedisClusterNodeHeartbeat.java` — batched async `EXISTS` in `findExpiredNodes` (②b).
- `.../cluster/redis/RedisSessionRegistry.java` — atomic Lua `deregister` (③).
- `.../cluster/ClusterProperties.java` — add `redisLossGracePeriodMs` (①) + `sessionRegistryWriteRate` (⑤).
- `.../cluster/ClusterSessionHookImpl.java` — write through `CoalescingRegistryWriter` (⑤).

**Created — `netty-spring-websocket-cluster`:**
- `.../cluster/node/ClusterReaper.java` — leader-election interface + `alwaysReap()` default (④).
- `.../cluster/redis/RedisClusterReaper.java` — `SET NX PX` claim impl (④).
- `.../cluster/CoalescingRegistryWriter.java` — token-bucket pass-through + coalescing queue (⑤).

**Modified — `netty-websocket-cluster-spring-boot-starter`:**
- `.../boot/configure/NettyWebSocketClusterConfigure.java` — wire grace period; `ClusterReaper` bean; `CoalescingRegistryWriter` bean; hook uses the writer.
- `src/main/resources/META-INF/additional-spring-configuration-metadata.json` — 2 new property entries.

**Created — tests (`netty-spring-websocket-cluster/src/test`):**
- `.../cluster/ClusterNodeManagerReliabilityTest.java` — grace (①), thread isolation (②a), reaper gating (④) — all dependency-free unit tests.
- `.../cluster/ClusterRegistryWriterTest.java` — coalescing/throttle (⑤).

**Modified — tests:**
- `.../cluster/RedisIntegrationTest.java` — batched expiry (②b), reap-claim winner (④), Lua deregister (③).
- `netty-websocket-cluster-spring-boot-starter/.../NettyWebSocketClusterConfigureTest.java` — assert the 2 new beans in the enabled path.

---

## Task 1: Bump to 1.9.0-SNAPSHOT

**Files:**
- Modify: `pom.xml` (root `<version>`) + 10 module poms (`<parent><version>`).

- [ ] **Step 1: Set the version across all modules**

Run:
```
mvn versions:set -DnewVersion=1.9.0-SNAPSHOT -DgenerateBackupPoms=false
```
Expected: `BUILD SUCCESS`, "Processing change of ...:1.8.0 -> 1.9.0-SNAPSHOT" for the reactor.

Fallback if the versions plugin can't resolve: manually edit `pom.xml` line 11 (`<version>1.8.0</version>` → `<version>1.9.0-SNAPSHOT</version>`) and, in each of the 10 module poms, the `<parent>` block's `<version>1.8.0</version>` → `<version>1.9.0-SNAPSHOT</version>`.

- [ ] **Step 2: Verify no 1.8.0 version refs remain in poms**

Run (PowerShell):
```
Select-String -Path (Get-ChildItem -Recurse -Filter pom.xml).FullName -Pattern '1\.8\.0' 
```
Expected: no output (every pom now reads `1.9.0-SNAPSHOT`).

- [ ] **Step 3: Compile to confirm the reactor still resolves**

Run: `mvn -q -DskipTests compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```
git add -A
git commit -m "chore(release): bump to 1.9.0-SNAPSHOT

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: (Item ②a) Split ClusterNodeManager into two schedulers

**Why:** Heartbeat renewal and the reconciliation sweep share one single-thread scheduler. A slow sweep starves heartbeat renewal → this node's heartbeat key expires → peers falsely reap it. Split into `cluster-hb-{node}` (renewal only) and `cluster-recon-{node}` (sweep + grace timer + resync task).

**Files:**
- Modify: `netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/node/ClusterNodeManager.java`
- Test: `netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterNodeManagerReliabilityTest.java` (create)

- [ ] **Step 1: Write the failing test (thread isolation)**

Create `ClusterNodeManagerReliabilityTest.java`:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.NodeState;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Reliability hardening tests (1.9.0): scheduler isolation (②), grace period (①), reaper gating (④). */
class ClusterNodeManagerReliabilityTest {

    // ---- helpers ----

    /** Heartbeat stub whose findExpiredNodes BLOCKS, to prove it can't starve heartbeat renewal. */
    private static ClusterNodeHeartbeat blockingReconHeartbeat(AtomicInteger renews, CountDownLatch reconEntered) {
        return new ClusterNodeHeartbeat() {
            @Override public void register(String nodeId, long timeoutMs) {}
            @Override public void renewHeartbeat(String nodeId, long timeoutMs) { renews.incrementAndGet(); }
            @Override public void deregister(String nodeId) {}
            @Override public List<String> findExpiredNodes(long timeoutMs) {
                reconEntered.countDown();
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return Collections.emptyList();
            }
        };
    }

    @Test
    void slowReconciliationDoesNotStarveHeartbeat() throws Exception {
        AtomicInteger renews = new AtomicInteger();
        CountDownLatch reconEntered = new CountDownLatch(1);
        // heartbeat every 200ms, reconciliation every 200ms (which then blocks 2s inside findExpiredNodes)
        ClusterNodeManager mgr = new ClusterNodeManager(
                "iso-node", 200, 10000, 200, 60000,
                blockingReconHeartbeat(renews, reconEntered), new InMemorySessionRegistry());
        mgr.start();
        assertTrue(reconEntered.await(2, TimeUnit.SECONDS), "reconciliation should run and block");
        Thread.sleep(1000); // during the blocked sweep, heartbeat must keep ticking on its own thread
        mgr.shutdown();
        assertTrue(renews.get() >= 3,
                "heartbeat must keep renewing while reconciliation is blocked (two schedulers); got " + renews.get());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl netty-spring-websocket-cluster test -Dtest=ClusterNodeManagerReliabilityTest#slowReconciliationDoesNotStarveHeartbeat`
Expected: FAIL — with the single shared scheduler, the 2s blocking sweep stalls heartbeat, so `renews` is ~0–1 (`< 3`).

- [ ] **Step 3: Implement the scheduler split**

In `ClusterNodeManager.java`, replace the scheduler fields (currently lines 72–74):
```java
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatFuture;
    private ScheduledFuture<?> reconciliationFuture;
```
with:
```java
    /** Dedicated heartbeat-renewal scheduler (thread cluster-hb-{node}) — kept lean so a slow
     *  reconciliation sweep can never delay heartbeat renewal (which would let peers falsely reap us). */
    private ScheduledExecutorService heartbeatScheduler;
    /** Dedicated reconciliation scheduler (thread cluster-recon-{node}) — also runs the grace timer
     *  and the RESYNC re-register task. */
    private ScheduledExecutorService reconScheduler;
    private ScheduledFuture<?> heartbeatFuture;
    private ScheduledFuture<?> reconciliationFuture;
```

In `start()`, replace the single-scheduler creation + scheduling (currently lines 148–175) — i.e. the block from `scheduler = Executors.newSingleThreadScheduledExecutor(...)` through the `heartbeatFuture = scheduler.scheduleAtFixedRate(...)` call — with:
```java
        String shortId = nodeId.substring(0, Math.min(8, nodeId.length()));
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-hb-" + shortId);
            t.setDaemon(true);
            return t;
        });
        reconScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-recon-" + shortId);
            t.setDaemon(true);
            return t;
        });

        // Always start reconciliation (slow-path backstop — even if initial registration
        // fails, reconciliation can detect and clean dead peers, and the heartbeat task
        // will retry registration on its next tick).
        reconciliationFuture = reconScheduler.scheduleAtFixedRate(
                this::doReconciliation, reconciliationIntervalMs, reconciliationIntervalMs, TimeUnit.MILLISECONDS);

        // Register this node
        try {
            heartbeat.register(nodeId, heartbeatTimeoutMs);
            log.info("Cluster node {} registered (heartbeat={}ms, timeout={}ms)",
                    nodeId, heartbeatIntervalMs, heartbeatTimeoutMs);
        } catch (Exception e) {
            log.error("Failed to register cluster node {} — starting in DEGRADED mode, "
                    + "heartbeat task will retry", nodeId, e);
            transitionTo(NodeState.DEGRADED);
        }

        // Schedule periodic heartbeat on its OWN thread (runs even in DEGRADED — doHeartbeat()
        // handles retry-on-failure and calls onTransportLost/onTransportRestored).
        heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(
                this::doHeartbeat, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
```

In `onTransportRestored()`, change the resync re-register scheduling from `scheduler.schedule(...)` to `reconScheduler.schedule(...)` (currently line 248).

In `shutdown()`, replace the scheduler teardown (currently the `if (scheduler != null) { scheduler.shutdown(); }` block, lines 219–221) with:
```java
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
        }
        if (reconScheduler != null) {
            reconScheduler.shutdown();
        }
```
(The two `*Future.cancel(false)` lines at 206–211 are unchanged.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl netty-spring-websocket-cluster test -Dtest=ClusterNodeManagerReliabilityTest#slowReconciliationDoesNotStarveHeartbeat`
Expected: PASS — heartbeat keeps renewing on its own thread (`renews >= 3`) while reconciliation is blocked.

- [ ] **Step 5: Commit**

```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/node/ClusterNodeManager.java netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterNodeManagerReliabilityTest.java
git commit -m "feat(cluster): isolate heartbeat and reconciliation onto separate schedulers

A slow reconciliation sweep can no longer starve heartbeat renewal (which
would let peers falsely reap this node). cluster-hb-{node} renews; the new
cluster-recon-{node} runs the sweep, grace timer, and resync task.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: (Item ②b) Batched EXISTS in findExpiredNodes

**Why:** `findExpiredNodes` confirms each timestamp-stale candidate with a **synchronous** `EXISTS` round-trip in a loop. During a simultaneous multi-node expiry that becomes N sequential blocking round-trips on the sweep thread. Issue them as one pipelined async batch.

> **Lettuce hazard:** `connection.setAutoFlushCommands(false)` is connection-WIDE, and this connection is shared with the registry/heartbeat. Do **not** use manual flush. Plain `async.exists(...)` (auto-flush on) already pipelines: fire all commands without awaiting, then await all — ~1 round-trip group, no shared-connection side effects.

**Files:**
- Modify: `netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/redis/RedisClusterNodeHeartbeat.java`
- Test: `netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/RedisIntegrationTest.java` (add a test)

- [ ] **Step 1: Write the failing test (real Redis)**

In `RedisIntegrationTest.java`, add after `heartbeatRegisterRenewAndExpiry` (after line 140):
```java
    @Test
    @Order(11)
    void findExpiredNodesBatchesMultipleSimultaneousExpiries() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisClusterNodeHeartbeat hb = new RedisClusterNodeHeartbeat(connection);
        // three nodes with a short TTL → all expire together
        hb.register("batch-A", 1000);
        hb.register("batch-B", 1000);
        hb.register("batch-C", 1000);
        // one node renewed long → must NOT be reported expired
        hb.register("batch-live", 60000);

        Thread.sleep(1300); // let A/B/C TTLs lapse

        List<String> expired = hb.findExpiredNodes(1000);
        assertTrue(expired.contains("batch-A"));
        assertTrue(expired.contains("batch-B"));
        assertTrue(expired.contains("batch-C"));
        assertFalse(expired.contains("batch-live"), "a freshly-renewed node must be excluded");

        hb.deregister("batch-A"); hb.deregister("batch-B");
        hb.deregister("batch-C"); hb.deregister("batch-live");
    }
```

- [ ] **Step 2: Run the test (passes against current code, or skips without Redis)**

Run: `mvn -q -pl netty-spring-websocket-cluster test -Dtest=RedisIntegrationTest#findExpiredNodesBatchesMultipleSimultaneousExpiries`
Expected: PASS if Redis is on `localhost:16379`; SKIPPED otherwise. This test pins the *behavior* (correct expired set); the refactor in Step 3 must keep it green. (It passes on the old loop too — that is intentional; it guards the refactor.)

- [ ] **Step 3: Implement the batched EXISTS**

In `RedisClusterNodeHeartbeat.java`, add imports (after line 22, with the other lettuce/util imports):
```java
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisAsyncCommands;
```
and (with the `java.util.*` imports):
```java
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
```

Replace the whole `findExpiredNodes` method (currently lines 98–126) with:
```java
    @Override
    public List<String> findExpiredNodes(long timeoutMs) {
        RedisCommands<String, String> sync = connection.sync();
        Map<String, String> nodes = sync.hgetall(NODES_KEY);
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }

        long now = System.currentTimeMillis();

        // Phase 1: collect timestamp-stale candidates (cheap, in-memory).
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, String> entry : nodes.entrySet()) {
            String nodeId = entry.getKey();
            try {
                long lastHeartbeat = Long.parseLong(entry.getValue());
                if (now - lastHeartbeat > timeoutMs) {
                    candidates.add(nodeId);
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid heartbeat timestamp for node {}: {}", nodeId, entry.getValue());
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        // Phase 2: confirm each candidate's heartbeat key is actually gone. Fire all EXISTS commands
        // async WITHOUT awaiting between them (Lettuce pipelines on the default auto-flush), then await
        // all — ~1 round-trip group instead of N sequential blocking calls. We must NOT toggle
        // setAutoFlushCommands here: it is connection-wide and this connection is shared.
        RedisAsyncCommands<String, String> async = connection.async();
        Map<String, RedisFuture<Long>> existsFutures = new LinkedHashMap<>();
        for (String nodeId : candidates) {
            existsFutures.put(nodeId, async.exists(HEARTBEAT_PREFIX + nodeId));
        }

        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, RedisFuture<Long>> e : existsFutures.entrySet()) {
            try {
                Long exists = e.getValue().get(timeoutMs, TimeUnit.MILLISECONDS);
                if (exists != null && exists == 0L) {
                    expired.add(e.getKey());
                }
            } catch (Exception ex) {
                // Treat an EXISTS we couldn't confirm as "still alive" (conservative — don't reap on doubt).
                log.debug("EXISTS check failed for candidate node {} during reconciliation", e.getKey(), ex);
            }
        }
        return expired;
    }
```

- [ ] **Step 4: Run the test to verify it still passes**

Run: `mvn -q -pl netty-spring-websocket-cluster test -Dtest=RedisIntegrationTest#findExpiredNodesBatchesMultipleSimultaneousExpiries`
Expected: PASS (or SKIPPED without Redis). Also run `-Dtest=RedisIntegrationTest#heartbeatRegisterRenewAndExpiry` — still PASS.

- [ ] **Step 5: Commit**

```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/redis/RedisClusterNodeHeartbeat.java netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/RedisIntegrationTest.java
git commit -m "perf(cluster): batch heartbeat EXISTS checks in findExpiredNodes

Fire all candidate EXISTS commands async (Lettuce-pipelined) and await
together — ~1 round-trip instead of N sequential calls during simultaneous
multi-node expiry. Avoids the connection-wide setAutoFlushCommands hazard.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: (Item ①) Redis-loss grace period (debounced degradation)

**Why:** 1.8.0 degrades the node the instant Redis disconnects, so a sub-second blip flaps DEGRADED↔ACTIVE and (with `on-redis-loss=close-all`) can mass-close sessions on a hiccup. Debounce the **node state machine** transition by a grace period; the broker's own `state()` stays immediately truthful (it flips in `RedisPubSubBroker`, unchanged).

**Files:**
- Modify: `netty-spring-websocket-cluster/.../cluster/ClusterProperties.java`
- Modify: `netty-spring-websocket-cluster/.../cluster/node/ClusterNodeManager.java`
- Modify: `netty-websocket-cluster-spring-boot-starter/.../configure/NettyWebSocketClusterConfigure.java`
- Modify: `netty-websocket-cluster-spring-boot-starter/.../META-INF/additional-spring-configuration-metadata.json`
- Test: `ClusterNodeManagerReliabilityTest.java` (add 3 tests)

- [ ] **Step 1: Write the failing tests**

In `ClusterNodeManagerReliabilityTest.java`, add a no-op heartbeat helper and three grace tests:
```java
    private static ClusterNodeHeartbeat noOpHeartbeat() {
        return new ClusterNodeHeartbeat() {
            @Override public void register(String nodeId, long timeoutMs) {}
            @Override public void renewHeartbeat(String nodeId, long timeoutMs) {}
            @Override public void deregister(String nodeId) {}
            @Override public List<String> findExpiredNodes(long timeoutMs) { return Collections.emptyList(); }
        };
    }

    /** Manager with a long heartbeat/recon interval so only the explicit transport events drive state. */
    private static ClusterNodeManager graceManager(long graceMs) {
        ClusterNodeManager mgr = new ClusterNodeManager(
                "grace-node", 60000, 600000, 60000, 60000, noOpHeartbeat(), new InMemorySessionRegistry());
        mgr.setRedisLossGracePeriodMs(graceMs);
        return mgr;
    }

    @Test
    void transportLostWithinGraceDoesNotDegrade() throws Exception {
        ClusterNodeManager mgr = graceManager(500);
        mgr.start();
        assertEquals(NodeState.ACTIVE, mgr.getState());
        mgr.onTransportLost();                  // starts the grace countdown, does NOT degrade
        Thread.sleep(150);
        assertEquals(NodeState.ACTIVE, mgr.getState(), "must not degrade during the grace window");
        mgr.onTransportRestored();              // recovery within grace cancels the countdown
        Thread.sleep(600);                      // past where the grace timer would have fired
        assertEquals(NodeState.ACTIVE, mgr.getState(), "recovered within grace — no flap, stays ACTIVE");
        mgr.shutdown();
    }

    @Test
    void transportLostBeyondGraceDegrades() throws Exception {
        ClusterNodeManager mgr = graceManager(400);
        mgr.start();
        mgr.onTransportLost();
        Thread.sleep(900);                      // exceed the grace window with no recovery
        assertEquals(NodeState.DEGRADED, mgr.getState(), "no recovery → degrade after the grace period");
        mgr.shutdown();
    }

    @Test
    void zeroGraceDegradesInstantly() {
        ClusterNodeManager mgr = graceManager(0);
        mgr.start();
        mgr.onTransportLost();
        assertEquals(NodeState.DEGRADED, mgr.getState(), "grace=0 preserves 1.8.0 instant-degrade");
        mgr.shutdown();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -pl netty-spring-websocket-cluster test -Dtest=ClusterNodeManagerReliabilityTest`
Expected: FAIL to COMPILE — `setRedisLossGracePeriodMs` does not exist yet. (`transportLostWithinGraceDoesNotDegrade` would also fail logically: current code degrades instantly.)

- [ ] **Step 3a: Add the config property**

In `ClusterProperties.java`, add after the `reconnectJitterMaxSeconds` field (after line 67):
```java
    /** Grace period in ms before a transport loss degrades the NODE state machine (debounce). A blip
     *  shorter than this won't flap the node or trigger {@code on-redis-loss=close-all}. The broker's
     *  own {@code state()} still flips immediately (truthful health + fast-fail of in-flight publishes).
     *  0 = instant degrade (exact 1.8.0 behavior). Default 5000. */
    private long redisLossGracePeriodMs = 5000;
```
and add the getter/setter near the other accessors (after the `reconnectJitterMaxSeconds` accessors, line 117):
```java
    public long getRedisLossGracePeriodMs() { return redisLossGracePeriodMs; }
    public void setRedisLossGracePeriodMs(long v) { this.redisLossGracePeriodMs = v; }
```

- [ ] **Step 3b: Implement the grace debounce in ClusterNodeManager**

In `ClusterNodeManager.java`, add fields after `reconnectJitterMaxMs` (after line 70):
```java
    /** Grace period (ms) before a transport loss actually degrades this node. 0 = instant. Default 5000. */
    private volatile long redisLossGracePeriodMs = 5000L;
    /** Pending grace-period degrade task (null = none). Mutated under {@link #graceLock}; volatile for the
     *  fast-path read in {@link #doHeartbeat()}. */
    private volatile ScheduledFuture<?> graceFuture;
    private final Object graceLock = new Object();
```

Add a setter next to `setReconnectJitterMaxMs` (after line 136):
```java
    /**
     * Sets the grace period (ms) before a transport loss degrades the node. 0 = instant degrade.
     * Bound from {@code server.netty.websocket.cluster.redis-loss-grace-period-ms}.
     */
    public void setRedisLossGracePeriodMs(long redisLossGracePeriodMs) {
        if (redisLossGracePeriodMs >= 0) {
            this.redisLossGracePeriodMs = redisLossGracePeriodMs;
        }
    }
```

Replace `onTransportLost()` (currently lines 230–236) with:
```java
    /**
     * Called by the broker (connection event) or {@link #doHeartbeat()} (renewal failure) when
     * transport connectivity is lost. Debounced: schedules a degrade after the grace period unless
     * the transport recovers first. With grace = 0, degrades immediately (1.8.0 behavior).
     */
    public void onTransportLost() {
        if (state.get() != NodeState.ACTIVE) {
            return; // already degraded/leaving — nothing to debounce
        }
        long grace = redisLossGracePeriodMs;
        if (grace <= 0) {
            degradeNow();
            return;
        }
        synchronized (graceLock) {
            if (state.get() != NodeState.ACTIVE) {
                return;
            }
            if (graceFuture != null && !graceFuture.isDone()) {
                return; // a grace timer is already counting down
            }
            log.warn("Cluster node {} transport lost — {}ms grace before degrading "
                    + "(local delivery continues meanwhile)", nodeId, grace);
            try {
                graceFuture = reconScheduler.schedule(this::onGraceExpired, grace, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException rej) {
                degradeNow(); // scheduler shutting down — degrade now as a fallback
            }
        }
    }

    /** Grace timer body: degrade only if still ACTIVE (i.e. transport never recovered). */
    private void onGraceExpired() {
        synchronized (graceLock) {
            graceFuture = null;
            if (state.get() == NodeState.ACTIVE) {
                degradeNow();
            }
        }
    }

    /** Performs the ACTIVE→DEGRADED transition (CAS so it fires the state change exactly once). */
    private void degradeNow() {
        if (state.compareAndSet(NodeState.ACTIVE, NodeState.DEGRADED)) {
            log.warn("Cluster node {} degraded — transport lost, local-only mode", nodeId);
            fireStateChange(NodeState.ACTIVE, NodeState.DEGRADED);
        }
    }
```

Replace `onTransportRestored()` (currently lines 242–259) with:
```java
    /**
     * Called when transport connectivity is restored. If a grace-period degrade is still pending
     * (node never left ACTIVE), cancels it — no flap. Otherwise runs the DEGRADED→RESYNC→ACTIVE recovery.
     */
    public void onTransportRestored() {
        synchronized (graceLock) {
            if (graceFuture != null && !graceFuture.isDone()) {
                graceFuture.cancel(false);
                graceFuture = null;
                log.info("Cluster node {} transport restored within grace — staying ACTIVE (no flap)", nodeId);
                if (state.get() == NodeState.ACTIVE) {
                    return; // never left ACTIVE — done
                }
                // else: raced with onGraceExpired() → fall through to RESYNC recovery
            }
        }
        if (state.compareAndSet(NodeState.DEGRADED, NodeState.RESYNC)) {
            log.info("Cluster node {} entering RESYNC — rebuilding cluster state", nodeId);
            fireStateChange(NodeState.DEGRADED, NodeState.RESYNC);
            try {
                reconScheduler.schedule(() -> {
                    try {
                        heartbeat.register(nodeId, heartbeatTimeoutMs);
                        transitionTo(NodeState.ACTIVE);
                        log.info("Cluster node {} completed RESYNC → ACTIVE", nodeId);
                    } catch (Exception e) {
                        log.error("Failed to resync node {}, staying DEGRADED", nodeId, e);
                        transitionTo(NodeState.DEGRADED);
                    }
                }, jitter(reconnectJitterMaxMs), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException rej) {
                log.debug("Resync task rejected (scheduler shutting down) for node {}", nodeId);
                transitionTo(NodeState.DEGRADED);
            }
        }
    }
```

In `doHeartbeat()`, update the ACTIVE-branch renewal (currently the `else { heartbeat.renewHeartbeat(nodeId, heartbeatTimeoutMs); }` at lines 279–281) so a successful renewal cancels a pending grace timer (heartbeat-detected recovery):
```java
            } else {
                heartbeat.renewHeartbeat(nodeId, heartbeatTimeoutMs);
                // A successful renew while a grace-period degrade is pending means the transport
                // recovered (no connection event fired) — cancel the pending degrade.
                if (graceFuture != null) {
                    onTransportRestored();
                }
            }
```

- [ ] **Step 3c: Wire the property in auto-config**

In `NettyWebSocketClusterConfigure.java`, in `clusterNodeManager(...)`, add after `manager.setReconnectJitterMaxMs(...)` (after line 206):
```java
        manager.setRedisLossGracePeriodMs(properties.getRedisLossGracePeriodMs());
```

- [ ] **Step 3d: Add config metadata**

In `additional-spring-configuration-metadata.json`, add this object to the `properties` array (after the `reconnect-jitter-max-seconds` entry, line 49):
```json
    {
      "name": "server.netty.websocket.cluster.redis-loss-grace-period-ms",
      "type": "java.lang.Long",
      "description": "Grace period (ms) before a transport loss degrades the node state machine (debounce). A blip shorter than this will not flap the node or trigger on-redis-loss=close-all. The broker's own state still flips immediately for truthful health. 0 = instant degrade (1.8.0 behavior).",
      "defaultValue": 5000
    },
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl netty-spring-websocket-cluster test -Dtest=ClusterNodeManagerReliabilityTest`
Expected: PASS — all grace tests plus the Task 2 thread-isolation test.

- [ ] **Step 5: Commit**

```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterProperties.java netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/node/ClusterNodeManager.java netty-websocket-cluster-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigure.java netty-websocket-cluster-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterNodeManagerReliabilityTest.java
git commit -m "feat(cluster): redis-loss grace period debounces node degradation

New redis-loss-grace-period-ms (default 5000, 0=instant) debounces the node
state-machine transition so a sub-grace Redis blip no longer flaps the node
or triggers on-redis-loss=close-all. Broker state() stays immediately truthful.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: (Item ④) Reconciliation leader-election (ClusterReaper)

**Why:** When node X dies, every surviving node's reconciliation independently runs `removeAllForNode(X)` + `deregister(X)` in the same interval → N-fold cleanup traffic. Add a claim-before-reap so only one node cleans up. Keep `ClusterNodeManager` transport-agnostic: the claim is a new `ClusterReaper` interface (default = always-reap, i.e. pre-1.9.0 behavior), with a Redis `SET NX` impl injected by auto-config.

**Files:**
- Create: `netty-spring-websocket-cluster/.../cluster/node/ClusterReaper.java`
- Create: `netty-spring-websocket-cluster/.../cluster/redis/RedisClusterReaper.java`
- Modify: `netty-spring-websocket-cluster/.../cluster/node/ClusterNodeManager.java`
- Modify: `netty-websocket-cluster-spring-boot-starter/.../configure/NettyWebSocketClusterConfigure.java`
- Test: `ClusterNodeManagerReliabilityTest.java` (2 unit tests) + `RedisIntegrationTest.java` (1)

- [ ] **Step 1: Write the failing unit tests (claim gating)**

In `ClusterNodeManagerReliabilityTest.java`, add this heartbeat helper and these two tests (the imports added in Task 2 — `java.util.List`, `Collections` — already cover them):
```java
    private static ClusterNodeHeartbeat expiredReturning(String deadNodeId) {
        return new ClusterNodeHeartbeat() {
            @Override public void register(String nodeId, long timeoutMs) {}
            @Override public void renewHeartbeat(String nodeId, long timeoutMs) {}
            @Override public void deregister(String nodeId) {}
            @Override public List<String> findExpiredNodes(long timeoutMs) { return List.of(deadNodeId); }
        };
    }

    @Test
    void reconciliationSkipsCleanupWhenReapClaimLost() throws Exception {
        InMemorySessionRegistry reg = new InMemorySessionRegistry();
        reg.register("/ws/x", "s-dead", "dead-1", Collections.emptyMap()).toCompletableFuture().join();
        ClusterNodeManager mgr = new ClusterNodeManager(
                "live-1", 600000, 10000, 200, 60000, expiredReturning("dead-1"), reg);
        mgr.setReaper((dead, me, win) -> false); // claim always lost
        mgr.start();
        Thread.sleep(600); // a few reconciliation cycles @200ms
        mgr.shutdown();
        assertEquals("dead-1", reg.lookupNode("/ws/x", "s-dead").toCompletableFuture().join(),
                "cleanup must be skipped when the reap claim is lost");
    }

    @Test
    void reconciliationCleansUpWhenReapClaimWon() throws Exception {
        InMemorySessionRegistry reg = new InMemorySessionRegistry();
        reg.register("/ws/x", "s-dead", "dead-2", Collections.emptyMap()).toCompletableFuture().join();
        ClusterNodeManager mgr = new ClusterNodeManager(
                "live-2", 600000, 10000, 200, 60000, expiredReturning("dead-2"), reg);
        mgr.setReaper((dead, me, win) -> true);
        mgr.start();
        Thread.sleep(600);
        mgr.shutdown();
        assertNull(reg.lookupNode("/ws/x", "s-dead").toCompletableFuture().join(),
                "cleanup must run when the reap claim is won");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -pl netty-spring-websocket-cluster test -Dtest=ClusterNodeManagerReliabilityTest#reconciliationSkipsCleanupWhenReapClaimLost+reconciliationCleansUpWhenReapClaimWon`
Expected: FAIL to COMPILE — `setReaper(...)` and the `ClusterReaper` lambda type don't exist yet.

- [ ] **Step 3a: Create the ClusterReaper interface**

Create `netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/node/ClusterReaper.java`:
```java
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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.node;

/**
 * Leader-election for reconciliation cleanup. When a node dies, every surviving node's
 * reconciliation independently detects it; to avoid N-fold cleanup, each node first asks the
 * reaper to claim the dead node, and only the claim winner performs the cleanup.
 *
 * <p>The default ({@link #alwaysReap()}) grants every claim — correct (cleanup is idempotent) but
 * not deduplicated, matching pre-1.9.0 behavior. The Redis implementation
 * ({@code RedisClusterReaper}) uses {@code SET NX PX} for a true single winner per window.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@FunctionalInterface
public interface ClusterReaper {

    /**
     * Attempts to claim the exclusive right to reap a dead node.
     *
     * @param deadNodeId    the dead node to clean up
     * @param reaperNodeId  this node's id (the claim owner)
     * @param claimWindowMs how long the claim is held (ms) before it can be re-claimed
     * @return true if this node won the claim and should perform cleanup
     */
    boolean tryClaim(String deadNodeId, String reaperNodeId, long claimWindowMs);

    /** A reaper that grants every claim (no dedup). Default when no distributed reaper is wired. */
    static ClusterReaper alwaysReap() {
        return (deadNodeId, reaperNodeId, claimWindowMs) -> true;
    }
}
```

- [ ] **Step 3b: Create the Redis SET NX impl**

Create `netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/redis/RedisClusterReaper.java`:
```java
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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.redis;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterReaper;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed {@link ClusterReaper}: claims the right to reap a dead node with
 * {@code SET netty:cluster:reaping:{deadNodeId} {reaperNodeId} NX PX {windowMs}}. Only the first
 * caller within the window gets {@code OK}; the rest are locked out, so a dead node is cleaned up once.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class RedisClusterReaper implements ClusterReaper {

    private static final String REAP_PREFIX = "netty:cluster:reaping:";

    private final StatefulRedisConnection<String, String> connection;

    public RedisClusterReaper(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    @Override
    public boolean tryClaim(String deadNodeId, String reaperNodeId, long claimWindowMs) {
        try {
            String res = connection.sync().set(REAP_PREFIX + deadNodeId, reaperNodeId,
                    SetArgs.Builder.nx().px(Math.max(1, claimWindowMs)));
            boolean won = "OK".equals(res);
            if (won) {
                log.debug("Node {} claimed reaping of dead node {}", reaperNodeId, deadNodeId);
            }
            return won;
        } catch (Exception e) {
            // On a Redis error prefer correctness over dedup: reap anyway (cleanup is idempotent).
            log.debug("Reap-claim for dead node {} failed; proceeding with cleanup", deadNodeId, e);
            return true;
        }
    }
}
```

- [ ] **Step 3c: Gate cleanup in ClusterNodeManager**

In `ClusterNodeManager.java`, add an import (with the other `...cluster.node` siblings — they share the package, so no import is needed; `ClusterReaper` is in the same package). Add a field after `deadNodeCallback` (after line 66):
```java
    /** Reconciliation leader-election: only the claim winner cleans up a dead node. Default = always-reap. */
    private volatile ClusterReaper reaper = ClusterReaper.alwaysReap();
```

Add a setter after `setDeadNodeCallback(...)` (after line 126):
```java
    /**
     * Sets the reconciliation reaper (leader-election for dead-node cleanup). Default is
     * {@link ClusterReaper#alwaysReap()} (every node reaps; correct but not deduplicated).
     */
    public void setReaper(ClusterReaper reaper) {
        if (reaper != null) {
            this.reaper = reaper;
        }
    }
```

In `doReconciliation()`, gate the per-dead-node cleanup. Replace the body of the `if (!deadNodeId.equals(nodeId)) { ... }` block (currently lines 301–312) with:
```java
                if (!deadNodeId.equals(nodeId)) {
                    // Leader-election: only the claim winner cleans up, so a dead node detected by
                    // every surviving node in the same sweep is reaped once, not N times.
                    if (!reaper.tryClaim(deadNodeId, nodeId, reconciliationIntervalMs)) {
                        log.debug("Skipping cleanup of dead node {} — another node claimed it", deadNodeId);
                        continue;
                    }
                    log.warn("Reconciliation detected dead node {} — cleaning up sessions + cache", deadNodeId);
                    sessionRegistry.removeAllForNode(deadNodeId);
                    heartbeat.deregister(deadNodeId);
                    // Notify sender to invalidate cached routes to the dead node (I-3)
                    java.util.function.Consumer<String> cb = deadNodeCallback;
                    if (cb != null) {
                        try { cb.accept(deadNodeId); } catch (Exception ex) {
                            log.debug("Dead node callback failed for {}", deadNodeId, ex);
                        }
                    }
                }
```

- [ ] **Step 3d: Wire the reaper bean in auto-config**

In `NettyWebSocketClusterConfigure.java`, add the import:
```java
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterReaper;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterReaper;
```
Add a new bean after `clusterNodeHeartbeat(...)` (after line 189):
```java
    @Bean
    @ConditionalOnMissingBean(ClusterReaper.class)
    public ClusterReaper clusterReaper(
            @org.springframework.beans.factory.annotation.Qualifier("nettyClusterRedisConnection")
            StatefulRedisConnection<String, String> connection) {
        return new RedisClusterReaper(connection);
    }
```
Change the `clusterNodeManager(...)` bean to inject and set the reaper. Replace its signature + body header (lines 193–207) so it takes `ClusterReaper clusterReaper` and calls `manager.setReaper(clusterReaper)` before `manager.start()`:
```java
    @Bean(destroyMethod = "shutdown")
    public ClusterNodeManager clusterNodeManager(
            ClusterProperties properties,
            ClusterNodeHeartbeat heartbeat,
            SessionRegistry sessionRegistry,
            ClusterReaper clusterReaper) {
        ClusterNodeManager manager = new ClusterNodeManager(
                properties.getNodeId(),
                properties.getHeartbeatIntervalSeconds() * 1000,
                properties.getHeartbeatTimeoutSeconds() * 1000,
                properties.getReconciliationIntervalSeconds() * 1000,
                properties.getDrainTimeoutSeconds() * 1000,
                heartbeat,
                sessionRegistry);
        manager.setReconnectJitterMaxMs(properties.getReconnectJitterMaxSeconds() * 1000);
        manager.setRedisLossGracePeriodMs(properties.getRedisLossGracePeriodMs());
        manager.setReaper(clusterReaper);
        manager.start();
        log.info("Cluster node manager started (nodeId={})", manager.getNodeId());
        return manager;
    }
```

- [ ] **Step 4: Run the unit tests to verify they pass**

Run: `mvn -q -pl netty-spring-websocket-cluster test -Dtest=ClusterNodeManagerReliabilityTest`
Expected: PASS — both reaper gating tests plus all earlier ones.

- [ ] **Step 5: Add the real-Redis integration test**

In `RedisIntegrationTest.java`, add imports near the top (with the other redis impl imports, line 9):
```java
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterReaper;
```
and add this test after the Task 3 test:
```java
    @Test
    @Order(12)
    void reapClaimElectsSingleWinner() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisClusterReaper r1 = new RedisClusterReaper(connection);
        RedisClusterReaper r2 = new RedisClusterReaper(connection);

        boolean w1 = r1.tryClaim("dead-X", "node-1", 5000);
        boolean w2 = r2.tryClaim("dead-X", "node-2", 5000);

        assertTrue(w1, "first claimant wins");
        assertFalse(w2, "second claimant is locked out within the window");
        assertTrue(w1 ^ w2, "exactly one winner");

        connection.sync().del("netty:cluster:reaping:dead-X");
    }
```

Run: `mvn -q -pl netty-spring-websocket-cluster test -Dtest=RedisIntegrationTest#reapClaimElectsSingleWinner`
Expected: PASS (or SKIPPED without Redis).

- [ ] **Step 6: Commit**

```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/node/ClusterReaper.java netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/redis/RedisClusterReaper.java netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/node/ClusterNodeManager.java netty-websocket-cluster-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigure.java netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterNodeManagerReliabilityTest.java netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/RedisIntegrationTest.java
git commit -m "feat(cluster): reconciliation leader-election to dedup dead-node cleanup

New ClusterReaper (default always-reap; RedisClusterReaper does SET NX PX)
so a dead node detected by every surviving node is reaped once, not N times.
ClusterNodeManager stays transport-agnostic — the reaper is injected.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: (Item ③) Atomic Lua deregister

**Why:** `deregister` does `HGET nodeId` → (gap) → `DEL` + `SREM` non-atomically; a concurrent re-register in the gap could let the trailing `DEL` wipe a newer registration. Defense-in-depth: make it one atomic Lua call. The node-set key is derived inside the script from the stored nodeId (safe on standalone/sentinel — the supported topologies).

**Files:**
- Modify: `netty-spring-websocket-cluster/.../cluster/redis/RedisSessionRegistry.java`
- Test: `RedisIntegrationTest.java` (1)

- [ ] **Step 1: Write the failing/guarding integration test**

In `RedisIntegrationTest.java`, add after the Task 5 test:
```java
    @Test
    @Order(13)
    void deregisterIsAtomicAndCleansNodeSet() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisSessionRegistry registry = new RedisSessionRegistry(connection);
        registry.register("/ws/lua", "s1", "node-L", Map.of()).toCompletableFuture().join();
        assertTrue(connection.sync().sismember("netty:node:node-L:sessions", "/ws/lua|s1"),
                "precondition: node-set has the member");

        registry.deregister("/ws/lua", "s1").toCompletableFuture().join();

        assertNull(registry.lookupNode("/ws/lua", "s1").toCompletableFuture().join(),
                "session hash deleted");
        assertFalse(connection.sync().sismember("netty:node:node-L:sessions", "/ws/lua|s1"),
                "Lua deregister must SREM the node-set member atomically (no orphan)");
    }
```

- [ ] **Step 2: Run the test (passes on old code too — it guards behavior)**

Run: `mvn -q -pl netty-spring-websocket-cluster test -Dtest=RedisIntegrationTest#deregisterIsAtomicAndCleansNodeSet`
Expected: PASS or SKIPPED. (The observable contract — both keys cleaned — holds before and after; the Lua change makes it atomic. This test guards the refactor.)

- [ ] **Step 3: Implement the Lua deregister**

In `RedisSessionRegistry.java`, add the import (with the lettuce imports, after line 20):
```java
import io.lettuce.core.ScriptOutputType;
```
Add the script constant after the field declarations (after line 50, `private final StatefulRedisConnection...`):
```java
    /**
     * Atomic deregister: HGET the owning nodeId, then DEL the session hash and SREM the node-set
     * member — in one script so a concurrent re-register of the same uri|sessionId cannot interleave.
     * KEYS[1] = sessionKey; ARGV[1] = "uri|sessionId" member. The node-set key is derived from the
     * stored nodeId (safe on standalone/sentinel; not Redis-Cluster cross-slot safe — that client is
     * a roadmap item). Returns the removed nodeId (or nil).
     */
    private static final String DEREGISTER_LUA =
            "local nodeId = redis.call('HGET', KEYS[1], 'nodeId') "
          + "if nodeId then "
          + "  redis.call('DEL', KEYS[1]) "
          + "  redis.call('SREM', 'netty:node:' .. nodeId .. ':sessions', ARGV[1]) "
          + "end "
          + "return nodeId";
```
Replace the whole `deregister` method (currently lines 74–91) with:
```java
    @Override
    public CompletionStage<Void> deregister(String uri, String sessionId) {
        String sessionKey = sessionKey(uri, sessionId);
        String member = uri + "|" + sessionId;
        // Single atomic Lua call replaces the former non-atomic HGET → DEL + SREM. Plain EVAL: Redis
        // caches the compiled script by SHA, and the body is tiny, so resending it is negligible.
        return connection.async().<String>eval(DEREGISTER_LUA, ScriptOutputType.VALUE,
                        new String[]{ sessionKey }, member)
                .thenAccept(removedNodeId ->
                        log.debug("Deregistered session {} for URI {} (was on node {})",
                                sessionId, uri, removedNodeId))
                .toCompletableFuture();
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl netty-spring-websocket-cluster test -Dtest=RedisIntegrationTest#deregisterIsAtomicAndCleansNodeSet`
Then the registry regression: `-Dtest=RedisIntegrationTest#sessionRegistryRegisterAndLookup`
Expected: PASS (or SKIPPED without Redis).

- [ ] **Step 5: Commit**

```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/redis/RedisSessionRegistry.java netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/RedisIntegrationTest.java
git commit -m "fix(cluster): make session deregister atomic via Lua

Collapse HGET -> DEL + SREM into one EVAL so a concurrent re-register of the
same uri|sessionId can't interleave and orphan a node-set entry. Signature
unchanged; standalone/sentinel only (Redis Cluster client is a roadmap item).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: (Item ⑤) Registry write coalescing

**Why:** A reconnect storm fires a burst of register/deregister writes from the session hook. Throttle them per node without ever dropping a register: token-bucket **pass-through** under the rate (zero added latency, identical to 1.8.0 for normal load); over the rate, ops queue into a per-session coalescing map (last op per `uri|sessionId` wins) and drain as tokens refill.

**Files:**
- Create: `netty-spring-websocket-cluster/.../cluster/CoalescingRegistryWriter.java`
- Modify: `netty-spring-websocket-cluster/.../cluster/ClusterProperties.java`
- Modify: `netty-spring-websocket-cluster/.../cluster/ClusterSessionHookImpl.java`
- Modify: `netty-websocket-cluster-spring-boot-starter/.../configure/NettyWebSocketClusterConfigure.java`
- Modify: `netty-websocket-cluster-spring-boot-starter/.../META-INF/additional-spring-configuration-metadata.json`
- Test: `netty-spring-websocket-cluster/.../cluster/ClusterRegistryWriterTest.java` (create)

- [ ] **Step 1: Write the failing tests**

Create `ClusterRegistryWriterTest.java`:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for CoalescingRegistryWriter (item ⑤): pass-through under rate, throttle+coalesce over rate, never drop. */
class ClusterRegistryWriterTest {

    /** Counts register/deregister; no-ops the rest. */
    static class RecordingRegistry implements SessionRegistry {
        final AtomicInteger registerCount = new AtomicInteger();
        final AtomicInteger deregisterCount = new AtomicInteger();
        @Override public CompletionStage<Void> register(String uri, String sessionId, String nodeId, Map<String, String> metadata) {
            registerCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> deregister(String uri, String sessionId) {
            deregisterCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<String> lookupNode(String uri, String sessionId) { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Set<String>> clusterSessionIds(String uri) { return CompletableFuture.completedFuture(Collections.emptySet()); }
        @Override public CompletionStage<Void> removeAllForNode(String nodeId) { return CompletableFuture.completedFuture(null); }
        @Override public void shutdown() {}
    }

    @Test
    void passesThroughWhenUnderRate() {
        RecordingRegistry reg = new RecordingRegistry();
        CoalescingRegistryWriter w = new CoalescingRegistryWriter(reg, 1000, 50, "test");
        // no start() → no background flusher; under-rate writes pass straight through
        for (int i = 0; i < 5; i++) w.register("/ws/x", "s" + i, "n", Collections.emptyMap());
        assertEquals(5, reg.registerCount.get(), "under-rate writes pass straight through");
        assertEquals(0, w.pendingCount());
    }

    @Test
    void throttlesOverRateButNeverDropsRegister() {
        RecordingRegistry reg = new RecordingRegistry();
        CoalescingRegistryWriter w = new CoalescingRegistryWriter(reg, 1, 50, "test"); // ~1 immediate token
        int n = 50;
        for (int i = 0; i < n; i++) w.register("/ws/x", "s" + i, "n", Collections.emptyMap()); // distinct
        assertTrue(reg.registerCount.get() < n, "over-rate writes are throttled, not all immediate");
        assertTrue(w.pendingCount() > 0, "excess writes are queued, not dropped");
        w.shutdown(); // final drain ignores the rate — everything lands
        assertEquals(n, reg.registerCount.get(), "every distinct register eventually lands; none dropped");
        assertEquals(0, w.pendingCount());
    }

    @Test
    void coalescesRepeatedOpsOnSameSession() {
        RecordingRegistry reg = new RecordingRegistry();
        CoalescingRegistryWriter w = new CoalescingRegistryWriter(reg, 1, 50, "test");
        w.register("/ws/x", "warmup", "n", Collections.emptyMap()); // consumes the single initial token
        for (int i = 0; i < 20; i++) w.register("/ws/x", "s1", "n", Collections.emptyMap()); // throttled, same key
        w.deregister("/ws/x", "s1");
        assertEquals(1, w.pendingCount(), "20 reg + 1 dereg on one session coalesce to ONE pending op");
        w.shutdown();
        assertEquals(1, reg.registerCount.get(), "only the warmup register hit the registry immediately");
        assertEquals(1, reg.deregisterCount.get(), "the coalesced op for s1 is the final deregister");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -pl netty-spring-websocket-cluster test -Dtest=ClusterRegistryWriterTest`
Expected: FAIL to COMPILE — `CoalescingRegistryWriter` doesn't exist.

- [ ] **Step 3a: Create CoalescingRegistryWriter**

Create `netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/CoalescingRegistryWriter.java`:
```java
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

package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Rate-limited, coalescing writer in front of {@link SessionRegistry} register/deregister, to absorb
 * reconnect storms without dropping any registration.
 *
 * <p><b>Pass-through under rate:</b> while token-bucket tokens are available (normal load), a write
 * goes straight to the registry with no added latency — behaviorally identical to pre-1.9.0. Only when
 * the per-node write rate is exceeded do ops queue into a per-session coalescing map (latest op per
 * {@code uri|sessionId} wins) and drain on a flusher thread as tokens refill. A register is NEVER
 * dropped: the map is bounded by the number of distinct pending sessions, and {@link #shutdown()}
 * drains everything ignoring the rate.
 *
 * <p>Rate {@code <= 0} disables throttling entirely (pure pass-through, no flusher thread).
 *
 * <p><b>Tradeoff:</b> a session that connects and disconnects faster than the flush interval while
 * throttled may never be written (its register+deregister coalesce to a no-op DEL). That sub-flush-
 * interval window is acceptable given cross-node delivery is already at-most-once.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class CoalescingRegistryWriter {

    private enum OpType { REGISTER, DEREGISTER }

    private static final class Op {
        final OpType type;
        final String uri;
        final String sessionId;
        final String nodeId;
        final Map<String, String> metadata;
        Op(OpType type, String uri, String sessionId, String nodeId, Map<String, String> metadata) {
            this.type = type; this.uri = uri; this.sessionId = sessionId;
            this.nodeId = nodeId; this.metadata = metadata;
        }
    }

    private static final int BACKLOG_WARN_THRESHOLD = 10_000;

    private final SessionRegistry delegate;
    private final double maxTokens;
    private final double refillPerMs;
    private final long flushIntervalMs;
    private final String nodeLabel;

    private final ConcurrentHashMap<String, Op> pending = new ConcurrentHashMap<>();
    private final Object bucketLock = new Object();
    private double tokens;
    private long lastRefillNanos = System.nanoTime();

    private ScheduledExecutorService flusher;

    /**
     * @param delegate       the underlying registry
     * @param ratePerSecond  max sustained register+deregister ops/sec; {@code <= 0} disables throttling
     * @param flushIntervalMs how often the flusher drains the coalescing map (ms)
     * @param nodeLabel      node id (used only for the flusher thread name)
     */
    public CoalescingRegistryWriter(SessionRegistry delegate, long ratePerSecond,
                                    long flushIntervalMs, String nodeLabel) {
        this.delegate = delegate;
        this.maxTokens = ratePerSecond <= 0 ? 0 : Math.max(1, ratePerSecond);
        this.refillPerMs = ratePerSecond <= 0 ? 0 : ratePerSecond / 1000.0;
        this.flushIntervalMs = Math.max(1, flushIntervalMs);
        this.tokens = this.maxTokens;
        this.nodeLabel = nodeLabel == null ? "" : nodeLabel.substring(0, Math.min(8, nodeLabel.length()));
    }

    /** Starts the background flusher (no-op when throttling is disabled). */
    public void start() {
        if (maxTokens <= 0) {
            return; // pure pass-through — no flusher needed
        }
        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-regwriter-" + nodeLabel);
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleAtFixedRate(this::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    public void register(String uri, String sessionId, String nodeId, Map<String, String> metadata) {
        if (maxTokens <= 0 || tryAcquire()) {
            doRegister(uri, sessionId, nodeId, metadata);
        } else {
            enqueue(new Op(OpType.REGISTER, uri, sessionId, nodeId, metadata));
        }
    }

    public void deregister(String uri, String sessionId) {
        if (maxTokens <= 0 || tryAcquire()) {
            doDeregister(uri, sessionId);
        } else {
            enqueue(new Op(OpType.DEREGISTER, uri, sessionId, null, null));
        }
    }

    /** Drains the coalescing map up to the tokens available this tick. Exposed for tests/manual drain. */
    public void flush() {
        try {
            Iterator<Map.Entry<String, Op>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                if (!tryAcquire()) {
                    break; // out of tokens — the rest drain next tick
                }
                Op op = it.next().getValue();
                it.remove();
                apply(op);
            }
        } catch (Exception e) {
            log.warn("Cluster registry flush failed", e);
        }
    }

    /** Number of coalesced ops currently queued (for tests / visibility). */
    public int pendingCount() {
        return pending.size();
    }

    /** Stops the flusher (if any) and drains everything remaining, ignoring the rate, so nothing is lost. */
    public void shutdown() {
        if (flusher != null) {
            flusher.shutdown();
        }
        Iterator<Map.Entry<String, Op>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Op op = it.next().getValue();
            it.remove();
            apply(op);
        }
    }

    // ---- internals ----

    private void enqueue(Op op) {
        pending.put(op.uri + "|" + op.sessionId, op); // coalesce: latest op per session wins
        if (pending.size() == BACKLOG_WARN_THRESHOLD) {
            log.warn("Cluster registry write backlog reached {} (reconnect storm?) — writes are being "
                    + "rate-limited to protect Redis; no registration is dropped", BACKLOG_WARN_THRESHOLD);
        }
    }

    private void apply(Op op) {
        if (op.type == OpType.REGISTER) {
            doRegister(op.uri, op.sessionId, op.nodeId, op.metadata);
        } else {
            doDeregister(op.uri, op.sessionId);
        }
    }

    private boolean tryAcquire() {
        synchronized (bucketLock) {
            long now = System.nanoTime();
            double elapsedMs = (now - lastRefillNanos) / 1_000_000.0;
            tokens = Math.min(maxTokens, tokens + elapsedMs * refillPerMs);
            lastRefillNanos = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private void doRegister(String uri, String sessionId, String nodeId, Map<String, String> metadata) {
        delegate.register(uri, sessionId, nodeId, metadata).exceptionally(ex -> {
            log.warn("Failed to register session {} in cluster registry", sessionId, ex);
            return null;
        });
    }

    private void doDeregister(String uri, String sessionId) {
        delegate.deregister(uri, sessionId).exceptionally(ex -> {
            log.warn("Failed to deregister session {} from cluster registry", sessionId, ex);
            return null;
        });
    }
}
```

- [ ] **Step 3b: Add the config property**

In `ClusterProperties.java`, add after the `messageMaxSizeBytes` field (after line 91):
```java
    /** Max sustained session register+deregister writes per second per node. Absorbs reconnect storms
     *  by coalescing+throttling registry writes (never dropping a register). Under this rate, writes
     *  pass straight through with no added latency. 0 = unlimited (pure pass-through). Default 1000. */
    private long sessionRegistryWriteRate = 1000;
```
and the accessors after the `messageMaxSizeBytes` accessors (after line 132):
```java
    public long getSessionRegistryWriteRate() { return sessionRegistryWriteRate; }
    public void setSessionRegistryWriteRate(long v) { this.sessionRegistryWriteRate = v; }
```

- [ ] **Step 3c: Route the hook through the writer**

In `ClusterSessionHookImpl.java`, replace the `registry` field + constructor (lines 41–51) with:
```java
    private final CoalescingRegistryWriter registryWriter;
    private final ClusterNodeManager nodeManager;
    private final ClusterMessageSender clusterSender;

    public ClusterSessionHookImpl(CoalescingRegistryWriter registryWriter,
                                  ClusterNodeManager nodeManager,
                                  ClusterMessageSender clusterSender) {
        this.registryWriter = registryWriter;
        this.nodeManager = nodeManager;
        this.clusterSender = clusterSender;
    }
```
Remove the now-unused import `import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;` (line 20).
In `onSessionRegistered`, replace the `registry.register(...).exceptionally(...)` block (lines 59–63) with:
```java
        // Register in distributed session registry (rate-limited/coalesced; the writer handles errors)
        registryWriter.register(uri, sessionId, nodeId, Collections.emptyMap());
```
In `onSessionRemoved`, replace the `registry.deregister(...).exceptionally(...)` block (lines 76–80) with:
```java
        // Deregister from distributed session registry (rate-limited/coalesced)
        registryWriter.deregister(uri, sessionId);
```

- [ ] **Step 3d: Wire the writer bean + hook in auto-config**

In `NettyWebSocketClusterConfigure.java`, add the import:
```java
import com.github.berrywang1996.netty.spring.web.websocket.cluster.CoalescingRegistryWriter;
```
Add a writer bean before `clusterSessionHook(...)` (before line 234):
```java
    @Bean(destroyMethod = "shutdown")
    public CoalescingRegistryWriter clusterRegistryWriter(
            SessionRegistry sessionRegistry, ClusterProperties properties, ClusterNodeManager nodeManager) {
        CoalescingRegistryWriter writer = new CoalescingRegistryWriter(
                sessionRegistry, properties.getSessionRegistryWriteRate(), 50L, nodeManager.getNodeId());
        writer.start();
        log.info("Cluster registry writer started (rate={} ops/s/node)", properties.getSessionRegistryWriteRate());
        return writer;
    }
```
Replace the `clusterSessionHook(...)` bean (lines 234–241) so it injects the writer instead of the raw registry:
```java
    @Bean
    public ClusterSessionHook clusterSessionHook(
            CoalescingRegistryWriter clusterRegistryWriter,
            ClusterNodeManager nodeManager,
            ClusterMessageSender clusterSender) {
        log.info("Registering cluster session hook for distributed session lifecycle");
        return new ClusterSessionHookImpl(clusterRegistryWriter, nodeManager, clusterSender);
    }
```

- [ ] **Step 3e: Add config metadata**

In `additional-spring-configuration-metadata.json`, add to the `properties` array (after the `message-max-size-bytes` entry, before the closing `]` of properties — i.e. after line 78, adding a comma to the prior entry):
```json
    {
      "name": "server.netty.websocket.cluster.session-registry-write-rate",
      "type": "java.lang.Long",
      "description": "Max sustained session register+deregister writes per second per node. Absorbs reconnect storms by coalescing and throttling registry writes (never dropping a register). Under this rate, writes pass straight through with no added latency. 0 means unlimited (pure pass-through).",
      "defaultValue": 1000
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl netty-spring-websocket-cluster test -Dtest=ClusterRegistryWriterTest`
Expected: PASS — pass-through, throttle-never-drop, and coalescing all green.

- [ ] **Step 5: Commit**

```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/CoalescingRegistryWriter.java netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterProperties.java netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterSessionHookImpl.java netty-websocket-cluster-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigure.java netty-websocket-cluster-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterRegistryWriterTest.java
git commit -m "feat(cluster): coalescing+throttled registry writes for reconnect storms

New session-registry-write-rate (default 1000 ops/s/node, 0=unlimited). Token-
bucket pass-through under rate (no latency change); over rate, writes coalesce
per session and drain as tokens refill. A register is never dropped.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: Update the auto-config context test for the new beans

**Files:**
- Modify: `netty-websocket-cluster-spring-boot-starter/.../NettyWebSocketClusterConfigureTest.java`

- [ ] **Step 1: Assert the two new beans in the enabled path**

In `enabled_primaryMessageSenderIsClusterSender_andHealthIndicatorRegistered()`, add inside the `.run(context -> { ... })` block, after the `hasSingleBean(ClusterHealthIndicator.class)` assertion (after line 97):
```java
                    // 1.9.0 reliability beans are wired.
                    assertThat(context).hasSingleBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterReaper.class);
                    assertThat(context).hasSingleBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.CoalescingRegistryWriter.class);
```

- [ ] **Step 2: Run the context tests**

Run: `mvn -q -pl netty-websocket-cluster-spring-boot-starter test -Dtest=NettyWebSocketClusterConfigureTest`
Expected: the two disabled-path tests PASS; the enabled-path test PASSES with Redis on `localhost:16379`, otherwise SKIPPED.

- [ ] **Step 3: Commit**

```
git add netty-websocket-cluster-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigureTest.java
git commit -m "test(cluster): assert ClusterReaper + CoalescingRegistryWriter beans wire

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 9: Documentation

Update docs to move the 5 items from deferred → shipped and document the 2 new config keys. No code.

**Files:**
- Modify: `docs/cluster-design.md` — scope table: move the 5 items to ✅ (1.9.0); add the 2 config keys to the config block.
- Modify: `docs/api-guide.md` — §9 WebSocket Cluster config table: add `redis-loss-grace-period-ms` and `session-registry-write-rate` rows; note the grace-period default behavior change.
- Modify: `docs/development-plan.md` — mark 1.9.0 reliability hardening done; set next focus (NATS / reliable delivery per roadmap).
- Modify: `docs/release-checklist.md` — add a `1.9.0` block under "最新版本口径".
- Create: `docs/release-notes-1.9.0.md` — version positioning, the 5 items, new tests, 2 new config keys, the one intentional default change (grace period), backward-compat statement.
- Modify: `README.md` — cluster config snippet/known-limitations: drop the 5 now-shipped items from the deferred list; mention grace period + write-rate (EN + 中文 sections).
- Modify: `.claude/CLAUDE.md` — current-version line → 1.9.0; note the 5 hardening items shipped; trim them from the 1.9.x backlog.

- [ ] **Step 1: Write `docs/release-notes-1.9.0.md`**

Follow the structure of `docs/release-notes-1.8.0.md` (版本定位 / 核心能力 / 配置项 / 测试覆盖 / 升级指南 / 向后兼容). Content to include:
  - Positioning: 1.9.0 = 集群可靠性硬化 (reliability hardening); single-node still identical to 1.7.x; cluster envelope unchanged (≤~10 nodes + dedicated Redis).
  - The 5 items (grace-period debounce, scheduler thread isolation + batched EXISTS, atomic Lua deregister, reconciliation leader-election, registry write coalescing).
  - 2 new config keys (`redis-loss-grace-period-ms`=5000, `session-registry-write-rate`=1000) with effects.
  - **Intentional default change:** with the 5 s grace default, a real Redis outage now takes up to 5 s before the node pauses cross-node / applies `close-all`; set `redis-loss-grace-period-ms=0` to restore exact 1.8.0 timing. Everything else is backward compatible.
  - New tests: `ClusterNodeManagerReliabilityTest` (6), `ClusterRegistryWriterTest` (3), `RedisIntegrationTest` (+3 integration, skipped without Redis).
  - Upgrade: bump version to 1.9.0; no code changes required.

- [ ] **Step 2: Update the other docs**

Apply the edits listed above to `docs/cluster-design.md`, `docs/api-guide.md`, `docs/development-plan.md`, `docs/release-checklist.md`, `README.md`, `.claude/CLAUDE.md`. For each, grep the file first for the 5 deferred-item phrases (e.g. "宽限期", "线程隔离", "deregister 原子性", "选主", "限速") and the "1.9.x" deferral notes, and move/update them.

- [ ] **Step 3: Consistency check**

Run (PowerShell) to catch stale references that should no longer call these items "deferred/1.9.x":
```
Select-String -Path docs\*.md, README.md, .claude\CLAUDE.md -Pattern 'redis-loss-grace|session-registry-write|线程隔离|deregister 原子|选主'
```
Expected: every hit reads as shipped in 1.9.0 (not "deferred"/"推迟").

- [ ] **Step 4: Commit**

```
git add docs/ README.md .claude/CLAUDE.md
git commit -m "docs: 1.9.0 reliability hardening — release notes, config, roadmap

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 10: Full verification + release bump

**Files:**
- Modify: all 11 poms (`1.9.0-SNAPSHOT` → `1.9.0`).

- [ ] **Step 1: Full reactor test (11 modules)**

Start Redis so the integration tests actually run (recommended before release):
```
docker run -d --name redis-7-standalone -p 16379:6379 redis:7.4 redis-server --notify-keyspace-events Ex
```
Run: `mvn test`
Expected: `BUILD SUCCESS`, all 11 modules green. New tests: `ClusterNodeManagerReliabilityTest` (6) + `ClusterRegistryWriterTest` (3) always run; the 3 new `RedisIntegrationTest` cases run with Redis up (skipped otherwise). No regressions in the existing cluster tests.

- [ ] **Step 2: Flip to the release version**

Run:
```
mvn versions:set -DnewVersion=1.9.0 -DgenerateBackupPoms=false
```
Verify: `Select-String -Path (Get-ChildItem -Recurse -Filter pom.xml).FullName -Pattern '1\.9\.0-SNAPSHOT'` → no output. Update `docs/release-notes-1.9.0.md` 发布日期 / version refs to `1.9.0` if any still say SNAPSHOT.

- [ ] **Step 3: Final test on the release version**

Run: `mvn -q test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit + tag**

```
git add -A
git commit -m "release: 1.9.0 — cluster reliability hardening

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git tag -a v1.9.0 -m "v1.9.0 — cluster reliability hardening (grace period, scheduler isolation, atomic deregister, reaper election, write coalescing)"
```

- [ ] **Step 5: Deploy — USER-DRIVEN, do not automate**

Maven Central deploy and GitHub Release are performed by the maintainer (credentials/identity reasons documented in `.claude/CLAUDE.md`): GPG signing, `~/.m2/settings.xml` Central token, and the `gh` CLI being logged in as a different identity than the repo owner. When the user asks to deploy, the command is:
```
gpgconf --kill all; gpgconf --launch keyboxd
mvn deploy -pl '!demo-netty-web-spring-boot-starter' -P release -DskipTests "-Dgpg.executable=C:\Program Files\GnuPG\bin\gpg.exe"
```
Then the user manually reviews/publishes on central.sonatype.com (autoPublish=false) and creates the GitHub Release as BerryWang1996. **Do not run deploy or touch `gh` auth in this plan.**

---

## Notes for the implementer

- **TDD discipline:** every code task writes the test first, watches it fail, implements, watches it pass. Integration tests (real Redis) are *skipped* without Redis — that is expected and not a failure; verify them with Redis up before the release (Task 10 Step 1).
- **Line numbers** reference the files as they were when this plan was written. If earlier tasks shifted lines, match on the quoted code, not the number.
- **No SPI breakage:** `ClusterBroker` / `SessionRegistry` / `MessagePayloadCodec` / `EnvelopeCodec` / `ClusterNodeHeartbeat` signatures are untouched. `ClusterReaper` is new. `ClusterSessionHookImpl`'s constructor changes, but it is framework-internal (constructed only by the auto-config), not part of the public SPI.
- **The one behavior change:** the grace-period default (5 s) delays real-outage degradation; `redis-loss-grace-period-ms=0` restores 1.8.0 timing. Call this out in the release notes (Task 9 Step 1).
```
