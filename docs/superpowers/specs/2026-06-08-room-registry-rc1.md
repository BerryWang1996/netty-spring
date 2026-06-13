# ClusterRoomRegistry — per-room node-targeted routing (1.10.0-RC1) — Design Spec

**Target:** netty-spring 1.10.0-RC1
**Branch:** `feature/1.10.0-room-registry` (off 1.9.0 GA master)
**Status:** approved 2026-06-08 — brainstormed + 5-lens adversarial design review (verdict FIX_DESIGN_FIRST → pivoted from shard-ring to per-room node-set targeting). Review archived at `docs/superpowers/notes/2026-06-08-room-registry-design-review.json`.

---

## 0. What changed from the first design (and why)

The first design used a **consistent-hashing shard ring**: rooms hashed onto S=256 shards, nodes subscribed
to shards they host members for. The adversarial review **quantitatively killed it**: under default random
load-balancer placement (users connect to random nodes), a shard *aggregates* many rooms, so every node
ends up hosting members of some room in nearly every shard → subscribes to ~99.4% of shards → **zero
fan-out reduction, collapses to global broadcast.** The project's own `cluster-design.md` already states
regular cluster pub/sub doesn't reduce fan-out — shard aggregation has the same flaw.

**This spec uses per-room node-set targeting instead**, which actually reduces fan-out:
- The registry tracks, per room, **the set of nodes hosting ≥1 member** of that room.
- `roomMessage(uri, room, msg)` looks up that node-set and **targets only those nodes** (reusing the
  existing 1.9.0 per-node unicast channel) — not a shard, not all N nodes.
- Reduction = **N/k** where k = distinct nodes with members. For bounded rooms in large clusters this is a
  real, large reduction (e.g. 5-member room in a 100-node cluster → ~5 nodes targeted → ~20× reduction),
  **even under random LB placement**, because we target the room's *actual* node-set, not an aggregated shard.

Bonus: targeting via the **existing per-node unicast channel** means **no shard subscribe/unsubscribe
churn** — the lifecycle FLAW findings from the review (debounce races, refcount subscriptions) **disappear**
because there is no new per-shard subscription at all. Every node already subscribes to its own unicast
channel (1.9.0 `broker.subscribeUnicast(nodeId, …)`).

## 1. Goal

Add room-scoped message routing + distributed room membership to the WebSocket cluster, with **honest,
real fan-out reduction proportional to room concentration**. Serves IM dogfooding (rooms = the IM
primitive) and is the membership/routing foundation that future affinity (RC4 mesh) builds on.

**Honest positioning (must appear verbatim in README + release notes):**
> Room-scoped routing + per-room node-targeted delivery (opt-in). A room message reaches only the nodes
> that host members of that room, so fan-out drops to N/k (k = nodes with members) — a real reduction for
> bounded rooms in large clusters, even under random load-balanced placement. A "hot" room whose members
> span every node sees no reduction (and, publish-side, costs k≈N targeted sends vs 1 global publish) — this
> is a true property, documented, metered, and benchmarked, not hidden. This is **not** an unconditional
> "scales to 100 nodes" claim; it is "scales with room locality."

## 2. Core model

- **Room = sub-dimension within a `@MessageMapping` URI.** One `/ws/chat` endpoint, unlimited rooms. A
  session may be in **many** rooms. Routing key = `(uri, room)`.
- **Per-room node-set** is the routing primitive. `roomMessage` targets the node-set; receivers fan out to
  their local members.
- **Opt-in.** `cluster.room.enable=false` (default) → no room beans, no new runtime path, byte-identical to
  1.9.0. Rooms are a new additive API + bean.

## 3. SPI surface

`spi/ClusterRoomRegistry.java` (new, additive — no change to existing SPIs):

```java
public interface ClusterRoomRegistry {
    /** Add a session (owned by nodeId) to a room. Atomic. */
    CompletionStage<Void> join(String uri, String room, String sessionId, String nodeId);

    /** Remove a session from a room. When it was the room's last member on nodeId,
     *  nodeId is atomically removed from the room's node-set. Atomic. */
    CompletionStage<Void> leave(String uri, String room, String sessionId, String nodeId);

    /** The set of nodeIds currently hosting >=1 member of the room (the routing primitive).
     *  Cacheable by the caller with a short TTL (see cluster.room.node-set-cache-ttl-ms). */
    CompletionStage<Set<String>> nodesForRoom(String uri, String room);

    /** Local sessionIds in a room on THIS node (receive-side fan-out; served from a local index,
     *  no I/O). Never null. */
    Set<String> localMembers(String uri, String room);

    /** Rooms a local session is in (for disconnect cleanup; local index). */
    Set<String> roomsForSession(String uri, String sessionId);

    /** Atomically remove a session from ALL its rooms (single Lua call, not N leave() calls). */
    CompletionStage<Void> removeAllForSession(String uri, String sessionId, String nodeId);

    /** Remove a dead node from every room's node-set + member sets (single bulk op). */
    CompletionStage<Void> removeAllForNode(String nodeId);

    void shutdown();
}
```

## 4. Components

| File | Role |
|---|---|
| `spi/ClusterRoomRegistry.java` **(new SPI)** | The interface above. |
| `room/RedisRoomRegistry.java` **(new, default)** | Redis impl. Atomic Lua for join/leave/removeAll. Local index for hot-path. See §5. |
| `room/InMemoryRoomRegistry.java` **(new, test stub)** | Non-Redis, proves `ClusterMessageSender` doesn't leak Lettuce (mirrors `InMemorySessionRegistry`). |
| `ClusterMessageSender.java` **(touched)** | New `roomMessage(uri, room, msg)` (send) + `onRoomMessage` (receive, dispatched from the existing unicast subscription) + node-set cache + room registry wiring in start/shutdown. |
| `spi/ClusterEnvelope.java` **(touched)** | Add `room` field + `ROOM_BROADCAST` MessageKind. **Co-bump `CURRENT_VERSION` 1→2 and the codec FIELD_COUNT in lockstep** (see §7). |
| `codec/SimpleTextEnvelopeCodec.java` + `codec/DefaultMessagePayloadCodec.java` **(touched)** | Encode/decode the room field, version-aware. |
| `ClusterProperties.java` + metadata json **(touched)** | `room.*` config keys. |
| `NettyWebSocketClusterConfigure.java` **(touched)** | Gated `clusterRoomRegistry` bean (`@ConditionalOnProperty room.enable=true` + `@ConditionalOnMissingBean`), wired into `ClusterMessageSender`. |
| `metrics/NettyClusterMeterBinder.java` **(touched)** | Room meters (§8). |

**Deferred to a later RC** (mirrors 1.9.x Redis-first cadence): `NatsKvRoomRegistry` (all-NATS room
parity). RC1 is Redis-only; the SPI is transport-agnostic so NATS slots in later with no API change.

## 5. Redis data model + atomicity

Keys (all under the existing cluster namespace, URI + room base64url-encoded like RC11's registry fix):

- `netty:room:{b64uri}:{b64room}:nodes` — **Set<nodeId>**: which nodes host ≥1 member. *The routing key.*
- `netty:room:{b64uri}:{b64room}:n:{nodeId}` — **Set<sessionId>**: members of this room on a specific node
  (the per-node member refcount; lets us know when a node drops to 0 members and must leave the node-set).
- `netty:roomsession:{b64uri}:{sessionId}` — **Set<room>**: rooms a session is in (for `removeAllForSession`).

**Atomic Lua scripts** (reuse the RC11 atomic-deregister pattern — no looped SREMs):
- `JOIN_LUA(uri, room, sid, node)`: `SADD n:{node} sid`; if that set's card was 0 before → `SADD nodes node`;
  `SADD roomsession sid? room` (track room for the session). Single EVAL.
- `LEAVE_LUA(uri, room, sid, node)`: `SREM n:{node} sid`; if now empty → `SREM nodes node` + `DEL n:{node}`;
  `SREM roomsession room`. Single EVAL.
- `REMOVE_ALL_FOR_SESSION_LUA(uri, sid, node)`: read `roomsession`, for each room SREM from `n:{node}` and
  conditionally SREM nodes; DEL `roomsession`. Single EVAL (bounded by the session's room count).
- `REMOVE_ALL_FOR_NODE`: SCAN `netty:room:*:n:{node}` → for each, the room's nodes-set loses `{node}` and the
  per-node member set is deleted. Driven by `ClusterReaper`'s existing dead-node hook (parallels
  `SessionRegistry.removeAllForNode`); SCAN+pipelined EVAL, idle-gated like RC11.

**Local index** (per node, in-process): `ConcurrentHashMap<(uri,room), Set<sessionId>>` (local members) +
`ConcurrentHashMap<(uri,sessionId), Set<room>>` (rooms per local session). Updated **after** the Lua call
confirms, so the local index never claims membership Redis rejected. Serves `localMembers` /
`roomsForSession` with zero I/O on the receive hot path.

> Redis-Cluster note (RC7 compatibility): the per-room keys share a hash tag `{b64uri}:{b64room}` so the
> nodes-set + per-node member sets for one room co-locate on one slot — the JOIN/LEAVE Lua is single-slot.
> The cross-room `removeAllForNode` SCAN is cluster-aware (already solved for `RedisClusterModeSessionRegistry`).

## 6. Data flow

### Join (session enters room)
`roomRegistry.join(U,R,sid,node)` → JOIN_LUA → on success update local index. **No new subscription** — the
node already receives room messages on its existing per-node unicast channel.

### Room broadcast — `roomMessage(U, R, msg)`
1. **Local fan-out first** to `localMembers(U,R)` — always, even if degraded (same contract as `topicMessage`).
2. Gate on `nodeManager.state()==ACTIVE && broker.state()==ACTIVE` (same as RC14 P1).
3. `Set<String> targets = nodesForRoomCached(U,R)` minus self. (Cached with `node-set-cache-ttl-ms`, like
   the unicast `registry-read-cache-ttl-ms`; invalidated on NODE_LEFT.)
4. Build envelope `(uri=U, room=R, kind=ROOM_BROADCAST, originNodeId=self)`, HMAC-wrapped + traceparent
   (reuse `buildBroadcastEnvelope` + set room).
5. For each target node: `broker.publishToNode(nodeId, envelope)` — reuse the existing per-node unicast
   transport (the same channel `subscribeUnicast` listens on). Size-cap + on-publish-failure per existing policy.
6. Stats: `room.broadcast.published++`, `room.fanout.target_nodes` records `targets.size()`.

### Receive — `onRoomMessage` (dispatched from the existing unicast subscription)
The existing `onUnicastMessage` path inspects `envelope.kind`: `UNICAST`/`CLOSE` as today; **`ROOM_BROADCAST`
→ `onRoomMessage`**: read `(uri, room)`, origin self-suppress, fan-out to `localMembers(uri,room)` only.
A node that no longer hosts members of R (membership changed in-flight) fans out to an empty set — counted
as `room.fanout.stale_target` (honest waste meter).

### Crossover honesty (publish-side cost)
For a room whose members span **k≈N** nodes (hot/global room), `roomMessage` issues k≈N targeted publishes
vs `topicMessage`'s 1 global publish. So per-room targeting **wins on delivery** (k nodes vs N) but **costs
more on publish** when k is large. **Documented crossover:** for rooms expected to span most nodes, use
`topicMessage(uri, msg)` (global) instead. RC1 does not auto-switch; it documents + meters the crossover.
(Auto-switch at a `k/N` threshold is a noted future option, not in RC1.)

## 7. Envelope v2 — backward-compatible, co-bumped

`ClusterEnvelope`: add `String room` (null for non-room messages) + `MessageKind.ROOM_BROADCAST`.

**Critical (review finding):** `SimpleTextEnvelopeCodec` splits on a fixed field count **before** checking
version. Therefore `CURRENT_VERSION` (1→2) and the codec's `FIELD_COUNT` (8→9) **must be bumped in lockstep**
in the same commit. Decoding rules:
- A **v2 decoder reading a v1 wire** (8 fields): tolerate — room=null, kind from the v1 set.
- A **v1 decoder (1.9.0 node) reading a v2 wire** (9 fields): the existing codec already guards
  "discard envelopes with version > max supported" — **verify the field-count split happens AFTER a
  version peek, or restructure the v2 wire so the version token is parseable first.** If the current codec
  splits-then-checks, the v2 wire MUST keep the version token in a position the v1 splitter still reads,
  and the v1 node discards on version>1. **A mixed-version rolling-upgrade test (1.9.0 node + 1.10.0 node
  in one cluster) is mandatory** and is the RC's correctness gate.
- `room.enable=false` still ships envelope v2 in the codec, but **no ROOM_BROADCAST envelopes are ever
  produced**, so a pure-1.9.0-behavior deployment emits only v1-shaped wires (room appended as empty/last
  field only when present). The "byte-identical when disabled" claim is scoped to: no ROOM_BROADCAST
  produced, no room beans, no room subscriptions. The codec carrying a v2-capable path is inert.

> If the rolling-upgrade test cannot be made green with an append-to-v1 wire, fall back to a **distinct v2
> codec path** keyed by a leading version token, leaving the v1 format byte-for-byte untouched. The
> implementer picks based on what the real codec allows; the IT decides.

## 8. Config + metrics + honesty surfaces

**Config** (`server.netty.websocket.cluster.room.*`):
| Key | Default | Meaning |
|---|---|---|
| `enable` | `false` | Master switch. False = no room beans, byte-identical-behavior to 1.9.0. |
| `node-set-cache-ttl-ms` | `5000` | Local cache TTL for `nodesForRoom` on the send hot path (mirrors `registry-read-cache-ttl-ms`). |

**Metrics** (`netty.cluster.room.*`):
| Meter | Meaning |
|---|---|
| `broadcast.published` (counter) | Room broadcasts sent. |
| `broadcast.received` (counter) | Room broadcasts received + locally delivered. |
| `fanout.target_nodes` (distribution summary) | Nodes targeted per room broadcast — **the reduction meter** (compare to cluster size). |
| `fanout.stale_target` (counter) | Room broadcasts received but zero local members (membership churned in-flight = wasted delivery). |
| `members.local` (gauge) | Total local room memberships on this node. |

**Honest docs (mandatory):** release-notes + cluster-design section states the N/k reduction model, the
hot-room no-reduction + publish-side crossover, the balanced/bounded-room assumption, and points operators
at `fanout.target_nodes` to **see** whether they're getting reduction.

## 9. Testing

| Test | Coverage |
|---|---|
| `RedisRoomRegistryTest` (Mockito) | JOIN/LEAVE/removeAll Lua invoked (not looped SREMs); node-set add on first member / remove on last; local index consistency. |
| `RoomRegistryIntegrationTest` (Testcontainers Redis) | join/leave/nodesForRoom round-trip; removeAllForSession atomic; removeAllForNode dead-node cleanup; concurrent join/leave on one room stays consistent. |
| `ClusterRoomE2ETest` (two-node real Redis) | **Headline:** node A `roomMessage(R)` where R has a member on B → B receives; a third node NOT hosting R does **not** receive (the reduction assertion). Plus origin self-suppression + HMAC on. |
| `EnvelopeRollingUpgradeTest` | **Mandatory:** v1 codec decodes/ignores v2 wire (discard on version>max); v2 codec decodes v1 wire (room=null). Mixed-version safety. |
| `RoomFanoutBenchmark` (manual harness, parallels PerformanceBenchmark) | **3 scenarios, all published:** (1) favorable — many bounded rooms, show N/k reduction; (2) adversarial — large rooms spread across all nodes, show reduction → 1 + publish-side cost; (3) hot room on every node — baseline. |
| `InMemoryRoomRegistry` | no-Lettuce-leak proof in the SPI-isolation test. |
| `NettyWebSocketClusterConfigureTest` (+context cases) | room.enable=true → registry bean present + wired; room.enable=false → absent, byte-identical path. |

## 10. Backward compatibility

- `room.enable=false` (default): no room beans, no room subscriptions, no ROOM_BROADCAST produced →
  behavior identical to 1.9.0. Envelope codec gains a v2-capable path but emits v1-shaped wires absent rooms.
- Envelope v2 is rolling-upgrade-safe (gated by the mandatory `EnvelopeRollingUpgradeTest`).
- `ClusterRoomRegistry` is purely additive; no existing SPI signature changes; `MessageSender` interface
  unchanged (`roomMessage` is a new method on the cluster sender, not the base interface — TBD in plan
  whether it belongs on a `RoomMessageSender` sub-interface to keep `MessageSender` focused; see §11).
- Boot 2.7 + Lettuce 6.1 only. No Boot 3.x dependency.

## 11. Open implementation decisions (resolved in the plan, not blockers)

1. **API placement:** `roomMessage` on `ClusterMessageSender` directly, vs a separate `RoomMessageSender`
   sub-interface. Lean: a small `RoomOperations` interface that `ClusterMessageSender` implements, keeping
   the base `MessageSender` untouched. Plan decides.
2. **`publishToNode`:** confirm the exact 1.9.0 unicast publish entrypoint to reuse for node-targeting
   (the channel `subscribeUnicast` listens on). Plan grounds this against `RedisPubSubBroker`.
3. **Hash tag exactness** for Redis-Cluster single-slot JOIN/LEAVE — plan verifies against
   `RedisClusterModeSessionRegistry`.

## 12. Scope summary

**In:** ClusterRoomRegistry SPI + RedisRoomRegistry (atomic Lua) + InMemoryRoomRegistry stub +
`roomMessage`/`onRoomMessage` wiring via existing unicast channel + node-set cache + envelope v2 (co-bumped) +
config + metrics + honest docs + 3-scenario benchmark + rolling-upgrade test.

**Out (deferred):** NatsKvRoomRegistry (later RC), room→home-node affinity (RC4 mesh), room-level reliable
delivery, per-room history (RC2), room presence (RC3), auto global/targeted crossover switch.

---

## Spec self-review

- **Placeholder scan:** §11 has 3 "resolved in plan" items — these are genuinely plan-level (API placement,
  exact reuse entrypoint, hash-tag detail), not spec gaps; each has a stated lean. Acceptable.
- **Internal consistency:** the pivot (per-room node-set, no shards) is consistent across §0/§2/§5/§6;
  the lifecycle-FLAW simplification (reuse unicast channel, no shard subscription) is reflected in §4/§6.
  Metrics (§8) match the honesty claims (§1). Envelope co-bump (§7) matches the review's load-bearing finding.
- **Scope check:** single feature, Redis-only, additive SPI. Plan-sized. NATS + affinity explicitly deferred.
- **Ambiguity check:** the one real ambiguity (envelope wire compat) is resolved with a concrete rule +
  a mandatory test that decides between append-to-v1 vs distinct-v2-codec — the implementer follows the
  real codec, the IT gates correctness. Not left vague.
- **Honesty:** the N/k model, hot-room no-reduction, and publish-side crossover are stated in §1/§6/§8 —
  no false ceiling claim survives. This is the corrected, defensible credential story.
