# Release Notes — v1.9.0 (开发中 / in development)

> 状态：**开发中（1.9.0-RC10，2026-06-05）** — 本文档随 1.9.0 周期累积。RC1 含 5 项可靠性硬化；RC2 新增可靠投递（Redis Streams `reliableBroadcast`，at-least-once，opt-in）；RC3 新增 HMAC envelope 认证（`auth.*` 3 个配置项）；RC4 新增完整 Micrometer 集群指标（`netty.cluster.*` meter-binder）；RC5 新增多节点 E2E + Testcontainers CI，并**修复跨节点单播 hook-wiring 缺陷**（影响 1.8.0~RC4，仅集群模式）；RC6 新增 W3C TraceContext 跨节点 MDC 日志关联（opt-in；Micrometer Observation 续接 → 2.0.0）；RC7 新增第一等 Redis Cluster 客户端支持（`cluster-nodes` 选择 Redis Cluster 传输；常规集群 pub/sub，不削减广播扇出；sharded pub/sub → 2.0.0）；RC8 新增多节点 Docker 演示（含**跨节点 JSON 广播修复**，影响 1.8.0+ 集群用户）与多 pub/sub 连接（opt-in 入站解码扩展，默认 1）；RC9 新增 NATS broker（ADR-001 规模化档位；`NatsClusterBroker` 由 `nats.servers` 选择，**仅传输层**、registry 仍在 Redis）；RC10 新增**全 NATS 栈**（`nats.registry=true` → NATS JetStream-KV registry/心跳/reaper，可完全不依赖 Redis；需 JetStream 服务器；ADR-001 更新为 NATS-only opt-in）。最终 1.9.0 发布日期待整个周期完成后确定。

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

- **381 个测试，11 个模块，全部通过**（`mvn test`，Redis + Docker/Testcontainers live；CI 用 Testcontainers 自带 Redis）。
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

- **NATS broker**（ADR-001 规模化档位）
- **sharded pub/sub（扇出削减，`SSUBSCRIBE`/`SPUBLISH`）**：需 Lettuce 6.2+（Boot 2.7.18 为 6.1.10），推迟到 2.0.0（Boot 3.x）。注：Redis Cluster **客户端**一等支持已在 RC7 落地（HA 故障转移 + 注册表/心跳跨 slot 分布），但 RC7 的常规 cluster pub/sub **不削减**广播扇出——扇出削减正来自这里推迟的 sharded pub/sub。
- **多 pub/sub 连接并行解码**
- **W3C TraceContext 的 Micrometer Observation / 活跃 span 续接**（`traceparent` + MDC 关联已在 RC6 落地；Observation 续接需 Boot 3.x，推迟到 2.0.0）+ 完整 OpenTelemetry instrumentation
- **可运行的多节点 Docker 示例**（docker-compose + 负载均衡 + 浏览器跨节点演示）。注：Testcontainers 端到端 CI + 进程内双节点 E2E 已在 RC5 落地，仍推迟的只是面向人工运行的 Docker 示例

详见 `docs/cluster-design.md` 与 `docs/development-plan.md`。
