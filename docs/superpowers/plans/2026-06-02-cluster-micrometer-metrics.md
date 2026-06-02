# Cluster Micrometer Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bind the cluster's existing runtime counters + node/broker state + HMAC reject count to `netty.cluster.*` Micrometer meters on every `MeterRegistry`, completing the 1.7.0 observability story — optional, gated, additive, zero hot-path cost.

**Architecture:** A new `NettyClusterMeterBinder implements MeterBinder` (in the cluster starter's `…boot.configure` package, beside `ClusterHealthIndicator`) of `FunctionCounter`/`Gauge` read-throughs over `ClusterRuntimeStats` getters + `ClusterNodeManager.getState()` + `ClusterBroker.state()` + the HMAC reject count — no change to any increment site. Wired by `NettyClusterMetricsConfigure` (mirrors `NettyClusterActuatorConfigure`), gated `@ConditionalOnClass(MeterRegistry)` + `@ConditionalOnBean({MeterRegistry, ClusterMessageSender})` + `cluster.enable=true`. micrometer-core added as an optional dep to the cluster starter.

**Tech Stack:** Java 17, Micrometer (`FunctionCounter`/`Gauge`/`MeterBinder`), Spring Boot 2.7.18 auto-config, JUnit 5 + Mockito + `SimpleMeterRegistry`. Spec: `docs/superpowers/specs/2026-06-02-cluster-micrometer-metrics-design.md`. Pattern reference: `netty-spring-boot-autoconfigure/.../NettyWebSocketMeterBinder.java` + `NettyMicrometerConfigure.java`. Develops on `1.9.0-RC3`.

---

## Environment notes for every task
- Repo: `C:\Users\qq951\IdeaProjects\netty-spring`; Windows (PowerShell + Bash); Maven 3.9.9 (Aliyun mirror); Java 17.
- Git: work on branch `feature/1.9.0-cluster-metrics` (created in Task 0). Do NOT push/deploy.
- Redis live on `localhost:16379`.
- Match on quoted code, not line numbers. TDD where a test is specified.

## Confirmed facts (no need to re-derive)
- `ClusterRuntimeStats` public getters: `getBroadcastPublished()`, `getCrossNodeBroadcastReceived()`, `getSelfDeliveryDropped()`, `getUnicastSent()`, `getPublishFailures()`, `getBroadcastsSkippedDegraded()`, `getReliablePublished()`, `getReliableReceived()`, `getCacheHits()`, `getCacheMisses()` (all `long`). No source change needed.
- `ClusterMessageSender.getClusterRuntimeStats()` → `ClusterRuntimeStats`. `ClusterNodeManager.getState()` → `NodeState`. `ClusterBroker.state()` → `BrokerState`. `HmacMessageAuthenticator.getRejectedCount()` → `long`.
- `NodeState` values: `JOINING, ACTIVE, DEGRADED, RESYNC, DRAINING, LEFT`. `BrokerState` values: `ACTIVE, DEGRADED, SHUTDOWN` (the binder iterates `.values()` so it's robust to changes).
- The cluster starter `pom.xml` already declares `spring-boot-starter-actuator` as `<optional>true</optional>` and `spring-boot-starter-test` as test scope (Mockito + JUnit available).
- The starter's `AutoConfiguration.imports` currently lists `NettyWebSocketClusterConfigure` + `NettyClusterActuatorConfigure`.

## File Structure
- New: `netty-websocket-cluster-spring-boot-starter/.../configure/NettyClusterMeterBinder.java`, `.../configure/NettyClusterMetricsConfigure.java`.
- Modified: cluster starter `pom.xml` (optional micrometer-core), `AutoConfiguration.imports`.
- Tests: new `.../configure/NettyClusterMeterBinderTest.java`; addition to `NettyWebSocketClusterConfigureTest.java`.

---

## Task 0: Branch setup
- [ ] **Step 1:** `git checkout -b feature/1.9.0-cluster-metrics` (from `master`, at RC3). Confirm `git branch --show-current`.

---

## Task 1: NettyClusterMeterBinder + unit test (+ optional micrometer dep)

**Files:** Modify the starter `pom.xml`; create `NettyClusterMeterBinder.java`; create `NettyClusterMeterBinderTest.java`.

- [ ] **Step 1: Add micrometer-core as an optional dependency**
In `netty-websocket-cluster-spring-boot-starter/pom.xml`, add inside `<dependencies>` (right after the actuator optional dependency block):
```xml
        <!-- Micrometer (optional): enables the cluster MeterBinder when present -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-core</artifactId>
            <optional>true</optional>
        </dependency>
```
(Version is managed by the Spring Boot BOM via the parent — no explicit `<version>`.)

- [ ] **Step 2: Write the failing unit test**
Create `netty-websocket-cluster-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/boot/configure/NettyClusterMeterBinderTest.java`:
```java
package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterRuntimeStats;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.HmacMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.NodeState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NettyClusterMeterBinderTest {

    @Test
    void bindsCountersAndPerStateGauges() {
        ClusterRuntimeStats stats = mock(ClusterRuntimeStats.class);
        when(stats.getBroadcastPublished()).thenReturn(5L);
        when(stats.getUnicastSent()).thenReturn(2L);
        when(stats.getReliablePublished()).thenReturn(7L);
        when(stats.getPublishFailures()).thenReturn(1L);
        // unstubbed long getters default to 0 (Mockito) → those meters read 0.0

        ClusterMessageSender sender = mock(ClusterMessageSender.class);
        when(sender.getClusterRuntimeStats()).thenReturn(stats);
        ClusterNodeManager nodeManager = mock(ClusterNodeManager.class);
        when(nodeManager.getState()).thenReturn(NodeState.ACTIVE);
        ClusterBroker broker = mock(ClusterBroker.class);
        when(broker.state()).thenReturn(BrokerState.ACTIVE);

        // a real HMAC authenticator with 2 rejections (strict rejects untagged input)
        HmacMessageAuthenticator auth =
                new HmacMessageAuthenticator("a-32-char-secret-for-the-test!!!".getBytes(), true);
        auth.unwrap("untagged-1");
        auth.unwrap("untagged-2");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NettyClusterMeterBinder binder = new NettyClusterMeterBinder(sender, nodeManager, broker, auth);
        binder.bindTo(registry);

        assertEquals(5.0, registry.get("netty.cluster.broadcast.published").functionCounter().count());
        assertEquals(2.0, registry.get("netty.cluster.unicast.sent").functionCounter().count());
        assertEquals(7.0, registry.get("netty.cluster.reliable.published").functionCounter().count());
        assertEquals(1.0, registry.get("netty.cluster.publish.failures").functionCounter().count());
        assertEquals(0.0, registry.get("netty.cluster.cache.hits").functionCounter().count());
        assertEquals(2.0, registry.get("netty.cluster.auth.rejected").functionCounter().count());

        // per-state gauges: ACTIVE=1.0, others=0.0
        assertEquals(1.0, registry.get("netty.cluster.node.state").tag("state", "active").gauge().value());
        assertEquals(0.0, registry.get("netty.cluster.node.state").tag("state", "degraded").gauge().value());
        assertEquals(0.0, registry.get("netty.cluster.node.state").tag("state", "left").gauge().value());
        assertEquals(1.0, registry.get("netty.cluster.broker.state").tag("state", "active").gauge().value());
        assertEquals(0.0, registry.get("netty.cluster.broker.state").tag("state", "shutdown").gauge().value());

        // idempotent re-bind: no duplicate meters
        int before = registry.getMeters().size();
        binder.bindTo(registry);
        assertEquals(before, registry.getMeters().size(), "re-binding the same registry must not duplicate meters");
    }

    @Test
    void noOpAuthenticatorReportsZeroRejections() {
        ClusterMessageSender sender = mock(ClusterMessageSender.class);
        when(sender.getClusterRuntimeStats()).thenReturn(mock(ClusterRuntimeStats.class));
        ClusterNodeManager nodeManager = mock(ClusterNodeManager.class);
        when(nodeManager.getState()).thenReturn(NodeState.ACTIVE);
        ClusterBroker broker = mock(ClusterBroker.class);
        when(broker.state()).thenReturn(BrokerState.ACTIVE);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new NettyClusterMeterBinder(sender, nodeManager, broker,
                new com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator())
                .bindTo(registry);

        assertEquals(0.0, registry.get("netty.cluster.auth.rejected").functionCounter().count());
    }
}
```

- [ ] **Step 3: Run to verify failure**
`mvn -q -pl netty-websocket-cluster-spring-boot-starter -am test -Dtest=NettyClusterMeterBinderTest` → FAIL to COMPILE (`NettyClusterMeterBinder` missing).

- [ ] **Step 4: Create the binder**
Create `netty-websocket-cluster-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/boot/configure/NettyClusterMeterBinder.java` (Apache 15-line header copied from `NettyClusterActuatorConfigure.java`), then:
```java
package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterRuntimeStats;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.HmacMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.NodeState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Bridges WebSocket-cluster runtime signals to a Micrometer {@link MeterRegistry} as
 * {@code netty.cluster.*} meters (read-throughs over the existing counters — no hot-path cost).
 *
 * <p>Counters: broadcast published/received/self_dropped/skipped_degraded, unicast sent, publish
 * failures, reliable published/received, cache hits/misses, auth rejected. Gauges: per-state up/down
 * for node state and broker state (tagged {@code state}). Aggregate-only (no per-URI tags).
 *
 * @author berrywang1996
 * @since V1.9.0
 * @see NettyClusterMetricsConfigure
 */
public class NettyClusterMeterBinder implements MeterBinder {

    private final ClusterMessageSender sender;
    private final ClusterNodeManager nodeManager;
    private final ClusterBroker broker;
    private final MessageAuthenticator authenticator;

    /** Registries already bound, by identity, so a repeated bindTo does not duplicate meters. */
    private final Set<MeterRegistry> boundRegistries =
            Collections.newSetFromMap(new IdentityHashMap<MeterRegistry, Boolean>());

    public NettyClusterMeterBinder(ClusterMessageSender sender, ClusterNodeManager nodeManager,
                                   ClusterBroker broker, MessageAuthenticator authenticator) {
        this.sender = sender;
        this.nodeManager = nodeManager;
        this.broker = broker;
        this.authenticator = authenticator;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        synchronized (boundRegistries) {
            if (!boundRegistries.add(registry)) {
                return;
            }
        }
        ClusterRuntimeStats stats = sender.getClusterRuntimeStats();

        counter(registry, "netty.cluster.broadcast.published", stats,
                ClusterRuntimeStats::getBroadcastPublished, "Broadcasts handed to the cluster broker for publish");
        counter(registry, "netty.cluster.broadcast.received", stats,
                ClusterRuntimeStats::getCrossNodeBroadcastReceived, "Broadcasts received from other nodes and delivered locally");
        counter(registry, "netty.cluster.broadcast.self_dropped", stats,
                ClusterRuntimeStats::getSelfDeliveryDropped, "Self-delivered broadcasts suppressed (origin == local node)");
        counter(registry, "netty.cluster.broadcast.skipped_degraded", stats,
                ClusterRuntimeStats::getBroadcastsSkippedDegraded, "Cross-node broadcasts skipped because the node was not ACTIVE");
        counter(registry, "netty.cluster.unicast.sent", stats,
                ClusterRuntimeStats::getUnicastSent, "Unicast messages sent to remote nodes");
        counter(registry, "netty.cluster.publish.failures", stats,
                ClusterRuntimeStats::getPublishFailures, "Cluster publishes that failed or were dropped");
        counter(registry, "netty.cluster.reliable.published", stats,
                ClusterRuntimeStats::getReliablePublished, "Reliable broadcasts published (XADD)");
        counter(registry, "netty.cluster.reliable.received", stats,
                ClusterRuntimeStats::getReliableReceived, "Reliable broadcasts received and delivered locally");
        counter(registry, "netty.cluster.cache.hits", stats,
                ClusterRuntimeStats::getCacheHits, "Node lookup cache hits");
        counter(registry, "netty.cluster.cache.misses", stats,
                ClusterRuntimeStats::getCacheMisses, "Node lookup cache misses");

        FunctionCounter.builder("netty.cluster.auth.rejected", authenticator,
                        a -> (a instanceof HmacMessageAuthenticator)
                                ? (double) ((HmacMessageAuthenticator) a).getRejectedCount() : 0.0)
                .description("Inbound cluster envelopes rejected for a missing/invalid HMAC tag")
                .register(registry);

        for (NodeState s : NodeState.values()) {
            Gauge.builder("netty.cluster.node.state", nodeManager, nm -> nm.getState() == s ? 1.0 : 0.0)
                    .tag("state", s.name().toLowerCase())
                    .description("1.0 when this node is in the tagged state, else 0.0")
                    .register(registry);
        }
        for (BrokerState s : BrokerState.values()) {
            Gauge.builder("netty.cluster.broker.state", broker, b -> b.state() == s ? 1.0 : 0.0)
                    .tag("state", s.name().toLowerCase())
                    .description("1.0 when the cluster broker is in the tagged state, else 0.0")
                    .register(registry);
        }
    }

    private static void counter(MeterRegistry registry, String name, ClusterRuntimeStats stats,
                                ToDoubleFunction<ClusterRuntimeStats> f, String description) {
        FunctionCounter.builder(name, stats, f).description(description).register(registry);
    }
}
```

- [ ] **Step 5: Run to verify pass**
`mvn -q -pl netty-websocket-cluster-spring-boot-starter -am test -Dtest=NettyClusterMeterBinderTest` → 2 PASS.

- [ ] **Step 6: Commit**
```
git add netty-websocket-cluster-spring-boot-starter/pom.xml netty-websocket-cluster-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/boot/configure/NettyClusterMeterBinder.java netty-websocket-cluster-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/boot/configure/NettyClusterMeterBinderTest.java
git commit -m "feat(cluster): NettyClusterMeterBinder — netty.cluster.* counters + per-state gauges

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: NettyClusterMetricsConfigure + auto-config registration + context test

**Files:** Create `NettyClusterMetricsConfigure.java`; modify `AutoConfiguration.imports`; modify `NettyWebSocketClusterConfigureTest.java`.

- [ ] **Step 1: Create the config** (mirrors `NettyClusterActuatorConfigure`)
Create `netty-websocket-cluster-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/boot/configure/NettyClusterMetricsConfigure.java` (Apache header), then:
```java
package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer auto-configuration for WebSocket cluster mode. Activated when {@code micrometer-core}
 * is on the classpath, cluster mode is enabled, and the cluster beans are present. Registers a
 * {@link NettyClusterMeterBinder} that bridges cluster runtime counters + node/broker state to the
 * application's {@link MeterRegistry} as {@code netty.cluster.*} meters.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
@Configuration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "server.netty.websocket.cluster", name = "enable", havingValue = "true")
@AutoConfigureAfter(NettyWebSocketClusterConfigure.class)
public class NettyClusterMetricsConfigure {

    @Bean
    @ConditionalOnBean({MeterRegistry.class, ClusterMessageSender.class})
    @ConditionalOnMissingBean(NettyClusterMeterBinder.class)
    public NettyClusterMeterBinder nettyClusterMeterBinder(ClusterMessageSender sender,
                                                           ClusterNodeManager nodeManager,
                                                           ClusterBroker broker,
                                                           MessageAuthenticator authenticator) {
        log.info("Registering WebSocket cluster metrics with Micrometer");
        return new NettyClusterMeterBinder(sender, nodeManager, broker, authenticator);
    }
}
```

- [ ] **Step 2: Register in AutoConfiguration.imports**
In `netty-websocket-cluster-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, append a third line:
```
com.github.berrywang1996.netty.spring.boot.configure.NettyClusterMetricsConfigure
```
(So the file lists all three: `NettyWebSocketClusterConfigure`, `NettyClusterActuatorConfigure`, `NettyClusterMetricsConfigure`.)

- [ ] **Step 3: Context-test addition**
In `NettyWebSocketClusterConfigureTest.java`, add a new test (it needs a `MeterRegistry` bean + the metrics config + Redis). Add the imports at the top if missing: `import io.micrometer.core.instrument.MeterRegistry;` and `import io.micrometer.core.instrument.simple.SimpleMeterRegistry;`. Then:
```java
    @Test
    void clusterMetrics_binderRegisteredWhenMicrometerPresent() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration.class,
                        NettyWebSocketClusterConfigure.class,
                        NettyClusterMetricsConfigure.class))
                .withUserConfiguration(LocalSenderConfig.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-metrics-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(NettyClusterMeterBinder.class);
                });
    }
```
(`LocalSenderConfig` is the existing nested `@Configuration` in this test class that provides the local `messageSender` bean. `ApplicationContextRunner` is already imported.)

- [ ] **Step 4: Run**
`mvn -q -pl netty-websocket-cluster-spring-boot-starter -am test -Dtest=NettyWebSocketClusterConfigureTest` → all PASS (the new metrics test runs against live Redis). Also re-run `-Dtest=NettyClusterMeterBinderTest`.

- [ ] **Step 5: Commit**
```
git add netty-websocket-cluster-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/boot/configure/NettyClusterMetricsConfigure.java netty-websocket-cluster-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports netty-websocket-cluster-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigureTest.java
git commit -m "feat(cluster): gate NettyClusterMeterBinder on micrometer + cluster.enable (auto-config)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Documentation

**Files:** Modify `docs/release-notes-1.9.0.md`, `docs/api-guide.md`, `docs/cluster-design.md`, `docs/development-plan.md`.

- [ ] **Step 1: Write the docs** (ENCODING SAFETY: Edit tool ONLY — never PowerShell file writes. After: `for f in docs/release-notes-1.9.0.md docs/api-guide.md docs/cluster-design.md docs/development-plan.md; do iconv -f UTF-8 -t UTF-8 "$f" >/dev/null 2>&1 && echo "$f ok" || echo "$f BAD"; done; LC_ALL=C grep -l $'\xef\xbf\xbd' docs/*.md || echo none`)
Content (accurate): full Micrometer `netty.cluster.*` meter set (the 11 counters + the per-state `netty.cluster.node.state{state=...}` / `netty.cluster.broker.state{state=...}` gauges), bound via `NettyClusterMeterBinder` (1.7.0 MeterBinder pattern), optional (micrometer-core on classpath) + gated (`cluster.enable=true`), aggregate-only (no per-URI tags), at `/actuator/metrics` + `/actuator/prometheus`; complements the existing `ClusterHealthIndicator`. Status → RC4. Move "完整 Micrometer 指标集 / full Micrometer meter-binder set" from the deferred lists to ✅ shipped (1.9.0 RC4); keep the remaining deferred items (NATS, multi/sharded pub/sub, Redis Cluster client, W3C TraceContext, multi-node demo + Testcontainers).

- [ ] **Step 2: Commit**
```
git add docs/
git commit -m "docs(cluster): Micrometer cluster metrics — release notes, api-guide, design, roadmap

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Full test + cut v1.9.0-RC4

**Files:** all 11 poms (`1.9.0-RC3` → `1.9.0-RC4`); `docs/release-notes-1.9.0.md` (test count + status).

- [ ] **Step 1: Full reactor test** — `mvn test` (Redis up) → BUILD SUCCESS, 11 modules. Capture the exact total (was 320; +~3: `NettyClusterMeterBinderTest` 2 + 1 context test). STOP+report if anything fails.
- [ ] **Step 2: Update release-notes** (Edit tool only) — bump the `## 测试覆盖` count line to the real Step-1 total + the status line to RC4; add a bullet for `NettyClusterMeterBinderTest`. Verify UTF-8 + U+FFFD as in Task 3.
- [ ] **Step 3: Bump to RC4** — `for f in pom.xml */pom.xml; do sed -i 's|<version>1.9.0-RC3</version>|<version>1.9.0-RC4</version>|g' "$f"; done` ; verify `grep -rl "1.9.0-RC3" --include=pom.xml . | wc -l` = 0 and `grep -rl "1.9.0-RC4" --include=pom.xml . | wc -l` = 11.
- [ ] **Step 4: Re-test** — `mvn -q test` → BUILD SUCCESS.
- [ ] **Step 5: Commit + tag**
```
git add -A
git commit -m "release: 1.9.0-RC4 — full Micrometer cluster metrics

netty.cluster.* meter set (counters + per-state node/broker gauges) via a
MeterBinder (1.7.0 pattern); optional + gated, aggregate-only, additive.
Part of the 1.9.0 cycle; final 1.9.0 cut when the cycle completes.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git tag -a v1.9.0-RC4 -m "v1.9.0-RC4 — full Micrometer cluster metrics (1.9.0 cycle, in development)"
```
- [ ] **Step 6: Report** — RC4 cut locally (not pushed/deployed). The 1.9.0 cycle continues until the user says it's complete.

---

## Notes for the implementer
- **Zero hot-path cost / additive:** the binder only reads existing getters; no increment site changes; no meters when micrometer-core is absent (the bean's `@ConditionalOnClass(MeterRegistry)`/`@ConditionalOnBean` keep it out).
- **`ClusterRuntimeStats` is mockable** (concrete, non-final) — the unit test stubs its getters; no need to make fields settable.
- **Gauges iterate enum `.values()`** so adding a `NodeState`/`BrokerState` later auto-adds its gauge.
- **No source change to the cluster module** — everything is in the starter + tests.
```
