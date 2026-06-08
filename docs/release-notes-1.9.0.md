# Release Notes — v1.9.0 (GA / Released 2026-06-07)

> 状态：**正式发布（1.9.0 GA，2026-06-07）** — 本文档随 1.9.0 周期累积。RC1 含 5 项可靠性硬化；RC2 新增可靠投递（Redis Streams `reliableBroadcast`，at-least-once，opt-in）；RC3 新增 HMAC envelope 认证（`auth.*` 3 个配置项）；RC4 新增完整 Micrometer 集群指标（`netty.cluster.*` meter-binder）；RC5 新增多节点 E2E + Testcontainers CI，并**修复跨节点单播 hook-wiring 缺陷**（影响 1.8.0~RC4，仅集群模式）；RC6 新增 W3C TraceContext 跨节点 MDC 日志关联（opt-in；Micrometer Observation 续接 → 2.0.0）；RC7 新增第一等 Redis Cluster 客户端支持（`cluster-nodes` 选择 Redis Cluster 传输；常规集群 pub/sub，不削减广播扇出；sharded pub/sub → 2.0.0）；RC8 新增多节点 Docker 演示（含**跨节点 JSON 广播修复**，影响 1.8.0+ 集群用户）与多 pub/sub 连接（opt-in 入站解码扩展，默认 1）；RC9 新增 NATS broker（ADR-001 规模化档位；`NatsClusterBroker` 由 `nats.servers` 选择，**仅传输层**、registry 仍在 Redis）；RC10 新增**全 NATS 栈**（`nats.registry=true` → NATS JetStream-KV registry/心跳/reaper，可完全不依赖 Redis；需 JetStream 服务器；ADR-001 更新为 NATS-only opt-in）；RC11 预发布安全审计硬化（15 项修复：SPI 契约、Redis 键安全、缓存有界、生命周期防御、自动装配护栏、文档一致性）；RC12 收尾 1.9.1 backlog 8 项 LOW/NIT polish（L2–L8 + N1；L1 推迟需自定义 Spring `Condition`）；RC13 关闭 all-NATS 可靠投递缺口（`NatsJetStreamReliableBroker`，opt-in；`reliable.enable=true && nats.registry=true` 激活，其他档位字节级不变）；RC14 polish bundle（P1/P5/P6/Q5/Q6/Q7 — 6 项；纯 polish，除 Q5 pathological URI 外无行为变更）；RC15 测试覆盖加固（Q1/Q2/Q3 NATS reliable IT + P2/P3/P4 NATS-KV IT polish + R1/R2 日志/javadoc，8 项纯测试 / 日志 / 文档）；RC16 backlog 清零（L1 `OnAnyRedisSpiRequired` + S1 streamCache reconnect-invalidate）—— **1.9.x backlog 至此清空**；RC17 GA-readiness 终审（10 维度独立审计 + 3 项 must-fix 修复：metadata 缺失 `trace-propagation.enable`/`redis.cluster-nodes` 项 + 39 个集群测试文件补 Apache 2.0 头 + 文档版本陈旧引用刷新）—— **审计结论 GA_READY，1.9.0 GA 可在 RC17 之上直接 cut**（详见 `docs/audits/2026-06-07-ga-readiness-final.md`）；RC18 RC17 audit nice-to-haves 落地（T1+T2 snapshot-then-iterate + 注释精确化 + T3 §⑪ redis.* 配置表 + T4 §-标号 Unicode 一致性确认；0 行为变更）；RC19 2.0.0 prep docs（3 篇前瞻设计稿：sharded pub/sub 可行性 + Boot 3.x 兼容矩阵 + 1.9.0→2.0.0 迁移指南 DRAFT；**1.9.0 自身 0 行为 / SPI / wire / config 变更，GA-readiness 不受影响**）；RC20 1.9.x cycle retrospective（19 RC、168 commit、+25,217/−288 LOC、444 测试、11 模块的诚实总结 — `docs/1.9.x-cycle-retrospective.md`；**1.9.x 周期收尾艺术品；后续动作用户驱动**）。**1.9.0 GA 已 cut（2026-06-07，自 RC20 之上 flip 至 1.9.0 正式版本）；Maven Central 部署 + GitHub Release 由用户驱动后续步骤。**

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

### ⑨ 多节点 E2E + Testcontainers CI / Multi-Node E2E + Testcontainers CI

*Since V1.9.0-RC5.* 让集群行为在 CI 中**真正被验证**，并新增一个全栈双节点端到端测试。

**Testcontainers-Redis 解析器（`ClusterTestRedis`）**：集群集成测试此前依赖手工启动的 `localhost:16379`，在 CI（无 Redis）中被 `assumeTrue` 静默跳过——集群跨节点行为从未在 CI 验证过。新增解析器按序选用 Redis：环境变量 `CLUSTER_TEST_REDIS_URI` → 可达的 `localhost:16379`（保留本地快速回路）→ Testcontainers `redis:7-alpine`（让 CI 拉起容器）→ 都没有则跳过。3 个既有集成测试 + 上下文测试改用它，**现在每次提交都在 CI 真实运行**（GitHub `ubuntu-latest` 自带 Docker，无需在 workflow 里加 Redis service）。

**双节点 E2E（`ClusterMultiNodeE2ETest`）**：进程内启动**两个完整 Spring Boot 节点**（真实 Netty WebSocket server + `ClusterMessageSender`，集群开启，HMAC 开启，共享一个 Redis），用 JDK `java.net.http.WebSocket` 客户端连到节点 A，断言：(a) 节点 B 广播 → A 的客户端收到（跨节点 fan-out）+ 节点 B `netty.cluster.broadcast.published` 指标自增；(b) 节点 B 单播到 A 的会话 → A 的客户端收到（跨节点路由）。证明完整的 `MessageSender → broker → registry → 活跃会话` 路径跨节点可用。

#### 🐛 修复：跨节点单播在自动装配下从未生效（影响 1.8.0 ~ 1.9.0-RC4）

上面的 E2E 暴露并修复了一个**真实的高严重度缺陷**：集群模式下，**跨节点单播（`sendMessage` / `sendTextToSession` 到其他节点的会话）与跨节点定向关闭一直不生效**——分布式会话注册表从未被写入。

- **根因**：`NettyServerBootstrapConfigure.nettyServer()` 在自身 bean 创建期间**急切启动** Netty server（同时跑 resolver 扫描 + 一次性 `getBeansOfType(ClusterSessionHook)` 查找）；而集群配置 `@AutoConfigureAfter` server 配置（1.8.0 为修 `@Primary` 顺序而引入），此刻 `clusterSessionHook` bean 尚不可解析（它传递依赖正在创建中的 `messageSender`/server bean），查找返回空 → hook 从未挂到 resolver → 注册表为空 → 单播无法路由。**广播不受影响**（Pub/Sub 不查注册表），所以此前测试与 demo 都未发现。
- **修复**：集群配置新增一个 `SmartInitializingSingleton`，在**所有单例创建完成后**把 `ClusterSessionHook` 挂到已建好的 WebSocket resolver 上（resolver 的 hook 字段为 `volatile`，外部客户端只在启动完成后接入，无实际竞态）。不改动急切启动时序，也不触碰敏感的 `@Primary` 装配顺序。
- **回归保护**：`ClusterMultiNodeE2ETest` 的单播断言现在是永久回归门（借 Testcontainers 在 CI 运行）。经对抗式验证：临时禁用修复 → 单播失败、广播仍通过，与根因吻合。
- **单机模式不受影响**：修复仅在 `cluster.enable=true` 时激活。

> ⚠️ **集群用户务必升级**：若你在 1.8.0 ~ 1.9.0-RC4 的集群模式下依赖跨节点单播或定向关闭，请升级到本版本——这些操作此前是静默失效的。

### ⑩ W3C TraceContext 跨节点传播 / W3C TraceContext Cross-Node Propagation

*Since V1.9.0-RC6.* 让分布式 trace 跨节点延续——在发布侧把当前 W3C `traceparent` 写入信封，在接收侧恢复进 SLF4J MDC，使跨节点投递的日志带上**同一个 `traceId`**（一条 trace 在所有节点的日志里可 grep）。**opt-in**，tracer 无关，纯加性，**无线格式变更**（envelope 早已携带 `traceparent` 字段，codec 早已编解码——本版本只是把它在收发两端接起来）。

#### 机制

- **`ClusterTraceContext` SPI**（`@ConditionalOnMissingBean`，tracer 无关）：`currentTraceparent()`（发送侧读）+ `restore(traceparent)`（接收侧写，返回 try-with-resources `Scope`）。
- **默认实现 `MdcClusterTraceContext`**（零依赖，基于 MDC）：发送侧优先读 MDC `traceparent`，否则用 MDC `traceId`+`spanId`（Sleuth/Brave 的约定键）合成 `00-{traceId32}-{spanId16}-01`（64 位 traceId 左补零到 32 hex，校验失败则不传播）；接收侧把 traceparent 解析回 `traceId`/`spanId`（让既有 `%X{traceId}` 日志模式直接生效）+ `netty.traceparent`，`Scope.close()` 在投递结束后清除这三个键。Sleuth/Brave 用户可提供自定义 `ClusterTraceContext` bean 做原生 span 续接。
- **发送侧**：`buildBroadcastEnvelope` / `buildUnicastEnvelope` / CLOSE 三处信封构造注入 `currentTraceparent()`。**接收侧**：`onBroadcastMessage` / `onUnicastMessage` / `onReliableMessage` 三处本地投递用 `try (Scope = restore(env.getTraceparent())) { … }` 包裹（MDC 线程局部于 broker 订阅线程，作用域精确、用后即清）。

#### 配置项（`server.netty.websocket.cluster.trace-propagation.*`）

| 配置项 | 默认 | 说明 |
|---|---|---|
| `enable` | `false` | 总开关；`false` 时信封 `traceparent` 仍为 null、不触碰 MDC，与 RC5 行为字节一致 |

#### 范围与推迟项

- **本版本（RC6）= MDC 日志关联**：跨节点日志可按 `traceId` 串联。
- **Micrometer Observation / 活跃 span 续接** 推迟到 **2.0.0（Boot 3.x）**：Boot 2.7.18 自带 Micrometer **1.9.17**，**没有 Observation API**（1.10+ 才有）。`tracestate` 传播亦推迟（需新增信封字段，YAGNI）。

### ⑪ Redis Cluster 客户端一等支持（客户端层）/ Redis Cluster Client First-Class Support (client level)

*Since V1.9.0-RC7.* 为 WebSocket 集群新增**第一等的 Redis Cluster 客户端支持**，由一个新配置键选择启用——让集群的会话注册表 / 心跳跨 Redis Cluster 的分片（slot）分布，并获得 Redis Cluster 原生的 HA 故障转移。**纯加性、opt-in**：`cluster-nodes` 留空（默认）时，经 `redis.uri` 的 standalone/sentinel 路径与 RC6 **字节级一致**（对现有用户 / 单机模式零行为变更）。

> ⚠️ **关键边界（务必先读，勿误读为扩展能力）**：RC7 交付的是 Redis Cluster **客户端**（HA 故障转移 + 会话注册表/心跳跨 slot 分布），**不是广播扇出削减**。RC7 的集群 broker 用的是**常规 cluster pub/sub**（`SUBSCRIBE`/`PUBLISH`），它在 Redis Cluster 下仍会经 cluster bus 把每条广播传播到**所有**节点——即**没有扇出削减**。扇出削减来自 **sharded pub/sub**（`SSUBSCRIBE`/`SPUBLISH`），后者需要 **Lettuce 6.2+**（Boot 2.7.18 管理的是 Lettuce 6.1.10），故**推迟到 2.0.0（Boot 3.x）**。容量模型（见 `cluster-design.md §深度瓶颈`）的 `M·(f·N−1)` 扇出墙在 RC7 下**不变**。

#### 配置选择器

新增配置项 `server.netty.websocket.cluster.redis.cluster-nodes`——逗号分隔的种子节点列表 `host:port,host:port,...`。

- **非空** → 启用 Redis **Cluster** 传输（`RedisClusterClient` + 新的 `RedisClusterMode*` 实现）。
- **空 / 缺省（默认）** → 经 `redis.uri` 的 standalone/sentinel 路径，与 RC6 **字节级一致**。

#### 四个 `RedisClusterMode*` 实现

均位于 `…cluster.redis` 包下，均 `@ConditionalOnMissingBean`（用户可覆盖），各自镜像其 standalone 兄弟实现的 SPI 契约：

- `RedisClusterModePubSubBroker`（`ClusterBroker`）
- `RedisClusterModeSessionRegistry`（`SessionRegistry`）
- `RedisClusterModeNodeHeartbeat`（`ClusterNodeHeartbeat`）
- `RedisClusterModeReaper`（`ClusterReaper`）

#### Slot 安全增量（相对 standalone 的差异，皆为 Redis Cluster 16384-slot 模型下的正确做法，无 CROSSSLOT）

- **`deregister` 非原子**：cluster 下 `HGET → DEL + SREM` 为三条各自路由的 async 命令，**而非**跨 slot 的 Lua `EVAL`（跨 slot 脚本在 cluster 下违法）。它原本关闭的竞态在 UUID sessionId 下是**理论性**的。
- **`clusterSessionIds` 用 cluster-aware SCAN**：跨所有 master 分片扇出扫描（而非单节点 SCAN）。
- **心跳过期判定用逐键 EXISTS**：每个心跳 key 单独 EXISTS（而非一次多键 EXISTS——多键 EXISTS 在 cluster 下会 CROSSSLOT）。
- **reaper 为单键 `SET NX PX`**：单键天然落在单 slot，认领逻辑不变。

#### 验证范围（诚实声明）

针对**单节点 Redis Cluster**验证（Testcontainers `redis:7 --cluster-enabled`，全部 16384 slot 落在该单节点上）。这证明了**`RedisClusterClient` API 路径**端到端打通：cluster 连接、常规 cluster pub/sub 的 publish→receive、slot 路由的 HSET/HGET/SADD/SREM/DEL/SET/EXISTS/SCAN——**针对一个真实的 cluster**。它**不覆盖**多节点 slot 分布或跨节点 pub/sub 传播（需多节点 cluster——超出 RC7 范围，记为未来项）。

#### 限制

- **`cluster-nodes` 无法表达 TLS / 密码**：它是纯 `host:port` 列表。需要连接受保护的 cluster（鉴权 / TLS）时，**自备 `RedisClusterClient` bean**（`@ConditionalOnMissingBean`，框架自动让位）。
- **可靠投递（Redis Streams）与 `cluster-nodes` 在 RC7 中互斥**：`reliable.enable=true` 与 `cluster-nodes` 同时设置时**不装配 `ReliableBroker`**（reliable-on-cluster 是后续项）。standalone 拓扑与 cluster-nodes 也互斥。
- **连接选项**：cluster 客户端配置为 `validateClusterNodeMembership(false)` + 关闭周期性拓扑刷新——让它在容器 / NAT 端口映射后仍可用（常见生产形态）；多节点拓扑刷新调优是后续项。

#### 配置项（`server.netty.websocket.cluster.redis.*`）

| 配置项 | 默认 | 说明 |
|---|---|---|
| `uri` | `redis://localhost:6379` | 单点或 sentinel；按 scheme 选择拓扑（`redis://`、`rediss://`、`redis-sentinel://`）。 |
| `cluster-nodes` | （空） | 非空 → 切到 RedisClusterClient 传输（RC7 客户端）；与 `reliable.enable=true` 在 RC7 中互斥。 |

#### 向后兼容

纯加性 + opt-in。无 SPI 签名变更，无线格式变更。standalone/sentinel + 单机模式行为不变。

### ⑫ 多节点 Docker 演示 + 跨节点 JSON 广播修复 / Multi-Node Docker Demo (+ cross-node JSON fix)

*Since V1.9.0-RC8.* 新增一个**可运行、CI 守护**的多节点 Docker 演示（`docker-demo/`），把集群特性（RC1–RC7）变成可亲手验证的活制品：2 个应用节点 + nginx 负载均衡 + 单机 Redis；浏览器聊天里在 node-a 上发的消息会带 `(via node-a)` 标记出现在 node-b 的浏览器中——**跨节点广播肉眼可证**。演示应用经 `cluster` Spring profile 启用集群（默认单机不变），`ChatRoomController` 加了 origin-node 戳记 + `/whoami` 节点徽章（均为**加性**，单机模式字节级不变）。`docker-demo/smoke.js` 为无头跨节点广播断言，并接入一个**路径过滤的 CI 冒烟作业**（构建栈 → 断言 → 拆除）。演示不发布到 Central。

> 🔴 **修复（影响 1.8.0+ 集群用户）：跨节点 JSON 广播此前不可解析。** 多节点演示的端到端冒烟揪出一个潜伏的 published-module 缺陷：`DefaultMessagePayloadCodec` 用字符串拼接序列化 JSON 广播内容，实际调用了内容对象的 `toString()`（`Map` 渲染成 `{a=b}`，**不是** JSON），故**跨节点** JSON 广播在对端落地时不可解析（本地投递走 Jackson 直序列化，未受影响——所以一直没被发现）。现已改为用消息自身的 `ObjectMapper` 编码、并把 `J:` 体解析回 JSON 树再投递，使**跨节点 JSON 投递与本地投递字节级一致**；附 `DefaultMessagePayloadCodecTest`（6 例，锁定 `responseMsg` 与零拷贝 `serializeSharedPayload` 两条投递路径）。**升级即生效，无需配置改动。**

### ⑬ 多 pub/sub 连接（opt-in 入站解码扩展）/ Multi Pub/Sub Connections

*Since V1.9.0-RC8.* 新增配置项 `server.netty.websocket.cluster.pubsub-connections`（int，默认 **1**），把 standalone `RedisPubSubBroker` 的 SUBSCRIBE 入站解码按频道哈希（`floorMod(channel.hashCode(), N)`）分散到 **N** 个 Lettuce pub/sub 连接上——把单连接入站解码扩展到至多 N 个 Lettuce I/O 线程，缓解 `cluster-design.md` 的「墙②」（单 pub/sub 连接 ~80k msg/s 解码天花板）。

- **纯加性、默认关闭**：`1` = 单连接，与既往**字节级一致**（list 单元素，`connectionFor` 恒返回 index 0）。2–4 仅在某节点逼近单连接解码墙时按需开启；范围 `[1,16]`。
- **subscribe-only**：PUBLISH 仍走单连接（fire-and-forget，Lettuce 自动 pipeline，非解码墙）。仅作用于 **standalone** `RedisPubSubBroker`；RC7 的 `RedisClusterModePubSubBroker` 保持单连接（后续项）。
- **并发安全（已验证）**：一个频道恒定落在一个连接上 → 同频道有序；不同频道并行解码。下游入站监听路径（`ClusterMessageSender`）本就并发安全（AtomicLong 计数、按线程 MDC、无状态解码、并发安全的本地 sender）。
- **诚实定位**：`cluster-design.md` 此前将其标为「过早优化」（实测吞吐远低于该墙）；本版作为**默认关闭、零开销的前瞻性开关**落地，并把该 scope 行从 ⏳ 翻到 ✅。

#### 向后兼容（⑫⑬）

均为纯加性 + opt-in。跨节点 JSON 修复为纯正确性修复——旧 `J:` 格式本就不可解析、无人依赖，故无线格式兼容顾虑。`pubsub-connections` 默认 1 = 既往行为。单机模式与 standalone/sentinel 路径不受影响。

### ⑭ NATS broker（ADR-001 规模化档位 / scaling tier）/ NATS Broker

*Since V1.9.0-RC9.* 新增 **`NatsClusterBroker`** —— `ClusterBroker` SPI 的 NATS core pub/sub 实现，作为 ADR-001 的「规模化档位」：当 Redis Pub/Sub 撞上扇出墙时，可经 SPI **零业务改动**切换的传输实现。由 `server.netty.websocket.cluster.nats.servers`（逗号分隔 `nats://host:port,...`）选择；非空时 `NatsClusterBroker` 替换 Redis broker。

> ⚠️ **仅传输层（务必先读）**：NATS 只替换**广播 / 单播传输**；`SessionRegistry` + 心跳 + reaper **仍在 Redis**（ADR-001 的「NATS broker + Redis registry」混合部署）。即一个 NATS 部署**仍需 Redis** 做会话路由——**广播走 NATS，但跨节点单播 / 定向关闭仍依赖 Redis registry**。ADR-001 明确**拒绝** NATS-only（JetStream-KV registry）为 YAGNI（抬高接入门槛，多数 ≤10 节点用户到不了 Redis 天花板）。

- **at-most-once**：core pub/sub，与 `RedisPubSubBroker` 同契约；JetStream 可靠投递（at-least-once）= 后续，平行于 `RedisStreamsReliableBroker`。
- **subjects**：`netty.broadcast.<base64url(uri)>` / `netty.unicast.<base64url(nodeId)>`（base64url 单 token，避开 NATS 非法字符，收发同编码精确匹配）。
- **健康**：NATS `ConnectionListener` 事件驱动 broker 状态 CAS（DISCONNECTED/CLOSED→DEGRADED，(RE)CONNECTED→ACTIVE），同 `RedisConnectionStateListener` 模型。
- **依赖**：`io.nats:jnats` **optional**（设 `nats.servers` 需 jnats 在 classpath，否则无 `ClusterBroker` → 上下文启动失败）；`@ConditionalOnClass` 优雅降级。
- **三路 broker 选择**（顺序无关 SpEL）：`nats.servers` 非空 → NATS；否则 `cluster-nodes` 空 → standalone Redis；否则 → cluster Redis。registry / 心跳恒为 Redis。
- **验证**：真实 `nats:2.10`（Testcontainers）的 broadcast + unicast publish→receive 端到端测试（oracle）+ Mockito 单测 + 上下文选择测试（NATS broker 选中 + `RedisSessionRegistry` 保留）。

#### 向后兼容

纯加性 + opt-in。无 SPI 变更，无信封字段变更。`nats.servers` 空（默认）⇒ 行为与 RC8 一致；`Connection` / `NatsClusterBroker` bean 仅在 jnats 在 classpath **且** `nats.servers` 非空时存在。NATS 上的线格式与 Redis 上一致（同一 `EnvelopeCodec` 输出）。

### ⑮ 全 NATS 栈（NATS-only registry）/ Full NATS Stack

*Since V1.9.0-RC10.* 在 RC9 NATS broker 之上补齐**最后一块**：NATS **JetStream-KV** 实现的 `SessionRegistry` / `ClusterNodeHeartbeat` / `ClusterReaper`——由 `server.netty.websocket.cluster.nats.registry=true` 选择，让一个部署**完全跑在 NATS 上、不依赖 Redis**。至此「自选中间件」三档齐全：**all-Redis**（`nats.servers` 空）/ **mixed**（NATS broker + Redis registry，RC9 默认）/ **all-NATS**（`nats.registry=true`，无 Redis）。

> ⚠️ **需 JetStream（务必先读）**：all-NATS 需一台 **JetStream-enabled NATS 服务器**（`nats-server -js`）——比 RC9 mixed 的 core pub/sub 更重。**定位诚实**：registry 从来不是扩展瓶颈（瓶颈是广播扇出墙，RC9 已上 NATS），故 all-NATS 是**运维便利**（单中间件），**非性能**。ADR-001 已更新：NATS-only 从「拒绝」改为 **opt-in**（mixed / all-Redis 仍为默认）。

- **三个 JetStream-KV 实现**（`…cluster.nats`，均 `@ConditionalOnMissingBean`，镜像各自 Redis 兄弟的契约）：`NatsKvSessionRegistry`（bucket `netty-sessions`；KV 键为 NATS 合法的 base64url + `.` 分隔，非原子 deregister）、`NatsKvNodeHeartbeat`（**单** bucket `netty-nodes`，**时间戳活性**——不依赖 KV maxAge 清除时序）、`NatsKvReaper`（bucket `netty-reaping`，KV `create` 原子 create-if-absent = `SET NX` 对应）。
- **双连接**：RC9 broker 仍独占其 core pub/sub 连接（不动，避免初始化时序竞态）；另起一条 JetStream 连接服务 KV registry。
- **五档自动装配矩阵**（顺序无关 SpEL）：每个 Redis registry/心跳/reaper/client/reliable bean 加 `&& NOT_ALL_NATS`，三个 NATS-KV bean 加 `ALL_NATS`；{all-Redis-standalone, all-Redis-cluster, mixed-standalone, mixed-cluster, all-NATS} 每档**恰一** SessionRegistry/Heartbeat/Reaper bean，all-NATS **无任何 RedisClient**。
- **验证**：真实 `nats:2.10 -js`（Testcontainers）的 KV register/lookup/deregister 往返、心跳过期检测、reaper 单赢家测试（oracle，经验验证 jnats `KeyValue` API）+ Mockito 单测 + all-NATS 上下文测试（NATS-KV bean 选中 + `doesNotHaveBean(RedisClient)`）。

#### 向后兼容

纯加性 + opt-in。`nats.registry` 默认 `false` ⇒ 行为与 RC9（mixed）及 all-Redis **字节级一致**。无 SPI 变更。NATS-KV bean 仅在 jnats 在 classpath **且** `nats.servers` 非空 **且** `nats.registry=true` 时存在。

### ⑰ 1.9.1 backlog polish 收尾 / 1.9.1 backlog polish

*Since V1.9.0-RC12.* 把 RC11 预发布审计存档的 8 项 LOW/NIT 落地（L1 仍推迟，需要自定义 Spring `Condition` 类才能覆盖「用户重写全部 4 个 Redis SPI bean」的边界场景）。**无 SPI 签名变更，无线格式变更**；除 **L3** 外无默认行为变更（详见下文升级提示）。

#### 修复项

- **L2 — NATS KV `register()` 写顺序反转**：membership 键先于 session 键写入；若 session 键 put 失败，cleanup 仍可经 membership 找到 orphan，避免泄漏会话记录。`removeAllForNode` 已迭代 membership 键并重建会话键删除，与新写序天然契合。
- **L3 — Redis 三个 broker 的入站大小限制改按 UTF-8 字节计**：`String.length()`（UTF-16 char 数）→ `getBytes(UTF_8).length`（字节数）。与 `NatsClusterBroker` 既有的字节判定一致，字段名 `inboundMaxBytes` 名实相符。**⚠️ 轻微行为变更（仅集群模式）**：先前对多字节字符按 char 数放行的请求，现在按字节严格判定。**操作建议**——默认 codec（ASCII / Base64）字节级一致；自定义 codec 且 payload 含非 ASCII 字符的用户应**核查 `message-max-size-bytes`** 设置（若原本依赖字符 / 字节比例放行，请按实际字节数适度上调）。
- **L4 — `ClusterNodeManager.doReconciliation` 死节点清理 chained 链路**：`sessionRegistry.removeAllForNode(deadNodeId)` 改为 `thenRunAsync(deregister, reconScheduler)`；清理失败时 `.exceptionally` 仅记日志、**不 deregister**，留待下一轮 sweep 重试。chained 块开头加 `state.get() == LEFT` 守护；调度被拒（reconScheduler 已 shutdown）走 `RejectedExecutionException` 静默降级。
- **L5 — NATS reaper IT 增加 maxAge + 过期/重认领断言**：`netty-reaping` bucket 在测试中以 `ttl(10s)` 创建（与生产 30s `ensureBucket` 同形式，缩短便于测试）；新增 `reaper_claimExpires_thenReclaimSucceeds`，r1 认领后等待 12 s 跨过 TTL，r2 重认领成功。
- **L6 — `sendMessage` 远端路径同时按 `broker.state() == ACTIVE` gate**：redis-loss 宽限期内 `nodeManager.state` 仍 ACTIVE 但 `broker.state` 已 DEGRADED 时直接 short-circuit，不再走 2 s `command-timeout` 等 Redis lookup。**未变更**：`closeSession()` 与 `topicMessage()` 路径仍仅按 `nodeManager.state` gate（仅一次浪费 lookup，非正确性问题，列入 1.9.1+ backlog）。
- **L7 — `ClusterNodeManager.shutdown()` await scheduler termination 后再 deregister**：`heartbeatScheduler` / `reconScheduler` 顺序 `.shutdown()` + `.awaitTermination(5s)`（超时降级 `shutdownNow`，`InterruptedException` 恢复中断标志）→ 才 `heartbeat.deregister(nodeId)`。杜绝任何 in-flight `doHeartbeat()` / `doReconciliation()` 任务在 deregister 之后访问 Redis。RC11 的 DRAINING/LEFT 转换与 `Thread.sleep(drainTimeoutMs)` 序列原位保留。
- **L8 — `RedisStreamsReliableBroker` 接 Lettuce `RedisConnectionStateListener`**：与 `RedisPubSubBroker` 同 CAS 模式（`onRedisDisconnected` → ACTIVE→DEGRADED；`onRedisConnected` → DEGRADED→ACTIVE；不覆写 SHUTDOWN）。`/actuator/health` 现在如实反映可靠 broker 传输健康，与 Pub/Sub broker 对齐。
- **N1 — `NatsKvSessionRegistry.removeAllForNode` 空 URI guard 修正**：`if (dot > 0)` → `if (dot >= 0)`，让 `@MessageMapping("")` 路径产生的 `s..<sid>` 会话键也被正确删除。

#### 新增测试（+15，全部本地真实运行）

| 测试 | 覆盖项 |
|---|---|
| `NatsKvSessionRegistryTest.register_writesMembershipKeyBeforeSessionKey` | L2 写序 InOrder 断言 |
| `NatsKvSessionRegistryTest.removeAllForNode_deletesSessionKeysForBothEmptyAndNormalUris` | N1 空 URI 等值边界 |
| `RedisBrokerInboundSizingTest`（6 例，3 broker × 2 路径） | L3 emoji UTF-8 字节 cap |
| `ReliableBroadcastIntegrationTest.reliableBroker_*degraded*` + Mockito spy 互补对 | L8 真实 `killContainerCmd` DEGRADED + 直接 listener 注入 ACTIVE 回归 |
| `ClusterNodeManagerReliabilityTest.shutdownAwaitsSchedulerTerminationBeforeDeregister` | L7 时序 |
| `ClusterNodeManagerReliabilityTest.reconciliationChainsRemoveAllForNodeBeforeDeregister` | L4 chained 顺序 |
| `ClusterNodeManagerReliabilityTest.reconciliationDoesNotDeregisterDeadNodeWhenCleanupFails` | L4 失败留待重试 |
| `NatsKvIntegrationTest.reaper_claimExpires_thenReclaimSucceeds` | L5 真实 TTL 过期/重认领 |
| `ClusterMessageSenderTest.sendMessageShortCircuitsRemoteWhenBrokerDegraded` | L6 broker-state gate |

#### 升级提示

- 单机模式（`cluster.enable=false` 或默认）：与 1.7.x / 1.8.0 / RC11 **字节级一致**，零影响。
- 集群模式且使用**默认 codec**（ASCII / Base64）：**字节级一致**，零配置改动。
- 集群模式且使用**自定义 codec 且 payload 含非 ASCII**：核查 `message-max-size-bytes` 设置——若原本依赖 char/byte 比例隐式放行，请按实际字节数显式上调（建议放在 `~16384` 以上以覆盖通常的中文/emoji JSON）。
- 无 SPI / 信封格式变更；运维行为变更仅 L3 一项，且仅影响多字节 payload 的临界尺寸。

#### 推迟项（落入 1.9.1+ backlog）

- L1（user-overrides-all-4-Redis-SPI-beans 时仍创建空闲 RedisClient）：需要自定义 `Condition` 检查 4 个 SPI 接口的覆盖状态——非平凡设计，独立周期处理。
- `closeSession()` / `topicMessage()` 也按 `broker.state()` gate（与 L6 一致的 short-circuit），仅一次 lookup 浪费，非正确性。
- 文档优化（@Tag("slow") 标注 12s 测试、L8 IT 15s 超时背景注释、UTF_8 导入风格统一等）。

---

### ⑱ NATS JetStream 可靠投递 / NATS JetStream Reliable Broadcast

*Since V1.9.0-RC13.* 关闭 all-NATS 部署下的可靠投递缺口（此前为「Known Limitation」）：新增 `NatsJetStreamReliableBroker`（at-least-once 可靠广播，opt-in），是 RC2 `RedisStreamsReliableBroker` 的 NATS 对偶。**仅当** `reliable.enable=true` **且** `nats.registry=true` 时激活，其他档位字节级不变（all-Redis / 混合 / 集群模式 / 全 NATS 但 `reliable.enable=false`）。

#### 机制

- **流名**：`netty-cluster-reliable-<b64url(uri)>`（每 URI 一条流；NATS-legal base64url 不含 `:`/`.`）。
- **subject**：`netty.reliable.<b64url(uri)>`。
- **耐久消费者**：`g_<b64url(nodeId)>`（每节点一份；jnats 不允许 durable 名含 `.`，使用 `_` 作分隔符）。
- **流配置**：FILE 存储、`Limits` retention、`Old` discard、`maxMessages=reliable.stream-max-len`（默认 10000）、`maxAge=0`（仅按 MAXMSGS 修剪）、`replicas=1`（HA 用户必须自行覆写 bean 设 `replicas≥3`，否则 leader 故障 = backlog 丢失）。
- **消费者配置**：`ackPolicy=Explicit`、`deliverPolicy=All`、`filterSubject=netty.reliable.<b64uri>`。
- **拉取循环**：每个订阅 URI 一条专用守护线程（`nats-reliable-<nodeIdShort>-<hex(uri.hashCode())>`），调用 `JetStreamSubscription.fetch(reliable.poll-count, Duration.ofMillis(reliable.poll-block-ms))`；本地投递成功后 `msg.ack()`；监听器抛异常时仍 `msg.ack()`（poison-pill 保护，避免 livelock）。
- **同源抑制**：与 RedisStreams 实现一致——本节点发出的 envelope 在订阅端被 `msg.ack()` 并跳过监听器。
- **dedup window**：进程内基于 JetStream 流序列号（`msg.metaData().streamSequence()`）的 LRU 环（默认 1024，由 `reliable.dedup-window` 控制）。
- **HMAC**：与 RedisStreams 实现一致，wrap/unwrap 发生在 broker **内部**（envelope codec 编/解码与 JetStream publish/fetch 之间）；无 HMAC 时 NoOp 透传。
- **入站大小护栏**：`inboundMaxBytes`（UTF-8 字节）；超限 ACK + drop（不重投）。auto-config 注入 `messageMaxSizeBytes * 2`（与 Redis 一致）。
- **DEGRADED 状态**：通过 NATS `Connection.addConnectionListener` 监听 `DISCONNECTED`/`CLOSED`/`RECONNECTED`/`CONNECTED`，CAS 切换 ACTIVE↔DEGRADED；不覆写 SHUTDOWN。`/actuator/health` 如实反映。
- **死节点消费者清理**：`destroyConsumerGroupsForNode(deadNodeId)` 复用 RC11 idle-gate 模式——仅当 `ConsumerInfo.delivered.lastActive` 早于 `group-destroy-idle-ms`（默认 1 小时）**且** `numPending==0` 才 `deleteConsumer`；任何疑点保留。`0` = 永不销毁。

#### 投递契约（与 §⑥ Redis Streams 一致）

- **本地永不丢**：`reliableBroadcast(uri, msg)` 先本地 fan-out（同步 `localSender.topicMessage()`），失败抛异常给调用方；之后才异步 `js.publishAsync(...)` 跨节点持久化。
- **跨节点 at-least-once**：耐久消费者游标驱动 replay-on-resync——节点崩溃/网络中断后重启重订阅 → JetStream 从上次 ack 位继续投递 backlog。
- **保留窗口**：受 `reliable.stream-max-len`（`DiscardPolicy.Old`）约束——离线时间超过 MAXMSGS 修剪窗口的 backlog 会被裁掉（与 Redis Streams 一致；同样的 bounded gap）。
- **处理器幂等性**：监听器必须幂等——dedup window 仅覆盖单进程内重投，节点重启或跨进程仍可能见到重复。

#### 配置项

复用 §⑥ 现有 6 个 `reliable.*` 配置（无新增配置键）；语义在 NATS 下的映射见 `additional-spring-configuration-metadata.json` 描述里的括号注释。

#### ⚠️ 操作要点

- **NATS server max_payload**：JetStream 与核心 NATS 共享 `max_payload`（默认 1 MB）；envelope Base64 后 (~+37%) 加上 HMAC 头部，wire body 可能超过 `max_payload`。**操作建议**——下调 `message-max-size-bytes` 或抬高 NATS server `max_payload`。超限消息由 `on-publish-failure` 处理；本地投递不受影响。
- **`replicas=1` 默认**：单节点 NATS 部署无影响；**clustered NATS HA 用户必须覆写 bean** 设 `replicas≥3`，否则 leader 故障会丢失 backlog。
- **FILE 存储**：耐久跨 NATS 重启，但每次 publish 产生磁盘 I/O；非持久化场景可自定义 bean 改为 `StorageType.Memory`。
- **TLS / 凭证**：可靠 broker 复用 RC9 的 `nettyClusterNatsKvConnection` 连接（`warnIfInsecureNats` 已就位）；无新增认证路径。**威胁模型** PUBLISH-ACL 劫持：将 NATS PUBLISH ACL 限制在权威应用节点；监控 stream stats 异常。

#### RC12 → RC13 升级路径

- RC12 下 `nats.registry=true && reliable.enable=false`（此前是 all-NATS 唯一支持的可靠投递状态——即「不支持」）→ RC13 下可安全开启 `reliable.enable=true`：
  - **无先前消息状态**（RC12 all-NATS 下可靠投递从未激活）→ 无数据丢失风险。
  - 新增运维约束：**§4.1 max_payload caveat**——审计 `message-max-size-bytes` 对 NATS server `max_payload` 的比值。
  - **无 SPI / 信封格式变更**。`ClusterMessageSender` API 与 envelope bytes 与 RC12 字节级一致。
- 用户自定义 `@Bean ReliableBroker` 覆写（RC10–RC12 期间的临时方案）仍胜出（`@ConditionalOnMissingBean(ReliableBroker.class)` 在全部档位的 broker bean 上都生效）—— **无需任何升级动作**。

#### 选择矩阵（RC13）

| 档位 | `ReliableBroker`（当 `reliable.enable=true`） |
|---|---|
| all-Redis standalone | `RedisStreamsReliableBroker`（不变） |
| all-Redis cluster | 不支持（RC7 互斥，不变） |
| 混合 standalone（NATS broker + Redis registry） | `RedisStreamsReliableBroker`（不变） |
| 混合 cluster（NATS broker + Redis cluster） | 不支持（不变） |
| **all-NATS** (`nats.registry=true`) | **`NatsJetStreamReliableBroker`（RC13 新）** |

---

### ⑲ RC14 polish 打包 / RC14 polish bundle

*Since V1.9.0-RC14.* 6 项 backlog polish 落地（无 SPI 变更、无线格式变更、无新增配置键、除 Q5 pathological URI 外无行为变更）：

- **P1 — `closeSession()` / `topicMessage()` 远端路径也按 `broker.state() == ACTIVE` gate**：与 RC12 L6 `sendMessage()` 对齐——redis-loss 宽限期内 broker 已 DEGRADED 时 short-circuit，节省一次 ≤2 s `command-timeout-ms` 注定失败的 lookup（`closeSession`）或一次注定失败的 publish（`topicMessage`）。仅内部收紧；先前成功的操作仍成功（gate 只在 broker DEGRADED 时触发——同样状态下 downstream publish/unicast 本就会失败）。
- **P5 — 风格统一**：NATS-KV `removeAllForNode` 中 RC11 L2 关于 `dot>=0` 的 4 行注释压缩为 2 行；`RedisPubSubBroker` / `RedisClusterModePubSubBroker` / `RedisStreamsReliableBroker` 中内联的 `java.nio.charset.StandardCharsets.UTF_8` 改为 `import StandardCharsets` 形式，与测试代码一致。
- **P6 — 慢 CI margin**：`ClusterNodeManagerReliabilityTest.shutdownAwaitsSchedulerTerminationBeforeDeregister` 的 reconciliation 等待 latch 从 2 s 改 5 s（50 ms 间隔——从 40-cycle margin 提升到 100-cycle）；断言内容不变。
- **Q5 — JetStream stream-name 长度 guard**：`NatsJetStreamReliableBroker.ensureStream()` 在任何 jsm round-trip **之前**预检 `streamName.length() > 255` → 抛 `ClusterBrokerException("Stream name too long: ...")`，诊断比 jnats 自身错误更直接。**仅影响 pathological 长 URI**（先前在 jnats 抛错；现在在 broker 抛清晰诊断）。
- **Q6 — RC13 spec §3 明确 Connection bean qualifier**（`@Qualifier("nettyClusterNatsKvConnection")` — 与 RC10 建立）。纯文档。
- **Q7 — RC13 spec §4 表格同步代码实际使用的 `g_<b64url(nodeId)>`**：jnats client-side validator 拒绝 durable name 含 `.`（会被解析为 subject token），RC13 实现期发现并改用 `_` 作分隔符；spec 表格未同步，本次修正（含 §5 consume / §5 dead-node cleanup 伪代码 + §7 测试描述 + 自审一致性检查全部对齐）。

#### Q4 — DedupRing capacity（reviewer false positive，**不修复**）

经 RC14 brainstorm 复核：`DedupRing` 内部 `LinkedHashMap(cap*2, 0.75f, true)` 中的 `cap*2` 是 **table-capacity 哈希提示**（避免 rehash），不是元素阈值；`removeEldestEntry` 返回 `size() > cap` 在每次 `put` 后立即驱逐 eldest，元素数严格上限 `cap`。reviewer 把 table-capacity 和元素阈值混淆了。不予修改。

#### 向后兼容

纯 polish。Q5 仅影响 pathological 长 URI（>175 字节左右的 ASCII URI——先前在 jnats 抛错；现在在 broker 抛 `ClusterBrokerException` 含清晰诊断）。其他项 0 行为变更：P1 是内部收紧（先前成功仍成功），P5 是字节级等价，P6 是测试 margin，Q6/Q7 是 doc-only。无 SPI 签名变更，无线格式变更，无新配置项。

---

### ⑯ 预发布安全审计硬化 / Pre-GA Security Audit Hardening

*Since V1.9.0-RC11.* 对 RC1–RC10 全量代码执行 **8 维度多代理对抗式审计**（brokers、heartbeat-reaper、session-registries、autoconfig-matrix、cluster-message-sender、cluster-node-manager、concurrency-lifecycle、docs-codec-metrics-security），24 项发现（3 HIGH、9 MEDIUM、11 LOW、1 NIT）；HIGH + MEDIUM + 文档不一致共 15 项修复落地，LOW/NIT 存入 `docs/pre-ga-audit-backlog.md` 留待 1.9.1。

#### HIGH（3 项）

- **NATS broker SPI 契约违反**：`NatsClusterBroker.publish()`/`unicast()` 原来泄漏 jnats `IllegalStateException`/`IllegalArgumentException`——违反 `ClusterBroker` SPI 的 `ClusterBrokerException` 契约。现已 try-catch 包装，下游 `on-publish-failure` 策略正常触发。
- **NATS heartbeat 静默吞异常**：`NatsKvNodeHeartbeat.register()`/`renewHeartbeat()` 原来 catch-log-swallow 所有异常——导致 `ClusterNodeManager` 永远不知道心跳写入失败，节点不会进入 DEGRADED。现已 rethrow 为 `RuntimeException`（`deregister()`/`findExpiredNodes()` 仍容忍异常）。
- **可靠 broker 死节点消费者组清理过激**：`destroyConsumerGroupsForNode` 原来无条件销毁——同 id 节点崩溃重启后其 PEL 被清除，导致回放丢失。现改为 **idle-gate**（`XINFO GROUPS`）：仅销毁 zero-pending **且** 空闲超过 `group-destroy-idle-ms`（默认 1 小时）的消费者组；有任何疑点则保留。

#### MEDIUM（9 项）

- **NATS KV registry 无专用线程池**：`supplyAsync`/`runAsync` 未指定 executor——跑在 ForkJoinPool 上，可能饿死其他 `CompletableFuture` 任务。现改为专用 `newFixedThreadPool(4)`（`nats-kv-registry-*`），`shutdown()` 时同步关闭。
- **Redis 可靠 broker 无入站大小限制**：接收端无上限——恶意或异常的超大条目可能 OOM。现增 `inboundMaxBytes`（默认 `messageMaxSizeBytes * 2`），超限条目 ACK + drop（不累积 PEL）。
- **Redis registry 键中 URI 直拼**：特殊字符 URI 可能导致键格式歧义或注入。现改为 **base64url 编码**（`RedisSessionRegistry` 和 `RedisClusterModeSessionRegistry` 对称修复）。
- **`ClusterMessageSender.nodeCache` 无界**：单播路径的 sessionId→nodeId 缓存无上限——大量不同会话的单播会无限增长。现改为 **access-order LRU `LinkedHashMap`**，默认上限 100 000 条（`registry-read-cache-max-size`）。
- **`ClusterNodeManager` RESYNC 可复活 LEFT**：shutdown 后 RESYNC 定时器仍可能在 `lifecycleLock` 外触发状态恢复。现 `transitionTo()` 对 `current==LEFT` 直接 no-op；RESYNC future 在 shutdown 中 cancel。
- **`ClusterNodeManager.shutdown()` drain 逻辑**：60s 默认 `drain-timeout-seconds` 让每次关闭盲等 60s。改默认为 **0**（即时注销 = 1.8.0 行为），正值 = 显式 opt-in。
- **NATS classpath 无 fail-fast**：`nats.servers` 配了但 jnats 不在 classpath 时，无 `ClusterBroker` bean → Spring 上下文报晦涩的 `NoSuchBeanDefinitionException`。新增 `@ConditionalOnExpression(NATS_TRANSPORT) + @ConditionalOnMissingClass("io.nats.client.Connection")` 守卫 → 抛 `IllegalStateException("nats.servers is set but io.nats:jnats is not on the classpath")`。
- **NATS 日志 URI 未脱敏**：启动日志直接输出 `nats.servers`（可能含 `nats://user:pass@host`）。新增 `redactServerUris()` 按条目掩盖 `://user[:pass]@`，与 Redis URI 脱敏对齐。
- **可靠 broker knobs 未接线**：`inboundMaxBytes` 和 `groupDestroyIdleMs` 在 auto-config 中未从 `ClusterProperties` 注入。已补线 + 新增 `additional-spring-configuration-metadata.json` 条目。

#### 文档修复（3 项）

- release-notes 已知限制章节：移除已落地的功能条目（NATS / 多 pub/sub / Docker 演示），保留真正未实现项（sharded pub/sub / Observation），新增 NATS 可靠投递缺口 + 1.9.1 backlog 指针。
- 配置参考 YAML：从"两个新条目"改为完整 RC1–RC11 全配置参考（含 `registry-read-cache-max-size`、`group-destroy-idle-ms`、`drain-timeout-seconds=0`、NATS `max_payload` 提示）。
- `cluster-design.md` 可观测性章节：标注为设计愿景/已部分实现，指向 `api-guide.md §9` 作为已出货的权威指标列表。

#### 新增配置项（RC11）

| 配置项 | 默认 | 说明 |
|---|---|---|
| `registry-read-cache-max-size` | `100000` | 单播 sessionId→nodeId 缓存最大条目（LRU 淘汰），防无界增长；`0` 或负值 = 不限（旧行为）。 |
| `reliable.group-destroy-idle-ms` | `3600000` | 死节点消费者组空闲超过此时间（ms）才允许被清理；防崩溃重启节点回放丢失。`0` 或负值 = 永不清理。 |
| `drain-timeout-seconds` | `0`（**默认值变更**） | 从 60 改为 0（即时注销 = pre-1.9.0 行为）；>0 = 显式 opt-in 优雅排空窗口。 |

#### 向后兼容

纯内部修复 + 防御性增强。无 SPI 签名变更，无线格式变更。`drain-timeout-seconds` 默认从 60 改为 0——对从 1.8.0 升级的用户而言是**恢复**旧行为（1.8.0 无 drain 概念，即 0）；仅从 RC1–RC10 升级且曾依赖默认 60s drain 的用户需显式设值。

---

### ⑳ RC15 测试覆盖加固 / RC15 test/IT coverage hardening

*Since V1.9.0-RC15.* 8 项 backlog 落地（**无 SPI 变更、无线格式变更、无新配置键、无 Java 行为变更**——纯测试 / 日志 / 文档）：

- **Q1 — NATS reliable IT 覆盖 DEGRADED→ACTIVE 恢复**：`killContainerCmd` 验证 DEGRADED 后 `container.start()` + 30 s 轮询 ACTIVE；为稳定测试 URL 使用 `ServerSocket(0)` + `withCreateContainerCmdModifier` 绑定固定主机端口（先前 RC13 IT 仅覆盖 ACTIVE→DEGRADED 单向）。
- **Q2 — NATS reliable HMAC 正向 round-trip IT**：匹配密钥下消息抵达；与 Q5(RC13) 反向拒绝 IT 互补，完成 HMAC 双向 IT 覆盖。
- **Q3 — NATS reliable DEGRADED-publish-doesn't-throw IT**：验证 spec §5.1 informational 语义——DEGRADED 期间 `reliablePublish(...)` 不抛异常（与 `RedisStreamsReliableBroker` 行为一致）。
- **P2 — NATS-KV reaper IT `@Tag("slow")` 注解**：12 s+ 测试可被 CI profile 选择性跳过（`-DexcludedGroups=slow`）；类头加入约定注释。
- **P3 — L8 IT `degradedDeadline=15s` 由来注释**：Docker kill 延迟（~1s）+ Lettuce channel-inactive 检测（~1-3s）+ listener-CAS budget。
- **P4 — NATS-KV reaper IT 轮询取代盲等**：`Thread.sleep(12_000)` → `Thread.sleep(11_000) + poll-until-success (max 4 s, 100ms 间隔)`，对 JetStream housekeeping jitter 慢 CI 更稳；实际典型耗时 ~11 s（先前 12 s）。
- **R1 — `ClusterMessageSender.topicMessage` DEGRADED-else 日志包含 `broker.state()`**：原日志 `"... node state is {} ..."` → `"... node state is {}, broker state is {} ..."`。redis-loss 宽限期内 node ACTIVE 但 broker DEGRADED 的真相现在日志中可见。**加性追加**，不破坏既有 `"node state is"` 子串匹配。
- **R2 — `ClusterMessageSender.closeSession` javadoc 明确 false-on-DEGRADED 重载语义**：caller 无法区分「无 session」与「transport degraded」，与 RC12 L6 `sendMessage` 对齐；明示给应用层。

#### 向后兼容

纯测试 / 日志格式 / javadoc 变更。R1 日志为加性追加（`"node state is"` 前缀保留）；R2 仅澄清既有语义，无行为变更。0 SPI / 配置 / wire 影响。

#### RC15 实现笔记（implementation notes）

- **Q1+Q3 Testcontainers 固定端口**：默认 random host-port 在 `start()` 后会变化导致 jnats 重连失败。通过 `ServerSocket(0)` 预选端口 + `withCreateContainerCmdModifier` 锁定 host→4222 映射，使重启后 URL 不变。
- **Q3 重连后 streamCache 局限**：当前 `NatsJetStreamReliableBroker.streamCache` 不在重连后重新校验，所以 DEGRADED 期间通过同一 broker 的 subscriber 收不到 post-reconnect 消息。**生产场景下不是问题**（FILE storage 持久化使流跨 NATS 重启幸存；缓存依然有效）；测试场景下 Q3 通过在新 URI 上 spawn 新 broker 对来验证 transport 层恢复，避开此限制。Backlog 添加 **S1**（broker reconnect 后 invalidate stream cache）作为后续 hardening。**[由 RC16 §㉑ 落地。]**

---

### ㉑ 1.9.x backlog 清零 / 1.9.x backlog cleanup

*Since V1.9.0-RC16.* 2 项 backlog 收尾，**1.9.x backlog 至此清空**（**无 SPI 变更、无线格式变更、无新配置键，加性 only**）：

- **L1 — `OnAnyRedisSpiRequired` Spring `Condition`**：用户重写**全部** 4 个 Redis-backed SPI bean（`SessionRegistry` / `ClusterBroker` / `ClusterNodeHeartbeat` / `ClusterReaper`）时，不再创建空闲 `RedisClient` + 连接。**仅影响该 niche 场景**——任何部分覆盖（即便只有 1 个 SPI bean 留默认 Redis 实现）都会**继续**创建 `RedisClient`，与 RC15 字节级一致。`@Conditional(OnAnyRedisSpiRequired.class)` 与既有 `@ConditionalOnExpression(STANDALONE_REDIS_REGISTRY)` + `@ConditionalOnMissingBean` AND 起作用，仅 narrow（never widen）bean 创建条件。位于 `cluster.support` 包，`ConfigurationPhase.REGISTER_BEAN` 确保看到 user `@Bean` 定义。
- **S1 — `NatsJetStreamReliableBroker.streamCache` 在重连时失效**：既有 `ConnectionListener.onReconnect`（RC13 §3）在 CAS DEGRADED→ACTIVE 之前调用 `streamCache.clear()`（防御式：即便状态已 ACTIVE 也清缓存）。next publish 走 `ensureStream(...)` 重新校验（已 idempotent + mismatch-detecting，RC13 §5.1）。每 URI 每次重连多一次 `getStreamInfo` round-trip（重连罕见 + URI 数量小，可忽略）。**Production 场景**（FILE storage + 标准 NATS restart）此前也无问题，本修复消除 RC15 IT 期间发现的测试场景对 ephemeral 数据丢失的隐式假设。

#### 向后兼容

加性变更。L1 只影响 niche 场景（无 `RedisClient` → 不再创建空闲连接，节约资源）；S1 重连后多一次 `getStreamInfo`（量级小，rare event）。0 SPI / 配置 / wire 影响。

#### 1.9.x backlog 状态

RC16 之后 1.9.x backlog **清空**——只剩 RC11/RC14 期间标记为 Refuted 的项（不予修复）。**1.9.0 GA 可在 RC16 之上直接 cut**（用户驱动；本仓库流程不自动 cut GA）。**[RC17 GA-readiness 终审进一步确认 GA-readiness，见 §㉒。]**

---

### ㉒ GA-readiness 终审 / GA-readiness final audit

*Since V1.9.0-RC17.* 在 1.9.0 GA cut 前做一轮**独立的 10 维度 GA-readiness 终审**（与 RC11 pre-GA audit 平行 + 互补，但这次是对 RC1→RC16 整 16-RC 累积成果做终查，含 RC11→RC16 polish/feature RC）。**审计结论：GA_READY_AFTER_FIXES → 3 项 must-fix 在本 RC 修复，1.9.0 GA 可在 RC17 之上直接 cut**。完整审计报告：`docs/audits/2026-06-07-ga-readiness-final.md`（人类可读叙事）+ `docs/audits/2026-06-07-ga-readiness-final.json`（14-agent 工作流原始输出）。

#### 10 维度审计结果

| 维度 | 结果 | 一句话 |
|---|---|---|
| D1 累积正确性（重审 24 项 pre-GA 修复仍在位） | **CLEAN** | 24 项 RC11 pre-GA 修复 + RC12-RC16 polish 全部仍在位，无回归 |
| D2 跨 RC 交互风险（5 个多 RC 文件） | NITS_ONLY | thread-safety / lifecycle / 状态 CAS / bean 条件矩阵均健全，2 项注释 polish |
| D3 安全态势 | **CLEAN** | HMAC end-to-end + 常量时间比较 + 凭证脱敏 + 0 secret-in-logs 路径 |
| D4 性能态势 | **CLEAN** | RC11→RC16 热路径无静默性能回归 |
| D5 API/SPI 兼容性 | **CLEAN** | 零破坏改动；`ClusterProperties` 新增字段是加性 getter/setter；唯一默认值变化（`drain-timeout-seconds` 60→0）已显式标注 |
| D6 文档完整性 | NITS_ONLY | 4 项小问题：README/api-guide 1.8.0 陈旧版本号 + roadmap 状态 + 双语风格小漏（**RC17 顺手修复**） |
| D7 配置完整性 | **NEEDS_FIX** | **2 项 metadata.json 漏配**：`trace-propagation.enable`（RC6）+ `redis.cluster-nodes`（RC7）（**RC17 修复**） |
| D8 测试覆盖盲区 | **CLEAN** | 每条生产分支均有测试；5 档配置矩阵每档有 context test |
| D9 Release 工程 | **NEEDS_FIX** | **39 个集群测试文件缺 Apache 2.0 头**（policy violation，**RC17 修复**） |
| D10 嗅探 / GA blocker 扫描 | **CLEAN** | 无 TODO/FIXME/@Disabled/dead code；assumeTrue-skip 全部是基础设施门控（Docker/Redis/NATS）而非失败掩盖 |

#### Must-fix（本 RC 修复，3 项）

- **MF1 (D7)** — `additional-spring-configuration-metadata.json` 新增 `trace-propagation.enable`（Boolean，default `false`，RC6 W3C TraceContext）+ `redis.cluster-nodes`（String，RC7 Redis Cluster 传输选择，与 `reliable.enable` 互斥）。IDE 自动补全恢复发现。
- **MF2 (D9)** — Apache 2.0 头补到 39 个测试文件（32 cluster + 7 starter），匹配 main-sources 既有 `Copyright 2018 berrywang1996` 形式。policy violation 关闭。

#### 顺手修（nice-to-have，本 RC 一起带走）

- README.md / api-guide.md / development-plan.md：1.8.0 → 1.9.0 版本陈旧引用刷新；README "Current Status" 重写概括 RC1-RC16；api-guide §9.1-9.3 添加双语标题；development-plan 路线图状态刷新。

#### 推迟到 1.9.1 backlog 的 nice-to-have

- D2 `NatsJetStreamReliableBroker.shutdown()` snapshot-then-iterate 风格化；`streamCache.clear()` 注释 atomicity 描述软化。
- D6 release-notes 圆圈/罗马数字混用 polish。
- D7 RC7 章节缺 `redis.*` 独立配置表（与 reliable.*/auth.* 对偶的风格统一）。

#### 向后兼容

**0 行为变更、0 SPI 变更、0 wire format 变更、0 配置默认值变更**。RC17 修复全部为：metadata 加性条目 + 测试文件文件头 + 文档版本号陈旧引用 + 风格化双语标题。

#### GA-readiness 结论

**GA_READY_AFTER_FIXES → fixes 已在本 RC17 落地 → 1.9.0 GA 可在 RC17 之上直接 cut。** 最后一步只剩用户授权（详见 `docs/audits/2026-06-07-ga-readiness-final.md` 末尾）。

---

### ㉓ 2.0.0 prep docs / 2.0.0 prep artifacts

*Since V1.9.0-RC19.* 在 1.9.x 周期内为 **2.0.0 (Boot 3.x) cycle** 准备 3 篇前瞻设计稿（**1.9.0 自身 0 功能 / SPI / wire / config / Java 行为变更**；RC17 GA-readiness 认证不受影响）：

- **`docs/2.0.0/sharded-pubsub-feasibility.md`** — `RedisShardedPubSubBroker` 可行性研究：cluster-design §Capacity 的 `M·(f·N-1)` 扇出墙的 sharded pub/sub 解（Lettuce 6.2+ SSUBSCRIBE/SPUBLISH，Redis 7.0+）；fan-out 减少量化模型（k=3/6/12 cluster masters，~83% 削减 @ k=6）+ 自动装配选择（`pubsub-mode=sharded`）+ 测试策略 + 6 项待 2.0.0 验证的技术假设清单（Lettuce 6.4 → ≥ 6.5.5 + chaos failover IT 等）。
- **`docs/2.0.0/boot3-compatibility-matrix.md`** — 11 模块 Boot 2.7→3.x 表面分析。**意外发现**：整个 codebase 只有 **6 个 `javax.*` 文件**，其中 5 个是 JDK `javax.crypto.*`（不需要 rename），仅 1 个 `javax.activation`（jakarta-impacted）。**jakarta sweep 本身是 trivial 的**；2.0.0 cycle 的真正工作量在 Lettuce 6.3 升级 + Observation API trace continuation（RC6 延期项）+ sharded pub/sub。
- **`docs/2.0.0/1.9.0-to-2.0.0-migration-guide.md`** (**DRAFT**) — 用户视角迁移指南：分步骤升级，配置兼容性立场（"1.9.0 yml 在 2.0.0 默认可用"——provisional stance，2.0.0 cycle 可再 softening），新 feature opt-in（`pubsub-mode=sharded` + Observation API trace），DRAFT 标注欢迎反馈。

#### 范围声明（务必先读）

**这 3 篇 docs 是 2.0.0 cycle 的输入，不是 1.9.0 的承诺。** 1.9.0 GA-readiness（RC17 audit 认证）未受影响；2.0.0 cycle 启动是用户驱动的下一步决定，与 1.9.0 GA cut 决策正交。Migration guide 中标注的"1.9.0 yml 兼容"立场仅为起点，2.0.0 cycle 实际开始后可根据需要 softening。

#### 向后兼容

文档全部位于 `docs/2.0.0/`，1.9.0 代码不读这些文件。**0 SPI / wire / config / 行为影响。**

---

### ㉔ 1.9.x cycle retrospective / 1.9.x 周期总结

*Since V1.9.0-RC20.* **`docs/1.9.x-cycle-retrospective.md`** — 19 RC / 168 commit / +25,217 / −288 LOC / 444 测试 / 11 模块的诚实回顾：交付内容、成功模式（多 RC milestone discipline、对抗式 audit、backlog discipline、forward-looking docs 分离）、不成功之处（polish-RC inflation、Q4 false positive、维度重叠、**1.8.0+ 跨节点 JSON 广播 bug 静默存活 6 个月直到 RC8 docker demo 暴露**）、2.0.0 cycle 经验、audit-driven QA 模式作为 2.0.0 模板。

#### 周期收尾声明

**RC20 是 1.9.x cycle 的自然终止标记。**1.9.x backlog 清空、GA-readiness 认证完成、2.0.0 前瞻文档备齐、周期回顾归档。**下一步动作完全由用户驱动：**

| 选项 | 含义 |
|---|---|
| Cut 1.9.0 GA | flip RC20 → `1.9.0`，发布 Maven Central（用户驱动；本仓库流程不自动 cut GA） |
| Start 2.0.0 cycle | 创建 `2.0.0-SNAPSHOT` 分支，按 `docs/2.0.0/boot3-compatibility-matrix.md` 的预测形态启动 Boot 3.x cycle |
| 其他 | 暂停、休息、或新方向 |

**不应继续 RC21+ 而不带新用户指令** —— 否则 marginal value 趋零。

#### 向后兼容

文档专属。**0 SPI / wire / config / 行为影响。**1.9.0 代码不变。

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

所有 1.8.0 已有配置项默认值不变；下面是 1.9.0 全部新增/相关配置项的完整参考（涵盖 RC1–RC10 与预发布硬化项，默认值即为 1.8.0 兼容行为）：

```yaml
server:
  netty:
    websocket:
      cluster:
        enable: false
        node-id: ${HOSTNAME:auto}
        redis:
          uri: redis://localhost:6379
          cluster-nodes: ""                   # （RC7）非空=Redis Cluster 客户端；留空=走 uri 的 standalone/sentinel
        heartbeat-interval-seconds: 3
        heartbeat-timeout-seconds: 10
        reconciliation-interval-seconds: 15
        drain-timeout-seconds: 0              # 0 = 关闭时即时注销（即原行为）；>0 = 注销前的有界优雅排空窗口
        reconnect-jitter-max-seconds: 10
        registry-read-cache-ttl-ms: 5000
        registry-read-cache-max-size: 100000  # （预发布硬化）单播 sessionId→nodeId 缓存最大条目，防无界增长；0 或更小=不限（旧行为）
        command-timeout-ms: 2000
        pubsub-connections: 1                  # （RC8）Pub/Sub SUBSCRIBE 连接数，按频道哈希分摊入站解码；范围 [1,16]
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
          group-destroy-idle-ms: 3600000     # （预发布硬化）死节点消费者组在心跳过期后被清理前的最小空闲窗口（ms），防同 id 节点重启回放丢失；0 或更小=永不清理（纯保留，靠 MAXLEN 裁剪）
        # --- 1.9.0 RC3 新增（HMAC envelope 认证）---
        auth:
          enable: false                       # true = 启用 HMAC-SHA256 信封认证
          secret: ${CLUSTER_AUTH_SECRET}      # 共享密钥，≥32 字符，禁止明文，日志脱敏
          permissive: false                   # true = 宽容模式（签名+接受无签名，滚动升级用）
        # --- 1.9.0 RC6 新增（W3C TraceContext 传播）---
        trace-propagation:
          enable: false                       # true = 跨节点信封携带 traceparent 并在投递侧恢复 MDC（traceId/spanId）
        # --- 1.9.0 RC9/RC10 新增（NATS 传输 / 全 NATS 注册表）---
        nats:
          servers: ""                         # 非空=NatsClusterBroker 替换 Redis 广播（需 io.nats:jnats）
          registry: false                     # true（且 servers 非空）=注册表/心跳/reaper 也走 NATS JetStream KV（需 nats-server -js，全程无 Redis）
```

> NATS 载荷上限提示：NATS 服务端默认 `max_payload` 为 1 MB，而 `message-max-size-bytes` 默认即 1 MiB（1048576），叠加信封 Base64（约 +37%）与可选 HMAC 开销后，**编码后的线上字节可能超过 NATS 的 `max_payload`**。使用 NATS 传输时，请相应**调低 `message-max-size-bytes`** 或**调高 NATS 服务端 `max_payload`**；超限的跨节点消息会按 `on-publish-failure` 策略优雅处理（本地投递不受影响），不再抛出原始 jnats 异常。

## 测试覆盖

- **444 个测试，11 个模块，全部通过**（`mvn test`，Redis + Docker/Testcontainers live；CI 用 Testcontainers 自带 Redis）。
- 1.9.0 新增测试：
  - `ClusterNodeManagerReliabilityTest` — 线程隔离调度器验证、宽限期抑制逻辑、双调度器并发压测
  - `ClusterRegistryWriterTest` — token-bucket 直通路径、超速合并、零丢失断言、并发注册风暴模拟
  - 3 个 `RedisIntegrationTest` 新增用例：原子 Lua deregister 竞态验证、`ClusterReaper` 选主去重端到端、宽限期恢复路径
  - （RC2）`ClusterReliableSenderTest` — reliableBroadcast 本地优先 fan-out、disabled 抛异常、origin 自投递抑制
  - （RC2）`ReliableBroadcastIntegrationTest` — 真实 Redis：发布/消费、断线重连回放（replay-on-resync，5/5）、死节点消费者组清理
  - （RC3）`MessageAuthenticatorTest` + `ClusterAuthIntegrationTest` — HMAC 签名/验签往返、篡改/错误密钥/缺签名拒绝、真实 Redis 同密钥接受 + 异密钥拒绝
  - （RC4）`NettyClusterMeterBinderTest`（`SimpleMeterRegistry` 单测：counter 值、节点/broker 状态 gauge 选态、HMAC 拒绝计数、重复 bind 幂等）+ `NettyWebSocketClusterConfigureTest` 上下文用例（micrometer + `cluster.enable=true` 时 `NettyClusterMeterBinder` bean 装配）
  - （RC5）`ClusterTestRedis` 解析器（localhost-first → Testcontainers `redis:7-alpine` 回退）+ 4 个集群集成测试改用它（CI 真实运行，不再跳过）；`ClusterMultiNodeE2ETest` 双节点全栈 E2E（跨节点广播 + 单播 + `netty.cluster.*` 指标断言，HMAC 开启，真实 Redis）—— 同时锁定跨节点单播 hook-wiring 修复的回归门
  - （RC6）`MdcClusterTraceContextTest`（6 个：显式 traceparent、从 traceId/spanId 合成、64 位左补零、畸形→null、restore 往返 + Scope 清除、NOOP）+ `ClusterTraceIntegrationTest`（真实 Redis：traceparent 跨节点 wire 往返）+ 2 个上下文用例（trace-propagation.enable 开/关时 `ClusterTraceContext` bean 在/不在）
  - （RC7）`ClusterTestRedisClusterSelfTest`（单节点 Redis Cluster 解析器自检）+ `RedisClusterModeSessionRegistryTest` / `…NodeHeartbeatTest` / `…PubSubBrokerTest` / `…ReaperTest`（Mockito 单测：deregister HGET→DEL+SREM 无 eval、per-key EXISTS、setNodeMessagePropagation + 广播频道、SET NX PX）+ `RedisClusterIntegrationTest`（真实单节点 Redis Cluster：注册/查找/注销往返、心跳 TTL 过期检测、集群 pub/sub 发布→接收）+ `NettyWebSocketClusterConfigureTest` 新增上下文用例（`cluster-nodes` 设置时选用 RedisClusterMode* 传输、standalone 让位）
  - （RC8）`DefaultMessagePayloadCodecTest`（6 例：JSON 编码为真 JSON 而非 `Map.toString()`、跨节点投递 == 本地投递、零拷贝 `serializeSharedPayload` 路径、T:/B: 往返、非 JSON `J:` 降级文本）+ `RedisPubSubBrokerMultiConnTest`（3 例：频道→哈希连接路由 + `never()` 其余、确定性、默认单连接）+ `RedisIntegrationTest` 新增 `multiPubSubConnectionsDeliverEveryChannel`（真实 Redis，N=3：5 广播 + 1 单播频道全投递）
  - （RC9）`NatsClusterBrokerTest`（4 例：base64url 广播 subject、subscribe 路由、入站 dispatch 到监听器、连接事件驱动状态）+ `ClusterTestNatsSelfTest`（NATS Testcontainers 解析器自检）+ `NatsIntegrationTest`（真实 `nats:2.10`：broadcast + unicast publish→receive，origin / target 断言）+ `NettyWebSocketClusterConfigureTest` 新增上下文用例（`nats.servers` 设置时选用 `NatsClusterBroker`、registry 仍为 `RedisSessionRegistry`）
  - （RC10）`NatsKvSessionRegistryTest` / `NatsKvNodeHeartbeatTest` / `NatsKvReaperTest`（Mockito 单测：KV `s.`/`n.` 键方案、时间戳活性、`create` 单赢家）+ `NatsKvIntegrationTest`（真实 `nats:2.10 -js`：KV register/lookup/deregister 往返、心跳过期检测、reaper 单赢家——经验验证 jnats `KeyValue` API）+ `NettyWebSocketClusterConfigureTest` 新增 all-NATS 上下文用例（`nats.registry=true` → `NatsKv*` bean 选中 + `doesNotHaveBean(RedisClient)`）。并以 surefire `api.version=1.43` 修复全反应堆 Testcontainers Docker 探测——集群 / NATS / JetStream IT 不再跳过

## 升级指南

### 从 v1.8.0 升级

1. 更新 Maven 依赖版本 → `1.9.0`（所有 `io.github.berrywang1996:netty-*` artifact）。
2. **无需任何代码改动** — 所有 SPI 签名不变；`ClusterReaper` 是新增 SPI（additive）。
3. **注意默认行为变更**：`redis-loss-grace-period-ms` 默认从"无宽限期"变为 5 000 ms。若需完全保留 1.8.0 的即时降级时序，显式设置：
   ```properties
   server.netty.websocket.cluster.redis-loss-grace-period-ms=0
   ```
4. 单机模式（`cluster.enable=false` 或缺省）无任何变化。
5. **（RC5 重要修复）跨节点单播 / 定向关闭**：1.8.0 ~ 1.9.0-RC4 的集群模式下，跨节点单播（`sendMessage` / `sendTextToSession` 到其他节点会话）与跨节点定向关闭因 `ClusterSessionHook` 装配顺序缺陷而**静默失效**（广播不受影响）。本版本已修复，无需任何代码或配置改动——升级即生效。详见上文 §⑨。
6. **（RC7）Redis Cluster 客户端**：纯 opt-in。**现有用户不受影响**——`cluster.redis.cluster-nodes` 留空（默认）时行为与 RC6 完全一致（继续走 `redis.uri` 的 standalone/sentinel 路径）。要切到 Redis Cluster 客户端，设 `cluster-nodes=host:port,...`（注意上文 §⑪ 的常规-vs-sharded pub/sub 边界：RC7 不削减广播扇出）。

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

- **sharded pub/sub（扇出削减，`SSUBSCRIBE`/`SPUBLISH`）**：需 Lettuce 6.2+（Boot 2.7.18 为 6.1.10），推迟到 2.0.0（Boot 3.x）。注：Redis Cluster **客户端**一等支持已在 RC7 落地（HA 故障转移 + 注册表/心跳跨 slot 分布），但 RC7 的常规 cluster pub/sub **不削减**广播扇出——扇出削减正来自这里推迟的 sharded pub/sub。
- **W3C TraceContext 的 Micrometer Observation / 活跃 span 续接**（`traceparent` + MDC 关联已在 RC6 落地；Observation 续接需 Boot 3.x，推迟到 2.0.0）+ 完整 OpenTelemetry instrumentation
- **次要硬化项**：原 1.9.1 backlog 在 RC12/RC14/RC15/RC16 cycle 中已清空（见 docs/pre-ga-audit-backlog.md 的 "Fixed in RCn" 历史归档）；当前没有 known-limitations-list 待办项。

> 注：以下能力**已在 1.9.0 落地**，不再属于已知限制——**NATS broker**（RC9，ADR-001 规模化档位）、**全 NATS 注册表**（RC10，`nats.registry=true`）、**all-NATS JetStream 可靠投递**（RC13，`NatsJetStreamReliableBroker`，`reliable.enable=true && nats.registry=true` 激活）、**多 pub/sub 连接并行解码**（RC8，`pubsub-connections`）、**可运行的多节点 Docker 示例**（RC8，docker-compose + 负载均衡 + 浏览器跨节点演示）、**Redis Cluster 客户端**（RC7）、**Testcontainers 端到端 CI + 进程内双节点 E2E**（RC5）。

详见 `docs/cluster-design.md` 与 `docs/development-plan.md`。
