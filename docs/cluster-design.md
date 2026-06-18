# Cluster Design — Redis 集群方案（设计全集 + 1.8.0 实现范围）

> **本文档描述的是完整目标架构（设计全集），不是单一版本的实现清单。** `1.8.0` 实现了其中一个子集；`1.9.0` RC1 完成了 5 项可靠性硬化项，RC2 新增了 Redis Streams 可靠投递（`reliableBroadcast`），RC3 新增 HMAC envelope 认证（`auth.*` 3 个配置项）；其余能力推迟到后续版本。下方"## 实现范围"表是实现与设计的权威对照——阅读本文其余章节时，请以该表为准判断某项是否已落地。
> 落地进度与推迟项跟踪：`docs/development-plan.md`。

## 实现范围 vs 设计目标（1.8.0 / 1.9.0 已落地）

> 原则：**只暴露有实际效果的配置项**。本文档其余章节描述的 `pubsub-connections`、
> `sharded-pubsub`、`publish-batch-size`、`max-subscribed-channels`、
> `subscription-hold-duration`、`redis.mode` 等配置项**当前在 `ClusterProperties` 中不存在**
> （其特性尚未实现，不提供会撒谎的开关），待对应特性落地时再引入。
> `redis-loss-grace-period-ms` 和 `session-registry-write-rate` 已在 **1.9.0 RC1** 落地；
> `reliable.*` 5 个配置项已在 **1.9.0 RC2** 落地（见下表）。

| 能力 | 1.8.0 | 说明 |
| --- | :---: | --- |
| `ClusterBroker` / `SessionRegistry` SPI + Redis 实现 | ✅ | `RedisPubSubBroker` + `RedisSessionRegistry` |
| `EnvelopeCodec` / `MessagePayloadCodec` SPI（零 Jackson） | ✅ | 默认 `SimpleTextEnvelopeCodec` + `DefaultMessagePayloadCodec` |
| `ClusterNodeHeartbeat` SPI + Redis TTL 实现 | ✅ | 心跳注册/续约/过期检测 |
| 跨节点广播 + 单播 + 远程关闭（CLOSE） | ✅ | at-most-once |
| origin 自投递抑制 | ✅ | 有回归测试 |
| 节点状态机 JOINING→ACTIVE→DEGRADED→RESYNC→DRAINING→LEFT | ✅ | |
| 心跳 + 周期对账（reconciliation）双路故障检测 | ✅ | 兜底 keyspace notification 漏报 |
| 单播热路径缓存（`registry-read-cache-ttl-ms`） | ✅ | + dead-node 失效 |
| `on-redis-loss`（degrade-to-local 默认 / close-all） | ✅ | |
| **事件驱动即时降级**（Lettuce 连接事件 → 立刻 DEGRADED + 真实 `broker.state()`） | ✅ | 非 3s 心跳滞后;心跳探测降级为兜底 |
| **命令超时 + 单播热路径短路**（`command-timeout-ms`，降级时不查 registry） | ✅ | Redis 失联不再最长阻塞 60s |
| `on-publish-failure`（log / drop）、`message-max-size-bytes`、`reconnect-jitter-max-seconds` | ✅ | |
| `ClusterRuntimeStats` 程序内计数 | ✅ | broadcastPublished / selfDropped / unicastSent / publishFailures / cacheHitRatio |
| Sentinel（`redis-sentinel://` URI） | ✅ | Lettuce 原生，URI scheme 选择拓扑 |
| **Redis 失联宽限期**（`redis-loss-grace-period-ms`，默认 5000） | ✅ 1.9.0 | 宽限窗口内 Redis 抖动不触发状态机降级；broker `state()` 仍立刻翻转；`0` 恢复 1.8.0 即时降级 |
| **心跳/对账线程隔离 + 批量 EXISTS**（两个独立调度器 `cluster-hb` / `cluster-recon`） | ✅ 1.9.0 | 慢对账扫描不再饿死心跳续约；`findExpiredNodes` 改为管道批量 EXISTS |
| **原子 Lua deregister**（HGET→DEL→SREM 单 `EVAL`） | ✅ 1.9.0 | 消除并发 re-register 交错的理论竞态；standalone/sentinel 支持 |
| **对账选主去重**（`ClusterReaper` SPI，`RedisClusterReaper` 用 `SET NX` 认领） | ✅ 1.9.0 | 死节点只被一个存活节点清理；`@ConditionalOnMissingBean` 可覆盖 |
| **Registry 写合并限速**（`session-registry-write-rate`，默认 1000 ops/s，`CoalescingRegistryWriter`） | ✅ 1.9.0 | token-bucket 透传；超速时合并写入；register 永不丢弃；防注册风暴 |
| 多 pub/sub 连接并行解码（`pubsub-connections`） | ✅ 1.9.x（opt-in，默认 1） | 把 SUBSCRIBE 入站解码按频道哈希分散到 N 个 Lettuce 连接；**默认关闭、零开销**，仅在逼近单连接解码墙（墙②，~80k msg/s）时按需开启。非当前瓶颈，纯前瞻性开关 |
| sharded pub/sub（`SSUBSCRIBE`/`SPUBLISH`，**广播扇出削减**） | ⏳ → 2.0.0 | 需 Lettuce 6.2+（Boot 2.7.18 为 6.1.10）；这才是扇出削减来源。RC7 的常规 cluster pub/sub **不**削减扇出 |
| Redis Cluster 客户端一等支持（`RedisClusterClient`） | ✅ 1.9.0 RC7（客户端层） | `cluster-nodes` 非空选择 cluster 传输；4 个 `RedisClusterMode*` 实现（slot 安全）；HA 故障转移 + 注册表/心跳跨 slot 分布。**仅客户端层，不削减广播扇出**（扇出削减见上一行 sharded pub/sub → 2.0.0） |
| **可靠投递（at-least-once `reliableBroadcast`，opt-in）** | ✅ 1.9.0 RC2（Redis Streams） / ✅ 1.9.0 RC13（NATS JetStream） | RC2 落地 Redis Streams 实现；RC13 落地 NATS JetStream 对偶（`NatsJetStreamReliableBroker`，激活条件 `reliable.enable=true && nats.registry=true`，其他档位保持 Redis Streams）；`reliable.*` 6 个配置项 transport-agnostic（NATS 映射见 release-notes §⑱）；默认关闭 |
| **HMAC envelope 认证**（`MessageAuthenticator` SPI，HMAC-SHA256，anti-forgery，opt-in） | ✅ 1.9.0 RC3 | 传输层 SPI，与 codec 无关；广播/单播/CLOSE/Streams 统一签名；`auth.*` 3 个配置项；默认关闭；NoOp 剥离 `H1:` 标签；三阶段滚动升级；仅 anti-forgery，不含重放保护 |
| 写 pipeline 批量（`publish-batch-size` / `publish-flush-interval`） | ⏳ 未来版本 | 当前单条 async（Lettuce 连接层自动 pipeline） |
| 频道基数硬上限 / 订阅 hold（`max-subscribed-channels` / `subscription-hold-duration`） | ⏳ 未来版本 | 订阅集由 `@MessageMapping` URI 固定，不会无界增长 |
| Actuator 集群健康（`ClusterHealthIndicator`，节点/broker 状态 + 运行时计数） | ✅ | `/actuator/health` 下 `nettyCluster`；actuator 在 classpath 时启用 |
| 完整 Micrometer 指标集（meter-binder） | ✅ 1.9.0 RC4 | `NettyClusterMeterBinder`：11 个 counter + 节点/broker 状态 gauge（按 `state` 标签）；聚合粒度（无 per-URI 标签）；只读 `ClusterRuntimeStats`；需 `micrometer-core` + `cluster.enable=true` |
| auto-config 装配测试（ApplicationContextRunner） | ✅ | 验证 enable=true→`@Primary` 为 ClusterMessageSender + health indicator；enable=false→零集群 bean |
| W3C TraceContext 跨节点传播（MDC 日志关联） | ✅ 1.9.0 RC6 | `ClusterTraceContext` SPI + `MdcClusterTraceContext`；发送侧注入 traceparent + 接收侧恢复 MDC（`traceId`/`spanId`/`netty.traceparent`）；opt-in；Micrometer Observation 续接 → 2.0.0 |
| NATS broker（ADR-001 规模化档位） | ✅ 1.9.0 RC9（传输层） / ✅ 1.9.0 RC13（all-NATS 可靠投递） | NatsClusterBroker（core pub/sub，at-most-once）；由 nats.servers 选择；**仅传输层**，registry/心跳仍在 Redis（混合部署）；RC13 在 `nats.registry=true && reliable.enable=true` 下激活 `NatsJetStreamReliableBroker`（at-least-once；详见 release-notes §⑱） |
| 全 NATS 栈（NATS-only 选项） | ✅ 1.9.0 RC10 | `NatsKvSessionRegistry`/`NatsKvNodeHeartbeat`/`NatsKvReaper`（JetStream KV）；由 `nats.registry=true` 选择 → 整套 registry/心跳/reaper 也跑在 NATS（**无 Redis**）；**需 JetStream NATS（`nats-server -js`）**；心跳为时间戳存活；reaper 用 KV `create`（create-if-absent）做单赢领导选举；附加/opt-in，mixed（NATS broker + Redis registry）与 all-Redis 仍为默认 |
| Testcontainers 端到端 CI + 进程内双节点 E2E（`ClusterMultiNodeE2ETest`） | ✅ 1.9.0 RC5 | 集群集成测试在 CI 真实运行（不再跳过）；E2E 证明跨节点广播/单播；锁定跨节点单播 hook-wiring 修复 |
| 可运行的多节点 Docker 示例（Compose + 负载均衡 + 浏览器） | ⏳ 未来版本 | 面向人工演示；CI 验证已由上一行覆盖 |
| **房间维度路由（`ClusterRoomRegistry`，按房间节点定向投递，opt-in）** | ✅ 1.10.0 RC1 | `roomMessage(uri, room, msg)` 只定向承载该房间成员的节点（复用 1.9.0 单播通道），扇出降到 **N/k**（k = 有成员的节点数）——**对有界房间是真实削减，即使随机落点**；**热房间（成员遍布所有节点）无削减**（且发布侧 k≈N 次定向发送 vs 1 次全局发布——此时改用 `topicMessage`）。原子 Lua（join/leave/removeAll）+ 本地索引 + hash-tag 单 slot；envelope v2（`room` 字段 + `ROOM_BROADCAST`，与 v1 滚动升级兼容）；`room.*` 2 个配置项 + `netty.cluster.room.*` 5 个指标（`fanout.target_nodes` 是削减观测点）；默认关闭、行为与 1.9.0 一致。**注意：这是"按房间局部性削减"，不是无条件的全局扇出削减——真正的集群级广播扇出削减仍需 RC4 mesh / 2.0.0 sharded pub/sub。** 设计修正（分片环 → 节点集合）归档于 `docs/superpowers/notes/2026-06-08-room-registry-design-review.json` |
| **离线队列 + 按用户寻址投递（`UserRegistry`/`OfflineQueueStore`/`UserIdResolver`，opt-in）** | ✅ 1.10.0 RC2 | `sendToUser(userId, msg)`：在线实时单播，离线（集群范围内零会话）则按用户 Redis Stream 存储并在重连时 FIFO 回填；每 userId drain 锁保证多设备精确一次；`sessionsForUser` 永不缓存（杜绝假在线静默丢失）。`offline.*` 6 个配置项 + `netty.cluster.offline.*` 指标；默认关闭、行为与 RC1 逐字节一致。**安全：默认 `HandshakeUserIdResolver` 仅供测试——生产须自备认证解析器**（见 §安全模型）。详见下方设计注记 |
| **多设备聚合在线状态（`PresenceRegistry`，aggregate ONLINE/AWAY/OFFLINE + 变更事件，opt-in）** | ✅ 1.10.0 RC3 | 按用户聚合在线状态（跨所有连接，原子 Lua 转换检测）+ 专用保留通道上的 `PRESENCE_CHANGE` 事件（不升信封版本）；死节点 leader 选举回收发出权威 `→OFFLINE`，**并顺带闭合潜伏的 RC2 死节点用户绑定泄漏**（`userRegistry.removeAllForNode` 此前从未接入对账，RC3 接到 leader 主路径）。`presence.*` 2 个配置项 + `netty.cluster.presence.*` 6 个指标；身份门控从 `offline.enable` 改为 `offline.enable OR presence.enable`；默认关闭、与 RC1/RC2 逐字节一致。**按设备*寻址*推迟**（需稳定 `DeviceIdResolver`）；事件是广播（~10 节点 Pub/Sub 上限）；`getPresence` 为建议性读取，非存活探针。设计修正（设备键单向门 → 聚合+计数）归档于 `docs/superpowers/notes/2026-06-15-rc3-presence-design-review.json` |
| **node-to-node mesh 传输地基（`MeshBroker implements ClusterBroker`，直连 Netty TCP，opt-in）** | ✅ 1.10.0 RC4a（**传输地基，无扇出削减**） | `MeshBroker` 把 `ClusterBroker` 跑在节点间直连 Netty TCP 上（`RedisPubSubBroker` 的直接替代）；registry/心跳仍在 Redis，**仅用于节点地址发现，不在消息热路径**；`MeshNodeDirectory` SPI + `RedisMeshNodeDirectory`（`netty:mesh:addr:*` + PX TTL，SCAN 发现）；成员关系 = `live-by-heartbeat ∩ has-address`。**直连 unicast + 朴素广播（发给全部对端，无 per-URI 兴趣路由 → 无扇出削减）——真正的天花板突破是 RC4b**。硬化：M1 出站背压 BLOCKER + M2 帧上限 + M3 分发卸载（监听器不在 I/O loop）+ M4 advertised-host 快速失败 + M5 仅完全孤立降级；impl-review 折叠 MF1 成员∩心跳 + MF2 连接超时 + BL1 出站缓存替换。`mesh.*` 配置项；默认关闭、与 RC1/RC2/RC3 逐字节一致（`NO_MESH` AND 进单机 broker 门控）。RC4c 健壮性（热路径地址快照缓存、传输选择守卫、idle handler、mTLS）/ RC4d 指标（`netty.cluster.mesh.*`）推迟。归档 `docs/superpowers/notes/2026-06-18-rc4a-mesh-impl-review.json` |

## 目标

让单机 netty-spring 服务可以多节点水平扩展，跨节点广播和单播对业务代码尽可能透明，节点扩缩容/故障自恢复可控；同时**对失败模式保持诚实**——Redis 中断、分区、慢订阅者等问题在 API 层和指标层有清晰的语义，而不是被吞掉。

## 架构

```
                    ┌─── Load Balancer (Nginx / K8s Ingress) ───┐
                    │       sticky session by IP / cookie         │
                    ▼              ▼              ▼              ▼
              ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
              │  Node-1  │  │  Node-2  │  │  Node-3  │  │  Node-N  │
              │ netty-   │  │ netty-   │  │ netty-   │  │ netty-   │
              │ spring   │  │ spring   │  │ spring   │  │ spring   │
              └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
                   │              │              │              │
                   ▼              ▼              ▼              ▼
              ┌──────────────────────────────────────────────────────┐
              │       Redis（Sentinel / Cluster / Standalone）        │
              │  ┌──────────┐ ┌──────────┐ ┌──────────────────────┐ │
              │  │ Pub/Sub  │ │ Session  │ │  Streams（可选，at-   │ │
              │  │ Channels │ │ Registry │ │  least-once 投递）    │ │
              │  └──────────┘ └──────────┘ └──────────────────────┘ │
              └──────────────────────────────────────────────────────┘
```

底层 Redis 客户端直接使用 `io.lettuce:lettuce-core`（而非 `spring-boot-starter-data-redis`），以避免与用户已有 Spring Data Redis 配置冲突。详见 §模块结构。

## 深度瓶颈分析与容量模型（二次评审，含量化）

> 本节是 1.7.x 第二轮架构评审的量化结论：定位 Redis Pub/Sub 方案的根本扩展瓶颈，给出容量模型和明确的适用边界。Redis 数字为规划预算（单主 ~100k PUBLISH 投递/s、~80–120k 简单写 ops/s、单 Lettuce pub/sub 连接 ~80k msg/s 解码），**必须用 `redis-benchmark` / `pubsub-sub-bench` 在目标硬件实测校准**；定性结论不依赖精确值，节点数随实测值 ±30% 浮动。

### 扇出放大 `M·(f·N−1)` —— 核心墙

每条逻辑广播：本节点先本地 fan-out，再 `PUBLISH` 一次；Redis 把这条消息投递给频道上**每一个**订阅连接。设 N=节点数、f=订阅该 URI 的节点比例（S=f·N 个订阅连接）、M=该 URI 的**集群级**广播消息/s、B=单消息字节：

```
Redis PUBLISH 次数/s   = M
Redis 投递次数/s       = M·(S−1)  ≈ M·f·N      ← 墙在这里
Redis 出口字节/s       = M·(S−1)·B
Redis CPU             ∝ M·S（PUBLISH 是 O(订阅者数)）
```

聊天室：U 个用户每人 r msg/s、每条都广播 → M=U·r，Redis 投递负载 = **U·r·f·N**。关键在于 `M·f·N` 随用户和节点同时增长是**近平方级**——用户多→节点多→两个因子一起涨。

**容量表**（f=1 最坏情况、B=300B、单 Redis 主 100k 投递/s 预算、单节点 ~25k 连接、单 pub/sub 连接 80k 解码/s）：

| 目标连接 | 节点 | 该 URI 逻辑广播/s | 放大(N−1) | Redis 投递/s | 出口 | 单节点解码/s | 单 Redis 结论 |
|---|---|---|---|---|---|---|---|
| 75k | 3 | 10,000 | 2 | 20,000 | 6 MB/s | 10,000 | ✅ 单主轻松 |
| 75k | 3 | 50,000 | 2 | 100,000 | 30 MB/s | 50,000 | ⚠️ 单主临界 |
| 250k | 10 | 10,000 | 9 | 90,000 | 27 MB/s | 10,000 | ⚠️ Redis 撞墙 |
| 250k | 10 | 50,000 | 9 | 450,000 | 135 MB/s | 50,000 | ❌ 4.5× 超载，需 sharded pub/sub 或 mesh |
| 750k | 30 | 3,400 | 29 | ~99,000 | 30 MB/s | 3,400 | ⚠️ 近空闲房间也撞墙 |
| 750k | 30 | 20,000 | 29 | 580,000 | 174 MB/s | 20,000 | ❌ 需 mesh（sharded 救 CPU 但救不了解码） |

运维经验法则：

```
节点数      ≈ 目标连接 / 单节点连接容量（如 /25k）
Redis 投递/s ≈ 逻辑广播/s × (f·N − 1)
单主可行     当 Redis 投递/s ≲ 100k  且  单节点解码 ≲ 80k
sharded     当 Redis 投递/s > 100k  但单节点解码仍 ≲ 80k（仅 cluster 模式有效）
mesh        当单节点解码 > ~80k  或  出口接近 NIC(~0.9 GB/s 10GbE)  或  房间式 fan-out（目标受众 ≪ 集群）
```

### 节点数适用边界（与频道基数边界并列声明）

文档已声明"频道基数边界"（仅 `@MessageMapping` URI、≤1024）。**还须声明节点数边界**：本 Pub/Sub 方案对**活跃广播 URI 仅在 ≤~10 节点安全**；一旦热门 URI 出现在 15+ 节点集群，会同时撞 Redis CPU 墙和单连接解码墙。超出后路径：先上 sharded pub/sub + 多订阅连接，再上 node-mesh。

### TOP 瓶颈排序（含触发规模）

1. **扇出放大 `M·(f·N−1)`** —— 核心墙，活跃广播 **N≈8–12** 即撞，**N≈30 近空闲房间**也饱和。缓解：sharded pub/sub（仅救 CPU）→ mesh。
2. **单 Lettuce pub/sub 连接解码天花板（~80k msg/s/node）** —— **M≳80k 逻辑/s** 撞，**Redis 分片救不了**（每节点仍收全量）。缓解：每节点 2–4 个 pub/sub 连接、解码与反序列化移出 event loop。**1.9.x 已提供 opt-in 开关 `pubsub-connections`（默认 1）**：把 SUBSCRIBE 入站解码按频道哈希分散到 N 个 Lettuce 连接，逼近此墙时设 2–4。
3. **🔴 origin 自投递（正确性 bug，非性能）** —— 集群一开就触发：origin 既本地 fan-out 又收到自己的 PUBLISH，**本地用户收到重复消息**。必须用 `originNodeId` 抑制。低成本高严重度，见 §3。
4. **单播热路径 Redis HGET** —— 单播为主的负载 **≳100k 跨节点 DM/s** 撞，且每条 DM 加一次 Redis RTT。缓解：本地 `sessionId→nodeId` 缓存 + NODE_LEFT 失效，见 §2。
5. **Redis 出口 / NIC 饱和（~0.9 GB/s 10GbE）** —— N≈30 + ~20k 逻辑/s 即 174 MB/s。中心化 Redis 把带宽收口，mesh 把出口分散到各节点 NIC。
6. **部署/排空时 registry 写风暴（10k+ ops/s 尖峰）** —— 20+ 节点滚动部署触发；已被 pipeline 批量 + token-bucket 限速缓解，稳态 churn ~333 ops/s 可忽略。
7. **NATS JetStream 可靠投递的流/消费者基数（RC13）** —— 每个 URI 一条 JetStream 流，每个 (URI, 节点) 一个 durable 消费者；N 节点 × U 个 reliable URI = N·U 个消费者。运维建议**每 NATS cluster ≤1000 条 reliable 流**；超过则改为按 URI 前缀手动分流或迁移到 RC10 之后的能力扩展。FILE 存储下每次 publish 产生磁盘 I/O；`replicas=1` 默认意味着 clustered NATS 下 leader 故障 = backlog 丢失，HA 用户必须覆写 bean 设 `replicas≥3`。

## 核心设计

### 1. 集群发现与成员管理

推荐方案：Redis Heartbeat + Keyspace Notification。

**Redis Key 设计：**

```
netty:cluster:nodes              → Hash { nodeId → JSON(host, port, startTime, lastHeartbeat, sessionCount) }
netty:cluster:heartbeat:{nodeId} → String with TTL，节点持续续期
netty:cluster:control            → Pub/Sub channel（节点加入/离开/广播控制命令）
```

**故障检测：**
- 默认 `heartbeat-interval: 3s`，`heartbeat-timeout: 10s`（3× 间隔，标准 SWIM 安全余量）。最大可放宽到 30s，但**不作为默认**——实时 WebSocket 框架需要更激进的故障检测，否则跨节点单播在节点已死的窗口内会静默丢消息。
- Redis Keyspace Notification 监听 `heartbeat:{nodeId}` 过期事件。
- 需要更细粒度（< 3s）时可切到 ScaleCube SWIM。

**节点生命周期：** `JOINING → ACTIVE → DRAINING → LEFT`，在 Redis 重连恢复时插入额外的 `RESYNC` 状态，期间节点暂不接收跨节点消息，等基本状态重建完毕再切回 `ACTIVE`。

**设计注记（评审）：** 故障检测时延的代价是 Redis SET 操作 ~`N × 1/3s`，1000 节点也只有 333 ops/s，远低于 Redis 单主吞吐。这笔成本值得换取实时性。

### 2. 分布式 Session Registry

**Redis Key 设计：**

```
netty:session:{uri}:{sessionId}  → Hash { nodeId, userId, connectedAt, metadata }
netty:node:{nodeId}:sessions     → Set { sessionId1, sessionId2, ... }
netty:user:{userId}:sessions     → Set { "uri:sessionId@nodeId", ... }   (可选，按用户查询时启用)
```

**写放大与控制：**
- 每次 connect = HSET + SADD（可选 user-set SADD）；disconnect 为反向操作。100k 连接 × 5%/min 重连流失 ≈ 333 ops/s 持续基线；部署或 LB 排空时会突发到 10k+ ops/s 的尖峰。
- 默认对 connect/close 的写操作使用 Redis pipeline（`publish-batch-size` 默认 64、`publish-flush-interval` 默认 10ms），把突发尖峰拍平。
- **Lazy registry**（可选，默认开）：仅在该 session 第一次成为跨节点操作目标时才持久化到 Redis；纯本地会话不写 registry，避免无意义的写放大。
- 文档同时给出"单 Redis 主下的连接吞吐上限"参考值（如 ~80–120k ops/s 写路径），让运维能据此估算分片需求。

**会话路由（私聊 A→B）：**
1. 查 `netty:session:{uri}:{targetSessionId}` 获取 `targetNodeId`。
2. `targetNodeId == 本节点` → 本地发送。
3. 否则 → 发布到 `netty:cluster:unicast:{targetNodeId}`。
4. 若 `targetNodeId` 不在 `netty:cluster:nodes` 中或已 LEFT，**同步返回错误**而非静默发布——避免向不存在的接收方扔消息。

**单播热路径缓存（评审新增，TOP 瓶颈 #4）：** 单播为主的负载下，步骤 1 的 `HGET` 在**每条 DM 的发送关键路径**上，集群级 ≳100k 跨节点 DM/s 时 registry 读会先于广播成为 Redis 瓶颈、并给每条 DM 叠加一次 Redis RTT。缓解：本地维护 `sessionId→nodeId` 短 TTL 缓存（`cluster.registry.read-cache-ttl-ms` 默认 5000），收到 `NODE_LEFT` / 该 session 关闭事件时主动失效。命中率指标 `netty.cluster.registry.read.cache.hit-ratio`。注意缓存陈旧风险：目标 session 迁移后短时间内会路由到旧节点，旧节点查无此 session 时**回退到一次实时 HGET** 并刷新缓存，而非直接丢弃。

### 3. 跨节点消息路由

**广播 Pub/Sub：**

```
netty:broadcast:{uri}  → 每个 @MessageMapping URI 一个 Pub/Sub 频道
```

- **频道基数边界**：本基线**仅支持 `@MessageMapping` URI 作为广播频道**（典型规模 10–100 条）。聊天室 / 房间式 fan-out **不**为每个房间开一个 Pub/Sub 频道；`ClusterRoomRegistry`（**1.10.0 RC1 已落地**，`room.enable=true`）把房间路由建在已有的单节点单播通道之上——`roomMessage(uri, room, msg)` 只定向承载该房间成员的节点集合（扇出 N/k），既不新增订阅、也不放大频道基数。
- 节点只订阅自己持有 ≥1 个本地 session 的 URI；最后一个 session 离开后保留订阅 `cluster.subscription-hold-duration`（默认 60s），避免 0/1 session 频繁波动时 SUB/UNSUB 抖动。

**流程（含 origin 自投递抑制）：**
1. 本节点 fan-out（写入所有本地 session）。
2. 把消息封装为 envelope（含 `originNodeId`、`traceparent`、可选 `offset`），`PUBLISH` 到 `netty:broadcast:{uri}`。
3. 每个订阅节点收到后：**先判断 `envelope.originNodeId == 本节点 nodeId`，是则直接丢弃**（本地已在步骤 1 投递过），否则本地 fan-out。

> **🔴 self-delivery 抑制是正确性要求，不是优化（TOP 瓶颈 #3）。** Redis Pub/Sub 会把消息投递给**包括发布者自己在内**的所有订阅连接；origin 节点既有本地 session 又订阅了该频道，若不抑制，本地用户会**收到两次**（本地 fan-out 一次 + 自己的 PUBLISH 回环一次）。必须在 envelope 带 `originNodeId` 并在订阅回调里比对丢弃。指标 `netty.cluster.pubsub.self-dropped` 用于确认生效。这只消除重复投递，不减少 Redis 出口（Redis 仍会写一次 origin 的 socket）；要连这次写都省掉需走 mesh（见 §传输层 SPI）。

**Redis Cluster 模式用 sharded pub/sub（评审新增）：** 经典 `SUBSCRIBE` 在 Redis Cluster 下，每次 `PUBLISH` 会经 cluster bus 广播到**所有**节点——本"频道-per-URI"设计会为每个 URI 付全量 cluster-bus 广播税。`mode = cluster` 时默认改用 `SSUBSCRIBE`/`SPUBLISH`（Redis 7.0+，频道按 hash slot 归属，只到该 shard），可水平扩展 pub/sub 吞吐。约束：sharded 与经典 pub/sub 互相隔离、不支持模式订阅（本设计本就不用模式订阅）。`standalone`/`sentinel` 模式无收益（单 shard），保持经典 pub/sub。**版本门槛：Lettuce 对 sharded pub/sub 的自动重订阅在 6.4.x 有 bug，须 ≥ 6.5.5；启动期校验 Lettuce 版本，不达标时报错或回退经典并告警。**
>
> **RC7 实现状态（与上述设计目标的差距）：** RC7 落地的是 Redis Cluster **客户端**（`cluster-nodes` 选择，`RedisClusterModePubSubBroker` 等），其 broker 用的仍是**常规 cluster pub/sub（`SUBSCRIBE`/`PUBLISH`）**——即上文描述的"付全量 cluster-bus 广播税"的行为，**没有扇出削减**。上文的 `SSUBSCRIBE`/`SPUBLISH`（sharded，真正削减扇出）需 Lettuce 6.2+（Boot 2.7.18 管理 6.1.10），**推迟到 2.0.0（Boot 3.x）**。故 `M·(f·N−1)` 扇出墙在 RC7 下不变；RC7 的价值是 cluster 原生 HA 故障转移 + 注册表/心跳跨 slot 分布，不是吞吐扩展。

**订阅连接拓扑（评审新增，TOP 瓶颈 #2）：** Lettuce 单条 pub/sub 连接在 netty event loop 上解码全部入站消息，单连接 ~80k msg/s 即天花板，且与本节点 WebSocket I/O 抢同一组 event loop 线程。**每节点开 2–4 条 pub/sub 连接**（`cluster.pubsub-connections` 默认 2），按 URI 哈希分片到不同连接并行解码；解码后**立即移交业务线程池**，绝不在 pub/sub 回调里做反序列化或业务逻辑。registry 命令连接与 pub/sub 连接分离。指标 `netty.cluster.subscribe.decode.lag`（event loop 队列深度）让该天花板在熔断前可见。注意：**Redis 分片解决不了这个天花板**——每节点仍收热门 URI 的全量消息。

**至多一次 vs. 至少一次（offset + epoch 契约，评审强化）：**
- Redis Pub/Sub 是 fire-and-forget；**广播默认是 at-most-once**。`MessageSender.broadcast*` 在 Javadoc 中明确标注此契约，同时通过 `netty.cluster.pubsub.drops.unknown` + `.subscriber.disconnected` 计数让运维感知潜在丢失。
- 需要 at-least-once 时通过 Redis Streams：
  ```
  netty:stream:{uri}  → Redis Stream + consumer group
  ```
  API 层用 `DeliveryHint.atLeastOnce()` 或专用的 `messageSender.reliableBroadcast(...)` 显式切换；默认仍走 Pub/Sub。
- **投递契约采用 Centrifugo 的 `offset` + `epoch` 模型**（业界标准做法）：每频道单调递增 `offset`（即 Stream entry ID），外加一个 `epoch` token——当 stream 被 trim / 丢失（重启、过期）时 `epoch` 变更，使从陈旧 offset 恢复的消费者得到**明确的"无法恢复"信号**，而不是静默拿到带空洞的视图。再加**周期性 offset 同步**，让 pub/sub 层丢掉的一条消息能被*检测*到并从 history 回填。
- **重连从 last-consumed ID 续读**（Socket.IO Streams adapter 的关键能力）：可靠路径持久化每个 consumer 的 last-consumed Stream ID，重连时 `XREAD`/`XAUTOCLAIM` 从该 ID 续读，使可靠路径上的 Redis 抖动零丢失。仅有 Streams 入口而不续读 = 假可靠。

**慢订阅者保护：**
- Redis `client-output-buffer-limit pubsub` 在订阅者输出缓冲超限时强制断连。文档在"运维章节"明确要求至少配置 `pubsub 32mb 8mb 60`，并提供推荐值的容量计算公式。
- Lettuce 重连回调触发时，本节点 `cluster.state` 切到 `DEGRADED`，发出 `netty.cluster.pubsub.subscriber.disconnected` 事件，重新订阅时携带 marker 帮助故障定位。

**顺序保证（明确写入 Javadoc）：**
- **同一发布节点、同一频道**：所有订阅者按发布顺序看到（Redis 单主保证）。
- **跨发布节点**：不保证。需要全序时应用层加序列号 / 走 Streams。
- **本地 vs. 远端**：本节点本地 fan-out 与 Redis 发布之间没有跨边界同步——本地接收可能先于其他节点。文档给出聊天场景的示例和应用层重排的工作模式。

### 4. 弹性扩缩容

**Scale-out：**
1. 新节点 JOIN → 注册到 `netty:cluster:nodes` → 发布 JOIN 事件。
2. LB 将新连接路由到新节点，已有连接自然分布（无迁移）。

**Scale-in：**
1. 节点收到 DRAIN 信号 → 切到 `DRAINING`，从 LB 摘除。
2. 向所有 session 发送 `CloseFrame(1001, "Server going away")`。
3. 客户端按退避算法重连到健康节点（**需客户端实现**，文档要求示例化此契约）。
4. 等待 session 关闭或 `drain-timeout`（默认 60s）→ 注销 → 退出。

### 5. 自恢复 — Redis SPOF / 重连风暴

**核心承诺：单 Redis 主中断不会拖垮整个集群。**

- `cluster.on-redis-loss` 默认 `degrade-to-local`：Redis 失联时本节点切到 `DEGRADED`，本地 fan-out / 本地会话保持工作；跨节点广播暂停并记入 `netty.cluster.degraded.duration` 直方图；本地 session **不被断开**。`close-all`（即历史"保守策略"）改为显式 opt-in。
- `cluster.redis-loss-grace-period-ms` 默认 5000ms（1.9.0 落地）：在此窗口内不改变状态机，避免短暂网络抖动触发降级；broker `state()` 仍立刻反映真实连接状态。
- **Sentinel / Redis Cluster 一等支持**：Lettuce 原生支持，通过 `cluster.redis.mode = standalone | sentinel | cluster` 切换；文档同时给出 Spring Boot `spring.data.redis.*` 复用方式（用 `@ConditionalOnMissingBean` 让用户已有连接工厂优先）。

**节点故障恢复：**
1. 故障节点 heartbeat key TTL 过期。
2. 健康节点收到 Keyspace Notification。
3. 触发清理：删除该节点 session 记录，发布 `NODE_LEFT`。
4. 跨节点单播尝试投递到该 nodeId 时返回错误（见 §2）。

**⚠️ Keyspace Notification 不可靠，必须有对账兜底（评审新增）：** Redis Keyspace Notification 本质是 fire-and-forget pub/sub——**没有重放**。若某健康节点在故障节点 heartbeat key 过期的那一刻恰好与 Redis 断连（或自身 GC / event loop 拥塞错过），它将**永久错过该 `NODE_LEFT` 事件**，从而长期持有过期的成员视图、继续向死节点投递。因此故障检测**不能只依赖通知**：每个节点周期性（`cluster.reconciliation-interval-seconds` 默认 15）扫描 `netty:cluster:nodes`，对比各节点 `lastHeartbeat`，把超过 `heartbeat-timeout` 仍未续期的节点判定为 LEFT 并本地清理。通知是快路径、对账是慢兜底；两者幂等。指标 `netty.cluster.reconciliation.detected`（对账兜底捕获的、通知漏掉的故障数）——该值持续 > 0 说明通知链路有问题。

**分区下的广播静默丢失（评审新增）：** 部分分区时（A 能连 Redis、B 连不上），B 已 `degrade-to-local` 但 A 在 B 的 heartbeat 过期前仍认为 B 活着。A 的**单播**到 B 的 session 会因目标查不到而返回错误（§2 已覆盖）；但 A 的**广播**只是 `PUBLISH`，B 没在消费 → B 的本地用户**静默收不到**，直到 B 的 heartbeat 过期被判 LEFT。这是 at-most-once 契约的固有后果，文档须明示：广播的跨节点送达**不保证**，需可靠送达的用户走 `reliableBroadcast`（Streams + offset/epoch，B 恢复后可回放）。

**Redis 恢复时的雷击防护：**
- 所有节点恢复同步时携带 `jitter(0, cluster.reconnect-jitter-max)`（默认 10s），避免雷击。
- Session registry 重建走 token-bucket 限速（`session-registry-write-rate` 默认 1000 ops/s/节点，1.9.0 落地）。
- 订阅重建按 100 个 URI 一批 pipeline，避免连接被填满。
- 节点状态机增加 `RESYNC` 状态明确表达"恢复中"。

## 配置与 API

### API 契约（修正：不再承诺"接口完全不变"）

集群模式下 `MessageSender` 接口的本地查询语义保持不变，但**跨集群查询是新接口、且为异步形态**——本地是 O(1) map 查询，跨集群是 Redis 往返，混在同一同步签名里会让业务代码踩坑。

| 方法 | 语义 | 备注 |
|---|---|---|
| `getSessionIds(uri)` / `getSessions(uri)` | **本地节点** | 不变，热路径安全 |
| `isSessionAlive(uri, ids…)` | **本地节点**，非本地 id 返回 `false` | 不变，热路径安全 |
| `closeSession(uri, sessionId)` | ✅ 1.8.0 — 本地 sessionId 同步关闭，远端 sessionId 异步发布 CLOSE 意图后返回（fire-and-forget，不等 ACK） | Javadoc 明确语义 |
| `topicMessage(uri, msg)`（广播） | ✅ 1.8.0 — 本地 + 跨节点 fan-out，**at-most-once** | 本地永不丢；跨节点 at-most-once |
| `getClusterSessionIds(uri)` | ✅ 1.8.0 — 全集群 | `CompletionStage<Set<String>>` |
| `isSessionAliveCluster(uri, ids…)` | ✅ 1.8.0 — 全集群 | `CompletionStage<Boolean>` |
| `closeSessionCluster(uri, sessionId)`（等目标节点 ACK） | ⏳ 1.9.x（1.8.0 用上面 fire-and-forget 的 `closeSession`） | `CompletionStage<Boolean>` |
| `reliableBroadcast(uri, msg)` | ✅ 1.9.0 RC2（Streams，opt-in `reliable.enable=true`） | At-least-once 广播，保留窗口内。`reliable.enable=false` 时抛 `IllegalStateException`。 |

控制器代码：

```java
@Controller
public class ChatController {
    private final MessageSender messageSender;   // 启用集群后自动注入 ClusterMessageSender

    @MessageMapping(value = "/ws/chat", messageType = MessageType.TEXT_MESSAGE)
    public void onMessage(ChatMessage msg, MessageSession session) {
        // 本地 + 跨节点 fan-out（at-most-once；本地永不丢，跨节点不保证送达）
        messageSender.broadcastJson("/ws/chat", response);
        // 本地直发 / 跨节点路由（送不到的 session 会抛 MessageSessionClosedException）
        messageSender.sendJsonToSession("/ws/chat", pm, targetSessionId);
        // 需要 at-least-once（重放）的可靠广播：启用 reliable.enable=true 后调用 reliableBroadcast(...)
        // clusterMessageSender.reliableBroadcast("/ws/chat", response);
    }
}
```

### 配置参考

> 命名空间是 `server.netty.websocket.cluster.*`（集群是 WebSocket 的扩展能力）。

**当前可用配置（`ClusterProperties` 中真实存在且有效果，含 1.9.0 新增）：**

```yaml
server:
  netty:
    websocket:
      cluster:
        enable: false                      # 关闭或缺省 → 退化为 1.7.x 单机行为
        node-id: ${HOSTNAME:auto}          # 默认自动生成 UUID
        redis:
          uri: redis://localhost:6379      # 拓扑由 URI scheme 决定：
                                           #   redis://...           → standalone
                                           #   redis-sentinel://...  → sentinel（Lettuce 原生）
        heartbeat-interval-seconds: 3
        heartbeat-timeout-seconds: 10
        reconciliation-interval-seconds: 15 # keyspace 通知漏报的慢路径对账兜底（§5）
        drain-timeout-seconds: 60
        reconnect-jitter-max-seconds: 10    # DEGRADED→RESYNC 重注册前抖动，防重连风暴
        registry-read-cache-ttl-ms: 5000    # sessionId→nodeId 单播热路径缓存（§2 瓶颈 #4）
        command-timeout-ms: 2000            # Redis 命令超时,失联时界定热路径阻塞上限（vs Lettuce 默认 60s）
        message-max-size-bytes: 1048576     # 超限消息不发往集群（本地投递不受影响）
        on-redis-loss: degrade-to-local     # degrade-to-local（默认） | close-all
        on-publish-failure: log             # log（默认） | drop
        # --- 1.9.0 新增 ---
        redis-loss-grace-period-ms: 5000    # Redis 失联宽限期（ms）；0 = 立即降级（1.8.0 行为）
        session-registry-write-rate: 1000   # registry 写限速（ops/s/node）；0 = 不限速
```

**设计全集中尚未实现的键（仍在路线图，当前设置无效）：**
`redis.mode`、`redis.sharded-pubsub`、`pubsub-connections`、`publish-batch-size`、
`publish-flush-interval-ms`、`max-subscribed-channels`、`subscription-hold-duration-seconds`、
`compression`、`unicast-buffer-size`。详见本文档顶部"实现范围"表。

### 命名空间一致性

集群子键统一使用 `enable`（与 `Mvc.enable` / `WebSocket.enable` / `Crypto.enable` / `Ssl.enable` / `Gzip.enable` / `Management.enable` 一致），不使用 `enabled`。

## 可观测性（设计草案 — 实际落地指标见下方说明）

> **⚠️ 本节是早期设计草案的指标命名，与实际发布的指标不一致，请勿据此搭建仪表盘。**
> 1.9.0 RC4 实际落地的 Micrometer 指标由 `NettyClusterMeterBinder` 注册（11 个 counter +
> `netty.cluster.node.state` / `netty.cluster.broker.state` 两个按 `state` 标签的 gauge，聚合粒度、无 per-URI 标签）。
> **权威清单见 `docs/api-guide.md` §9「Cluster metrics」与 `docs/release-notes-1.9.0.md` §⑫**。
> 下面这组名字是设计阶段的设想（部分从未实现，部分最终改名），仅留作设计意图记录：

- `netty.cluster.nodes.active`（Gauge）/ `.state`（Gauge：ACTIVE/DEGRADED/JOINING/DRAINING）
- `netty.cluster.broadcast.published`（Counter，按 `uri` 标签，受 §3.A-1 上限约束）
- `netty.cluster.unicast.routed.cross-node` / `.local` / `.unknown-target`（Counter）
- `netty.cluster.pubsub.drops.unknown`（Counter）/ `netty.cluster.pubsub.subscriber.disconnected`（Counter）
- `netty.cluster.pubsub.self-dropped`（Counter，确认 origin 自投递抑制生效，§3 瓶颈 #3）
- `netty.cluster.subscribe.decode.lag`（Gauge，pub/sub event loop 队列深度，§3 瓶颈 #2 预警）
- `netty.cluster.registry.read.cache.hit-ratio`（Gauge，单播缓存命中率，§2 瓶颈 #4）
- `netty.cluster.reconciliation.detected`（Counter，对账兜底捕获的、keyspace 通知漏掉的故障数；持续 > 0 = 通知链路有问题，§5）
- `netty.cluster.degraded.duration`（Timer）
- `netty.cluster.publish.latency`（Timer）/ `netty.cluster.subscribe.latency`（Timer）
- `netty.cluster.registry.writes` / `.reads`（Counter）

**分布式追踪**：W3C TraceContext (`traceparent`) 注入 Redis Pub/Sub 信封头，订阅侧抽出并恢复 SLF4J MDC + Micrometer Observation Scope。跨节点首跳即可串起 trace。`1.8.0` 必做，详见 development-plan 第二刀。

## 传输层抽象（Transport SPI）—— 评审最高优先级结构决策

> 容量分析（§深度瓶颈）的结论是：Redis Pub/Sub 在 ~10 节点 / 活跃广播即撞墙，终局扩展路径是 **node-to-node mesh**（Slack 即全 Java 的 CS/GS mesh，Redis/Consul 只做发现+注册、**不在消息热路径**）或 **NATS 的兴趣路由**（只发给有订阅者的节点，天然无 N× 放大）。这意味着 1.8.0 **必须在 API 层把"传输"和"注册表"抽象成 SPI**，否则 Redis Pub/Sub 的实现细节会渗进 `MessageSender`，将来换 mesh/NATS 就是破坏性大改。**这是"现在不做、以后改不动"的决策——成本只是 1.8.0 多定义两个接口。**

借鉴 Centrifugo 的 `Broker` / `PresenceManager` 拆分：

```java
// 跨节点消息传输：fan-out 广播 + 单播。1.8.0 唯一实现是 Redis Pub/Sub。
public interface ClusterBroker {
    void publish(String uri, ClusterEnvelope envelope);              // 广播（含 originNodeId）
    void unicast(String targetNodeId, ClusterEnvelope envelope);     // 单播
    AutoCloseable subscribe(String uri, ClusterMessageListener l);   // 订阅（按 URI 分片到多连接）
    BrokerState state();                                             // ACTIVE / DEGRADED
}

// 会话发现 / 路由：presence + sessionId→nodeId。可与 Broker 独立选型。
public interface SessionRegistry {
    CompletionStage<Void> register(String uri, String sessionId, String nodeId, Map<String,String> meta);
    CompletionStage<Void> deregister(String uri, String sessionId);
    CompletionStage<String> lookupNode(String uri, String sessionId); // 带本地缓存（§2）
    CompletionStage<Set<String>> sessionIds(String uri);
}
```

- **1.8.0 实现**：`RedisPubSubBroker` + `RedisSessionRegistry`（Lettuce）。`ClusterMessageSender` 只依赖这两个 SPI，不直接碰 Lettuce。
- **已实现 / 未来实现（无 API 破坏）**：✅ `MeshBroker`（**1.10.0 RC4a 落地**——Netty TCP 直连，registry/心跳仍在 Redis 做发现；RC4a 是传输地基[直连 unicast + 朴素广播]，扇出削减=RC4b 按兴趣路由）、✅ `NatsClusterBroker`（1.9.0 RC9，兴趣路由）。用户也可混搭（如 mesh broker + Redis registry）。
- **装配**：两个 SPI 都用 `@ConditionalOnMissingBean`，默认 Redis 实现，高级用户可覆盖。
- **演进锚点**：§深度瓶颈的"mesh 切换点"（单节点解码 >80k、或房间式 fan-out、或 N≳15 活跃广播）就是上 `MeshBroker` 的触发条件——届时业务代码零改动。

**1.9.x 第一个 mesh 步**：单播（A→B 私聊）当前是 Redis 中继（`unicast:{nodeId}` 频道）。registry 已经知道"在哪个节点"，把单播改成**直接 node→node 发送**（registry 只做发现），即可把 Redis 移出单播热路径——这是 Slack 模式的最小落地，也是 TOP 瓶颈 #4 的终极解。

## ADR-001：集群中间件选型 —— Redis-first，NATS 后补，不自研、不引 Go/Rust 中间件

> 状态：**已采纳（2026-05-29）**。本节固化决策与理由，避免后续（尤其有人提议"用 Go/Rust 重写中间件"时）重复争论。

**背景：** 1.8.0 需要跨节点消息传输。§深度瓶颈量化了 Redis Pub/Sub 的天花板（扇出放大 `M·(f·N−1)`、单连接解码 ~80k msg/s、活跃广播 ≤~10 节点）。自然会问：既然 Redis 有上限、迟早要换 NATS/mesh，为何不一次做对、先上 Redis 是不是重复开发？

**决策：**

1. **1.8.0 先落 Redis**（`RedisPubSubBroker` + `RedisSessionRegistry`），置于 `ClusterBroker` / `SessionRegistry` SPI 之后。
2. **NATS 作为"规模化档位"在 1.9.x 以 `NatsBroker` 形式追加**——是**新增实现**，不是替换；Redis 实现长期保留服务小集群。
3. **不自研通用 broker/消息总线**（任何语言）；**不引入自研 Go/Rust 中间件进程**。要"Go 级中间件性能"直接 **adopt 开源的 NATS**（Apache-2.0、CNCF、兴趣路由天然无 N× 放大），不自己造。

**为什么这不是重复开发（驳"先 Redis 再换=浪费"）：**

- 有 SPI 后是**加实现**不是**换实现**：集群层 ~80% 是 transport-agnostic 逻辑（SPI、`ClusterMessageSender` 集成、envelope/trace/序列化、self-delivery 抑制、失败状态机、背压/顺序/投递语义、指标、demo、测试），**只写一遍**被所有 transport 复用；Redis 专属适配器仅占 ~15–20%。真正的重复开发是"没有抽象、每个 transport 重写一遍"——SPI 正是用来杜绝它。
- Redis 与 NATS 服务**不同档位、并存**，不是"差的"被"好的"取代（类比：框架同时支持内存缓存和 Redis 缓存，没人说前者是浪费）。
- 先在最简单 transport 上验证抽象与失败模式（简单模式），再上复杂 transport，低风险。

**被拒方案及复议条件：**

| 方案 | 拒绝理由 | 何时复议 |
|---|---|---|
| NATS-first（默认强制 NATS） | 抬高所有用户接入门槛（人人要装 NATS）；多数目标用户（≤10 节点）到不了 Redis 天花板（YAGNI）。**注：** NATS-**only** 已于 1.9.0 RC10 作为 **opt-in** 落地（`nats.registry=true` → JetStream KV 跑 registry/心跳/reaper）；mixed（NATS broker + Redis registry）与 all-Redis 仍为默认；registry 从来不是扩展瓶颈，故 all-NATS 属于"运维形态"而非"性能档位"，且需 JetStream NATS（`nats-server -js`） | 若把 NATS-only 设为默认/强制 |
| mesh-only 自研 | 工程量最大、首版最慢；多数用户用不到 | 单节点解码 >80k 或要彻底去中心依赖 |
| 自研 Go/Rust 中间件 | 等于做第二个产品（独立 roadmap/运维/安全/语言生态）；单人维护不可持续；NATS 已覆盖该需求 | 仅当要做 Centrifugo 级"实时平台"且有持续维护力量 |

**后果：** Redis 实现长期保留（小集群档位）；NATS 是有证据表明用户撞墙后的规模化档位；因 SPI，档位切换对业务零改动、决策可逆（成本只是薄适配器）。**节点本身永远是 Java——中间件语言无关，节点只作客户端对接。**

## 模块结构（规划）

```
netty-spring/
├── netty-spring-web
├── netty-spring-webmvc
├── netty-spring-websocket
├── netty-spring-websocket-cluster              [NEW]  Redis 通信、Session Registry、跨节点路由
├── netty-spring-boot-autoconfigure
├── netty-web-spring-boot-starter
├── netty-webmvc-spring-boot-starter
├── netty-websocket-spring-boot-starter
├── netty-websocket-cluster-spring-boot-starter [NEW]
└── demo-netty-web-spring-boot-starter
```

依赖选择：

- 直接依赖 `io.lettuce:lettuce-core`（不是 `spring-boot-starter-data-redis`），避免引入 RedisTemplate / repository / keyvalue 等不必要的抽象，也避免与用户已有 Spring Data Redis 配置共用连接产生意外行为。
- 提供 `@ConditionalOnMissingBean LettuceConnectionFactory`，允许用户用 Spring Data Redis 的 `spring.data.redis.*` 复用配置；连接通过 `@Qualifier("nettyClusterRedisConnection")` 隔离。

## 兼容性承诺

- **API 兼容**：现有 `@MessageMapping` 控制器零迁移；本地查询接口语义不变；跨集群操作通过**新增**异步接口提供，避免对 1.7.x 用户产生隐式语义变化。
- **配置兼容**：`server.netty.websocket.cluster.enable` 默认 `false`；未启用时所有集群依赖不参与运行时路径，行为与 `1.7.x` 完全一致。
- **回退路径**：集群启用后如需回退，关闭 `cluster.enable` 即可，无需修改业务代码或数据迁移（在线 session 自然过期）。
- **Micrometer 最低版本**：与主线一致（见根 README "兼容性"）；集群指标在缺失 `micrometer-core` 时自动跳过。
- **Lettuce 版本**：跟随 Spring Boot BOM；如有特定漏洞需要覆盖，通过 `dependencyManagement` 显式 pin。

## 安全模型（Security）— 必读

> **Redis 是集群的"控制平面"，1.8.0 对它是完全信任的。** 任何能向集群 Redis `PUBLISH` 的主体，都能向任意 WebSocket 会话注入消息、或强制关闭任意会话（`CLOSE` 控制指令无授权校验，`originNodeId` 是明文字段、可伪造，自投递抑制可被绕过）。这是 Pub/Sub 扇出架构的固有信任假设。生产部署**必须**据此加固：

- **专用、网络隔离的 Redis**：集群 Redis 不要与其他应用共用实例；用防火墙/安全组限制只有集群节点能连。
- **认证**：使用 `requirepass`，URI 形如 `redis://:password@host:6379`（1.8.0 在检测到无密码/无 TLS 时会打 `WARN`；日志中 URI 的密码部分已脱敏）。
- **TLS**：跨网络时用 `rediss://host:6379`（Lettuce 原生支持）。
- **入站大小上限**：1.8.0 在接收端对单条消息有大小上限（由 `message-max-size-bytes` 的 2 倍推导），防止恶意 peer 发超大消息打爆订阅节点内存。

**加密边界提示**：应用层 AES-GCM 加密作用在浏览器↔节点的 WebSocket 帧上。集群广播/单播把**解密后的明文** `AbstractMessage` 放上 Redis 再扇出到远端会话——所以"端到端加密"的保证**不延伸过 Redis**。需要跨集群也保密时，依赖 `rediss://` + 专用 Redis 的网络层保护。

**1.9.0 RC3 纵深防御（已落地）**：`MessageAuthenticator` SPI，默认实现 `HmacMessageAuthenticator`（HMAC-SHA256）。接收端对缺失或不匹配的标签直接丢弃消息（计数 + 日志），杜绝 `originNodeId` 伪造与未授权 `CLOSE`/注入。默认关闭（`auth.enable=false`），通过三阶段滚动升级可零停机开启（见 `docs/api-guide.md` §9.2）。**仅防伪造，不防重放**（重放需 Redis 读权限，属更强前提；时间窗口与 reliable replay-on-resync 冲突）——该限制已文档化。

**1.9.0 RC13 NATS JetStream 可靠投递的传输安全（已落地）**：`NatsJetStreamReliableBroker` 复用 RC9/RC10 的 `nettyClusterNatsKvConnection` 连接，TLS/凭证继承自 `warnIfInsecureNats` 既有路径；**无新增认证路径**。HMAC 在 broker **内部** wrap/unwrap（与 Redis Streams 实现一致），envelope 与 publish/fetch 之间。**威胁模型 PUBLISH-ACL 劫持**：能向 JetStream 流 `PUBLISH` 的主体可注入跨节点广播；**缓解措施**——将 NATS PUBLISH ACL 限制在权威应用节点（NATS account/permissions）；监控流 stats（异常 publish rate 反映非授权写入）。与 Redis Streams 同类威胁同形，详见 release-notes §⑱。

**🔴 1.10.0 RC2 身份与离线队列安全（必读）**：离线队列、在线状态、按用户投递都以 `UserIdResolver` 返回的 `userId` 为键——**错误的身份 = 跨用户数据泄露**（读取他人排队消息、冒充其在线状态、劫持其投递）。这与上面"Redis 是受信任控制平面"的诚实一脉相承：**框架提供机制，运维必须保障身份**。`UserIdResolver` SPI 的 javadoc 携带**安全契约**：返回的 userId 必须派生自会话的**已认证**主体（验签 JWT `sub`、OAuth、SAML NameID），绝不能是客户端可控的原始值。默认实现 `HandshakeUserIdResolver` 逐字读取 `query:userId` / `header:X-User-Id`，**明确仅供便利/测试**（`?userId=bob` 即被当作 `bob`）——**生产 IM 必须提供自己的 `UserIdResolver` `@Bean`**（通常由 `WebSocketHandshakeInterceptor` 认证连接、解析器读取已验证主体）。自动装配仅在 `@ConditionalOnMissingBean` 下注册默认实现；`offline.enable=false`（默认）时解析器永不被调用——无任何身份面。

## 已知未覆盖项（明确推迟）

- **Kubernetes Operator / Helm Chart**：运维侧专项，不在 `1.8.0` 范围。
- **Redis Streams 完整生命周期**（消费者重平衡、死信队列）：`1.8.0` 仅提供基础至少一次入口，完整治理推迟到 `1.9.x`。
- **跨 DC 多 Redis**：本设计假设单 Redis 集群可用区；多活 / 跨 DC 复制方案在 `2.x` 评估。
- **持久化 / 离线消息**：通过 Streams 提供原语，但消息存储 / 拉取 API 不在 `1.8.0`。

### 集群已知限制与硬化状态

集群适用于 **≤~10 节点、配专用且加密的 Redis** 的场景。

**✅ 1.9.0 已完成的硬化项（原 1.8.0 推迟项）：**

- ✅ **Redis 失联宽限期**（`redis-loss-grace-period-ms`）：亚秒级 Redis 抖动不再触发状态机降级；`0` 恢复 1.8.0 即时行为。
- ✅ **心跳 / 对账线程隔离 + 批量 EXISTS**：两个独立调度器（`cluster-hb` / `cluster-recon`），慢扫描不再饿死心跳；`findExpiredNodes` 改管道批量。
- ✅ **`deregister` 原子性**：改为 Lua `EVAL`（HGET→DEL→SREM 单事务），消除并发竞态理论路径。
- ✅ **reconciliation 去重 / 选主**：`ClusterReaper` SPI，`RedisClusterReaper` 用 `SET NX` 认领清理权，死节点只被一个节点清理。
- ✅ **registry 限速**（`session-registry-write-rate`）：token-bucket `CoalescingRegistryWriter`，防注册风暴；register 永不丢弃。
- ✅ **可靠投递（Redis Streams `reliableBroadcast`）**（RC2）：per-URI Redis Stream `netty:cluster:rstream:{uri}`；每节点一个消费者组（`g:{nodeId}`）；断线重连自动 replay-on-resync；`MAXLEN ~` 有界保留；进程内 PEL 去重；origin 自投递抑制；死节点消费者组随 `ClusterReaper` 清理。详见 §3 可靠投递设计注记。
- ✅ **HMAC envelope 认证**（RC3）：传输层 `MessageAuthenticator` SPI，默认实现 `HmacMessageAuthenticator`（HMAC-SHA256，常量时间比较）；线格式 `H1:{base64url(hmac)}:{payload}`；广播/单播/CLOSE/Streams 路径统一签名；`auth.*` 3 个配置项（`enable`、`secret`、`permissive`）；默认关闭（`auth.enable=false`），零额外开销；NoOp 剥离 `H1:` 标签以兼容混合滚动期；三阶段零停机滚动升级；**仅防伪造（anti-forgery）**，不防重放（详见 `docs/api-guide.md` §9.2）。
- ✅ **完整 Micrometer 指标集（meter-binder）**（RC4）：`NettyClusterMeterBinder` 把程序内 `ClusterRuntimeStats` 计数器 + 节点/broker 状态 + HMAC 拒绝计数桥接为 `netty.cluster.*` 时序——11 个 counter（broadcast published/received/self_dropped/skipped_degraded、unicast sent、publish failures、reliable published/received、cache hits/misses、auth rejected）+ 按 `state` 标签的 `netty.cluster.node.state`（6 态）/`netty.cluster.broker.state`（4 态）gauge。沿用 1.7.0 的 `MeterBinder` 模式（`FunctionCounter`/`Gauge` 只读直通，无热路径开销）；**聚合粒度**（无 per-URI/per-session 标签，基数有界）；`@ConditionalOnClass(MeterRegistry)` + `@ConditionalOnBean` 门控，缺 `micrometer-core` 或 `cluster.enable=false` 时零注册。与既有 `ClusterHealthIndicator`（point-in-time）互补。
- ✅ **多节点 E2E + Testcontainers CI + 跨节点单播修复**（RC5）：`ClusterTestRedis` 解析器（localhost-first → Testcontainers `redis:7-alpine` 回退）让集群集成测试在 CI 真实运行；`ClusterMultiNodeE2ETest` 进程内双节点全栈 E2E 证明跨节点广播 + 单播 + 指标（HMAC 开启）。**该 E2E 暴露并修复了一个高严重度缺陷**：自动装配下 `ClusterSessionHook` 因装配顺序（server 急切启动早于 `@AutoConfigureAfter` 的 hook bean）从未挂到 resolver，分布式注册表为空 → 跨节点单播/定向关闭静默失效（影响 1.8.0 ~ RC4，广播不受影响）。修复用 `SmartInitializingSingleton` 在单例全部就绪后挂 hook；E2E 单播断言为永久回归门。
- ✅ **W3C TraceContext 跨节点传播（MDC 日志关联）**（RC6）：`ClusterTraceContext` SPI（默认 `MdcClusterTraceContext`，零依赖、tracer 无关）在发送侧把当前 `traceparent`（显式 MDC 键或从 `traceId`/`spanId` 合成）写入信封，在接收侧恢复进 MDC（`traceId`/`spanId`/`netty.traceparent`），使跨节点投递日志带同一 `traceId`。opt-in（`trace-propagation.enable`，默认关）；无线格式变更（信封早已携带 `traceparent`）。Micrometer Observation 活跃 span 续接推迟到 2.0.0（Boot 2.7 的 Micrometer 1.9 无 Observation API）。
- ✅ **Redis Cluster 客户端一等支持（客户端层）**（RC7）：新增 `cluster.redis.cluster-nodes`（逗号分隔 `host:port` 种子节点）选择 Redis Cluster 传输（`RedisClusterClient` + 4 个 `RedisClusterMode*` 实现，皆 `@ConditionalOnMissingBean`），让注册表/心跳跨 slot 分布并获得 cluster 原生 HA 故障转移；slot 安全增量：`deregister` 非原子（三条各自路由的 async，非跨 slot Lua）、`clusterSessionIds` 用 cluster-aware SCAN 跨 master 扇出、心跳判定用逐键 EXISTS、reaper 单键 `SET NX PX`。**`cluster-nodes` 空（默认）时与 RC6 字节级一致**。**⚠️ 仅客户端层——broker 用常规 cluster pub/sub（`SUBSCRIBE`/`PUBLISH`），仍向所有节点传播每条广播，不削减扇出**；扇出削减需 sharded pub/sub（Lettuce 6.2+ → 2.0.0）。限制：`cluster-nodes` 无法表达 TLS/密码（受保护 cluster 自备 `RedisClusterClient` bean）；reliable.* 与 cluster-nodes 在 RC7 互斥；连接配 `validateClusterNodeMembership(false)` + 关周期拓扑刷新（容器/NAT 端口映射友好）。验证范围：单节点 Redis Cluster（Testcontainers `redis:7 --cluster-enabled`，16384 slot 全在单节点），证明 `RedisClusterClient` API 路径端到端；多节点 slot 分布/跨节点 pub/sub 传播为未来项。

**⏳ 仍推迟到后续版本的项：**

- **多 pub/sub 连接并行解码 / 写 pipeline 批量**：规模化档位优化，见实现范围表。
- **sharded pub/sub（广播扇出削减，`SSUBSCRIBE`/`SPUBLISH`）**：需 Lettuce 6.2+（Boot 2.7.18 为 6.1.10），推迟到 2.0.0。注：Redis Cluster **客户端**已在 RC7 落地，但 RC7 的常规 cluster pub/sub **不**削减广播扇出——`M·(f·N−1)` 扇出墙在 RC7 下不变；削减来自这里的 sharded pub/sub。
- **NATS broker**（ADR-001 规模化档位）：`NatsBroker` SPI 实现，消除 N× 扇出放大。
- **W3C TraceContext 的 Micrometer Observation / 活跃 span 续接**：`traceparent` 传播 + MDC 关联已在 RC6 落地；让真实 tracer 在接收节点续接活跃 span 需 Micrometer 1.10+（Boot 3.x），推迟到 2.0.0。`tracestate` 传播亦推迟（需新增信封字段）。
- **可运行的多节点 Docker 示例（Compose + 负载均衡 + 浏览器）**（Testcontainers CI + 进程内双节点 E2E 已在 RC5 落地）。

### 可靠投递设计注记（1.9.0 RC2）

`reliableBroadcast(uri, message)` 提供 at-least-once 广播，以 Redis Streams 为存储原语。关键设计点：

- **每 URI 一条 Stream**（`netty:cluster:rstream:{uri}`），`MAXLEN ~` 近似裁剪（`stream-max-len`，默认 10 000 条）。
- **每节点一个消费者组**（`g:{nodeId}`）：节点在线时持续推进游标；节点下线时游标冻结；重连后 `XREADGROUP >` 从上次位置续读，无需应用层介入——即 replay-on-resync。
- **专用阻塞 Lettuce 连接**：消费循环与 Pub/Sub 连接、命令连接完全隔离，不抢占 event loop。
- **进程内 PEL 去重**（`dedup-window` 滑动窗口，默认 1 024）：覆盖跨重连的重复条目，不覆盖跨进程崩溃重复（调用方负责幂等）。
- **Origin 自投递抑制**：消费侧比对 `originNodeId`，本节点发布的条目不重复 fan-out。
- **保留窗口有界缺口**：节点离线时间超过 `stream-max-len` 条目对应的时间窗口，被 `MAXLEN ~` 裁剪的条目不可回放（bounded gap）。这是 at-least-once 在有界保留下的固有契约，不同于无限重放语义。
- **默认关闭**（`reliable.enable=false`）：无额外连接/线程，`reliableBroadcast()` 抛 `IllegalStateException`。启用后仅影响 `reliableBroadcast()` 调用路径；现有 `topicMessage()`（Pub/Sub）完全不变。

### 离线队列 + 按用户寻址投递设计注记（1.10.0 RC2）

`sendToUser(userId, message)` 提供按稳定用户身份的投递：在线则实时单播，离线（集群范围内零会话）则存储并在重连时 FIFO 回填。与可靠*广播*（1.9.0 RC2，向短暂掉线的**节点**重放）正交——它面向离线**用户**。关键设计点：

- **稳定身份来自握手**：`UserIdResolver` SPI 从握手提取 `userId`（默认 `HandshakeUserIdResolver` 读 `query`/`header`，**仅供测试**——生产须自备认证解析器，见 §安全模型身份注记）。`userId` 流入 register 元数据 + 新的 `UserRegistry`（`userId → sessions` 反向索引）。
- **`UserRegistry` 是派生的路由/在线索引，不是 `SessionRegistry` 的耐久副本**：选独立 SPI 而非给 `SessionRegistry` 加方法，是为了让 1.9.0 的 `SessionRegistry` 签名及其 3 个实现不变（设计评审 Option B）。经 `removeAllForNode` 对账。RC3 多设备在线状态扩展同一 SPI。
- **`sessionsForUser` 永不缓存**（离线检测正确性决策）：缓存在线状态会让刚断开的用户读到「在线」 → 对死会话发后即忘单播 → 无异常 → 无兜底 → **静默丢失**。反向查询每次命中 Redis（位于相对冷的 `sendToUser` 路径）。
- **每用户离线流 + 每 userId drain 锁**：`netty:offline:{b64userId}` 是 HMAC 包裹信封的 Redis Stream（`MAXLEN ~ max-messages-per-user`，与可靠路径一致的写时包裹；入队时还按 `ttl-seconds` 刷新流键 PTTL，使无人重连的弃用队列自动回收）。`drain()` 先 `SET netty:offline-lock:{b64userId} {node} NX PX drain-lock-ms`——未获锁（并发多设备重连）则返回空且**不动锁**（非本节点所有），获锁则 `XRANGE - + COUNT drain-batch-size` 读取最旧的一批（其余在下次连接 drain）并 FIFO 返回（超 TTL / 损坏条目在本次 drain 内一并 `XDEL` 回收，不再被反复读取）；若本批可投递结果为空则当场用 **compare-and-DEL** 释放锁，非空则保持持锁交由调用方投递后 `delete()`（XDEL 已投递 id + compare-and-DEL 释放锁）。锁释放是基于本节点 id 的 Lua compare-and-DEL，绝不会误删本节点锁过期后被其他设备重新获取的同名锁。**该锁消除确定性多设备重复投递**（否则两设备都会在任一方删除前 `XRANGE` 整条流——必然重复），由 `MultiDeviceDrainLockTest` 回归门守护。
- **连接时回填（bind→drain 窗口）**：钩子在 `bindUser` 完成**后**再 drain，使 bind→drain 窗口内入队的消息仍在 drain 读到流尾的范围内（被回填，不丢不重）；窗口后到达的消息直接实时投递到已在线会话。
- **仅发送时边界（诚实）**：`broker.unicast()` 发后即忘，离线队列只兜底**发送时**失败（零可达会话或本地 `MessageSessionClosedException`）；远程会话在受理后、收帧前关闭不入队（指标 `offline.unicast_failures`），精确一次超出范围，处理器靠幂等对账。
- **有界保留 + 至少一次**：`max-messages-per-user`（默认 1000）/`ttl-seconds`（默认 7 天）之外裁剪（有界缺口）。**TTL 丢弃路径**（超过 `ttl-seconds`、drain 时清理的条目）计入指标 `dropped_retention`；服务端 `MAXLEN ~` 裁剪由 Redis 在 `XADD` 时执行，不单独计量。drain 投递后删除；删除失败下次重投——处理器须幂等（携带 `X-Offline-Message-Id` 便于去重）。
- **离线 = 集群范围内零会话**：多设备用户有任一在线会话即「在线」，投递到在线设备；按设备离线回填随按设备**寻址**一并推迟到后续 RC（RC3 多设备在线状态交付的是聚合，不含按设备寻址）。
- **默认关闭**（`offline.enable=false`）：无离线 Bean、不解析 userId、钩子向 register 传 `emptyMap()`，与 RC1 逐字节一致。仅 Redis（NATS-KV 离线存储是后续 RC）。

### 多设备在线状态设计注记（1.10.0 RC3）

RC3 在 RC2 身份之上新增**按用户的聚合在线状态** + **实时变更事件**。交付的是*聚合*（多设备**感知**）；按设备**寻址**被记录推迟（需稳定 `DeviceIdResolver`，属单向门，今天唯一能合成的 `nodeId|sessionId` 是临时且泄漏拓扑的）——因此公开 API 暴露**聚合 + 按状态连接计数**，绝不泄漏设备映射。关键设计点：

- **新 `PresenceRegistry` SPI（仅 Redis，与 RC2 `UserRegistry` 平行，而非挂在其上）**：Redis hash `netty:presence:{b64userId}`（hash-tag，与 `netty:user:{b64userId}` 同槽）；字段 `nodeId|sessionId`（nodeId 在前以便回收前缀匹配），值为 `ONLINE`/`AWAY`。`OFFLINE` 是推导的聚合（零连接），从不作为存储状态。
- **原子 Lua 转换检测（正确性核心）**：`setPresence`/`setPresenceForUser`/`clearPresence`/`removeAllForNode` 各为**一次 `EVAL`**——读旧聚合（`HVALS`）→ 变更 → 重算新聚合 → 返回 `(old,new)`。Lua 在单槽上串行化跨节点并发，所以两个同时首连只产生**恰好一次** `OFFLINE→ONLINE`（第二个看到 `old==ONLINE`）。
- **专用保留通道 + 新 `PRESENCE_CHANGE` 类型**：在线状态事件走专用通道（`ClusterMessageSender.PRESENCE_CHANNEL`），**不**走广播 topic 路径（否则 `onBroadcastMessage` 会把在线状态信封误派为应用消息）；专用 `onPresenceMessage` 监听器在 `start()` 无条件订阅（零本地会话节点也收）；源自抑制（本地直触发 + 为他节点发布，源丢自身回声）。新 `MessageKind.PRESENCE_CHANGE` 追加，**不升信封版本**（`CURRENT_VERSION` 保持 2），滚动升级安全。自动装配启动时对碰撞保留名的 `@MessageMapping` URI 快速失败。
- **对账回收是主导崩溃路径（BLOCKER）**：硬崩溃从不调 `clearPresence`，所以 `→OFFLINE` 的权威来源是死节点回收。`presenceRegistry.removeAllForNode(dead)`（转换感知 Lua）为每个聚合变化用户返回一个 `PresenceTransition`，**leader 选举**的回收节点为每个变化用户发布一个 `PRESENCE_CHANGE`。
- **🐛 同时闭合潜伏的 RC2 死节点用户绑定泄漏（对账缺口修复）**：RC2 的 `UserRegistry.removeAllForNode` 虽实现但**从未接入**死节点扇出——崩溃节点的 `netty:user:*` 绑定永久泄漏（假 ONLINE → `sendToUser` 即发即忘到死会话 → 静默丢失）。RC3 把 `userRegistry` + `presenceRegistry` 的回收接到 `ClusterNodeManager.doReconciliation` 的 **leader 选举主路径**上（与 `sessionRegistry.removeAllForNode` 同一条带 `.exceptionally` 重试的链，失败重新排队下次扫描——不是吞异常的尽力 `deadNodeCallback`）。`UserRegistry` 那句「经 removeAllForNode 对账」自此才真正成立，由 `UserRegistryReapRegressionIT` 端到端守护。
- **建议性读取（陈旧 ONLINE 窗口）**：`getPresence` 不缓存，返回最后已知状态——**不是存活探针**。崩溃后死连接保持 ONLINE 直到对账回收（上界 `heartbeatTimeoutMs + reconciliationIntervalMs + leader 抢占 + SCAN`，默认约 25s）；延迟敏感者视其为建议性，正确性依赖 `sendToUser` 投递时的新鲜查找 + 离线兜底。回收发出的 `→OFFLINE` 是权威自愈修正。
- **广播上限**：在线状态事件是广播——与 `topicMessage` 同的 ~10 节点 Pub/Sub 上限；大规模需 RC4 mesh。Lua 抑制同状态无操作重设，去抖超此为应用职责。
- **默认关闭**（`presence.enable=false`）：与 `offline.enable=false` 组合 → 身份关闭 → `register(emptyMap())`，与 RC1/RC2 逐字节一致。仅 Redis（NATS-KV 在线状态是后续 RC）。

## 技术参考

- [Centrifugo: Broker / PresenceManager 引擎拆分](https://centrifugal.dev/docs/server/engines) 与 [delivery 设计（offset+epoch）](https://centrifugal.dev/docs/getting-started/design)
- [Socket.IO Redis Adapter（经典 + sharded）](https://socket.io/docs/v4/redis-adapter/) 与 [Redis Streams Adapter（重连续读）](https://socket.io/docs/v4/redis-streams-adapter/)
- [Redis Pub/Sub 文档（PUBLISH 是 O(订阅者数)、Cluster 经典 pub/sub 全节点广播）](https://redis.io/docs/latest/develop/pubsub/) 与 [SSUBSCRIBE（sharded pub/sub）](https://redis.io/docs/latest/commands/ssubscribe/)
- [Redis 基准（单线程吞吐 / pipeline）](https://redis.io/docs/latest/operate/oss_and_stack/management/optimization/benchmarks/)
- [Lettuce Pub/Sub（单连接、event loop 派发、多连接才能并行）](https://github.com/lettuce-io/lettuce-core/wiki/Pub-Sub) 与 [sharded pub/sub 版本问题 #2971/#2940/#3213（须 ≥6.5.5）](https://github.com/redis/lettuce/issues/2971)
- [NATS 兴趣路由（只发给有订阅者的节点）](https://docs.nats.io/nats-concepts/subjects) 与 [gateways](https://docs.nats.io/running-a-nats-service/configuration/gateways)
- [Slack 实时消息：全 Java 的 Channel Server / Gateway Server mesh](https://www.infoq.com/news/2023/04/real-time-messaging-slack/)
- [Spring STOMP broker relay（对比：硬耦合外部 broker，本设计 degrade-to-local 更友好）](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html)
- [Redis client-output-buffer-limit](https://redis.io/docs/management/clients/#client-output-buffer-limit) · [W3C Trace Context](https://www.w3.org/TR/trace-context/) · [ScaleCube SWIM](https://github.com/scalecube/scalecube-cluster)
