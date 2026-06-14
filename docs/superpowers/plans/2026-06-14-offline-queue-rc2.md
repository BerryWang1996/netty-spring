# Offline Queue RC2 — Implementation Plan

> Single-track sequential (SPIs + Redis impls + hook/sender interdependent). subagent-driven-development, TDD.
> Spec: `docs/superpowers/specs/2026-06-14-offline-queue-rc2.md` (design-review fixes folded in).

**Goal:** Ship user-addressable offline delivery as 1.10.0-RC2 on Boot 2.7 + Lettuce 6.1.
**Branch:** `feature/1.10.0-offline-queue` (off RC1 master).

---

## Task 1: SPIs + HandshakeUserIdResolver (with SECURITY contract)
- [ ] Create `spi/UserIdResolver.java` with the **SECURITY CONTRACT javadoc** verbatim from spec §3/§11
  (userId MUST be from authenticated principal; WRONG/RIGHT examples).
- [ ] Create `spi/UserRegistry.java` + `spi/SessionRef.java` (value type: nodeId, uri, sessionId). Javadoc:
  derived index, NOT cached `sessionsForUser`, RC3 extends.
- [ ] Create `spi/OfflineQueueStore.java` + `spi/StoredMessage.java` (id + ClusterEnvelope). Javadoc: drain is
  exclusive per userId.
- [ ] Create `room/HandshakeUserIdResolver.java` (default): parse `user-id-source` (`query:<name>` /
  `header:<name>`), read from `MessageSession` handshake, null when absent. Javadoc: **convenience/testing
  only, NOT for production**.
- [ ] `HandshakeUserIdResolverTest` + `HandshakeUserIdResolverSecurityTest` (asserts the SPI javadoc contract
  text is present / the default is documented testing-only). Compile + test.
- [ ] Commit: `feat(cluster): offline-queue SPIs (UserIdResolver+SECURITY, UserRegistry, OfflineQueueStore) + Handshake resolver (RC2 T1)`

## Task 2: RedisUserRegistry (no-cache, atomic)
- [ ] Read `RedisSessionRegistry` (base64url keys, executor, atomic Lua, hash-tag for cluster).
- [ ] `room/RedisUserRegistry.java`: `netty:user:{b64userId}` Set of `nodeId|b64uri|sessionId`; atomic
  bind/unbind; `sessionsForUser` = **direct SMEMBERS every call (no cache)**; `isUserOnline` = SCARD>0;
  `removeAllForNode` = SCAN + pipelined member prune (cluster-aware). Dedicated executor.
- [ ] `RedisUserRegistryTest` (Mockito): bind/unbind/sessionsForUser/isUserOnline; multi-device (2 sessions
  one user); removeAllForNode prunes only that node's members.
- [ ] Commit: `feat(cluster/redis): RedisUserRegistry — userId→sessions index, no-cache lookups (RC2 T2)`

## Task 3: RedisOfflineQueueStore (stream + drain lock)
- [ ] `room/RedisOfflineQueueStore.java`: per-user Redis Stream `netty:offline:{b64userId}`.
  - `enqueue`: `XADD` with `MAXLEN ~ max-messages-per-user`; envelope encoded via EnvelopeCodec (HMAC-wrap
    consistent with reliable path — confirm with how RedisStreamsReliableBroker wraps).
  - `drain`: `SET netty:offline-lock:{b64userId} {node} NX PX drain-lock-ms` → if not acquired return empty;
    else `XRANGE - +` (all up to tail), build `StoredMessage` list (id = stream entry id), lazy-drop entries
    older than `ttl-seconds`.
  - `delete(ids)`: `XDEL` the delivered ids, then `DEL` the lock.
  - `removeAllForUser`: `DEL` stream + lock.
  - Dedicated executor.
- [ ] `RedisOfflineQueueStoreTest` (Mockito) + grounding: enqueue/drain(FIFO)/delete; lock acquire/skip;
  MAXLEN trim drops oldest; TTL age drop. (IT lives in T9.)
- [ ] Commit: `feat(cluster/redis): RedisOfflineQueueStore — per-user stream + drain lock + retention (RC2 T3)`

## Task 4: InMemory stubs
- [ ] `room/InMemoryUserRegistry.java` + `room/InMemoryOfflineQueueStore.java` (test scope), mirror
  `InMemorySessionRegistry`. Include a working drain-lock (per-userId in-process `ReentrantLock`/Set).
- [ ] Unit tests. Commit: `test(cluster): InMemory UserRegistry + OfflineQueueStore stubs (RC2 T4)`

## Task 5: ClusterMessageSender — sendToUser + UserOperations
- [ ] Add `UserOperations` sub-interface: `sendToUser(userId, message)` + `isUserOnline(userId)` (mirrors
  `RoomOperations` having multiple methods).
- [ ] Implement `sendToUser` per spec §5: `sessionsForUser` (fresh, no cache) → if online unicast each
  (reuse existing unicast path); local `MessageSessionClosedException` → fallback enqueue; if empty → enqueue;
  enqueue-failure → `fallbackEnqueueFailures++` + ERROR log. Stats: `sendToUser.realtime`/`.queued`,
  `unicast_failures`. Wire nullable `userRegistry`/`offlineStore` (null when disabled); shutdown integration.
- [ ] `ClusterSendToUserTest` (InMemory): online→realtime; offline→enqueued; local-close→fallback enqueue.
- [ ] Commit: `feat(cluster): sendToUser + UserOperations + fallback enqueue (RC2 T5)`

## Task 6: ClusterSessionHookImpl — resolve + bind + drain-on-connect
- [ ] `onSessionRegistered`: guard `if (offline.enable)` → `userId = resolver.resolve(session)` → if non-null:
  `register(uri, sid, node, {userId})` (was emptyMap) + `userRegistry.bindUser(...).thenRun(drain)`. Drain:
  `offlineStore.drain(userId)` → deliver each to the new session with `X-Offline-Message-Id` metadata (FIFO)
  → `delete(deliveredIds)`. `onSessionRemoved`: resolve + `unbindUser` (FullHttpRequest is safe at removal —
  add the javadoc lifecycle guarantee to `ClusterSessionHook`).
- [ ] `OfflineDrainOnConnectTest`. Commit: `feat(cluster): hook resolves userId + bind + drain-on-connect (RC2 T6)`

## Task 7: Config + metrics + metadata
- [ ] `ClusterProperties.Offline`: enable(false), userIdSource("query:userId"), maxMessagesPerUser(1000),
  ttlSeconds(604800), drainBatchSize(100), drainLockMs(5000).
- [ ] `NettyClusterMeterBinder` + `ClusterRuntimeStats`: the §7 meters (enqueued/drained/dropped_retention/
  realtime/queued/unicast_failures/fallback_enqueue_failures/resolved_identities/unresolved_sessions/
  users.online).
- [ ] metadata json entries. Commit: `feat(cluster): offline.* config + netty.cluster.offline.* meters (RC2 T7)`

## Task 8: Auto-config gated beans
- [ ] Gated `userIdResolver` (`@ConditionalOnProperty offline.enable=true` + `@ConditionalOnMissingBean` →
  `HandshakeUserIdResolver`), `userRegistry`→`RedisUserRegistry`, `offlineQueueStore`→`RedisOfflineQueueStore`.
  Wire into sender + hook. Extend `OnAnyRedisSpiRequired` so offline.enable keeps the Redis connection.
- [ ] Context tests: enable=true → beans present + wired; enable=false → absent + hook emptyMap (the 3 paths).
- [ ] Commit: `feat(cluster/autoconfig): gated offline beans + 3-path context test (RC2 T8)`

## Task 9: Integration tests + E2E
- [ ] `RedisUserRegistryIntegrationTest` + `RedisOfflineQueueStoreIntegrationTest` (Testcontainers): full
  round-trips, MAXLEN/TTL trim, lock acquire/skip.
- [ ] `OfflineDeliveryE2ETest` (two-node real Redis): **headline** offline→backfill-FIFO + **bind→drain-window
  case** (enqueue during window → delivered once).
- [ ] `MultiDeviceDrainLockTest` (real Redis): two concurrent reconnects → each message delivered once (lock).
- [ ] `SendToUserRaceTest`: just-disconnected (no cache) → enqueue not unicast-to-stale.
- [ ] Commit: `test(cluster): offline queue ITs + two-node backfill E2E + multi-device drain-lock + race (RC2 T9)`

## Task 10: Docs
- [ ] `docs/release-notes-1.10.0.md` RC2 section: honest positioning (spec §6 verbatim — at-least-once within
  retention, per-USER not per-device, send-time-only boundary, identity-required), the **SECURITY** call-out
  (default resolver testing-only), config + metrics, design-review record. Bilingual.
- [ ] `cluster-design.md`: offline-queue subsection + §Security identity note. `api-guide.md` §9: `sendToUser` +
  `offline.*` + the **production-identity-validation warning**.
- [ ] Verify UTF-8, no U+FFFD. Commit: `docs(cluster): RC2 offline queue — honest semantics + SECURITY identity (RC2 T10)`

---
**Controller (not the implementer):** Task 11 = pom bump 1.10.0-RC1→RC2, full reactor, release-notes test count,
tag v1.10.0-RC1... no — `v1.10.0-RC2`, FF-merge.

## Self-Review
Every spec section maps to a task; the 3 must-fixes are T1 (security javadoc), T3+T9 (drain lock + its test),
T2+T5 (no-cache + race test). Honest docs (T10) carry the send-time boundary + security loudly. Single-track
sequential; Redis-only; NATS deferred. Right-sized (~10 tasks, comparable to RC1).
