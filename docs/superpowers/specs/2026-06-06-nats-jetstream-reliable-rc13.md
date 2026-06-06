# NATS JetStream Reliable Broadcast — RC13 Design Spec

**Target:** netty-spring 1.9.0-RC13
**Branch:** `feature/1.9.0-rc13-nats-reliable`
**Status:** approved 2026-06-06 (brainstormed; awaiting user spec review)

---

## 1. Goal

Close the all-NATS reliability gap (explicitly listed in `release-notes-1.9.0.md` Known Limitations since
RC10): provide **at-least-once reliable broadcast** on JetStream-enabled NATS, parallel to RC2's
`RedisStreamsReliableBroker`. Selected only when `nats.registry=true && reliable.enable=true`, leaving all
other tiers byte-level identical.

**Non-goals:**
- Reliable broadcast in mixed deployments (NATS broker + Redis registry). Mixed stays Redis-Streams reliable.
- A new `reliable.transport` config dimension. YAGNI; the activation is derived from `nats.registry` exactly.
- Tuning the JetStream stream beyond reasonable defaults (storage type / replicas / max-age) — power users
  override the bean.

## 2. Architecture & SPI reuse

The existing `ReliableBroker` SPI (`reliableBroadcast(uri, MessagePayload)` + lifecycle + subscription
callback) is transport-agnostic. Add `NatsJetStreamReliableBroker` implementing it. `ClusterMessageSender`
unchanged. Auto-config selects exactly one `ReliableBroker` bean based on the 5-tier deployment matrix
already established by RC10.

**Key parallel to `RedisStreamsReliableBroker`** — same envelope wrap/unwrap (HMAC + traceparent +
originNodeId + payload codec), same origin self-suppression, same in-process PEL dedup window, same
`inboundMaxBytes` UTF-8 byte cap (post-RC12 L3), same dead-node consumer cleanup with idle gate (post-RC11
fix #5).

## 3. Components

Three classes:

| File | Purpose |
|---|---|
| `netty-spring-websocket-cluster/.../cluster/nats/NatsJetStreamReliableBroker.java` | The `ReliableBroker` impl. Owns: per-URI `ensureStream(b64uri)` (idempotent `JetStreamManagement.addStream`); `publishAsync(subject, envelope)`; per-URI durable pull subscription thread (`nats-reliable-<uri>`) running `fetch(N, blockMs)` loop; `msg.ack()` on local-delivery success; lifecycle (`start`/`shutdown`); state (ACTIVE/DEGRADED/SHUTDOWN); transport listener wired off the existing JetStream connection. |
| (no new helper class needed) | JetStream connection acquisition reuses the existing all-NATS JetStream `Connection` bean from RC10 (`NatsKvSessionRegistry` et al. already share it). The reliable broker takes the same `Connection` via DI; it opens its own `JetStream` and `JetStreamManagement` views. |
| `NatsJetStreamReliableBrokerTest.java` (unit, Mockito) + `NatsJetStreamReliableIntegrationTest.java` (Testcontainers IT) | See §7. |

Auto-config: new `@Bean natsJetStreamReliableBroker` in `NettyWebSocketClusterConfigure` with
`@ConditionalOnExpression("${...reliable.enable:false} && ${...nats.registry:false}")` +
`@ConditionalOnMissingBean(ReliableBroker.class)`. Existing `redisStreamsReliableBroker` bean gains the
complementary `&& !${...nats.registry:false}` clause so exactly one wins.

## 4. Wire-level naming & retention

| Item | Pattern / value | Rationale |
|---|---|---|
| Stream name | `netty-cluster-reliable-<b64url(uri)>` | NATS-legal (base64url is alnum + `-` + `_`); one per URI |
| Subject | `netty.reliable.<b64url(uri)>` | One subject per stream |
| Storage type | `FILE` | Durable across NATS restarts; required for at-least-once |
| Retention policy | `LIMITS` | Drop-on-limit (mirrors Redis `MAXLEN ~`) |
| Discard policy | `OLD` | Drop oldest on overflow |
| Max messages | `reliable.stream-max-len` (default 10000) | Reuses existing key |
| Max age | `0` (unlimited) | Only MAXMSGS governs retention; matches Redis semantics |
| Replicas | `1` | Simplest default; cluster-NATS users override via custom bean |
| Durable consumer name | `g.<b64url(nodeId)>` | Mirrors Redis `g:{nodeId}`. NATS allows `.` in consumer names, NOT `:`. |
| Ack policy | `EXPLICIT` | At-least-once requires explicit ack on local-delivery success |
| Deliver policy | `ALL` | New durable starts at first available message; resume from cursor on reconnect (durable handles this) |
| Replay-on-resync | Automatic via durable cursor | Same semantics as Redis |
| Inbound size cap | Reuses `inboundMaxBytes` field (UTF-8 bytes) | Same field name, same semantics post-RC12 L3 |

## 5. Data flow

### Publish

```
clusterMessageSender.reliableBroadcast(uri, msg)
 → builds envelope (originNodeId, traceparent, payload codec'd, HMAC wrapped) — UNCHANGED
 → broker.reliableBroadcast(uri, envelopeBytes)
    ├ ensureStream(b64(uri))                         // idempotent (addStream-or-update)
    ├ JetStream.publishAsync("netty.reliable.<b64uri>", envelopeBytes)
    ├ on completion: increment reliablePublished counter (existing)
    └ on failure: on-publish-failure policy (log/drop) — UNCHANGED
```

### Consume

```
broker.subscribe(uri, listener)
 → lazy-create durable pull consumer "g.<b64nodeId>" on the URI's stream
 → spawn dedicated thread "nats-reliable-<uri>"
 → loop:
     fetch(reliable.poll-count, reliable.poll-block-ms)
     for each msg:
       envelopeBytes = msg.getData()
       if envelopeBytes.length > inboundMaxBytes: ack-and-drop (size guard, mirrors L3)
       envelope = unwrap(envelopeBytes)              // HMAC verify, codec decode
       if envelope.originNodeId == myNodeId: ack (origin self-suppress)
       elif dedupWindow.contains(envelope.id): ack
       else:
         dedupWindow.add(envelope.id)
         listener.onReliableMessage(uri, envelope)   // local fan-out, unchanged path
         msg.ack()                                   // ONLY on success
```

### Replay-on-resync

Node disconnects → JetStream durable cursor stays at last-acked seq → node reconnects → next
`fetch()` returns the unacked backlog (within stream retention window). Identical user-facing
semantics to RC2 Redis Streams replay.

### Dead-node consumer cleanup (with idle gate)

When `ClusterReaper` declares `deadNodeId` dead, the reliable broker's existing
`destroyConsumerGroupsForNode(deadNodeId)` hook is called (same SPI hook used by RC2/RC11). For NATS:

```
streams = JetStreamManagement.getStreamNames(prefix="netty-cluster-reliable-")
for each stream:
  try info = JetStreamManagement.getConsumerInfo(stream, "g.<b64deadNodeId>")
  catch JetStreamApiException(404): continue        // consumer doesn't exist — skip
  if info.numPending == 0 && (now - info.deliveredLastTimeMs) > groupDestroyIdleMs:
    JetStreamManagement.deleteConsumer(stream, "g.<b64deadNodeId>")
  else: keep                                        // retain to protect crash-restart replay
```

Same `groupDestroyIdleMs` knob as Redis (default 1h). Same retain-on-doubt behavior.

### Transport health (DEGRADED state)

Reuses NATS `Connection.addConnectionListener(ConnectionListener)` already wired by the RC9 NATS broker.
Reliable broker gets its own listener:
- `Events.DISCONNECTED` / `Events.CLOSED` → CAS `ACTIVE → DEGRADED`
- `Events.RECONNECTED` / `Events.CONNECTED` → CAS `DEGRADED → ACTIVE`
- Never overwrite `SHUTDOWN`. `/actuator/health` reflects truth.

## 6. Config & defaults

**Zero new config keys.** All knobs reuse the existing `server.netty.websocket.cluster.reliable.*` namespace:

| Key | NATS semantics |
|---|---|
| `reliable.enable` | Master switch (existing) |
| `reliable.stream-max-len` | JetStream stream `max_msgs` |
| `reliable.poll-block-ms` | `fetch()` timeout in ms |
| `reliable.poll-count` | `fetch()` batch size |
| `reliable.dedup-window` | In-process PEL dedup ring size (same as Redis) |
| `reliable.group-destroy-idle-ms` | Idle window before durable consumer deletion on heartbeat-expiry |

`additional-spring-configuration-metadata.json` descriptions get a parenthetical "(NATS: maps to JetStream
stream max_msgs / fetch timeout / fetch batch / dedup ring / durable consumer idle window)".

## 7. Tests

| Test | Coverage | Type |
|---|---|---|
| `NatsJetStreamReliableBrokerTest` (Mockito) | (1) `reliableBroadcast` publishes to expected subject `netty.reliable.<b64uri>`; (2) `subscribe` creates durable consumer named `g.<b64nodeId>`; (3) `ensureStream` is idempotent (calls `getStreamInfo` first, only `addStream` if not exists); (4) ack-on-success / no-ack-on-listener-throw; (5) origin self-suppression; (6) dedup window; (7) `inboundMaxBytes` UTF-8 byte guard. | unit |
| `NatsJetStreamReliableIntegrationTest` (Testcontainers `nats:2.10 -js`) | (a) publish → receive round-trip on a single node; (b) **replay-on-resync** (the headline): subscribe → publish 3 → unsubscribe → publish 2 while down → resubscribe → assert 5 received with 0 loss; (c) dead-node consumer cleanup with idle gate (mock `now`/sleep past idle threshold then verify deleted). | IT |
| `NettyWebSocketClusterConfigureTest` (+2 context cases) | (i) `nats.registry=true, reliable.enable=true` → `NatsJetStreamReliableBroker` selected, `RedisStreamsReliableBroker` absent; (ii) `nats.registry=true, reliable.enable=false` → no reliable bean (existing behavior). | context |

Existing `ReliableBroadcastIntegrationTest` (Redis) is untouched and remains the regression gate for the
Redis path.

## 8. Auto-config matrix entry

Extending the RC10 5-tier matrix with reliable-broker selection:

| Tier | `ReliableBroker` (when `reliable.enable=true`) |
|---|---|
| all-Redis standalone | `RedisStreamsReliableBroker` (gains `&& !nats.registry`) |
| all-Redis cluster | NOT supported (RC7 mutual exclusion, unchanged) |
| mixed standalone (NATS broker + Redis registry) | `RedisStreamsReliableBroker` (unchanged) |
| mixed cluster (NATS broker + Redis cluster) | NOT supported (unchanged) |
| **all-NATS** (`nats.registry=true`) | **`NatsJetStreamReliableBroker` (NEW)** |

Context test #i above asserts exactly-one selection in the all-NATS case.

## 9. Backward compatibility

- **Single-node mode (default):** zero impact.
- **All-Redis / mixed deployments:** byte-level identical.
- **All-NATS deployments with `reliable.enable=false`:** byte-level identical.
- **All-NATS deployments with `reliable.enable=true` (NEW capability):** previously documented as
  unsupported in `release-notes-1.9.0.md` Known Limitations ("**NATS / JetStream 可靠投递**"). RC13 lifts
  this restriction. Behavior matches RC2 Redis Streams reliable semantics.

No SPI signature change. No envelope/wire format change. No new config key.

## 10. Documentation updates

- `docs/release-notes-1.9.0.md`: new section §⑱ "NATS JetStream 可靠投递" (since RC13). Move
  "NATS / JetStream 可靠投递" from Known-Limitations to shipped.
- `docs/cluster-design.md`: ADR-001 status table — change the row for "all-NATS reliable" from "deferred"
  to "shipped RC13".
- `docs/api-guide.md` §9 (cluster): note the `reliable.*` keys apply transport-agnostically; one-line
  pointer to release notes for NATS-specific mapping.
- `additional-spring-configuration-metadata.json`: append the NATS-mapping note to each `reliable.*`
  description.

## 11. Risk register

| Risk | Mitigation |
|---|---|
| jnats `JetStreamSubscription.fetch` blocking semantics differ from Redis Lettuce `XREADGROUP BLOCK` | IT (b) is the oracle — proves replay-on-resync end-to-end with real `nats:2.10 -js`. Implementer must verify jnats 2.20.4 API against the IT; do not trust API recall. |
| `JetStreamManagement.addStream` parameter mismatch with jnats 2.20.4 (RC10 hit similar issue with `KeyValueConfiguration.maxAge` vs `ttl`) | Implementer reads `NatsKvSessionRegistry` to see how RC10 invokes `KeyValueConfiguration`; mirrors the same builder discovery for `StreamConfiguration`. IT catches errors. |
| dead-node consumer-info 404 (consumer never created on this URI) | Catch `JetStreamApiException` with code 10014 / status 404 and skip silently. |
| Eager stream creation on publish-only nodes | `ensureStream` is per-URI lazy; publishing to a URI creates its stream. No node-wide upfront work. |
| JetStream not enabled on the server (`nats-server` without `-js`) | Stream operations throw; the `nats.registry=true` precondition already requires JetStream, so this is a misconfiguration the existing NATS-KV beans would also fail on. Surface a clear error on first publish. |
| Concurrent `ensureStream` race | Make the check inside a per-URI `ConcurrentHashMap.computeIfAbsent` cache so we only call `getStreamInfo` once per process per URI. |

## 12. Implementation order (informs the plan)

1. `NatsJetStreamReliableBroker` skeleton: ctor, fields, ACTIVE state, `state()`, `shutdown()` lifecycle —
   compile + no-op tests.
2. `ensureStream(b64uri)` with the per-URI `ConcurrentHashMap` cache.
3. `reliableBroadcast(uri, bytes)` publish path + `reliablePublished` counter increment.
4. `subscribe(uri, listener)` with durable pull consumer + dedicated thread + fetch loop.
5. ack-on-success + inbound size guard + origin self-suppression + dedup window — match the Redis path.
6. Connection listener for DEGRADED/ACTIVE state CAS.
7. `destroyConsumerGroupsForNode(deadNodeId)` with idle-gate.
8. Auto-config: new bean + complementary condition on `redisStreamsReliableBroker`.
9. Unit + IT + context tests.
10. Docs + metadata.
11. Pom bump + release notes + finish.

---

## Spec self-review

- **Placeholder scan:** None. Every section has concrete values, naming, behavior.
- **Internal consistency:** §3 (components) ↔ §4 (wire-level) ↔ §5 (data flow) ↔ §6 (config) all use the
  same `g.<b64nodeId>` consumer name, `netty.reliable.<b64uri>` subject, `netty-cluster-reliable-<b64uri>`
  stream — checked.
- **Scope check:** Single feature, single broker class, single tier of the activation matrix. Plan-sized
  (will decompose into ~5 sequential implementer tasks since they share one source file).
- **Ambiguity check:**
  - "lazy-create durable pull consumer" — clarified in §5 (durable named `g.<b64nodeId>`, EXPLICIT ack,
    DeliverPolicy.ALL); no ambiguity.
  - "stream retention" — explicitly LIMITS + OLD discard + only `max_msgs` (max_age=0) in §4.
  - "dead-node cleanup" — explicit JetStreamApiException catch + idle-gate condition in §5.
- **Decomposition note:** Unlike RC12, this one's tasks share `NatsJetStreamReliableBroker.java` heavily,
  so parallel subagent dispatch is not appropriate here. The implementation plan will use a single
  sequential implementer track with TDD per skeleton step.
