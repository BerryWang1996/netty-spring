# RC4c — Mesh hot-path robustness (design)

**Status:** design — **two review rounds folded; plan-ready** (v2.1). **Branch:** `feature/1.10.0-mesh-rc4c` (off `v1.10.0-RC4b`).
**Module:** `netty-spring-websocket-cluster` (+ the starter for auto-config). **Stack:** Boot 2.7 + Netty 4.1 + Lettuce 6.1, JDK 17.

> **Design-review correction (recorded).** A 9-agent adversarial design-review (`fixDesignFirst=true`, archived at
> `docs/superpowers/notes/2026-06-20-rc4c-mesh-design-review.json`) caught three MAJOR design flaws + six refinements,
> all folded here: (1) putting the reconnect backoff inside the **shared** `connectionTo()` would latch the node
> DEGRADED for up to `backoff-max` because the membership tick — the *only* DEGRADED→ACTIVE recovery probe — also calls
> `connectionTo`; v2 scopes the backoff to the **hot path only** and lets the tick dial raw. (2) BL5's snapshot applied
> uniformly to **unicast** widened a silent-drop window from ~0 to ≤tickMs, breaking the documented
> `unicast → MessageSessionClosedException` contract; v2 keeps broadcast on the snapshot but makes unicast **fall back
> to a direct lookup on a snapshot miss**. (3) the "zero synchronous Redis on the hot path" goal was over-claimed (the
> RC4b interest `SMEMBERS` survives); v2 states the honest scope. Plus: WRITER_IDLE (not ALL_IDLE), BL4 reconcile moved
> to the end of `ClusterMessageSender.start()`, `reconnectNotBefore` pruned in the tick, a bounded `start()` populate,
> and honest BL2-guard / observability wording. See §9 for the finding-by-finding resolution.

---

## 1. Honest positioning (read this first)

RC4a built the mesh transport, RC4b made `publish` interest-routed. Both shipped with a **known gap the docs already
flag**: `MeshBroker.publish`/`unicast` resolve peer addresses with a **synchronous Redis `SCAN`+`GET` on the message
hot path, per message** (`directory.peers(nodeId).join()` — a full keyspace SCAN of `netty:mesh:addr:*` + a `GET` per
key), contradicting the mesh thesis *"Redis for control, off the message path."* RC4c closes that gap and rounds out
the transport's failure-mode robustness. It adds **no new application feature**; it makes the existing RC4a/RC4b mesh
**production-robust**.

**What BL5 actually removes (honest scope).** RC4c's snapshot removes the **per-message directory `SCAN`+`GET`** — the
expensive part. The publish path's *only* remaining Redis touch is the **RC4b interest node-set lookup**, which is a
**5s-TTL-cached `SMEMBERS`** (one read per URI per cache-TTL, command-timeout-bounded — **not** per message), and is
skipped entirely for reserved channels / when interest routing is off. So after RC4c the steady-state **broadcast** hot path does
**zero Redis I/O** (snapshot read + cached interest read); a Redis touch happens only on an interest-cache miss
(~once per URI per 5s). **Unicast** likewise reads the in-memory snapshot in steady state, falling back to a single
bounded direct lookup only on a ≤tickMs snapshot miss (which warms the snapshot — §3.1). Making the interest read
*also* fully in-memory (refreshed from the tick) is a larger change deferred to **RC4d / approach-C** — not claimed here.

**One-line scope:** RC4c (1) serves broadcast peer addresses from an **in-memory snapshot** refreshed by the existing
membership tick (and serves unicast from the snapshot with a **direct-lookup fallback** so it never silently widens its
delivery window), (2) adds **hot-path-only per-peer reconnect backoff** (no reconnect storm; the tick still probes for
recovery), (3) wires the **idle-timeout** knob (WRITER_IDLE reap of idle peer connections), (4) **reconciles** the
broker state at the end of `ClusterMessageSender.start()` (close a narrow missed-degrade edge), and (5) **fails fast**
on the `mesh.enable` + `cluster-nodes`/`nats.servers` misconfig. All `mesh.enable=false`-gated → byte-identical to
RC1/RC2/RC3 when the mesh is off.

**Explicitly deferred (separable subsystems, NOT RC4c):** mTLS on mesh channels (its own security stage); approach-C
mesh interest-change notifications (shrinks RC4b's ≤TTL window — and would make the interest read in-memory); full
`netty.cluster.mesh.*` meters (BL6 — RC4d); bidirectional-link dedup.

---

## 2. Goal & non-goals

**Goal.** When `mesh.enable=true`, the steady-state message hot path performs **no synchronous Redis I/O** (broadcast
reads the snapshot; interest is cache-read; unicast reads the snapshot, falling back to a single direct lookup only on
a miss). A down/flapping peer triggers **bounded reconnect attempts on the send path** (not one dial per message) while
the membership tick still probes it for recovery; idle peer connections are **proactively reaped**; a node isolated
before its state listener was wired is **reconciled** to DEGRADED; and a `mesh.enable=true` deployment that ALSO set
`redis.cluster-nodes` or `nats.servers` **fails fast at startup**.

**Non-goals (RC4c).** No change to interest-routing semantics (RC4b), framing/auth/envelope wire, the at-most-once
contract, or membership *correctness* (still `live-by-heartbeat ∩ has-address`); no `roomMessage`/`sendToUser`/presence
change; no mTLS / approach-C / full meters.

---

## 3. Components

### 3.1 BL5 — peer-address snapshot (Redis off the hot path) — **the headline**

**Problem.** `MeshBroker.publish` (line 265) and `unicast` (line 293) each call
`directory.peers(nodeId).toCompletableFuture().join()` — a blocking Redis SCAN loop + per-key GET on the caller
(WebSocket handler) thread, **per message**. The `membershipTick` (line 200) already fetches the same
`directory.peers(nodeId)` every `tickMs` (= `max(1000, advertiseTtlMs/3)` ≈ 10s).

**Design.** A `volatile Map<String,String> peerSnapshot` on `MeshBroker`, refreshed by the membership tick; the hot
path reads the snapshot.

```java
private volatile Map<String, String> peerSnapshot = java.util.Collections.emptyMap();

// in membershipTick(), right after the directory.peers() join (line 200):
Map<String, String> peers = directory.peers(nodeId).toCompletableFuture().join();
this.peerSnapshot = peers;   // hot path reads this; the existing live∩address reachability loop below is unchanged

// in start(), BEFORE scheduling the tick — BOUNDED so a slow Redis can't stall bean init unboundedly:
try { this.peerSnapshot = directory.peers(nodeId).toCompletableFuture().get(commandTimeoutMs, TimeUnit.MILLISECONDS); }
catch (Exception e) { log.debug("initial mesh peer snapshot failed/timed out — empty until first tick", e); }
```

- **`publish` (broadcast) reads the snapshot.** Replace `directory.peers(nodeId).join()` with `this.peerSnapshot`. A
  peer advertising *after* the last tick is invisible to broadcast for ≤`tickMs` — a freshly-joined-peer window,
  at-most-once, the same class as RC1's room-cache and RC4b's interest-cache ≤TTL windows; the proactive-connect tick
  warms the channel. **Acceptable for broadcast.**
- **`unicast` reads the snapshot WITH a direct-lookup fallback (the must-fix).** Unicast's `targetNodeId` is resolved
  authoritatively from the session/room/user registries — the caller *knows* that node hosts the session *now*, and
  the 1.9.0 contract is "unicast undeliverable → `MessageSessionClosedException` to the caller." A registry can learn
  of a node before the mesh tick refreshes the snapshot, so a snapshot miss must **not** become a silent drop:

  ```java
  String addr = this.peerSnapshot.get(targetNodeId);
  if (addr == null) {
      // snapshot miss for a registry-known target (freshly-joined node) — pay ONE bounded direct read AND WARM the
      // snapshot from its full result, so a burst of unicasts to the fresh target hits the warmed snapshot (no
      // per-message SCAN storm) rather than silently widening the undeliverable window from ~0 to ≤tickMs.
      try {
          Map<String, String> fresh = directory.peers(nodeId).toCompletableFuture()
                  .get(commandTimeoutMs, TimeUnit.MILLISECONDS);
          this.peerSnapshot = fresh;          // off-cycle snapshot warm (same volatile-swap as the tick)
          addr = fresh.get(targetNodeId);
      } catch (Exception e) { addr = null; }
  }
  if (addr == null) { stats.incMeshSendFailures(); return; }   // genuinely unknown — existing behavior
  ```

  The common case (target in the snapshot) is Redis-free; the **first** unicast to a freshly-joined target pays one
  bounded read **and warms the whole snapshot from it**, so subsequent unicasts (and broadcasts) to that node hit the
  snapshot — at most one SCAN per fresh-target per ≤tickMs window, not per message. Preserves unicast's ~0 staleness +
  the undeliverable contract.
- **Redis-loss resilience improves.** A Redis blip during the tick leaves the *previous* snapshot in place (the tick's
  try/catch already swallows the failure), so broadcast keeps routing to last-known peers instead of failing every
  message. The membership-correctness (`live∩address`) + isolation/degrade logic still run in the tick on the **fresh**
  `directory.peers()` result, not the snapshot.
- A volatile reference swap is safe vs a concurrent `publish` iterating the previous map (each `publish` snapshots the
  reference once into a local; the old map is never mutated).

### 3.2 Per-peer reconnect backoff — **hot-path only** (reconnect-storm guard)

**Problem.** For a persistently DOWN peer, **every** `sendTo` re-enters `connectionTo` and re-dials (blocking
`cf.sync()`, bounded by `connectTimeoutMs`) — a per-message blocking-connect storm.

**Design — backoff on the SEND path only; the tick dials raw.** The backoff MUST NOT live inside the shared
`connectionTo()`, because `membershipTick` also calls `connectionTo` and is the **sole** DEGRADED→ACTIVE recovery
probe (`evaluateReachability` restores only on `reachable>0`). Gating the tick would latch the broker DEGRADED for up
to `backoff-max` after a peer recovers. So:

```java
private final ConcurrentHashMap<String, long[]> reconnect = new ConcurrentHashMap<>(); // peerNodeId → [notBeforeMs, curBackoffMs]

/** Send-path connect: the backoff gates only the DIAL, never the use of an already-cached channel (e.g. one the
 *  membership tick just re-established). */
private Channel connectionForSend(String peerNodeId, String addr) {
    Channel existing = outbound.get(peerNodeId);
    if (existing != null && existing.isActive()) {
        return existing;   // a live cached channel (incl. one the tick reconnected) — backoff is irrelevant
    }
    long[] b = reconnect.get(peerNodeId);
    if (b != null && System.currentTimeMillis() < b[0]) {
        return null;  // no live channel AND within backoff → skip the dial (at-most-once drop)
    }
    Channel ch = connectionTo(peerNodeId, addr);   // the raw dial (unchanged; re-checks the cached fast-path)
    if (ch == null || !ch.isActive()) {
        long cur = (b == null) ? reconnectBackoffBaseMs : Math.min(b[1] * 2, reconnectBackoffMaxMs);
        reconnect.put(peerNodeId, new long[]{ System.currentTimeMillis() + cur, cur });   // extend backoff
    } else {
        reconnect.remove(peerNodeId);   // success → clear
    }
    return ch;
}
```

- `sendTo` calls `connectionForSend`; **`membershipTick` calls the raw `connectionTo`** (no backoff) — it already
  self-rate-limits to one dial per peer per `tickMs` (not a storm), so a recovered peer is re-probed each tick and
  `evaluateReachability` restores ACTIVE within ~`tickMs`, not ~`backoff-max`.
- `connectionTo` (the raw dial + the RC4a BL1 cache-replace loop) is **unchanged**.
- **Leak prevention:** `membershipTick` prunes `reconnect` to the live∩address peer set each tick. Materialize the set
  explicitly — `Set<String> live = new HashSet<>(peers.keySet()); live.removeAll(deadNodeView.get());
  reconnect.keySet().retainAll(live);` — so a dead-but-still-advertised peer's backoff entry is dropped immediately
  (not held until its address TTL-expires) and a vanished peer doesn't leak an entry. `ConcurrentHashMap.keySet()
  .retainAll` is weakly-consistent vs a concurrent send-path `put` (a stale entry may survive one extra tick —
  bounded, not a leak); `shutdown` clears the map.

### 3.3 BL3 — `idle-timeout-ms` → `IdleStateHandler` (WRITER_IDLE)

**Problem.** `ClusterProperties.Mesh.idleTimeoutMs` (default 60000) is **never consumed** — no `IdleStateHandler`
(RC4a impl-review BL3). Idle outbound connections are never proactively reaped.

**Design.** Thread `idleTimeoutMs` into the `MeshBroker` constructor; add a **WRITER_IDLE** `IdleStateHandler` to the
**outbound (client)** pipeline. The outbound channel is **write-only** (inbound is unused/directional), so READ is
never satisfied — `ALL_IDLE` would reap a merely-slow peer mid-traffic. `WRITER_IDLE` reaps only when *this node*
hasn't written to the peer for `idleTimeoutMs` (a genuinely-unused connection):

```java
// client initChannel (start()):
if (idleTimeoutMs > 0) {
    ch.pipeline().addLast(new io.netty.handler.timeout.IdleStateHandler(
            0, idleTimeoutMs, 0, TimeUnit.MILLISECONDS));   // writerIdle only
    ch.pipeline().addLast(new IdleCloseHandler());          // userEventTriggered: IdleStateEvent → ctx.close()
}
ch.pipeline().addLast(MeshFrames.prepender());
```

- `IdleCloseHandler` (a tiny `ChannelInboundHandlerAdapter`) closes on the `IdleStateEvent`; the channel's
  `closeFuture` listener already evicts it from `outbound`. The next `sendTo` re-dials lazily (honoring backoff).
  0 = disabled. Default 60s (conservative).

### 3.4 BL4 — reconcile the broker state at the end of `ClusterMessageSender.start()`

**Problem.** `broker.start()` schedules the membership tick **before** `ClusterMessageSender.start()` wires the
transport-state listener. With a low `advertise-ttl-ms` (→ 1s first tick) + a slow context refresh, the first tick can
degrade the broker and find the listener `null`, dropping `onTransportLost` and latching the broker DEGRADED while the
node manager stays ACTIVE (RC4a impl-review BL4).

**Design — reconcile in the sender, not inline in the setter.** Do **not** fire the callback inside
`setTransportStateListener` (firing inline on the bean-init thread, with `redis-loss-grace-period-ms=0`, could degrade
synchronously — possibly `closeAllLocalSessions` — *before* `start()` finishes wiring the CLOSE_ALL listener and the
broadcast/unicast subscriptions). Instead, at the **end** of `ClusterMessageSender.start()` (after all wiring +
subscriptions are in place), reconcile once:

```java
// end of ClusterMessageSender.start(), after setTransportStateListener + subscriptions:
if (broker.state() == BrokerState.DEGRADED) {
    // a tick degraded the broker before the listener was wired — deliver the missed callback now (idempotent:
    // node-manager onTransportLost is grace-debounced + CAS-guarded).
    nodeManager.onTransportLost();
}
```

- `setTransportStateListener` stays a plain setter. The reconcile reads `broker.state()` once on the sender's
  (already-running) thread after wiring is complete. Only `onTransportLost` is reconciled (a node wired while ACTIVE
  needs no callback; restore runs through the normal `evaluateReachability` path).

### 3.5 BL2 — transport-selection fail-fast guard

**Problem.** `mesh.enable=true` + `redis.cluster-nodes` (or `nats.servers`) set **silently** selects the
Redis-Cluster / NATS broker instead of the mesh (RC4a impl-review BL2).

**Design.** A fail-fast guard bean mirroring `natsTransportClasspathGuard`:

```java
@Bean
@ConditionalOnExpression(MESH_TRANSPORT + " and not (" + STANDALONE_TRANSPORT + " and " + NO_NATS_TRANSPORT + ")")
public Object meshTransportConflictGuard() {
    throw new IllegalStateException(
        "server.netty.websocket.cluster.mesh.enable=true requires the standalone-Redis transport, but "
        + "redis.cluster-nodes and/or nats.servers is also set — the mesh broker would be silently suppressed. "
        + "Use exactly one transport: unset cluster-nodes/nats.servers for the mesh, or set mesh.enable=false.");
}
```

- The SpEL fires on exactly the misconfig (`cluster-nodes` set, `nats.servers` set, or both) and never on a valid
  empty/empty mesh deployment (verified by the reviewer).
- **Honest caveat (folded):** unlike `natsTransportClasspathGuard` (which fills a genuine zero-broker void because
  every broker is `@ConditionalOnClass`-suppressed), here a real conflicting broker (`clusterBrokerCluster`, or
  `clusterBrokerNats` which opens a live NATS connection in its factory) is eligible alongside the guard with no
  enforced ordering — so one may be **briefly instantiated before the guard throws**. The failing context refresh then
  tears it down via destroy callbacks (a transient resource-open during a *failing* startup, not a leak). The guard's
  job is to abort startup with an actionable message; the operator fixes the config and restarts. (A
  `BeanFactoryPostProcessor` that fails before any broker is eligible is a possible future hardening — not needed for
  RC4c.)

---

## 4. Config & auto-config

| Key | Default | Meaning |
|---|---|---|
| `server.netty.websocket.cluster.mesh.idle-timeout-ms` | `60000` | **Now wired (RC4c).** Closes+reaps an outbound peer connection after this long with no WRITE to the peer (0 = disabled). Was inert. |
| `server.netty.websocket.cluster.mesh.reconnect-backoff-base-ms` | `1000` | Initial per-peer **send-path** reconnect backoff after a failed connect (doubles per consecutive failure). |
| `server.netty.websocket.cluster.mesh.reconnect-backoff-max-ms` | `30000` | Cap on the per-peer send-path reconnect backoff. |

The `MeshBroker` constructor gains `idleTimeoutMs`, `reconnectBackoffBaseMs`, `reconnectBackoffMaxMs`, and reuses the
existing `commandTimeoutMs` (passed for the bounded `start()` populate + the unicast fallback — wired from
`properties.getCommandTimeoutMs()`, as `setNodeLookupTimeoutMs` already is). Auto-config passes the three new knobs;
the `meshTransportConflictGuard` bean is additive. All existing `MeshBroker` test constructor call sites get the new
trailing args.

---

## 5. Consistency & failure semantics

- **At-most-once preserved.** BL5 changes *where* broadcast addresses are read; unicast keeps its ~0 window via the
  direct-lookup fallback. Backoff drops a frame to a down peer (already a drop today, now without the per-message
  re-dial). Idle-reap closes a connection the next send re-dials.
- **Broadcast freshly-joined-peer window (BL5):** ≤`tickMs` (≈10s), at-most-once, RC1/RC4b precedent, tick-warmed.
  **Unicast does NOT widen** (snapshot-hit common case, direct-read fallback on a miss — preserving the
  `unicast → MessageSessionClosedException` undeliverable contract).
- **Backoff is fail-safe + does not delay recovery.** The send path skips a dial within the window (drop+count); the
  membership tick dials raw, so a recovered peer restores ACTIVE within ~`tickMs`.
- **BL4 reconcile** runs once at the end of sender `start()` (all wiring in place), idempotent via the node-manager
  grace-debounce.
- **Observability honesty:** the backoff/backpressure drops increment the broker's internal counters; surfacing them
  to `netty.cluster.mesh.*` meters (and fixing the throwaway-`ClusterRuntimeStats` wiring, BL6) is **RC4d** — RC4c does
  **not** claim operator-visible meters for these drops.

---

## 6. Testing

- **BL5 broadcast snapshot:** `publish` performs **no** `directory.peers()` call (a `MeshNodeDirectory` whose `peers()`
  THROWS on the hot path but was pre-populated via one `membershipTick()` → publish still routes from the snapshot);
  `start()` populates it initially; refresh on tick. Extend `MeshTwoNodeE2ETest` to assert broadcast still delivers
  after the directory starts throwing on `peers()`.
- **BL5 unicast fresh-target:** a target node advertised AFTER the last tick (not in the snapshot) → `unicast` falls
  back to the direct lookup and still resolves the address (assert one fallback read happened, delivery succeeds);
  a genuinely-unknown target → drop+count (existing behavior).
- **Backoff (hot path) + recovery:** a peer at a dead address → first `sendTo`/`connectionForSend` dials+fails and sets
  the backoff; a second within the window returns null **without** dialing (connect-count or timing bound); **a
  membershipTick within the window still dials raw and, when the peer is back, restores ACTIVE within ~tickMs** (the
  must-fix recovery test); a successful connect clears the backoff; `reconnect` is pruned to the live peer set.
- **Idle (WRITER_IDLE):** with a short `idle-timeout-ms`, an idle outbound channel closes + is evicted, **and the next
  send re-dials and delivers** (not just "channel closes").
- **BL4 reconcile:** broker forced DEGRADED (`evaluateReachability(1,0)`) BEFORE the sender finishes `start()` →
  assert `nodeManager.onTransportLost` is delivered exactly once after wiring.
- **BL2 guard:** context tests — `mesh.enable=true` + `redis.cluster-nodes` ⇒ fail fast; `mesh.enable=true` +
  `nats.servers` ⇒ fail fast (and assert no NATS connection / Redis-Cluster client left running); `mesh.enable=true` +
  standalone ⇒ no guard, mesh broker present.
- **No-op paths:** `mesh.enable=false` byte-identical; `idle-timeout-ms=0` → no IdleStateHandler.

---

## 7. Backward compatibility

`mesh.enable=false` ⇒ none of this exists — byte-identical to RC1/RC2/RC3. The `MeshBroker` constructor gains args
(internal, not an SPI). `idle-timeout-ms` changes inert→active (documented RC4c behavior; conservative 60s default,
WRITER_IDLE so only a truly-unused connection is reaped). The transport-conflict guard turns a previously-silent
misconfig into a fail-fast — an intentional change for an unsupported config combination (no correct deployment
affected). Same wire, no envelope bump.

---

## 8. Risks / open questions for design-review (v2 — post-fold)

1. **Unicast fallback cost.** On a snapshot miss the fallback is a full `directory.peers()` SCAN, but it **warms the
   whole snapshot** from that read (§3.1), so it costs **at most one SCAN per fresh target per ≤tickMs window** — not
   per message, even under a burst of unicasts to a just-joined node's sessions. A single-peer directory SPI method
   (`lookup(nodeId)`) to avoid the one SCAN is deferred as YAGNI.
2. **Backoff base/cap defaults** (1s→30s): reasonable for a ~10s tick? Confirm the send-path backoff + the raw-tick
   recovery interact as intended (recovery ≤tickMs regardless of backoff state).
3. **WRITER_IDLE default 60s** vs the advertise TTL (30s): a peer with no app traffic for 60s gets its outbound channel
   reaped and re-dialed on the next send — confirm that's the intended "reap genuinely-idle" behavior, not churn.
4. **BL4 reconcile placement** at the end of `ClusterMessageSender.start()` — confirm all listeners/subscriptions are
   wired before that point so a synchronous degrade (grace=0) sees a fully-wired node.

---

## 9. Design-review resolutions (folded)

| # | Finding (severity) | Resolution |
|---|---|---|
| 1 | **MAJOR** — backoff in shared `connectionTo()` latches DEGRADED for ~backoff-max (the tick is the recovery probe) | Backoff scoped to the **send path** (`connectionForSend`); `membershipTick` dials **raw** `connectionTo` (§3.2) + recovery test |
| 2 | **MAJOR** — BL5 snapshot widens the **unicast** silent-drop window, breaking `unicast→MessageSessionClosedException` | Unicast keeps the snapshot but **falls back to a direct lookup on a miss** (§3.1) + fresh-target test |
| 3 | **MAJOR** — "zero synchronous Redis on the hot path" over-claimed (RC4b interest `SMEMBERS` survives) | Honest §1/§2: BL5 removes the per-message directory SCAN+GET; interest is a 5s-cached `SMEMBERS`; fully-in-memory interest is RC4d/approach-C |
| 4 | nit — `ALL_IDLE` on a write-only channel reaps a slow-but-alive peer | **WRITER_IDLE** (`IdleStateHandler(0, idle, 0)`) (§3.3) |
| 5 | nit — BL4 reconcile fired inline in the setter → grace=0 re-entrancy during `start()` | Moved to the **end of `ClusterMessageSender.start()`** (§3.4) |
| 6 | nit — `reconnectNotBefore` leak (onNodeLeft doesn't prune) | `membershipTick` prunes via `retainAll(livePeers)` (§3.2) |
| 7 | nit — initial `start()` populate is an unbounded `join()` | Bounded `.get(commandTimeoutMs, MS)` (§3.1) |
| 8 | nit — BL2 guard doesn't prevent a transient conflicting-broker open | Honest caveat: transient open torn down by the failing refresh; + the mesh+nats context test (§3.5, §6) |
| 9 | nit — §5 implied backoff drops are operator-visible (throwaway stats, BL6) | Dropped the visibility claim; meters are RC4d (§5) |

**Round 2 (v2 → v2.1): re-review of the v2 patches** (`docs/superpowers/notes/2026-06-20-rc4c-mesh-rereview.json` —
M1/M2/M3 confirmed resolved; one new MAJOR in the v2 unicast fallback + 2 minor):

| # | Round-2 finding | Resolution |
|---|---|---|
| R1 | **MAJOR** — the v2 unicast fallback resolved into a local var and never warmed `peerSnapshot`, so a burst of unicasts to a freshly-joined node re-paid a full SCAN **per message** for ≤tickMs (re-introducing the storm BL5 removes) | The fallback now **warms the whole snapshot** from its `directory.peers()` read (§3.1); ≤1 SCAN per fresh target per window. §8 risk #1 corrected. |
| R2 | nit — the M1 `retainAll` prune used `peers.keySet()`, not the live∩address set (would hold a dead-but-advertised peer's backoff entry until TTL) | Materialize `live = peers.keySet() − deadNodeView.get()` for the prune (§3.2) |
| R3 | nit — §1 "zero Redis on the hot path" headline could be read as covering unicast | Scoped §1 explicitly to broadcast+interest; unicast's snapshot+bounded-fallback noted inline |
| — | self-review (pre-re-review) — backoff gated a cached-active channel the tick reconnected | `connectionForSend` checks `outbound.get(peer).isActive()` BEFORE the backoff window (§3.2) |
