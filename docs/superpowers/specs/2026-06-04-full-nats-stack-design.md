# Full NATS Stack (NATS-only registry) — Design Spec

> Date: 2026-06-04 · Target: **1.9.x cycle** (develops on `master` @ `92619c3`; cuts `v1.9.0-RC10`) · Status: design approved (delegated)
> Completes the "choose your middleware" story: all-NATS / mixed / all-Redis. Builds on RC9's `NatsClusterBroker`. **Revises ADR-001** (NATS-only becomes an opt-in).

## Overview / Goal

Three additive NATS **JetStream-KV** implementations of the remaining cluster SPIs — `SessionRegistry`,
`ClusterNodeHeartbeat`, `ClusterReaper` — so a deployment can run **NATS-only, with no Redis at all**. Selected by
an explicit `nats.registry` flag. This is an **operational** convenience (one middleware instead of two for
NATS-committed shops), **not** a performance feature — the registry was never the scaling bottleneck (the
broadcast fan-out wall was, and that is already on NATS as of RC9).

The three deployment shapes:
- **all-Redis** (`nats.servers` empty): everything on Redis (standalone or cluster) — unchanged.
- **mixed** (`nats.servers` set, `nats.registry=false`, the RC9 default): NATS broker + Redis registry.
- **all-NATS** (`nats.servers` set, `nats.registry=true`): NATS broker + NATS-KV registry/heartbeat/reaper; **no Redis**.

**⚠️ Operational constraint:** all-NATS requires a **JetStream-enabled NATS server** (`nats-server -js`). Mixed
mode (RC9) needs only core NATS. Documented prominently.

## Scope

**In:** `NatsKvSessionRegistry`, `NatsKvNodeHeartbeat`, `NatsKvReaper`; a JetStream KV connection + bucket
bootstrap; the `nats.registry` selector; auto-config re-gating across the 5-deployment matrix; ADR-001 update;
JetStream Testcontainers resolver + ITs + a context test + Mockito unit tests; docs.

**Out:**
- **JetStream reliable broadcast** (at-least-once over JetStream streams) — separate future, parallels
  `RedisStreamsReliableBroker`. `reliable.enable` stays Redis-only (unsupported in all-NATS — documented).
- **Refactoring RC9's broker** to share a connection — deliberately NOT done (see Architecture); RC9 stays as-is.
- **Throughput assertions** — the testable invariant is KV round-trip + expiry/claim correctness.

## Architecture

`ClusterMessageSender` + `ClusterNodeManager` depend only on the SPIs, so swapping the registry/heartbeat/reaper
beans to NATS-KV is transparent. **Two NATS connections in all-NATS mode (deliberate):**
- RC9's `NatsClusterBroker` keeps owning its **own core-pub/sub connection** (unchanged — it is fully `attach`ed
  when its bean method returns, so there is no init-order race; sharing one connection would force the broker to
  be attached by a later bean → the RC5-class ordering fragility, not worth it).
- A **separate JetStream connection** (`nettyClusterNatsKvConnection`) serves the KV registry. NATS connections
  are cheap/multiplexed; two per node is fine.

Both connect to the same (JetStream-enabled) NATS server; the broker uses core pub/sub, the registry uses KV.

## Components

### `NatsKvSessionRegistry implements SessionRegistry`
Bucket `netty-sessions` (KV). Keys are NATS-KV-safe (`[-/_=.a-zA-Z0-9]`; uri is **base64url-encoded** to avoid
`.`/illegal chars).
- `register(uri, sessionId, nodeId, metadata)` → `put("s." + b64(uri) + "." + sessionId, nodeId)` **+** a
  node-membership key `put("n." + b64(nodeId) + "." + b64(uri) + "." + sessionId, "")` (for `removeAllForNode`; `.` separators only — `|` is NOT a legal NATS-KV key char, and `nodeId` is base64url-encoded so its prefix split is unambiguous).
- `lookupNode(uri, sessionId)` → `get("s." + b64(uri) + "." + sessionId)` → nodeId (or null).
- `deregister(uri, sessionId)` → read the owning nodeId, then `delete` the session key + the node-membership key
  (non-atomic, three KV ops — same trade-off as Redis-Cluster mode; the race is theoretical under UUID sessionIds).
- `clusterSessionIds(uri)` → `keys()` filtered by `"s." + b64(uri) + "."` prefix → extract sessionIds.
- `removeAllForNode(nodeId)` → `keys()` filtered by `"n." + b64(nodeId) + "."` → delete each session key + membership key.
- `shutdown()` — no-op (connection owned by auto-config).

### `NatsKvNodeHeartbeat implements ClusterNodeHeartbeat`
**Single** bucket `netty-nodes` (KV, no maxAge) — **timestamp-based liveness** (robust; no reliance on NATS KV
purge timing):
- `register(nodeId, timeoutMs)` / `renewHeartbeat(nodeId, timeoutMs)` → `put(nodeId, String.valueOf(now))`.
- `deregister(nodeId)` → `delete(nodeId)`.
- `findExpiredNodes(timeoutMs)` → read all entries (`keys()` + `get`), return nodeIds where
  `now − Long.parseLong(value) > timeoutMs`. (Faithful to the Redis impl, whose nodes-hash timestamp check is its
  primary expiry signal. Dead nodes' keys linger until reaped via `removeAllForNode`/`deregister` — exactly the
  cluster's reconcile→reap→cleanup flow.)

### `NatsKvReaper implements ClusterReaper`
Bucket `netty-reaping` (KV, **maxAge = claim window**, bounded + non-critical):
- `tryClaim(deadNodeId, reaperNodeId, claimWindowMs)` → `create("r." + deadNodeId, reaperNodeId)` (KV `create` =
  atomic create-if-absent = the `SET NX` analog); success → won. On a KV "key exists" error → lost (`false`). On
  any other error → reap anyway (`true`, cleanup is idempotent — matches `RedisClusterReaper`).
- The `maxAge` lets a stale claim expire so a node can be re-reaped later; a slight purge lag is harmless.

### JetStream connection + bucket bootstrap
- `nettyClusterNatsKvConnection` bean (`@ConditionalOnClass(io.nats.client.Connection)` + `@ConditionalOnExpression(ALL_NATS)`,
  `destroyMethod="close"`): `Nats.connect(Options.builder().server(nats.servers)…build())` (JetStream available
  because the server runs `-js`).
- Bucket bootstrap: on creation of the registry/heartbeat/reaper beans (or a shared init), ensure the 3 KV buckets
  exist via `connection.keyValueManagement().create(KeyValueConfiguration…)` (idempotent — create-if-absent;
  `netty-reaping` with `maxAge`). The `KeyValue` handles (`connection.keyValue("netty-sessions")` etc.) are passed
  to the impls.

### Auto-config — the 5-deployment matrix (riskiest part; order-independent SpEL)
New constants: `NATS_REGISTRY = "'${server.netty.websocket.cluster.nats.registry:false}' == 'true'"`;
`ALL_NATS = NATS_TRANSPORT + " and " + NATS_REGISTRY`; `NOT_ALL_NATS = "!(" + ALL_NATS + ")"`.
- **Append `&& NOT_ALL_NATS`** to every Redis SPI/infra bean: `nettyClusterRedisClient`, `nettyClusterRedisConnection`,
  `sessionRegistry`, `clusterNodeHeartbeat`, `clusterReaper`, the RC7 cluster-mode variants
  (`nettyClusterRedisClusterClient`, `nettyClusterRedisClusterConnection`, `sessionRegistryCluster`,
  `clusterNodeHeartbeatCluster`, `clusterReaperCluster`), and `reliableBroker`.
- **Add 3 NATS-KV beans** on `@ConditionalOnExpression(ALL_NATS)` + `@ConditionalOnClass(Connection)` +
  `@ConditionalOnMissingBean(<SPI>)`: `SessionRegistry natsKvSessionRegistry`, `ClusterNodeHeartbeat natsKvNodeHeartbeat`,
  `ClusterReaper natsKvReaper` (each taking the KV connection / a KV handle).
- Verified truth table (exactly one of each SPI bean wins): {all-Redis-standalone, all-Redis-cluster,
  mixed-standalone, mixed-cluster, all-NATS}. `clusterNodeManager` (requires heartbeat+registry+reaper) is
  satisfied in every case. The RC9 broker bean (`NATS_TRANSPORT`) already activates for both mixed + all-NATS — unchanged.

### Config
`ClusterProperties.Nats.registry` (boolean, default `false`) + accessor. Javadoc: when `true` (and `nats.servers`
set), the registry/heartbeat/reaper run on NATS JetStream KV (no Redis) — requires a JetStream-enabled NATS server.

## Testing

- **Integration — JetStream NATS Testcontainers (`ClusterTestNatsJetStream` resolver):** mirror `ClusterTestNats`
  (env `CLUSTER_TEST_NATS_JS_URL` → Testcontainers `nats:2.10` with the **`-js`** command arg → assumeTrue-skip;
  include the `api.version=1.43` static initializer). `NatsKvIntegrationTest`: registry register/lookup/deregister
  round-trip; heartbeat register → wait → `findExpiredNodes` detects the stale node + a renewed node is excluded;
  reaper claim-once single-winner (two reapers, exactly one wins). **The IT is the oracle** — the jnats
  `KeyValue`/`KeyValueManagement`/`KeyValueConfiguration` API is verified empirically against the jar; fix the API
  until the round-trips pass, never weaken.
- **Context test (`NettyWebSocketClusterConfigureTest`):** with `nats.servers` + `nats.registry=true` (gated on the
  JetStream resolver), assert `SessionRegistry` is `NatsKvSessionRegistry`, `ClusterNodeHeartbeat` is
  `NatsKvNodeHeartbeat`, `ClusterReaper` is `NatsKvReaper`, the `ClusterBroker` is `NatsClusterBroker`, and
  **`doesNotHaveBean(RedisClient.class)`** (truly Redis-free). Duplicate `ClusterTestNatsJetStream` into the starter
  test sources (mirroring the `ClusterTestNats` duplication).
- **Unit (Mockito):** mock the `KeyValue` handle; assert `register`→`put` on the right keys, `deregister`→read+delete
  (no atomic op), `findExpiredNodes` timestamp logic, reaper `create`→won/lost. (`KeyValue` is a jnats interface →
  mockable.)

## Backward compatibility

Purely additive + opt-in. `nats.registry` default `false` ⇒ RC9 behavior (mixed) and all-Redis are byte-identical.
No SPI change. The NATS-KV beans only exist with jnats on the classpath **and** `nats.servers` set **and**
`nats.registry=true`. No change to RC9's broker or the Redis impls.

## ADR-001 update

Reframe the "NATS-first / NATS-only" rejection: NATS-only is now an **opt-in** (`nats.registry=true`) for shops that
prefer a single (JetStream-enabled) middleware; **mixed** (NATS broker + Redis registry) and **all-Redis** remain
the defaults. The rationale stands — the registry was never the scaling wall, so all-NATS is operational, not
performance — but it is available rather than forbidden. Update the cluster-design.md scope table row + ADR-001 text.

## Versioning / workflow

1.9.x cycle (master @ `92619c3`). Spec + plan on `master`; implementation on a feature branch
`feature/1.9.x-full-nats`, FF-merged via `finishing-a-development-branch`. No push/deploy. Completing it cuts
**`v1.9.0-RC10`**.

## Files

**New (main):** `…cluster/nats/NatsKvSessionRegistry.java`, `NatsKvNodeHeartbeat.java`, `NatsKvReaper.java`.
**New (test):** `…cluster/nats/Nats Kv*Test.java` (Mockito); `…cluster/ClusterTestNatsJetStream.java` +
`NatsKvIntegrationTest.java`; starter `…boot/configure/ClusterTestNatsJetStream.java`.
**Modified (main):** `ClusterProperties.java` (`Nats.registry`); `NettyWebSocketClusterConfigure.java`
(`ALL_NATS`/`NOT_ALL_NATS` constants, `&& NOT_ALL_NATS` on the Redis beans, the KV connection + 3 NATS-KV beans).
**Modified (test):** starter `NettyWebSocketClusterConfigureTest.java`.
**Docs:** `cluster-design.md` (scope row + ADR-001), `api-guide.md` (`nats.registry` row),
`additional-spring-configuration-metadata.json`; release-notes at the RC10 cut.
