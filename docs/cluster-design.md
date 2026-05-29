# Cluster Design — `1.8.0` Redis 集群方案

> 本文档是 `netty-spring 1.8.0` 集群支持的设计参考。`1.8.0` 当前为规划中状态。
> 落地进度跟踪：`docs/development-plan.md` 中的 "`1.8.0` Redis 集群支持版本规划"。
> 1.7.x 架构评审已对本设计作出关键修订，详见每节末尾的"设计注记"。

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

### 3. 跨节点消息路由

**广播 Pub/Sub：**

```
netty:broadcast:{uri}  → 每个 @MessageMapping URI 一个 Pub/Sub 频道
```

- **频道基数边界**：本基线**仅支持 `@MessageMapping` URI 作为广播频道**（典型规模 10–100 条）。聊天室 / 房间式 fan-out 需要 N×rooms 个频道，属于应用层 / `1.9.0+` 的 `ClusterRoomRegistry` 范畴；通过 `cluster.max-subscribed-channels`（默认 1024）硬上限保护，超限时启动期报错而非静默放过。
- 节点只订阅自己持有 ≥1 个本地 session 的 URI；最后一个 session 离开后保留订阅 `cluster.subscription-hold-duration`（默认 60s），避免 0/1 session 频繁波动时 SUB/UNSUB 抖动。

**流程：**
1. 本节点 fan-out（写入所有本地 session）。
2. 通过 Redis 发布 `netty:broadcast:{uri}`。
3. 远端订阅节点收到后各自本地 fan-out。

**至多一次 vs. 至少一次：**
- Redis Pub/Sub 是 fire-and-forget；**广播默认是 at-most-once**。`MessageSender.broadcast*` 在 Javadoc 中明确标注此契约，同时通过 `netty.cluster.pubsub.drops.unknown`（计数）+ `netty.cluster.pubsub.subscriber.disconnected`（计数）让运维感知潜在丢失。
- 需要 at-least-once 时通过 Redis Streams：
  ```
  netty:stream:{uri}  → Redis Stream + consumer group
  ```
  API 层用 `DeliveryHint.atLeastOnce()` 或专用的 `messageSender.reliableBroadcast(...)` 显式切换；默认仍走 Pub/Sub。

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
- `cluster.redis-loss-grace-period` 默认 60s：在此窗口内不改变状态机，避免短暂网络抖动触发降级。
- **Sentinel / Redis Cluster 一等支持**：Lettuce 原生支持，通过 `cluster.redis.mode = standalone | sentinel | cluster` 切换；文档同时给出 Spring Boot `spring.data.redis.*` 复用方式（用 `@ConditionalOnMissingBean` 让用户已有连接工厂优先）。

**节点故障恢复：**
1. 故障节点 heartbeat key TTL 过期。
2. 健康节点收到 Keyspace Notification。
3. 触发清理：删除该节点 session 记录，发布 `NODE_LEFT`。
4. 跨节点单播尝试投递到该 nodeId 时返回错误（见 §2）。

**Redis 恢复时的雷击防护：**
- 所有节点恢复同步时携带 `jitter(0, cluster.reconnect-jitter-max)`（默认 10s），避免雷击。
- Session registry 重建走 token-bucket 限速（默认 1000 ops/s/节点）。
- 订阅重建按 100 个 URI 一批 pipeline，避免连接被填满。
- 节点状态机增加 `RESYNC` 状态明确表达"恢复中"。

## 配置与 API

### API 契约（修正：不再承诺"接口完全不变"）

集群模式下 `MessageSender` 接口的本地查询语义保持不变，但**跨集群查询是新接口、且为异步形态**——本地是 O(1) map 查询，跨集群是 Redis 往返，混在同一同步签名里会让业务代码踩坑。

| 方法 | 语义 | 备注 |
|---|---|---|
| `getSessionIds(uri)` / `getSessions(uri)` | **本地节点** | 不变，热路径安全 |
| `isSessionAlive(uri, ids…)` | **本地节点**，非本地 id 返回 `false` | 不变，热路径安全 |
| `closeSession(uri, sessionId)` | 本地 sessionId 同步关闭，远端 sessionId 异步发布关闭意图后立即返回 `true` | Javadoc 明确语义 |
| `broadcast(uri, msg)` | 本地 + 跨节点 fan-out，**at-most-once** | 同上 |
| `getClusterSessionIds(uri)` | 全集群 | 新增，返回 `CompletionStage<Set<String>>` |
| `isSessionAliveCluster(uri, ids…)` | 全集群 | 新增，`CompletionStage<Boolean>` |
| `closeSessionCluster(uri, sessionId)` | 全集群关闭，等待目标节点 ACK | 新增，`CompletionStage<Boolean>` |
| `reliableBroadcast(uri, msg)` 或 `broadcast(uri, msg, DeliveryHint.atLeastOnce())` | Streams 路径 | 显式 at-least-once |

控制器代码：

```java
@Controller
public class ChatController {
    private final MessageSender messageSender;   // 启用集群后自动注入 ClusterMessageSender

    @MessageMapping(value = "/ws/chat", messageType = MessageType.TEXT_MESSAGE)
    public void onMessage(ChatMessage msg, MessageSession session) {
        // 本地 + 跨节点 fan-out（at-most-once）
        messageSender.broadcastJson("/ws/chat", response);
        // 本地直发 / 跨节点路由
        messageSender.sendJsonToSession("/ws/chat", pm, targetSessionId);
        // 重要通知：at-least-once
        messageSender.reliableBroadcast("/ws/chat", important);
    }
}
```

### 配置参考

```yaml
server:
  netty:
    cluster:
      enable: false                        # 关闭或缺省 → 退化为 1.7.x 单机行为
      redis:
        mode: standalone                   # standalone | sentinel | cluster
        uri: redis://localhost:6379        # 或复用 spring.data.redis.*（@ConditionalOnMissingBean）
      node-id: ${HOSTNAME:auto}            # 默认自动生成
      heartbeat-interval-seconds: 3
      heartbeat-timeout-seconds: 10
      drain-timeout-seconds: 60

      # ---- 失效模式 ----
      on-redis-loss: degrade-to-local       # degrade-to-local（默认） | close-all
      redis-loss-grace-period-seconds: 60
      reconnect-jitter-max-seconds: 10

      # ---- 性能 / 容量 ----
      publish-batch-size: 64
      publish-flush-interval-ms: 10
      max-subscribed-channels: 1024
      subscription-hold-duration-seconds: 60
      unicast-buffer-size: 4096
      message-max-size-bytes: 1048576
      compression: none                    # none | lz4 | gzip
      session-registry-write-rate: 1000    # ops/s/节点

      # ---- 投递策略 ----
      on-publish-failure: log              # drop | log | callback
      reliable-stream-max-len: 100000      # Streams MAXLEN 上限
```

### 命名空间一致性

集群子键统一使用 `enable`（与 `Mvc.enable` / `WebSocket.enable` / `Crypto.enable` / `Ssl.enable` / `Gzip.enable` / `Management.enable` 一致），不使用 `enabled`。

## 可观测性 — `1.8.0` 必须随集群同步落地

- `netty.cluster.nodes.active`（Gauge）/ `.state`（Gauge：ACTIVE/DEGRADED/JOINING/DRAINING）
- `netty.cluster.broadcast.published`（Counter，按 `uri` 标签，受 §3.A-1 上限约束）
- `netty.cluster.unicast.routed.cross-node` / `.local` / `.unknown-target`（Counter）
- `netty.cluster.pubsub.drops.unknown`（Counter）/ `netty.cluster.pubsub.subscriber.disconnected`（Counter）
- `netty.cluster.degraded.duration`（Timer）
- `netty.cluster.publish.latency`（Timer）/ `netty.cluster.subscribe.latency`（Timer）
- `netty.cluster.registry.writes` / `.reads`（Counter）

**分布式追踪**：W3C TraceContext (`traceparent`) 注入 Redis Pub/Sub 信封头，订阅侧抽出并恢复 SLF4J MDC + Micrometer Observation Scope。跨节点首跳即可串起 trace。`1.8.0` 必做，详见 development-plan 第二刀。

## 模块结构（规划）

```
netty-spring/
├── netty-spring-web
├── netty-spring-webmvc
├── netty-spring-websocket
├── netty-spring-cluster              [NEW]  Redis 通信、Session Registry、跨节点路由
├── netty-spring-boot-autoconfigure
├── netty-web-spring-boot-starter
├── netty-webmvc-spring-boot-starter
├── netty-websocket-spring-boot-starter
├── netty-cluster-spring-boot-starter [NEW]
└── demo-netty-web-spring-boot-starter
```

依赖选择：

- 直接依赖 `io.lettuce:lettuce-core`（不是 `spring-boot-starter-data-redis`），避免引入 RedisTemplate / repository / keyvalue 等不必要的抽象，也避免与用户已有 Spring Data Redis 配置共用连接产生意外行为。
- 提供 `@ConditionalOnMissingBean LettuceConnectionFactory`，允许用户用 Spring Data Redis 的 `spring.data.redis.*` 复用配置；连接通过 `@Qualifier("nettyClusterRedisConnection")` 隔离。

## 兼容性承诺

- **API 兼容**：现有 `@MessageMapping` 控制器零迁移；本地查询接口语义不变；跨集群操作通过**新增**异步接口提供，避免对 1.7.x 用户产生隐式语义变化。
- **配置兼容**：`server.netty.cluster.enable` 默认 `false`；未启用时所有集群依赖不参与运行时路径，行为与 `1.7.x` 完全一致。
- **回退路径**：集群启用后如需回退，关闭 `cluster.enable` 即可，无需修改业务代码或数据迁移（在线 session 自然过期）。
- **Micrometer 最低版本**：与主线一致（见根 README "兼容性"）；集群指标在缺失 `micrometer-core` 时自动跳过。
- **Lettuce 版本**：跟随 Spring Boot BOM；如有特定漏洞需要覆盖，通过 `dependencyManagement` 显式 pin。

## 已知未覆盖项（明确推迟）

- **Kubernetes Operator / Helm Chart**：运维侧专项，不在 `1.8.0` 范围。
- **Redis Streams 完整生命周期**（消费者重平衡、死信队列）：`1.8.0` 仅提供基础至少一次入口，完整治理推迟到 `1.9.x`。
- **跨 DC 多 Redis**：本设计假设单 Redis 集群可用区；多活 / 跨 DC 复制方案在 `2.x` 评估。
- **持久化 / 离线消息**：通过 Streams 提供原语，但消息存储 / 拉取 API 不在 `1.8.0`。

## 技术参考

- [Centrifugo: Redis Pub/Sub 集群架构](https://centrifugal.dev/docs/server/engines)
- [Socket.IO Redis Adapter](https://socket.io/docs/v4/redis-adapter/)
- [Lettuce: Master/Replica & Cluster](https://lettuce.io/core/release/reference/index.html#redis-cluster)
- [Redis client-output-buffer-limit](https://redis.io/docs/management/clients/#client-output-buffer-limit)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [ScaleCube SWIM Gossip 库](https://github.com/scalecube/scalecube-cluster)
