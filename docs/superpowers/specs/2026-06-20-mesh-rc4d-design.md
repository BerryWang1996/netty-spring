# 1.10.0-RC4d — Mesh observability (`netty.cluster.mesh.*` Micrometer meters) — Design (v2)

**Status:** design-review folded (round 1) · **Date:** 2026-06-20 · **Line:** 1.10.0 (built on RC4c)
**Author:** maintainer + Claude · **Supersedes:** the "RC4d = full `netty.cluster.mesh.*` meters" backlog line in RC4a/RC4b/RC4c

> **v2 changelog (round-1 adversarial design-review, 4 lenses):** the v1 "BL6 = swap the broker's stats to the
> sender's instance via a setter" design was **wrong** — `broker.start()` runs inside the broker `@Bean` (line
> 402) *before* the sender bean exists, so any setter-swap is after the server is bound and the tick is running
> (a real startup race, the false-invariant BLOCKER). v2 replaces it with: **the binder reads the broker's OWN
> `ClusterRuntimeStats` for the mesh block** — the broker's instance is where the counters are already written;
> it was merely never *read*. No sharing, no swap, no start-ordering hazard, no `volatile`. Also folded:
> disjoint `backoff.skips` vs `send.failures`, `frames.sent` counted on write-success, a real `fanout.target_nodes`
> reduction gauge (the headline meter — the room sampler already exists), the static-`IdleCloseHandler` fix, and
> corrected `peers.known` semantics.

---

## 1. Goal

Make the mesh transport **observable**. RC4a–RC4c built the node-to-node TCP broker, interest routing, and
hot-path robustness — but a `MeshBroker`'s internal counters (frames, send failures, backpressure drops, idle
reaps, reconnect-backoff skips) and its live connection/peer/fan-out state are **invisible** to Micrometer
today. After RC4d, a `mesh.enable=true` deployment surfaces an aggregate `netty.cluster.mesh.*` meter set on
every `MeterRegistry`, exactly like the existing `netty.cluster.*` / `.room.*` / `.offline.*` / `.presence.*`
families — **including a fan-out reduction gauge** so you can finally *see* the RC4b interest routing working.

This is the **last functional RC before 1.10.0 GA**. It ships no new transport behaviour — only observability
(plus one small, contained counter-accounting cleanup, §4.4) — so it is low-risk and a clean GA on-ramp.

### The real bug — **BL6 (the mesh counters are written but never read)**

`MeshBroker` increments `meshFramesReceived` / `meshSendFailures` / `meshSendDroppedBackpressure` on the
`ClusterRuntimeStats` it was constructed with (`NettyWebSocketClusterConfigure.java:379`). The meter binder,
however, only ever reads the **sender's** stats (`sender.getClusterRuntimeStats()`), so those three counters —
added back in RC4a — are **dead**: written every frame, read by nothing. RC4d makes the binder read the
broker's instance for the mesh meters.

> **Why not unify the two instances?** Because the counters partition cleanly by owner and never overlap:
> - The **sender** owns the routing/application counters (`broadcastPublished`, `unicastSent`,
>   `crossNodeBroadcastReceived` via its subscribe-listener callback, `publishFailures`, room/offline/presence …)
>   — all on `sender.getClusterRuntimeStats()`.
> - The **broker** owns the transport counters (TCP frames in/out, backpressure drops, idle reaps,
>   backoff skips, fan-out samples) — all on its own `ClusterRuntimeStats`.
>
> No counter is incremented on both, so the binder can read each meter from its true owner. Unifying (a shared
> `@Bean`, or a setter-swap) buys nothing and — because the broker self-starts at bean-creation, before the
> sender bean — cannot satisfy a "wired before any traffic" invariant. Reading the broker's own instance is
> both simpler and correct.

---

## 2. Background / grounding (verified against the tree)

**The existing meter binder** — `NettyClusterMeterBinder`
(`netty-websocket-cluster-spring-boot-starter`, `com.github.berrywang1996.netty.spring.boot.configure`), a
Micrometer `MeterBinder`:

- Gated by `@ConditionalOnClass(MeterRegistry.class)` + cluster `enable=true` (via `NettyClusterMetricsConfigure`,
  `@AutoConfigureAfter(NettyWebSocketClusterConfigure.class)`,
  `@ConditionalOnBean({MeterRegistry.class, ClusterMessageSender.class})`).
- Constructed with `(ClusterMessageSender sender, ClusterNodeManager nodeManager, ClusterBroker broker,
  MessageAuthenticator authenticator)` — **it already holds the `broker` reference** (used for the
  `netty.cluster.broker.state` per-state gauges, `NettyClusterMeterBinder.java:175-180`).
- `bindTo(registry)` reads `stats = sender.getClusterRuntimeStats()` once, registers `FunctionCounter`s via a
  private `counter(name, stats, fn, desc)` helper over `FunctionCounter.builder`, plus `Gauge`s. Idempotent per
  registry (an `IdentityHashMap` `boundRegistries` guard).
- Precedent for a **reduction gauge from a sampler**: the room family already ships
  `netty.cluster.room.fanout.target_nodes` as `Gauge.builder(..., ClusterRuntimeStats::getRoomFanoutTargetsAvg)`
  (`NettyClusterMeterBinder.java:108-111`), backed by a 3-field sampler in `ClusterRuntimeStats`
  (`roomFanoutSampleCount` / `roomFanoutTargetsTotal` / `roomFanoutTargetsLast` + `getRoomFanoutTargetsAvg()`).
  RC4d reuses this exact pattern for mesh fan-out.

**How the Redis path is metered (and why mesh is structurally different).** `RedisPubSubBroker` takes **no**
stats; the *sender* counts the Redis path. Mesh has counters **below** that abstraction (TCP frame in/out,
backpressure, idle reaps) that only the broker sees, so `MeshBroker` was given its own stats param. RC4d simply
reads that param's instance.

**`ClusterRuntimeStats` mesh inventory (existing, RC4a):**

| field (AtomicLong) | getter | inc | incremented in `MeshBroker` |
|---|---|---|---|
| `meshFramesReceived` | `getMeshFramesReceived()` | `incMeshFramesReceived()` | `onInboundFrame()` (~509) |
| `meshSendFailures` | `getMeshSendFailures()` | `incMeshSendFailures()` | `sendTo` no-live-channel (413), `sendTo` catch (418), `writeFramed` async-fail listener (437) |
| `meshSendDroppedBackpressure` | `getMeshSendDroppedBackpressure()` | `incMeshSendDroppedBackpressure()` | `writeFramed` not-writable (431) |

> **Correction to v1 (finding F9):** the not-writable branch (431) increments **backpressure**, not failures,
> and returns without touching `meshSendFailures`. The accurate failure sites are 413 / 418 / 437.

**`MeshBroker` live state (for gauges):**
- `outbound` (`ConcurrentHashMap<String,Channel>`, ~93) — live outbound channels, lazily dialed, evicted on
  close/idle-reap.
- `peerSnapshot` (`volatile Map<String,String>`, ~113) — **the raw `directory.peers(nodeId)` result** assigned
  at start (208), every membership tick (230), and on a unicast snapshot-miss warm (334). The dead-node
  subtraction (`deadNodeView`) is applied **only locally** inside the tick for the reachability count
  (238-252); it is **not** persisted into `peerSnapshot`. So the snapshot is "peers advertised in the directory
  (present by address TTL)", which may **briefly include a heartbeat-dead peer** whose mesh address has not yet
  expired.
- No production accessors today (only `outboundForTest()`).

**Broker self-start ordering (the F1 fact):** `broker.start()` is invoked **inside the mesh broker `@Bean`**
(`NettyWebSocketClusterConfigure.java:402`), which binds the server channel (`MeshBroker.java:173`), populates
`peerSnapshot` (208), and schedules the membership tick (218) — all **before** the sender bean
(`...:857`) is constructed and long before `sender.start()` (`...:914`). `ClusterMessageSender.start()` never
calls `broker.start()`. This is why a "wire the stats before start" setter is impossible and why v2 reads the
broker's own instance instead.

---

## 3. Scope

### In scope
1. **BL6 (read, don't share)** — the binder reads `((MeshBroker) broker).runtimeStats()` for the mesh block.
2. **4 new `ClusterRuntimeStats` mesh members** — counters `meshFramesSent`, `meshIdleReaps`,
   `meshReconnectBackoffSkips`; plus a **fan-out sampler** (`meshFanoutSampleCount` / `meshFanoutTargetsTotal` /
   `meshFanoutTargetsLast` + `recordMeshFanoutTargets(int)` + `getMeshFanoutTargetsAvg()`), mirroring the room
   sampler.
3. **Increment/record sites in `MeshBroker`** — incl. a small **counter-accounting cleanup** so
   `reconnect.backoff_skips` and `send.failures` are **disjoint** (§4.4).
4. **3 new `MeshBroker` production accessors** — `runtimeStats()`, `activeOutboundConnections()`,
   `knownPeerCount()`.
5. **9 new meters** registered in `NettyClusterMeterBinder.bindTo()`, guarded by `instanceof MeshBroker` so the
   Redis path emits none of them.
6. **Docs** — api-guide mesh-metrics table, cluster-design RC4d row, release-notes RC4d (EN+中文),
   development-plan + CLAUDE version sync.

### Out of scope (deferred, stated honestly)
- **Per-peer tags.** All mesh meters are **node-aggregate** — no `peer`/`remote-node` tag (unbounded
  cardinality; same rule as room/offline/presence).
- **Mesh state in the health indicator.** `ClusterHealthIndicator` reads the sender's stats and shows no mesh
  counters; RC4d does not extend it (the meter set is the time-series surface). A deliberate non-goal.
- **Micrometer Observation API / trace propagation** — 2.0.0 (Boot 3.x).
- **mTLS, approach-C interest-change notifications** — separable subsystems, still deferred.

---

## 4. Design

### 4.1 BL6 — the binder reads the broker's own stats

Add one production accessor to `MeshBroker`:

```java
/** RC4d: the broker's own runtime stats — where mesh transport counters are written. The meter binder reads
 *  THIS (not the sender's instance) for the netty.cluster.mesh.* meters. */
public ClusterRuntimeStats runtimeStats() { return stats; }
```

`stats` stays `final` (no swap, no `volatile`). The binder's mesh block uses `meshStats =
((MeshBroker) broker).runtimeStats()` for the six counters; the two existing trio counters and the four new
members are all on this instance. (See §4.6 for the full registration.)

### 4.2 New counters in `ClusterRuntimeStats`

Three new `AtomicLong`s + getters + inc methods:

| field | getter | inc | semantics |
|---|---|---|---|
| `meshFramesSent` | `getMeshFramesSent()` | `incMeshFramesSent()` | a frame **successfully written** to a peer channel (the `writeAndFlush` listener fired `isSuccess`). Disjoint from `send.failures` and `dropped_backpressure` (§4.4). |
| `meshIdleReaps` | `getMeshIdleReaps()` | `incMeshIdleReaps()` | an outbound channel closed by the RC4c WRITER_IDLE reaper. |
| `meshReconnectBackoffSkips` | `getMeshReconnectBackoffSkips()` | `incMeshReconnectBackoffSkips()` | a **send-path** dial deliberately skipped because the per-peer reconnect-backoff window was open. **Not** a failure (§4.4). The raw membership-tick dial is un-gated and uncounted. |

### 4.3 New fan-out sampler in `ClusterRuntimeStats` (mirrors the room sampler)

```java
private final AtomicLong meshFanoutSampleCount  = new AtomicLong();
private final AtomicLong meshFanoutTargetsTotal = new AtomicLong();
private final AtomicLong meshFanoutTargetsLast  = new AtomicLong();

/** Record the number of peers a single mesh broadcast actually targeted (post interest-pruning, or all known
 *  peers when interest routing is off / a registry read failed / a reserved channel). The fan-out reduction
 *  observation point — compare getMeshFanoutTargetsAvg() against knownPeerCount(). */
public void recordMeshFanoutTargets(int targets) {
    meshFanoutTargetsTotal.addAndGet(targets);
    meshFanoutTargetsLast.set(targets);
    meshFanoutSampleCount.incrementAndGet();
}
public double getMeshFanoutTargetsAvg() {
    long n = meshFanoutSampleCount.get();
    return n == 0 ? 0.0 : (double) meshFanoutTargetsTotal.get() / n;
}
public long getMeshFanoutTargetsLast() { return meshFanoutTargetsLast.get(); }
```

### 4.4 Increment/record sites in `MeshBroker` (incl. the disjoint-counter cleanup)

**Cleanup (finding F4): make `reconnect.backoff_skips` and `send.failures` disjoint.** Today a backoff-skipped
send returns `null` from `connectionForSend` and `sendTo` then counts it as a `meshSendFailure` (413) — so a
naive `incMeshReconnectBackoffSkips()` at the skip branch would double-count the same event. Fix by moving the
**reason-specific** accounting into `connectionForSend` (its only caller is `sendTo`), so each null reason is
counted exactly once at its origin and `sendTo` stops blanket-counting `null` as a failure:

```java
// connectionForSend(...) — RC4d
Channel existing = outbound.get(peerNodeId);
if (existing != null && existing.isActive()) { reconnect.remove(peerNodeId); return existing; }
long[] b = reconnect.get(peerNodeId);
if (b != null && System.currentTimeMillis() < b[0]) {
    stats.incMeshReconnectBackoffSkips();   // deliberate shed — NOT a failure
    return null;
}
Channel ch = connectionTo(peerNodeId, addr);
if (ch == null || !ch.isActive()) {
    long cur = (b == null) ? reconnectBackoffBaseMs : Math.min(b[1] * 2, reconnectBackoffMaxMs);
    reconnect.put(peerNodeId, new long[]{ System.currentTimeMillis() + cur, cur });
    stats.incMeshSendFailures();             // genuine dial/connect failure — counted here now
    return ch;
}
reconnect.remove(peerNodeId);
return ch;
```

```java
// sendTo(...) — RC4d: connectionForSend already accounted for the null reason (skip OR dial-fail); do not
// double-count. The catch still counts an unexpected exception.
protected void sendTo(String peerNodeId, String addr, String wrapped) {
    try {
        Channel ch = connectionForSend(peerNodeId, addr);
        if (ch == null || !ch.isActive()) { return; }   // reason already counted in connectionForSend
        writeFramed(peerNodeId, ch, wrapped);
    } catch (Exception e) {
        stats.incMeshSendFailures();
        log.debug("mesh send to {} threw", peerNodeId, e);
    }
}
```

Net effect — every send attempt lands in **exactly one** bucket: `backoff_skip` (deliberate shed) | `send.failure`
(no/dead channel, dial fail, async-write fail, unexpected exception) | `dropped_backpressure` (peer not writable)
| `frames.sent` (write succeeded). Totals are unchanged except backoff-skips no longer inflate failures.

**`incMeshFramesSent()` (finding F6) — on write success, not enqueue:**

```java
// writeFramed(...) — RC4d: count a SENT frame only when the async write actually succeeds, so frames.sent is
// disjoint from the async-failure path (and from the not-writable backpressure drop above).
ch.writeAndFlush(MeshFrames.toPayload(wrapped)).addListener(f -> {
    if (f.isSuccess()) {
        stats.incMeshFramesSent();
    } else {
        stats.incMeshSendFailures();
        log.debug("mesh send to {} failed", peerNodeId, f.cause());
    }
});
```

**`incMeshIdleReaps()` (finding F2) — the static handler must be given `stats`.** `IdleCloseHandler` is
`private static final class` (line 632) and cannot read the instance field. Give it the (final) stats via its
constructor when the pipeline is built (line ~196):

```java
// in start(), where the client pipeline is assembled:
ch.pipeline().addLast(new IdleCloseHandler(stats));   // was: new IdleCloseHandler()

private static final class IdleCloseHandler extends io.netty.channel.ChannelInboundHandlerAdapter {
    private final ClusterRuntimeStats stats;
    IdleCloseHandler(ClusterRuntimeStats stats) { this.stats = stats; }
    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof io.netty.handler.timeout.IdleStateEvent) {
            stats.incMeshIdleReaps();   // RC4d: count the reap before closing
            ctx.close();
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }
}
```

(Passing the final `stats` to the ctor is clean precisely because §4.1 keeps it non-swappable.)

**`recordMeshFanoutTargets(...)` (finding F5) — once per broadcast, on the publish path.** `publish` filters
peers **inline** against the interest set (it does not build a `targets` collection), so count the peers
actually addressed in the loop and record after:

```java
// publish(...) — RC4d: count the inline-filtered fan-out and record it once per broadcast.
int targets = 0;
for (Map.Entry<String, String> e : peers.entrySet()) {
    if (interested != null && !interested.contains(e.getKey())) {
        continue; // peer has no live audience for this uri
    }
    targets++;
    sendTo(e.getKey(), e.getValue(), wrapped);
}
stats.recordMeshFanoutTargets(targets);   // interested.size() effectively, or peers.size() when interested==null
```

This records the count of peers **addressed** (interest-routed subset; or all known peers when routing is off /
the registry read failed / the channel is reserved → `interested == null`), before per-peer backoff/backpressure
outcomes — it measures the *routing* fan-out, not delivery success. An empty snapshot honestly records `0`.

### 4.5 New gauges — live reads off `MeshBroker`

```java
/** RC4d gauge: live outbound channels currently cached (lazily dialed; usually <= knownPeerCount). */
public int activeOutboundConnections() { return outbound.size(); }

/** RC4d gauge: peers currently in this node's directory snapshot (advertised by address TTL; the raw
 *  directory view — may briefly include a heartbeat-dead peer whose mesh address has not yet expired). */
public int knownPeerCount() { return peerSnapshot.size(); }
```

### 4.6 Meter registration (extend `bindTo`)

Append one guarded block after the presence block and before the state gauges:

```java
// ---- Mesh transport (1.10.0-RC4d) — only when the broker IS the mesh broker ----
if (broker instanceof MeshBroker) {
    MeshBroker mesh = (MeshBroker) broker;
    ClusterRuntimeStats meshStats = mesh.runtimeStats();   // the BROKER's own instance (BL6)
    counter(registry, "netty.cluster.mesh.frames.received", meshStats,
            ClusterRuntimeStats::getMeshFramesReceived, "Frames received over the mesh TCP transport");
    counter(registry, "netty.cluster.mesh.frames.sent", meshStats,
            ClusterRuntimeStats::getMeshFramesSent, "Frames successfully written to a peer channel (one per peer per fan-out)");
    counter(registry, "netty.cluster.mesh.send.failures", meshStats,
            ClusterRuntimeStats::getMeshSendFailures, "Mesh sends that failed (no/dead channel, dial failure, async write failure)");
    counter(registry, "netty.cluster.mesh.send.dropped_backpressure", meshStats,
            ClusterRuntimeStats::getMeshSendDroppedBackpressure,
            "Frames dropped because a peer's outbound channel was not writable (the slow-peer OOM guard)");
    counter(registry, "netty.cluster.mesh.idle.reaps", meshStats,
            ClusterRuntimeStats::getMeshIdleReaps, "Outbound channels closed by the WRITER_IDLE reaper (RC4c)");
    counter(registry, "netty.cluster.mesh.reconnect.backoff_skips", meshStats,
            ClusterRuntimeStats::getMeshReconnectBackoffSkips,
            "Send-path dials deliberately skipped while a per-peer reconnect-backoff window was open (RC4c) — a partition signal, NOT a failure");
    Gauge.builder("netty.cluster.mesh.fanout.target_nodes", meshStats, ClusterRuntimeStats::getMeshFanoutTargetsAvg)
            .description("Average peers targeted per mesh broadcast (the fan-out reduction meter; compare to mesh.peers.known)")
            .register(registry);
    Gauge.builder("netty.cluster.mesh.connections.active", mesh, m -> (double) m.activeOutboundConnections())
            .description("Live outbound mesh channels currently cached (lazily dialed; usually <= peers.known)")
            .register(registry);
    Gauge.builder("netty.cluster.mesh.peers.known", mesh, m -> (double) m.knownPeerCount())
            .description("Peers in this node's directory snapshot (advertised by address TTL; raw — may briefly include a heartbeat-dead peer)")
            .register(registry);
}
```

The `instanceof` guard makes the nine meters appear **only** on a mesh deployment.

### 4.7 Meter summary (9 meters)

| meter | type | source |
|---|---|---|
| `netty.cluster.mesh.frames.received` | counter | `getMeshFramesReceived()` |
| `netty.cluster.mesh.frames.sent` | counter | `getMeshFramesSent()` *(new)* |
| `netty.cluster.mesh.send.failures` | counter | `getMeshSendFailures()` |
| `netty.cluster.mesh.send.dropped_backpressure` | counter | `getMeshSendDroppedBackpressure()` |
| `netty.cluster.mesh.idle.reaps` | counter | `getMeshIdleReaps()` *(new)* |
| `netty.cluster.mesh.reconnect.backoff_skips` | counter | `getMeshReconnectBackoffSkips()` *(new)* |
| `netty.cluster.mesh.fanout.target_nodes` | gauge | `getMeshFanoutTargetsAvg()` *(new sampler)* |
| `netty.cluster.mesh.connections.active` | gauge | `MeshBroker.activeOutboundConnections()` *(new accessor)* |
| `netty.cluster.mesh.peers.known` | gauge | `MeshBroker.knownPeerCount()` *(new accessor)* |

---

## 5. Honest scope (for the docs)

- **Aggregate per node, no per-peer tags.** This node's totals, not a per-remote breakdown. A 100-node mesh
  creates no per-peer tag explosion.
- **`fanout.target_nodes` is the reduction meter, read against `peers.known`.** `avg ≈ peers.known` ⇒ no
  reduction (global topic / saturated audience); `avg ≪ peers.known` ⇒ interest routing is pruning. **The RC1/RC4b
  random-LB caveat still holds:** a *logically* partitioned but high-population topic saturates peers under
  random LB and shows little reduction — the gauge will honestly show `avg` near `peers.known` in that case,
  which is the truth, not a bug.
- **`peers.known` is the raw directory snapshot, not a liveness probe.** It counts peers advertised by address
  TTL and may briefly include a heartbeat-dead peer whose mesh address lingers (the membership tick subtracts
  heartbeat-dead peers only for its reachability decision, not from the snapshot).
- **`connections.active` is usually ≤ `peers.known`, but not invariantly.** Channels are dialed lazily on first
  send and evicted on idle-reap/close; a peer dropped from the snapshot can keep a cached channel until reaped,
  so `active` can **transiently exceed** `known`. A large `known − active` gap is normal and informative — it
  means a mostly-quiet topology **or**, under interest routing, that broadcasts dial only the interested subset.
- **`frames.sent` counts per-peer fan-out** (a broadcast to k targeted peers ⇒ +k) and only on **write success**;
  it is disjoint from `send.failures`, `dropped_backpressure`, and `reconnect.backoff_skips`. `frames.sent /
  broadcast.published` is the empirical mean fan-out — the same quantity `fanout.target_nodes` reports directly.
- **`reconnect.backoff_skips` is a deliberate shed, not a failure**, and counts the send path only (the tick
  dials raw, the sole recovery probe, uncounted).
- **No trace/Observation** (2.0.0).

---

## 6. Testing

All in `netty-spring-websocket-cluster` (broker counters/accessors) + the starter context test (binder/wiring).

1. **`ClusterRuntimeStats` new members** — unit test: `incMeshFramesSent` / `incMeshIdleReaps` /
   `incMeshReconnectBackoffSkips` move their getters; `recordMeshFanoutTargets(k)` over several samples yields the
   right `getMeshFanoutTargetsAvg()` and `getMeshFanoutTargetsLast()` (mirror any existing room-sampler test).
2. **Disjoint counters (F4)** — extend `MeshReconnectBackoffTest`: a within-window `connectionForSend` increments
   `meshReconnectBackoffSkips` by 1 and `meshSendFailures` by **0**; a genuine dial-fail increments
   `meshSendFailures` by 1 and `backoffSkips` by 0; the cached-active fast path and the raw tick dial increment
   neither.
3. **`meshFramesSent` on success (F6)** — in the two-node E2E (`MeshTwoNodeE2ETest`) or `MeshInterestRoutingTest`,
   assert `broker.runtimeStats().getMeshFramesSent()` increases by the number of targeted peers after a broadcast,
   and that a backpressure drop / async fail does **not** also bump it.
4. **`meshIdleReaps` (F2)** — extend `MeshIdleReapTest`: after the reap, `runtimeStats().getMeshIdleReaps() >= 1`.
5. **`recordMeshFanoutTargets` on publish (F5)** — in `MeshInterestRoutingTest`, after a broadcast routed to a
   known interested subset, assert `getMeshFanoutTargetsLast()` == the targeted-peer count and `…Avg()` reflects it;
   and an all-peers publish (interest off / reserved channel) records `knownPeerCount()`.
6. **Accessors** — extend `MeshSnapshotTest`: `knownPeerCount()` reflects the snapshot after a tick;
   `activeOutboundConnections()` goes 0 → 1 after a dial and back to 0 after an idle reap (`MeshIdleReapTest`).
7. **Binder wiring / BL6 (the regression guard)** — context/integration test (mirror
   `NettyWebSocketClusterConfigureTest`): with `mesh.enable=true` + a `SimpleMeterRegistry`, assert (a) the nine
   `netty.cluster.mesh.*` meters exist, and (b) after driving a mesh broadcast,
   `registry.get("netty.cluster.mesh.frames.sent").functionCounter().count() > 0` — proving the binder reads the
   broker's own instance. With `mesh.enable=false` (Redis path), assert **none** of the `netty.cluster.mesh.*`
   meters exist (the `instanceof` guard).
8. **Full reactor green** — `mvn install -DskipTests` then `mvn test` across 11 modules; expect the prior 629 +
   the new tests, 0 skips.

---

## 7. Rollout & docs (Doc Sync Matrix)

- `docs/api-guide.md` — add the nine-meter table to the mesh section (after the RC4c robustness block), with the
  aggregate/no-per-peer-tag note, the `fanout.target_nodes` vs `peers.known` reduction reading (+ random-LB
  caveat), and the `peers.known` raw-snapshot / `connections.active` transient-overshoot explanations.
- `docs/cluster-design.md` — add an RC4d scope row (mesh observability; BL6 read-not-share; the fan-out gauge;
  disjoint counters).
- `docs/release-notes-1.10.0.md` — RC4d section (EN + 中文), 0 U+FFFD; the nine meters, BL6, the disjoint-counter
  cleanup, and the honest reduction-gauge reading.
- `docs/development-plan.md` — RC4 row: append RC4d-cut; the `1.10.0` line stays "开发中（RC4d）".
- `.claude/CLAUDE.md` — version line RC4c→RC4d + an RC4d summary; "Next" → RC4d ✅ cut → **1.10.0 GA**.
- `docs/release-checklist.md` — no change (reuse).

Then the standard cut: bump 11 POMs RC4c→RC4d, `release: 1.10.0-RC4d` commit, tag `v1.10.0-RC4d`, FF-merge to
master, **STOP before push**.

---

## 8. Rejected alternatives

1. **Share one `ClusterRuntimeStats` (a `@Bean`, or swap the broker's to the sender's via a setter)** — v1's
   approach. Rejected: the broker self-starts in its `@Bean` (line 402) **before** the sender bean, so no setter
   can run "before any traffic" (the false-invariant BLOCKER), and a shared bean buys nothing because the
   broker- and sender-owned counters never overlap. Reading the broker's own instance is simpler and correct.
2. **Count `frames.sent` at `writeAndFlush` enqueue** — overlaps `send.failures` on an async write failure (the
   same frame in two buckets). Rejected for listener-success counting (clean partition).
3. **Leave `backoff.skips` ⊆ `send.failures` and just document it** — acceptable but forces operators to subtract.
   Rejected for the disjoint-counter cleanup (§4.4), which is a small, single-caller change that gives
   non-overlapping buckets.
4. **Defer the fan-out reduction gauge** — v1 did, over-claiming it needed new publish-path sampling and a design
   pass. False: the targeted set is already computed on `publish`, and the room sampler is a proven precedent.
   It is the single most valuable mesh meter (it shows RC4b working), so it ships in RC4d.
5. **Surface gauges via counters instead of live `MeshBroker.size()` reads** — redundant state mirroring.
   Rejected (DRY).
6. **Register mesh meters unconditionally (no `instanceof` guard)** — nine always-zero meters on every Redis
   deployment. Rejected.

---

## 9. Notes for implementation-review

- Confirm `connectionForSend`'s only caller is `sendTo` (so moving failure accounting there changes no other
  path) — verified at design time, re-verify on impl.
- Confirm the `publish` target-set is materialised (a `Collection` whose `.size()` is the true fan-out) at the
  record point, including the interest-off / read-failure / reserved-channel all-peers fallbacks.
- Confirm there is exactly one `IdleCloseHandler` add-site (the client pipeline) so the ctor change is local.
- The fan-out sampler is read-mostly aggregate (three `AtomicLong`s); no per-publish allocation.
