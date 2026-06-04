# Redis Cluster Client Foundation — Design Spec

> Date: 2026-06-04 · Target: **1.9.0 cycle** (develops on `1.9.0-RC6`; cuts `v1.9.0-RC7`) · Status: design approved (delegated)
> Builds on: RC1 hardening + RC2 reliable broadcast + RC3 HMAC + RC4 metrics + RC5 E2E/CI + unicast fix + RC6 trace propagation.

## Overview

The cluster's Redis impls (`RedisPubSubBroker`, `RedisSessionRegistry`, `RedisClusterNodeHeartbeat`) are built on Lettuce **`RedisClient`** (standalone / sentinel). Running against a **Redis Cluster** needs a different client (`RedisClusterClient`) with different connection types (`StatefulRedisClusterConnection`, `StatefulRedisClusterPubSubConnection`) and listener APIs — and several of the standalone optimizations are **slot-unsafe on cluster**: the atomic-Lua deregister touches a `netty:node:{nodeId}` key in a different slot than the session key (`CROSSSLOT`), the batched multi-key `EXISTS` in heartbeat reaping spans slots, and `SCAN` is per-node. The SPI (`ClusterBroker` / `SessionRegistry` / `ClusterNodeHeartbeat`, all `@ConditionalOnMissingBean`) is transport-agnostic and the design already tells Redis-Cluster users to bring their own beans — so this feature ships **first-class, additive `RedisCluster*` impls** selected by URI.

**Scope constraint (confirmed):** Boot 2.7.18 manages Lettuce **6.1.10.RELEASE**; **sharded pub/sub (`SSUBSCRIBE`/`SPUBLISH`) was added in Lettuce 6.2.0**. So sharded pub/sub — the part that actually reduces Redis Pub/Sub's N× broadcast fan-out — is **deferred to 2.0.0** (Boot 3.x → Lettuce 6.2+). RC7 delivers Redis-Cluster **client** support: HA failover + a session registry/heartbeat distributed across slots + **regular** cluster pub/sub (which still propagates broadcasts to every node — no fan-out reduction yet). This is stated plainly so it is not oversold.

## Goals

- First-class `RedisCluster*` impls of `ClusterBroker`, `SessionRegistry`, `ClusterNodeHeartbeat`, selected automatically when a Redis Cluster URI is configured.
- Correct on Redis Cluster's slot model (no `CROSSSLOT` errors): per-key routing, cluster-aware `SCAN`, per-key `EXISTS`, non-atomic deregister.
- Additive + opt-in: zero change to the standalone/sentinel path or single-node mode; selected purely by URI/config.

## Non-goals

- **Sharded pub/sub (`SSUBSCRIBE`/`SPUBLISH`)** — needs Lettuce 6.2+ (Boot 3.x). Deferred to 2.0.0. RC7's cluster broker uses **regular** cluster pub/sub.
- Reducing broadcast fan-out — that is exactly what sharded pub/sub provides; RC7 does not claim it.
- Changing the standalone/sentinel impls or the SPI signatures.
- Reliable-stream (`ReliableBroker`) on cluster — out of scope for RC7 (the Streams impl stays standalone; a `RedisClusterStreamsReliableBroker` can follow later).

## Architecture — three parallel `RedisCluster*` impls

All in `…cluster.redis`, all `@ConditionalOnMissingBean` so users can still override. Each mirrors its standalone sibling's SPI contract exactly.

### 1. `RedisClusterPubSubBroker implements ClusterBroker`
- Built on `RedisClusterClient` + `StatefulRedisClusterPubSubConnection` (+ a `StatefulRedisClusterConnection` for `PUBLISH`).
- **Regular** cluster pub/sub: `PUBLISH` to `netty:broadcast:{uri}` / `netty:unicast:{node}` propagates cluster-wide; the cluster pub/sub connection receives. Lettuce cluster pub/sub message propagation must be enabled so a subscribe on the connection sees messages from any node (the plan pins the exact Lettuce 6.1 API — likely `connection.setNodeMessagePropagation(true)` or subscribing on the upstream node).
- Same `EnvelopeCodec` + `MessageAuthenticator` + `TransportStateListener` + inbound-size-cap contract as `RedisPubSubBroker`. Transport-health listener uses `RedisClusterClient.addListener(RedisConnectionStateListener)` (verify in the plan).

### 2. `RedisClusterSessionRegistry implements SessionRegistry`
- `StatefulRedisClusterConnection`; commands auto-route per slot (`RedisAdvancedClusterAsyncCommands`).
- `register` (HSET session + SADD node-set), `lookupNode` (HGET), `removeAllForNode` (SMEMBERS + per-key DEL) — identical logic; each command routes to its own slot, so they work unchanged.
- **`deregister` is non-atomic on cluster:** `HGET` the owning nodeId → `DEL` the session key + `SREM` the node-set member, as **three separately-routed async commands** (not the cross-slot Lua). The race the Lua closed is theoretical under UUID sessionIds (a closed session's id is never reused). Documented trade-off; standalone keeps the atomic Lua.
- `clusterSessionIds` uses Lettuce's **cluster-aware `SCAN`** (scans all masters and merges) instead of single-node SCAN.

### 3. `RedisClusterNodeHeartbeat implements ClusterNodeHeartbeat`
- `StatefulRedisClusterConnection`; `SET key val EX ttl` / per-key reads route per slot — unchanged.
- **`findExpiredNodes` uses per-key `EXISTS`** (pipelined individual `EXISTS`, results joined) instead of one multi-key `EXISTS` (which is `CROSSSLOT` on cluster). Same outcome, slot-safe.

## Auto-config selection (`NettyWebSocketClusterConfigure`)

Config-driven, no new behavior unless cluster nodes are set. The single selector is **`server.netty.websocket.cluster.redis.cluster-nodes`** (comma-separated `host:port,host:port,…`). (Lettuce has no `redis-cluster://` scheme — `RedisClusterClient.create(...)` takes a list of seed `RedisURI`s, which the auto-config builds from `cluster-nodes`.)
- **When `cluster-nodes` is non-empty (`@ConditionalOnProperty` on it):** create a `RedisClusterClient` (`@Bean @ConditionalOnMissingBean(RedisClusterClient.class)`, seeds parsed from `cluster-nodes`), a `StatefulRedisClusterConnection` bean, and the three `RedisCluster*` SPI beans (each `@ConditionalOnMissingBean(<the SPI>)`). The standalone `RedisClient` / `RedisPubSubBroker` / `RedisSessionRegistry` / `RedisClusterNodeHeartbeat` beans get the **complementary** condition (`@ConditionalOnProperty(... cluster-nodes, matchIfMissing=true)` i.e. active only when `cluster-nodes` is absent) so exactly one transport wins and there is no duplicate-`ClusterBroker`/`SessionRegistry` ambiguity.
- **When `cluster-nodes` is absent:** the existing standalone/sentinel path is **byte-identical** to RC6.
- Reuse the existing command-timeout (`ClientOptions`/`ClusterClientOptions`), URI redaction, and insecure-Redis-warning helpers.

`ClusterProperties.Redis` gains `clusterNodes` (a comma-separated `String`, default empty/null) + accessor; the `uri` semantics are unchanged.

## Testing

- **Unit (mockable, the verification backbone):** for each `RedisCluster*` impl, mock `StatefulRedisClusterConnection` + `RedisAdvancedCluster[Async]Commands` (Lettuce interfaces) and assert the right commands/keys are issued — e.g. `register` → `hmset(sessionKey, hash)` + `sadd(nodeSetKey, "uri|sessionId")`; **`deregister` → `hget` then `del` + `srem` (three routed calls, no `eval`)**; `findExpiredNodes` → per-key `exists` (not one multi-key call). This verifies slot-safety and key schemes without a live cluster.
- **Integration (real cluster, gated-skip):** a `RedisClusterIntegrationTest` resolves a real Redis Cluster from `CLUSTER_TEST_REDIS_CLUSTER_NODES` (env) — register/lookup/deregister + a pub/sub broadcast round-trip — and **`assumeTrue`-skips when no cluster is configured**. **Honest limitation:** a Testcontainers Redis Cluster is not attempted here — the announce-ip/NAT problem (the cluster gossips internal ports that don't match Testcontainers' mapped ports) makes it flaky, especially on Docker-Desktop; so this IT will typically **skip in CI** until a stable cluster harness exists. The unit tests carry the slot-correctness proof.
- **Context test:** with `redis.cluster-nodes` set, the `RedisClusterClient` + the three `RedisCluster*` beans are present and the standalone `RedisClient`/`RedisPubSubBroker` are NOT; with only `redis.uri` (standalone), the reverse. (Bean-presence assertions don't need a live cluster — `RedisClusterClient.create(...)` is lazy until connect; if any bean eagerly connects, gate that context test `assumeTrue` on a cluster being available.)

## Backward compatibility

Purely additive + opt-in. No SPI signature change; the new impls are alternative `@ConditionalOnMissingBean` beans selected only when cluster nodes are configured. The standalone/sentinel path (incl. the atomic-Lua deregister and batched `EXISTS`) is unchanged. Single-node mode unaffected. No wire-format change.

## Versioning

Part of the 1.9.0 cycle. Develops on `1.9.0-RC6`; completing it cuts **`v1.9.0-RC7`**. Sharded pub/sub (broadcast fan-out reduction) → **2.0.0** (Boot 3.x / Lettuce 6.2+).

## Files (for the plan)

- **New:** `…cluster/redis/RedisClusterPubSubBroker.java`, `RedisClusterSessionRegistry.java`, `RedisClusterNodeHeartbeat.java`; unit tests for each (Mockito over Lettuce cluster command interfaces); `RedisClusterIntegrationTest.java` (gated-skip).
- **Modified:** `ClusterProperties.java` (`Redis.clusterNodes`); `NettyWebSocketClusterConfigure.java` (cluster-URI detection → `RedisClusterClient` + cluster connection + the three cluster beans, with complementary conditions on the standalone beans); `NettyWebSocketClusterConfigureTest.java` (selection context test).
- **Docs:** `release-notes-1.9.0.md` (RC7 section — **with the explicit "regular cluster pub/sub, sharded pub/sub → 2.0.0" caveat**), `api-guide.md` (cluster-nodes config), `cluster-design.md` + `development-plan.md` (move "Redis Cluster 客户端一等支持" to ✅ RC7 at the client level; sharded pub/sub stays deferred), `release-checklist.md`.
