# RC4b — Interest-routed mesh broadcast (design)

**Status:** design — **design-review + re-review folded** (v3). **Branch:** `feature/1.10.0-mesh-rc4b` (off `v1.10.0-RC4a`).
**Module:** `netty-spring-websocket-cluster`. **Stack:** Boot 2.7 + Netty 4.1 + Lettuce 6.1, JDK 17.

> **Design-review corrections (recorded — this project's honest-engineering bar, like RC1's shard-ring→node-set).**
> Two adversarial review rounds shaped this spec (archived under `docs/superpowers/notes/`):
> **Round 1** (`2026-06-18-rc4b-mesh-design-review.json`, 31 agents, `fixDesignFirst`) killed v1's
> registered-`@MessageMapping`-grained interest (zero reduction on a homogeneous fleet). **Round 2**
> (`2026-06-18-rc4b-mesh-rereview.json`, 20 agents) confirmed v2's session-grained pivot resolved 5/6 root flaws but
> caught **3 new flaws v2 introduced** plus an unsound flaw-F resolution. v3 folds all of them:
> - **Interest is session-grained AND the node-set flip is decided atomically in Redis (RC1-exact).** A node is
>   interested in `uri` iff it currently has ≥1 live local session. v2 put the per-URI session count in a JVM map and
>   fired two *independent, unordered* Redis writes (`SADD` on first / `SREM`-Lua on last) — a same-node
>   connect/disconnect race could land the `SADD` before the `SREM`-Lua and **permanently wipe a live node's
>   membership** (silent under-interest, unbounded). v3 **mirrors `RedisRoomRegistry` exactly**: a Redis-side per-node
>   session set, with the node-set add-on-first / remove-on-last decided *inside* a JOIN/LEAVE Lua (per-session
>   writes, like rooms). This also makes `removeAllForNode` node-scoped (fixes the v2 cost flaw) and makes the
>   "mirrors RC1" claim genuinely true.
> - **Reserved/control channels bypass interest pruning entirely** (always all-peers). v2 registered *permanent*
>   `PRESENCE_CHANNEL` interest via a one-shot boot `SADD` — a transient Redis blip on that single write would
>   permanently, silently prune the node from presence for its whole lifetime (no session re-fire). v3 removes the
>   presence SADD altogether: `publish` to a reserved channel never consults interest, so presence is never pruned,
>   has no new failure mode, and loses nothing (its audience is all presence-enabled nodes anyway).
> - **`OnAnyRedisSpiRequired` gains a real interest clause** (v2's "no clause needed" was unsound — the default
>   interest registry is gated independently of the directory, so a custom-directory + default-interest deployment
>   would orphan the Redis connection).
> - **§1 discloses the random-LB / population-saturation precondition** — the exact lesson that retired RC1's shard
>   ring: a *logically* partitioned topic whose live population is large relative to node count saturates (nearly) all
>   nodes under random load-balancing, so reduction is real only for small populations or session-sticky/tenant-affine
>   routing. Plus minor: the router must NOT cache a null/failure read; the connect hook is `onSessionRegistered`.
>
> See §12 for the finding-by-finding resolution across both rounds.

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

- **Where RC4b's reduction is real: _physically-partitioned live audiences_.** Because interest is session-grained, a
  node that has the `@MessageMapping` for `/ws/support` but **no live support session right now** is **not** in the
  interest set and is **not** contacted. So a support topic whose agents are connected to 4 of 100 nodes routes
  `99 → 3` peer sends (~25×) **even though all 100 nodes run the same jar**. Same applies to admin/ops channels (few
  operators) and the transient case of a node whose last session for a URI just left. Reduction is proportional to how
  *physically partitioned the live audience* is across nodes — the same locality honesty as RC1's per-room node-set.

- **⚠️ Precondition (the RC1 shard-ring lesson — do not skip).** Reduction requires the live audience to land on a
  **subset** of nodes. Under random / round-robin load-balancing (the WebSocket-fleet default), a topic whose
  *concurrent live population* is comparable to or larger than the node count **saturates (nearly) all nodes** by
  coupon-collector — so a *logically* partitioned topic (e.g. `/ws/region-us` with 50k users across 100 nodes, or a
  large tenant) has interest = all nodes → **~0 reduction despite being "partitioned."** RC4b helps when **either**
  the topic's live population is small relative to node count (support/admin/ops, small tenants) **or** the LB is
  **session-sticky / tenant-affine** so a topic's sessions cluster on few nodes. This is exactly why RC1's
  consistent-hashing shard ring was retired (shards collapsed to global broadcast under random LB) and why RC1's docs
  ship the "hot room on every node → 1.0× (no reduction)" benchmark row. RC4b inherits that honesty, not an
  unconditional claim.

- **The IM ceiling-break is composed across RCs, not delivered solely by RC4b.** RC1 `roomMessage(uri, room, ...)`
  already targets only member-hosting nodes via `broker.unicast(...)`, which over the RC4a mesh is **already a direct
  TCP send** — so **room broadcast is already mesh-native and optimal** once `mesh.enable=true`; RC4b does **not**
  touch the room path. RC4b's job is the remaining plain `topicMessage` / `publish` path, so *non-room* partitioned
  topics also stop paying all-peers fan-out.

- **Use rooms, not per-entity URIs, for high-cardinality topics.** Session-grained interest creates Redis keys per
  *distinct URI*. That is the right tool for a **moderate** number of partitioned topics (per-feature, support/admin,
  a handful of large tenants) — tens to low-hundreds. A per-conversation `/ws/conv-{id}` design (millions of URIs)
  must use **RC1 rooms** (one `/ws/chat` URI + many rooms), which are built for exactly that cardinality.

**One-line scope:** RC4b makes `MeshBroker.publish(uri)` route to `interestedNodes(uri) ∩ live-mesh-membership` (via a
new `MeshInterestRegistry` that **mirrors `ClusterRoomRegistry` minus the room dimension**), where interest tracks
**live local sessions**. It is a strict optimization that never loses delivery relative to RC4a except the inherent
≤cache-TTL freshly-subscribed window (RC1 parity), degrades to RC4a all-peers on any registry uncertainty, and never
prunes reserved/control channels.

---

## 2. Goal & non-goals

**Goal.** When `mesh.enable=true` and `interest-routing.enable=true`, `publish(uri, env)` sends the framed envelope
only to peers in `interestedNodes(uri) ∩ live-mesh-membership`, instead of all peers. Interest is **node-grained,
session-driven, and Redis-authoritative**: a node is "interested in `uri`" iff it currently has **≥1 live local
session** for `uri`, and the node-set add-on-first / remove-on-last transition is decided **atomically inside a Redis
Lua** over a per-node session set — exactly like `RedisRoomRegistry`'s JOIN/LEAVE. A short local send-cache keeps
Redis off the message hot path in steady state.

**Receive vs. send — the load-bearing separation.** A node's **broker subscription** (`broadcastListeners`, populated
at boot for every registered `@MessageMapping` URI and **held for the node lifetime**) is its *receive capability* —
unchanged from RC4a; it is what processes a delivered broadcast into local fan-out. The **interest registry** is the
*send-side targeting* other nodes consult. They are decoupled: interest can be briefly wrong without losing
correctness — (a) a node pruned in error still has a held subscription, so a fallback/all-peers or slightly-stale send
that *does* arrive is still delivered locally; (b) a node over-included in error just receives a broadcast it fans out
to zero local sessions — a wasted send, never a wrong delivery. Interest never gates local receive.

**Non-goals (RC4b).**
- No change to `roomMessage` (already mesh-native via unicast), `unicast`, `sendToUser`/offline, or presence wiring
  (presence is handled by the reserved-channel pruning *bypass*, §4.6 — a routing rule, not a presence-code change).
- No interest *gossip* over the mesh (considered + rejected below — staleness/correctness). Redis stays the source of
  truth.
- No hierarchical / relay-tree fan-out for genuinely-global topics (future / sharded-pub-sub; the honest answer for a
  global all-audience topic remains "decentralized N−1 fan-out", delivered by RC4a).
- No change to at-most-once delivery semantics.

---

## 3. Approaches considered

**A. Redis-backed, session-grained interest registry that mirrors `RedisRoomRegistry` exactly (RECOMMENDED).** A new
SPI `MeshInterestRegistry` with a default `RedisMeshInterestRegistry` that copies `RedisRoomRegistry`'s structure
**minus the room dimension**: a per-(uri,node) **session set** plus a per-uri **node-set**, with a `JOIN_LUA`
(`SADD sessionId` to the node's session set; if it became the first, `SADD nodeId` to the node-set) and a `LEAVE_LUA`
(`SREM sessionId`; if the session set is now empty, `SREM nodeId` from the node-set + `DEL` the session set), each a
single atomic `EVAL`. `nodesForUri(uri)` reads the node-set; `removeAllForNode` SCANs this node's session-set keys
(node-scoped). Wrapped in a 5s local send-cache (`nodesForUriCached`) with wholesale invalidation on `NODE_LEFT`.
- **Pro:** authoritative + **atomic** (the 0↔1 node-set flip is decided inside the EVAL over the session set, so
  concurrent same-node connect/disconnect and cross-node ops all serialize at Redis — no reorder can wipe a live
  node); **identical idiom** to the proven RC1 path (same two-key + hash-tag + Lua + node-scoped `removeAllForNode`);
  Redis off the message hot path via the cache; correctness equals Redis Pub/Sub's subscription model.
- **Cost:** one Redis write per session connect/disconnect — exactly what RC1 rooms (`joinRoom`/`leaveRoom`) and the
  session registry (`register`/`deregister`) already pay on the session-lifecycle path, **off the message hot path**.
- **Why not a JVM session count + per-transition writes (v2's rejected approach):** splitting the count into the JVM
  and firing two independent unordered Redis writes loses RC1's in-EVAL atomicity — a same-node `SADD`(connect) can
  land before a `SREM`-Lua(disconnect) and the Lua's empty-check then `DEL`s a live node's membership permanently.
  Round-2 review confirmed this as a BLOCKER. The fix is to put the count and the flip back in Redis, in the Lua.

**B. Mesh-gossiped interest.** Each node tells peers its live-session-URI set; no Redis.
- **Con:** eventual-consistency **staleness is a correctness regression** — a freshly-subscribed node is invisible to
  publishers until gossip converges; a partition/missed-gossip can drop broadcasts indefinitely with no authoritative
  source to reconcile against. The codebase's correctness bar + RC1/RC2/RC3 precedent favor the authoritative
  registry. **Rejected** for RC4b.

**C. Hybrid (Redis source of truth + mesh interest-change notifications for fast cache invalidation).** A plus a
lightweight mesh "interest-changed" notice on first/last-session so publishers refresh immediately instead of ≤TTL.
- **Pro:** shrinks the freshly-subscribed window from ≤TTL to ~RTT. **Con:** extra protocol + ordering; the ≤TTL
  window is already accepted by RC1. **Deferred** (RC4c).

**Decision: A**, RC1-faithful. Correct, atomic, consistent with the codebase, keeps Redis off the message hot path,
reuses dead-node invalidation. C is the natural follow-on if the staleness window matters.

---

## 4. Components

### 4.1 `MeshInterestRegistry` SPI (new)

```java
public interface MeshInterestRegistry {
    /** A local session for {@code uri} registered on this node (call per session-register; the impl decides the
     *  0→1 node-set add atomically). */
    CompletionStage<Void> subscribe(String uri, String sessionId, String nodeId);
    /** A local session for {@code uri} was removed from this node (call per session-remove; the impl decides the
     *  1→0 node-set remove atomically). */
    CompletionStage<Void> unsubscribe(String uri, String sessionId, String nodeId);
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

Mirror of `ClusterRoomRegistry` minus the room dimension. `subscribe`/`unsubscribe` carry the `sessionId` because the
registry — not a JVM counter — owns the per-node session set and decides the 0↔1 node-set transition in its Lua.

### 4.2 `RedisMeshInterestRegistry` (default impl) — RC1-exact two-key + Lua

**Redis key schema** (mirror `RedisRoomRegistry`'s base64url + hash-tag convention so the two keys co-locate in one
slot for the `EVAL`, Redis-Cluster-safe):
```
netty:interest:{b64uri}:nodes        →  Set<nodeId>      (the routing set; read by nodesForUri)
netty:interest:{b64uri}:n:{b64node}  →  Set<sessionId>   (per-node live-session set; drives the 0↔1 flip)
```
(`{b64uri}` is both the base64url-encoded URI and the Redis hash-tag, exactly as `RedisRoomRegistry` tags on the room.)

- `subscribe(uri, sessionId, nodeId)` → **`JOIN_LUA` (single `EVAL`)**:
  `SADD sessionSet sessionId; if redis.call('SCARD', sessionSet)==1 then redis.call('SADD', nodeSet, nodeId) end`
- `unsubscribe(uri, sessionId, nodeId)` → **`LEAVE_LUA` (single `EVAL`)**:
  `SREM sessionSet sessionId; if redis.call('SCARD', sessionSet)==0 then redis.call('SREM', nodeSet, nodeId); redis.call('DEL', sessionSet) end`
- `nodesForUri(uri)` → `SMEMBERS netty:interest:{b64uri}:nodes`
- `removeAllForNode(nodeId)` → `SCAN MATCH netty:interest:*:n:{b64node}` (the nodeId is in the **key suffix**, so this
  is **node-scoped** — only this node's session-set keys, `O(URIs-on-this-node)`, exactly like
  `RedisRoomRegistry.removeAllForNode`); for each, parse the `{b64uri}`, then `SREM nodeId` from the node-set + `DEL`
  the session set. Runs only on the **leader-elected dead-node reap**, off the message hot path. (A `SCAN MATCH` still
  walks the whole keyspace cursor-side and filters — `O(total-keys)` cursor cost — same as the shipped
  `RedisRoomRegistry`; acceptable at the documented scale.)

This is `RedisRoomRegistry` with the room dimension collapsed away: the per-node session set is keyed by `(uri,node)`
instead of `(uri,room,node)`, and `nodesForUri` is `nodesForRoom` without the room. The atomic in-EVAL 0↔1 decision —
the property the v2 JVM-count approach lost — is restored verbatim. Dedicated executor like the other Redis SPIs.

### 4.3 Interest wiring (`ClusterMessageSender` / `ClusterSessionHookImpl`) — per-session, no JVM counter

The hooks already fire per session: `ClusterSessionHookImpl.onSessionRegistered` → `clusterSender.onLocalUriActive(uri, sessionId)`
(today the call is `onLocalUriActive(uri)` at line 189) and `onSessionRemoved` → `clusterSender.onLocalUriInactive(uri, sessionId)`
(line 262). RC4b threads the `sessionId` through and calls the registry per session — the registry's Lua owns the
0↔1 decision, so **no JVM counter exists** (v2's race source is gone):

```java
public void onLocalUriActive(String uri, String sessionId) {
    subscribeBroadcast(uri);                       // unchanged: hold the broker RECEIVE subscription (idempotent)
    if (reliableBroker != null) subscribeReliable(uri);
    if (interestRegistry != null) {
        interestRegistry.subscribe(uri, sessionId, nodeId)
            .exceptionally(ex -> { log.warn("interest subscribe({}) failed — publishers may miss this node ≤TTL until a later session re-asserts", uri, ex); return null; });
    }
}

public void onLocalUriInactive(String uri, String sessionId) {
    if (interestRegistry != null) {                // broker subscription stays HELD (receive capability)
        interestRegistry.unsubscribe(uri, sessionId, nodeId)
            .exceptionally(ex -> { log.warn("interest unsubscribe({}) failed — stale interest until dead-node reap / a later op", uri, ex); return null; });
    }
}
```

- **Atomic & race-free:** because the node-set flip is decided inside the registry's Lua over the per-node session
  set, a concurrent same-node connect (`SADD`+maybe-add-node) and disconnect (`SREM`+maybe-remove-node) serialize at
  Redis — the v2 reorder-wipe is impossible. The session set is the source of truth for "does this node still have a
  live session for `uri`."
- **Drift / write-failure is fail-safe.** A failed `subscribe` write under-includes the node, bounded by the ≤TTL
  window + the next session's `subscribe` re-asserting + the all-peers fallback when reads fail. A failed `unsubscribe`
  over-includes (a wasted send dropped locally) until the dead-node reap or a later op. Never a wrong delivery.
- **Crash cleanup.** The session sets die with no graceful `unsubscribe`; the node's interest is cleared by
  `removeAllForNode` on the leader-elected reap (§4.2) — identical to session-registry/userRegistry/presence reaps.
- The broker subscription is **never** dropped on last-session (receive capability is held), exactly as today.

`onLocalUriActive`/`onLocalUriInactive` gain the `sessionId` parameter; `ClusterSessionHookImpl` passes it (the
session is in hand at both call sites). The hook method is named `onSessionRegistered` (connect) / `onSessionRemoved`
(disconnect).

### 4.4 `MeshBroker.publish` — interest routing (reserved-bypass + null-vs-empty sentinel)

```java
public void publish(String uri, ClusterEnvelope envelope) {
    checkActive();
    String wrapped = authenticator.wrap(codec.encode(envelope));
    Map<String,String> peers = directory.peers(nodeId).toCompletableFuture().join();  // live ∩ has-address (RC4a)
    Set<String> interested = interestRouter.nodesForUriCached(uri);   // null => UNCERTAIN or RESERVED => all-peers
    for (Map.Entry<String,String> e : peers.entrySet()) {
        if (interested != null && !interested.contains(e.getKey())) {
            continue;   // RC4b: skip peers with no live session for this uri
        }
        sendTo(e.getKey(), e.getValue(), wrapped);
    }
}
```

**Reserved/control channels bypass pruning (the presence fix).** `nodesForUriCached(uri)` returns **`null`** (⇒
all-peers, RC4a behavior) for any **reserved channel** — for RC4b that is exactly `PRESENCE_CHANNEL`. No interest is
ever registered for reserved channels, so there is no SADD that could fail and no membership that could be pruned;
cross-node presence keeps fanning out to every presence-enabled node exactly as in RC4a. (Pruning a reserved channel
would buy zero reduction anyway — its audience *is* all presence-enabled nodes.) The router is constructed with the
reserved-channel set (`{ PRESENCE_CHANNEL }`).

**The failure sentinel — a DELIBERATE divergence from RC1's `nodesForRoomCached`.** RC1 returns
`Collections.emptySet()` on a lookup timeout/error. RC4b must **not** copy that. `nodesForUriCached` returns:
- **`null`** when the URI is reserved, the registry is **absent**, or a read **failed / timed out** ⇒ publish falls
  back to **all-peers** (logged once, metered `fanout_fallback`). **The null/failure result is NOT cached** — mirror
  RC1's `nodesForRoomCached` which puts into the cache only on the success branch and returns without caching on the
  catch branch, so a transient blip does not pin all-peers for a full TTL window.
- a **non-null set (possibly empty)** only on a **successful authoritative read** of a non-reserved URI. An empty set
  means "no peer currently has a live audience" ⇒ pruning all peers is **correct** (the publisher already did local
  fan-out before `publish`).

This null⇒fallback / empty⇒authoritative split is the load-bearing safety contract (copying RC1's emptySet-on-failure
would silently drop a whole broadcast on a transient Redis error). Three tests pin it (§9): read-timeout ⇒ all-peers
(not cached); authoritative-empty ⇒ no peers; reserved channel ⇒ all-peers.

The cache + registry live in a small `MeshInterestRouter` collaborator injected into `MeshBroker` (mirrors how
`ClusterMessageSender` owns `nodesForRoomCached`). `nodesForUriCached` reuses RC1's `CachedNodeSet` 5s-TTL +
lookup-timeout-bounded + `NODE_LEFT`-clear shape, with the null-on-failure (uncached) sentinel above. When the
registry bean is absent (custom mesh without interest), the router returns `null` ⇒ all-peers.

### 4.5 Dead-node + cache wiring

`invalidateCacheForNode(deadNodeId)` already clears the room cache + calls `roomRegistry.removeAllForNode` on the
leader-elected reaped path (verified: the sole production caller of the dead-node callback is inside
`ClusterNodeManager`'s `reaper.tryClaim` guard). RC4b adds the symmetric `interestRegistry.removeAllForNode(deadNodeId)`
+ `interestNodeSetCache.clear()` on that **same** path. No boot-time blanket announce: a node announces interest in
`uri` only when its first live session for `uri` connects.

### 4.6 Reserved channels — pruning bypass (not registration)

- **Presence** (`PRESENCE_CHANNEL`): **never registered in the interest registry and never pruned** — `publish`
  treats it as all-peers (§4.4). This is strictly simpler and safer than v2's permanent-SADD: no write to fail, no
  membership to lose, no lifecycle to manage, and cross-node presence (the shipped RC3 GA feature) keeps working
  unchanged. A test asserts presence E2E still reaches a remote watcher with interest routing on.
- **Room** (`ROOM_BROADCAST`): goes through `broker.unicast(targetNode, ...)`, **not** `publish` — untouched. ✔
- **Offline / `sendToUser`:** unicast/user-targeted, not topic `publish` — untouched. ✔
- **Generalization:** the `MeshInterestRouter` holds the reserved-channel set; any channel in it bypasses pruning
  (returns `null` ⇒ all-peers). For RC4b that set is `{ PRESENCE_CHANNEL }`.

---

## 5. Config & auto-config

| Key | Default | Meaning |
|---|---|---|
| `server.netty.websocket.cluster.mesh.interest-routing.enable` | `true` | When `true` (and `mesh.enable=true`), `publish` routes to interested peers; `false` forces RC4a all-peers (debug / escape hatch). A sub-knob of the already-opt-in mesh (`mesh.enable=false` default), so the opt-in convention holds; default-on because routing is the feature's whole purpose. |
| `server.netty.websocket.cluster.mesh.interest-routing.node-set-cache-ttl-ms` | `5000` | Local send-cache TTL for `nodesForUri` (mirrors `room.node-set-cache-ttl-ms`). Lower = fresher interest, more Redis reads. |

Auto-config (`NettyWebSocketClusterConfigure`): a `RedisMeshInterestRegistry` bean gated on
`STANDALONE_MESH_BROKER and interest-routing.enable` + `@ConditionalOnMissingBean(MeshInterestRegistry.class)`, wired
into the `MeshBroker`/router (with the reserved-channel set) and into `ClusterMessageSender`'s per-session interest
hooks. When `mesh.enable=false` the bean never exists — byte-identical to RC1/RC2/RC3/RC4a.

**`OnAnyRedisSpiRequired` gains an interest clause (resolved — v2's "no clause" was unsound).** The default
`RedisMeshInterestRegistry` autowires `nettyClusterRedisConnection`, and its gate (`STANDALONE_MESH_BROKER and
interest-routing.enable`) is **independent of `MeshNodeDirectory`** — so a custom `MeshNodeDirectory` (e.g. K8s-DNS)
plus all-custom core SPIs plus the **default** interest registry would gate the Redis connection off (the directory
clause sees the custom directory ⇒ `false`) while still creating the registry that needs it ⇒ `NoSuchBeanDefinitionException`
at startup. This is the exact structural situation the room/offline/presence per-SPI clauses already guard. Add the
symmetric clause:
```java
boolean interestRoutingEnabled = meshEnabled && Boolean.parseBoolean(env.getProperty(
        "server.netty.websocket.cluster.mesh.interest-routing.enable", "true"));
if (interestRoutingEnabled && !hasBean(bf, MeshInterestRegistry.class)) return true;
```
(OR'd with the existing directory clause.) Context tests cover both the **symmetric** case (custom directory + custom
interest registry ⇒ Redis gated off) **and the asymmetric** case (custom directory + **default** interest registry +
all-custom core SPIs ⇒ Redis stays **on**) — the latter is what would otherwise break and what the v2 test missed.

---

## 6. Consistency & failure semantics

- **At-most-once preserved.** Interest routing only changes *which* peers a `publish` contacts; delivery to a
  contacted peer is unchanged.
- **Freshly-subscribed ≤TTL window (RC1 parity).** A node whose first session for `uri` just connected may be missed
  by remote publishers for up to `node-set-cache-ttl-ms` (their cache hasn't refreshed). Identical to RC1's
  just-joined-room-member window and to Redis Pub/Sub's own subscribe-propagation latency. Local delivery on the
  subscribing node is immediate. Mitigation is the deferred approach C.
- **Cold-start / first-publish race** is the same ≤TTL window from the publisher's side (the `subscribe` write is
  async, like RC1's `joinRoom`). At-most-once; authoritative-empty ⇒ prune-all is correct (no live remote audience yet).
- **Registry read failure ⇒ RC4a all-peers fallback** (the §4.4 `null` sentinel, not cached). Never a missed delivery
  from a Redis blip; logged + metered (`fanout_fallback`).
- **Reserved channels never pruned** (§4.6) — presence/control delivery is exactly RC4a all-peers, with no dependence
  on any interest write succeeding.
- **Over/under-interest are both fail-safe** (see §2 receive-vs-send): over-interest = a wasted send dropped locally;
  under-interest (a node briefly absent from the set) = bounded by the ≤TTL window, and the held broker subscription
  means any send that *does* arrive is still delivered.
- **Dead peer.** Excluded by live-membership (RC4a MF1) immediately for reachability; pruned from the interest
  registry on the leader-elected reap (`removeAllForNode`) + wholesale cache clear. A stale interest entry for a dead
  node at worst causes one `sendTo` the membership filter (RC4a MF1) already drops.
- **Backpressure (RC4a M1).** Unchanged; a slow interested peer still drops+counts past the watermark.

---

## 7. Metrics (RC4b)

Internal counters now (surfaced to `netty.cluster.mesh.*` in RC4d, consistent with RC4a's deferral, written to the
**sender's shared** `ClusterRuntimeStats` so RC4d can read them — also fixing RC4a's BL6):
- `mesh.broadcast.target_nodes` — interested peers targeted per `publish` (the reduction observable; mirrors
  `netty.cluster.room.fanout.target_nodes`).
- `mesh.broadcast.fanout_fallback` — publishes that fell back to all-peers (registry unavailable/timeout/reserved) —
  watch this; a high value on non-reserved URIs means interest routing is not engaging (Redis trouble).

---

## 8. Backward compatibility

- `mesh.enable=false` ⇒ no mesh, no interest registry — byte-identical to RC1/RC2/RC3.
- `mesh.enable=true, interest-routing.enable=false` ⇒ RC4a naive all-peers (the prior RC4a behavior exactly).
- New SPI `MeshInterestRegistry` is additive + `@ConditionalOnMissingBean`. Same envelope wire (no version bump). A
  mesh node with interest routing and one without inter-operate: the without-routing node sends to all; the
  with-routing node prunes only after a successful read — a mixed RC4a/RC4b cluster never loses delivery.

---

## 9. Testing

- `MeshInterestRegistry` SPI + an `InMemoryMeshInterestRegistry` test stub (mirror `InMemoryMeshNodeDirectory`;
  per-node session set + node-set in memory, 0↔1 decided under a lock to model the Lua).
- `RedisMeshInterestRegistry`: `JOIN_LUA` / `LEAVE_LUA` / `nodesForUri` / node-scoped `removeAllForNode` unit (mock
  Lettuce, assert one `EVAL` per subscribe/unsubscribe) + a real-Redis IT (assumeTrue-gated, like
  `RedisMeshNodeDirectoryTest`). **Atomicity tests:** (a) two sessions for `/ws/a` on one node ⇒ node-set has the node
  once, first leave keeps it, last leave removes it; (b) the **same-node connect/disconnect race** — interleave a
  `subscribe(sessionB)` with an `unsubscribe(sessionA)` and assert the node stays in the node-set whenever a live
  session remains (the v2-regression guard); (c) cross-node `SREM`→0 concurrent with another node's `SADD` keeps both.
- **Per-session wiring** (`ClusterMessageSender`): `onLocalUriActive`/`Inactive` thread `sessionId` and call the
  registry per session; the held broker subscription is NOT dropped on last-leave.
- `MeshBroker.publish` interest routing: a peer **with** a live session receives, a peer **without** is skipped;
  registry read **failure/timeout ⇒ `null` ⇒ all-peers** and the null is **not cached** (a second publish within the
  TTL still attempts the read); authoritative **empty ⇒ no peers**; **reserved channel (`PRESENCE_CHANNEL`) ⇒
  all-peers** (bypass); live-membership ∩ interest (a dead-but-interested peer is not contacted).
- **Presence composition** (the §4.6 bypass): with interest routing **on**, a presence change on node A still reaches
  a remote watcher node B; no interest key is created for `PRESENCE_CHANNEL`.
- Two-node real-TCP-via-real-Redis E2E (extend `MeshTwoNodeE2ETest`): B has a live session on `/ws/a` only; A's
  publish to `/ws/a` reaches B, A's publish to `/ws/b` does **not**; close B's `/ws/a` session ⇒ A's next `/ws/a`
  publish (after ≤TTL) no longer targets B.
- Context tests (`NettyWebSocketClusterConfigureTest`): `mesh.enable=true` ⇒ interest registry present + wired;
  `interest-routing.enable=false` ⇒ absent (all-peers); `mesh.enable=false` ⇒ absent; **custom directory + custom
  interest registry ⇒ Redis gated off**; **custom directory + DEFAULT interest registry + all-custom core SPIs ⇒
  Redis stays on** (the OnAnyRedisSpiRequired interest-clause regression test).
- **Honesty regression:** a topic with live sessions on every node targets all peers (no false reduction); a
  physically-partitioned topic (live sessions on a subset) targets only that subset — on a **homogeneous**
  registration set (every node has the mapping; only some have live sessions), proving the reduction is session-grained.

---

## 10. Deferred

- **Approach C** (mesh interest-change notifications to shrink the ≤TTL freshly-subscribed window) → RC4c.
- **Full `netty.cluster.mesh.*` meters** (incl. `broadcast.target_nodes`, `fanout_fallback`, + the RC4a counters
  fixed to the shared `ClusterRuntimeStats` — RC4a BL6) → RC4d.
- Hierarchical/relay-tree fan-out for genuinely-global topics (out of mesh scope; sharded pub/sub → 2.0.0).
- Per-tenant / wildcard interest aggregation (YAGNI now).

---

## 11. Risks / open questions for design-review (v3 — post-re-review)

1. **Lua adaptation fidelity.** Confirm `JOIN_LUA`/`LEAVE_LUA` mirror `RedisRoomRegistry`'s (lines 79-105) atomic
   add-on-first/remove-on-last with the room dimension collapsed (per-node session set keyed by `(uri,node)`), and the
   hash-tag co-locates both keys for the `EVAL` (Redis-Cluster-safe).
2. **Reserved-channel set source.** Confirm the router's reserved set (`{ PRESENCE_CHANNEL }`) is sourced from the
   canonical constant and that `publish` to it is genuinely all-peers (no interest read), so presence has no
   dependence on any interest write.
3. **`OnAnyRedisSpiRequired` interest clause** covers the asymmetric custom-directory + default-interest case without
   over-retaining Redis when `interest-routing.enable=false`.
4. **§1 honesty precondition** (random-LB population saturation) is stated plainly and mirrored into release-notes +
   cluster-design, so RC4b is not over-claimed (RC1-style scope honesty).

---

## 12. Design-review resolutions (folded across two rounds)

**Round 1 (v1 → v2): registered-mapping grain → session grain.**

| # | Round-1 blocker | Resolution |
|---|---|---|
| A | Interest was registered-`@MessageMapping`-grained ⇒ zero reduction on a homogeneous fleet; `unsubscribe` dead code | Pivot to **session-grained** interest (§2, §4.3); reduction genuine on homogeneous fleets |
| B | `PRESENCE_CHANNEL` never registered interest ⇒ presence pruned to zero | (superseded by v3) reserved channels **bypass pruning** (§4.6) |
| C | "Verbatim RC1" emptySet-on-failure ⇒ a Redis blip drops the whole broadcast | **null-vs-empty sentinel**, null **not cached** (§4.4) + tests |
| D | Non-atomic `SREM`+`DEL` unsubscribe races cross-node | Atomic Lua (now `LEAVE_LUA`, §4.2) |
| E | `removeAllForNode` mischaracterized as node-scoped | v3 makes it **genuinely node-scoped** via the per-node session-set key suffix (§4.2) |
| F | `OnAnyRedisSpiRequired` "same shape" clause redundant | (corrected by v3) a **properly-predicated interest clause IS needed** (§5) |

**Round 2 (v2 → v3): the session-grained pivot's own flaws.**

| # | Round-2 finding | Resolution |
|---|---|---|
| R1 | **BLOCKER** — JVM session-count + two unordered Redis writes let a same-node connect/disconnect race permanently wipe a live node's membership | **RC1-exact in-EVAL atomicity**: per-node session set + JOIN/LEAVE Lua decides the 0↔1 flip; no JVM counter (§3 A, §4.2, §4.3) |
| R2 | **BLOCKER** — presence permanent-interest one-shot `SADD` had no retry ⇒ a transient blip permanently, silently killed cross-node presence | Reserved channels **bypass interest pruning** entirely — no presence SADD, no failure mode (§4.4, §4.6) |
| R3 | **BLOCKER/MAJOR** — `OnAnyRedisSpiRequired` "no clause" was unsound (custom directory + default interest registry orphans the Redis connection) | Add the **interest clause** + asymmetric context test (§5, §9) |
| R4 | **MAJOR** — §1 over-claimed partitioned reduction; omitted the random-LB / population-saturation precondition (the RC1 shard-ring lesson) | Add the **§1 precondition** + reframe examples (small population / session-sticky / tenant-affine) |
| R5 | **MINOR** — `removeAllForNode` cost label / null-not-cached not explicit / hook named `onSessionAdded` | Honest `O(total-keys)` cursor cost note (§4.2); null-not-cached stated + tested (§4.4, §9); hook named `onSessionRegistered` (§4.3) |
