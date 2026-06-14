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
