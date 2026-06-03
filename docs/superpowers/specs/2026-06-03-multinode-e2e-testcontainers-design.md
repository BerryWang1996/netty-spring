# Multi-Node Cluster E2E + Testcontainers CI — Design Spec

> Date: 2026-06-03 · Target: **1.9.0 cycle** (develops on `1.9.0-RC4`; cuts `v1.9.0-RC5`) · Status: design approved (delegated)
> Builds on: RC1 reliability + RC2 reliable broadcast + RC3 HMAC auth + RC4 Micrometer metrics.

## Overview

The WebSocket cluster's behavior is, today, **never validated in CI**. `.github/workflows/ci.yml` runs `mvnw test` on `ubuntu-latest` with **no Redis**, so all three cluster integration tests (`RedisIntegrationTest`, `ReliableBroadcastIntegrationTest`, `ClusterAuthIntegrationTest`) hit their `Assumptions.assumeTrue(redisAvailable)` guard and **silently skip**. Cross-node behavior is exercised only on the maintainer's machine against a manually-run Redis on `localhost:16379`.

This feature closes that gap and adds a true end-to-end proof:

1. **Testcontainers-Redis** — a shared resolver makes the cluster integration tests run **wherever Docker exists** (CI runners included) while preserving the fast local loop against a manually-run Redis. No more silent CI skips.
2. **One headline multi-node E2E test** — two **full in-process Spring Boot nodes** (real Netty WebSocket servers + `ClusterMessageSender` + Redis-backed broker/registry), HMAC on, sharing one Redis, with a real WebSocket client. It proves the complete `MessageSender → broker → registry → live session` path works **across nodes** — broadcast + unicast — and that a `netty.cluster.*` metric increments on the real path.

Scope is deliberately **CI confidence**, not a runnable Docker demo (no demo Dockerfile / compose / load balancer) and not container-per-node E2E. Additive: test sources + test-scoped deps + docs only; zero `src/main` change.

## Goals

- The 3 existing cluster integration tests **run (not skip)** in CI on every push.
- Preserve the maintainer's fast local loop: when a Redis is already reachable (manual `localhost:16379` or a `CLUSTER_TEST_REDIS_URI` override), use it; only spin a container when no Redis is reachable.
- A single, reliable, in-process **two-node E2E** test that proves cross-node broadcast + unicast over real WebSocket connections, with HMAC enabled and a metric assertion.
- Graceful on a machine with neither a reachable Redis nor Docker: skip (never fail the build).

## Non-goals

- A runnable multi-node **demo** (Dockerfile for the demo app, `docker-compose`, sticky-session load balancer, browser walkthrough). Deferred — separate, heavier deliverable.
- **Container-per-node** E2E (building the app image and running N app containers in the test). Too slow/flaky for CI; rejected.
- Re-proving reliable-broadcast **replay-on-resync** at the full-stack E2E level — already proven at the broker level by `ReliableBroadcastIntegrationTest`; adding offline/online node cycling to a 2-node full-stack test would only add flake.
- Changing any production (`src/main`) behavior, SPI, or config.

## Architecture

### 1. Testcontainers-Redis resolver (`ClusterTestRedis`)

A small shared test-support class (no JUnit-extension magic) that resolves a usable Redis **once per JVM** (singleton), in this order:

1. Env `CLUSTER_TEST_REDIS_URI` set **and** reachable (PING) → use it.
2. `redis://localhost:16379` reachable (PING) → use it. *(preserves today's local loop)*
3. Docker available → start a Testcontainers singleton `redis:7-alpine` (exposed 6379) → use its mapped `redis://host:port`. *(makes CI run them)*
4. None of the above → not available.

API (used by every cluster integration test):
- `static boolean available()` — true if 1–3 resolved; false otherwise. Tests guard with `Assumptions.assumeTrue(ClusterTestRedis.available(), "no Redis and no Docker")`.
- `static String uri()` — the resolved `redis://…` (resolving lazily on first call).
- `static RedisClient newClient()` — a Lettuce client for `uri()`.
- `static void wipe(RedisClient client)` (or a connection-taking variant) — flush the cluster keyspaces between tests (the ITs already do bespoke wipes; keep their existing wipe logic, just point it at `uri()`).

Lifecycle: the Testcontainers container (case 3) is a **static singleton, started once and never explicitly stopped** — Testcontainers' Ryuk reaper removes it at JVM exit (the documented "singleton container" pattern). Startup is paid at most once per module test run (~2–5s, redis:alpine).

**Retrofit the 3 existing ITs** (`RedisIntegrationTest`, `ReliableBroadcastIntegrationTest`, `ClusterAuthIntegrationTest`): replace each one's hardcoded `private static final String REDIS_URI = "redis://localhost:16379"` + `@BeforeAll check()` ping-probe with `ClusterTestRedis`. Their `assumeTrue(redisAvailable, …)` calls become `assumeTrue(ClusterTestRedis.available(), …)`; their `RedisClient.create(REDIS_URI)` becomes `ClusterTestRedis.newClient()` (or they read `ClusterTestRedis.uri()`). **All existing assertions stay byte-for-byte identical** — this is pure test-infra plumbing.

`NettyWebSocketClusterConfigureTest` (in the starter module) also hardcodes `redis://localhost:16379`; it is retrofitted the same way so its enabled/reliable/auth/metrics context tests run in CI too.

### 2. Multi-node E2E (`ClusterMultiNodeE2ETest`, starter module)

Lives in `netty-websocket-cluster-spring-boot-starter/src/test` — the only place with the **full stack** on the classpath (Netty server auto-config via `netty-web-spring-boot-starter`, cluster auto-config via this starter, actuator + micrometer from RC4).

**Node startup.** A static `@SpringBootApplication` test app (`E2ETestApp`) importing a test WS controller. The test starts **two** nodes via `new SpringApplicationBuilder(E2ETestApp.class).properties(…).run()` — mirroring the existing `DemoApplicationSmokeTest` pattern — each with:
- a distinct `server.netty.port` (two free ports pre-picked via `new ServerSocket(0)` like `NettyServerBootstrapConfigureTest.findAvailablePort()`; explicit ports so the client knows where to connect),
- `server.netty.websocket.cluster.enable=true`, `…cluster.redis.uri=<ClusterTestRedis.uri()>`, distinct `…cluster.node-id`,
- `…cluster.auth.enable=true` + a shared `…cluster.auth.secret` (≥32 chars) — so the whole E2E exercises the **signed** cross-node path (proves RC3),
- a `SimpleMeterRegistry` `@Bean` in `E2ETestApp` so `NettyClusterMeterBinder` (RC4) binds deterministically and the metric is assertable.
Both `ConfigurableApplicationContext`s are closed in a `finally` (ports/threads released; no leak between methods). Redis is wiped between methods.

**Test WS controller** (in `E2ETestApp`): a `@MessageMapping("/ws/e2e", …)` with an `ON_HANDSHAKE` handler that records the connecting `MessageSession`'s id (so a unicast target is known) and a `TEXT_MESSAGE` handler (echo/no-op). The client connects with the JDK's built-in `java.net.http.HttpClient.newWebSocketBuilder()` (no new dependency) to `ws://localhost:<portA>/ws/e2e`.

**Headline assertions** (poll with generous deadlines like the existing ITs — `while (… ) Thread.sleep(50)` up to ~5–8s):
- **`broadcastFromNodeBReachesClientOnNodeA`** — client connects to node A; node B's `MessageSender` bean does `topicMessage("/ws/e2e", text("hello-from-B"))`; the A-connected client **receives** the frame. Then assert node B's `MeterRegistry` shows `netty.cluster.broadcast.published` ≥ 1 (ties RC4 into the real path).
- **`unicastFromNodeBRoutesToSessionOnNodeA`** — client connects to node A (its session id captured by A's handshake handler, read from node A's context); node B does `sendMessage("/ws/e2e", text("dm"), <that session id>)`; the registry resolves the session lives on A; the A-connected client **receives** the unicast (proves cross-node routing).

Both methods run with HMAC enabled, so a passing test also proves cross-node signing/verification end-to-end. Foreign-secret rejection stays covered by `ClusterAuthIntegrationTest` (broker level) — not duplicated here.

### 3. Dependencies

- Add `org.testcontainers:testcontainers` (core, for `GenericContainer`) — test scope, **no explicit version** — to `netty-spring-websocket-cluster` and `netty-websocket-cluster-spring-boot-starter`. Spring Boot 2.7.x's BOM does **not** manage testcontainers (that began in Boot 3.1), so the parent imports `testcontainers-bom` (a `<testcontainers.version>` property) in `dependencyManagement`, mirroring its existing `netty-bom` / `spring-framework-bom` imports; the child test deps then stay version-free. (TEST scope only — never on a main/runtime classpath.)
- `ClusterTestRedis` is **duplicated** in both modules' test trees (cluster module package `…cluster`, starter package `…boot.configure`). A Maven `test-jar` was considered for sharing but rejected: its goal binds to the `package` phase, and the project's CI/local workflow is `mvn test` (which stops before `package`), so a test-jar dependency would make the starter's tests unresolvable. A ~60-line duplicated test util is the lower-risk choice and keeps `mvn test` green.

### 4. CI impact

**No `services:`/Redis block is added to `ci.yml`** — Testcontainers uses the runner's own Docker (present on `ubuntu-latest`). The only observable change is that the previously-skipped cluster ITs now execute. A short comment is added near the `Run tests` step documenting that the cluster ITs self-provision Redis via Testcontainers (so a future maintainer doesn't "helpfully" add a Redis service and wonder why it's unused). Optionally bump the test job's checkout to keep Docker available (default behavior; no action needed).

## What this proves (and where each guarantee is covered)

| Guarantee | Covered by (after RC5) |
| --- | --- |
| Cross-node **broadcast** to a live WS session | `ClusterMultiNodeE2ETest` (full stack) + broker-level ITs |
| Cross-node **unicast** routing to a remote session | `ClusterMultiNodeE2ETest` |
| **HMAC** signed path across nodes (RC3) | `ClusterMultiNodeE2ETest` (auth on) + `ClusterAuthIntegrationTest` (foreign-secret reject) |
| Reliable **replay-on-resync** (RC2) | `ReliableBroadcastIntegrationTest` (now runs in CI) |
| `netty.cluster.*` **metrics** on the real path (RC4) | `ClusterMultiNodeE2ETest` metric assertion + `NettyClusterMeterBinderTest` |
| All of the above **in CI**, every push | Testcontainers-Redis resolver |

## Test plan & risk

- **Flake control:** in-process (no container-per-node); explicit context shutdown in `finally`; pre-picked free ports; bounded-wait polling with generous deadlines; Redis wiped between methods.
- **Container startup cost:** one `redis:7-alpine` per module test run (singleton), ~2–5s in CI — acceptable.
- **Two Spring contexts in one JVM:** ensure clean close to avoid port/thread leaks; the existing `DemoApplicationSmokeTest` proves a single full app starts cleanly in a test — this extends it to two.
- **WS handshake/port mechanics:** grounded in `DemoApplicationSmokeTest` (`SpringApplicationBuilder().run("--server.netty.port=…")`) and `NettyServerBootstrapConfigureTest` (`@MessageMapping`, `findAvailablePort()`). The plan pins the exact WS path/query and session-id capture against `MessageMappingResolver`/`MessageSession`.
- **Docker-less machine:** `ClusterTestRedis.available()` returns false → every cluster IT (and the E2E) skips gracefully; non-cluster modules unaffected.

## Backward compatibility

Purely additive and test-only. No `src/main`, SPI, config, or wire-format change. New deps are test-scoped (+ a test-jar execution + a parent BOM import). The only behavioral change is in CI: cluster ITs now run instead of skipping.

## Versioning

Part of the 1.9.0 cycle (RC line). Develops on `1.9.0-RC4`; completing it cuts **`v1.9.0-RC5`**. Final `1.9.0` only when the user confirms the cycle is done.

## Files (for the plan)

- **New:** `netty-spring-websocket-cluster/src/test/java/.../cluster/ClusterTestRedis.java` (resolver) + a self-test; the same `ClusterTestRedis.java` **duplicated** at `netty-websocket-cluster-spring-boot-starter/src/test/java/.../boot/configure/`; `netty-websocket-cluster-spring-boot-starter/src/test/java/.../ClusterMultiNodeE2ETest.java` (+ its inner `E2ETestApp` + test WS controller).
- **Modified (test-only):** `RedisIntegrationTest`, `ReliableBroadcastIntegrationTest`, `ClusterAuthIntegrationTest`, `NettyWebSocketClusterConfigureTest` (retrofit to `ClusterTestRedis`).
- **Modified (poms):** `netty-spring-websocket-cluster/pom.xml` + `netty-websocket-cluster-spring-boot-starter/pom.xml` (each: `org.testcontainers:testcontainers` test dep, no version — Boot-managed). No parent/BOM/test-jar change.
- **Modified (CI/docs):** `.github/workflows/ci.yml` (clarifying comment); `docs/release-notes-1.9.0.md` (RC5 section + test count), `docs/cluster-design.md` + `docs/development-plan.md` + `docs/release-checklist.md` (move "多节点 demo + Testcontainers" item to ✅ for the Testcontainers/E2E half; note the runnable Docker demo remains deferred).
