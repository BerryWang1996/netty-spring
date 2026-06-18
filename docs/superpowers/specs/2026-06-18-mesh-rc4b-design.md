# RC4b — Interest-routed mesh broadcast (design)

**Status:** design — **design-review folded** (v2). **Branch:** `feature/1.10.0-mesh-rc4b` (off `v1.10.0-RC4a`).
**Module:** `netty-spring-websocket-cluster`. **Stack:** Boot 2.7 + Netty 4.1 + Lettuce 6.1, JDK 17.

> **Design-review correction (recorded — this project's honest-engineering bar, like RC1's shard-ring→node-set).**
> The v1 spec routed interest at the **registered-`@MessageMapping` grain** (announce once at boot per mapped URI,
> never retract). A 31-agent adversarial design-review (`docs/superpowers/notes/2026-06-18-rc4b-mesh-design-review.json`)
> proved that grain delivers **ZERO reduction on a homogeneous fleet** (every node maps every URI ⇒ interest = all
> nodes, forever) — the reduction would have required a *heterogeneous build* (mappings absent from some nodes' jars),
> an unstated precondition contradicting "same app on every node." The fix folded here: **interest is now
> session-grained** — a node is interested in `uri` iff it currently has **≥1 live local session** for `uri` — so the
> reduction is genuine for partitioned live audiences **even on a homogeneous build**, the `unsubscribe` path becomes
> real (it was dead code), and the v1 "zero-session-but-subscribed" claim becomes true. Also folded: presence
> reserved-channel interest (was never registered ⇒ would have silently dropped all cross-node presence), the
> null-vs-empty failure sentinel (v1 would have dropped a whole broadcast on a Redis blip), atomic-Lua unsubscribe, an
> honest `removeAllForNode` cost statement, and the redundant `OnAnyRedisSpiRequired` clause. See §12 for the
> finding-by-finding resolution.

---

## 1. Honest positioning (read this first)

RC4a moved cluster broadcast off Redis Pub/Sub onto direct node-to-node TCP, but `publish(uri)` sends to **every**
peer (naive). RC4b makes `publish` **interest-aware**: send only to peers that **currently host a live session** for
that URI. The honest scope, stated up front:

- **Global topic (audience on every node): ZERO recipient reduction — and that is correct.** When a topic genuinely
  has live sessions on every node (a global lobby), the interest set *is* all nodes; no routing layer can reduce a
  broadcast whose audience is everyone. For that case the mesh's win was already delivered by **RC4a**: the fan-out
  work is **decentralized** across each origin's NIC instead of funneled through one Redis Pub/Sub connection (the
  ~80k-decode / central-egress wall that caps Redis at ~10 nodes). RC4a is the structural ceiling-break for global
  broadcast; RC4b does not add to it there.

- **Where RC4b's reduction is real (and now genuine on a homogeneous fleet): _partitioned live audiences_.** Because
  interest is **session-grained**, a node that has the `@MessageMapping` for `/ws/support` but **no live support
  session right now** is **not** in the interest set and is **not** contacted. So a support topic whose agents are
  connected to 4 of 100 nodes routes `99 → 3` peer sends (~25×) **even though all 100 nodes run the same jar** — the
  reduction tracks where the *live audience* actually is, not where the code is deployed. Same applies to per-tenant
  topics (sessions for tenant X land on a subset of nodes), admin/ops channels (few operators), and the transient
  case of a node whose last session for a URI just left. Reduction is proportional to how *partitioned the live
  audience* is — the same locality honesty as RC1's per-room node-set, now for plain topics.

- **The IM ceiling-break is composed across RCs, not delivered solely by RC4b.** RC1 `roomMessage(uri, room, ...)`
  already targets only member-hosting nodes via `broker.unicast(...)`, which over the RC4a mesh is **already a direct
  TCP send** — so **room broadcast is already mesh-native and optimal** once `mesh.enable=true`; RC4b does **not**
  touch the room path. RC4b's job is the remaining plain `topicMessage` / `publish` path, so *non-room* partitioned
  topics also stop paying all-peers fan-out.

- **Use rooms, not per-entity URIs, for high-cardinality topics.** Session-grained interest creates one Redis set
  per *distinct URI*. That is the right tool for a **moderate** number of partitioned topics (per-tenant, per-feature,
  support/admin) — tens to low-hundreds. A per-conversation `/ws/conv-{id}` design (millions of URIs) must use **RC1
  rooms** (one `/ws/chat` URI + many rooms), which are built for exactly that cardinality. Stated so operators don't
  explode the interest keyspace.

**One-line scope:** RC4b makes `MeshBroker.publish(uri)` route to `interestedNodes(uri) ∩ live-mesh-membership` (via a
new `MeshInterestRegistry`, mirroring `ClusterRoomRegistry`), where interest tracks **live local sessions**. It is a
strict optimization that never loses delivery relative to RC4a except the inherent ≤cache-TTL freshly-subscribed
window (RC1 parity), and degrades to RC4a all-peers on any registry uncertainty.

---

## 2. Goal & non-goals

**Goal.** When `mesh.enable=true` and `interest-routing.enable=true`, `publish(uri, env)` sends the framed envelope
only to peers in `interestedNodes(uri) ∩ live-mesh-membership`, instead of all peers. Interest is **node-grained but
session-driven**: a node is "interested in `uri`" iff it currently has **≥1 live local session** for `uri`. The
interested-node set is an authoritative, Redis-backed registry with a short local send-cache (Redis off the message
hot path in steady state), mirroring RC1's `RedisRoomRegistry` + `nodesForRoomCached`.

**Receive vs. send — the load-bearing separation.** A node's **broker subscription** (`broadcastListeners`, populated
at boot for every registered `@MessageMapping` URI and **held for the node lifetime**) is its *receive capability* —
it is unchanged from RC4a and is what processes a delivered broadcast into local fan-out. The **interest registry** is
the *send-side targeting* other nodes consult to decide whom to contact. They are deliberately decoupled: interest can
be briefly wrong without losing correctness, because (a) a node pruned in error still has a held subscription, so a
fallback/all-peers or slightly-stale send that *does* arrive is still delivered locally; and (b) a node over-included
in error (drift) just receives a broadcast it fans out to zero local sessions — a wasted send, never a wrong delivery.
Interest never gates local receive.

**Non-goals (RC4b).**
- No change to `roomMessage` (already mesh-native via unicast), `unicast`, `sendToUser`/offline, or presence's
  *delivery* wiring. (RC4b **does** add presence's *interest registration* — see §4.6 — because without it interest
  routing would drop presence events; that is a fix, not a feature change.)
- No interest *gossip* over the mesh (considered + rejected below — staleness/correctness). Redis stays the interest
  source of truth.
- No hierarchical / relay-tree fan-out for genuinely-global topics (future / sharded-pub-sub; the honest answer for a
  global all-audience topic remains "decentralized N−1 fan-out", delivered by RC4a).
- No change to at-most-once delivery semantics.

---

## 3. Approaches considered

**A. Redis-backed, session-grained interest registry + local send-cache (RECOMMENDED).** A new SPI
`MeshInterestRegistry` with a default `RedisMeshInterestRegistry` mirroring `RedisRoomRegistry`: `SADD nodeId` to
`netty:interest:{b64uri}:nodes` on a node's **first live session** for `uri` (the genuine 0→1 transition), atomic-Lua
`SREM`(+conditional `DEL`) on its **last session leaving** (1→0), `removeAllForNode` on dead-node reap;
`nodesForUri(uri)` read on publish, wrapped in a 5s local send-cache (`nodesForUriCached`, mirroring
`nodesForRoomCached`) with wholesale invalidation on `NODE_LEFT`.
- **Pro:** authoritative + atomic; identical idiom to the proven RC1 path; Redis off the message hot path via the
  cache; correctness equals Redis Pub/Sub's subscription model. Reuses the existing dead-node-callback invalidation.
- **Con:** keeps Redis as the interest control plane (but *not* the message hot path — same as discovery/registry,
  consistent with the mesh thesis "Redis for control, off the message path").
- **Requires** a per-URI live-session refcount in `ClusterMessageSender` (§4.3) so the 0↔1 transitions actually fire —
  net-new machinery the v1 spec wrongly assumed already existed.

**B. Mesh-gossiped interest.** Each node tells peers its live-session-URI set on connect + on change; no Redis.
- **Con:** eventual-consistency **staleness is a correctness regression** — a freshly-subscribed node is invisible to
  publishers until gossip converges; a partition/missed-gossip can drop broadcasts indefinitely with no authoritative
  source to reconcile against. Significant new machinery (anti-entropy, versioning). The `/goal` text says "gossip",
  but the codebase's correctness bar + RC1/RC2/RC3 precedent favor the authoritative registry. **Rejected** for RC4b.

**C. Hybrid (Redis source of truth + mesh interest-change notifications for fast cache invalidation).** Option A plus
a lightweight mesh "interest-changed" notice on first/last-session so publishers refresh immediately instead of ≤TTL.
- **Pro:** shrinks the freshly-subscribed window from ≤TTL to ~RTT.
- **Con:** extra protocol + ordering/race handling; the ≤TTL window is already accepted by RC1. **Deferred** (RC4c).

**Decision: A**, session-grained. Correct, atomic, consistent with the codebase, keeps Redis off the message hot path,
reuses dead-node invalidation. C is the natural follow-on if the staleness window matters.

---

## 4. Components

### 4.1 `MeshInterestRegistry` SPI (new)

```java
public interface MeshInterestRegistry {
    /** This node now hosts its FIRST live local session for {@code uri} (the 0→1 transition). */
    CompletionStage<Void> subscribe(String uri, String nodeId);
    /** This node's LAST live local session for {@code uri} just left (the 1→0 transition). */
    CompletionStage<Void> unsubscribe(String uri, String nodeId);
    /** Remove all of a (dead) node's interest entries — called on the leader-elected dead-node reap. */
    CompletionStage<Void> removeAllForNode(String nodeId);
    /**
     * Node-set currently hosting a live session for {@code uri} (the routing primitive).
     * Completes with the authoritative set (possibly EMPTY = genuinely no interested node); completes
     * EXCEPTIONALLY on read failure/timeout — the router maps that to {@code null} ⇒ all-peers fallback (§4.4).
     */
    CompletionStage<Set<String>> nodesForUri(String uri);
    void shutdown();
}
```

Mirror of `ClusterRoomRegistry` minus the room dimension. Interest is node-grained in Redis (one set per URI), driven
by the per-URI **live-session refcount** in §4.3 (the broker does *not* track session counts today — that refcount is
new). `subscribe`/`unsubscribe` are called only on the true 0↔1 transitions, so they are NOT per-session writes.

### 4.2 `RedisMeshInterestRegistry` (default impl)

**Redis key schema** (mirror `RedisRoomRegistry`'s base64url convention):
```
netty:interest:{b64uri}:nodes   →  Set<nodeId>   (the routing set; b64uri = base64url(uri), delimiter-safe)
```
- `subscribe(uri, nodeId)`   → `SADD netty:interest:{b64uri}:nodes nodeId`
- `unsubscribe(uri, nodeId)` → **single Lua `EVAL`**: `SREM key nodeId; if redis.call('SCARD', key)==0 then redis.call('DEL', key) end`.
  Atomic so the cross-node race (A `SREM`→0, B `SADD`→1, A `DEL` wipes B) cannot happen — a concurrent `SADD` is
  serialized either fully before (SCARD≥1 ⇒ no DEL) or fully after (key re-created) the script. **Do NOT** use a
  non-atomic SREM-then-DEL. Mirrors `RedisRoomRegistry.LEAVE_LUA`'s reason-for-being.
- `nodesForUri(uri)`         → `SMEMBERS netty:interest:{b64uri}:nodes`
- `removeAllForNode(nodeId)` → `SCAN netty:interest:*:nodes` + `SREM nodeId` from each. **Cost (stated honestly):**
  this is **node-agnostic** — the nodeId is a set *member*, not in the key — so it scans **all distinct-URI interest
  sets cluster-wide**, an **O(distinct-URIs)** scan, NOT the node-scoped `O(rooms-on-this-node)` cost of
  `RedisRoomRegistry.removeAllForNode` (whose pattern embeds the nodeId in the key suffix). It is correct (`SREM` of a
  non-member is a no-op) and runs only on the **leader-elected dead-node reap**, off the message hot path. Acceptable
  at the documented scale (≤~10 nodes; distinct partitioned-topic URIs in the tens–low-hundreds per the §1 "use rooms
  for high cardinality" guidance). A per-node reverse index (`netty:interest:node:{b64nodeId} → Set<b64uri>`) to make
  cleanup bounded is **deferred** (§10) — only worth it if URI cardinality grows.

Single key per URI ⇒ no cross-slot concern (one key is one slot; no hash-tag needed). Dedicated executor like the
other Redis SPIs.

### 4.3 Per-URI live-session refcount (`ClusterMessageSender`) — the new mechanism

The hooks already fire per session: `ClusterSessionHookImpl.onSessionAdded` → `clusterSender.onLocalUriActive(uri)`
(line 189) and `onSessionRemoved` → `clusterSender.onLocalUriInactive(uri)` (line 262). Today
`onLocalUriActive` calls the idempotent `subscribeBroadcast` (holds the broker subscription) and `onLocalUriInactive`
is a **no-op**. RC4b adds a per-URI live-session count and fires interest on the genuine 0↔1 transitions:

```java
// new: live local session count per app URI (NOT the held broker subscription, which stays for the node lifetime)
private final ConcurrentHashMap<String, Integer> localUriSessions = new ConcurrentHashMap<>();

public void onLocalUriActive(String uri) {
    subscribeBroadcast(uri);                       // unchanged: hold the broker RECEIVE subscription (idempotent)
    if (reliableBroker != null) subscribeReliable(uri);
    if (interestRegistry == null) return;
    boolean first = localUriSessions.merge(uri, 1, Integer::sum) == 1;   // atomic 0→1 detect
    if (first) {
        interestRegistry.subscribe(uri, nodeId)
            .exceptionally(ex -> { log.warn("interest subscribe({}) failed — publishers may miss this node ≤TTL until retry", uri, ex); return null; });
    }
}

public void onLocalUriInactive(String uri) {
    if (interestRegistry == null) return;          // broker subscription stays HELD regardless (receive capability)
    boolean last = localUriSessions.compute(uri, (u, c) -> (c == null || c <= 1) ? null : c - 1) == null;  // atomic 1→0 (removes key)
    if (last) {
        interestRegistry.unsubscribe(uri, nodeId)
            .exceptionally(ex -> { log.warn("interest unsubscribe({}) failed — stale interest until dead-node reap / retry", uri, ex); return null; });
    }
}
```

- **Fail-safe drift.** If a count drifts high (an `onSessionRemoved` somehow missed for a live node), the node stays
  over-interested → extra sends it drops locally — never a missed delivery. A failed `subscribe` write is the only
  under-interest risk and is bounded by the ≤TTL window + the periodic nature of reconnects (a new session re-fires).
- **Crash cleanup.** On crash the in-memory count dies with the node; the node's interest is cleared by
  `removeAllForNode` on the leader-elected reap (§4.2) — identical to how session-registry/userRegistry/presence are
  reaped. Graceful shutdown clears via the broker/registry shutdown.
- The broker subscription is **never** dropped on last-session (receive capability is held), exactly as today — only
  the interest *registration* is session-grained.

### 4.4 `MeshBroker.publish` — interest routing (with the null-vs-empty sentinel)

```java
public void publish(String uri, ClusterEnvelope envelope) {
    checkActive();
    String wrapped = authenticator.wrap(codec.encode(envelope));
    Map<String,String> peers = directory.peers(nodeId).toCompletableFuture().join();  // live ∩ has-address (RC4a)
    Set<String> interested = interestRouter.nodesForUriCached(uri);   // null => UNCERTAIN => fall back to all-peers
    for (Map.Entry<String,String> e : peers.entrySet()) {
        if (interested != null && !interested.contains(e.getKey())) {
            continue;   // RC4b: skip peers with no live session for this uri
        }
        sendTo(e.getKey(), e.getValue(), wrapped);
    }
}
```

**The failure sentinel — a DELIBERATE divergence from RC1's `nodesForRoomCached`.** RC1's `nodesForRoomCached`
returns `Collections.emptySet()` on a lookup timeout/error (a tolerated missed room-round). RC4b must **not** copy
that: `nodesForUriCached` returns:
- **`null`** when the registry is **absent**, or a read **failed / timed out** ⇒ publish falls back to **all-peers**
  (logged once, metered `fanout_fallback`). *Never a missed delivery from a Redis blip.*
- a **non-null set (possibly empty)** only on a **successful authoritative read**. An empty set means "no peer
  currently has a live audience" ⇒ pruning all peers is **correct** (the publisher already did local fan-out before
  `publish`; a genuinely-empty remote audience should receive nothing).

This null⇒fallback / empty⇒authoritative split is the load-bearing safety contract and MUST be implemented as such
(the v1 "verbatim RC1 pattern" wording is removed; copying RC1's emptySet-on-failure would silently drop a whole
broadcast on a transient Redis error). Two tests pin it (§9): read-timeout ⇒ all-peers; authoritative-empty ⇒ no peers.

The cache + registry live in a small `MeshInterestRouter` collaborator injected into `MeshBroker` (keeps the broker
focused; mirrors how `ClusterMessageSender` owns `nodesForRoomCached`). `nodesForUriCached` reuses RC1's
`CachedNodeSet` 5s-TTL + lookup-timeout-bounded + `NODE_LEFT`-clear shape, but with the null-on-failure sentinel above.
When the registry bean is absent (custom mesh without interest), the router returns `null` ⇒ all-peers.

### 4.5 App-URI interest wiring summary (`ClusterMessageSender`)

- **subscribe (0→1 live session):** in `onLocalUriActive` via the §4.3 refcount ⇒ `interestRegistry.subscribe(uri, nodeId)`.
- **unsubscribe (1→0 live session):** in `onLocalUriInactive` via the §4.3 refcount ⇒ `interestRegistry.unsubscribe(uri, nodeId)`.
- **dead node:** `invalidateCacheForNode(deadNodeId)` already clears the room cache + calls `roomRegistry.removeAllForNode`
  on the leader-elected reaped path; add the symmetric `interestRegistry.removeAllForNode(deadNodeId)` +
  `interestNodeSetCache.clear()` on that **same** path (which is leader-only — verified: the sole production caller of
  the dead-node callback is inside `ClusterNodeManager`'s `reaper.tryClaim` guard).
- **startup:** no boot-time blanket announce. A node announces interest in `uri` only when its first live session for
  `uri` connects — so a freshly-booted node with no sessions is correctly absent from every interest set.

### 4.6 Reserved channels — presence interest is registered (the fix)

Presence is **not** session-driven: a presence-enabled node always wants every cross-node `PRESENCE_CHANGE` event for
its whole lifetime. So presence registers **permanent** interest, separate from the app-URI refcount, at the point it
subscribes the reserved channel:

- **Presence** (`PRESENCE_CHANNEL`): where `start()` does `broker.subscribe(PRESENCE_CHANNEL, this::onPresenceMessage)`
  (the dedicated, non-`subscribeBroadcast` path), **also** call `interestRegistry.subscribe(PRESENCE_CHANNEL, nodeId)`
  once, and **never** session-retract it (cleared only on shutdown / dead-node reap). Then
  `nodesForUri(PRESENCE_CHANNEL)` = all presence-enabled nodes = the correct audience, and `firePresenceTransition`'s
  `broker.publish(PRESENCE_CHANNEL, env)` still reaches every watcher. **Without this, interest routing would read an
  empty set for `PRESENCE_CHANNEL` (a successful, non-null empty read ⇒ NOT the fallback) and prune every peer —
  silently killing all cross-node presence (a shipped RC3 GA feature).** A test asserts presence E2E still reaches a
  remote watcher with interest routing on.
- **Room** (`ROOM_BROADCAST`): goes through `broker.unicast(targetNode, ...)`, **not** `publish` — untouched. ✔
- **Offline / `sendToUser`:** unicast/user-targeted, not topic `publish` — untouched. ✔
- **Generalization:** any channel a node listens on via a non-session path (reserved/control channels) registers
  permanent interest at its subscribe site. For RC4b that is exactly `PRESENCE_CHANNEL`.

---

## 5. Config & auto-config

| Key | Default | Meaning |
|---|---|---|
| `server.netty.websocket.cluster.mesh.interest-routing.enable` | `true` | When `true` (and `mesh.enable=true`), `publish` routes to interested peers; `false` forces RC4a all-peers (debug / escape hatch). A sub-knob of the already-opt-in mesh (`mesh.enable=false` default), so the opt-in convention holds; default-on because routing is the feature's whole purpose. |
| `server.netty.websocket.cluster.mesh.interest-routing.node-set-cache-ttl-ms` | `5000` | Local send-cache TTL for `nodesForUri` (mirrors `room.node-set-cache-ttl-ms`). Lower = fresher interest, more Redis reads. |

Auto-config (`NettyWebSocketClusterConfigure`): a `RedisMeshInterestRegistry` bean gated on
`STANDALONE_MESH_BROKER and interest-routing.enable` + `@ConditionalOnMissingBean(MeshInterestRegistry.class)`, wired
into the `MeshBroker`/router, into `ClusterMessageSender`'s per-URI refcount hooks, and into the presence subscribe
site (§4.6). When `mesh.enable=false` the bean never exists — byte-identical to RC1/RC2/RC3/RC4a.

**`OnAnyRedisSpiRequired`: no new clause needed (resolved).** Mesh already forces the standalone-Redis path and the
existing `meshEnabled && !hasBean(MeshNodeDirectory.class)` clause **already** retains `nettyClusterRedisConnection`
for any mesh deployment using the default directory; the default `RedisMeshInterestRegistry` autowires that same
connection. Adding a "same shape" interest clause would either be redundant or (if it omitted the
`interest-routing.enable` predicate) hold Redis up when the registry bean isn't even created. So **no clause is added**;
a context test covers the only edge it would protect: `mesh.enable=true` + a fully custom mesh SPI set (custom
`MeshNodeDirectory` AND custom `MeshInterestRegistry`) correctly gates Redis off, while `mesh.enable=true` + default
directory keeps it on.

---

## 6. Consistency & failure semantics

- **At-most-once preserved.** Interest routing only changes *which* peers a `publish` contacts; delivery to a
  contacted peer is unchanged.
- **Freshly-subscribed ≤TTL window (RC1 parity).** A node whose first session for `uri` just connected may be missed
  by remote publishers for up to `node-set-cache-ttl-ms` (their cache hasn't refreshed). Identical to RC1's
  just-joined-room-member window and to Redis Pub/Sub's own subscribe-propagation latency. Local delivery on the
  subscribing node is immediate. Mitigation is the deferred approach C.
- **Cold-start / first-publish race.** A `publish` to `uri` before a remote audience node's `SADD` has landed reads an
  authoritative (possibly empty) set and may miss that node — this is the *same* ≤TTL freshly-subscribed window from
  the publisher's side (the `subscribe` write is async, like RC1's `joinRoom`). At-most-once; documented, not a new
  loss class. Authoritative-empty ⇒ prune-all is correct (genuinely no live remote audience yet).
- **Registry read failure ⇒ RC4a all-peers fallback** (the §4.4 `null` sentinel). Never a missed delivery from a
  Redis blip; logged + metered (`fanout_fallback`).
- **Over/under-interest are both fail-safe** (see §2 receive-vs-send): over-interest = a wasted send dropped locally;
  under-interest (a held-subscription node briefly absent from the set) = bounded by the ≤TTL window, and the held
  broker subscription means any send that *does* arrive is still delivered.
- **Dead peer.** Excluded by live-membership (RC4a MF1) immediately for reachability, and pruned from the interest
  registry on the leader-elected reap (`removeAllForNode`) + wholesale cache clear. A stale interest entry for a dead
  node at worst causes one `sendTo` that the membership filter (RC4a MF1) already drops.
- **Backpressure (RC4a M1).** Unchanged; a slow interested peer still drops+counts past the watermark.

---

## 7. Metrics (RC4b)

Internal counters now (surfaced to `netty.cluster.mesh.*` in RC4d, consistent with RC4a's deferral, written to the
**sender's shared** `ClusterRuntimeStats` so RC4d can read them — also fixing RC4a's BL6):
- `mesh.broadcast.target_nodes` — interested peers targeted per `publish` (the reduction observable; mirrors
  `netty.cluster.room.fanout.target_nodes`).
- `mesh.broadcast.fanout_fallback` — publishes that fell back to all-peers (registry unavailable/timeout) — watch this;
  a high value means interest routing is not actually engaging (Redis trouble) and the reduction isn't being realized.

---

## 8. Backward compatibility

- `mesh.enable=false` ⇒ no mesh, no interest registry — byte-identical to RC1/RC2/RC3.
- `mesh.enable=true, interest-routing.enable=false` ⇒ RC4a naive all-peers (the prior RC4a behavior exactly).
- New SPI `MeshInterestRegistry` is additive + `@ConditionalOnMissingBean`. Same envelope wire (no version bump). A
  mesh node with interest routing and one without inter-operate: the without-routing node receives + drops uninterested
  URIs as in RC4a; the with-routing node prunes — both safe (a mixed RC4a/RC4b cluster never loses delivery, because
  pruning is only ever applied by a node that successfully read interest; an RC4a node sends to all).

---

## 9. Testing

- `MeshInterestRegistry` SPI + an `InMemoryMeshInterestRegistry` test stub (mirror `InMemoryMeshNodeDirectory`).
- `RedisMeshInterestRegistry`: `SADD` / atomic-Lua `SREM`+conditional-`DEL` / `nodesForUri` / `removeAllForNode` unit
  (mock Lettuce, assert the single `EVAL` for unsubscribe) + a real-Redis IT (assumeTrue-gated, like
  `RedisMeshNodeDirectoryTest`), incl. the unsubscribe-race (`SREM`→0 concurrent with `SADD` keeps the member).
- **Session-grained refcount** (`ClusterMessageSender`): two sessions on `/ws/a` ⇒ exactly **one** `subscribe`; first
  leave ⇒ no `unsubscribe`; second (last) leave ⇒ exactly one `unsubscribe`; the held broker subscription is NOT
  dropped on last-leave.
- `MeshBroker.publish` interest routing: a peer **with** a live session receives, a peer **without** is skipped;
  registry read **failure/timeout ⇒ `null` ⇒ all-peers**; authoritative **empty ⇒ no peers**; live-membership ∩
  interest (a dead-but-interested peer is not contacted).
- **Presence composition** (the §4.6 fix): with interest routing **on**, a presence change on node A still reaches a
  remote watcher node B (B registered permanent `PRESENCE_CHANNEL` interest); a presence-only deployment SADDs
  `PRESENCE_CHANNEL`.
- Two-node real-TCP-via-real-Redis E2E (extend `MeshTwoNodeE2ETest`): B has a live session on `/ws/a` only; A's
  publish to `/ws/a` reaches B, A's publish to `/ws/b` does **not**; close B's `/ws/a` session ⇒ A's next `/ws/a`
  publish (after ≤TTL) no longer targets B.
- Context test (`NettyWebSocketClusterConfigureTest`): `mesh.enable=true` ⇒ interest registry present + wired;
  `interest-routing.enable=false` ⇒ absent (all-peers); `mesh.enable=false` ⇒ absent; `mesh.enable=true` + fully
  custom mesh SPI (custom directory + custom interest registry) ⇒ Redis connection gated off.
- **Honesty regression:** a topic with live sessions on every node targets all peers (no false reduction); a
  partitioned topic (live sessions on a subset) targets only that subset — on a **homogeneous** registration set
  (every node has the mapping; only some have live sessions), proving the reduction is session-grained, not
  build-heterogeneity-dependent.

---

## 10. Deferred

- **Approach C** (mesh interest-change notifications to shrink the ≤TTL freshly-subscribed window) → RC4c.
- **Per-node reverse index** `netty:interest:node:{b64nodeId} → Set<b64uri>` to make `removeAllForNode` bounded
  (`SMEMBERS` + pipelined `SREM`) instead of an `O(distinct-URIs)` SCAN → only if URI cardinality grows beyond the
  documented scale (otherwise YAGNI). Stated honestly in §4.2.
- **Full `netty.cluster.mesh.*` meters** (incl. `broadcast.target_nodes`, `fanout_fallback`, + the RC4a counters
  fixed to the shared `ClusterRuntimeStats` — RC4a BL6) → RC4d.
- Hierarchical/relay-tree fan-out for genuinely-global topics (out of mesh scope; sharded pub/sub → 2.0.0).
- Per-tenant / wildcard interest aggregation (YAGNI now).

---

## 11. Risks / open questions for design-review (v2 — post-fold)

1. **Refcount correctness under concurrent connect/disconnect.** `merge`/`compute` give atomic 0↔1 detection; confirm
   no interleaving fires a spurious double-`subscribe` or misses a `1→0`. (Fail-safe direction is over-interest.)
2. **`subscribe` write failure under-interest.** A failed first-session `subscribe` leaves the node absent from the set
   until a retry/new session; bounded by ≤TTL + reconnect cadence + the all-peers fallback when many nodes fail.
   Confirm this is within at-most-once + RC1 parity.
3. **Presence permanent-interest lifecycle.** Confirm `PRESENCE_CHANNEL` interest is registered exactly once at the
   presence subscribe site and cleared only on shutdown/dead-node reap (never by the app-URI refcount path).
4. **Honesty framing.** Release notes + cluster-design must state plainly: global-topic reduction is ~0; the win is
   *partitioned live audiences* (now genuine on homogeneous fleets) + already-mesh-native rooms; high-cardinality
   topics must use rooms. So RC4b is not over-claimed (RC1-style scope honesty).

---

## 12. Design-review resolutions (folded from the 31-agent review)

| # | Blocking finding | Resolution in this spec |
|---|---|---|
| A | Interest was registered-mapping-grained; "zero-session" reduction fictional; `unsubscribe` dead code; sparse win needed a heterogeneous build | **Pivot to session-grained interest** via the per-URI live-session refcount (§4.3); reduction now genuine on homogeneous fleets; `subscribe`/`unsubscribe` fire on real 0↔1 transitions (§4.1, §4.5); §1/§2 reframed |
| B | `PRESENCE_CHANNEL` never registered interest ⇒ interest routing would prune all peers ⇒ silent cross-node presence regression | **Permanent presence interest** registered at the presence subscribe site (§4.6) + composition test (§9) |
| C | "Verbatim RC1" `nodesForRoomCached` returns emptySet-on-failure ⇒ a Redis blip silently drops the whole broadcast | **null-vs-empty sentinel** made explicit + DELIBERATE divergence from RC1 (§4.1 `nodesForUri` contract, §4.4) + two tests |
| D | Non-atomic `SREM`+`DEL` unsubscribe races (A SREM→0, B SADD→1, A DEL wipes B) | **Single atomic Lua `EVAL`** mandated; non-atomic option removed (§4.2) |
| E | `removeAllForNode` SCAN mischaracterized as "same shape as room" (it is O(distinct-URIs), not node-scoped) | **Honest cost statement** (§4.2); reverse-index optimization deferred (§10) |
| F | `OnAnyRedisSpiRequired` "same shape" clause redundant / wrong predicate | **No new clause** — the directory clause already retains Redis; documented + context test (§5) |

Non-blocking refinements folded: cold-start window documented as the ≤TTL window (§6); over/under-interest fail-safe
framing (§2, §6); metrics written to the shared `ClusterRuntimeStats` (§7); §1 examples corrected to
live-audience-partitioned (not heterogeneous-build).
