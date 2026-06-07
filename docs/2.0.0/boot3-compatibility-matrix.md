> **2.0.0 forward-looking artifact.** This document is a planning input for the
> 2.0.0 (Spring Boot 3.x) cycle and ships under `docs/2.0.0/`. **1.9.0 itself is
> unaffected** — no SPI, wire, config, or Java behavior change is induced by this
> file. The 1.9.0 code base does not read it.

# Boot 3.x Compatibility Matrix (netty-spring 2.0.0 prep)

Per-module surface analysis of the Boot 2.7.18 → Boot 3.2.x migration, backed by
mechanical grep counts. Headline finding is at the top of
[§ Per-module impact](#per-module-impact) — skim there first.

---

## JDK baseline

| Item | Current (1.9.0) | Target (2.0.0) | Delta |
|---|---|---|---|
| `java.version` (root `pom.xml`) | **17** | **17** | none |
| `maven.compiler.release` | 17 | 17 | none |
| Tested JDK | GraalVM JDK 17.0.11 | JDK 17 (Boot 3.x requires 17+) | none |

**No JDK action required.** Boot 3.x requires JDK 17 minimum; 1.9.0 already targets 17.

## Spring Boot

| Item | Current (1.9.0) | Target (2.0.0) | Notes |
|---|---|---|---|
| `spring-boot.version` (root `pom.xml`) | **2.7.18** | **3.2.x** (or current Boot 3 LTS at 2.0.0 cut) | Boot 2.7 OSS support ended 2023-11; commercial support window also closing. Boot 3.2.x is the current generally-supported line at time of writing. |
| Auto-config registration | `META-INF/spring.factories` | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | New file format added in Boot 2.7 (compat) and required in Boot 3.x. Mechanical move. |
| `@AutoConfigureAfter` | unchanged | unchanged | API surface kept across the boundary. |

**Why 3.2.x:** Boot's 6-month cadence shifts the "current LTS" alias over time;
3.2.x is the line that's stable, OSS-supported, and manages a Lettuce /
Micrometer / Netty triple consistent with what 2.0.0 needs to unlock
(see [§ Library upgrades](#library-upgrades)). Pick whichever Boot 3 line is
OSS-supported when the cycle opens — the matrix below holds regardless.

## Spring Framework

Boot 3.x rides Spring Framework 6.x; 1.9.0 currently pins
[Spring Framework 5.3.39](../../pom.xml) (overridden above Boot 2.7.18's 5.3.31 for
several CVE fixes — see the BOM comments).

| Surface | Boot 2.7 / Spring 5.3 | Boot 3.x / Spring 6.x | Code impact |
|---|---|---|---|
| `javax.servlet.*` | required for Servlet stack | **moved to `jakarta.servlet.*`** | netty-spring does **not** use Servlet (we run on raw Netty); see grep below. |
| `javax.annotation.{PostConstruct,PreDestroy}` | optional | **moved to `jakarta.annotation`** | grep shows **0 matches** in this codebase. |
| `javax.persistence.*` | optional | **moved to `jakarta.persistence`** | grep shows **0 matches**. |
| `javax.activation.MimetypesFileTypeMap` | available (via `javax.activation:activation`) | **moved to `jakarta.activation`** | 1 file affected (`ServiceHandlerUtil`). |
| `javax.crypto.*` | JDK-provided | **unchanged (still `javax.crypto`)** | JDK API, not impacted by jakarta rename. Several files import it; not a migration item. |
| `org.springframework.web.util.UriComponentsBuilder` | available | available | API kept. |
| `ApplicationContextRunner` / `@ConditionalOnBean` / `@ConditionalOnClass` / `@ConditionalOnProperty` / `@ConditionalOnMissingBean` | available | available | API kept across the 5.x → 6.x boundary; this is the dominant Spring surface netty-spring uses. |

---

## Per-module impact

Counts come from this codebase, current branch:

```
grep -rl "javax\." --include="*.java" <module>     # files containing any javax.* import
grep -rl "@PostConstruct\|@PreDestroy" --include="*.java" <module>
```

**Headline finding: 6 Java files in the entire reactor contain a `javax.*`
import. Five are `javax.crypto.*` (JDK API, unaffected by jakarta rename). One
is `javax.activation.MimetypesFileTypeMap` (single import, single replacement).
Zero `@PostConstruct` / `@PreDestroy`. Zero `javax.servlet`. Zero `javax.annotation`.
Zero `javax.persistence`.** netty-spring runs on raw Netty and never depended on
the Servlet API — the jakarta sweep that dominates most Boot 3.x migrations is
essentially a one-line change here.

| Module | Java files | `javax.*` files | Jakarta-impacted (subset) | Spring API surface impact | Dependency-version changes | Effort |
|---|---:|---:|---|---|---|---|
| `netty-spring-web` | 35 | **1** | `ServiceHandlerUtil.java` (1 import: `javax.activation` → `jakarta.activation`) | None (no Spring imports in `util.*`) | activation:1.1.1 → jakarta.activation-api:2.1.x | **xs** |
| `netty-spring-webmvc` | 35 | 0 | none | Stable subset (`@RequestMapping` is netty-spring's own annotation, not Spring's; `AnnotatedElementUtils`, `AliasFor`, `PathMatcher` are 5.x→6.x stable) | none | **xs** |
| `netty-spring-websocket` | 38 | **3** | `AesGcmMessageCryptoCodec`, `MessageCryptoKeyProvider`, `AesGcmMessageCryptoCodecTest` — all `javax.crypto.*` (JDK API, **no rename**) | None (autoconfigure / context APIs only) | Jackson 2.13.x → 2.15.x (Boot-managed; ABI-compatible at our usage level) | **xs** |
| `netty-spring-websocket-cluster` | 80 | **1** | `HmacMessageAuthenticator.java` — `javax.crypto.Mac` (JDK API, **no rename**) | None | Lettuce 6.1.10 → 6.3.x (Boot-managed) — see [§ Library upgrades](#library-upgrades). Micrometer 1.9.x → 1.12.x. jnats unchanged. | **s** (Lettuce-driven, not jakarta-driven) |
| `netty-spring-boot-autoconfigure` | 12 | 0 | none | `AutoConfigureAfter`, `ConditionalOnClass`, `ConditionalOnBean`, `ConditionalOnProperty`, `ConditionalOnMissingBean`, `EnableConfigurationProperties`, `ConfigurationProperties` — all 5.x→6.x stable. **One `spring.factories` → `AutoConfiguration.imports` move per starter that registers an auto-config.** | none | **xs** |
| `netty-web-spring-boot-starter` | (POM only) | 0 | none | `spring.factories` → `AutoConfiguration.imports` if it registers an autoconfig | Inherits parent | **xs** |
| `netty-webmvc-spring-boot-starter` | (POM only) | 0 | none | same | Inherits parent | **xs** |
| `netty-websocket-spring-boot-starter` | (POM only) | 0 | none | same | Inherits parent | **xs** |
| `netty-websocket-cluster-spring-boot-starter` | (POM only) | 0 | none | same | Inherits parent | **xs** |
| `demo-netty-web-spring-boot-starter` | varies | **1** | `WebSocketCryptoDemoConfiguration.java` — `javax.crypto.SecretKey` / `javax.crypto.spec.SecretKeySpec` (JDK API, **no rename**) | None | none | **xs** |
| parent `pom.xml` | n/a | n/a | n/a | n/a | Boot 2.7.18 → 3.2.x; Spring BOM override 5.3.39 → 6.1.x (or drop entirely if Boot 3 LTS pins a clean Spring 6.x line) | **s** (BOM coordination) |

**Effort legend.** xs ≤ 1 hour; s = half-day; m = 1-2 days; l = ≥ 3 days (coding
hours, not wall-clock).

**Total estimated coding effort: ~1 day** for the migration sweep itself,
dominated by Lettuce 6.3 + parent-POM BOM coordination, not by jakarta rewrites.
Deferred-item work (sharded pub/sub, Observation API) is the bulk of the 2.0.0
cycle's actual scope — see [§ Cycle shape](#estimated-cycle-shape).

---

## Library upgrades

| Library | Current (Boot 2.7.18 BOM) | Target (Boot 3.2.x BOM) | Why we care |
|---|---|---|---|
| **Lettuce** | 6.1.10 | 6.3.x | **Unlocks sharded pub/sub** (`SSUBSCRIBE` / `SPUBLISH`, introduced in Lettuce 6.2). See sibling doc `sharded-pubsub-feasibility.md` for the Track P design. Required for the M·(f·N-1) fan-out wall fix. |
| **Micrometer Core** | 1.9.x | 1.12.x | Stable API surface; cluster meter binder ports cleanly. |
| **Micrometer Observation API** | absent (pre-1.10) | 1.12.x | **Unlocks the RC6-deferred Observation-based trace continuation** (currently MDC-only via `ClusterTraceContext`). Brave / OTel propagation becomes a config switch instead of a custom SPI. |
| **Micrometer Tracing** | n/a | 1.2.x | Companion to Observation API for trace continuation. |
| **Jackson** | 2.13.x (Boot 2.7) | 2.15.x (Boot 3.2) | netty-spring uses `ObjectMapper` in the crypto codec only. ABI-compatible at our usage level. |
| **Netty** | 4.1.134.Final (root-pinned) | unchanged (4.1.x) | We pin via netty-bom directly, not via Boot. No upgrade forced. |
| **jnats** | 2.20.4 (root-pinned) | unchanged | Independent of Boot. |
| **Lombok** | 1.18.38 (root-pinned) | unchanged | Independent of Boot. |
| **Testcontainers** | 1.20.4 (root-imports BOM) | Boot 3.1+ **manages testcontainers in its BOM** — drop our explicit BOM import. | Tidier dependency-management section. |
| **logback** override | 1.2.13 | likely **drop** — Boot 3 BOM ships 1.4.x with the CVE fix natively | Smaller override surface in parent POM. |
| **snakeyaml** override | 1.33 | likely **drop** — Boot 3 BOM ships 2.x with the DoS fixes | Smaller override surface in parent POM. |

---

## Per-feature 2.0.0 unlocks

What the platform upgrade buys (mapped to deferred items in `cluster-design.md`
and per-RC release notes):

1. **`RedisShardedPubSubBroker`** (Lettuce 6.2+ / Redis 7.0+) — the M·(f·N-1)
   fan-out wall fix. See `sharded-pubsub-feasibility.md` for the design.
2. **Observation-API trace continuation** (Micrometer 1.10+ `ObservationRegistry`)
   — replaces RC6's MDC-only `traceparent` propagation with a properly-modelled
   cross-node observation that Brave / OTel collectors pick up automatically.
   The current `ClusterTraceContext` SPI stays as an additive override.
3. **`AutoConfiguration.imports` registration** — mechanical move onto the
   registration mechanism Boot 3 mandates.
4. **Optional:** drop `javax.activation:activation` for
   `jakarta.activation:jakarta.activation-api:2.1.x` — one import rewrite.

**Still deferred** (not in this cycle): Redis Cluster `RedisClusterModeReliableBroker`
(RC7 shipped pub/sub + registry + heartbeat, not reliable / Streams); mesh-grade
fan-out beyond sharded pub/sub; multi-region active-active.

---

## Estimated cycle shape

Speculative — final cycle shape is decided when the user opens the 2.0.0 cycle.

| RC | Theme | Touches |
|---|---|---|
| **2.0.0-RC1** | Baseline migration | Root POM: Boot 2.7.18 → 3.2.x; Spring BOM override re-evaluated; drop logback/snakeyaml overrides if Boot 3 BOM is clean; `spring.factories` → `AutoConfiguration.imports`; `javax.activation` → `jakarta.activation` (1 import); full reactor green. |
| **2.0.0-RC2** | Jakarta/Spring 6 sweep + library bumps | Lettuce 6.1 → 6.3; Micrometer 1.9 → 1.12; Jackson 2.13 → 2.15; Testcontainers BOM dropped (Boot 3 manages it); all 444 existing tests green; no behavior changes. |
| **2.0.0-RC3** | Sharded pub/sub | `RedisShardedPubSubBroker` impl behind `pubsub-mode=sharded`; Lettuce 6.2 `SSUBSCRIBE`/`SPUBLISH`; new 6-master Testcontainers Redis Cluster resolver; fan-out reduction tests. (See Track P doc.) |
| **2.0.0-RC4** | Observation API trace continuation | `ObservationClusterTraceContext` impl on Micrometer 1.10+ `ObservationRegistry`; gated by `trace-propagation=observation` (default still `mdc` for parity until RC5+); Brave + OTel test matrices. |
| **2.0.0-RC5** | Audit + adversarial review | Like the 1.9.0 RC17 audit: 4-way parallel code audit, pre-GA fixes, docs polish. |
| **2.0.0-RC6 (optional)** | Polish + final docs | Migration guide finalization (the DRAFT in Track G becomes definitive), release notes, README updates. |
| **2.0.0 GA** | Cut | Final cut + Central deploy. |

**Range: 5-7 RCs**, matching the 1.9.0 cycle shape (1.9.0 was RC1..RC19 but the
tail was deferred-item driven; the migration cycle itself should be tighter).

### Top technical risks

1. **Lettuce 6.3 RESP3 default.** Lettuce switched RESP version defaults across
   6.x minors; the RC7 `RedisClusterModePubSubBroker` needs an end-to-end test
   against a production-shaped Redis to confirm no protocol-level surprise.
2. **Testcontainers BOM coordination.** If Boot 3.2's BOM pins Testcontainers
   earlier than what `ClusterTestRedisCluster` needs, the parent must override
   (same pattern as today; just which side is the override flips).
3. **`AutoConfiguration.imports` is per-module.** 5 starter / autoconfigure
   modules each get their own new file. Mechanically trivial — easy to miss one
   and have a silently-not-loaded auto-config.
4. **Spring BOM override.** Parent currently pins Spring 5.3.39 above Boot
   2.7.18's 5.3.31. Boot 3.2.x's Spring 6.x is likely current enough to drop
   the override; verify against Spring 6 CVE history when the cycle opens.

### Test-infrastructure changes

- `@SpringBootTest` slice + `ApplicationContextRunner` carry over; 444 existing
  tests should compile against Boot 3 with no source changes other than the
  jakarta sweep (which doesn't touch tests — the one test file containing
  `javax.*` is `AesGcmMessageCryptoCodecTest`, JDK `javax.crypto`, no rename).
- Mockito version: spot-check during RC1 if Boot 3 BOM pins something incompatible.
- Testcontainers gating on Docker availability is independent of Boot version.

---

> **Reminder:** this document is a 2.0.0 planning artifact. 1.9.0 GA-readiness
> (as certified by the RC17 audit) is unaffected. The decision to open the
> 2.0.0 cycle is orthogonal to the 1.9.0 GA cut.
