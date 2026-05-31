# Release Notes — v1.9.0

> 发布日期：2026-05-31

## 版本定位

v1.9.0 是 **集群可靠性硬化** milestone。聚焦于将 1.8.0 发版评审中明确推迟的 5 项集群运维加固项落地：Redis 失联宽限期、心跳/对账线程隔离 + 批量 EXISTS、原子 Lua deregister、对账选主去重、registry 写合并限速。

- **单机模式（默认）**：与 1.7.x / 1.8.0 字节级行为完全一致，零开销，不受本次任何更改影响。
- **集群模式**：适用边界不变（≤~10 节点 + 专用加密 Redis）；5 项硬化让该边界内的运维可靠性大幅提升。
- **唯一行为默认值变更**：Redis 失联宽限期（`redis-loss-grace-period-ms`）默认 5 000 ms，即真实 Redis 中断现在会在最多 5s 后才触发降级；设置为 `0` 可恢复 1.8.0 的即时降级行为。

## 核心能力（1.9.0 新增 / 硬化）

### ① Redis 失联宽限期

新增配置项 `server.netty.websocket.cluster.redis-loss-grace-period-ms`（默认 **5000**，`0` = 即时降级，等同 1.8.0 行为）。

- Broker 自身的 `state()` 仍然**立刻**翻转（如实反映连接状态，用于 Actuator 健康检查和统计）。
- 节点状态机的 DEGRADED 转换和 `on-redis-loss` 策略（`degrade-to-local` / `close-all`）在宽限窗口内**抑制**；窗口内 Redis 恢复则宽限取消，节点状态不变。
- 防止亚秒级 Redis 网络抖动（云环境常见）触发状态机抖动或 `close-all` 强制断开会话。

> ⚠️ **这是 1.9.0 中唯一的有意默认行为变更。** 真实 Redis 中断现在最多等 5s 才降级（而非 1.8.0 的即时降级）。如需恢复 1.8.0 精确时序，设 `redis-loss-grace-period-ms=0`。

### ② 心跳/对账线程隔离 + 批量 EXISTS

- `ClusterNodeManager` 拆分为两个独立的单线程调度器：
  - `cluster-hb-{node}`：只负责心跳续约（写 Redis TTL key），优先级最高，永不被阻塞。
  - `cluster-recon-{node}`：负责对账扫描 + 宽限期计时器 + RESYNC 重连。
- 对账扫描的 `findExpiredNodes` 改为**批量 EXISTS 管道请求**（单次往返组，而非 N 个串行调用），大幅减少对账扫描的 Redis 往返延迟。
- 内部变更，无新配置项。解决了 1.8.0 中慢对账扫描可能"饿死"心跳续约（让存活节点被同伴误判为下线）的竞态。

### ③ 原子 Lua deregister

`RedisSessionRegistry.deregister` 现在是一个**原子 `EVAL` 脚本**（HGET→DEL→SREM），替代之前的 HGET + DEL + SREM 三步操作。

- 彻底消除了并发 re-register（同 uri|sessionId）与 deregister 交错时可能遗留 node-set 孤条目的理论竞态。
- 接口签名不变；支持 standalone 和 sentinel 拓扑（Redis Cluster 客户端一等支持仍在路线图）。
- 内部变更，无新配置项。

### ④ 对账选主去重（ClusterReaper SPI）

新增 `ClusterReaper` SPI，默认实现 `alwaysReap()`（兼容 1.8.0 行为）；提供 `RedisClusterReaper` 实现，通过 `SET netty:cluster:reaping:{deadNode} {me} NX PX {window}` 竞争认领清理权，确保死节点只被**一个**存活节点清理（而非 N 个节点各自独立清理，产生 N 倍幂等写流量）。

- `RedisClusterReaper` 通过 `@ConditionalOnMissingBean` 注入，用户可自定义覆盖。
- `ClusterNodeManager` 保持 transport-agnostic，只依赖 `ClusterReaper` SPI。
- 内部变更，无新配置项。

### ⑤ Registry 写合并限速（CoalescingRegistryWriter）

新增配置项 `server.netty.websocket.cluster.session-registry-write-rate`（默认 **1000** ops/s/node，`0` = 不限速/纯透传）。

- `CoalescingRegistryWriter` 使用 token-bucket 在速率内**直通**（零延迟变化）；超过速率时，同一 session 的 register/deregister 操作**合并**排队，tokens 补充后批量执行。
- **register 操作永不丢弃**——合并只减少冗余调用次数，不丢信息。
- 专用线程 `cluster-regwriter-{node}` 异步处理排队写入。
- 防御滚动部署或 LB 排空触发的 10k+ ops/s 注册/注销风暴，保护 Redis 写路径。

## 新增配置项（1.9.0）

命名空间 `server.netty.websocket.cluster.*`：

| 配置项 | 默认 | 作用 |
|---|---|---|
| `redis-loss-grace-period-ms` | `5000` | Redis 失联后节点状态机降级前的宽限窗口（ms）；`0` = 即时降级（1.8.0 行为）。**唯一的默认行为变更。** |
| `session-registry-write-rate` | `1000` | Registry 写限速（ops/s/node）；`0` = 不限速。token-bucket 透传，超速时合并写入，register 永不丢弃。 |

### 完整配置参考（1.9.0）

所有 1.8.0 已有配置项不变，1.9.0 新增以上两项：

```yaml
server:
  netty:
    websocket:
      cluster:
        enable: false
        node-id: ${HOSTNAME:auto}
        redis:
          uri: redis://localhost:6379
        heartbeat-interval-seconds: 3
        heartbeat-timeout-seconds: 10
        reconciliation-interval-seconds: 15
        drain-timeout-seconds: 60
        reconnect-jitter-max-seconds: 10
        registry-read-cache-ttl-ms: 5000
        command-timeout-ms: 2000
        message-max-size-bytes: 1048576
        on-redis-loss: degrade-to-local
        on-publish-failure: log
        # --- 1.9.0 新增 ---
        redis-loss-grace-period-ms: 5000      # 0 = 恢复 1.8.0 即时降级行为
        session-registry-write-rate: 1000     # 0 = 不限速
```

## 测试覆盖

- **304 个测试，11 个模块，全部通过**（`mvn test`，Redis 7.4.9 live on localhost:16379）。
- 1.9.0 新增测试：
  - `ClusterNodeManagerReliabilityTest` — 线程隔离调度器验证、宽限期抑制逻辑、双调度器并发压测
  - `ClusterRegistryWriterTest` — token-bucket 直通路径、超速合并、零丢失断言、并发注册风暴模拟
  - 3 个 `RedisIntegrationTest` 新增用例：原子 Lua deregister 竞态验证、`ClusterReaper` 选主去重端到端、宽限期恢复路径

## 升级指南

### 从 v1.8.0 升级

1. 更新 Maven 依赖版本 → `1.9.0`（所有 `io.github.berrywang1996:netty-*` artifact）。
2. **无需任何代码改动** — 所有 SPI 签名不变；`ClusterReaper` 是新增 SPI（additive）。
3. **注意默认行为变更**：`redis-loss-grace-period-ms` 默认从"无宽限期"变为 5 000 ms。若需完全保留 1.8.0 的即时降级时序，显式设置：
   ```properties
   server.netty.websocket.cluster.redis-loss-grace-period-ms=0
   ```
4. 单机模式（`cluster.enable=false` 或缺省）无任何变化。

```xml
<!-- 集群 starter 版本号改为 1.9.0 -->
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-websocket-cluster-spring-boot-starter</artifactId>
    <version>1.9.0</version>
</dependency>
```

### 从 v1.7.x 升级

先参考 [1.8.0 升级指南](release-notes-1.8.0.md) 完成集群模块接入，再执行上述步骤。

## 向后兼容性声明

- **单机模式**（`cluster.enable=false`，默认）：与 1.7.x / 1.8.0 **字节级行为完全一致**。零集群开销。
- **集群模式 SPI**：`ClusterBroker` / `SessionRegistry` / `EnvelopeCodec` / `MessagePayloadCodec` / `ClusterNodeHeartbeat` 签名**全部不变**；`ClusterReaper` 是**新增** SPI（additive，不破坏任何现有实现）。
- **配置项**：所有 1.8.0 配置项默认值不变（除 `redis-loss-grace-period-ms` 从"无宽限"变为 5 000 ms，已在上文突出标注）。
- **Java API**：`MessageSender` / `ClusterMessageSender` 接口无变化；`deregister` 内部原子化，对调用方透明。

## 已知限制（仍推迟到后续版本）

以下能力在 1.9.0 中**仍未实现**，推迟到 1.9.x 后续版本或 2.x：

- **NATS broker**（ADR-001 规模化档位）
- **Redis Streams 可靠投递**（`reliableBroadcast` / at-least-once，含 offset+epoch 模型）
- **完整 Micrometer 指标集**（`netty.cluster.*` meter-binder 时序）
- **HMAC envelope 认证**（消除 `originNodeId` 伪造与未授权 `CLOSE`/注入）
- **多 pub/sub 连接并行解码 / sharded pub/sub**
- **Redis Cluster 客户端一等支持**（`RedisClusterClient`）
- **W3C TraceContext 跨节点传播**（envelope 已预留 `traceparent` 字段）
- **多节点 demo + Testcontainers 端到端 CI**

详见 `docs/cluster-design.md` 与 `docs/development-plan.md`。
