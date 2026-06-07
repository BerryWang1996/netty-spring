# RC16 Backlog Cleanup — Implementation Plan

> **For agentic workers:** 2 parallel file-disjoint implementer tracks (Track L + Track S), then controller-side cut.

**Goal:** Ship L1 (custom Spring Condition for all-4-SPI-overridden niche) + S1 (`streamCache` invalidate on
NATS reconnect) as `1.9.0-RC16`.

**Spec:** `docs/superpowers/specs/2026-06-07-rc16-backlog-cleanup.md`.

**Branch:** `feature/1.9.0-rc16-backlog-cleanup` (off RC15 `4fb5bdf`).

---

## File Structure

| Path | Action |
|---|---|
| `netty-spring-websocket-cluster/src/main/java/.../cluster/support/OnAnyRedisSpiRequired.java` (or wherever convention dictates) | **Create** (L1) |
| `netty-websocket-cluster-spring-boot-starter/src/main/java/.../configure/NettyWebSocketClusterConfigure.java` | Modify (L1) |
| `netty-websocket-cluster-spring-boot-starter/src/test/java/.../NettyWebSocketClusterConfigureTest.java` | Modify (L1 tests) |
| `netty-spring-websocket-cluster/src/main/java/.../cluster/nats/NatsJetStreamReliableBroker.java` | Modify (S1) |
| `netty-spring-websocket-cluster/src/test/java/.../cluster/nats/NatsJetStreamReliableBrokerTest.java` | Modify (S1 test) |
| `docs/release-notes-1.9.0.md` | Modify (header + §㉑) |
| `docs/pre-ga-audit-backlog.md` | Modify (strike L1+S1; update header to reflect empty backlog) |
| 11 POMs | Modify (RC15 → RC16) |

---

## Track L: L1 — OnAnyRedisSpiRequired Condition

**Files:**
- Create: `netty-spring-websocket-cluster/src/main/java/.../cluster/support/OnAnyRedisSpiRequired.java`
  (verify the convention; could be `cluster.spi` or `cluster.config` — pick what already exists)
- Modify: `netty-websocket-cluster-spring-boot-starter/.../NettyWebSocketClusterConfigure.java`
- Modify: `netty-websocket-cluster-spring-boot-starter/.../NettyWebSocketClusterConfigureTest.java`

### Steps

- [ ] **Step 1:** Determine the right package for `OnAnyRedisSpiRequired`. Look at where existing
  Spring-glue classes live in the project. If `cluster.spi` already contains the 4 SPI interfaces,
  put it next door. If there's a `cluster.support` or `cluster.config` package, put it there.
  If unsure, put it in `cluster.support` (create the package if needed).

- [ ] **Step 2:** Implement the Condition:

```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.support;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterReaper;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when at least one of the 4 Redis-backed cluster SPI interfaces will be created by
 * the default auto-config — i.e. the user has NOT provided a {@code @Bean} for it. Used to gate
 * the eager-init Redis client/connection beans so a fully-custom-SPI deployment doesn't pay for
 * an idle Redis connection.
 * <p>
 * Evaluated at {@link ConfigurationPhase#REGISTER_BEAN} so user-supplied {@code @Bean} definitions
 * are visible.
 */
public class OnAnyRedisSpiRequired implements ConfigurationCondition {

    @Override
    public ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.REGISTER_BEAN;
    }

    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata md) {
        ConfigurableListableBeanFactory bf = (ConfigurableListableBeanFactory) ctx.getBeanFactory();
        return !hasBean(bf, SessionRegistry.class)
            || !hasBean(bf, ClusterBroker.class)
            || !hasBean(bf, ClusterNodeHeartbeat.class)
            || !hasBean(bf, ClusterReaper.class);
    }

    private static boolean hasBean(ConfigurableListableBeanFactory bf, Class<?> type) {
        return bf.getBeanNamesForType(type, true, false).length > 0;
    }
}
```

- [ ] **Step 3:** Open `NettyWebSocketClusterConfigure.java`. Find `nettyClusterRedisClient` and
  `nettyClusterRedisConnection` beans. Add `@Conditional(OnAnyRedisSpiRequired.class)` to BOTH, keeping
  the existing `@ConditionalOnExpression(STANDALONE_REDIS_REGISTRY)` and `@ConditionalOnMissingBean(...)`
  annotations. Order doesn't matter for Spring; pick the convention used elsewhere (likely after
  `@Bean` and before `@ConditionalOnMissingBean`).

Add the necessary import:
```java
import org.springframework.context.annotation.Conditional;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.support.OnAnyRedisSpiRequired;
```

- [ ] **Step 4:** Add 3 context test cases to `NettyWebSocketClusterConfigureTest`:

```java
@Test
void clusterMode_allFourSpiOverridden_doesNotCreateRedisClient() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NettyWebSocketClusterConfigure.class))
        .withUserConfiguration(AllFourSpiOverridesConfig.class)
        .withPropertyValues("server.netty.websocket.cluster.enable=true")
        .run(ctx -> {
            assertThat(ctx).doesNotHaveBean(io.lettuce.core.RedisClient.class);
            assertThat(ctx).hasBean("customSessionRegistry");
            assertThat(ctx).hasBean("customClusterBroker");
            assertThat(ctx).hasBean("customClusterNodeHeartbeat");
            assertThat(ctx).hasBean("customClusterReaper");
        });
}

@Test
void clusterMode_onlySessionRegistryOverridden_stillCreatesRedisClient() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NettyWebSocketClusterConfigure.class))
        .withUserConfiguration(OnlySessionRegistryOverrideConfig.class)
        .withPropertyValues("server.netty.websocket.cluster.enable=true")
        .run(ctx -> {
            // RedisClient still needed because broker/heartbeat/reaper are still defaults
            assertThat(ctx).hasSingleBean(io.lettuce.core.RedisClient.class);
        });
}

@Test
void clusterMode_zeroOverrides_createsRedisClient_regression() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NettyWebSocketClusterConfigure.class))
        .withPropertyValues("server.netty.websocket.cluster.enable=true")
        .run(ctx -> {
            assertThat(ctx).hasSingleBean(io.lettuce.core.RedisClient.class);
            // RC15 behavior preserved
        });
}

// Helper user-config classes for the overrides — define as @Configuration static nested classes
// or top-level inner classes per existing test conventions.
@Configuration
static class AllFourSpiOverridesConfig {
    @Bean public SessionRegistry customSessionRegistry() { return mock(SessionRegistry.class); }
    @Bean public ClusterBroker customClusterBroker() { return mock(ClusterBroker.class); }
    @Bean public ClusterNodeHeartbeat customClusterNodeHeartbeat() { return mock(ClusterNodeHeartbeat.class); }
    @Bean public ClusterReaper customClusterReaper() { return mock(ClusterReaper.class); }
}

@Configuration
static class OnlySessionRegistryOverrideConfig {
    @Bean public SessionRegistry customSessionRegistry() { return mock(SessionRegistry.class); }
}
```

> **Note for implementer:** the existing test pattern in `NettyWebSocketClusterConfigureTest` may use
> `ApplicationContextRunner` differently (e.g. with `withBean(...)` or different helper-class style).
> Match the existing convention. The mocks may need to be Mockito mocks or anonymous SPI impls — match
> what other context tests do.

- [ ] **Step 5:** Run: `mvn -pl netty-websocket-cluster-spring-boot-starter test -Dtest=NettyWebSocketClusterConfigureTest`. All green.

- [ ] **Step 6:** Run the full cluster + starter modules to verify no regressions:
  `mvn -pl netty-spring-websocket-cluster,netty-websocket-cluster-spring-boot-starter test`. All green.

- [ ] **Step 7:** Commit:

```bash
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/support/OnAnyRedisSpiRequired.java \
        netty-websocket-cluster-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigure.java \
        netty-websocket-cluster-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigureTest.java
git commit -m "feat(cluster/autoconfig): OnAnyRedisSpiRequired gates Redis client to actual need (L1)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Track S: S1 — streamCache invalidate on reconnect

**Files:**
- Modify: `netty-spring-websocket-cluster/.../cluster/nats/NatsJetStreamReliableBroker.java`
- Modify: `netty-spring-websocket-cluster/.../cluster/nats/NatsJetStreamReliableBrokerTest.java`

### Steps

- [ ] **Step 1:** Open `NatsJetStreamReliableBroker.java`. Find the `ConnectionListener` registered in the
  constructor (RC13 §3 wiring; search for `addConnectionListener` or `connectionEvent`).

- [ ] **Step 2:** In the `RECONNECTED` / `CONNECTED` event branch, add `streamCache.clear()` before the
  state CAS:

```java
} else if (ev == Events.RECONNECTED || ev == Events.CONNECTED) {
    streamCache.clear();   // S1: invalidate per-URI stream-existence cache so the next publish
                           // re-validates the stream's existence (in case NATS lost data while we were down).
    if (state.compareAndSet(BrokerState.DEGRADED, BrokerState.ACTIVE)) {
        log.info("NatsJetStreamReliableBroker transport reconnected — state ACTIVE; streamCache cleared");
    }
}
```

- [ ] **Step 3:** Add a unit test to `NatsJetStreamReliableBrokerTest`:

```java
@Test
void connectionListener_clearsStreamCacheOnReconnect() throws Exception {
    // Capture the ConnectionListener registered with the connection during broker construction
    ArgumentCaptor<ConnectionListener> cap = ArgumentCaptor.forClass(ConnectionListener.class);
    verify(mockConn).addConnectionListener(cap.capture());
    ConnectionListener listener = cap.getValue();

    // Seed streamCache by completing a successful publish (or via reflection if simpler)
    when(jsm.getStreamInfo(anyString())).thenReturn(streamInfoWith(
        StorageType.File, DiscardPolicy.Old, RetentionPolicy.Limits, 10000L, 1));
    broker.reliablePublish("/ws/s1", envelope("seed"));
    // sanity: cache now has the URI marker
    // (skip if reflection is too invasive; cover via the post-reconnect re-invocation below)

    // Fire RECONNECTED
    listener.connectionEvent(mockConn, Events.RECONNECTED);

    // Assert: subsequent publish re-invokes getStreamInfo (proving cache was cleared)
    reset(jsm);
    when(jsm.getStreamInfo(anyString())).thenReturn(streamInfoWith(
        StorageType.File, DiscardPolicy.Old, RetentionPolicy.Limits, 10000L, 1));
    broker.reliablePublish("/ws/s1", envelope("post-reconnect"));
    verify(jsm, atLeastOnce()).getStreamInfo("netty-cluster-reliable-" + b64("/ws/s1"));
}
```

> **Implementer note:** the existing RC13 T5 unit test for `connectionListener_flipsStateOnDisconnectAndReconnect`
> shows the `ArgumentCaptor` pattern for capturing the listener. Reuse it. The `streamInfoWith(...)` and
> `envelope(...)` helpers exist (RC13). If accessing `streamCache` via reflection is cleaner than the
> "publish-twice + verify getStreamInfo twice" pattern, use reflection — pick whichever the existing test
> style prefers.

- [ ] **Step 4:** Run: `mvn -pl netty-spring-websocket-cluster test -Dtest=NatsJetStreamReliableBrokerTest`. All green.

- [ ] **Step 5:** Commit:

```bash
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/nats/NatsJetStreamReliableBroker.java \
        netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/nats/NatsJetStreamReliableBrokerTest.java
git commit -m "fix(cluster/nats): invalidate streamCache on transport reconnect (S1)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Controller tasks: release + finish

### Release notes + backlog cleanup

- [ ] **Step 1:** `docs/release-notes-1.9.0.md` — update header line (RC15 → RC16; append summary
  `;RC16 backlog 清零（L1 custom Spring Condition + S1 streamCache reconnect invalidate）`).

- [ ] **Step 2:** Add §㉑ before the config-reference section:

```markdown
### ㉑ 1.9.x backlog 清零 / 1.9.x backlog cleanup

*Since V1.9.0-RC16.* 2 项 backlog 收尾（**无 SPI 变更、无线格式变更、无新配置键，加性 only**）：

- **L1 — `OnAnyRedisSpiRequired` Spring Condition**：用户重写**全部** 4 个 Redis-backed SPI bean（`SessionRegistry` / `ClusterBroker` / `ClusterNodeHeartbeat` / `ClusterReaper`）时，不再创建空闲 `RedisClient` + 连接。**仅影响该 niche 场景**（部分覆盖时 `RedisClient` 照常创建——其他 SPI 仍依赖它）。
- **S1 — `NatsJetStreamReliableBroker.streamCache` 在重连时失效**：现有 `ConnectionListener.onReconnect` 在 CAS DEGRADED→ACTIVE 时同时 `streamCache.clear()`。next publish 走 `ensureStream(...)` 重新校验（已 idempotent + mismatch-detecting，RC13 §5.1）。每 URI 多一次 `getStreamInfo` round-trip，可忽略。生产场景（FILE storage + 标准 NATS restart）此前也无问题，本修复消除 RC15 IT 期间发现的测试场景对 ephemeral 数据丢失的隐式假设。

#### 向后兼容

加性变更。L1 只影响 niche 场景（无 `RedisClient` → 不再创建一个空闲连接，节约资源）；S1 reconnect 后多一次 `getStreamInfo`（量级小，rare event）。0 SPI / 配置 / wire 影响。

#### 1.9.x backlog 状态

RC16 之后 1.9.x backlog **清空**——只剩 RC11/RC14 期间标记为 Refuted 的项（不修复）。1.9.0 GA 可在 RC16 之上直接 cut。
```

- [ ] **Step 3:** Update test count to **444** (was 440 + L1 ×3 + S1 ×1 = 444).

- [ ] **Step 4:** `docs/pre-ga-audit-backlog.md` — strike L1 + S1; update the intro to note backlog is empty.
  Add a "Fixed in RC16" reference section with both items.

- [ ] **Step 5:** Verify no U+FFFD: `grep -l $'\xef\xbf\xbd' docs/` should not return any RC16-touched docs.

- [ ] **Step 6:** Commit. `git commit -m "docs(cluster): RC16 §㉑ + backlog cleanup (1.9.x backlog empty after this)"`

### Pom bump + final reactor + finish

- [ ] **Step 7:** Bump 11 POMs RC15 → RC16:

```bash
sed -i "s/1\.9\.0-RC15/1.9.0-RC16/g" pom.xml demo-netty-web-spring-boot-starter/pom.xml netty-spring-boot-autoconfigure/pom.xml netty-spring-web/pom.xml netty-spring-webmvc/pom.xml netty-spring-websocket/pom.xml netty-spring-websocket-cluster/pom.xml netty-web-spring-boot-starter/pom.xml netty-webmvc-spring-boot-starter/pom.xml netty-websocket-spring-boot-starter/pom.xml netty-websocket-cluster-spring-boot-starter/pom.xml
```

- [ ] **Step 8:** Full reactor: `mvn test`. Expect BUILD SUCCESS, **444 tests**.

- [ ] **Step 9:** Commit pom bump. `git commit -m "release: 1.9.0-RC16 - backlog cleanup (L1 + S1; 1.9.x backlog empty)"`

- [ ] **Step 10:** Finish branch: FF-merge to master + tag `v1.9.0-RC16` + delete branch. STOP before push.

---

## Self-Review

- **Spec coverage:** L1 in Track L (Condition class + 2 bean annotations + 3 context tests); S1 in Track S
  (one-line broker change + 1 unit test). Maps 1:1.
- **Parallel safety:** Track L touches 3 files (new Condition class + 2 starter-module files); Track S touches
  2 files (broker + its test). **No overlap.** Safe to dispatch in parallel.
- **Tests delta:** +4 expected (3 context + 1 unit).
- **Risks addressed:** §7 of the spec; the implementer should re-read the spec §7 before committing to be
  aware of the `REGISTER_BEAN` phase + `getBeanNamesForType` semantics.
- **Post-RC16 GA:** §8 of the spec notes that the user may cut 1.9.0 GA after RC16. This plan does NOT cut
  GA — that's a separate user-directed step.
