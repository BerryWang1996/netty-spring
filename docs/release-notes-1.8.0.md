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

## 性能基准

测试环境：Docker Desktop / Redis 7.4.9 / JDK 17 / SimpleTextEnvelopeCodec

| 场景 | 吞吐量 | 延迟 |
|---|---|---|
| 本地广播（无 Redis） | 1,808,057 msg/s | 0.6 µs |
| Raw Redis Pub/Sub | 16,281 msg/s | 61.4 µs |
| 双节点跨节点广播 | ~14,000 msg/s | 76.5 µs |

## 测试覆盖

- 282 个测试，11 个模块，全部通过
- 集群模块：6 SPI 隔离测试 + 8 Redis 集成测试 + 4 性能基准

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
server.netty.websocket.cluster.redis.uri=redis://your-redis:6379
```

业务代码零改动 — `MessageSender` 自动切换为 `ClusterMessageSender`。
