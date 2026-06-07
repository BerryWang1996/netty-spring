# RC16 Backlog Cleanup — Design Spec

**Target:** netty-spring 1.9.0-RC16
**Branch:** `feature/1.9.0-rc16-backlog-cleanup` (off master at RC15 `4fb5bdf`)
**Status:** approved 2026-06-07 (goal directive "继续开发" — proceed)

---

## 1. Goal

Land the last 2 items from the 1.9.x backlog: **L1** (custom Spring `Condition` for the all-4-Redis-SPI-overridden
niche) + **S1** (`NatsJetStreamReliableBroker.streamCache` invalidation on transport reconnect). After RC16
ships, the 1.9.x backlog is **empty**.

## 2. Items

### L1 — `OnAnyRedisSpiRequired` Condition

**Where:**
- New: `netty-spring-websocket-cluster/src/main/java/.../cluster/spi/OnAnyRedisSpiRequired.java`
  (or under `cluster.support` / `cluster.spring` — wherever the project's convention for Spring-glue classes
  lives; auto-config module is also acceptable).
- Modify: `netty-websocket-cluster-spring-boot-starter/.../NettyWebSocketClusterConfigure.java`
  — beans `nettyClusterRedisClient` and `nettyClusterRedisConnection`.

**Approach:**

Implement a custom `org.springframework.context.annotation.Condition` that **returns true iff at least one
of these 4 Redis-backed SPI interfaces will be created by default auto-config** (i.e., the user has NOT
provided a `@Bean` for it):

- `com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry`
- `com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker`
- `com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterNodeHeartbeat`
- `com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterReaper`

Implementation pattern:

```java
public class OnAnyRedisSpiRequired implements ConfigurationCondition {

    @Override
    public ConfigurationPhase getConfigurationPhase() {
        // REGISTER_BEAN — evaluate after auto-config bean definitions are registered so
        // we can see user-supplied @Bean overrides.
        return ConfigurationPhase.REGISTER_BEAN;
    }

    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata md) {
        ConfigurableListableBeanFactory bf = (ConfigurableListableBeanFactory) ctx.getBeanFactory();
        return !hasBeanOfType(bf, SessionRegistry.class)
            || !hasBeanOfType(bf, ClusterBroker.class)
            || !hasBeanOfType(bf, ClusterNodeHeartbeat.class)
            || !hasBeanOfType(bf, ClusterReaper.class);
    }

    private static boolean hasBeanOfType(ConfigurableListableBeanFactory bf, Class<?> type) {
        return bf.getBeanNamesForType(type, true /* includeNonSingletons */, false /* allowEagerInit */).length > 0;
    }
}
```

**Wiring:** add `@Conditional(OnAnyRedisSpiRequired.class)` to BOTH `nettyClusterRedisClient` and
`nettyClusterRedisConnection`, **in addition to** the existing `@ConditionalOnExpression(STANDALONE_REDIS_REGISTRY)`
+ `@ConditionalOnMissingBean(...)`.

### S1 — `streamCache.clear()` on transport reconnect

**Where:** `netty-spring-websocket-cluster/.../cluster/nats/NatsJetStreamReliableBroker.java`,
inside the existing `ConnectionListener` callback wired in the constructor (RC13 §3).

**Approach:** Extend the existing `RECONNECTED` / `CONNECTED` event branch:

```java
} else if (ev == Events.RECONNECTED || ev == Events.CONNECTED) {
    streamCache.clear();   // S1: invalidate per-URI stream-existence cache
    if (state.compareAndSet(BrokerState.DEGRADED, BrokerState.ACTIVE)) {
        log.info("NatsJetStreamReliableBroker transport reconnected — state ACTIVE; streamCache cleared");
    }
}
```

Subsequent `reliablePublish(uri, ...)` calls re-invoke `ensureStream(b64uri)`, which is already
idempotent + mismatch-detecting (RC13 §5.1). The cost is one extra `getStreamInfo` round-trip per URI per
reconnect event — negligible.

## 3. Tests delta

| Item | Test |
|---|---|
| **L1** | New cases in `NettyWebSocketClusterConfigureTest`: (i) `cluster.enable=true` + user `@Bean` for all 4 SPI interfaces → assert no `RedisClient` bean + assert all 4 user beans present; (ii) `cluster.enable=true` + user `@Bean` for ONLY `SessionRegistry` → assert `RedisClient` IS created (the other 3 SPI still need it); (iii) regression: `cluster.enable=true` + zero overrides (default all-Redis) → assert `RedisClient` IS created (RC15 behavior preserved). |
| **S1** | New unit test in `NatsJetStreamReliableBrokerTest`: `connectionListener_clearsStreamCacheOnReconnect`. Seed `streamCache` via reflection (or via a first publish on a mocked `jsm`). Capture the `ConnectionListener` via `ArgumentCaptor` (the existing pattern from RC13 T5 / RC12 L8). Invoke `connectionEvent(conn, Events.RECONNECTED)`. Assert (a) `streamCache` is empty afterwards, (b) state is `ACTIVE`, (c) subsequent `reliablePublish` re-invokes `jsm.getStreamInfo`. |

**Expected reactor delta:** +3 context tests (L1) + 1 unit test (S1) = **+4**. 440 → 444.

## 4. Backward compatibility

- **Single-node mode (default):** zero impact.
- **All-Redis / mixed / all-NATS deployments:** byte-level identical for the vast majority.
  - L1 only changes behavior in the niche all-4-overridden case (was: idle `RedisClient` created; now: no
    `RedisClient`). **Pure correctness improvement.**
  - S1 adds one `getStreamInfo` per URI per reconnect event. Negligible overhead for the rare reconnect
    case. Steady-state publish path unchanged.
- **No SPI signature change.** No wire format change. No config key change. No bean condition tightening
  that could surprise existing users (additive `@Conditional` only narrows when the bean is created — never
  causes a bean to be created when it wasn't before).

## 5. Documentation updates

1. `docs/release-notes-1.9.0.md` — header `RC15` → `RC16`; append `;RC16 backlog 清零 (L1 + S1)`. Add a
   short §㉑ noting the 2 items + "backlog status: empty" line.
2. `docs/pre-ga-audit-backlog.md` — strike L1 + S1 (move to "Fixed in RC16" reference); update header
   to note backlog status. Update intro to reflect L1+S1 shipped.
3. No api-guide / cluster-design changes (no user-facing surface change).

## 6. Implementation tracks (parallel)

| Track | Files | Item |
|---|---|---|
| **L** | `OnAnyRedisSpiRequired.java` (new) + `NettyWebSocketClusterConfigure.java` + `NettyWebSocketClusterConfigureTest.java` | L1 |
| **S** | `NatsJetStreamReliableBroker.java` + `NatsJetStreamReliableBrokerTest.java` | S1 |

File-disjoint. Safe for parallel dispatch.

## 7. Risk register

| Risk | Mitigation |
|---|---|
| L1: `ConfigurationPhase.REGISTER_BEAN` may be too late — user `@Bean` declared on the same `@Configuration` could resolve before/after timing-sensitive | Use `REGISTER_BEAN` phase (as recommended by Spring docs for SPI-bean lookups). Context tests with multiple permutations validate it. If a corner case is found in review, fall back to a static `@ConditionalOnBean(...)` chain (less precise but evaluated at the right phase). |
| L1: `bf.getBeanNamesForType(..., allowEagerInit=false)` — does it see beans from `@Bean` methods on user configs that haven't been processed yet? | At `REGISTER_BEAN` phase, all `@Bean` methods on user `@Configuration` classes are registered as bean DEFINITIONS (not yet instantiated). `getBeanNamesForType` queries definitions, so this works. Context tests cover it. |
| L1: corner-case — user provides one of the SPI beans via `@Primary` along with the default | `@ConditionalOnMissingBean` on the SPI bean would back off; our condition would still want `RedisClient` to exist (since user is using `@Primary` Redis registry). Test covers this via case (ii). |
| S1: `streamCache.clear()` on a transient flap could leak `getStreamInfo` calls (one per active URI) | Acceptable — reconnects are rare; URI count per node is small (typically <50). Worst case: brief one-shot uptick in NATS read load. No data-loss or correctness risk. |
| S1: clearing the cache while a concurrent `ensureStream` is mid-`computeIfAbsent` for some URI | `ConcurrentHashMap.computeIfAbsent` holds a per-key lock during its lambda. `clear()` is a separate operation that operates on the visible entries at clear time. Worst case: one URI's "in-flight ensure" completes after clear, leaving its marker in the now-mostly-empty map — totally harmless (the next publish on that URI hits the cache; subsequent publishes on other URIs re-ensure as expected). No race. |
| Bean lookup edge case: `OnAnyRedisSpiRequired` runs during context startup before user `@Configuration` is processed | `REGISTER_BEAN` phase runs AFTER bean definitions are loaded — covers user `@Configuration` classes scanned by `@ComponentScan` and `@Import`. Context tests with `@Bean` methods on a user config class confirm. |

## 8. Post-RC16 GA option

After RC16 lands, the 1.9.x backlog is empty (no L/N/P/Q/R/S items open; only "Refuted" entries remain).
The user can choose to cut **1.9.0 GA** (flip RC16 → 1.9.0, deploy to Maven Central) as the next step.
This spec does NOT cut GA — that is a separate, user-driven step per the standing directive.

---

## Spec self-review

- **Placeholder scan:** None. All sections cite file paths, type names, exact wiring.
- **Internal consistency:** L1 condition signature (`getBeanNamesForType` with `includeNonSingletons=true, allowEagerInit=false`) matches Spring's recommended pattern for `REGISTER_BEAN` phase. S1 reuse of the existing RC13 `ConnectionListener` is consistent with §2 architecture in the RC13 spec.
- **Scope:** 2 items, file-disjoint, well-bounded. Single short RC.
- **Ambiguity:**
  - L1 location for the Condition class: prefer the **cluster module** (`cluster.spi` or `cluster.support`)
    since that's where the SPI interfaces it inspects live, but the auto-config module is acceptable if
    the convention prefers it. **Implementer chooses based on existing patterns** (likely `cluster.support`
    if such a package exists; otherwise next to the SPI interfaces).
  - Test (ii) "only SessionRegistry overridden" — implementer may also add cases (iv) only-ClusterBroker,
    (v) only-Heartbeat, (vi) only-Reaper for parity, but it's not required (one partial-override case is
    sufficient to prove the condition logic).
- **Compatibility:** Backward compat claims explicit in §4.
