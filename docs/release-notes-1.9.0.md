# Release Notes — v1.9.0 (开发中 / in development)

> 状态：**开发中（1.9.0-RC4，2026-06-03）** — 本文档随 1.9.0 周期累积。RC1 含 5 项可靠性硬化；RC2 新增可靠投递（Redis Streams `reliableBroadcast`，at-least-once，opt-in）；RC3 新增 HMAC envelope 认证（`auth.*` 3 个配置项）；RC4 新增完整 Micrometer 集群指标（`netty.cluster.*` meter-binder）。最终 1.9.0 发布日期待整个周期完成后确定。

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

### ⑥ 可靠投递（Redis Streams）— `reliableBroadcast`

新增 **opt-in** 的 `clusterMessageSender.reliableBroadcast(uri, message)` 方法，提供跨节点广播的 **at-least-once** 投递保障。现有 `topicMessage()`（Pub/Sub，at-most-once）行为完全不变。

**默认关闭**：`server.netty.websocket.cluster.reliable.enable` 默认 `false`。未启用时：无消费线程、无额外 Redis 连接；调用 `reliableBroadcast()` 抛出 `IllegalStateException`。

#### 机制

- **每 URI 一个 Redis Stream**：key 格式为 `netty:cluster:rstream:{uri}`，带 `MAXLEN ~` 上限（`stream-max-len`，默认 10 000 条）控制存储。
- **每节点一个消费者组**：消费者组名 `g:{nodeId}`。节点下线期间组游标不前进；节点重连后执行 `XREADGROUP >` 即可拉取积压消息，实现**断线重连自动回放（replay-on-resync）**。
- **专用阻塞 Lettuce 连接**：消费循环使用独立的阻塞连接，不占用 Pub/Sub 或命令连接。
- **进程内 PEL 去重**：in-process 滑动窗口（`dedup-window`，默认 1024 条）过滤重连后可能出现的跨崩溃重复。
- **Origin 自投递抑制**：发布节点本地已 fan-out，消费侧通过 `originNodeId` 抑制自身重复消费。
- **死节点消费者组清理**：`ClusterReaper` 现有的 dead-node 清理流程同步清理对应节点的 Stream 消费者组，防止孤儿 PEL 积压。

#### 投递契约（重要）

- **At-least-once（保留窗口内）**：仅在 Stream 的 `stream-max-len` 条目保留窗口内保证。节点离线时间过长、对应积压条目已被 `MAXLEN ~` 修剪，则这部分消息**不可回放**（有界缺口）。
- **耐久性 = 你的 Redis**：消息持久化依赖 Redis 的 AOF/RDB 配置；Redis 宕机且无持久化则流内消息丢失。
- **发布时 Redis 必须可达**：`XADD` 失败（Redis 不可达）会触发现有的 `on-publish-failure` 策略（`log`/`drop`），不静默。
- **允许跨崩溃重复**：应用层处理函数应设计为**幂等**，因为 PEL 去重窗口仅覆盖进程内，跨进程崩溃可能产生重复。
- **延迟略高于 Pub/Sub**：消费侧有轮询延迟（`poll-block-ms`），故为显式 opt-in 而非默认。

#### 配置项（`server.netty.websocket.cluster.reliable.*`）

| 配置项 | 默认 | 说明 |
|---|---|---|
| `enable` | `false` | 可靠投递总开关；`false` 时无额外连接/线程 |
| `stream-max-len` | `10000` | 每个 Stream 的最大条目数（`MAXLEN ~` 近似裁剪） |
| `poll-block-ms` | `2000` | 消费侧阻塞等待超时（ms） |
| `poll-count` | `64` | 每次 `XREADGROUP` 最多读取条目数 |
| `dedup-window` | `1024` | 进程内 PEL 去重滑动窗口大小 |

### ⑦ HMAC envelope 认证 / HMAC Envelope Authentication

*Since V1.9.0-RC3.* 跨节点信封的传输层 **HMAC-SHA256** 认证，通过 `MessageAuthenticator` SPI 实现（与 codec 无关，不改变 envelope 线格式）。广播、单播、CLOSE 以及可靠投递（Redis Streams）路径统一签名/验证。

**默认关闭**：`server.netty.websocket.cluster.auth.enable=false` — 不签名，零额外开销；禁用状态下 NoOp 认证器仍会静默剥离 `H1:` 标签，不会拒绝已签名流量，便于滚动升级期间兼容。

#### 机制

- **线格式**：`H1:{base64url(hmac)}:{payload}`（前缀 `H1:` 标识版本和算法）。
- **签名**：发送前对原始 payload 字节计算 HMAC-SHA256，base64url 编码后前置于信封。
- **验证**：接收端提取标签、重算 HMAC，使用**常量时间比较**（防时序攻击）。验证失败（标签缺失或不匹配）→ 消息丢弃 + 计数（`HmacMessageAuthenticator.getRejectedCount()`）+ 日志告警；不抛异常、不关闭连接。
- **范围**：仅防伪造（anti-forgery），不提供重放保护。重放攻击需要 Redis 读权限，已是更强的安全前提；时间窗口方案与可靠投递的 replay-on-resync 语义冲突，故不引入。该限制已在文档中明示。

#### 关闭的威胁

任何能向集群 Redis `PUBLISH` 的主体，在 1.8.0 中可以：伪造 `originNodeId`（绕过自投递抑制）、注入任意广播/单播消息、或通过发送 CLOSE 控制指令强制关闭任意 WebSocket 会话。启用 HMAC 后，缺少共享密钥的攻击者无法构造合法信封，上述攻击面被消除。

#### 配置项（`server.netty.websocket.cluster.auth.*`）

| 配置项 | 默认 | 说明 |
|---|---|---|
| `auth.enable` | `false` | 认证总开关；`false` = NoOp（不签名，剥离 `H1:` 标签但不验证） |
| `auth.secret` | （无默认，启用时必填） | 共享 HMAC 密钥（≥32 字符，通过 `${ENV_VAR}` 外部化，日志中脱敏，不得明文写入 YAML） |
| `auth.permissive` | `false` | 宽容模式：`true` = 签名发出但允许接收无签名流量（滚动升级用）；`false` = 严格模式（拒绝无签名或签名错误的入站消息） |

#### 三阶段零停机滚动升级

| 阶段 | 配置 | 行为 |
|---|---|---|
| ① 全集群 `auth.enable=false` | 默认，所有节点互通明文；NoOp 剥离 `H1:` 标签 | 基线，全量互操作 |
| ② 滚动 `auth.enable=true, auth.permissive=true` | 已翻转节点签名发出 + 接受明文/签名两种入站；未翻转节点 NoOp 剥离标签并正常读取 | 混合期，零跨节点消息丢失 |
| ③ 滚动 `auth.permissive=false` | 全部节点切严格模式，拒绝无签名入站 | 认证完全生效 |

**密钥轮换**：本版本单密钥，无双密钥重叠窗口；密钥轮换需短暂维护窗口（同时更新所有节点 `auth.secret` 并重启），或按上述三阶段流程执行一次重新升级。

#### 注意事项

- `auth.secret` **必须**通过环境变量或 Secret 管理系统注入（`${CLUSTER_AUTH_SECRET}`），禁止明文写入配置文件。
- HMAC 仅保护 Redis 传输层（跨节点信封），不扩展至浏览器↔节点的 WebSocket 帧（见应用层加密 §8）。
- 启用后日志中 `auth.secret` 值被脱敏（输出为 `[REDACTED]`）。

### ⑧ 完整 Micrometer 集群指标 / Full Micrometer Cluster Metrics

*Since V1.9.0-RC4.* 把集群已有的程序内运行时信号桥接为 **`netty.cluster.*` Micrometer 时序指标**，补全 1.7.0 起的可观测性体系（`netty.websocket.*` / `netty.http.*`）。沿用 1.7.0 的 `MeterBinder` 模式（`NettyWebSocketMeterBinder`）：`NettyClusterMeterBinder` 用 `FunctionCounter`/`Gauge` **只读直通**已有计数器，**不改动任何自增点**，热路径零开销。

**可选 + 门控**：`micrometer-core` 在 classpath 且 `cluster.enable=true` 时才注册（`@ConditionalOnClass(MeterRegistry)` + `@ConditionalOnBean`）；缺失或集群关闭时零注册、零报错。**聚合粒度**（无 per-URI/per-session 标签，基数有界）。与既有 `ClusterHealthIndicator`（`/actuator/health`，point-in-time）互补，提供时序。

#### 指标集（`netty.cluster.*`）

11 个 counter：

| 指标 | 含义 |
|---|---|
| `broadcast.published` | 交给 broker 发布的广播数 |
| `broadcast.received` | 收到并本地投递的跨节点广播数 |
| `broadcast.self_dropped` | 抑制的自投递广播数（origin == 本节点） |
| `broadcast.skipped_degraded` | 因节点非 ACTIVE 跳过的跨节点广播数 |
| `unicast.sent` | 发往远端节点的单播数 |
| `publish.failures` | 失败/丢弃的集群发布数 |
| `reliable.published` | 可靠广播发布数（XADD） |
| `reliable.received` | 收到并本地投递的可靠广播数 |
| `cache.hits` / `cache.misses` | 单播节点查找缓存命中/未命中 |
| `auth.rejected` | 因缺失/无效 HMAC 标签被拒的入站信封数（未启用 HMAC 时恒为 0） |

按 `state` 标签的状态 gauge（当前态为 `1.0`，其余为 `0.0`）：

- `netty.cluster.node.state{state=joining|active|degraded|resync|draining|left}`（6 态）— 源 `ClusterNodeManager.getState()`
- `netty.cluster.broker.state{state=active|degraded|resync|shutdown}`（4 态）— 源 `ClusterBroker.state()`

比率（如缓存命中率）留给查询层计算（`hits/(hits+misses)`）。仪表盘可对 `netty_cluster_node_state{state="degraded"} == 1` 告警。

#### 注意事项

- 纯加性、可选；无 SPI/签名/行为变更；无新配置项（开关复用既有 `micrometer-core` classpath 探测 + `cluster.enable`）。
- 指标为每节点本地视图（无 `node.id` 标签）；跨节点聚合在采集端（如 Prometheus）完成。

---



命名空间 `server.netty.websocket.cluster.*`：

| 配置项 | 默认 | 作用 |
|---|---|---|
| `redis-loss-grace-period-ms` | `5000` | Redis 失联后节点状态机降级前的宽限窗口（ms）；`0` = 即时降级（1.8.0 行为）。**唯一的默认行为变更。** |
| `session-registry-write-rate` | `1000` | Registry 写限速（ops/s/node）；`0` = 不限速。token-bucket 透传，超速时合并写入，register 永不丢弃。 |
| `reliable.enable` | `false` | （RC2 新增）可靠投递（Redis Streams）总开关；`false` 时零额外开销，调用 `reliableBroadcast()` 抛异常。 |
| `reliable.stream-max-len` | `10000` | （RC2 新增）每 URI Stream 最大条目（`MAXLEN ~` 近似），超限自动裁剪旧条目。 |
| `reliable.poll-block-ms` | `2000` | （RC2 新增）消费侧阻塞轮询超时（ms）。 |
| `reliable.poll-count` | `64` | （RC2 新增）每次 `XREADGROUP` 最多读取条目数。 |
| `reliable.dedup-window` | `1024` | （RC2 新增）进程内 PEL 去重滑动窗口大小。 |
| `auth.enable` | `false` | （RC3 新增）HMAC envelope 认证总开关；`false` = NoOp（不签名，剥离 `H1:` 标签但不验证）。 |
| `auth.secret` | （必填） | （RC3 新增）共享 HMAC-SHA256 密钥（≥32 字符，通过环境变量注入，日志脱敏）；`auth.enable=true` 时必须配置。 |
| `auth.permissive` | `false` | （RC3 新增）宽容模式；`true` = 签名发出 + 允许无签名入站（滚动升级用）；`false` = 严格（拒绝无效/缺失标签）。 |

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
        # --- 1.9.0 RC1 新增 ---
        redis-loss-grace-period-ms: 5000      # 0 = 恢复 1.8.0 即时降级行为
        session-registry-write-rate: 1000     # 0 = 不限速
        # --- 1.9.0 RC2 新增（可靠投递）---
        reliable:
          enable: false                       # true = 启用 Redis Streams 可靠投递
          stream-max-len: 10000              # 每 URI Stream 最大条目（MAXLEN ~）
          poll-block-ms: 2000               # 消费侧阻塞轮询超时（ms）
          poll-count: 64                    # 每次 XREADGROUP 最多读取条目数
          dedup-window: 1024                # 进程内 PEL 去重滑动窗口
        # --- 1.9.0 RC3 新增（HMAC envelope 认证）---
        auth:
          enable: false                       # true = 启用 HMAC-SHA256 信封认证
          secret: ${CLUSTER_AUTH_SECRET}      # 共享密钥，≥32 字符，禁止明文，日志脱敏
          permissive: false                   # true = 宽容模式（签名+接受无签名，滚动升级用）
```

## 测试覆盖

- **324 个测试，11 个模块，全部通过**（`mvn test`，Redis 7.4.9 live on localhost:16379）。
- 1.9.0 新增测试：
  - `ClusterNodeManagerReliabilityTest` — 线程隔离调度器验证、宽限期抑制逻辑、双调度器并发压测
  - `ClusterRegistryWriterTest` — token-bucket 直通路径、超速合并、零丢失断言、并发注册风暴模拟
  - 3 个 `RedisIntegrationTest` 新增用例：原子 Lua deregister 竞态验证、`ClusterReaper` 选主去重端到端、宽限期恢复路径
  - （RC2）`ClusterReliableSenderTest` — reliableBroadcast 本地优先 fan-out、disabled 抛异常、origin 自投递抑制
  - （RC2）`ReliableBroadcastIntegrationTest` — 真实 Redis：发布/消费、断线重连回放（replay-on-resync，5/5）、死节点消费者组清理
  - （RC3）`MessageAuthenticatorTest` + `ClusterAuthIntegrationTest` — HMAC 签名/验签往返、篡改/错误密钥/缺签名拒绝、真实 Redis 同密钥接受 + 异密钥拒绝
  - （RC4）`NettyClusterMeterBinderTest`（`SimpleMeterRegistry` 单测：counter 值、节点/broker 状态 gauge 选态、HMAC 拒绝计数、重复 bind 幂等）+ `NettyWebSocketClusterConfigureTest` 上下文用例（micrometer + `cluster.enable=true` 时 `NettyClusterMeterBinder` bean 装配）

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
- **集群模式 SPI**：`ClusterBroker` / `SessionRegistry` / `EnvelopeCodec` / `MessagePayloadCodec` / `ClusterNodeHeartbeat` 签名**全部不变**；`ClusterReaper` 和 `MessageAuthenticator` 是**新增** SPI（additive，不破坏任何现有实现）。
- **配置项**：所有 1.8.0 配置项默认值不变（除 `redis-loss-grace-period-ms` 从"无宽限"变为 5 000 ms，已在上文突出标注）。
- **Java API**：`MessageSender` / `ClusterMessageSender` 接口无变化；`deregister` 内部原子化，对调用方透明。

## 已知限制（仍推迟到后续版本）

以下能力在 1.9.0 中**仍未实现**，推迟到 1.9.x 后续版本或 2.x：

- **NATS broker**（ADR-001 规模化档位）
- **多 pub/sub 连接并行解码 / sharded pub/sub**
- **Redis Cluster 客户端一等支持**（`RedisClusterClient`）
- **W3C TraceContext 跨节点传播**（envelope 已预留 `traceparent` 字段）
- **多节点 demo + Testcontainers 端到端 CI**

详见 `docs/cluster-design.md` 与 `docs/development-plan.md`。
