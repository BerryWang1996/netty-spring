# Multi-Node Docker Demo — Design Spec

> Date: 2026-06-04 · Target: **1.9.x cycle** (develops on `master` @ `v1.9.0-RC7`) · Status: design approved (delegated)
> Builds on the full cluster feature set RC1–RC7 (cross-node broadcast/unicast over Redis, HMAC, metrics, trace, Redis-Cluster client).

## Overview / Goal

A runnable, **CI-guarded** multi-node Docker demo that makes the WebSocket cluster's cross-node delivery
tangible: two app nodes behind an nginx load balancer, sharing state via Redis, with a browser chat where a
message typed in one browser (served by `node-a`) visibly appears in another browser (served by `node-b`),
stamped with its origin node. The demo turns the cluster feature set into a living, human-verifiable artifact.

**Scope is lean (confirmed):** prove cross-node **broadcast** viscerally by reusing the existing chat. Cross-node
unicast / targeted-close are NOT re-proven here — they are already gated in CI by `ClusterMultiNodeE2ETest` and
the Testcontainers integration tests. **CI integration is in scope (confirmed):** a smoke job builds the stack,
asserts cross-node delivery headlessly, and tears down.

## Scope

**In:** `docker-compose` (2 app nodes + standalone Redis + nginx LB); a `cluster` Spring profile on the existing
demo app; a minimal node-identity surface (origin-node stamp on chat payloads + a `/whoami` badge); a headless
smoke check (`smoke.js`); a CI job that runs it; a `README` walkthrough.

**Out:** cross-node private messages / a global online-user directory (the node-local nickname map is unchanged);
a Redis-**Cluster**-client variant (the demo uses **standalone** Redis — the common `RedisPubSubBroker` path);
sticky-session LB tuning (WebSocket connections self-pin to a backend for their lifetime); publishing anything to
Maven Central.

## Architecture

```
   ┌─────────┐   ┌─────────┐        two browser tabs (round-robined to different nodes)
   │ Browser │   │ Browser │
   └────┬────┘   └────┬────┘
        │  ws://localhost:8080/ws/chat
        ▼             ▼
     ┌─────────────────────┐
     │  nginx (lb)  :80     │  round-robin + WS upgrade
     └─────┬──────────┬─────┘
           ▼          ▼
   ┌──────────────┐ ┌──────────────┐
   │ node-a :8080 │ │ node-b :8080 │   Spring Boot demo, profile=cluster, NODE_ID=node-a/node-b
   └──────┬───────┘ └──────┬───────┘
          │  Redis pub/sub  │            cross-node fan-out (RedisPubSubBroker)
          ▼                 ▼
        ┌────────────────────┐
        │   redis:7  :6379   │
        └────────────────────┘
```

A WebSocket connection from a browser is upgraded at nginx and **pinned** to one backend for its lifetime (no
sticky config needed). A broadcast originated on `node-a` is delivered locally **and** published to Redis;
`node-b` receives it via its `RedisPubSubBroker` and delivers to its own browsers.

## Components

### A. Demo app changes (`demo-netty-web-spring-boot-starter`) — additive, cluster OFF by default

1. **`pom.xml`:** add dependency `io.github.berrywang1996:netty-websocket-cluster-spring-boot-starter`
   (`${project.version}`; pulls Lettuce transitively). Cluster auto-config only activates when
   `server.netty.websocket.cluster.enable=true`, so with the default profile the demo is behaviorally
   **identical to today** — no Redis required, `DemoApplicationSmokeTest` unchanged.
2. **`src/main/resources/application-cluster.properties`** (Spring profile `cluster`, activated only inside
   Docker):
   ```
   server.netty.websocket.cluster.enable=true
   server.netty.websocket.cluster.redis.uri=redis://${REDIS_HOST:redis}:6379
   server.netty.websocket.cluster.node-id=${NODE_ID:node-x}
   ```
   The default `application.properties` keeps cluster off.
3. **`ChatRoomController` — minimal additive change** (reused as-is otherwise):
   - Constructor-inject `@Autowired(required = false) ClusterNodeManager nodeManager` (present only when the
     cluster auto-config is active). Compute `String nodeId = (nodeManager != null) ? nodeManager.getNodeId() : null;`
     once and hold it.
   - When `nodeId != null`, add `event.put("originNode", nodeId)` to the **broadcast**, **join**, and **leave**
     JSON payloads (the node that *originates* the broadcast stamps itself before publish).
   - Add `@RequestMapping("/whoami")` returning a small JSON string `{"nodeId":"<id>"}` (or
     `{"nodeId":"single-node"}` when `nodeId == null`).
   - In `CHAT_HTML` JS: on connect, `fetch('/whoami')` → render a header badge `Node: <id>`; when handling a
     `message`/`join`/`leave`, if `data.originNode` is present and differs from my node, append a small
     `(via <originNode>)` tag.
   - **Single-node mode** (`nodeId == null`): `originNode` is never added and `/whoami` reports `single-node`
     → behavior byte-identical to the current demo.

   > Note: this app-level `originNode` (chat JSON, for display/proof) is independent of the cluster envelope's
   > internal `originNodeId` (used for broadcast self-delivery suppression). Do not conflate them.

### B. New `docker-demo/` directory (infra only — NOT a Maven module, mirroring `stress-test/`)

1. **`Dockerfile`** — `FROM eclipse-temurin:17-jre-alpine`; `COPY` the pre-built demo jar
   (`demo-netty-web-spring-boot-starter/target/demo-netty-web-spring-boot-starter-1.9.0-RC7.jar` → `app.jar`)
   via a build context = repo root; `ENTRYPOINT` runs `java -jar app.jar --spring.profiles.active=cluster`
   (`NODE_ID`/`REDIS_HOST` come from the container env). The jar is built by Maven first (documented in README +
   built in CI) — matching the existing `stress-test/Dockerfile.server` "COPY a prebuilt jar" idiom. Use a build
   arg for the jar path/version to avoid hard-coding the RC in two places where practical.
2. **`docker-compose.yml`** — services:
   - `redis`: `image: redis:7`; `healthcheck: redis-cli ping`.
   - `node-a`, `node-b`: `build` from the `docker-demo/Dockerfile` (context = repo root); `environment:
     NODE_ID=node-a|node-b`, `REDIS_HOST=redis`; `depends_on: redis (service_healthy)`; `healthcheck:
     wget -qO- http://localhost:8080/actuator/health` (actuator is already a demo dependency).
   - `lb`: `image: nginx:alpine`; mount `nginx.conf`; `ports: "8080:80"`; `depends_on: node-a, node-b`.
3. **`nginx.conf`** — `upstream backend { server node-a:8080; server node-b:8080; }` (round-robin) and a
   `location /` that proxies with `proxy_http_version 1.1;`, `proxy_set_header Upgrade $http_upgrade;`,
   `proxy_set_header Connection "upgrade";`, `proxy_set_header Host $host;`, and a long `proxy_read_timeout 3600s;`
   so idle WebSocket connections are not cut.
4. **`smoke.js`** — headless Node `ws` client (the CI oracle):
   - Connect `client1` (`ws://localhost:8080/ws/chat?nickname=alice`); learn its node from the `originNode` of
     its own `join` event.
   - Connect `client2` (`nickname=bob`); learn its node likewise. If both landed on the same node, reconnect
     `client2` up to K times (round-robin across 2 nodes alternates, so this converges fast); fail if it cannot
     get a distinct node.
   - From `client1`, send a broadcast chat message; assert `client2` receives a `message` whose `originNode`
     equals `client1`'s node (≠ `client2`'s node) within a timeout (e.g. 5 s).
   - Exit `0` on success, `1` on failure (printing what was/wasn't received). Pin the `ws` dependency (a tiny
     `package.json` in `docker-demo/`, or run via `npx ws`).
5. **`README.md`** — quickstart (`mvn -pl demo-netty-web-spring-boot-starter -am package -DskipTests` then
   `docker compose -f docker-demo/docker-compose.yml up --build`), the two-tab walkthrough (open
   `http://localhost:8080/chat` twice → different node badges → a message in one appears in the other tagged
   `(via node-a)`), how the proof works, a one-line note on scaling to 3+ nodes (add a service + an
   `upstream` line), and an **expectation note** that the online-user list/count is **per-node** in this lean
   demo (a global cross-node directory is intentionally out of scope) — the cross-node proof is the broadcast
   messages with their origin tags, not the roster.

### C. CI smoke job (`.github/workflows/`)

A new job (in `ci.yml` or a dedicated workflow) `docker-demo-smoke`:
- Checkout; set up JDK 17 + Maven (reuse the repo's cache pattern); `mvn -pl demo-netty-web-spring-boot-starter
  -am package -DskipTests` to produce the jar.
- Set up Node; `docker compose -f docker-demo/docker-compose.yml up -d --build`; wait for the `lb`/nodes to be
  healthy (poll `http://localhost:8080/actuator/health`).
- `node docker-demo/smoke.js` (the assertion).
- `always()`: `docker compose ... logs` on failure for debugging, then `docker compose ... down -v`.
- Trigger on PRs touching `docker-demo/**`, `demo-netty-web-spring-boot-starter/**`, or the cluster modules, plus
  pushes to `master`. Keep it a separate job so it doesn't slow the unit-test matrix.

## Cross-node proof mechanism (the crux)

Each node knows its own id (`ClusterNodeManager.getNodeId()`, fed by the `NODE_ID` env via
`cluster.node-id`). The node that **originates** a chat broadcast stamps `originNode = self` into the JSON
payload **before** `broadcastJson` publishes it. Redis fan-out carries that payload verbatim to the other node,
which delivers it to its browsers with `originNode` intact. Each browser also shows which node it is connected to
(`/whoami` badge). So a message authored on `node-a` and seen in a `node-b` browser renders as
**"Alice (via node-a)"** on a tab whose header reads **"Node: node-b"** — direct, visible proof the message
crossed nodes. `smoke.js` asserts exactly this invariant headlessly.

## Testing

- **Automated (the oracle):** `docker-demo/smoke.js` run by the CI `docker-demo-smoke` job against the live
  composed stack — fails the build if cross-node broadcast does not occur.
- **Regression:** `DemoApplicationSmokeTest` must still pass with the cluster starter on the classpath but cluster
  disabled (no cluster beans created, no Redis needed). Verify during implementation.
- **Manual:** the README two-tab walkthrough.

## Risks / mitigations

- **WS through nginx** needs upgrade headers + a long read timeout → pinned in `nginx.conf`.
- **Round-robin could co-locate two clients** → `smoke.js` reconnects `client2` until it lands on a distinct node
  (bounded retries; 2 nodes + RR converges immediately).
- **Jar path / build context** is the fiddly bit → the `Dockerfile` COPYs the Maven-built jar from a repo-root
  context; README + CI both `mvn package` first. A build arg parameterizes the version to avoid double-maintaining
  the RC string.
- **Demo enlargement** (cluster starter + Lettuce on the demo classpath) → cluster off by default keeps runtime
  behavior identical; the regression check on `DemoApplicationSmokeTest` guards it.
- **CI minutes / flake** from `docker compose` → one small, bounded, log-dumping job, separate from the unit
  matrix; teardown with `down -v` always.

## Versioning / workflow

Part of the **1.9.x cycle** (develops on `master` @ `v1.9.0-RC7`). The spec and the implementation plan are
committed to `master`; the implementation is done on a feature branch `feature/1.9.x-docker-demo` and FF-merged
back via `finishing-a-development-branch`. The Docker demo is a development/docs artifact — **not published to
Central**, **no push/deploy**. It does not, by itself, advance the RC number; whether it folds into a later RC or
the final 1.9.0 cut is a separate decision.

## Files

**New:** `docker-demo/Dockerfile`, `docker-demo/docker-compose.yml`, `docker-demo/nginx.conf`,
`docker-demo/smoke.js`, `docker-demo/package.json`, `docker-demo/README.md`;
`demo-netty-web-spring-boot-starter/src/main/resources/application-cluster.properties`; a CI job/workflow.

**Modified:** `demo-netty-web-spring-boot-starter/pom.xml` (cluster starter dep);
`.../demo/controller/ChatRoomController.java` (origin-node stamp + `/whoami` + JS badge);
`.github/workflows/ci.yml` (or a new workflow file).
