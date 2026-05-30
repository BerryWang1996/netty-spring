# Release Notes — v1.8.0

> 发布日期：2026-05-29

## 版本定位

v1.8.0 是 **WebSocket 集群支持** 的 milestone 版本。通过 Redis Pub/Sub 实现跨节点广播和单播，让 netty-spring 应用可以多节点水平扩展。默认单机模式零开销；通过 `server.netty.websocket.cluster.enable=true` 一键启用集群。

## 核心能力

### 新增模块

- **`netty-spring-websocket-cluster`** — 集群核心：ClusterBroker / SessionRegistry / MessagePayloadCodec SPI、Redis Pub/Sub 传输实现、节点生命周期管理
- **`netty-websocket-cluster-spring-boot-starter`** — Spring Boot starter，`cluster.enable=true` 时自动装配

### SPI 可插拔架构（5 层 SPI）

| SPI | 职责 | 默认实现 | 用户可替换为 |
|---|---|---|---|
| `ClusterBroker` | 跨节点传输 | Redis Pub/Sub | NATS / node-mesh |
| `SessionRegistry` | 分布式会话注册表 | Redis Hash + Set | 自定义存储 |
| `EnvelopeCodec` | 信封线格式 | SimpleTextEnvelopeCodec（零依赖） | JSON / Protobuf |
| `MessagePayloadCodec` | 消息体序列化 | DefaultMessagePayloadCodec（T:/J:/B:） | Protobuf / MessagePack |
| `ClusterNodeHeartbeat` | 心跳持久化 | Redis TTL | 自定义 |

所有 SPI 通过 `@Bean` + `@ConditionalOnMissingBean` 覆盖，零继承、零模板代码。

### 零外部序列化依赖

集群模块**不依赖 Jackson**。信封和消息体序列化均使用零依赖的默认实现。用户可通过 SPI 自由选择 JSON / Protobuf / 自定义格式。

### 集群功能清单

- 跨节点广播（at-most-once via Redis Pub/Sub）
- 跨节点单播（registry 路由 + broker 中继）
- 远程 session 关闭（CLOSE 控制指令）
- Origin 自投递抑制（防广播重复）
- 节点生命周期管理（JOINING→ACTIVE→DEGRADED→RESYNC→DRAINING→LEFT）
- 心跳 + 周期性对账（reconciliation 兜底 keyspace notification 漏报）
- 节点查找缓存（5s TTL + NODE_LEFT 失效）
- 集群运行时统计（ClusterRuntimeStats：broadcastPublished / selfDeliveryDropped / unicastSent / cacheHitRatio 等）
- `ClusterSessionHook` 生命周期钩子（session 连接/断开 → registry 同步）

### 配置项（1.8.0 实际生效）

命名空间 `server.netty.websocket.cluster.*`。**1.8.0 只暴露有实际效果的配置项**——`docs/cluster-design.md` 设计全集中尚未实现的特性（多 pub/sub 连接、sharded pub/sub、可靠 streams、写 pipeline、限速、宽限期、Redis Cluster 客户端等）不提供"会撒谎的开关"，推迟到 `1.9.x`。

| 配置项 | 默认 | 作用 |
|---|---|---|
| `enable` | `false` | 集群总开关；缺省退化为 1.7.x 单机行为 |
| `node-id` | 自动 UUID | 节点唯一标识 |
| `redis.uri` | `redis://localhost:6379` | 拓扑由 URI scheme 决定（`redis://` standalone / `redis-sentinel://` sentinel） |
| `heartbeat-interval-seconds` / `heartbeat-timeout-seconds` | 3 / 10 | 心跳写入间隔 / 失联判定超时 |
| `reconciliation-interval-seconds` | 15 | 周期对账兜底间隔 |
| `drain-timeout-seconds` | 60 | 优雅停机等待 session 关闭上限 |
| `reconnect-jitter-max-seconds` | 10 | DEGRADED→RESYNC 重注册前抖动，防重连风暴 |
| `registry-read-cache-ttl-ms` | 5000 | sessionId→nodeId 单播热路径缓存 TTL |
| `message-max-size-bytes` | 1048576 | 超限消息不发往集群（本地投递不受影响） |
| `on-redis-loss` | `degrade-to-local` | Redis 失联策略：保活本地（默认）/ `close-all` 关闭全部本地 session |
| `on-publish-failure` | `log` | 集群发布失败策略：`log` / `drop` |

## 性能基准

测试环境：Docker Desktop / Redis 7.4.9 / JDK 17 / SimpleTextEnvelopeCodec

| 场景 | 吞吐量 | 延迟 |
|---|---|---|
| 本地广播（无 Redis） | 1,808,057 msg/s | 0.6 µs |
| Raw Redis Pub/Sub | 16,281 msg/s | 61.4 µs |
| 双节点跨节点广播 | ~14,000 msg/s | 76.5 µs |

## 测试覆盖

- 285 个测试，11 个模块，全部通过
- 集群模块：6 SPI 隔离测试 + 6 配置 knobs 测试 + 9 Redis 集成测试（含入站大小上限安全测试）
- `PerformanceBenchmark`（4 个方法）是**手动运行的性能 harness**，不计入 285 的 `mvn test` 套件

## 升级指南

从 v1.7.1 升级：
1. 更新依赖版本号 → `1.8.0`
2. **不需要任何其他改动** — 默认单机模式行为与 1.7.x 完全一致

启用集群：
```xml
<!-- 额外引入集群 starter -->
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-websocket-cluster-spring-boot-starter</artifactId>
    <version>1.8.0</version>
</dependency>
```

```properties
server.netty.websocket.cluster.enable=true
# 生产：专用 + 隔离 + 认证 + TLS 的 Redis
server.netty.websocket.cluster.redis.uri=rediss://:password@your-redis:6379
```

业务代码零改动 — `MessageSender` 自动切换为 `ClusterMessageSender`。

## 生产发版定位与安全

- **单机模式（默认）= 生产级**：`cluster.enable=false`（或缺省）时行为与 1.7.x 完全一致，零集群开销。绝大多数用户用这个。
- **集群模式 = 面向 ≤~10 节点 + 专用加密 Redis**。⚠️ **Redis 是集群控制平面**：任何能 `PUBLISH` 的主体都能向任意会话注入消息或强制关闭会话。生产**必须**用专用、网络隔离、带密码（`redis://:secret@host`）+ TLS（`rediss://`）的 Redis。1.8.0 已内置：URI 密码日志脱敏 + 无认证/无 TLS 时 `WARN` + 接收端入站消息大小上限。应用层 AES-GCM **不**延伸过 Redis（明文扇出到远端）。完整信任模型见 `docs/cluster-design.md §安全模型`。

## 已知限制（推迟到 1.9.x 硬化）

envelope HMAC 认证、完整 Micrometer 指标集 + Actuator 集群健康、reconciliation 选主去重、`deregister` 原子性、心跳/对账线程隔离、Lettuce 连接事件即时降级、多 pub/sub 连接、sharded pub/sub、Redis Cluster 客户端一等支持、W3C TraceContext 跨节点传播、多节点 demo + Testcontainers、auto-config ApplicationContextRunner 装配测试。详见 `docs/cluster-design.md` 与 `docs/development-plan.md`。
