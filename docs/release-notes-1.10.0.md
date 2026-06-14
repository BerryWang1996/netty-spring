# Release Notes — 1.10.0

> **Status: IN DEVELOPMENT (RC cycle).** 1.10.0 is the IM-platform line built on top of 1.9.0 GA. RCs
> accumulate on the feature branch toward the eventual 1.10.0 GA. This file is updated per RC.

---

## 1.10.0-RC1 — ClusterRoomRegistry: per-room node-targeted routing

**The first 1.10.0 feature.** Adds room-scoped message routing + distributed room membership to the
WebSocket cluster. Rooms are the IM primitive; this is also the membership/routing foundation that future
affinity (the RC4 node-to-node mesh) builds on. Opt-in (`cluster.room.enable=true`, default `false`) — when
disabled there are no room beans, no room subscriptions, and no `ROOM_BROADCAST` envelopes are produced, so
behavior is identical to 1.9.0.

### Honest positioning (read this first)

> Room-scoped routing + per-room node-targeted delivery (opt-in). A room message reaches only the nodes
> that host members of that room, so fan-out drops to N/k (k = nodes with members) — a real reduction for
> bounded rooms in large clusters, even under random load-balanced placement. A "hot" room whose members
> span every node sees no reduction (and, publish-side, costs k≈N targeted sends vs 1 global publish) — this
> is a true property, documented, metered, and benchmarked, not hidden. This is **not** an unconditional
> "scales to 100 nodes" claim; it is "scales with room locality."

### What it is

- **Room = a sub-dimension within a `@MessageMapping` URI.** One `/ws/chat` endpoint, unlimited rooms; a
  session may be in many rooms. The routing key is `(uri, room)`.
- **The routing primitive is the per-room node-set**: the set of nodes hosting ≥1 member of a room.
  `roomMessage(uri, room, msg)` looks up that node-set and **targets only those nodes**, reusing the existing
  1.9.0 per-node unicast channel — not a shard, not all N nodes. Receivers fan out to their local members.
- **No new subscription.** Because targeting rides the per-node unicast channel every node already subscribes
  to, there is **no shard subscribe/unsubscribe churn** — no debounce, no refcounted per-shard subscriptions.

### Fan-out reduction — measured, all three cases (`RoomFanoutBenchmark`, N = 100 nodes)

| Scenario | avg nodes targeted / room | reduction vs global | publish-side cost (targeted sends/room) |
|---|---:|---:|---:|
| Favorable — bounded rooms (5 members), random LB | ~4.9 | ~20× | ~3.9 |
| Adversarial — large rooms (60 members), random LB | ~45 | ~2.2× | ~44 |
| Hot room — a member on every node (k = N) | 100 | 1.0× (none) | 99 |

The reduction is **N/k**. For bounded rooms it is large even under random load-balancer placement (a 5-member
room lands on ≤5 distinct nodes). For a hot room whose members span every node there is **no delivery
reduction**, and the publish side costs ~N targeted sends vs the 1 publish of a global `topicMessage` — so
**for rooms expected to span most nodes, use `topicMessage(uri, msg)` (global) instead.** RC1 documents and
meters this crossover; it does not auto-switch.

### API

```java
@AutowiredMessageSender
private MessageSender sender; // the @Primary ClusterMessageSender when cluster mode is on

// Membership (call on connect/subscribe and disconnect):
((RoomOperations) sender).joinRoom("/ws/chat", "room-42", sessionId);
((RoomOperations) sender).leaveRoom("/ws/chat", "room-42", sessionId);

// Send to every member of the room across the cluster (per-room node-targeted):
((RoomOperations) sender).roomMessage("/ws/chat", "room-42", new TextMessage("hi room"));
```

`RoomOperations` is a small sub-interface implemented by `ClusterMessageSender` — the base `MessageSender`
is untouched. When `room.enable=false` the room methods throw `IllegalStateException` (explicit, not a silent
drop). On local disconnect, call `removeAllRoomsForSession(uri, sessionId)` to clear all of a session's rooms
in a single distributed call.

### Config

| Key | Default | Meaning |
|---|---|---|
| `server.netty.websocket.cluster.room.enable` | `false` | Master switch. `false` = no room beans, byte-identical behavior to 1.9.0. |
| `server.netty.websocket.cluster.room.node-set-cache-ttl-ms` | `5000` | Local cache TTL for the `nodesForRoom` node-set on the room send hot path (mirrors `registry-read-cache-ttl-ms`; invalidated on `NODE_LEFT`). |

### Metrics (`netty.cluster.room.*`, when Micrometer is present)

| Meter | Meaning |
|---|---|
| `broadcast.published` (counter) | Room broadcasts sent. |
| `broadcast.received` (counter) | Room broadcasts received + locally delivered. |
| `fanout.target_nodes` (gauge) | **The reduction meter** — average nodes targeted per room broadcast. Compare to your cluster size to *see* whether you are getting reduction (1.0 = none / hot room). |
| `fanout.stale_target` (counter) | Room broadcasts received with zero local members (membership churned in-flight = wasted delivery). |
| `members.local` (gauge) | Total local room memberships on this node. |

### Redis data model + atomicity

- `netty:room:{b64uri:b64room}:nodes` — Set\<nodeId\> (the routing key).
- `netty:room:{b64uri:b64room}:n:{nodeId}` — Set\<sessionId\> (per-node member set; the refcount that decides
  node-set add-on-first / remove-on-last).
- `netty:roomsession:{b64uri}:{sessionId}` — Set\<room\> (for `removeAllForSession`).

`join` / `leave` / `removeAllForSession` each run as a **single atomic Lua `EVAL`** (no looped SREMs),
mirroring 1.9.0's atomic deregister. The per-room keys share a Redis-Cluster **hash tag** `{b64uri:b64room}`
so the node-set and the per-node member sets co-locate on one slot (single-slot JOIN/LEAVE). The local index
(in-process) is updated **after** the Lua confirms, so it never claims membership Redis rejected.

### Envelope v2 — rolling-upgrade-safe

The wire envelope gains a `room` field + a `ROOM_BROADCAST` message kind, co-bumping `CURRENT_VERSION` 1→2
and the codec's field count 8→9 **in lockstep**. The default `SimpleTextEnvelopeCodec` is **version-aware**:

- a 1.10.0 (v2) codec decoding a **1.9.0 (v1) wire** yields `room=null` — no error;
- a **1.9.0 (v1) node decoding a v2 wire discards it on the `version > max` gate** (the version token is the
  leading field, read before the payload) — no crash.

A mixed-version cluster (a 1.9.0 node and a 1.10.0 node) is therefore safe. This is gated by the mandatory
`EnvelopeRollingUpgradeTest`, the RC's correctness gate.

### Backward compatibility

`room.enable=false` (default): no room beans, no room subscriptions, no `ROOM_BROADCAST` produced → runtime
**behavior** identical to 1.9.0. Note the envelope **wire** is globally v2 since 1.10.0 (version 2, 9 fields
incl. an empty `room` field) — so it is **not** byte-for-byte identical to 1.9.0's v1/8-field wire; a 1.9.0
node safely **discards** a v2 wire on the version gate (the rolling-upgrade contract, proven by
`EnvelopeRollingUpgradeTest`). `ClusterRoomRegistry` is purely additive — no existing SPI signature changes;
`roomMessage` lives on the new `RoomOperations` sub-interface, not on `MessageSender`. Boot 2.7 + Lettuce 6.1
only.

### Tests + review

**473 个测试 / 11 个模块全绿**（1.9.0 GA 的 444 + RC1 的 ~29：envelope rolling-upgrade、InMemory/Redis room
registry、room sender、room IT、双节点 E2E reduction 断言、3-场景 fan-out benchmark、context 装配）。RC1 经
4-lens 对抗式审查（spec-compliance / envelope-v2 / concurrency / regression）+ skeptic 复核,verdict
`rc1ReadyToCut=true, 0 must-fix`;envelope v2 滚动升级安全双向证明;并发审查的 8 项发现均不损坏分布式 Redis
状态(全部 Lua 为原子单 EVAL),为 local-index/清理健壮性/可观测性 gap,已存入 RC2 backlog
(`docs/superpowers/notes/2026-06-08-rc1-review-backlog.md`)。审查中发现的「byte-identical」误导措辞已在本 RC
修正(诚实工程)。

### Design correction recorded (shard ring → per-room node-set)

The **first** design used a consistent-hashing **shard ring** (rooms hashed onto 256 shards; nodes subscribe
to shards they host members for). A 5-lens adversarial design review **quantitatively killed it**: under
default random load-balancer placement a shard *aggregates* many rooms, so every node ends up hosting members
of some room in nearly every shard → it subscribes to ~99.4% of shards → **zero fan-out reduction, collapsing
to a global broadcast.** The design pivoted to **per-room node-set targeting**, which actually reduces fan-out
(N/k) and — because it reuses the existing unicast channel — also eliminates the shard subscribe/unsubscribe
lifecycle hazards the review flagged. The review verdict was `FIX_DESIGN_FIRST`; the full review is archived
at `docs/superpowers/notes/2026-06-08-room-registry-design-review.json`.

### Deferred to later RCs

`NatsKvRoomRegistry` (all-NATS room parity — the SPI is transport-agnostic, so it slots in with no API
change), room→home-node affinity (RC4 mesh), room-level reliable delivery, per-room history (RC2), room
presence (RC3), and an auto global/targeted crossover switch.

---

## 1.10.0-RC2 — Offline queue + user-addressable delivery

**The second 1.10.0 feature.** Adds a stable `userId` recipient identity + per-user offline message queue:
`sendToUser(userId, msg)` delivers to an online user in realtime, or — when the user has no live session
anywhere in the cluster — stores the message and backfills it (FIFO) when they reconnect. This is the IM
"send to a user who is offline" primitive, distinct from RC2-of-1.9.0's reliable *broadcast* (which replays
to briefly-disconnected **nodes**, not offline **users**). Opt-in (`cluster.offline.enable=true`, default
`false`) — when disabled there are no offline beans, no userId resolution, and the cluster session hook
passes `emptyMap()` to register exactly as RC1 (byte-identical).

### Honest positioning (read this first)

> **At-least-once to offline users, within the retention window** (`max-messages-per-user` default 1000,
> `ttl-seconds` default 7 days). Beyond it, the oldest are trimmed (a bounded gap, like reliable broadcast's
> MAXLEN; metered `offline.dropped_retention`). **Ordering is per-user FIFO** (Redis Stream). Cross-sender
> order is not guaranteed (same caveat as reliable). **Not exactly-once:** drain delivers then deletes; if the
> delete fails after delivery, the next connect redelivers — **handlers must be idempotent** (same contract as
> reliable broadcast). Each backfilled message carries an `X-Offline-Message-Id` (the Redis stream entry id, in
> MDC during dispatch) so apps can dedup on the infra id. **Offline = zero sessions cluster-wide:** a
> multi-device user with any online session is "online" and is delivered to the online device(s); per-device
> offline backfill is RC3. **Identity is required:** anonymous sessions (resolver → null) get no userId and no
> offline queue. Redis-only in RC2 (NATS-KV offline store is a later RC).

### Send-time-only boundary (the load-bearing honesty)

`broker.unicast()` is **fire-and-forget** (it throws only on send-build / broker-accept failure, never on
post-accept delivery). So the offline queue is a fallback for **send-time** failures only: zero reachable
sessions, or a **local** `MessageSessionClosedException`. A **remote** session that closes *after* the broker
accepted the unicast but *before* the frame arrives produces no exception and triggers **no enqueue** — that
message is **not** recovered by the offline queue. This is metered as `offline.unicast_failures` (distinct
from `queued`); exactly-once is out of scope (handlers reconcile via idempotency at their layer).

### 🔒 SECURITY — the default resolver is testing-only (read before production)

The offline queue, presence, and user-addressed delivery all key on the `userId` returned by `UserIdResolver`.
**A wrong identity is cross-user data exposure** (read another user's queued messages, impersonate their
presence, hijack their delivery). The `UserIdResolver` SPI javadoc carries a **SECURITY CONTRACT**: the
returned userId MUST be derived from the session's **authenticated** principal (verified JWT `sub`, OAuth,
SAML NameID), never a raw client-controllable value.

> The default **`HandshakeUserIdResolver`** reads `query:userId` / `header:X-User-Id` **verbatim** and is
> **explicitly convenience/testing only**. A client connecting with `?userId=bob` would be treated as `bob`.
> **Production IM MUST supply its own `UserIdResolver` `@Bean`** that validates identity — typically a
> `WebSocketHandshakeInterceptor` authenticates the connection and the resolver reads the already-verified
> principal. The auto-config registers the default only under `@ConditionalOnMissingBean`, so a user-supplied
> resolver replaces it. With `offline.enable=false` (default) the resolver is never invoked — no identity
> surface at all.

### API

```java
@AutowiredMessageSender
private MessageSender sender; // the @Primary ClusterMessageSender when cluster mode is on

// Deliver to a user by stable identity — realtime if online, else queued for backfill:
((UserOperations) sender).sendToUser("user-42", new TextMessage("hi user"));

// Presence (fresh, uncached lookup):
CompletionStage<Boolean> online = ((UserOperations) sender).isUserOnline("user-42");
```

`UserOperations` is a small sub-interface implemented by `ClusterMessageSender` — the base `MessageSender` is
untouched. When `offline.enable=false`, `sendToUser`/`isUserOnline` throw `IllegalStateException` (explicit,
not a silent drop). On connect, the cluster session hook resolves the userId, binds presence, and drains the
offline queue (backfill); on disconnect it unbinds.

### Config

| Key | Default | Meaning |
|---|---|---|
| `server.netty.websocket.cluster.offline.enable` | `false` | Master switch. `false` = no offline beans, RC1 `emptyMap` hook path (byte-identical). |
| `server.netty.websocket.cluster.offline.user-id-source` | `query:userId` | Where the **testing-only** `HandshakeUserIdResolver` reads the userId (`query:<name>` / `header:<name>`). |
| `server.netty.websocket.cluster.offline.max-messages-per-user` | `1000` | Per-user Redis Stream `MAXLEN ~` — the at-least-once retention bound. |
| `server.netty.websocket.cluster.offline.ttl-seconds` | `604800` | Per-message age cap (7 days); lazily dropped on drain + bounded by stream trim. |
| `server.netty.websocket.cluster.offline.drain-batch-size` | `100` | Max messages drained + delivered per connect. |
| `server.netty.websocket.cluster.offline.drain-lock-ms` | `5000` | Per-userId drain lock TTL (`SET NX PX`); auto-expires so a crashed drainer can't wedge the queue. |

### Metrics (`netty.cluster.offline.*`, when Micrometer is present)

| Meter | Meaning |
|---|---|
| `enqueued` / `drained` (counter) | Messages stored offline / drained + backfilled on reconnect. |
| `dropped_retention` (counter) | Entries trimmed by `MAXLEN`/TTL — **the bounded-gap honesty meter** (alert on `rate(...[5m]) > 0`). |
| `send_to_user.realtime` / `send_to_user.queued` (counter) | The online/offline split of `sendToUser`. |
| `unicast_failures` (counter) | Send-time unicast failures (local close on a bound session) — distinct from `queued` and from post-accept loss. |
| `fallback_enqueue_failures` (counter) | Enqueue itself failed after all unicast paths — **never a silent drop** (logged ERROR). |
| `resolved_identities` / `unresolved_sessions` (counter) | Authenticated vs anonymous handshakes. |
| `users.online` (gauge) | Local bound-user sessions on this node (per-node identified live sessions, not a distinct cluster-wide count). |

### Redis data model + the drain lock

- `netty:user:{b64userId}` — Set of `nodeId|b64uri|sessionId` members (the `userId → sessions` presence
  index; **never cached** — every `sessionsForUser`/`isUserOnline` hits Redis; see the no-cache decision
  below). Hash-tagged on `{b64userId}` for Redis-Cluster slot co-location.
- `netty:offline:{b64userId}` — Redis Stream of HMAC-wrapped envelopes (FIFO, `MAXLEN ~ max-messages-per-user`),
  mirroring the reliable broadcast wrap-on-write so the offline path carries the same anti-forgery tag.
- `netty:offline-lock:{b64userId}` — the **per-userId drain lock** (`SET NX PX drain-lock-ms`). `drain()`
  acquires it; if it is already held — a concurrent multi-device reconnect — `drain()` returns empty (the lock
  holder delivers). `delete()` XDELs the acked ids then DELs the lock. This kills **deterministic** multi-device
  double-delivery (both devices would otherwise `XRANGE` the whole stream before either deleted — a guaranteed
  duplicate, not a rare race), proven by the `MultiDeviceDrainLockTest` regression gate.

### Design decisions (from the 4-lens adversarial review — verdict was FIX_DESIGN_FIRST, fixes folded in)

- **`UserRegistry` is a dedicated SPI, not added to `SessionRegistry`** — a derived routing/presence index
  (reconciled via `removeAllForNode`), kept separate so the 1.9.0 `SessionRegistry` signature and its 3 impls
  stay untouched. RC3 multi-device presence extends this same SPI.
- **`sessionsForUser` is NOT cached** — the offline-detection correctness decision. Caching presence would let
  a just-disconnected user read "online" → a fire-and-forget unicast to a dead session → no exception → no
  fallback → **silent loss**. The reverse lookup hits the store every time (it is on the relatively cold
  `sendToUser` path, not the per-session hot path). Proven by `SendToUserRaceTest`.
- **Per-userId drain lock** — kills the deterministic multi-device duplicate (above), not just "idempotent
  handlers." The regression gate is `MultiDeviceDrainLockTest`.

The full review is archived at `docs/superpowers/notes/2026-06-14-rc2-design-review.json`.

### Backward compatibility

`offline.enable=false` (default): the `UserIdResolver` bean is **not created**, `UserRegistry`/
`OfflineQueueStore` beans are absent, and `onSessionRegistered` passes `Collections.emptyMap()` to register
exactly as RC1 — **byte-identical**. When `enable=true` but the resolver returns null (anonymous), register
still passes `emptyMap()`. The context test asserts all three paths (off / on+anonymous / on+authenticated).
No envelope wire change (offline reuses the existing v2 envelope; `StoredMessage` is a Redis-only wrapper
carrying the stream id). `UserRegistry` / `OfflineQueueStore` / `UserIdResolver` are additive SPIs; `sendToUser`
lives on the new `UserOperations` sub-interface, not on `MessageSender`. Redis-only RC2; Boot 2.7 + Lettuce 6.1.
A user can be in rooms AND have an offline queue (orthogonal keys).

### Deferred to later RCs

Per-device offline backfill (RC3 multi-device presence builds on `UserRegistry`), message history/scrollback
(retain-everything + fetch API — a different weight class), room/group-to-offline, `NatsKvOfflineQueueStore`
(all-NATS parity), and exactly-once.

---

# 发布说明 — 1.10.0（中文）

> **状态：开发中（RC 周期）。** 1.10.0 是构建在 1.9.0 GA 之上的 IM 平台线。各 RC 在特性分支上累积，直至最终
> 1.10.0 GA。本文件按 RC 更新。

## 1.10.0-RC1 — ClusterRoomRegistry：按房间的节点定向路由

**1.10.0 的第一个特性。** 为 WebSocket 集群新增房间维度的消息路由 + 分布式房间成员关系。房间是 IM 的基本
单元；它同时也是未来亲和性（RC4 节点间 mesh）所依赖的成员/路由地基。可选开关（`cluster.room.enable=true`，
默认 `false`）——关闭时没有任何房间 Bean、没有房间订阅、不产生 `ROOM_BROADCAST` 信封，行为与 1.9.0 完全一致。

### 诚实定位（务必先读）

> 房间维度路由 + 按房间的节点定向投递（可选）。一条房间消息只会到达承载该房间成员的节点，因此扇出降到 N/k
> （k = 有成员的节点数）——对大集群中的有界房间，即使在负载均衡器随机落点下，这也是真实的扇出削减。一个成员
> 遍布所有节点的"热房间"得不到任何削减（且在发布侧需要 k≈N 次定向发送 vs 1 次全局发布）——这是一个真实属性，
> 已被文档化、被指标化、被基准测试，而非被隐藏。这**不是**无条件的"可扩展到 100 节点"的说法；而是
> "随房间局部性而扩展"。

### 它是什么

- **房间 = 一个 `@MessageMapping` URI 内的子维度。** 一个 `/ws/chat` 端点、无限房间；一个会话可同时属于多个
  房间。路由键是 `(uri, room)`。
- **路由原语是按房间的节点集合（node-set）**：承载该房间 ≥1 个成员的节点集合。`roomMessage(uri, room, msg)`
  查询该节点集合并**只定向这些节点**，复用 1.9.0 已有的单节点单播通道——不是分片，不是全部 N 个节点。接收方
  再向本地成员扇出。
- **没有新订阅。** 因为定向复用了每个节点本就订阅的单播通道，所以**没有分片订阅/退订抖动**——没有去抖、没有
  按分片的引用计数订阅。

### 扇出削减——三种情况全部实测（`RoomFanoutBenchmark`，N = 100 节点）

| 场景 | 每房间定向节点数（均值） | 相对全局的削减 | 发布侧成本（定向发送/房间） |
|---|---:|---:|---:|
| 有利——有界房间（5 成员），随机落点 | ~4.9 | ~20× | ~3.9 |
| 对抗——大房间（60 成员），随机落点 | ~45 | ~2.2× | ~44 |
| 热房间——每个节点都有成员（k = N） | 100 | 1.0×（无） | 99 |

削减量为 **N/k**。对有界房间，即使随机落点也很大（5 成员房间最多落在 5 个节点上）。对成员遍布所有节点的热房间，
**没有任何投递削减**，且发布侧需要 ~N 次定向发送 vs 全局 `topicMessage` 的 1 次发布——所以**对预期会遍布大多数
节点的房间，请改用 `topicMessage(uri, msg)`（全局）。** RC1 文档化并指标化这一交叉点，但不自动切换。

### 配置

| 键 | 默认 | 含义 |
|---|---|---|
| `server.netty.websocket.cluster.room.enable` | `false` | 总开关。`false` = 无房间 Bean，行为与 1.9.0 逐字节一致。 |
| `server.netty.websocket.cluster.room.node-set-cache-ttl-ms` | `5000` | 房间发送热路径上 `nodesForRoom` 节点集合的本地缓存 TTL（对齐 `registry-read-cache-ttl-ms`；`NODE_LEFT` 时整体失效）。 |

### 指标（`netty.cluster.room.*`，存在 Micrometer 时）

| 指标 | 含义 |
|---|---|
| `broadcast.published`（计数器） | 已发送的房间广播。 |
| `broadcast.received`（计数器） | 已接收并本地投递的房间广播。 |
| `fanout.target_nodes`（仪表） | **削减指标**——每条房间广播定向的平均节点数。与集群规模对比即可**看出**是否获得削减（1.0 = 无削减/热房间）。 |
| `fanout.stale_target`（计数器） | 接收到但本地零成员的房间广播（成员在途中变动 = 无效投递）。 |
| `members.local`（仪表） | 本节点的本地房间成员总数。 |

### 信封 v2——滚动升级安全

信封新增 `room` 字段 + `ROOM_BROADCAST` 类型，将 `CURRENT_VERSION` 1→2 与编解码器字段数 8→9 **锁步同步**。
默认 `SimpleTextEnvelopeCodec` 是**版本感知**的：v2 解码 v1 报文得到 `room=null`、不报错；v1（1.9.0 节点）解码
v2 报文在 `version > max` 闸门上**丢弃**（版本号是首字段，在解析载荷前读取）、不崩溃。因此 1.9.0 节点与 1.10.0
节点混部安全，由强制的 `EnvelopeRollingUpgradeTest` 守门。

### 设计修正记录（分片环 → 按房间节点集合）

**最初**的设计使用一致性哈希**分片环**（房间映射到 256 个分片；节点订阅其承载成员的分片）。一次 5 视角对抗式
设计评审**用量化方式否决了它**：在默认随机落点下，一个分片*聚合*了很多房间，于是每个节点几乎在每个分片里都
承载了某个房间的成员 → 订阅 ~99.4% 的分片 → **零扇出削减，退化为全局广播。** 设计转向**按房间节点集合定向**，
它确实削减扇出（N/k），且因复用已有单播通道，也消除了评审指出的分片订阅/退订生命周期隐患。评审结论为
`FIX_DESIGN_FIRST`；完整评审归档于 `docs/superpowers/notes/2026-06-08-room-registry-design-review.json`。

### 推迟到后续 RC

`NatsKvRoomRegistry`（全 NATS 房间对等——SPI 与传输无关，可无 API 变更地接入）、房间→归属节点亲和性（RC4
mesh）、房间级可靠投递、按房间历史（RC2）、房间在线状态（RC3）、以及全局/定向的自动交叉切换。

---

## 1.10.0-RC2 — 离线队列 + 按用户寻址投递

**1.10.0 的第二个特性。** 引入稳定的 `userId` 接收方身份 + 每用户离线消息队列：`sendToUser(userId, msg)`
对在线用户实时投递；当该用户在集群中任何节点都没有活跃会话时，存储消息并在其重连时按 FIFO 回填（backfill）。
这是 IM「给离线用户发消息」的基本原语，区别于 1.9.0 的可靠*广播*（RC2-of-1.9.0，向短暂掉线的**节点**重放，
而非离线**用户**）。可选开关（`cluster.offline.enable=true`，默认 `false`）——关闭时没有任何离线 Bean、不做
userId 解析，集群会话钩子向 register 传 `emptyMap()`，与 RC1 逐字节一致。

### 诚实定位（务必先读）

> **对离线用户在保留窗口内至少一次**（`max-messages-per-user` 默认 1000，`ttl-seconds` 默认 7 天）。超出后
> 最旧的被裁剪（有界缺口，如可靠广播的 MAXLEN；指标 `offline.dropped_retention`）。**按用户 FIFO**（Redis
> Stream）。跨发送方顺序不保证（与可靠相同的注意点）。**非精确一次：** drain 先投递后删除；若投递后删除失败，
> 下次连接会重投——**处理器必须幂等**（与可靠广播相同的约定）。每条回填消息携带一个 `X-Offline-Message-Id`
> （Redis stream 条目 id，投递期间置于 MDC），应用可据该基础设施 id 去重。**离线 = 集群范围内零会话：** 一个
> 有任一在线会话的多设备用户即视为「在线」，投递到其在线设备；按设备的离线回填是 RC3。**身份必需：** 匿名会话
> （解析器 → null）没有 userId，也没有离线队列。RC2 仅 Redis（NATS-KV 离线存储是后续 RC）。

### 仅发送时边界（承重的诚实）

`broker.unicast()` 是**发后即忘**（仅在发送构建/broker 受理失败时抛异常，受理后投递永不抛）。因此离线队列只是
**发送时**失败的兜底：零可达会话，或一次**本地** `MessageSessionClosedException`。一个**远程**会话在 broker
受理单播之后、帧到达之前关闭，不会产生异常，也**不会入队**——该消息**不**由离线队列恢复。这被指标化为
`offline.unicast_failures`（区别于 `queued`）；精确一次超出范围（处理器在其层面靠幂等对账）。

### 🔒 安全——默认解析器仅供测试（生产前必读）

离线队列、在线状态与按用户投递都以 `UserIdResolver` 返回的 `userId` 为键。**错误的身份就是跨用户数据泄露**
（读取他人排队消息、冒充其在线状态、劫持其投递）。`UserIdResolver` SPI 的 javadoc 携带**安全契约**：返回的
userId 必须派生自会话的**已认证**主体（验签的 JWT `sub`、OAuth、SAML NameID），绝不能是客户端可控的原始值。

> 默认的 **`HandshakeUserIdResolver`** 逐字读取 `query:userId` / `header:X-User-Id`，**明确仅供便利/测试**。
> 客户端用 `?userId=bob` 连接就会被当作 `bob`。**生产 IM 必须提供自己的 `UserIdResolver` `@Bean`** 来校验
> 身份——通常由 `WebSocketHandshakeInterceptor` 认证连接、解析器读取已验证的主体。自动装配仅在
> `@ConditionalOnMissingBean` 下注册默认实现，因此用户提供的解析器会替换它。`offline.enable=false`（默认）时
> 解析器永不被调用——完全没有身份面。

### API

```java
@AutowiredMessageSender
private MessageSender sender; // 集群模式开启时即 @Primary 的 ClusterMessageSender

// 按稳定身份投递——在线则实时，否则入队等待回填：
((UserOperations) sender).sendToUser("user-42", new TextMessage("hi user"));

// 在线状态（新鲜、无缓存的查询）：
CompletionStage<Boolean> online = ((UserOperations) sender).isUserOnline("user-42");
```

`UserOperations` 是由 `ClusterMessageSender` 实现的小型子接口——基础 `MessageSender` 不变。`offline.enable=false`
时 `sendToUser`/`isUserOnline` 抛 `IllegalStateException`（显式，而非静默丢弃）。连接时集群会话钩子解析 userId、
绑定在线状态、并 drain 离线队列（回填）；断开时解绑。

### 配置

| 键 | 默认 | 含义 |
|---|---|---|
| `server.netty.websocket.cluster.offline.enable` | `false` | 总开关。`false` = 无离线 Bean，RC1 的 `emptyMap` 钩子路径（逐字节一致）。 |
| `server.netty.websocket.cluster.offline.user-id-source` | `query:userId` | **仅供测试**的 `HandshakeUserIdResolver` 从何处读取 userId（`query:<name>` / `header:<name>`）。 |
| `server.netty.websocket.cluster.offline.max-messages-per-user` | `1000` | 每用户 Redis Stream `MAXLEN ~`——至少一次的保留上界。 |
| `server.netty.websocket.cluster.offline.ttl-seconds` | `604800` | 每条消息的年龄上限（7 天）；drain 时惰性丢弃 + 由 stream 裁剪兜底。 |
| `server.netty.websocket.cluster.offline.drain-batch-size` | `100` | 每次连接 drain + 投递的最大消息数。 |
| `server.netty.websocket.cluster.offline.drain-lock-ms` | `5000` | 每 userId 的 drain 锁 TTL（`SET NX PX`）；自动过期，使崩溃的 drainer 不会卡住队列。 |

### 指标（`netty.cluster.offline.*`，存在 Micrometer 时）

| 指标 | 含义 |
|---|---|
| `enqueued` / `drained`（计数器） | 离线存储的消息 / 重连时 drain + 回填的消息。 |
| `dropped_retention`（计数器） | 被 `MAXLEN`/TTL 裁剪的条目——**有界缺口诚实指标**（对 `rate(...[5m]) > 0` 告警）。 |
| `send_to_user.realtime` / `send_to_user.queued`（计数器） | `sendToUser` 的在线/离线分流。 |
| `unicast_failures`（计数器） | 发送时单播失败（绑定会话上的本地关闭）——区别于 `queued` 与受理后丢失。 |
| `fallback_enqueue_failures`（计数器） | 在所有单播路径之后入队本身失败——**绝不静默丢弃**（ERROR 日志）。 |
| `resolved_identities` / `unresolved_sessions`（计数器） | 已认证 vs 匿名握手。 |
| `users.online`（仪表） | 本节点的已绑定用户会话数（本节点已识别的活跃会话，非跨集群去重计数）。 |

### Redis 数据模型 + drain 锁

- `netty:user:{b64userId}` —— `nodeId|b64uri|sessionId` 成员的 Set（`userId → sessions` 在线索引；**永不缓存**——
  每次 `sessionsForUser`/`isUserOnline` 都命中 Redis；见下方 no-cache 决策）。按 `{b64userId}` 加哈希标签以在
  Redis Cluster 中同槽。
- `netty:offline:{b64userId}` —— HMAC 包裹的信封 Redis Stream（FIFO，`MAXLEN ~ max-messages-per-user`），与可靠
  广播的写时包裹一致，使离线路径携带同样的防伪标签。
- `netty:offline-lock:{b64userId}` —— **每 userId 的 drain 锁**（`SET NX PX drain-lock-ms`）。`drain()` 获取它；
  若已被持有——并发的多设备重连——`drain()` 返回空（持锁者投递）。`delete()` 先 XDEL 已 ack 的 id 再 DEL 锁。
  这消除了**确定性**的多设备重复投递（否则两设备都会在任一方删除前 `XRANGE` 整条流——必然重复，而非偶发竞态），
  由 `MultiDeviceDrainLockTest` 回归门守护。

### 设计决策（来自 4 视角对抗式评审——结论 FIX_DESIGN_FIRST，修复已折入）

- **`UserRegistry` 是独立 SPI，未加进 `SessionRegistry`** —— 一个派生的路由/在线索引（经 `removeAllForNode` 对账），
  保持独立以使 1.9.0 的 `SessionRegistry` 签名及其 3 个实现不变。RC3 多设备在线状态扩展同一 SPI。
- **`sessionsForUser` 不缓存** —— 离线检测正确性决策。缓存在线状态会让刚断开的用户读到「在线」 → 对死会话发后即忘
  单播 → 无异常 → 无兜底 → **静默丢失**。反向查询每次命中存储（位于相对冷的 `sendToUser` 路径，而非每会话热路径）。
  由 `SendToUserRaceTest` 证明。
- **每 userId 的 drain 锁** —— 消除上面的确定性多设备重复，而不仅是「幂等处理器」。回归门是 `MultiDeviceDrainLockTest`。

完整评审归档于 `docs/superpowers/notes/2026-06-14-rc2-design-review.json`。

### 向后兼容

`offline.enable=false`（默认）：**不创建** `UserIdResolver` Bean，无 `UserRegistry`/`OfflineQueueStore` Bean，
`onSessionRegistered` 向 register 传 `Collections.emptyMap()`，与 RC1 完全一致——**逐字节一致**。`enable=true` 但
解析器返回 null（匿名）时，register 仍传 `emptyMap()`。context 测试断言全部三条路径（关 / 开+匿名 / 开+已认证）。
无信封线格变化（离线复用现有 v2 信封；`StoredMessage` 是仅 Redis 的包装，携带 stream id）。`UserRegistry` /
`OfflineQueueStore` / `UserIdResolver` 都是新增 SPI；`sendToUser` 位于新的 `UserOperations` 子接口，不在
`MessageSender` 上。RC2 仅 Redis；Boot 2.7 + Lettuce 6.1。用户可同时在房间里并拥有离线队列（键正交）。

### 推迟到后续 RC

按设备的离线回填（RC3 多设备在线状态基于 `UserRegistry`）、消息历史/回滚（保留一切 + 拉取 API——不同量级）、
房间/群组到离线、`NatsKvOfflineQueueStore`（全 NATS 对等）、以及精确一次。
