# Cluster Design — `1.8.0` Redis Pub/Sub 集群方案

> 本文档为 `netty-spring 1.8.0` 集群支持的设计参考。`1.8.0` 当前为规划中状态，发布前实现细节可能调整。
> 落地进度跟踪：`docs/development-plan.md` 中的"`1.8.0` Redis 集群支持版本规划"。

## 目标

让单机 netty-spring 服务可以多节点水平扩展，跨节点广播和单播对业务代码透明，节点扩缩容/故障自恢复可控。

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
              │                    Redis (cluster or single)         │
              │  ┌──────────┐ ┌──────────┐ ┌──────────────────────┐ │
              │  │ Pub/Sub  │ │ Session  │ │  Streams (可选)      │ │
              │  │ Channels │ │ Registry │ │  持久化 / 离线消息    │ │
              │  └──────────┘ └──────────┘ └──────────────────────┘ │
              └──────────────────────────────────────────────────────┘
```

## 核心设计

### 1. 集群发现与成员管理

推荐方案：Redis Heartbeat + Keyspace Notification（最小外部依赖）。

**Redis Key 设计：**

```
netty:cluster:nodes              → Hash { nodeId → JSON(host, port, startTime, lastHeartbeat, sessionCount) }
netty:cluster:heartbeat:{nodeId} → String with TTL 30s（心跳 key，过期即判定故障）
netty:cluster:control            → Pub/Sub channel（节点加入/离开/广播命令）
```

**故障检测：**
- 每个节点每 10s 续期 `heartbeat:{nodeId}` 的 TTL。
- 使用 Redis Keyspace Notification 监听 key 过期事件。
- 默认 TTL 30s → 最大故障检测延迟 30s；需要更快检测的场景可切换 ScaleCube SWIM。

**节点生命周期：** `JOINING → ACTIVE → DRAINING → LEFT`

### 2. 分布式 Session Registry

**Redis Key 设计：**

```
netty:session:{uri}:{sessionId}  → Hash { nodeId, userId, connectedAt, metadata }
netty:node:{nodeId}:sessions     → Set { sessionId1, sessionId2, ... }
netty:user:{userId}:sessions     → Set { "uri:sessionId@nodeId", ... }   (可选，按用户查询时启用)
```

**会话路由（私聊 A→B）：**
1. 查 `netty:session:{uri}:{targetSessionId}` 获取 `targetNodeId`。
2. `targetNodeId == 本节点` → 走本地发送路径。
3. 否则 → 发布到 `netty:cluster:unicast:{targetNodeId}` channel。

### 3. 跨节点消息路由

**广播（Pub/Sub）：**

```
每个 URI 一个频道：  netty:broadcast:{uri}
节点只订阅自己持有活跃 session 的 URI。
```

流程：
1. Node-1 收到广播 → **本地扇出**（写入本节点所有 session）。
2. Node-1 → **Redis 发布** `netty:broadcast:{uri}`。
3. Node-2/3 → 各自本地扇出。

优化：URI 无跨节点 session 时直接短路为内存路径，不写 Redis。

**私聊（点对点）：**

```
netty:cluster:unicast:{targetNodeId}  → Pub/Sub channel（每节点一个）
```

同机房 Redis Pub/Sub RTT 通常 < 1ms。

**可靠投递（可选）：** Redis Streams `netty:stream:{uri}` + consumer group，用于离线消息 / 重要通知。

### 4. 弹性扩缩容

**Scale-out**：
1. 新节点启动 → 注册到 `netty:cluster:nodes` → 发布 JOIN 事件。
2. LB 将新连接路由到新节点（无需迁移已有连接，自然平衡）。

**Scale-in**：
1. 节点收到 DRAIN 信号 → 状态切到 `DRAINING`，从 LB 摘除。
2. 向所有 session 发送 `CloseFrame(1001, "Server going away")`。
3. 客户端重连到健康节点（需要客户端实现退避重连）。
4. 等待 session 关闭或超时 → 注销 → 退出。

**Kubernetes HPA 集成（示例）**：

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  metrics:
  - type: Pods
    pods:
      metric:
        name: netty_websocket_sessions_active   # 来自 Prometheus
      target:
        type: AverageValue
        averageValue: "5000"                    # 每 Pod 目标连接数
```

### 5. 自恢复

**节点故障：**
1. 故障节点 heartbeat key TTL 过期。
2. 健康节点收到 Keyspace Notification。
3. 触发清理：删除该节点的所有 session 记录、发布 `NODE_LEFT` 事件。
4. 客户端连接断开 → 退避重连到其他节点。

**脑裂防护：** Redis 作为 single source of truth；节点与 Redis 失联超过阈值时主动关闭本地 session（保守策略），避免双写不一致。

## 配置与 API

业务代码完全透明，`MessageSender` 接口不变；启用集群后自动切换到 `ClusterMessageSender`：

```yaml
server:
  netty:
    cluster:
      enabled: true                          # 关闭或不配置 → 退化为 1.7.x 单机行为
      redis-uri: redis://localhost:6379      # 或复用 spring.data.redis.*
      node-id: ${HOSTNAME:auto}              # 默认自动生成
      heartbeat-interval: 10s
      heartbeat-timeout: 30s
      drain-timeout: 60s
```

```java
@Controller
public class ChatController {
    private final MessageSender messageSender;   // 自动注入集群版本

    @MessageMapping(value = "/ws/chat", messageType = MessageType.TEXT_MESSAGE)
    public void onMessage(ChatMessage msg, MessageSession session) {
        // 本地扇出 + Redis Pub/Sub 发布
        messageSender.broadcastJson("/ws/chat", response);
        // 本地直发 or Redis 转发
        messageSender.sendJsonToSession("/ws/chat", pm, targetSessionId);
    }
}
```

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

新增依赖：`spring-boot-starter-data-redis`（集群模式必需，单机模式可选）。

## 兼容性承诺

- **API 兼容**：`MessageSender` 接口与方法签名不变；现有 `@MessageMapping` 控制器零迁移。
- **配置兼容**：`server.netty.cluster.enabled` 默认 `false`；未启用时所有集群依赖不参与运行时路径，行为与 `1.7.x` 完全一致。
- **回退路径**：集群启用后如需回退，关闭 `cluster.enabled` 即可，无需修改业务代码或数据迁移（在线 session 自然过期）。

## 技术参考

- [Centrifugo: Redis Pub/Sub 集群架构](https://centrifugal.dev/docs/server/engines)
- [Socket.IO Redis Adapter](https://socket.io/docs/v4/redis-adapter/)
- [ScaleCube SWIM Gossip 库](https://github.com/scalecube/scalecube-cluster)
- [Spring Boot + Redis WebSocket 水平扩展（参考文章）](https://betterprogramming.pub/implement-a-scalable-websocket-server-with-spring-boot-redis-pub-sub-and-redis-streams-b6b8cc08767f)
