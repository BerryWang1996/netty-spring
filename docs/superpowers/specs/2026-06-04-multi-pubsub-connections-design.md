# Multi Pub/Sub Connections — Design Spec

> Date: 2026-06-04 · Target: **1.9.x cycle** (develops on `master` @ `ae6be86`) · Status: design approved (delegated); correctness verified
> Builds on `RedisPubSubBroker` (the default Redis cluster transport).

## Overview / Goal

Opt-in parallelism for the Redis Pub/Sub broker's **inbound** path: spread `SUBSCRIBE` across **N** Lettuce
pub/sub connections, partitioning broadcast/unicast channels by a stable hash, so inbound decode runs on up to
N Lettuce I/O threads instead of one. This mitigates "wall #2" in `docs/cluster-design.md` — the
single-Lettuce-pub/sub-connection decode ceiling (~80k msg/s/node). Additive and opt-in:
`pubsub-connections` defaults to **1** = byte-identical to today's single-connection behavior.

**YAGNI note (honest framing):** `cluster-design.md` deferred this as premature optimization ("实测吞吐远低于
单连接天花板" — measured throughput far below the single-connection ceiling). It ships as a **default-off
future-proofing knob** with zero overhead when unused, flipping the cluster-design scope row ⏳→✅ at the
client level. It is not a response to a current bottleneck.

## Scope

**In:** multiplex the standalone `RedisPubSubBroker`'s SUBSCRIBE connections; the `pubsub-connections` config
knob; auto-config wiring; unit + integration tests; docs.

**Out:**
- **PUBLISH multiplexing** — publish is fire-and-forget async and Lettuce auto-pipelines a single connection;
  it is not the decode wall. (Publish batching is a separate deferred item, `cluster-design.md` line 41.)
- **The RC7 `RedisClusterModePubSubBroker`** — keeps its single connection (different Lettuce connection/adapter
  types; the standalone broker is the common path). Documented follow-up.
- **Any perf SLA / benchmark assertion** — perf is environment-dependent; the testable invariant is
  correctness-under-partitioning, not throughput.

## Architecture

Today `RedisPubSubBroker` holds **1** `publishConnection` + **1** `subscribeConnection` (one inbound adapter; a
global `channelListeners` map of channel→listener). After this change: **1** `publishConnection` + **N**
`subscribeConnections`, each carrying the same inbound adapter. A channel `C` is subscribed on **exactly**
`connectionFor(C) = subscribeConnections[Math.floorMod(C.hashCode(), N)]`. Redis routes `C`'s messages only to
that one connection, so connection *i* decodes only its channel partition → up to N-way parallel inbound decode.

```
 publish:   publishConnection.async().publish(channel, data)           (unchanged, single connection)

 subscribe: channelListeners.put(C, listener)                          (global map, unchanged)
            connectionFor(C).async().subscribe(C)                      (routed to 1 of N connections)

 inbound:   conn[0].adapter ─┐
            conn[1].adapter ─┼─► onInboundMessage(channel, message) ─► channelListeners.get(channel).onMessage
            conn[i].adapter ─┘   (each conn fires only for channels subscribed on it)
```

## Components / Changes

### `RedisPubSubBroker`
- **New constructor** `RedisPubSubBroker(RedisClient, EnvelopeCodec, MessageAuthenticator, int pubsubConnections)`.
  The existing 2-arg and 3-arg constructors delegate with `pubsubConnections = 1` (backward-compatible). The
  count is clamped to `[1, 16]` (a value outside logs a warn and is clamped).
- **`subscribeConnection`** (single) → **`List<StatefulRedisPubSubConnection<String, String>> subscribeConnections`**
  (N elements, each `redisClient.connectPubSub()`).
- **Inbound adapter body** → a private `onInboundMessage(String channel, String message)` holding the EXISTING
  logic verbatim (inbound-size guard → `channelListeners.get(channel)` → `authenticator.unwrap` → `codec.decode`
  → `listener.onMessage`). A `RedisPubSubAdapter` delegating to `onInboundMessage` is added to **each** of the N
  connections.
- **`connectionFor(channel)`** = `subscribeConnections.get(Math.floorMod(channel.hashCode(), subscribeConnections.size()))`
  — deterministic channel→connection map (same channel always lands on the same connection, so subscribe,
  inbound, and unsubscribe are consistent).
- **`subscribe`/`subscribeUnicast`** route the `SUBSCRIBE` to `connectionFor(channel)`; **`createSubscription`**'s
  `unsubscribe` routes to `connectionFor(channel)` (recomputed, deterministic).
- **`shutdown`** closes all N subscribe connections + the publish connection.
- **Health: unchanged.** The `RedisConnectionStateListener` is registered on the `RedisClient`, so it already
  covers all N connections; the existing idempotent CAS (any connection drop → `DEGRADED`, any reconnect →
  `ACTIVE`) needs no edit. Lettuce re-subscribes each connection's own channels on reconnect; the deterministic
  mapping keeps the same channels on the same connections.

### Concurrency — VERIFIED SAFE (documented property, not a risk)
Multiplexing makes inbound run on up to N threads concurrently **for different channels**. A channel is pinned
to one connection, so its messages stay serialized on one thread — **per-channel ordering is preserved**; and
Redis Pub/Sub provides no cross-channel ordering, so nothing depends on a global inbound order. The downstream
listener path is already concurrency-safe (verified by reading `ClusterMessageSender`):
`onBroadcastMessage`/`onUnicastMessage` touch only `AtomicLong` stats, per-thread MDC trace scope, stateless
payload decode (a static read-only `ObjectMapper`), and the `localSender` fan-out — which is already invoked
concurrently by application threads in normal operation. So introducing cross-channel inbound concurrency
requires no change to the listener and is safe. The integration test exercises it (N=3 + multiple channels).

### Config (`ClusterProperties`)
- `pubsubConnections` (int, default **1**) → `server.netty.websocket.cluster.pubsub-connections`. Javadoc: number
  of Redis Pub/Sub SUBSCRIBE connections to spread inbound decode across; `1` = single connection (default,
  unchanged); 2–4 recommended **only** when a node approaches the single-connection decode ceiling; clamped to
  `[1, 16]`. Redis-Pub/Sub-specific (no effect on other transports). Kept top-level alongside the sibling Redis
  tuning knob `command-timeout-ms`.

### Auto-config (`NettyWebSocketClusterConfigure`)
- The standalone `clusterBroker` bean constructs `new RedisPubSubBroker(redisClient, envelopeCodec,
  messageAuthenticator, properties.getPubsubConnections())` (then `setInboundMaxBytes(...)` as today). No other
  bean changes; the cluster-transport beans are untouched.

## Testing

- **Unit (Mockito, no live Redis):** mock `RedisClient`; `connectPubSub()` returns N distinct mock
  `StatefulRedisPubSubConnection` (each `.async()` a mock `RedisPubSubAsyncCommands`). With N=4, assert: (a) two
  channels that hash to **different** indices issue `subscribe(...)` on the correct **different** connections;
  (b) a given channel always routes to the **same** connection (determinism); (c) the `connectPubSub()` call
  count equals N. This pins partitioning + routing without Redis. (The existing registry/broker unit tests show
  the `RedisFuture` mock pattern to reuse.)
- **Integration (real Redis, `ClusterTestRedis`):** construct `RedisPubSubBroker` with `pubsubConnections=3`,
  subscribe to several broadcast channels (URIs chosen so ≥2 land on different connections) plus a unicast
  channel, publish to each, and assert **all** are received with the correct envelopes within a timeout —
  proving no channel is dropped regardless of its partition. Include an `pubsubConnections=1` control (single
  connection still delivers). No throughput assertion.
- **Regression:** the existing `RedisIntegrationTest` (single connection) is the N=1 regression — behavior must
  stay green.

## Backward compatibility

Default `1` = byte-identical to today (the `List` has one element, `connectionFor` always returns index 0). The
new constructor is additive; existing constructors are unchanged. No wire-format change, no SPI change. Single-
node mode and the standalone/sentinel path are unaffected; the cluster-transport broker is untouched.

## Versioning / workflow

Part of the **1.9.x cycle** (develops on `master` @ `ae6be86`). Spec + plan committed to `master`; implementation
on a feature branch `feature/1.9.x-multi-pubsub`, FF-merged via `finishing-a-development-branch`. No push/deploy;
**no RC bump** (rides to the next RC or the final 1.9.0 cut, together with the cross-node JSON fix).

## Files

**New:** a `RedisPubSubBrokerMultiConnTest` (Mockito partition/routing unit test); an IT method appended to
`RedisIntegrationTest` (delivery under N=3).

**Modified:** `RedisPubSubBroker.java` (N subscribe connections + routing); `ClusterProperties.java`
(`pubsubConnections`); `NettyWebSocketClusterConfigure.java` (`clusterBroker` bean passes the count);
`docs/cluster-design.md` (scope row ⏳→✅ with the opt-in/off-by-default note), `docs/api-guide.md` (config row).
Release-notes is updated at the next RC cut, not here.
