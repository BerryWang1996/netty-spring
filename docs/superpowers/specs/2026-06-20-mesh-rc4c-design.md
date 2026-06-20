# RC4c — Mesh hot-path robustness (design)

**Status:** design (brainstorming output, pre design-review). **Branch:** `feature/1.10.0-mesh-rc4c` (off `v1.10.0-RC4b`).
**Module:** `netty-spring-websocket-cluster` (+ the starter for auto-config). **Stack:** Boot 2.7 + Netty 4.1 + Lettuce 6.1, JDK 17.

---

## 1. Honest positioning (read this first)

RC4a built the mesh transport, RC4b made `publish` interest-routed. Both shipped with a **known gap the docs already
flag**: `MeshBroker.publish`/`unicast` resolve peer addresses with a **synchronous Redis `SCAN`+`GET` on the message
hot path, per message** (`directory.peers(nodeId).join()`), which contradicts the mesh's headline thesis — *"Redis for
control, off the message path."* RC4c closes that gap and rounds out the transport's failure-mode robustness. It adds
**no new feature surface** to the application; it makes the existing RC4a/RC4b mesh **production-robust**.

**One-line scope:** RC4c (1) moves peer-address resolution **off the hot path** (an in-memory snapshot refreshed by the
existing membership tick), (2) adds **per-peer reconnect backoff** (no reconnect storm to a down peer), (3) wires the
**idle-timeout** knob (reap idle peer connections), (4) **reconciles** the transport-state listener on wiring (close a
narrow missed-degrade edge), and (5) **fails fast** on the `mesh.enable` + `cluster-nodes`/`nats.servers` misconfig.
All of it is `mesh.enable=false`-gated → byte-identical to RC1/RC2/RC3 when the mesh is off.

**Explicitly deferred (separable subsystems, NOT RC4c):**
- **mTLS on mesh channels** — a distinct security concern (SslContext on both pipelines, cert/key loading + rotation,
  config surface). Its own stage (RC4c-sec or 2.1.0 enterprise-security); bundling it would make RC4c span two
  subsystems.
- **Approach-C (mesh interest-change notifications)** — shrinks RC4b's ≤TTL freshly-subscribed window from ≤TTL to
  ~RTT. An optimization of RC4b's staleness, not core robustness. Deferred.
- **Full `netty.cluster.mesh.*` meters (BL6)** — RC4d.
- **Bidirectional-link dedup** (a node A↔B holding two outbound channels) — an optimization; the current one-outbound-
  per-peer cache is correct, just not deduplicated across the inbound direction. Deferred (YAGNI now).

---

## 2. Goal & non-goals

**Goal.** When `mesh.enable=true`, the message hot path (`publish`/`unicast`) performs **zero synchronous Redis calls**;
a down/flapping peer triggers **bounded reconnect attempts** (not one dial per message); idle peer connections are
**proactively reaped**; a node isolated before its state listener was wired is **reconciled** to DEGRADED; and a
`mesh.enable=true` deployment that ALSO set `redis.cluster-nodes` or `nats.servers` **fails fast at startup** with an
actionable message (instead of silently running a different transport).

**Non-goals (RC4c).**
- No change to the interest-routing semantics (RC4b), the framing/auth/envelope wire, or the at-most-once contract.
- No change to `roomMessage`/`sendToUser`/presence.
- No mTLS, no approach-C, no full meters (see §1 deferred).
- No change to membership *correctness* (still `live-by-heartbeat ∩ has-address`); only *where/when* addresses are read.

---

## 3. Components

### 3.1 BL5 — peer-address snapshot (Redis off the hot path) — **the headline**

**Problem.** `MeshBroker.publish` (line 265) and `unicast` (line 293) each call
`directory.peers(nodeId).toCompletableFuture().join()` — a blocking Redis `SCAN` loop + per-key `GET` (see
`RedisMeshNodeDirectory.peers`) on the caller (WebSocket handler) thread, **per message**. The `membershipTick`
(line 200) already fetches the same `directory.peers(nodeId)` every `tickMs` (= `max(1000, advertiseTtlMs/3)` ≈ 10s).

**Design.** Add a `volatile Map<String,String> peerSnapshot` to `MeshBroker`, refreshed by the membership tick; the
hot path reads the snapshot, never Redis:

```java
private volatile Map<String, String> peerSnapshot = java.util.Collections.emptyMap();

// in membershipTick(), right after the directory.peers() join (line 200):
Map<String, String> peers = directory.peers(nodeId).toCompletableFuture().join();
this.peerSnapshot = peers;   // publish/unicast read this snapshot, no Redis on the hot path
// ... (the existing live∩address reachability loop is unchanged)

// in start(), BEFORE scheduling the tick: one synchronous populate so publish works immediately (not empty until +tick)
try { this.peerSnapshot = directory.peers(nodeId).toCompletableFuture().join(); }
catch (Exception e) { log.debug("initial mesh peer snapshot failed — empty until first tick", e); }

// publish(): replace `directory.peers(nodeId).join()` with the snapshot
Map<String, String> peers = this.peerSnapshot;

// unicast(): replace the per-call join with the snapshot lookup
String addr = this.peerSnapshot.get(targetNodeId);
```

- **Semantics unchanged except freshness.** The snapshot is exactly what `publish`/`unicast` read before, just cached.
  A peer that advertises *after* the last tick is invisible to the hot path for up to `tickMs` — a **freshly-joined-
  peer membership-staleness window**, at-most-once, the same class as RC1's room-cache and RC4b's interest-cache ≤TTL
  windows. The membership tick also proactively connects to peers (line 213), so the outbound connection is warm by
  the time the snapshot exposes the peer. Documented (§5 honesty); tunable via `advertise-ttl-ms` (shorter ttl →
  shorter tick → fresher snapshot, more advertise writes).
- **Redis-loss resilience improves.** A Redis blip during the tick leaves the *previous* snapshot in place (the tick's
  try/catch already swallows the failure), so the hot path keeps routing to the last-known peers instead of failing —
  strictly better than the per-message join (which would block/throw on every message during the blip).
- **No new scheduler.** Reuses the existing single `cluster-mesh-tick` thread.

### 3.2 Per-peer reconnect backoff (reconnect-storm guard)

**Problem.** `connectionTo` dials on demand (blocking `cf.sync()`, bounded by `connectTimeoutMs`). For a persistently
DOWN peer, **every** `sendTo` re-enters `connectionTo` and re-dials (RC4a BL1 fixed the cache-replace race, but a peer
that is simply unreachable is re-dialed on every message — a per-message blocking-connect storm). The RC4a impl-review
explicitly deferred per-peer backoff to RC4c.

**Design.** A per-peer backoff window in `MeshBroker`:

```java
private final ConcurrentHashMap<String, Long> reconnectNotBefore = new ConcurrentHashMap<>(); // peerNodeId → epochMs

// in connectionTo(), after the "existing active" fast-path and before dialing:
Long notBefore = reconnectNotBefore.get(peerNodeId);
if (notBefore != null && System.currentTimeMillis() < notBefore) {
    return null;   // within backoff → skip the dial; sendTo drops+counts (at-most-once)
}
// ... dial ...
// on dial FAILURE (the catch): set/extend backoff (capped exponential)
reconnectNotBefore.merge(peerNodeId, /* next = */ now + nextBackoffMs(peerNodeId), Long::max);
// on dial SUCCESS: clear the backoff
reconnectNotBefore.remove(peerNodeId);
```

- Backoff is **bounded exponential**: start at a base (e.g. `connectTimeoutMs` or a `reconnect-backoff-base-ms`,
  default 1000) doubling to a cap (`reconnect-backoff-max-ms`, default 30000). A simple scheme: store the *current*
  backoff per peer alongside the not-before time, double on each consecutive failure, reset on success.
- **No staleness risk:** the membership tick's proactive connect path ALSO honors the backoff (it calls
  `connectionTo`), so a down peer isn't hammered by the tick either; once it recovers, the next attempt after the
  backoff window reconnects and clears it.
- `reconnectNotBefore` entries for departed peers are cleaned on `shutdown`/`onNodeLeft` (bounded by peer count).

### 3.3 BL3 — `idle-timeout-ms` → `IdleStateHandler` (wire the inert knob)

**Problem.** `ClusterProperties.Mesh.idleTimeoutMs` (default 60000) is **never consumed** — no `IdleStateHandler` on
either pipeline (RC4a impl-review BL3). Idle outbound connections are never proactively reaped (only TCP keep-alive).
The knob "lies."

**Design.** Thread `idleTimeoutMs` into the `MeshBroker` constructor and add an `IdleStateHandler` to the **outbound
(client)** pipeline; on `ALL_IDLE` (no read or write for `idleTimeoutMs`), close the channel (its `closeFuture`
listener already evicts it from `outbound`), so an idle peer connection is reaped (frees the fd; the next `sendTo`
re-dials lazily). 0 = disabled.

```java
// client initChannel (start()):
if (idleTimeoutMs > 0) {
    ch.pipeline().addLast(new io.netty.handler.timeout.IdleStateHandler(0, 0, idleTimeoutMs, TimeUnit.MILLISECONDS));
    ch.pipeline().addLast(new IdleCloseHandler());  // userEventTriggered: on IdleStateEvent → ctx.close()
}
ch.pipeline().addLast(MeshFrames.prepender());
```

- `IdleCloseHandler` is a tiny `ChannelInboundHandlerAdapter` that closes on an `IdleStateEvent`. Server pipeline is
  left as-is (inbound idle reaping is RC4c-optional; the outbound cache is the fd-leak surface).
- Wired in auto-config: `m.getIdleTimeoutMs()` → the constructor.

### 3.4 BL4 — reconcile the transport-state listener on wiring

**Problem.** `broker.start()` schedules the membership tick **before** `ClusterMessageSender.start()` calls
`setTransportStateListener`. With a non-default low `advertise-ttl-ms` (→ a 1s first tick) + a slow context refresh,
the first tick can fire `evaluateReachability` → `compareAndSet(ACTIVE, DEGRADED)` and find the listener `null`, so
`onTransportLost` is **dropped** and the broker is **latched DEGRADED** while the node manager stays ACTIVE (RC4a
impl-review BL4). Narrow, but a real missed-callback.

**Design.** Make `setTransportStateListener` **reconcile** the current state when wiring:

```java
@Override
public void setTransportStateListener(TransportStateListener listener) {
    this.transportStateListener = listener;
    // RC4c BL4: if the broker already degraded before the listener was wired (a tick fired during context refresh),
    // deliver the missed callback now so the node manager isn't left ACTIVE while the broker is latched DEGRADED.
    if (listener != null && state.get() == BrokerState.DEGRADED) {
        listener.onTransportLost();
    }
}
```

- Idempotent: the node manager's `onTransportLost` is debounced (grace timer) and CAS-guarded; a redundant call is a
  harmless no-op. Symmetric restore is not needed (a DEGRADED→ACTIVE recovery fires `onTransportRestored` through the
  normal `evaluateReachability` path once a peer is reachable).

### 3.5 BL2 — transport-selection fail-fast guard

**Problem.** `STANDALONE_MESH_BROKER` requires the standalone path, so `mesh.enable=true` + `redis.cluster-nodes` set
(or `nats.servers` set) **silently** selects the Redis-Cluster / NATS broker instead of the mesh — no warn, no
fail-fast — while the operator believes they are on the mesh (RC4a impl-review BL2). The codebase already fails fast
for the analogous NATS-classpath misconfig (`natsTransportClasspathGuard`).

**Design.** A fail-fast guard bean in `NettyWebSocketClusterConfigure` (mirroring `natsTransportClasspathGuard`):

```java
@Bean
@ConditionalOnExpression(MESH_TRANSPORT + " and not (" + STANDALONE_TRANSPORT + " and " + NO_NATS_TRANSPORT + ")")
public Object meshTransportConflictGuard(ClusterProperties properties) {
    throw new IllegalStateException(
        "server.netty.websocket.cluster.mesh.enable=true requires the standalone-Redis transport, but "
        + "redis.cluster-nodes and/or nats.servers is also set — the mesh broker would be silently suppressed. "
        + "Use exactly one transport: unset cluster-nodes/nats.servers for the mesh, or set mesh.enable=false.");
}
```

- Activates ONLY in the exact misconfig (`mesh.enable=true` AND not the standalone+no-NATS path). A correct mesh
  deployment (`mesh.enable=true` + standalone Redis + no NATS) does not match → no guard, broker starts normally.

---

## 4. Config & auto-config

| Key | Default | Meaning |
|---|---|---|
| `server.netty.websocket.cluster.mesh.idle-timeout-ms` | `60000` | **Now wired (RC4c).** Closes+reaps an outbound peer connection idle for this long (0 = disabled). Was inert in RC4a/RC4b. |
| `server.netty.websocket.cluster.mesh.reconnect-backoff-base-ms` | `1000` | Initial per-peer reconnect backoff after a failed connect (doubles per consecutive failure). |
| `server.netty.websocket.cluster.mesh.reconnect-backoff-max-ms` | `30000` | Cap on the per-peer reconnect backoff. |

No new hot-path config for BL5 (the snapshot reuses the membership tick; freshness tracks `advertise-ttl-ms`). The
`MeshBroker` constructor gains `idleTimeoutMs`, `reconnectBackoffBaseMs`, `reconnectBackoffMaxMs` (auto-config passes
`m.getIdleTimeoutMs()` etc.); the existing `meshTransportConflictGuard` bean is additive. All existing test constructor
call sites get the 3 new trailing args.

---

## 5. Consistency & failure semantics

- **At-most-once preserved.** BL5 changes *where* addresses are read, not delivery; reconnect-backoff drops a frame to
  a down peer (already a drop today, now without the re-dial storm); idle-reap closes a connection the next send
  re-dials.
- **Freshly-joined-peer window (BL5).** A peer advertising after the last tick is invisible to the hot path for
  ≤`tickMs`. Same class as the RC1/RC4b ≤TTL windows; at-most-once; the proactive-connect tick warms the channel. The
  membership-correctness (`live∩address`) and isolation/degrade logic are unchanged (they still run in the tick on the
  fresh `directory.peers()` result).
- **Redis-loss on the hot path improves.** With BL5, a Redis blip no longer blocks/fails every `publish`/`unicast`
  (they read the last snapshot); only the membership refresh pauses until Redis returns.
- **Backoff is fail-safe.** Within a peer's backoff window, `connectionTo` returns null → `sendTo` drops+counts
  (`mesh.send_dropped_backpressure`-style failure counter) — never a block, never an unbounded retry.
- **BL4 reconcile is idempotent** (node-manager debounce + CAS). **BL2 guard** only fires on the misconfig.

---

## 6. Testing

- **BL5 snapshot:** `publish`/`unicast` perform **no `directory.peers()` call** (a recording/throwing
  `MeshNodeDirectory` whose `peers()` fails on the hot path but is pre-populated via one `membershipTick()` → publish
  still routes from the snapshot); the snapshot refreshes on `membershipTick()`; `start()` populates it initially.
  Extend `MeshTwoNodeE2ETest` to assert broadcast still delivers with a directory that throws on `peers()` after the
  initial snapshot.
- **Reconnect backoff:** a peer at a dead address → first `connectionTo` dials+fails and sets the backoff; a second
  `connectionTo` within the window returns null **without** dialing (assert via a connect-counting bootstrap or a
  timing bound); after the window, it dials again; a successful connect clears the backoff.
- **Idle handler:** with a short `idle-timeout-ms`, an idle outbound channel is closed + evicted from `outbound`
  (two real brokers, send once, then assert the channel closes after the idle window).
- **BL4 reconcile:** put a broker in DEGRADED (via `evaluateReachability(1,0)`), THEN `setTransportStateListener` →
  assert the listener's `onTransportLost` fires exactly once on wiring.
- **BL2 guard:** context test — `mesh.enable=true` + `redis.cluster-nodes` set ⇒ context fails fast with the
  actionable `IllegalStateException`; `mesh.enable=true` + standalone ⇒ no guard, mesh broker present (the existing
  mesh context test already covers the happy path).
- **No-op paths:** `mesh.enable=false` byte-identical; `idle-timeout-ms=0` → no IdleStateHandler.

---

## 7. Backward compatibility

`mesh.enable=false` ⇒ no mesh, none of this exists — byte-identical to RC1/RC2/RC3. The `MeshBroker` constructor gains
3 args (internal; not an SPI). `idle-timeout-ms` changes from inert to active (the documented RC4c behavior; default
60s is conservative). The transport-conflict guard turns a previously-silent misconfig into a fail-fast — an
**intentional** behavior change for an unsupported config combination (no correct deployment is affected). Same wire,
no envelope bump.

---

## 8. Risks / open questions for design-review

1. **BL5 staleness vs the old always-fresh per-publish read.** Is a ≤`tickMs` (≈10s default) freshly-joined-peer
   window acceptable for broadcast, given at-most-once + the proactive-connect warm-up + RC1/RC4b precedent? Should a
   dedicated `membership-refresh-ms` knob (faster than the advertise tick) be added now, or is reusing the tick +
   `advertise-ttl-ms` tuning enough (YAGNI)?
2. **Backoff interaction with the membership tick.** The tick calls `connectionTo` for every live∩address peer; with
   backoff, a down peer is skipped during its window — confirm this does not wrongly affect `evaluateReachability`
   (a backed-off peer is `reachable=0` for that peer, which is correct — it IS unreachable).
3. **Idle-reap vs the snapshot.** Closing an idle outbound channel evicts it from `outbound` but the peer stays in the
   snapshot — confirm the next `sendTo` re-dials cleanly (it does: `connectionTo` sees no active channel → dials,
   honoring backoff). No interaction bug.
4. **BL4 reconcile scope.** Is firing only `onTransportLost` (not a restore) on wiring correct, given the restore path
   runs through the normal `evaluateReachability`? (Lean yes — a node wired while ACTIVE needs no callback.)
5. **Scope honesty.** Confirm mTLS + approach-C are genuinely separable and correctly deferred (RC4c stays a focused
   robustness RC, not a security/optimization grab-bag).
