# Pre-GA Audit + RC12 / RC13 / RC14 Review Backlog (deferred to 1.9.1+)

> **1.9.0 GA 前做过一轮多代理审计**（33 项原始发现 → 24 项经对抗式验证确认）。
> **3 个 HIGH + 9 个 MEDIUM + 3 个文档不一致已在 GA 前的 RC11 修复**（见 release-notes-1.9.0.md §⑯）。
> **RC12 又落地了 8 个 LOW/NIT polish 项**（L2–L8 + N1；见 release-notes-1.9.0.md §⑰）。
> **RC14 又落地了 6 个 RC12/RC13 review nice-to-haves**（P1/P5/P6/Q5/Q6/Q7；见 release-notes-1.9.0.md §⑲；Q4 经复核为 reviewer false positive 不修复）。
> 下面是 **仍未处理** 的项，集中推迟到 **1.9.1**（或更晚）。
> 每项都给出位置、问题、推迟理由、建议修法，便于后续直接落地。

This document tracks the LOW/NIT/polish findings still deferred after RC11 + RC12 + RC14. None affects single-node mode
(the production-grade default) or the GA's correctness/security posture. Items are listed by source (audit id or
review round).

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

> ~~**P1** — fixed in RC14 (see "Fixed in RC14" reference below).~~

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

> ~~**P5** — fixed in RC14 (see "Fixed in RC14" reference below).~~

> ~~**P6** — fixed in RC14 (see "Fixed in RC14" reference below).~~

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

> ~~**Q4** — refuted by RC14 brainstorm complementary review (reviewer false positive).~~ `DedupRing`'s
> `LinkedHashMap(cap*2, 0.75f, true)` uses `cap*2` as **table-capacity** (rehash-avoidance hint), not as
> an element threshold; `removeEldestEntry` returns `size() > cap` and evicts on every `put`, so the
> element count is strictly bounded by `cap`. Not shipped — comment already states the semantics correctly.

> ~~**Q5** — fixed in RC14 (see "Fixed in RC14" reference below).~~

> ~~**Q6** — fixed in RC14 (see "Fixed in RC14" reference below).~~

> ~~**Q7** — fixed in RC14 (see "Fixed in RC14" reference below).~~

---

### Not deferred — fixed before RC12 (for reference)

**Fixed in RC11 (pre-GA hardening, 15 items):**
- HIGH: NATS broker raw-exception escape; NATS heartbeat exception-swallow; reliable consumer-group destroy-on-restart data-loss.
- MEDIUM: Redis registry URI base64url-encode (cross-URI leak); NATS KV registry dedicated executor; nats-without-jnats fail-fast; NATS payload/max_payload doc; bounded node-lookup cache; RESYNC-can't-resurrect-LEFT; honored drain timeout; reliable inbound size cap; NATS URL redaction.
- DOC: known-limitations vs shipped features; "two new items" stale config reference; cluster-design fictional metric names.

**Fixed in RC12 (1.9.1 backlog polish, 8 items):**
- L2 NATS KV `register()` two-write order; L3 Redis broker UTF-8 byte sizing (3 brokers); L4 dead-node cleanup chained on `reconScheduler` with LEFT guard + retry-on-fail; L5 NATS reaper IT TTL + expiry/re-claim assertion; L6 `sendMessage` gates on `broker.state() == ACTIVE`; L7 `ClusterNodeManager.shutdown` awaits scheduler termination before deregister; L8 `RedisStreamsReliableBroker` wires `RedisConnectionStateListener` for DEGRADED/ACTIVE; N1 empty-URI guard in `removeAllForNode`.

**Fixed in RC14 (RC12/RC13 review polish bundle, 6 items):**
- P1 `closeSession()` / `topicMessage()` also gate on `broker.state() == ACTIVE` (consistency with RC12 L6 `sendMessage()` — avoids one bounded registry lookup / one doomed publish in the redis-loss grace window).
- P5 NATS-KV `removeAllForNode` RC11 L2 comment condensed (4 lines → 2); `RedisPubSubBroker` / `RedisClusterModePubSubBroker` / `RedisStreamsReliableBroker` switched inline `java.nio.charset.StandardCharsets.UTF_8` to imported form (matches test code style).
- P6 `ClusterNodeManagerReliabilityTest.shutdownAwaitsSchedulerTerminationBeforeDeregister` reconciliation-await latch bumped 2 s → 5 s (40-cycle → 100-cycle margin at 50 ms interval).
- Q5 `NatsJetStreamReliableBroker.ensureStream()` pre-checks `streamName.length() > 255` and throws `ClusterBrokerException("Stream name too long: ...")` before any jsm round-trip (clearer than the jnats-side diagnostic).
- Q6 RC13 spec §3 names the `@Qualifier("nettyClusterNatsKvConnection")` bean explicitly (doc only).
- Q7 RC13 spec §4 table + §5 consume / dead-node cleanup pseudocode + §7 test description + self-review aligned to `g_<b64url(nodeId)>` (code uses `_` because the jnats client-side validator rejects `.` in durable names).

### Refuted by adversarial verification (no action)

9 claims were refuted by the two-lens skeptic pass during RC11, including: reliable stream-trim MAXLEN "data loss" (documented at-most-once-beyond-retention); NoOp authenticator accepting unsigned (by-design when auth disabled); scheduler-field visibility; coalescing check-then-act reorder; first-subscription-starts-at-`$`.

**Q4 added to this list by RC14 brainstorm:** `DedupRing` "capacity boundary" — reviewer conflated `LinkedHashMap`'s table-capacity hint (`cap*2`) with the element-count cap. The eviction predicate `size() > cap` runs on every `put`, so element count is strictly bounded by `cap`. Existing comment correctly says "fixed-capacity"; no change needed.
