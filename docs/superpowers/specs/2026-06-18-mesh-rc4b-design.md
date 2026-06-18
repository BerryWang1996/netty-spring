# RC4b — Interest-routed mesh broadcast (design)

**Status:** design (brainstorming output, pre design-review). **Branch:** `feature/1.10.0-mesh-rc4b` (off `v1.10.0-RC4a`).
**Module:** `netty-spring-websocket-cluster`. **Stack:** Boot 2.7 + Netty 4.1 + Lettuce 6.1, JDK 17.

---

## 1. Honest positioning (read this first)

RC4a moved cluster broadcast off Redis Pub/Sub onto direct node-to-node TCP, but `publish(uri)` sends to **every**
peer (naive). RC4b makes `publish` **interest-aware**: send only to peers that currently host a subscriber for that
URI. But the honest scope must be stated up front, because the naive intuition ("interest routing breaks the
~10-node ceiling") is **only conditionally true**:

- **Homogeneous deployment (the common case): interest routing yields ZERO recipient reduction for a global topic.**
  When the same app runs on every node, every node has a `@MessageMapping` listener for every broadcast URI, so the
  interest set for a global URI is *all* nodes. A global lobby broadcast still fans out to N−1 nodes — that is
  *inherent* to "everyone subscribes to this topic", and no routing layer can reduce it. For that case the mesh's win
  was already delivered by **RC4a**: the fan-out work is **decentralized** across each origin's NIC instead of
  funneled through one Redis Pub/Sub connection (the ~80k-decode / central-egress wall that caps Redis at ~10 nodes).
  RC4a is the structural ceiling-break for global broadcast; RC4b does not add to it there.

- **Where RC4b's reduction is real:** *sparse / partitioned* interest — support-only channels, admin URIs, per-tenant
  or per-feature topics that live on a subset of nodes; and the transient case of a node that holds a subscription
  but currently has zero local sessions. There `N_sub ≪ N` and routing to only the `N_sub` interested nodes is a
  genuine reduction (e.g. a 5-of-100-node support topic: 99→4 sends, ~20×). This is the same *locality-based* honesty
  as RC1 (per-room node-set) — reduction proportional to how partitioned the audience is, not an unconditional claim.

- **The IM ceiling-break is already composed.** The realistic high-scale IM workload is **room**-based, not a global
  firehose. RC1 `roomMessage(uri, room, ...)` already targets only member-hosting nodes via `broker.unicast(...)` —
  and over the RC4a mesh that unicast is *already a direct TCP send*. So **room broadcast is already mesh-native and
  optimal** once `mesh.enable=true`; RC4b does **not** change the room path. RC4b's job is the remaining **plain
  `topicMessage` / `publish`** path, so sparse/partitioned topics also stop paying all-peers fan-out.

**One-line scope:** RC4b makes `MeshBroker.publish(uri)` route to the interested-node set (via a new
`MeshInterestRegistry`, mirroring `ClusterRoomRegistry`) instead of all peers; it is a strict optimization that never
loses delivery relative to RC4a except for the inherent ≤cache-TTL freshly-subscribed-staleness window (RC1 parity).

---

## 2. Goal & non-goals

**Goal.** When `mesh.enable=true`, `publish(uri, env)` sends the framed envelope only to peers in
`interestedNodes(uri) ∩ live-mesh-membership`, instead of all peers. Interest is node-grained: a node is "interested
in `uri`" iff it has ≥1 local broadcast subscription for `uri`. The interested-node set is an authoritative,
Redis-backed registry with a short local send-cache (Redis off the hot path in steady state), mirroring RC1's
`RedisRoomRegistry` + `nodesForRoomCached`.

**Non-goals (RC4b).**
- No change to `roomMessage` (already mesh-native via unicast), `unicast`, `sendToUser`/offline, or presence wiring.
- No interest *gossip* over the mesh (considered + rejected below — staleness/correctness). Redis stays the interest
  source of truth.
- No hierarchical / relay-tree fan-out for genuinely-global topics (that is a future / sharded-pub-sub concern; the
  honest answer for a global all-subscribe topic remains "decentralized N−1 fan-out", delivered by RC4a).
- No change to at-most-once delivery semantics.

---

## 3. Approaches considered

**A. Redis-backed interest registry + local send-cache (RECOMMENDED).** A new SPI `MeshInterestRegistry` with a
default `RedisMeshInterestRegistry` that mirrors `RedisRoomRegistry` exactly: `SADD nodeId` to
`netty:interest:{b64uri}:nodes` on a node's *first* local subscription to `uri`, `SREM` on its *last* unsubscribe,
`removeAllForNode` on dead-node reap; `nodesForUri(uri)` read on publish, wrapped in a 5s local send-cache
(`nodesForUriCached`, mirroring `nodesForRoomCached`) with wholesale invalidation on `NODE_LEFT`.
- **Pro:** authoritative + atomic (single-key `SADD`/`SREM`, no Lua even needed — it's a plain set keyed by URI);
  identical idiom to the proven RC1 path; Redis off the hot path via the cache; correctness equals Redis Pub/Sub's
  subscription model (a node either is or isn't in the set). Reuses the existing dead-node-callback invalidation.
- **Con:** keeps Redis as the interest control plane (but *not* the message hot path — same as discovery/registry,
  consistent with the mesh thesis "Redis for control, off the message path").

**B. Mesh-gossiped interest.** Each node tells peers its subscribed-URI set on connect + on change; publishers consult
a locally-held peer-interest map; no Redis for interest.
- **Pro:** fully decentralized, no Redis for routing.
- **Con:** eventual-consistency **staleness is a correctness regression** — a freshly-subscribed node is invisible to
  publishers until gossip converges, and a partition/missed-gossip can drop broadcasts indefinitely (no
  authoritative source to reconcile against). Significant new machinery (anti-entropy, versioning) for a path the
  Redis registry already solves. The `/goal` text says "gossip", but the codebase's correctness bar + RC1/RC2/RC3
  precedent favor the authoritative registry. **Rejected** for RC4b; revisit only if a Redis-free mesh is a goal.

**C. Hybrid (Redis source of truth + mesh interest-change notifications for fast cache invalidation).** Option A plus
a lightweight mesh "interest-changed" notification on first-subscribe/last-unsubscribe so publishers refresh
immediately instead of waiting ≤TTL.
- **Pro:** shrinks the freshly-subscribed-staleness window from ≤TTL to ~RTT.
- **Con:** extra protocol + ordering/race handling; the ≤TTL window is already accepted by RC1. **Deferred** to a
  future refinement (RC4c) — RC4b ships A and documents the ≤TTL window (RC1 parity).

**Decision: A.** It is correct, atomic, consistent with the codebase, keeps Redis off the message hot path via the
existing cache pattern, and reuses dead-node invalidation. C is the natural follow-on if the staleness window matters.

---

## 4. Components

### 4.1 `MeshInterestRegistry` SPI (new)

```java
public interface MeshInterestRegistry {
    /** This node now hosts a local subscriber for {@code uri} (call on first-local-subscription transition). */
    CompletionStage<Void> subscribe(String uri, String nodeId);
    /** This node no longer hosts any local subscriber for {@code uri} (call on last-unsubscription transition). */
    CompletionStage<Void> unsubscribe(String uri, String nodeId);
    /** Remove all of a (dead) node's interest entries — called on dead-node reap. */
    CompletionStage<Void> removeAllForNode(String nodeId);
    /** Node-set that currently hosts a subscriber for {@code uri} (the routing primitive). */
    CompletionStage<Set<String>> nodesForUri(String uri);
    void shutdown();
}
```

Mirror of `ClusterRoomRegistry` minus the room dimension and the per-session bookkeeping (interest is node-grained,
toggled on the 0↔1 *local* subscription-count transition, which the broker already detects). No `localMembers`
equivalent is needed (the broker's own `broadcastListeners` is the local interest view).

### 4.2 `RedisMeshInterestRegistry` (default impl)

**Redis key schema** (mirror `RedisRoomRegistry`'s base64url + suffix convention):
```
netty:interest:{b64uri}:nodes   →  Set<nodeId>   (the routing set; b64uri = base64url(uri), delimiter-safe)
```
- `subscribe(uri, nodeId)`  → `SADD netty:interest:{b64uri}:nodes nodeId`
- `unsubscribe(uri, nodeId)`→ `SREM ...; if SCARD==0 then DEL` (tidy empty keys; a tiny Lua or SREM+conditional DEL)
- `nodesForUri(uri)`        → `SMEMBERS netty:interest:{b64uri}:nodes`
- `removeAllForNode(nodeId)`→ SCAN `netty:interest:*:nodes` + `SREM nodeId` (same shape as room `removeAllForNode`;
  reaped on the leader-elected dead-node path, off the hot path)

Single key per URI ⇒ no cross-slot concern (no hash-tag needed; one key is one slot). Dedicated executor like the
other Redis SPIs. No per-session set (unlike rooms) because interest flips only on the node's local 0↔1 transition —
the broker tracks local subscription counts, so the registry only stores node membership.

### 4.3 `MeshBroker.publish` — interest routing

```java
public void publish(String uri, ClusterEnvelope envelope) {
    checkActive();
    String wrapped = authenticator.wrap(codec.encode(envelope));
    Map<String,String> peers = directory.peers(nodeId).toCompletableFuture().join();  // live ∩ has-address
    Set<String> interested = interestRouter.nodesForUriCached(uri);   // null => registry unavailable => fall back
    for (Map.Entry<String,String> e : peers.entrySet()) {
        if (interested != null && !interested.contains(e.getKey())) {
            continue;   // RC4b: skip peers with no subscriber for this uri
        }
        sendTo(e.getKey(), e.getValue(), wrapped);
    }
}
```

- `interested == null` (registry absent, or a read failure/timeout) ⇒ **fall back to RC4a all-peers** (logged once;
  safe — never loses delivery). Interest routing is thus a *strict optimization*: a successful read prunes
  uninterested peers; any uncertainty degrades to RC4a behavior, not to a miss.
- A **successful but stale** read can still miss a peer that subscribed within the last ≤TTL (the inherent window,
  §6). Local fan-out (origin's own sessions) is unaffected — it happens before `publish` in `ClusterMessageSender`.
- The interest filter is applied to the *live-membership* peer map, so a dead peer is already excluded (RC4a MF1).

The cache + registry live in a small `MeshInterestRouter` collaborator injected into `MeshBroker` (keeps the broker
focused; mirrors how `ClusterMessageSender` owns `nodesForRoomCached`). `nodesForUriCached` = the RC1
`nodesForRoomCached` pattern verbatim (5s TTL `CachedNodeSet`, lookup-timeout-bounded, wholesale-cleared on
`NODE_LEFT`). When the registry bean is absent (custom mesh w/o interest), the router is a no-op returning `null` ⇒
all-peers.

### 4.4 Subscription wiring (`ClusterMessageSender`)

Interest must be announced to the registry exactly on the node's **0↔1 local-subscription transitions** for a URI:
- **subscribe(uri):** in `subscribeBroadcast(uri)` (first subscription for `uri` on this node) ⇒
  `interestRegistry.subscribe(uri, nodeId)`. The existing `broadcastSubscriptions.computeIfAbsent` already fires
  exactly once per URI — the natural 0→1 hook.
- **unsubscribe / hold-expiry:** when the broker drops the last local subscription for `uri` ⇒
  `interestRegistry.unsubscribe(uri, nodeId)`. (Today subscriptions are held; RC4b ties interest-remove to the same
  point the broker actually stops listening, so a held subscription stays "interested" — correct, it would still
  deliver locally.)
- **dead node:** `invalidateCacheForNode(deadNodeId)` already clears the room cache + calls
  `roomRegistry.removeAllForNode`; add the symmetric `interestRegistry.removeAllForNode(deadNodeId)` +
  `interestNodeSetCache.clear()` on the same leader-elected reaped path.
- **startup:** for each `localSender.getRegisteredUri()` already subscribed in `start()`, the `subscribeBroadcast`
  call announces interest — so a node advertises its full interest set at boot.

### 4.5 Reserved channels compose correctly

- **Presence** (`PRESENCE_CHANNEL`): every presence-enabled node calls `broker.subscribe(PRESENCE_CHANNEL, ...)` at
  start ⇒ each registers interest in `PRESENCE_CHANNEL` ⇒ `nodesForUri(PRESENCE_CHANNEL)` = all presence-enabled
  nodes = exactly the correct audience. Presence events still reach every watcher. ✔
- **Room** (`ROOM_BROADCAST`): goes through `broker.unicast(targetNode, ...)`, **not** `publish` — untouched by
  interest routing. ✔
- **Offline / `sendToUser`:** unicast/user-targeted, not topic `publish` — untouched. ✔

---

## 5. Config & auto-config

| Key | Default | Meaning |
|---|---|---|
| `server.netty.websocket.cluster.mesh.interest-routing.enable` | `true` | When `true` (and `mesh.enable=true`), `publish` routes to interested peers; `false` forces RC4a all-peers (debug / escape hatch). |
| `server.netty.websocket.cluster.mesh.interest-routing.node-set-cache-ttl-ms` | `5000` | Local send-cache TTL for `nodesForUri` (mirrors `room.node-set-cache-ttl-ms`). Lower = fresher interest, more Redis reads. |

Auto-config (`NettyWebSocketClusterConfigure`): a `RedisMeshInterestRegistry` bean gated on
`STANDALONE_MESH_BROKER and interest-routing.enable` + `@ConditionalOnMissingBean(MeshInterestRegistry.class)`,
wired into the `MeshBroker`/router and into `ClusterMessageSender`'s subscribe/unsubscribe/dead-node hooks.
`OnAnyRedisSpiRequired` gains a mesh-interest clause so the Redis connection stays up for it (same shape as the mesh
clause). When `mesh.enable=false` the bean never exists — byte-identical to RC1/RC2/RC3/RC4a.

---

## 6. Consistency & failure semantics

- **At-most-once preserved.** Interest routing only changes *which* peers a `publish` contacts; delivery to a
  contacted peer is unchanged.
- **Freshly-subscribed ≤TTL window (RC1 parity).** A node that subscribes to `uri` may be missed by remote
  publishers for up to `node-set-cache-ttl-ms` (their cache hasn't refreshed). Identical to RC1's
  just-joined-room-member window and to Redis Pub/Sub's own subscribe-propagation latency. Documented; mitigation is
  the deferred approach C. Local delivery on the subscribing node is immediate.
- **Registry read failure ⇒ RC4a fallback.** Never a missed delivery from a Redis blip — the publish degrades to
  all-peers, logged + metered.
- **Dead peer.** Excluded by live-membership (RC4a MF1) immediately for *reachability*, and pruned from the interest
  registry on the leader-elected reap (`removeAllForNode`) + wholesale cache clear (RC1 parity). A stale interest
  entry for a dead node at worst causes one `sendTo` that the membership filter already drops.
- **Backpressure (RC4a M1).** Unchanged; a slow interested peer still drops+counts past the watermark.

---

## 7. Metrics (RC4b)

Internal counters now (surfaced to `netty.cluster.mesh.*` in RC4d, consistent with RC4a's deferral):
- `mesh.broadcast.target_nodes` — interested peers targeted per `publish` (the reduction observable, mirrors
  `netty.cluster.room.fanout.target_nodes`).
- `mesh.broadcast.fanout_fallback` — publishes that fell back to all-peers (registry unavailable) — watch this; a
  high value means interest routing is not actually engaging.

---

## 8. Backward compatibility

- `mesh.enable=false` ⇒ no mesh, no interest registry — byte-identical to RC1/RC2/RC3.
- `mesh.enable=true, interest-routing.enable=false` ⇒ RC4a naive all-peers (the prior RC4a behavior exactly).
- New SPI `MeshInterestRegistry` is additive + `@ConditionalOnMissingBean`. Same envelope wire (no version bump);
  a mesh node with interest routing and one without inter-operate (the without-routing node just receives + drops
  uninterested URIs as in RC4a; the with-routing node prunes — both safe).

---

## 9. Testing

- `MeshInterestRegistry` SPI + an `InMemoryMeshInterestRegistry` test stub (mirror `InMemoryMeshNodeDirectory`).
- `RedisMeshInterestRegistry`: SADD/SREM/SCARD-DEL/`nodesForUri`/`removeAllForNode` unit (mock Lettuce) + a real-Redis
  IT (assumeTrue-gated, like `RedisMeshNodeDirectoryTest`).
- `MeshBroker.publish` interest routing: a peer **with** interest receives, a peer **without** is skipped; registry
  `null` ⇒ all-peers fallback; live-membership ∩ interest (a dead-but-interested peer is not contacted).
- Two-node real-TCP-via-real-Redis E2E (extend `MeshTwoNodeE2ETest`): node B subscribes to `/ws/a` only; A's
  publish to `/ws/a` reaches B, A's publish to `/ws/b` (B not interested) does **not** reach B; flip B's interest and
  re-assert.
- `ClusterMessageSender` wiring: first-subscribe announces interest, last-unsubscribe removes it, dead-node reap
  calls `removeAllForNode` + clears the cache.
- Context test (`NettyWebSocketClusterConfigureTest`): `mesh.enable=true` ⇒ interest registry present + wired;
  `interest-routing.enable=false` ⇒ absent (all-peers); `mesh.enable=false` ⇒ absent.
- Honesty regression: a homogeneous all-subscribe topic targets all peers (no false reduction); a sparse topic
  targets only the subscribed subset.

---

## 10. Deferred

- **Approach C** (mesh interest-change notifications to shrink the ≤TTL window) → RC4c.
- **Full `netty.cluster.mesh.*` meters** (incl. `broadcast.target_nodes`, `fanout_fallback`, + the RC4a counters
  fixed to the shared `ClusterRuntimeStats`) → RC4d.
- Hierarchical/relay-tree fan-out for genuinely-global topics (out of mesh scope; sharded pub/sub territory → 2.0.0).
- Per-tenant / wildcard interest aggregation (YAGNI now).

---

## 11. Risks / open questions for design-review

1. **Is the ≤TTL freshly-subscribed window acceptable for plain topic broadcast** the same way RC1 accepts it for
   rooms? (Local delivery is immediate; only cross-node freshly-subscribed nodes miss, ≤TTL, at-most-once.) Lean yes
   (RC1 parity), but a reviewer should confirm no path makes it worse than RC1.
2. **Interest-remove timing vs subscription-hold.** If the broker holds a subscription after the last session leaves,
   interest should stay until the actual unsubscribe — verify the hook fires at the right transition so a held-but-
   zero-session node still receives (it would deliver locally) and isn't prematurely pruned.
3. **`removeAllForNode` SCAN cost** over `netty:interest:*:nodes` — same shape/cost as room `removeAllForNode`;
   confirm it stays on the leader-elected reaped path (off the hot path) and is acceptable at the documented scale.
4. **Honesty framing** — ensure the release notes + cluster-design state plainly that global-topic reduction is ~0
   and the win is sparse-interest + already-mesh-native rooms, so RC4b is not over-claimed (RC1-style scope honesty).
