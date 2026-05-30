# 1.9.0 Cluster Reliability Hardening — Design Spec

> Date: 2026-05-30 · Target version: `1.9.0` · Status: approved (design), pending spec review
> Theme chosen over: reliable delivery (at-least-once), HMAC auth, observability, scale-out — each a separate later release.

## Overview

1.8.0 shipped WebSocket cluster support (Redis Pub/Sub + 5-layer SPI) and is production-grade
for **≤ ~10 nodes with a dedicated, secured Redis**. The 1.8.0 pre-release review explicitly
deferred a set of resilience/correctness items to 1.9.x. **1.9.0 finishes those deferrals** so the
existing Redis cluster is bulletproof within its supported envelope — no new transports, no new
user-facing features, no new SPIs.

This is **in-place surgical hardening** of the existing components
(`ClusterNodeManager`, `RedisSessionRegistry`, `RedisClusterNodeHeartbeat`, `ClusterSessionHookImpl`,
auto-config). The only new type is a small, focused `ClusterReaper` helper (item ④) extracted for
testability. No new modules.

It re-introduces two config keys that 1.8.0 deliberately removed as "dead config"
(`redis-loss-grace-period-ms`, `session-registry-write-rate`) — now backed by real behavior,
validating the 1.8.0 principle of "re-add the knob when the feature lands".

## Goals

- Eliminate the documented 1.8.0 resilience/correctness gaps for the ≤10-node + dedicated-Redis envelope.
- Keep all changes backward compatible except one intentional default behavior change (item ①, called out below).
- Every change has a regression test; ②③④ also get real-Redis integration tests.

## Non-goals (explicitly deferred)

- Reliable/at-least-once delivery (Redis Streams, `reliableBroadcast`) — separate large milestone.
- HMAC envelope auth, full Micrometer meter-binder set, W3C trace propagation, NATS/sharded/mesh,
  Redis Cluster client, room/topic fan-out — separate themes.

## The 5 items

### ① Redis-loss grace period (debounced degradation)

**Problem.** 1.8.0 S1 (event-driven detection) flips to DEGRADED the instant Redis disconnects.
A sub-second Redis blip therefore causes DEGRADED↔ACTIVE flapping, and with
`on-redis-loss=close-all` it could mass-close all local sessions on a 200 ms hiccup.

**Design.**
- New config `redis-loss-grace-period-ms` (default **5000**; `0` = preserve 1.8.0 instant degrade).
- The grace window debounces the **node state machine** transition, NOT the broker state:
  - On transport loss (connection event *or* heartbeat failure): `broker.state()` → `DEGRADED`
    **immediately** (stays truthful; in-flight publishes fast-fail and are counted).
  - `ClusterNodeManager` does NOT transition to `DEGRADED` yet — it starts/refreshes a grace timer.
  - If transport is restored before the timer fires → cancel the timer; broker → `ACTIVE`; the node
    never left `ACTIVE`. No flapping, no `close-all`.
  - If the timer fires while still disconnected → node → `DEGRADED` (pauses cross-node, applies
    `on-redis-loss` policy, `broadcastsSkippedDegraded` counting begins).
- The grace timer runs on the reconciliation scheduler (`cluster-recon-{node}`, item ②) — never on
  the heartbeat scheduler, which stays lean for renewal only. Re-entrancy guarded by a single
  `volatile ScheduledFuture<?> graceFuture` + CAS on node state.

**Backward-compat note (intentional).** With the 5 s default, a real Redis outage now takes up to
5 s before the node pauses cross-node traffic / applies `close-all` (vs instant in 1.8.0). Setting
`redis-loss-grace-period-ms=0` restores exact 1.8.0 behavior. This is the only behavior-changing default.

**Test.** transport-lost-then-restored within grace → node stays ACTIVE (no DEGRADED, no close-all);
transport-lost beyond grace → node DEGRADED. (Unit test with a tiny grace value + manual event firing.)

### ② Heartbeat / reconciliation thread isolation + batched EXISTS

**Problem (primary).** `ClusterNodeManager` runs heartbeat renewal and the reconciliation sweep on
**one** single-thread scheduler sharing **one** Redis connection. Under Redis latency a slow
reconciliation sweep starves heartbeat renewal → this node's own heartbeat key expires → peers
falsely reap it (cascading failure). This is the real risk and the main driver of item ②.

**Problem (secondary).** `findExpiredNodes` does `HGETALL` then, for each node whose timestamp is
*already* stale, a **synchronous per-node `EXISTS`** round-trip. In the common case zero nodes are
stale so this is free; but during a **simultaneous multi-node expiry** (mass failure / Redis stall)
it becomes N sequential blocking round-trips on the sweep thread — exactly when latency hurts most.

**Design.**
- Split into **two** single-thread schedulers: `cluster-hb-{node}` (heartbeat renewal only) and
  `cluster-recon-{node}` (reconciliation sweep + the grace timer from ①). A slow sweep can no longer
  delay heartbeat renewal. This is the core fix.
- `RedisClusterNodeHeartbeat.findExpiredNodes`: collect the stale candidates, then issue their
  `EXISTS` checks as one pipelined async batch (await all) instead of a sequential `sync().exists()`
  loop → one round-trip group instead of N. Behavior (which nodes are returned) is unchanged.
- `shutdown()` stops both schedulers; external lifecycle unchanged.

**Test.** existing node-lifecycle tests stay green; new integration test: with multiple
simultaneously-expired nodes `findExpiredNodes` returns the correct expired set via the batched path
(and a non-expired node with a live heartbeat key is excluded).

### ③ `deregister` atomicity (Lua)

**Problem.** `RedisSessionRegistry.deregister` does `HGET nodeId` → (gap) → `DEL sessionKey` +
`SREM netty:node:{nodeId}:sessions` non-atomically. In the gap, a concurrent `register` for the same
`uri|sessionId` could re-create the hash under a new node; the trailing `DEL` then wipes that *newer*
registration, orphaning the new node-set entry. This requires a sessionId to be reused while a
deregister is in flight — practically impossible (session ids are per-connection UUIDs, and one
connection cannot be closing and opening at once). Defense-in-depth correctness fix; not a live bug.

**Design.**
- Collapse the read-then-write into one **atomic Lua script** (`EVAL`/`EVALSHA`) so HGET→DEL→SREM
  cannot interleave. The node-set key is derived at runtime from the hash's `nodeId` (the registry's
  existing schema — `netty:session:{uri}:{sessionId}` hash, `netty:node:{nodeId}:sessions` set):
  ```
  -- KEYS[1] = sessionKey  (netty:session:{uri}:{sessionId})
  -- ARGV[1] = member      ("uri|sessionId")
  local nodeId = redis.call('HGET', KEYS[1], 'nodeId')
  if nodeId then
    redis.call('DEL', KEYS[1])
    redis.call('SREM', 'netty:node:' .. nodeId .. ':sessions', ARGV[1])
  end
  return nodeId
  ```
- SPI signature unchanged (`deregister(uri, sessionId) → CompletionStage<Void>`); no expected-nodeId
  param added. The script reads the owning nodeId itself, so no SPI/caller change.
- The script derives a key (`netty:node:{nodeId}:sessions`) not passed in `KEYS[]`. That is safe on
  **standalone / Sentinel Redis** (1.8.0's supported topologies) but would violate Redis Cluster's
  cross-slot key rule — consistent with our non-goal "Redis Cluster client deferred". Documented as
  such; if a Redis Cluster client lands later it revisits this.
- Plain `EVAL` (Redis caches the compiled script by SHA after first use, and the body is tiny, so
  resending it is negligible). An `EVALSHA`-with-fallback wire-size micro-opt is not worth the
  NOSCRIPT bookkeeping at deregister rates — deferred.

**Test.** integration against real Redis: register then deregister and assert both the session hash is
gone (`lookupNode == null`) and the node-set member was `SREM`-ed (no orphan) — the observable atomic
contract. (A forced interleave race is non-deterministic; the node-set-cleanup assertion is the
durable check.)

### ④ Reconciliation leader-election (claim before reap)

**Problem.** When node X dies, **every** surviving node's reconciliation independently runs
`removeAllForNode(X)` + `deregister(X)` within the same interval → N-fold cleanup traffic at the
worst moment (a node just died).

**Design.**
- New focused helper `ClusterReaper` (in `...cluster.node`): `boolean tryClaim(String deadNodeId)`
  does `SET netty:cluster:reaping:{deadNodeId} {myNodeId} NX PX {windowMs}`.
- `doReconciliation`: for each detected dead node, only the node whose `tryClaim` returns true runs
  `removeAllForNode` + `heartbeat.deregister` + the dead-node cache-invalidation callback. Losers skip.
- Claim window default = `reconciliation-interval` (so a reaper that dies mid-cleanup releases the
  claim by the next sweep, allowing re-claim). No new config (derived).

**Test.** unit: two `ClusterReaper` instances (sharing an in-memory/real Redis), only one `tryClaim`
wins per dead node; integration: with 3 live nodes + 1 dead, `removeAllForNode` runs exactly once.

### ⑤ Registry write coalescing (reconnect-storm throttle, never drops)

**Problem.** A reconnect storm (many sessions reconnecting at once) fires a burst of
`register`/`deregister` writes from `ClusterSessionHookImpl`, hammering Redis.

**Design (coalesce, do NOT drop).** Dropping a `register` would lose routing — worse than the storm.
- New config `session-registry-write-rate` (default **1000** ops/s/node).
- `ClusterSessionHookImpl` writes go through a small bounded **coalescing writer**: register/deregister
  ops are enqueued and flushed via Redis **pipeline** in batches, bounded to the configured rate
  (e.g. flush up to `rate/100` ops every 10 ms). A `register` is **never dropped**; a
  `deregister` superseded by a later `register` for the same session (or vice-versa) collapses to the
  latest op (last-write-wins per sessionId) — correct and reduces writes.
- Bounded queue with a high-water log so unbounded growth is visible; on shutdown the queue is flushed.

**Test.** unit: a burst of N register/deregister ops on one session coalesces to one effective write;
rate stays ≤ configured; no register lost.

## Config additions (re-introduced, now functional)

| Key | Default | Item |
| --- | --- | --- |
| `server.netty.websocket.cluster.redis-loss-grace-period-ms` | `5000` | ① |
| `server.netty.websocket.cluster.session-registry-write-rate` | `1000` | ⑤ |

Both added to `ClusterProperties`, `additional-spring-configuration-metadata.json`, the api-guide §9
config table, and the cluster-design config block. Items ②③④ are internal — no config.

## Architecture / isolation

- `ClusterNodeManager`: gains a second scheduler + the grace-timer logic. Still one class, clear purpose.
- `RedisClusterNodeHeartbeat`: `findExpiredNodes` batched. Same interface.
- `RedisSessionRegistry`: `deregister` becomes a Lua call. Same interface.
- `ClusterReaper` (new, small): claim-before-reap. Single responsibility, unit-testable in isolation.
- `ClusterSessionHookImpl` + a small `CoalescingRegistryWriter` (new, small): bounded batched writes.
- Auto-config wires the two new config values; everything else transparent.

No `ClusterBroker`/`SessionRegistry`/`MessagePayloadCodec`/`EnvelopeCodec`/`ClusterNodeHeartbeat` SPI
signature changes — custom transport implementors are unaffected.

## Testing & release

- Per-item unit tests (above) + real-Redis integration tests for ②③④.
- Full `mvn test` across 11 modules green; the pre-release multi-round review loop re-run.
- Version bump 1.8.0 → 1.9.0 across all poms at release time (not during implementation).
- Docs: cluster-design scope table moves these 5 from ⏳ to ✅; dev-plan/release-notes-1.9.0/release-checklist/api-guide updated; CLAUDE.md current-version line.

## Out of scope for 1.9.0 (next 1.9.x / 2.x)

Reliable delivery (Streams), HMAC auth, full Micrometer meter-binders, W3C trace, NATS/sharded/mesh,
Redis Cluster client, room/topic fan-out, multi-node demo + Testcontainers.
