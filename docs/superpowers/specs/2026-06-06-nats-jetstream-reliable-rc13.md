# NATS JetStream Reliable Broadcast — RC13 Design Spec

**Target:** netty-spring 1.9.0-RC13
**Branch:** `feature/1.9.0-rc13-nats-reliable`
**Status:** approved 2026-06-06 (brainstormed + multi-angle adversarial review v2)

---

## 1. Goal

Close the all-NATS reliability gap (currently documented in `release-notes-1.9.0.md` Known Limitations as
"NATS / JetStream 可靠投递…future work"). Provide **at-least-once reliable broadcast** on JetStream-enabled
NATS, parallel to RC2's `RedisStreamsReliableBroker`. **Activated only when BOTH `nats.registry=true`
(all-NATS stack) AND `reliable.enable=true` (opt-in) are set.** All other deployment tiers stay byte-level
identical.

**Non-goals:**
- Reliable broadcast in mixed deployments (NATS broker + Redis registry). Mixed stays Redis-Streams reliable.
- A new `reliable.transport` config dimension. YAGNI; activation is derived from `nats.registry` exactly.
- Tuning the JetStream stream beyond reasonable defaults (storage type / replicas / max-age) — power users
  override the bean.

## 2. Architecture & SPI reuse

The existing `ReliableBroker` SPI is transport-agnostic. Add `NatsJetStreamReliableBroker` implementing the
SPI exactly:

```java
public interface ReliableBroker {
    void reliablePublish(String uri, ClusterEnvelope envelope);
    ClusterSubscription reliableSubscribe(String uri, String nodeId, ClusterMessageListener listener);
    void destroyConsumerGroupsForNode(String nodeId);
    BrokerState state();
    void shutdown();
}
```

`ClusterMessageSender` is unchanged. Auto-config selects exactly one `ReliableBroker` bean based on the
RC10 5-tier deployment matrix (see §8).

**Shutdown contract.** `shutdown()` stops all consume loops, closes the JetStream views, and CAS-flips the
broker state to `SHUTDOWN`. **Unacked messages are intentionally left in the stream PEL** and replay on
next start via the durable consumer cursor. Mirrors the Redis Streams reliable broker contract.

**Key parallel to `RedisStreamsReliableBroker`** — same envelope wrap/unwrap (HMAC + traceparent +
originNodeId + payload codec), same origin self-suppression, same in-process PEL dedup window, same
`inboundMaxBytes` UTF-8 byte cap (post-RC12 L3), same dead-node consumer cleanup with idle gate (post-RC11
fix #5).

## 3. Components

Three classes:

| File | Purpose |
|---|---|
| `netty-spring-websocket-cluster/.../cluster/nats/NatsJetStreamReliableBroker.java` | The `ReliableBroker` impl. Owns: per-URI `ensureStream(b64uri)` (idempotent `JetStreamManagement.addStream` with mismatch detection); `publishAsync(subject, envelopeBytes)`; per-URI durable pull-subscription thread (`nats-reliable-<nodeIdShort>-<hex(uri.hashCode())>`) running `fetch(N, blockMs)` loop; `msg.ack()` on local-delivery success; lifecycle (`start`/`shutdown`); state (ACTIVE/DEGRADED/SHUTDOWN); transport listener wired off the existing JetStream connection. |
| (no new helper class needed) | JetStream connection acquisition reuses the existing all-NATS JetStream `Connection` bean from RC10 (`NatsKvSessionRegistry` et al. already share it) — specifically the bean qualified as `@Qualifier("nettyClusterNatsKvConnection")` established in RC10. The reliable broker takes the same `Connection` via DI; it opens its own `JetStream` and `JetStreamManagement` views. **Connection auth (user/credentials/TLS) inherits from RC9-vetted bean — no new auth path.** The broker uses one dedicated `OS` thread per subscribed URI (rationale: each fetch loop is blocking; per-URI threads simplify exception handling and isolation. Pooled-executor optimization is deferred.) |
| `NatsJetStreamReliableBrokerTest.java` (unit, Mockito) + `NatsJetStreamReliableIntegrationTest.java` (Testcontainers IT) | See §7. |

**Auto-config matrix wiring** (§8 has the full matrix). New `@Bean natsJetStreamReliableBroker` in
`NettyWebSocketClusterConfigure` with
`@ConditionalOnExpression("${...reliable.enable:false} && ${...nats.registry:false}")` +
`@ConditionalOnMissingBean(ReliableBroker.class)`. The **existing `redisStreamsReliableBroker` bean is
already suppressed in all-NATS mode** because its `STANDALONE_REDIS_REGISTRY` precondition is
`(nats.servers empty || !nats.registry)`; we verify this in the implementation rather than adding a
redundant clause. Both beans carry `@ConditionalOnMissingBean(ReliableBroker.class)`, so user-supplied
overrides still win.

## 4. Wire-level naming & retention

| Item | Pattern / value | Rationale |
|---|---|---|
| Stream name | `netty-cluster-reliable-<b64url(uri)>` | NATS-legal (base64url is alnum + `-` + `_`); one per URI |
| Subject | `netty.reliable.<b64url(uri)>` | One subject per stream |
| Storage type | `FILE` | Durable across NATS restarts; required for at-least-once. Per-publish disk I/O cost; users with non-durable use cases override the bean. |
| Retention policy | `LIMITS` | Drop-on-limit (mirrors Redis `MAXLEN ~`) |
| Discard policy | `OLD` | Drop oldest on overflow |
| Max messages | `reliable.stream-max-len` (default 10000) | Reuses existing key |
| Max age | `0` (unlimited) | Only MAXMSGS governs retention; matches Redis semantics |
| Replicas | `1` | Simplest default. **⚠️ Clustered NATS HA users MUST override** via custom bean to set replicas≥3, otherwise leader loss = backlog loss. |
| Durable consumer name | `g_<b64url(nodeId)>` | Mirrors Redis `g:{nodeId}`. NATS allows `.` and `_` in consumer names but NOT `:`. The jnats client-side validator rejects `.` in **durable** names (a `.` is parsed as a subject token), so the implementation uses `_` as the separator (RC13 implementer discovery; matches code at `NatsJetStreamReliableBroker.CONSUMER_PREFIX`). |
| Ack policy | `EXPLICIT` | At-least-once requires explicit ack on local-delivery success |
| Deliver policy | `ALL` | New durable starts at first available message; resume from cursor on reconnect (durable handles this) |
| Replay-on-resync | Automatic via durable cursor | Same semantics as Redis |
| Inbound size cap | Reuses `inboundMaxBytes` field (UTF-8 bytes) | Same field name, same semantics post-RC12 L3 |
| URI length | Implementer caps b64url(uri) ≤ 200 chars (NATS stream-name limit ~256) | Documented operational limit |

### 4.1 Payload sizing caveat (operator-facing)

JetStream shares the NATS server `max_payload` (default 1 MB). After envelope Base64 (~+37%) and optional
HMAC overhead, **the wire body may exceed `max_payload`**. Operators must either **lower
`message-max-size-bytes`** or **raise the server `max_payload`** when using NATS JetStream reliable.
Oversized messages are handled per `on-publish-failure` (`log` / `drop`); local delivery is unaffected.
This mirrors the RC9 NATS broker caveat already documented in release-notes §⑮.

## 5. Data flow

### Publish

```
clusterMessageSender.reliableBroadcast(uri, msg)
  → ClusterMessageSender builds ClusterEnvelope (originNodeId, traceparent, payload codec'd) — UNCHANGED
  → broker.reliablePublish(uri, envelope)
      ├ if state() == SHUTDOWN: throw ClusterBrokerException
      ├ envelopeBytes = authenticator.wrap(envelopeCodec.encode(envelope))   // HMAC wrap inside the broker
      ├ ensureStream(b64(uri))                                                // §5.1
      ├ JetStream.publishAsync("netty.reliable.<b64uri>", envelopeBytes)
      ├ on completion: stats.reliablePublished++                              // existing counter
      └ on failure (rejected by JetStream, max_payload exceeded, transient): on-publish-failure policy
                                                                              (`log` / `drop` / throw) — UNCHANGED
```

State semantics for `reliablePublish`:
- `state() == SHUTDOWN` → throw `ClusterBrokerException` (fail-fast).
- `state() == DEGRADED` → **still attempt publish** (informational only; matches Redis Streams reliable).
  Failures from the underlying JetStream call are handled per `on-publish-failure`.
- `state() == ACTIVE` → normal path.

### 5.1 ensureStream(b64uri) — idempotency + mismatch detection

```
return perUriCache.computeIfAbsent(b64uri, k -> {
    String streamName = "netty-cluster-reliable-" + b64uri;
    StreamConfiguration desired = buildExpectedConfig(streamName, b64uri);
    try {
        StreamInfo info = jsm.getStreamInfo(streamName);
        StreamConfiguration actual = info.getConfiguration();
        if (configMatches(actual, desired)) {
            return STREAM_MARKER;                                   // reuse, no-op
        }
        log.warn("Pre-existing stream {} has config mismatch: expected={}, actual={}",
                 streamName, desired, actual);
        throw new ClusterBrokerException("JetStream stream " + streamName + " already exists with " +
                                         "incompatible config (fail-fast; do not silently reuse)");
    } catch (JetStreamApiException e) {
        if (e.getApiErrorCode() == STREAM_NOT_FOUND) {
            jsm.addStream(desired);                                 // first-time create
            return STREAM_MARKER;
        }
        // Transient network / API failure — propagate so cache is NOT poisoned.
        throw new ClusterBrokerException("ensureStream failed for " + streamName, e);
    }
});
```

Notes:
- `configMatches` compares storage, max_msgs, discard, retention, max_age, replicas — not stream subjects
  (those are fixed by the name).
- On transient failure, the throw bubbles out of `computeIfAbsent`, so the next publish retries (no NULL
  cache poisoning).
- Concurrent first-publishes for the same URI race only inside `computeIfAbsent`, which serialises them;
  `jsm.addStream` is also idempotent server-side so duplicate creates are harmless.

### Consume

```
broker.reliableSubscribe(uri, nodeId, listener)        // SPI 3-arg signature
  → ensureStream(b64(uri))
  → lazy-create durable pull consumer "g_<b64url(nodeId)>" on the URI's stream  // '_' separator: §4
  → spawn dedicated thread "nats-reliable-<nodeIdShort>-<hex(uri.hashCode())>"
  → loop:
      List<Message> batch = consumer.fetch(reliable.poll-count, reliable.poll-block-ms)
      for each msg:
          envelopeBytes = msg.getData()
          // L3-style size guard (UTF-8 bytes; field name inboundMaxBytes)
          if envelopeBytes.length > inboundMaxBytes:
              log.warn(...); msg.ack(); continue                    // ACK-and-drop, don't infinite-redeliver
          envelope = envelopeCodec.decode(authenticator.unwrap(envelopeBytes))   // HMAC verify + decode
          if envelope == null: msg.ack(); continue                  // HMAC failed → drop, no redeliver
          if envelope.originNodeId.equals(myNodeId): msg.ack(); continue   // origin self-suppress
          if dedupWindow.contains(envelope.envelopeId): msg.ack(); continue
          dedupWindow.add(envelope.envelopeId)
          try {
              listener.onMessage(envelope)
              msg.ack()
          } catch (Throwable t) {
              // Poison-pill guard: log + ack anyway, matches RedisStreamsReliableBroker pattern.
              log.warn("Reliable listener threw on uri={} envId={} — ACKing to avoid livelock",
                       uri, envelope.envelopeId, t)
              msg.ack()
          }
```

**Handler contract.** Listeners MUST be idempotent. The in-process dedup window (`reliable.dedup-window`,
default 1024) covers only intra-process redelivery; across node restarts the window resets and unacked
messages replay via the durable consumer cursor. **Same at-least-once contract as Redis Streams reliable.**

**Poison-pill semantics.** A listener that throws → log WARN + `msg.ack()` to avoid livelock. JetStream
server-side `MaxDeliver` left at default; the in-process catch-and-ack is the authoritative guard. This
mirrors `RedisStreamsReliableBroker` lines 257–261.

### Replay-on-resync

Node disconnects → JetStream durable cursor stays at last-acked seq → node reconnects → next `fetch()`
returns the unacked backlog. Identical user-facing semantics to RC2 Redis Streams replay.

**Retention caveat (same as Redis):** At-least-once holds only within the stream retention window
(`reliable.stream-max-len`, default 10000, `DiscardPolicy.OLD`). A node offline long enough that its
unacked entries are trimmed by MAXMSGS will **permanently lose those messages** — bounded gap, matches
release-notes §⑥ Redis Streams documentation.

### Dead-node consumer cleanup (idle-gate, RC11-style)

When `ClusterReaper` declares `deadNodeId` dead, `destroyConsumerGroupsForNode(deadNodeId)` is invoked
(same SPI hook used by RC2/RC11). For NATS:

```
streamNames = jsm.getStreamNames(prefix="netty-cluster-reliable-")
String consumerName = "g_" + b64url(deadNodeId)   // '_' separator: §4 jnats validator constraint
for each streamName in streamNames:
    try {
        ConsumerInfo info = jsm.getConsumerInfo(streamName, consumerName)
        long idleMs = now() - info.getDelivered().lastActive().toEpochMilli()
        if info.getNumPending() == 0 && idleMs > reliable.group-destroy-idle-ms:
            jsm.deleteConsumer(streamName, consumerName)
            log.info("Reaped idle reliable consumer {} from {}", consumerName, streamName)
        else:
            log.debug("Keeping reliable consumer {} on {}: pending={}, idleMs={}",
                      consumerName, streamName, info.getNumPending(), idleMs)
    } catch (JetStreamApiException e) {
        if (e.getApiErrorCode() == CONSUMER_NOT_FOUND) continue    // already gone — fine
        log.warn("dead-node cleanup failed for {} on {}", consumerName, streamName, e)
    }
```

`info.getDelivered().lastActive()` is the JetStream-server-maintained `ConsumerInfo.delivered.lastActive`
(jnats 2.20.4) and is not client-settable, so it cannot be tampered with by a misbehaving node. Same
`group-destroy-idle-ms` knob as Redis (default 1h). Same retain-on-doubt behavior.

### Transport health (DEGRADED state)

Reuses NATS `Connection.addConnectionListener(ConnectionListener)` already wired by the RC9 NATS broker.
Reliable broker installs its own listener:
- `Events.DISCONNECTED` / `Events.CLOSED` → CAS `ACTIVE → DEGRADED`
- `Events.RECONNECTED` / `Events.CONNECTED` → CAS `DEGRADED → ACTIVE`
- Never overwrite `SHUTDOWN`.
- `/actuator/health` reflects truth via `BrokerState`.

## 6. Config & defaults

**Zero new config keys.** All knobs reuse the existing `server.netty.websocket.cluster.reliable.*` namespace:

| Key | NATS semantics | Redis semantics (unchanged) |
|---|---|---|
| `reliable.enable` | Master switch (existing) | Master switch |
| `reliable.stream-max-len` | JetStream stream `max_msgs` | Redis Stream `MAXLEN ~` |
| `reliable.poll-block-ms` | `JetStreamSubscription.fetch()` timeout (ms) | `XREADGROUP BLOCK` (ms) |
| `reliable.poll-count` | `fetch()` batch size | `XREADGROUP COUNT` |
| `reliable.dedup-window` | In-process dedup ring (envelope IDs) | Same |
| `reliable.group-destroy-idle-ms` | Idle window before deleting durable consumer on heartbeat-expiry | Same (idle on `XINFO GROUPS`) |

`additional-spring-configuration-metadata.json` descriptions get a parenthetical "(NATS: maps to JetStream
stream max_msgs / fetch timeout / fetch batch / dedup ring / durable consumer idle window)".

### 6.1 Activation summary (operator-facing)

JetStream reliable is selected when **AND ONLY WHEN** `reliable.enable=true` AND `nats.registry=true` are
both set. If `reliable.enable=true` but `nats.registry=false` (mixed or all-Redis), the
`RedisStreamsReliableBroker` is selected instead (this is by design — ADR-001).

Configuration errors are surfaced at context startup:
- `nats.registry=true` without `nats.servers`: existing all-NATS preconditions raise `IllegalStateException`.
- `nats.servers` set without `io.nats:jnats` on classpath: existing RC11 FIX E fail-fast guard raises
  `IllegalStateException`.
- `reliable.enable=true` on `nats.registry=true` but JetStream NOT enabled on the NATS server: surfaces on
  first publish as `ClusterBrokerException` wrapping the underlying `JetStreamApiException` (clear message
  with the URI and server URL).

## 7. Tests

| Test | Coverage | Type |
|---|---|---|
| `NatsJetStreamReliableBrokerTest` (Mockito) | (1) `reliablePublish` invokes `authenticator.wrap()` and publishes to `netty.reliable.<b64uri>`; (2) `reliableSubscribe(uri, nodeId, listener)` creates durable consumer `g_<b64url(nodeId)>` (see §4 — `_` not `.`, jnats validator); (3) `ensureStream` idempotent (calls `getStreamInfo` first; only `addStream` on STREAM_NOT_FOUND); (4) **ensureStream mismatch → `ClusterBrokerException`** (pre-existing stream with different `max_msgs`); (5) **poison-pill ack** (listener throws → `msg.ack()` still invoked, loop continues); (6) origin self-suppression; (7) dedup window; (8) `inboundMaxBytes` UTF-8 byte guard ACKs the message (does not nak). | unit |
| `NatsJetStreamReliableIntegrationTest` (Testcontainers `nats:2.10 -js`) | (a) publish → receive round-trip on a single node; (b) **replay-on-resync (headline)**: subscribe → publish 3 → close subscription → publish 2 while down → re-`reliableSubscribe(uri, sameNodeId, listener)` → assert 5 received, 0 loss; (c) **dead-node consumer cleanup with idle gate**: r1 subscribes + acks all → simulate r1 dead via `destroyConsumerGroupsForNode("r1")` immediately → assert consumer retained (idle < threshold); set system-time forward (via a small `groupDestroyIdleMs` of 2s in the test) + wait → re-invoke `destroyConsumerGroupsForNode` → assert consumer deleted; (d) **DEGRADED state IT** (mirrors RC12 L8): subscribe → `nats:2.10 -js` container `kill` → poll `broker.state() == DEGRADED` within 10 s → restart → poll back to ACTIVE within 15 s; (e) **HMAC rejection IT**: configure two brokers with mismatched `auth.secret`, publisher with key A subscribes with key B, asserts received count remains 0; publisher with key A subscribes with key A asserts received. | IT |
| `NettyWebSocketClusterConfigureTest` (+2 context cases) | (i) `nats.registry=true, reliable.enable=true` → `NatsJetStreamReliableBroker` bean present, `RedisStreamsReliableBroker` bean absent; (ii) `nats.registry=true, reliable.enable=false` → no `ReliableBroker` bean (existing all-NATS behavior preserved); (iii) `nats.servers=...,nats.registry=false (mixed), reliable.enable=true` → `RedisStreamsReliableBroker` selected (regression for mixed path); (iv) `nats.registry=true, reliable.enable=true` but `io.nats:jnats` absent on classpath → RC11 FIX E guard fires (no silent broken-bean state). | context |

Existing `ReliableBroadcastIntegrationTest` (Redis) is untouched and remains the regression gate for the
Redis path.

## 8. Auto-config matrix entry

Extending the RC10 5-tier matrix with reliable-broker selection:

| Tier | `ReliableBroker` (when `reliable.enable=true`) |
|---|---|
| all-Redis standalone | `RedisStreamsReliableBroker` (unchanged) |
| all-Redis cluster | NOT supported (RC7 mutual exclusion, unchanged) |
| mixed standalone (NATS broker + Redis registry) | `RedisStreamsReliableBroker` (unchanged) |
| mixed cluster (NATS broker + Redis cluster) | NOT supported (unchanged) |
| **all-NATS** (`nats.registry=true`) | **`NatsJetStreamReliableBroker` (NEW)** |

The existing `redisStreamsReliableBroker` bean's precondition is `STANDALONE_REDIS_REGISTRY` SpEL, which
already evaluates to false when `nats.registry=true` (verified in implementation; no SpEL change required).
Both beans carry `@ConditionalOnMissingBean(ReliableBroker.class)`, so user-supplied
`@Bean ReliableBroker` overrides win across **every** tier. Context test #i asserts exactly-one selection
in the all-NATS case; context test #iii asserts the mixed case stays on Redis.

## 9. Backward compatibility

- **Single-node mode (default):** zero impact.
- **All-Redis / mixed deployments:** byte-level identical (auto-config beans unchanged in effect).
- **All-NATS deployments with `reliable.enable=false`:** byte-level identical.
- **All-NATS deployments with `reliable.enable=true` (NEW capability):** previously documented in
  `release-notes-1.9.0.md` Known Limitations ("**NATS / JetStream 可靠投递**…future work"). RC13 lifts this
  restriction. Behavior matches RC2 Redis Streams reliable semantics with the §4.1 max_payload caveat.
- **User-supplied custom `@Bean ReliableBroker` overrides** (from RC10–RC12 workarounds): still win via
  `@ConditionalOnMissingBean(ReliableBroker.class)`. **No upgrade action required.**

### RC12 → RC13 upgrade journey

Users running `nats.registry=true && reliable.enable=false` in RC12 (the previously unsupported state) can
safely upgrade to RC13 with `reliable.enable=true`:
- **No prior message state exists** (reliable was not active in RC12 all-NATS) → no data-loss risk.
- **New operational constraint:** the §4.1 max_payload caveat — audit `message-max-size-bytes` against
  NATS server `max_payload`.
- **No SPI / wire format change.** ClusterMessageSender API and envelope bytes are identical.

## 10. Documentation updates

1. **`docs/release-notes-1.9.0.md`** — concrete edits:
   - **Add §⑱ "NATS JetStream 可靠投递 / NATS JetStream Reliable Broadcast"** mirroring §⑥ (Redis reliable)
     bilingual structure: Chinese intro paragraph with bracketed English terms + English subsection.
     Reference §4.1 max_payload caveat. Reference §9 RC12→RC13 upgrade journey.
   - **Remove "NATS / JetStream 可靠投递" entry from §Known Limitations** (currently near release-notes line 514
     of RC12 release).
   - **Add upgrade-guide bullet:** users with custom `@Bean ReliableBroker` from RC10–RC12 workarounds keep
     precedence via `@ConditionalOnMissingBean` — no action required.
   - **Add ⚠️ note in §⑱:** clustered-NATS HA deployments — replicas=1 default; override the bean to set
     replicas≥3 for HA.
   - **Add ⚠️ note in §⑱:** FILE storage incurs per-publish disk I/O on the NATS server; MEMORY is
     available via bean override for non-durable use cases.
   - **Test count** updated to the final reactor number (410 + RC13 deltas).

2. **`docs/cluster-design.md`** — ADR-001 implementation-range table:
   - Update the all-NATS reliable row from ⏳/deferred to **✅ 1.9.0 RC13**.

3. **`docs/api-guide.md` §9 (cluster):**
   - Add a one-line pointer under `reliable.*` config: "applies transport-agnostically; see release-notes
     §⑱ for NATS-specific JetStream mapping and the max_payload caveat."

4. **`additional-spring-configuration-metadata.json`:**
   - Append a parenthetical NATS mapping to each `reliable.*` description (e.g.,
     `stream-max-len` → "(NATS: JetStream stream max_msgs)";
     `poll-block-ms` → "(NATS: JetStream fetch block timeout)";
     `group-destroy-idle-ms` → "(NATS: idle-gate on ConsumerInfo.delivered.lastActive)").

5. **`docs/cluster-design.md` §Capacity** (operator note):
   - One stream per URI; ~N consumers per URI for N nodes; recommend ≤1000 streams per NATS cluster.
   - Per-publish disk I/O cost (FILE storage); replicas=1 default → leader-loss = backlog-loss in clustered
     NATS without override.

6. **`docs/cluster-design.md` §Security** (operator note):
   - Reliable broker inherits NATS TLS/credentials from the same `Connection` as the RC9 NATS broker via
     `warnIfInsecureNats`; no new auth path.
   - Threat model: PUBLISH-ACL hijack — same Redis-Streams mitigation pattern (restrict NATS PUBLISH ACL
     to authoritative app nodes; monitor stream stats for anomalous publish rates).

## 11. Risk register

| Risk | Mitigation |
|---|---|
| jnats `JetStreamSubscription.fetch` API differs from Redis Lettuce `XREADGROUP BLOCK` | IT (b) replay-on-resync is the oracle — proves end-to-end with real `nats:2.10 -js`. Implementer must verify jnats 2.20.4 API against the IT; do not trust API recall. |
| `JetStreamManagement.addStream` parameter mismatch with jnats 2.20.4 (RC10 hit similar issue with `KeyValueConfiguration.maxAge` vs `ttl`) | Implementer reads `NatsKvSessionRegistry` to see how RC10 invokes `KeyValueConfiguration`; mirrors the same builder discovery for `StreamConfiguration`. IT catches errors. |
| dead-node consumer-info 404 (consumer never created on this URI) | Catch `JetStreamApiException` `CONSUMER_NOT_FOUND` and continue silently. |
| Eager stream creation on publish-only nodes | `ensureStream` is per-URI lazy via `ConcurrentHashMap.computeIfAbsent`. No node-wide upfront work. |
| JetStream not enabled on the server (`nats-server` without `-js`) | Stream operations throw on first publish; the `nats.registry=true` precondition already requires JetStream, so this is misconfiguration the existing NATS-KV beans would also fail on. Surface a clear `ClusterBrokerException` with server URL on first publish. |
| Concurrent `ensureStream` race | Per-URI `ConcurrentHashMap.computeIfAbsent` serialises first-publishes; transient errors propagate out (no NULL poisoning), so next publish retries. `addStream` is server-idempotent. |
| Pre-existing user-created stream with same name but different config | `configMatches` detects mismatch → `ClusterBrokerException` with both expected and actual config logged. **No silent reuse.** |
| JetStream metadata corruption (rare) | Operator-handled: delete-and-recreate the stream (bounded backlog loss). Detailed tooling deferred to 2.0.0. |
| Poison-pill listener (perpetual throw) | In-process catch-and-ack pattern (§5 Consume) prevents livelock. Server-side `MaxDeliver` left at default; the in-process guard is authoritative. |
| Stream/consumer count at scale (100s of URIs × N nodes) | Documented operator-facing in `cluster-design.md §Capacity`. JetStream supports thousands of streams per server; recommend ≤1000 per NATS cluster as a soft ceiling. |
| FILE storage disk I/O cost | Documented operator-facing in release-notes §⑱; MEMORY storage available via bean override. |
| `replicas=1` default fails over in clustered NATS | Documented ⚠️ in release-notes §⑱; HA users override the bean. |

## 12. Implementation order (informs the plan)

1. **Skeleton + lifecycle:** `NatsJetStreamReliableBroker` ctor, fields, `state()`, `shutdown()` lifecycle —
   compile + no-op tests pass.
2. **ensureStream(b64uri):** per-URI `ConcurrentHashMap` cache + mismatch detection + ConsumerBrokerException
   propagation — unit test (3) + (4).
3. **`reliablePublish(uri, envelope)`:** HMAC wrap + `JetStream.publishAsync` + `reliablePublished`
   counter — unit test (1).
4. **`reliableSubscribe(uri, nodeId, listener)`:** durable consumer + dedicated thread + fetch loop —
   unit test (2).
5. **ack-on-success + inbound size guard + origin self-suppression + dedup window + poison-pill ack** —
   unit tests (5)(6)(7)(8).
6. **Connection listener** for DEGRADED/ACTIVE state CAS.
7. **`destroyConsumerGroupsForNode(deadNodeId)`** with idle-gate.
8. **Auto-config bean** in `NettyWebSocketClusterConfigure` + complementary condition verification +
   context tests (i)(ii)(iii)(iv).
9. **Integration tests (a)(b)(c)(d)(e).**
10. **Docs + metadata** (§10 #1-#6).
11. **Pom bump + release notes + finish branch.**

---

## Spec self-review

- **Placeholder scan:** None. All sections have concrete values, naming, behavior, code/pseudocode.
- **Internal consistency:** §3 (components) ↔ §4 (wire-level) ↔ §5 (data flow) ↔ §6 (config) ↔ §8 (matrix)
  all use the same `g_<b64url(nodeId)>` consumer name, `netty.reliable.<b64url(uri)>` subject,
  `netty-cluster-reliable-<b64url(uri)>` stream — checked. SPI signatures (`reliablePublish` /
  `reliableSubscribe(uri, nodeId, listener)` / `destroyConsumerGroupsForNode`) match the actual
  `ReliableBroker.java` (verified against repo).
- **Scope check:** Single feature, single broker class, single tier of the activation matrix. Plan-sized;
  will decompose into ~5 sequential implementer tasks (skeleton → ensureStream → publish → subscribe →
  ack/dedup/size-guard → listener → dead-node cleanup → auto-config + tests + docs) since they all share
  one source file. Parallel subagent dispatch is NOT appropriate here.
- **Ambiguity check:**
  - Activation: §1 + §6.1 + §8 all state the two-key (`nats.registry=true && reliable.enable=true`)
    requirement explicitly.
  - HMAC wrap: §5 publish pseudocode + §2 SPI-reuse note + §7 test (1) all assert it's inside the broker.
  - State semantics: §5 spells out SHUTDOWN-throws / DEGRADED-best-effort / ACTIVE-normal.
  - ensureStream mismatch: §5.1 explicitly throws `ClusterBrokerException` on config mismatch — no silent
    reuse.
  - Replay window: §5 (replay-on-resync) cites retention caveat.
  - Idle-gate: §5 (dead-node cleanup) pseudocode is concrete.
  - Handler contract: §5 (consume) + poison-pill semantics — both spelled out.
  - Compat with custom `@Bean ReliableBroker`: §9 explicit.
- **Decomposition note:** Tasks share one source file → single sequential implementer track with TDD per
  skeleton step. Plan uses subagent-driven-development with the single implementer + spec-reviewer +
  code-quality-reviewer pattern per step (not file-disjoint parallelism).
