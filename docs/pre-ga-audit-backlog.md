# Pre-GA Audit Backlog (deferred to 1.9.1)

> 1.9.0 GA 前做了一轮多代理审计（33 项原始发现 → 24 项经对抗式验证确认）。
> **3 个 HIGH + 9 个 MEDIUM + 3 个文档不一致已在 GA 前修复**（见 release-notes-1.9.0.md「预发布硬化」与对应提交）。
> 下面是确认为真、但优先级低（LOW/NIT）、**不影响 GA 质量**的项，集中推迟到 **1.9.1**。
> 每项都给出位置、问题、推迟理由、建议修法，便于后续直接落地。

This document tracks the LOW/NIT findings from the pre-GA audit that were intentionally deferred to 1.9.1.
None affects single-node mode (the production-grade default) or the GA's correctness/security posture; each is a
robustness or polish refinement on the opt-in cluster path. Items are listed by their audit id.

---

## L1 — Eager standalone Redis client/connection even when all Redis SPI beans are user-overridden
- **Where:** `NettyWebSocketClusterConfigure.nettyClusterRedisClient` / `nettyClusterRedisConnection`.
- **Issue:** Both beans are gated only on the transport SpEL (`STANDALONE_REDIS_REGISTRY` + `@ConditionalOnMissingBean` on the client/connection themselves), not on whether any Redis-backed SPI bean is actually created. A user who overrides *every* Redis SPI bean (registry/heartbeat/reaper/broker) still gets a Redis client that `connect()`s eagerly.
- **Why deferred:** Niche (requires a fully-custom-SPI deployment that still leaves `cluster-nodes`/`nats` unset); only cost is one idle connection.
- **Fix sketch:** Make the client/connection `@ConditionalOnMissingBean` on the actual Redis SPI beans they back, or only create them when the default `RedisSessionRegistry`/`RedisPubSubBroker` conditions hold.

## L2 — NATS KV `register()` is a two-write non-atomic op; a failed second put orphans the session key
- **Where:** `NatsKvSessionRegistry.register()`.
- **Issue:** Writes the `s.<b64uri>.<sid>` session key then the `n.<b64nodeId>.<b64uri>.<sid>` membership key. If the first succeeds and the second throws, the session key is left with no membership record, so `removeAllForNode` can't reap it.
- **Why deferred:** Same non-atomic trade-off already accepted for Redis-Cluster mode; the race needs a mid-pair failure under a UUID sessionId.
- **Fix sketch:** Write the membership key **first** (cleanup can then always find an over-set, never an under-set), or add a periodic reconciliation sweep cross-checking session keys against membership keys.

## L3 — Inbound size cap compares `String.length()` (UTF-16 chars) against a byte-named budget
- **Where:** `RedisPubSubBroker.onInboundMessage()` / `RedisClusterModePubSubBroker.onClusterMessage()` (field `inboundMaxBytes`).
- **Issue:** The guard rejects when `message.length() > inboundMaxBytes`, but `length()` is a UTF-16 char count while the knob is derived from a byte budget. For non-ASCII payloads under a custom codec the char/byte ratio diverges. (The NATS broker already guards on byte length.)
- **Why deferred:** The default codec is ASCII/Base64 (1 char ≈ 1 byte), so the default path is unaffected; the cap is a coarse DoS backstop, not an exact limit.
- **Fix sketch:** Measure UTF-8 bytes consistently with the NATS broker, or rename the knob to make the char semantics explicit. At minimum align the log message wording.

## L4 — Dead-node cleanup is fire-and-forget after the reaper claim is consumed
- **Where:** `ClusterNodeManager.doReconciliation()`.
- **Issue:** After winning the single-winner reaper claim, `sessionRegistry.removeAllForNode(deadNodeId)` returns a `CompletionStage` that is neither awaited nor failure-observed before `heartbeat.deregister(...)`. A failed cleanup isn't retried until the claim expires and the node is re-detected.
- **Why deferred:** Reconciliation re-detects and re-reaps on the next sweep; the window is bounded by the claim TTL.
- **Fix sketch:** Chain `deregister` on `removeAllForNode` success; on cleanup failure leave the node in the nodes set so a later sweep retries.

## L5 — NATS reaper integration test creates the reaping bucket with no `maxAge` (test gap)
- **Where:** `NatsKvIntegrationTest.up()` vs production `NettyWebSocketClusterConfigure.ensureBucket` (30s maxAge).
- **Issue:** The IT creates `netty-reaping` without `maxAge`, so it only proves first-claim-wins on a never-expiring key — it never exercises the production claim-expiry/re-claim path.
- **Why deferred:** Test-only; production code is correct (maxAge is set in auto-config).
- **Fix sketch:** Mirror production with a short `maxAge` in the IT and add a claim → wait-past-window → re-claim assertion.

## L6 — Redis-loss grace period defeats the S2 unicast short-circuit for up to grace-period ms
- **Where:** `ClusterMessageSender.sendMessage()` (state gate) × `ClusterNodeManager` grace period.
- **Issue:** The S2 hardening short-circuits remote sessions to "closed" without a registry lookup when the node is not ACTIVE. Since 1.9.0 the node debounces transport loss by `redis-loss-grace-period-ms`, so during the grace window the node is still ACTIVE and the unicast hot path can still take a (command-timeout-bounded) registry round-trip.
- **Why deferred:** Still bounded by `command-timeout-ms` (default 2s); only affects unicast during the grace window of an actual Redis outage.
- **Fix sketch:** Also short-circuit when the **broker** itself is degraded (gate the lookup on `broker.state() == ACTIVE` in addition to node state).

## L7 — `shutdown()` does not await scheduler termination
- **Where:** `ClusterNodeManager.shutdown()`.
- **Issue:** `heartbeatScheduler.shutdown()` / `reconScheduler.shutdown()` are non-interrupting and not followed by `awaitTermination`, so a `doHeartbeat()`/`doReconciliation()` task already running can still touch Redis after `deregister`.
- **Why deferred:** Worst case is a single late, harmless write right at shutdown; the bounded drain wait added in 1.9.0 (FIX D) narrows the window.
- **Fix sketch:** Cancel futures and shut the schedulers down **before** deregister, with a bounded `awaitTermination`, mirroring `CoalescingRegistryWriter.shutdown()`.

## L8 — Reliable broker `state()` never reports DEGRADED on Redis loss
- **Where:** `RedisStreamsReliableBroker.state()` (only writer is `shutdown()`).
- **Issue:** `state` is initialized ACTIVE and only ever set to SHUTDOWN, so when the underlying Redis connection drops the reliable broker still reports ACTIVE. (`RedisPubSubBroker` wires a `RedisConnectionStateListener` and flips to DEGRADED.)
- **Why deferred:** The reliable path is opt-in (`reliable.enable`); its consume loop already logs/retries on failure, so health is observable via logs/counters.
- **Fix sketch:** Wire the reliable broker's connections to Lettuce `RedisConnectionStateListener` and flip DEGRADED on disconnect / ACTIVE on reconnect, or derive state from consume-loop failures.

## N1 (NIT) — NATS KV `removeAllForNode` skips the session-key delete when the URI is the empty string
- **Where:** `NatsKvSessionRegistry.removeAllForNode()` (the `dot > 0` guard).
- **Issue:** For an empty mapping URI, `b64("")` is `""`, so the membership key is `n.<b64nodeId>..<sid>`; after stripping the prefix, `rest` = `.<sid>` and `rest.lastIndexOf('.') == 0`, which the `> 0` guard rejects — the session key isn't reconstructed/deleted.
- **Why deferred:** An empty `@MessageMapping` URI is degenerate; membership key is still cleaned, only the session key lingers until TTL/sweep.
- **Fix sketch:** Change the guard to `if (dot >= 0)` so an empty b64uri segment is handled (`substring(0,0)` → `""`, reconstructing `s..<sid>` correctly).

---

### Not deferred — fixed before GA (for reference)
- **HIGH:** NATS broker raw-exception escape; NATS heartbeat exception-swallow; reliable consumer-group destroy-on-restart data-loss.
- **MEDIUM:** Redis registry URI base64url-encode (cross-URI leak); NATS KV registry dedicated executor; nats-without-jnats fail-fast; NATS payload/max_payload doc; bounded node-lookup cache; RESYNC-can't-resurrect-LEFT; honored drain timeout; reliable inbound size cap; NATS URL redaction.
- **DOC:** known-limitations vs shipped features; "two new items" stale config reference; cluster-design fictional metric names.

### Refuted by adversarial verification (no action)
9 claims were refuted by the two-lens skeptic pass, including: reliable stream-trim MAXLEN "data loss" (documented at-most-once-beyond-retention); NoOp authenticator accepting unsigned (by-design when auth disabled); scheduler-field visibility; coalescing check-then-act reorder; first-subscription-starts-at-`$`.
