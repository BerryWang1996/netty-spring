# ClusterRoomRegistry RC1 — Implementation Plan

> Single-track sequential build (envelope + SPI + registry + sender are interdependent). Use
> subagent-driven-development. TDD per task. Spec:
> `docs/superpowers/specs/2026-06-08-room-registry-rc1.md`.

**Goal:** Ship per-room node-targeted routing as 1.10.0-RC1 on the current Boot 2.7 + Lettuce 6.1 stack.

**Branch:** `feature/1.10.0-room-registry` (already created off 1.9.0 GA master).

**Module:** all under `netty-spring-websocket-cluster` + `netty-websocket-cluster-spring-boot-starter`.

---

## File Structure

| Path | Action |
|---|---|
| `…/cluster/spi/ClusterEnvelope.java` | Modify — add `room` field + `ROOM_BROADCAST` kind; co-bump `CURRENT_VERSION` 1→2. |
| `…/cluster/codec/SimpleTextEnvelopeCodec.java` | Modify — encode/decode `room`, co-bump FIELD_COUNT, version-aware. |
| `…/cluster/spi/ClusterRoomRegistry.java` | **Create** — the SPI (see spec §3). |
| `…/cluster/room/RedisRoomRegistry.java` | **Create** — Redis impl, atomic Lua, local index. |
| `…/cluster/room/InMemoryRoomRegistry.java` | **Create** (test scope) — no-Lettuce-leak stub. |
| `…/cluster/ClusterMessageSender.java` | Modify — `roomMessage` + `onRoomMessage` (dispatch from unicast) + node-set cache + registry wiring in start/shutdown. |
| `…/cluster/ClusterProperties.java` | Modify — `Room` nested (`enable`, `node-set-cache-ttl-ms`). |
| `…/cluster/metrics/NettyClusterMeterBinder.java` + `ClusterRuntimeStats` | Modify — room meters. |
| `…starter/.../NettyWebSocketClusterConfigure.java` | Modify — gated `clusterRoomRegistry` bean + wire into sender. |
| `…starter/.../additional-spring-configuration-metadata.json` | Modify — `room.*` keys. |
| Tests (see Task 8) | Create. |
| `docs/release-notes-1.10.0.md` (new), `cluster-design.md`, `api-guide.md` | Modify (Task 9). |

---

## Task 1: Envelope v2 (room field + co-bump) + rolling-upgrade test

**The riskiest correctness item — do first, gate with the mixed-version test.**

- [ ] Read `ClusterEnvelope.java` + `SimpleTextEnvelopeCodec.java` fully. Determine how the codec splits
  (fixed field count) and where the version check is relative to the split.
- [ ] Add `MessageKind.ROOM_BROADCAST`. Add `String room` field + getter + both constructors.
- [ ] Bump `CURRENT_VERSION` 1→2. In the codec, **co-bump the field count in lockstep** and make decode
  version-aware: v1 wire (old field count) → room=null; v2 wire → parse room. A v1 reader (1.9.0 node)
  receiving a v2 wire must **discard on version>max** WITHOUT crashing — verify the split tolerates the
  extra field or the version token is read first. If the current split-then-check order would crash a 1.9.0
  node, choose the **append-room-as-trailing-field** layout so the 1.9.0 splitter still parses the leading
  fields and the version gate (now reading version=2) discards cleanly.
- [ ] **Write `EnvelopeRollingUpgradeTest`** (the gate): (a) v2 codec encodes → v2 codec decodes round-trip
  with room; (b) v2 codec decodes a hand-built v1 wire → room=null, no error; (c) simulate a v1 decode of a
  v2 wire → discarded on version, no exception. This test MUST pass before proceeding.
- [ ] `mvn -pl netty-spring-websocket-cluster test -Dtest=SimpleTextEnvelopeCodecTest,EnvelopeRollingUpgradeTest`
- [ ] Commit: `feat(cluster): envelope v2 — room field + ROOM_BROADCAST + version-safe codec (RC1 T1)`

## Task 2: ClusterRoomRegistry SPI

- [ ] Create `spi/ClusterRoomRegistry.java` exactly per spec §3 (join/leave/nodesForRoom/localMembers/
  roomsForSession/removeAllForSession/removeAllForNode/shutdown). Javadoc the caching contract + that it
  mirrors `SessionRegistry`.
- [ ] Compile. Commit: `feat(cluster): ClusterRoomRegistry SPI (RC1 T2)`

## Task 3: InMemoryRoomRegistry stub (test scope)

- [ ] Create `room/InMemoryRoomRegistry.java` under `src/test` (mirrors `InMemorySessionRegistry`):
  in-process maps for membership + per-room node-set + roomsForSession. Implements the SPI with
  `CompletableFuture.completedFuture`.
- [ ] Unit test `InMemoryRoomRegistryTest`: join/leave updates node-set (add on first node member, remove on
  last); removeAllForSession clears all rooms; localMembers correct.
- [ ] `mvn -pl netty-spring-websocket-cluster test -Dtest=InMemoryRoomRegistryTest`
- [ ] Commit: `test(cluster): InMemoryRoomRegistry stub + unit test (RC1 T3)`

## Task 4: RedisRoomRegistry (atomic Lua + local index)

- [ ] Read `RedisSessionRegistry.java` (atomic Lua deregister pattern, base64url key encoding, executor) +
  `RedisClusterModeSessionRegistry.java` (hash-tag for single-slot) to ground the impl.
- [ ] Create `room/RedisRoomRegistry.java`:
  - Keys per spec §5 with base64url(uri)/base64url(room) + hash tag `{b64uri}:{b64room}` for single-slot
    JOIN/LEAVE.
  - `JOIN_LUA` / `LEAVE_LUA` / `REMOVE_ALL_FOR_SESSION_LUA` as single EVALs (no looped SREMs).
  - `removeAllForNode`: SCAN `netty:room:*:n:{node}` + pipelined conditional node-set SREM (cluster-aware).
  - Local index (`ConcurrentHashMap`) updated AFTER Lua confirms. Serves `localMembers`/`roomsForSession`
    with no I/O.
  - Dedicated executor for the blocking Lettuce calls (mirror `NatsKvSessionRegistry` rationale).
- [ ] `RedisRoomRegistryTest` (Mockito): verify JOIN_LUA/LEAVE_LUA EVAL'd (not looped SREMs); node-set
  add-on-first / remove-on-last; local index consistency; removeAllForSession single EVAL.
- [ ] `mvn -pl netty-spring-websocket-cluster test -Dtest=RedisRoomRegistryTest`
- [ ] Commit: `feat(cluster/redis): RedisRoomRegistry — atomic Lua + local index (RC1 T4)`

## Task 5: ClusterMessageSender — roomMessage + onRoomMessage + node-set cache

- [ ] Read `ClusterMessageSender.java` around: `topicMessage` (L336), `onUnicastMessage` dispatch (L256
  area), `buildBroadcastEnvelope` (L746), start/shutdown, the existing unicast cache. Confirm the exact
  per-node publish entrypoint (`broker.publishToNode` or equivalent on the unicast channel).
- [ ] Add `roomMessage(uri, room, message)` per spec §6: local fan-out to `localMembers` → gate
  (node ACTIVE && broker ACTIVE) → `nodesForRoomCached(uri,room)` minus self → build ROOM_BROADCAST
  envelope (reuse `buildBroadcastEnvelope` + set room) → size-cap → publish to each target node's unicast
  channel → stats.
- [ ] Add node-set cache (short TTL = `node-set-cache-ttl-ms`, invalidate on NODE_LEFT) — mirror the
  existing unicast `registry-read-cache` pattern.
- [ ] In the existing unicast receive dispatch, branch on `kind==ROOM_BROADCAST` → `onRoomMessage`:
  origin self-suppress, fan-out to `localMembers(uri,room)`, count stale-target if empty.
- [ ] Wire `ClusterRoomRegistry` (nullable; null when room.enable=false) into the constructor; integrate
  shard/room registry into `start()`/`shutdown()` ordering with the `_shuttingDown` guard.
- [ ] `ClusterRoomSenderTest` (InMemory broker + InMemoryRoomRegistry): roomMessage targets only nodes in
  the node-set; local fan-out happens; origin suppressed; onRoomMessage to zero local members counted stale.
- [ ] `mvn -pl netty-spring-websocket-cluster test -Dtest=ClusterRoomSenderTest`
- [ ] Commit: `feat(cluster): roomMessage + onRoomMessage via per-node channel + node-set cache (RC1 T5)`

## Task 6: Config + metrics

- [ ] `ClusterProperties.Room` nested: `enable` (false), `nodeSetCacheTtlMs` (5000). Getters/setters.
- [ ] `NettyClusterMeterBinder` + `ClusterRuntimeStats`: `room.broadcast.published/received`,
  `room.fanout.target_nodes` (distribution), `room.fanout.stale_target` (counter), `room.members.local`
  (gauge). Read-only pass-through (no hot-path change), gated on micrometer like the existing meters.
- [ ] `additional-spring-configuration-metadata.json`: `room.enable`, `room.node-set-cache-ttl-ms`.
- [ ] `mvn -pl netty-spring-websocket-cluster test` (meter binder test).
- [ ] Commit: `feat(cluster): room.* config + netty.cluster.room.* meters (RC1 T6)`

## Task 7: Auto-config bean wiring

- [ ] In `NettyWebSocketClusterConfigure`: gated `@Bean clusterRoomRegistry`
  (`@ConditionalOnProperty cluster.room.enable=true` + `@ConditionalOnMissingBean(ClusterRoomRegistry.class)`)
  → `RedisRoomRegistry` (reuse the existing standalone Redis connection bean). Wire into
  `ClusterMessageSender` (pass null when absent). Dead-node hook: register the room registry's
  `removeAllForNode` alongside the existing `ClusterReaper` cleanup.
- [ ] Context tests in `NettyWebSocketClusterConfigureTest`: room.enable=true → registry bean present +
  wired; room.enable=false → absent, no room path.
- [ ] `mvn -pl netty-websocket-cluster-spring-boot-starter test`
- [ ] Commit: `feat(cluster/autoconfig): gated ClusterRoomRegistry bean + dead-node hook (RC1 T7)`

## Task 8: Integration tests + 3-scenario benchmark

- [ ] `RoomRegistryIntegrationTest` (Testcontainers Redis, reuse `ClusterTestRedis`): join/leave/nodesForRoom
  round-trip; removeAllForSession atomic; removeAllForNode dead-node cleanup; concurrent join/leave on one
  room stays consistent.
- [ ] `ClusterRoomE2ETest` (two-node real Redis): node A `roomMessage(R)` where R has a member on B → B
  delivers to its local member; a third node NOT hosting R does NOT receive (the reduction assertion);
  origin self-suppress; HMAC on.
- [ ] `RoomFanoutBenchmark` (manual harness, parallels `PerformanceBenchmark`): **3 scenarios, all printed**:
  (1) favorable — many bounded rooms → show N/k reduction; (2) adversarial — large rooms on all nodes →
  reduction → 1 + publish-side cost; (3) hot room on every node → baseline.
- [ ] `mvn -pl netty-spring-websocket-cluster test` (full module; ITs live on Docker).
- [ ] Commit: `test(cluster): room registry IT + two-node E2E + 3-scenario fan-out benchmark (RC1 T8)`

## Task 9: Docs

- [ ] `docs/release-notes-1.10.0.md` (new): RC1 section with the **honest positioning** verbatim from spec
  §1, the N/k model, hot-room no-reduction + publish-side crossover, config + metrics, the design-correction
  note (shard→node-set, link the review archive). Bilingual.
- [ ] `docs/cluster-design.md`: add a "Room-scoped routing" subsection; update the scope table (room → ✅ RC1
  with the locality caveat). State that true cluster-wide fan-out reduction needs RC4 mesh / 2.0.0 sharded
  pub/sub.
- [ ] `docs/api-guide.md` §9: `roomMessage` API + `room.*` config + the locality caveat pointer.
- [ ] **Per the Doc Sync Matrix** — this is an RC, not a GA, so README/dev-plan/CLAUDE.md status updates wait
  for 1.10.0 GA; only release-notes-1.10.0.md + cluster-design + api-guide change now.
- [ ] Verify UTF-8, no U+FFFD.
- [ ] Commit: `docs(cluster): RC1 room registry — honest positioning + design-correction record (RC1 T9)`

## Task 10: Cut RC1

- [ ] Bump 11 POMs `1.9.0` → `1.10.0-RC1`.
- [ ] Full reactor `mvn test` — BUILD SUCCESS, capture test count.
- [ ] Update release-notes-1.10.0 test count.
- [ ] Commit: `release: 1.10.0-RC1 — ClusterRoomRegistry per-room node-targeted routing`
- [ ] `finishing-a-development-branch`: FF-merge to master, tag `v1.10.0-RC1`, STOP before push (per the
  established cadence — push/deploy is user-driven; but the GA goal means RCs accumulate on master toward GA).

---

## Self-Review

- **Spec coverage:** envelope v2 (T1) + SPI (T2) + InMemory (T3) + Redis impl (T4) + sender (T5) + config/
  metrics (T6) + auto-config (T7) + tests/benchmark (T8) + docs (T9) + cut (T10) — every spec section mapped.
- **Sequencing:** T1 first (riskiest, gated by rolling-upgrade test). T2-T4 build the registry. T5 wires the
  sender. T6-T7 config/auto-config. T8 ITs. T9 docs. T10 cut. Interdependencies respected.
- **No placeholders:** each task has concrete files + concrete test names + concrete commands.
- **Honesty:** T8 mandates the 3-scenario benchmark (incl. the collapse case); T9 mandates the honest
  positioning verbatim. The design-correction is recorded, not hidden.
