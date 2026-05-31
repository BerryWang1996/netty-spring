# Reliable Broadcast via Redis Streams — Design Spec

> Date: 2026-05-31 · Target: **1.9.0** cycle (develops on the `1.9.0-RC1` line; folds into final 1.9.0) · Status: design approved, pending spec review
> Builds on: the 1.9.0-RC1 cluster reliability hardening (grace period, scheduler isolation, atomic deregister, reaper election, write coalescing).

## Overview

1.8.0/1.9.0-RC1 cross-node **broadcast** is **at-most-once**: `topicMessage()` fans out locally then fire-and-forgets over Redis Pub/Sub. A subscriber node that is briefly offline (DEGRADED, restarting, GC-stalled) **silently misses** any broadcast published during that window — Pub/Sub has no replay.

This feature adds an **opt-in, at-least-once** broadcast path backed by **Redis Streams**, exposed as a new
`ClusterMessageSender.reliableBroadcast(uri, message)`. The existing at-most-once `topicMessage()` (Pub/Sub)
is **completely unchanged** — apps opt into reliability per message, only where it matters (latency cost is
slightly higher than Pub/Sub).

This is **additive**: a new `ReliableBroker` SPI beside `ClusterBroker`, a new sender method, new config under
`…cluster.reliable.*`, all **gated off by default** (`reliable.enable=false` ⇒ zero overhead, no consumer
threads, no new connections). No existing SPI signature changes.

## Goals

- New `reliableBroadcast(uri, message)`: **at-least-once** cross-node broadcast **within a bounded retention window**.
- A node that was offline and rejoins (RESYNC) **catches up** on missed reliable broadcasts automatically.
- Zero impact when disabled; zero change to `topicMessage()` / Pub/Sub semantics when enabled.
- Reuse the existing `ClusterEnvelope` + `EnvelopeCodec` + `MessagePayloadCodec` — **no new serialization**.

## Non-goals (explicitly out of scope)

- Reliable **unicast** / reliable **close** — unicast already surfaces failures synchronously
  (`MessageSessionClosedException`); not the gap.
- **Exactly-once** — at-least-once + consumer-side dedup is the target (exactly-once is impractical).
- Durability beyond Redis itself — "as durable as your Redis" (AOF/RDB is an ops concern).
- Replacing Pub/Sub or making broadcast reliable by default.
- NATS / Kafka / external logs, sharded streams, Redis Cluster cross-slot streams — later milestones.

## Architecture

```
reliableBroadcast(uri,msg) ─┐                          ┌─ XREADGROUP loop (per subscribed URI, dedicated conn)
                            ▼                          ▼
ClusterMessageSender ──> ReliableBroker.reliablePublish ──> Redis Stream  ──> ReliableBroker delivers to listener
   (local fan-out first)        (XADD MAXLEN~)        netty:cluster:rstream:{uri}        │
                                                                                          ▼
                                                              self-suppress + dedup ──> localSender.topicMessage ──> XACK
```

- **One Redis Stream per URI:** `netty:cluster:rstream:{uri}`. Each entry is a single field `e` carrying the
  `EnvelopeCodec`-encoded `ClusterEnvelope` (the same bytes/string the Pub/Sub path uses; `originNodeId` is
  already inside it) — no new serialization, no envelope schema change.
- **One consumer group per node** on each stream: `g:{nodeId}` (so every node independently consumes the
  full stream with its own server-tracked cursor + PEL — the canonical Redis at-least-once primitive).
- **Origin also consumes its own entry** → suppressed by `originNodeId == self` (it already did local fan-out
  before XADD), identical contract to the Pub/Sub path.

### Components (new)

- **`spi/ReliableBroker.java`** (SPI, `@ConditionalOnMissingBean`):
  - `void reliablePublish(String uri, ClusterEnvelope envelope)` — XADD to the URI stream (MAXLEN~), and
    `SADD netty:cluster:rstreams uri` (registry of active reliable streams, for dead-node cleanup).
  - `ReliableSubscription reliableSubscribe(String uri, String nodeId, ClusterMessageListener listener)` —
    ensure group `g:{nodeId}` exists (`XGROUP CREATE … $ MKSTREAM`; ignore `BUSYGROUP`), start the consume loop.
  - `void destroyConsumerGroup(String uri, String nodeId)` — `XGROUP DESTROY` (used by dead-node cleanup + own shutdown).
  - `java.util.Set<String> knownReliableStreams()` — `SMEMBERS netty:cluster:rstreams` (for cleanup sweeps).
  - `BrokerState state()`, `void shutdown()`.
- **`spi/ReliableSubscription.java`** — `unsubscribe()` / `isActive()` (mirrors `ClusterSubscription`).
- **`redis/RedisStreamsReliableBroker.java`** (default impl): XADD/XREADGROUP/XACK; a **dedicated Lettuce
  connection** for blocking reads (an `XREADGROUP BLOCK` holds its connection — must NOT use the shared
  command connection); per-URI consume thread (or a small pool) on `cluster-rstream-{node}`; per-URI bounded
  dedup ring (entry-ids); self-suppression by `originNodeId`.

### Consume loop (per subscribed URI)

1. `XREADGROUP GROUP g:{nodeId} {consumer} COUNT {n} BLOCK {pollBlockMs} STREAMS {key} >` (new entries).
2. For each entry: decode envelope; if `originNodeId == self` → `XACK` + skip; else if entry-id already in the
   dedup ring → `XACK` + skip; else `listener.onMessage(envelope)` (→ `localSender.topicMessage`) then `XACK`
   and record the entry-id.
3. On reconnect after a disconnect, a **one-time** `XREADGROUP … STREAMS {key} 0` first redelivers the PEL
   (delivered-but-unacked, e.g. a crash between deliver and ack), then resumes `>`.

**Replay is automatic:** while a node is offline its group cursor doesn't advance, so on reconnect `>` returns
the whole backlog since its last read — no bespoke replay logic.

### Lifecycle (in `ClusterMessageSender`, only when `reliable.enable`)

- A URI becomes locally active (`onLocalUriActive`) → also `reliableSubscribe(uri, nodeId, onReliableMessage)`.
- `reliableBroadcast(uri, msg)`: **local fan-out first** (like topicMessage), then `reliablePublish` (XADD).
  Reuses size-cap / `onPublishFailure` handling. If `reliable.enable=false`, throws `IllegalStateException`
  ("reliable delivery disabled; set …cluster.reliable.enable=true").
- RESYNC (Redis recovered): the consume loop reconnects → backlog replays automatically.
- Node shutdown: stop loops, `destroyConsumerGroup` for this node's groups, close the dedicated connection.
- **Dead-node cleanup:** the reconciliation/reaper path (1.9.0-RC1 item ④) already runs once-per-dead-node
  under a claim; extend it so the winner also, for each `uri ∈ knownReliableStreams()`,
  `destroyConsumerGroup(uri, deadNodeId)` — so a crashed node's group + PEL don't leak.

### New group creation start position

`XGROUP CREATE … $` (new-messages-only) so a freshly-connected session doesn't receive old history.
A node that merely disconnected keeps its group (BUSYGROUP on re-create → resume from last-delivered = replay).
A node that cleanly shut down destroyed its groups → on restart it starts at `$` (correct: restarted node's
sessions are new). A crashed node's group persists until reaped.

## Config (new, under `server.netty.websocket.cluster.reliable.*`)

| Key | Default | Effect |
| --- | --- | --- |
| `enable` | `false` | Master gate. `false` ⇒ no consumer threads, no extra connection, `reliableBroadcast` throws. |
| `stream-max-len` | `10000` | `XADD … MAXLEN ~ {n}` per URI stream — bounds memory; the at-least-once **retention window**. |
| `poll-block-ms` | `2000` | `XREADGROUP BLOCK` timeout (loop responsiveness vs idle wakeups). |
| `poll-count` | `64` | `XREADGROUP COUNT` per read. |
| `dedup-window` | `1024` | Per-URI ring size of recently-acked entry-ids (PEL-redelivery dedup). |

Added to `ClusterProperties` (nested `Reliable`), `additional-spring-configuration-metadata.json`, api-guide §9, cluster-design.

## Delivery contract (documented)

- **At-least-once within the retention window.** A node offline longer than `stream-max-len` entries of
  history on a URI misses the trimmed entries (a **bounded gap**, logged when detectable via the stream's
  first-id vs the group cursor).
- **Durability = your Redis.** If Redis loses the stream (no AOF/RDB, or data loss), undelivered entries are lost.
- **Latency** is higher than Pub/Sub (Streams blocking read) — hence opt-in per message.
- **Ordering:** per-URI stream ⇒ per-URI total order across nodes.
- **Dedup:** origin self-suppression + entry-id ring make redelivery (PEL) idempotent at the cluster layer;
  application handlers should still be idempotent (standard at-least-once guidance).

## Auto-config wiring

- `ReliableBroker` `@Bean @ConditionalOnMissingBean`, created **only when `reliable.enable=true`**
  (`@ConditionalOnProperty`), constructed with a **dedicated** `StatefulRedisConnection` (its own, for blocking
  reads) derived from the existing `RedisClient`, plus the `EnvelopeCodec` and the reliable config.
- `ClusterMessageSender` gets the `ReliableBroker` (nullable when disabled) + the reliable config; `reliableBroadcast`
  and the reliable-subscribe lifecycle are no-ops/throws when absent.
- Dead-node cleanup hook wired into the existing reconciliation cleanup (post-reaper-claim).

## Testing

Mostly **real-Redis integration** (skipped without `localhost:16379`, per the existing pattern):
- **Headline (the whole point):** node B subscribes, then stops consuming (simulating DEGRADED); node A
  `reliableBroadcast`s N messages; B resumes → receives **all N** (vs. a Pub/Sub control that loses them).
- **Self-suppression:** origin does not double-deliver its own reliable broadcast.
- **PEL redelivery dedup:** a redelivered (unacked) entry is not delivered to the app twice.
- **New-vs-reconnect:** a brand-new group starts at `$` (no history flood); a reconnecting group replays backlog.
- **Dead-group cleanup:** after a node is reaped, its consumer group is destroyed (no PEL leak).
- **Disabled path:** `reliable.enable=false` ⇒ no `ReliableBroker` bean, `reliableBroadcast` throws, zero threads.
- **Unit:** an in-memory `ReliableBroker` stub exercises the self-suppression + dedup-ring logic without Redis;
  `ClusterMessageSender.reliableBroadcast` does local-fan-out-then-publish (asserted via stubs).

## Implementation phasing (for the plan)

1. `ReliableBroker`/`ReliableSubscription` SPI + `ClusterProperties.Reliable` + metadata.
2. `RedisStreamsReliableBroker`: XADD publish + XREADGROUP consume loop + self-suppression + dedup + dedicated conn.
3. `ClusterMessageSender.reliableBroadcast` + reliable-subscribe lifecycle (onLocalUriActive) + auto-config bean (gated).
4. Replay-on-resync + PEL one-time `0` read; dead-node group cleanup in reconciliation.
5. Integration + unit tests (above); docs (release-notes-1.9.0 reliable section, api-guide §9, cluster-design).

## Versioning

Part of the **1.9.0 cycle** (RC line). Lands on `1.9.0-SNAPSHOT`/`-RC` development; cut **`v1.9.0-RC2`** at this
feature's completion. **Final `1.9.0` is cut only when the user confirms the whole cycle is done.** Backward
compatible; disabled by default.
