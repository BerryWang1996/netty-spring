# Pre-GA Audit + RC12 Review Backlog (deferred to 1.9.1+)

> **1.9.0 GA 前做过一轮多代理审计**（33 项原始发现 → 24 项经对抗式验证确认）。
> **3 个 HIGH + 9 个 MEDIUM + 3 个文档不一致已在 GA 前的 RC11 修复**（见 release-notes-1.9.0.md §⑯）。
> **RC12 又落地了 8 个 LOW/NIT polish 项**（L2–L8 + N1；见 release-notes-1.9.0.md §⑰）。
> 下面是 **仍未处理** 的项，集中推迟到 **1.9.1**（或更晚）。
> 每项都给出位置、问题、推迟理由、建议修法，便于后续直接落地。

This document tracks the LOW/NIT/polish findings still deferred after RC11 + RC12. None affects single-node mode
(the production-grade default) or the GA's correctness/security posture. Items are listed by source (audit id or
RC12 review).

---

## L1 — Eager standalone Redis client/connection even when all Redis SPI beans are user-overridden

- **Where:** `NettyWebSocketClusterConfigure.nettyClusterRedisClient` / `nettyClusterRedisConnection`.
- **Issue:** Both beans are gated only on the transport SpEL (`STANDALONE_REDIS_REGISTRY` + `@ConditionalOnMissingBean`
  on the client/connection themselves), not on whether any Redis-backed SPI bean is actually created. A user who
  overrides *every* Redis SPI bean (registry/heartbeat/reaper/broker) still gets a Redis client that `connect()`s
  eagerly.
- **Why deferred (again, by RC12 skeptic verdict):** The audit's proposed "pragmatic fix" (gate on
  `@ConditionalOnMissingBean(SessionRegistry.class)` alone) was rejected as inadequate — partial-override scenarios
  (e.g. custom `ClusterNodeHeartbeat` + default `RedisSessionRegistry`) would still need the Redis client. A correct
  fix needs a custom Spring `Condition` class that checks if **any** of the 4 Redis-backed SPI interfaces is missing
  while STANDALONE_REDIS_REGISTRY is true. Non-trivial design — owns its own brainstorming cycle.
- **Why niche:** Requires a fully-custom-SPI deployment that still leaves `cluster-nodes` / `nats.*` unset; only cost
  is one idle Redis connection.
- **Fix sketch (revised):** New `OnAnyRedisSpiRequired` Condition class that checks at least one of
  `SessionRegistry` / `ClusterBroker` / `ClusterNodeHeartbeat` / `ClusterReaper` is missing while
  `STANDALONE_REDIS_REGISTRY` evaluates true; attach to `nettyClusterRedisClient` + `nettyClusterRedisConnection`.

---

## RC12 review nice-to-haves (added 2026-06-06)

### P1 — `closeSession()` / `topicMessage()` also gate on `broker.state() == ACTIVE`

- **Where:** `ClusterMessageSender.closeSession()` (~line 458) and `topicMessage()` (similar).
- **Issue:** Inconsistent with L6: during the redis-loss grace window, these paths still perform a bounded registry
  lookup the broker can't act on (wasted ≤ `command-timeout-ms` per call).
- **Why deferred:** Not a correctness issue — the lookup is bounded and the resulting publish/close fails cleanly;
  L6 already covered the unicast hot path. Consistency polish.
- **Fix sketch:** Mirror the L6 gate in `closeSession()` and `topicMessage()`; reuse the unit-test pattern from
  `sendMessageShortCircuitsRemoteWhenBrokerDegraded`.

### P2 — `@Tag("slow")` annotation for long-running ITs

- **Where:** `NatsKvIntegrationTest.reaper_claimExpires_thenReclaimSucceeds` (12 s sleep).
- **Issue:** No `@Tag("slow")` so CI can't optionally skip slow groups. Existing `Testcontainers` gate already
  filters docker-less runs, so the impact is small.
- **Fix sketch:** Add `@org.junit.jupiter.api.Tag("slow")` and document the convention in the test class header.

### P3 — Documentation for IT timing constants

- **Where:** `ReliableBroadcastIntegrationTest.degradedDeadline = +15000ms` (L8 IT).
- **Issue:** No inline comment explaining why 15 s (≈ typical Docker `killContainerCmd` + Lettuce channel-inactive
  + listener-CAS latency budget).
- **Fix sketch:** One-line comment.

### P4 — Investigate L5 12s sleep margin if flakes appear

- **Where:** `NatsKvIntegrationTest.reaper_claimExpires_thenReclaimSucceeds`.
- **Issue:** 12 s sleep with 2 s margin above the 10 s `ttl()`; on heavily loaded CI, JetStream housekeeping may
  exceed the margin.
- **Fix sketch:** If flakes are observed, bump to 15 s + raise `ttl()` to 12 s OR retry-and-assert pattern.

### P5 — Comment/import style polish

- **Where:** `NatsKvSessionRegistry.java:157-160` (verbose explanatory comment), `RedisBrokerInboundSizingTest`
  vs `RedisPubSubBroker` (one uses `import StandardCharsets`, the other inlines `java.nio.charset.StandardCharsets`).
- **Issue:** Trivial style inconsistencies.
- **Fix sketch:** Drive-by clean-up on next touch.

### P6 — `ClusterNodeManagerReliabilityTest.shutdownAwaitsSchedulerTerminationBeforeDeregister` timing

- **Where:** 2 s latch / 50 ms reconciliation interval (40-cycle margin).
- **Issue:** On extremely slow CI environments the latch could time out.
- **Fix sketch:** If flaking, bump the latch timeout to 5 s and/or shorten reconciliation interval.

---

## RC13 review nice-to-haves (added 2026-06-06)

### Q1 — IT for `NatsJetStreamReliableBroker` DEGRADED→ACTIVE recovery

- **Where:** `NatsJetStreamReliableIntegrationTest` (the kill-container test currently only proves ACTIVE→DEGRADED).
- **Issue:** ACTIVE←DEGRADED reverse transition only covered at unit level.
- **Fix sketch:** Extend the kill-container test to restart the container and poll `broker.state() == ACTIVE` within 15 s.

### Q2 — Positive HMAC round-trip IT

- **Where:** `NatsJetStreamReliableIntegrationTest.hmacRejection*` (currently only asserts wrong-key rejection).
- **Issue:** Positive case is implicit via ITs (a) and (b) but not asserted with matching `auth.secret`.
- **Fix sketch:** Add a sibling test that uses matching secrets and asserts the receiver gets the message.

### Q3 — IT for DEGRADED-state publish still attempts

- **Where:** Spec §5.1 says DEGRADED still attempts publish; only unit-level coverage today.
- **Fix sketch:** Add an IT that disconnects → confirms `reliablePublish` does not throw + JetStream eventually receives once reconnected.

### Q4 — `DedupRing` capacity boundary

- **Where:** `NatsJetStreamReliableBroker.DedupRing.removeEldestEntry()` ~line 535.
- **Issue:** `LinkedHashMap` with LF 0.75 + capacity doubling = ring grows to ~1.5× cap before eviction (semantically benign but the "fixed-capacity LRU" comment over-promises).
- **Fix sketch:** Either tighten the comment to "soft-capped (LRU eviction triggers at size > cap)" or use `LinkedHashMap(cap, 1.0f, true)` with a tighter rehash-aware eviction strategy.

### Q5 — Explicit stream-name length guard

- **Where:** `NatsJetStreamReliableBroker.ensureStream()` ~line 358.
- **Issue:** NATS rejects stream names > 256 bytes; an extremely long URI would fail at `jsm.getStreamInfo` with a less-friendly diagnostic.
- **Fix sketch:** Add a pre-check `if (streamName.length() > 256) throw new ClusterBrokerException("URI too long: ...")` for a clearer message.

### Q6 — Make connection-bean reuse explicit in spec §3

- **Where:** Spec `docs/superpowers/specs/2026-06-06-nats-jetstream-reliable-rc13.md` §3.
- **Issue:** Spec says "reuses the same Connection" but doesn't name the bean qualifier (`nettyClusterNatsKvConnection`).
- **Fix sketch:** Edit the spec to name the qualifier (doc only; no code impact).

### Q7 — Reconcile `g_` vs `g.` durable consumer prefix drift

- **Where:** Spec §4 table says `g.<b64url(nodeId)>`; code uses `g_<b64url(nodeId)>` because jnats client-validator rejects `.` in durable names.
- **Issue:** Documented in code comment + release-notes §⑱; the spec table itself wasn't retroactively updated.
- **Fix sketch:** Edit spec §4 table to match the code; the existing release-notes §⑱ paragraph is already correct.

---

### Not deferred — fixed before RC12 (for reference)

**Fixed in RC11 (pre-GA hardening, 15 items):**
- HIGH: NATS broker raw-exception escape; NATS heartbeat exception-swallow; reliable consumer-group destroy-on-restart data-loss.
- MEDIUM: Redis registry URI base64url-encode (cross-URI leak); NATS KV registry dedicated executor; nats-without-jnats fail-fast; NATS payload/max_payload doc; bounded node-lookup cache; RESYNC-can't-resurrect-LEFT; honored drain timeout; reliable inbound size cap; NATS URL redaction.
- DOC: known-limitations vs shipped features; "two new items" stale config reference; cluster-design fictional metric names.

**Fixed in RC12 (1.9.1 backlog polish, 8 items):**
- L2 NATS KV `register()` two-write order; L3 Redis broker UTF-8 byte sizing (3 brokers); L4 dead-node cleanup chained on `reconScheduler` with LEFT guard + retry-on-fail; L5 NATS reaper IT TTL + expiry/re-claim assertion; L6 `sendMessage` gates on `broker.state() == ACTIVE`; L7 `ClusterNodeManager.shutdown` awaits scheduler termination before deregister; L8 `RedisStreamsReliableBroker` wires `RedisConnectionStateListener` for DEGRADED/ACTIVE; N1 empty-URI guard in `removeAllForNode`.

### Refuted by adversarial verification (no action)

9 claims were refuted by the two-lens skeptic pass, including: reliable stream-trim MAXLEN "data loss" (documented at-most-once-beyond-retention); NoOp authenticator accepting unsigned (by-design when auth disabled); scheduler-field visibility; coalescing check-then-act reorder; first-subscription-starts-at-`$`.
