# 1.10.0-RC4a — MeshBroker (node-to-node TCP transport skeleton) — Design Spec

> Status: design-review-folded. The 4-lens adversarial design review ran; its **skeptic-verify + synthesis phases were
> truncated by a transient server-side rate-limit + a session limit**, so 1 finding was machine-verified (the
> write-buffer BLOCKER) and the rest were folded by the controller on direct code-grounded engineering judgment (each
> cites an in-repo precedent). Partial review archived: `docs/superpowers/notes/2026-06-15-rc4a-mesh-design-review.json`.
> RC4a is the FIRST sub-milestone of RC4 (node-to-node mesh — the real node-ceiling break). Interest-routed broadcast
> (the fan-out reduction) is **RC4b**. Additive only; no existing SPI signature changes.

## 1. Goal & honest scope

A new **`MeshBroker implements ClusterBroker`** over **direct Netty TCP**, a drop-in for `RedisPubSubBroker`. Registry +
heartbeat **stay on Redis** (off the message hot path). RC4a proves the transport works end-to-end — **direct unicast**
(no Redis hop) + broadcast — on real TCP between two nodes, with a two-node real-TCP E2E.

**Honest scope (the ceiling-break is NOT in RC4a):** RC4a broadcast is **naive** — `publish(uri,env)` sends to all
peers; each peer dispatches to its local subscribers and a peer with no subscriber drops it. There is **NO fan-out
reduction** yet (that is RC4b's interest routing). So RC4a mesh broadcast is *correct + safe*, **not faster than Redis**
(it is N× to all peers, like Redis but decentralized). Its value is the **transport foundation** + **direct unicast** +
the safe base RC4b extends. Documented loudly, not hidden. Deferred: interest-routed broadcast (RC4b); mTLS,
bidirectional-link dedup, richer failure detection, metrics/cut polish (RC4c/RC4d).

## 2. Components

### 2.1 `MeshBroker implements ClusterBroker`
- One Netty **TCP server** bound to `mesh.bind-address:mesh.port` (boss+worker groups), accepting inbound peer links =
  the **receive** side.
- A lazily-created, cached **outbound connection per peer** (auto-reconnect) = the **send** side. Directional links
  (A↔B = 2 TCP conns); bidirectional dedup deferred to RC4c.
- `unicast(nodeId,env)` → resolve `nodeId→host:port` via the directory (§2.2) → write a framed, HMAC-wrapped envelope to
  that peer's outbound channel. `publish(uri,env)` → write to **every peer** (minus self) — RC4a naive. `subscribe(uri,l)`
  / `subscribeUnicast(nodeId,l)` → register `l` in a local map keyed by uri / "unicast". `state()` per §4.
- **Inbound**: a single pipeline `LengthFieldBasedFrameDecoder → bytes→String → handler` that decodes the frame
  (`maxFrameLength` = §3 cap), then **hands the decoded envelope to a business executor** (NOT the Netty event loop —
  §3) which unwraps (HMAC) → `codec.decode` → routes by `kind`/`uri` to the registered local listener.
- **Self-suppression**: send-side never writes to self; the existing `originNodeId` receive-check in
  `ClusterMessageSender` stays (over mesh the origin never receives its own send, so it is belt-and-suspenders).
- Reuses the existing `EnvelopeCodec` + `MessageAuthenticator` **unchanged, outside the transport**.

### 2.2 `MeshNodeDirectory` SPI (new) — node→address advertisement
The codebase stores only `nodeId + timestamp` in the heartbeat (no address). The mesh needs `nodeId → reachable
host:port`. New SPI; Redis-backed default `RedisMeshNodeDirectory` (reuses the cluster Redis connection; pluggable for
k8s/Consul later):
- `advertise(nodeId, advertisedHost, port, ttlMs)` — write `netty:mesh:addr:{nodeId} = host:port` with TTL, on start +
  periodic refresh.
- `peers()` → live `nodeId → host:port` (minus self).
- `remove(nodeId)` — on graceful deregister.
- `shutdown()` — its own small refresh scheduler.

**Membership is derived, single-source-of-truth (folded — dual-membership concern):** a node is a routable peer **iff
the heartbeat liveness says it is alive AND it has an advertised address**. The directory provides *address-for-a-live-
node*; it does NOT introduce a second, divergent "is alive" view. Dead-node reconciliation (the existing
`deadNodeCallback`) closes that peer's outbound connection and the address entry expires by TTL.

## 3. Transport correctness must-fixes (design-review-folded)

- **M1 — Outbound backpressure (the verified BLOCKER).** Each outbound peer channel sets a **`WriteBufferWaterMark`**
  (default low 32 KiB / high 64 KiB, configurable). Before/at write, if **`!channel.isWritable()`** the frame for that
  peer is **dropped and counted** (`mesh.send_dropped_backpressure` + `onPublishFailure`), **never buffered
  indefinitely**. This makes a single *slow* peer safe (drops to it; does not OOM the node) and keeps the at-most-once
  contract honest (drop is visible, not silent). Precedent: the server WS pipeline already does
  `configureWriteBufferWaterMark` + `isWritable()` (NettyChannelInitializer) — the mesh outbound bootstrap needs the
  same.
- **M2 — Inbound frame-size cap.** `LengthFieldBasedFrameDecoder.maxFrameLength = mesh.max-frame-bytes` (default = the
  existing `message-max-size-bytes`, ~1 MiB). An oversized/garbage length prefix is rejected (close the connection),
  so a malicious/corrupt frame can't OOM the receiver. Mirrors the Redis inbound size cap.
- **M3 — Offload inbound dispatch off the event loop.** The `ClusterMessageListener` contract says `onMessage` runs on
  the transport I/O thread and must not block; local fan-out/app work must not run on the mesh Netty event loop. The
  inbound handler decodes the frame on the event loop, then dispatches unwrap+decode+listener on a dedicated business
  executor (mirrors how `RedisPubSubBroker` moves decode/dispatch off the I/O loop).

## 4. Lifecycle & the mesh degrade signal (folded — Redis-vs-mesh divergence)

A single dead/slow peer is **per-target** (handled by M1 drop-and-count + `onPublishFailure`), NOT a global node
degrade. But the existing reliability machinery (`on-redis-loss`, `redis-loss-grace-period-ms`, `DEGRADED` node state,
`close-all`) is driven by the broker's `TransportStateListener.onTransportLost()` — if the mesh **never** fires it, that
machinery is silently dead in mesh mode. So:
- **The mesh's `onTransportLost()` fires only on TOTAL ISOLATION**: the node has peers it *should* reach (directory
  non-empty) but can reach **zero** of them. `onTransportRestored()` fires when at least one peer becomes reachable.
  `state()` = `DEGRADED` during total isolation, else `ACTIVE` while the local server is bound.
- This keeps `on-redis-loss`-style degrade meaningful ("I am cut off from the whole mesh") while honoring "one dead
  peer ≠ node down." The spec names the exact trigger so the divergence is explicit, not silent.
- **Async write-failure visibility:** Netty writes complete on a future; the write listener (or `!isWritable()` at M1)
  routes the failure to `onPublishFailure`/`mesh.send_dropped_*` — a buffered-but-never-flushed write is impossible
  under M1, so there is no silent-loss-until-OOM path.
- Reconnect: bounded backoff per peer (avoid reconnect storms). Shutdown ordering: stop accepting → close outbound
  channels (flush bounded) → close server → release groups.

## 5. `advertised-host` explicitness (folded — the Kafka footgun)

Auto-detecting and silently trusting a local IP makes the mesh **silently unreachable** under multi-NIC/containers/NAT/
k8s. So:
- If `mesh.advertised-host` is **set**, use it verbatim.
- If unset, auto-detect a **non-loopback site-local** address, but **fail fast at startup** if it resolves to loopback
  or is ambiguous (multiple candidate NICs), with a clear message: "set `server.netty.websocket.cluster.mesh.advertised-host`
  explicitly (containers/NAT/k8s require it)."
- The advertised address is a new Redis write — no secret, but the **no-auth + not-network-isolated warning** (mirroring
  the Redis URI warning) applies: the mesh listens on a TCP port; warn loudly if `auth.enable=false`.

## 6. Config & auto-config

- `ClusterProperties.Mesh`: `enable=false`, `port` (listen+advertise), `advertised-host` (default auto+validated),
  `bind-address=0.0.0.0`, `connect-timeout-ms=5000`, `idle-timeout-ms=60000`, `max-frame-bytes` (default =
  `message-max-size-bytes`), `write-buffer-high-water-mark-bytes=65536`, `write-buffer-low-water-mark-bytes=32768`.
- **Selection:** `mesh.enable=true` → the `MeshBroker` bean replaces the Redis/NATS broker; **Redis registry/heartbeat
  retained** (`@ConditionalOnExpression` standalone-Redis + not-all-NATS). `OnAnyRedisSpiRequired` keeps the Redis
  connection alive for the registry. The `MeshNodeDirectory` bean is gated the same way.
- Purely additive: no change to `ClusterBroker`/`SessionRegistry`/`EnvelopeCodec`/`ClusterNodeHeartbeat`/`RoomOperations`/
  `UserOperations`/`PresenceRegistry` signatures. `ClusterMessageSender` plugs the `MeshBroker` into the unchanged SPI.

## 7. SPI-fit & the RC4b foundation (folded — dead-end check)

`subscribe(uri,listener)` registers a **local** listener (RC4a has no cross-node subscription effect). This is **not a
dead end**: RC4b interest-routing layers an interest view (which nodes subscribe which URIs) ON TOP — RC4a's local
listener map becomes the local half, and `publish()` changes from "all peers" to "peers with interest." RC4a's SPI
shape is the clean foundation, not a throwaway.

## 8. Testing

- Unit: framing round-trip (length-prefix encode/decode, partial-read, oversized-frame rejection); directory advertise/
  peers/remove (Mockito + InMemory); `MeshBroker` publish/unicast routing + self-suppression + backpressure-drop
  (embedded channel with a non-writable channel → frame dropped + counted).
- **Headline two-node real-TCP E2E** (`MeshTwoNodeE2ETest`, mirrors `ClusterMultiNodeE2ETest`): two `MeshBroker`s on two
  ports in one JVM, Redis directory; a client on node-A receives a **broadcast** from node-B **and** a **direct unicast**
  from node-B — proving cross-node delivery rides TCP, not Redis Pub/Sub. A backpressure test: a stalled peer's frames
  are dropped+counted, the sender does not OOM.

## 9. Backward compatibility

`mesh.enable=false` (default): no mesh beans, the Redis (or NATS) broker is selected exactly as today — byte-identical.
Mesh mode is a transport swap only; all higher layers (rooms, offline, presence, reliable) ride the same `ClusterBroker`
SPI unchanged. Redis-backed registry/heartbeat/directory. Boot 2.7 + Netty 4.1.

## 10. Design-review record (folded)

- **BLOCKER (machine-verified)** unbounded outbound buffer → §3 M1 (watermark + drop-and-count).
- **Folded must-fixes (controller, code-grounded):** inbound frame cap (§3 M2); offload inbound dispatch (§3 M3);
  `advertised-host` explicitness/fail-fast (§5); the mesh total-isolation degrade trigger so reliability machinery
  isn't silently disabled (§4); derived single-source membership (§2.2); honest naive-broadcast scope + RC4b foundation
  (§1/§7).
- **Note:** the skeptic-verify + synthesis phases were rate-limited/session-limited mid-run; a re-run of the full
  adversarial review (or an impl-review gate after coding) should re-confirm the folded items. The impl-review before
  the RC4a cut covers this.

## 11. Deferred to later RC4 sub-milestones

RC4b: interest-routed broadcast (the fan-out reduction — the actual ceiling-break). RC4c: mTLS / bidirectional-link
dedup / richer failure detection / per-peer reconnect-storm controls / backpressure tuning. RC4d: full metrics
(`netty.cluster.mesh.*`), docs, capacity-model update, cut.
