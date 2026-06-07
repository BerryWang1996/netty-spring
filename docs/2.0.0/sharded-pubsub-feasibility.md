# Sharded Pub/Sub Feasibility Study — `RedisShardedPubSubBroker`

> **2.0.0 forward-looking artifact.** This document is a design study prepared during the
> 1.9.x cycle (RC19) as input to the 2.0.0 (Boot 3.x) cycle. **It is NOT a 1.9.0 feature
> commitment** — no SPI / wire / config / Java behaviour referenced here ships in 1.9.0.
> All concrete API signatures must be re-verified against Lettuce 6.2+ javadoc / source
> during 2.0.0 implementation.

---

## 1. Motivation — the `M·(f·N-1)` fan-out wall

`docs/cluster-design.md` §深度瓶颈 quantifies the core scaling limit of the RC1–RC18
Redis Pub/Sub design. Quoted verbatim from §扇出放大 `M·(f·N−1)`:

> 每条逻辑广播：本节点先本地 fan-out，再 `PUBLISH` 一次；Redis 把这条消息投递给频道上**每一个**订阅连接。设 N=节点数、f=订阅该 URI 的节点比例（S=f·N 个订阅连接）、M=该 URI 的**集群级**广播消息/s、B=单消息字节：
>
> ```
> Redis PUBLISH 次数/s   = M
> Redis 投递次数/s       = M·(S−1)  ≈ M·f·N      ← 墙在这里
> Redis 出口字节/s       = M·(S−1)·B
> Redis CPU             ∝ M·S（PUBLISH 是 O(订阅者数)）
> ```

The same section's TOP-bottleneck list flags this as the **#1 wall**: "活跃广播 **N≈8–12**
即撞，**N≈30 近空闲房间**也饱和"。 RC7 (`RedisClusterModePubSubBroker`) added Redis Cluster
*client* support (HA + slot-distributed registry/heartbeat), but explicitly **does not** cut
fan-out — it uses regular `SUBSCRIBE`/`PUBLISH`, which in Cluster mode pays a full cluster-bus
broadcast tax per message (see release-notes-1.9.0.md §⑪ "RC7 不削减广播扇出").

Sharded pub/sub (`SSUBSCRIBE`/`SPUBLISH`, Redis 7.0+) routes each channel to **exactly one
hash slot** — and therefore to exactly one master shard — turning the fan-out denominator
from `S = f·N` into `S/k` where `k` is the cluster master count.

## 2. Why deferred from 1.9.0 (RC7)

| Constraint | 1.9.0 reality | 2.0.0 unlock |
|---|---|---|
| Spring Boot managed Lettuce version | 2.7.18 → **Lettuce 6.1.10** | Boot 3.2.x → **Lettuce 6.2.x+** |
| Sharded pub/sub Lettuce API (`ssubscribe`/`spublish`) | Not present in 6.1.x | Available from **6.2.0** |
| Lettuce sharded-pubsub auto-resubscribe correctness | n/a | Requires **Lettuce ≥ 6.5.5** (cluster-design.md §3 calls out the 6.4.x auto-resubscribe bug) |
| Redis server requirement | Redis 6.x acceptable | **Redis 7.0+** for `SSUBSCRIBE`/`SPUBLISH` |

RC7 explicitly recorded sharded pub/sub as "推迟到 2.0.0 (Boot 3.x)" because forcing Lettuce
6.2+ onto Boot 2.7.18 users would break Boot's managed-dependency contract.

## 3. API surface (Lettuce 6.2 — verify during 2.0.0 implementation)

Expected sharded pub/sub surface (**API recall — verify during 2.0.0 implementation**):

```java
// Same StatefulRedisClusterPubSubConnection as RC7, plus sharded ops on the async API:
StatefulRedisClusterPubSubConnection<String, String> conn = redisClusterClient.connectPubSub();
RedisClusterPubSubAsyncCommands<String, String> async = conn.async();
async.ssubscribe("netty:broadcast:" + uri);
async.spublish("netty:broadcast:" + uri, envelopeBytes);

// Listener — node-aware adapter (same shape as RC7's RedisClusterPubSubAdapter);
// exact sharded-message method name (smessage vs. overloaded message) TBD §9.
```

Listener naming, `setNodeMessagePropagation(true)` applicability, and connection
multiplexing with classic subscriptions are tracked in §9.

## 4. Fan-out reduction model

Sharded pub/sub partitions each channel to one master shard. With random URI-to-slot
distribution across `k` masters, per-shard load drops by ~`k`:

```
Original (RC7 regular cluster pub/sub):  Redis 投递/s ≈ M · (f·N − 1)
Sharded (2.0.0):    per-shard delivery   ≈ M · (f·N − 1) / k    (k shards in parallel)
```

Worst-case `f=1` (every node subscribed):

| `N` | `M` (msg/s) | Original `M·(f·N-1)` | k=3 | k=6 | k=12 | Reduction @ k=6 |
|---|---|---|---|---|---|---|
| 100 | 1,000 | 99,000 | 33,000 | 16,500 | 8,250 | **~83%** |
| 500 | 1,000 | 499,000 | 166,333 | 83,167 | 41,583 | **~83%** |
| 500 | 5,000 | 2,495,000 | 831,667 | 415,833 | 207,917 | **~83%** |
| 1000 | 1,000 | 999,000 | 333,000 | 166,500 | 83,250 | **~83%** |

Cluster-wide aggregate is unchanged at `M·(f·N-1)`, but distributed across `k` masters —
the single-master 100k delivery/s budget becomes `k·100k` cluster-wide. The per-node
**decode** ceiling (~80k msg/s, "墙②") is NOT reduced: sharded redistributes publisher-side
load only. Multi-pub/sub connections (RC8 §⑬) remain the lever for the decode wall.

## 5. Auto-config matrix entry sketch

Proposed addition (mirrors RC7 §⑪ table style):

| Property | Default | Effect |
|---|---|---|
| `pubsub-mode` | `regular` | `regular` → RC7 `RedisClusterModePubSubBroker` (SUBSCRIBE/PUBLISH); `sharded` → `RedisShardedPubSubBroker` (SSUBSCRIBE/SPUBLISH). Only meaningful with `cluster-nodes` set. |

Auto-config gate (mirroring the 5-tier matrix from RC10 §⑮):

```
@ConditionalOnClass(name = "io.lettuce.core.cluster.pubsub.api.async.RedisClusterPubSubAsyncCommands")
@ConditionalOnExpression(
    "'${...cluster.redis.cluster-nodes:}' != ''"
    + " && '${...cluster.redis.pubsub-mode:regular}' == 'sharded'"
    + " && '${...cluster.nats.servers:}' == ''")
@ConditionalOnMissingBean(ClusterBroker.class)
```

Enforced at autoconfigure-time: `pubsub-mode=sharded` requires `cluster-nodes` non-empty
(standalone/sentinel = single shard = zero benefit → fail-fast); requires Lettuce ≥ 6.5.5
(startup version check, per the cluster-design.md §3 "6.4.x bug" note); coexists with
`nats.servers=` per the three-way selection from §⑭ (NATS wins).

## 6. Mutual-exclusion with RC7 layout

Sharded broker is purely a cross-node fan-out swap. RC7 layers unchanged:
`RedisClusterModeSessionRegistry`, `RedisClusterModeNodeHeartbeat`, `RedisClusterReaper`
(RC11), `EnvelopeCodec` / `MessageAuthenticator` / `MessagePayloadCodec`, channel naming
(`netty:broadcast:{uri}` / `netty:unicast:{targetNodeId}`).

Self-delivery suppression via `envelope.originNodeId` (cluster-design.md §3 TOP #3) still
applies — sharded only partitions which shard sees the channel; it does not change the
publisher-receives-own-publish property.

## 7. Test strategy

| Layer | RC7 today | 2.0.0 sharded |
|---|---|---|
| Testcontainers fixture | `ClusterTestRedisCluster` (single-node, all 16384 slots on 1 master) | **6-master `ClusterTestRedisCluster6`** — shard distribution observable |
| Unit | Mocked `StatefulRedisClusterPubSubConnection` | Add `ssubscribe`/`spublish` mocks |
| Integration | broadcast / unicast round-trip | Plus **shard-distribution verification** (URIs A and B land on distinct masters via `CLUSTER KEYSLOT`) and **fan-out reduction** measured vs RC7 baseline on the same 6-master fixture |

The 6-master fixture is mandatory — RC7's single-node cluster has all slots on one master
by construction, so sharding effects are not measurable there.

## 8. Migration impact

Pure opt-in. Existing `cluster-nodes` deployments default to `pubsub-mode=regular` (RC7
behaviour, byte-level identical). To adopt:

```yaml
server:
  netty:
    websocket:
      cluster:
        redis:
          cluster-nodes: redis-0:6379,redis-1:6379,redis-2:6379
          pubsub-mode: sharded   # NEW in 2.0.0; requires Lettuce ≥ 6.5.5 + Redis 7.0+
```

No application code change. No session-registry data migration. Wire format unchanged.
`MessageSender` API surface unchanged.

## 9. Open questions (to be answered during 2.0.0 implementation)

- **Channel pattern matching** — Redis 7 sharded pub/sub does **not** support
  `PSUBSCRIBE`/pattern variants. Our exact `netty:broadcast:{uri}` naming should be fine;
  verify no transitive pattern use exists.
- **Multi-key `SSUBSCRIBE`** — only legal if all channels hash to the same slot. Per-URI
  channels do not — confirm one `SSUBSCRIBE` per channel and measure RTT cost.
- **Failover-during-resharding** — verify Lettuce ≥ 6.5.5 auto-resubscribe under
  `CLUSTER FAILOVER` mid-broadcast (the 6.4.x bug from cluster-design.md §3).
- **Connection multiplexing** — can one `StatefulRedisClusterPubSubConnection` carry both
  classic and sharded subscriptions, or do we need a second connection? Affects how
  `pubsub-connections` (§⑬) composes with sharded mode.
- **Listener method** — confirm `smessage(...)` vs an overloaded `message(...)` on
  `RedisClusterPubSubAdapter`.
- **`spublish` failure surface** — async exception promptness when target slot's master
  is unavailable; affects the `logAsyncPublishFailure` path inherited from RC7.

These are explicit TBDs; this study commits to none of them. RC7's
`RedisClusterModePubSubBroker` remains the production cluster broker for the 1.9.x line.
