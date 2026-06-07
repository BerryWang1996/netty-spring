# Pre-GA Audit + RC12 / RC13 / RC14 / RC15 / RC16 Review Backlog (deferred to 1.9.1+)

> **1.9.0 GA 前做过一轮多代理审计**（33 项原始发现 → 24 项经对抗式验证确认）。
> **3 个 HIGH + 9 个 MEDIUM + 3 个文档不一致已在 GA 前的 RC11 修复**（见 release-notes-1.9.0.md §⑯）。
> **RC12 又落地了 8 个 LOW/NIT polish 项**（L2–L8 + N1；见 release-notes-1.9.0.md §⑰）。
> **RC14 又落地了 6 个 RC12/RC13 review nice-to-haves**（P1/P5/P6/Q5/Q6/Q7；见 release-notes-1.9.0.md §⑲；Q4 经复核为 reviewer false positive 不修复）。
> **RC15 又落地了 8 项测试覆盖加固**（Q1/Q2/Q3 + P2/P3/P4 + R1/R2；见 release-notes-1.9.0.md §⑳；RC15 实现发现 S1 转入 RC16）。
> **RC16 又落地了 L1 + S1**（见 release-notes-1.9.0.md §㉑）—— **1.9.x backlog 至此清空**。1.9.0 GA 可在 RC16 之上直接 cut。
>
> 下面没有仍未处理的项；本文件保留作为 1.9.x 周期的完整 backlog 历史档案。1.9.1+ 新发现可追加到这里。

This document is the **complete 1.9.x backlog historical archive**. As of RC16, **no open items remain**. Future
1.9.1+ findings can be appended below.

---

> ~~**L1** — fixed in RC16 (see "Fixed in RC16" reference below).~~

---

## RC12 review nice-to-haves (added 2026-06-06)

> ~~**P1** — fixed in RC14 (see "Fixed in RC14" reference below).~~

> ~~**P2** — fixed in RC15 (see "Fixed in RC15" reference below).~~

> ~~**P3** — fixed in RC15 (see "Fixed in RC15" reference below).~~

> ~~**P4** — fixed in RC15 (see "Fixed in RC15" reference below).~~

> ~~**P5** — fixed in RC14 (see "Fixed in RC14" reference below).~~

> ~~**P6** — fixed in RC14 (see "Fixed in RC14" reference below).~~

---

## RC13 review nice-to-haves (added 2026-06-06)

> ~~**Q1** — fixed in RC15 (see "Fixed in RC15" reference below).~~

> ~~**Q2** — fixed in RC15 (see "Fixed in RC15" reference below).~~

> ~~**Q3** — fixed in RC15 (see "Fixed in RC15" reference below).~~

> ~~**Q4** — refuted by RC14 brainstorm complementary review (reviewer false positive).~~ `DedupRing`'s
> `LinkedHashMap(cap*2, 0.75f, true)` uses `cap*2` as **table-capacity** (rehash-avoidance hint), not as
> an element threshold; `removeEldestEntry` returns `size() > cap` and evicts on every `put`, so the
> element count is strictly bounded by `cap`. Not shipped — comment already states the semantics correctly.

> ~~**Q5** — fixed in RC14 (see "Fixed in RC14" reference below).~~

> ~~**Q6** — fixed in RC14 (see "Fixed in RC14" reference below).~~

> ~~**Q7** — fixed in RC14 (see "Fixed in RC14" reference below).~~

---

## RC14 review nice-to-haves (added 2026-06-07)

> ~~**R1** — fixed in RC15 (see "Fixed in RC15" reference below).~~

> ~~**R2** — fixed in RC15 (see "Fixed in RC15" reference below).~~

---

## RC15 implementation discoveries (added 2026-06-07)

> ~~**S1** — fixed in RC16 (see "Fixed in RC16" reference below).~~

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

**Fixed in RC15 (test/IT coverage hardening, 8 items):**
- Q1 `NatsJetStreamReliableIntegrationTest` extends DEGRADED kill test with `container.start()` + 30 s ACTIVE poll, using `ServerSocket(0)` + `withCreateContainerCmdModifier` to bind a stable host port.
- Q2 new `reliableBroker_hmacRoundTripWithMatchingSecrets` IT — positive HMAC complement to RC13's reject-on-mismatch.
- Q3 new `reliableBroker_publishDoesNotThrowWhenDegraded` IT — `assertDoesNotThrow` during DEGRADED + eventual post-reconnect delivery on a fresh broker pair (workaround for S1 stream-cache staleness, see below).
- P2 `NatsKvIntegrationTest.reaper_claimExpires_thenReclaimSucceeds` annotated `@Tag("slow")` + class-header convention note.
- P3 `ReliableBroadcastIntegrationTest` L8 `degradedDeadline = +15_000` gets one inline comment explaining the 15 s budget (Docker kill + Lettuce inactive + listener-CAS).
- P4 same NATS-KV reaper IT: `Thread.sleep(12_000)` replaced with `Thread.sleep(11_000) + poll-until-success (max 4 s, 100 ms interval)`. Faster typical case (~11 s), more resilient to JetStream housekeeping jitter.
- R1 `ClusterMessageSender.topicMessage` DEGRADED-else log appends `broker state is {}` (additive — preserves `"node state is"` substring matching).
- R2 `ClusterMessageSender.closeSession` javadoc documents the false-on-DEGRADED ambiguity (caller cannot distinguish "no such session" from "transport degraded"; mirrors RC12 L6 `sendMessage` semantics).

**Fixed in RC16 (1.9.x backlog cleanup, 2 items — backlog now empty):**
- L1 new `OnAnyRedisSpiRequired` Spring `Condition` (in `cluster.support` package, `ConfigurationPhase.REGISTER_BEAN`) attached via `@Conditional` to `nettyClusterRedisClient` + `nettyClusterRedisConnection`. Skips `RedisClient` creation when user provides `@Bean` for all 4 Redis-backed SPI interfaces (`SessionRegistry` + `ClusterBroker` + `ClusterNodeHeartbeat` + `ClusterReaper`). Partial-override scenarios (1-3 SPI overridden) still create the Redis client — additive narrowing only, no widening, no risk of bean missing when expected.
- S1 `NatsJetStreamReliableBroker.ConnectionListener` now calls `streamCache.clear()` on `Events.RECONNECTED` / `Events.CONNECTED` (defensive — even if state already ACTIVE). Next publish re-invokes `ensureStream(...)` (idempotent + mismatch-detecting per RC13 §5.1). One extra `getStreamInfo` round-trip per URI per reconnect — negligible.

### Refuted by adversarial verification (no action)

9 claims were refuted by the two-lens skeptic pass during RC11, including: reliable stream-trim MAXLEN "data loss" (documented at-most-once-beyond-retention); NoOp authenticator accepting unsigned (by-design when auth disabled); scheduler-field visibility; coalescing check-then-act reorder; first-subscription-starts-at-`$`.

**Q4 added to this list by RC14 brainstorm:** `DedupRing` "capacity boundary" — reviewer conflated `LinkedHashMap`'s table-capacity hint (`cap*2`) with the element-count cap. The eviction predicate `size() > cap` runs on every `put`, so element count is strictly bounded by `cap`. Existing comment correctly says "fixed-capacity"; no change needed.
