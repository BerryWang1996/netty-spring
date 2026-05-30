# 开发计划与阶段状态

更新时间：2026-05-30

## 当前结论

- **当前推荐版本：`1.8.0`**（WebSocket 集群支持：Redis Pub/Sub 跨节点 + 5 层 SPI + 288 测试。单机模式生产级；集群模式面向 ≤~10 节点 + 专用加密 Redis，详见 `docs/release-notes-1.8.0.md` 与 `cluster-design.md §安全模型`）。
- 上一版本：`1.7.1`（在 `1.7.0` 之上修复架构评审发现的 CORS 通配符+credentials 安全问题、`@MessageMapping(ON_CLOSE)` 在陈旧 channel 路径上未触发的正确性问题、CompressorHandler 解析与大小写问题、AES-GCM IV 长度校验，并 pin logback 1.2.13 修复 CVE-2023-6378。详见 `docs/release-notes-1.7.1.md`）。
- `P0`–`P7` 全部里程碑已完成；项目历经"功能建设期 → 质量深化 → 产品化 → 性能优化 → 安全稳定性加固 → 可观测性增强 → 集群水平扩展"七个阶段。
- 下一步：**`2.0.0`** Spring Boot 3.x 迁移基线，**`2.1.0`** 企业安全准入。`2.0` / `2.1` 拆分见下方 `2.x` 路线说明。`1.9.x` 跟随项见 `1.8.0` 推迟项清单。

## 当前发版判断

`1.8.0`（当前推荐）在 `1.7.1` 之上新增 **WebSocket 集群支持**（向后兼容，默认单机模式行为与 `1.7.x` 完全一致）：

- **两个新模块**：`netty-spring-websocket-cluster`（集群核心 + Redis 实现）、`netty-websocket-cluster-spring-boot-starter`（自动装配，`server.netty.websocket.cluster.enable=true` 时激活）。
- **5 层可插拔 SPI**：`ClusterBroker`（跨节点传输，Redis Pub/Sub 默认）、`SessionRegistry`（分布式会话路由）、`EnvelopeCodec`（信封线格式，零依赖默认）、`MessagePayloadCodec`（消息体序列化，零依赖默认）、`ClusterNodeHeartbeat`（心跳持久化）。全部 `@ConditionalOnMissingBean` 可覆盖。
- **零 Jackson 依赖**：集群模块序列化全部走 SPI，用户自由选择 JSON/Protobuf/自定义。
- **核心能力**：跨节点广播/单播、远程关闭、origin 自投递抑制、节点生命周期状态机（JOINING→ACTIVE→DEGRADED→RESYNC→DRAINING→LEFT）、心跳+周期对账、单播热路径缓存、集群运行时统计、`onRedisLoss`（degrade-to-local 默认 / close-all）、`onPublishFailure`、消息大小上限、重连抖动。
- 288 个测试（含 6 SPI 隔离 + 6 配置 knobs + 9 Redis 集成 + 3 auto-config 装配；另有 4 个性能基准为手动 harness，不计入套件），11 模块全绿。详见 `docs/release-notes-1.8.0.md`。

> **1.8.0 实现范围 vs 设计目标**：`docs/cluster-design.md` 描述的是**完整目标架构**，其中相当一部分能力（多 pub/sub 连接并行解码、sharded pub/sub、Redis Streams 可靠投递、写 pipeline 批量、registry 限速、Redis 失联宽限期、Redis Cluster 客户端一等支持、W3C TraceContext 跨节点传播、多节点 demo + Testcontainers）**推迟到 `1.9.x`**。1.8.0 只暴露**有实际效果**的配置项——不暴露还没实现的特性的开关（"会撒谎的配置比没有配置更糟"）。下方"四刀"为原始设计全集，标注 ✅ 已实现 / ⏳ 推迟。

`1.7.1`（上一版本）在 `1.7.0` 之上做了 4 项审计驱动的安全/正确性修复（CORS 通配符+credentials、`@MessageMapping(ON_CLOSE)` 生命周期、CompressorHandler 解析、AES-GCM IV 校验）并 pin logback 1.2.13 修复 CVE-2023-6378，详见 `docs/release-notes-1.7.1.md`。

## `1.8.0` Redis 集群支持版本（已交付）

目标：让 netty-spring 服务可以多节点水平扩展，跨节点广播和单播对业务代码尽可能透明，节点扩缩容/故障自恢复可控；**同时对失败模式保持诚实**——Redis 中断、分区、慢订阅者在 API 层和指标层有清晰语义，而不是被吞掉。详细技术设计见 [`docs/cluster-design.md`](cluster-design.md)。

版本定位：

- ✅ 新增 `netty-spring-websocket-cluster` 模块和 `netty-websocket-cluster-spring-boot-starter`；底层直接依赖 `io.lettuce:lettuce-core`（`optional`，而非 `spring-boot-starter-data-redis`，避免与用户已有 Spring Data Redis 配置冲突，提供 `@ConditionalOnMissingBean` 兼容点）。
- **传输层 SPI 是本版本最重要的结构决策**：把"跨节点传输"和"会话注册表"抽象成 `ClusterBroker` / `SessionRegistry` 两个 SPI（借鉴 Centrifugo 引擎拆分），1.8.0 唯一实现是 Redis Pub/Sub。这样 mesh / NATS 是未来的 drop-in 实现、不破坏 `MessageSender` API。**现在不做、以后改不动。**
- 本地 `MessageSender` 查询接口语义保持不变；**新增**异步的集群查询接口（`getClusterSessionIds` / `isSessionAliveCluster` / `closeSessionCluster` 返回 `CompletionStage`），避免把网络 RTT 偷塞进同步签名。
- ✅ 不启用集群（`server.netty.websocket.cluster.enable=false` 或缺省）时行为与 `1.7.x` 完全一致；集群依赖在单机模式下不参与运行时路径。
- ⏳ **推迟到 1.9.x**：Sentinel 通过 `redis-sentinel://` URI scheme 可用（Lettuce 原生）；Redis Cluster 一等支持需要 `RedisClusterClient`（不同客户端类型），1.8.0 不通过 auto-config 提供，运行 Redis Cluster 的用户自备 `ClusterBroker`/`SessionRegistry` bean。sharded pub/sub 同步推迟。
- ✅ 配置子键与现有约定一致使用 `enable`（不是 `enabled`），命名空间 `server.netty.websocket.cluster.*`，与 `Mvc.enable` / `WebSocket.enable` / `Crypto.enable` 等齐平。

**已知扩展边界（二次评审量化结论，详见 `cluster-design.md §深度瓶颈`）：** Redis Pub/Sub 方案因扇出放大 `M·(f·N−1)` 和单 Lettuce 连接解码天花板（~80k msg/s/node），对**活跃广播 URI 仅在 ≤~10 节点安全**；超出后路径是 sharded pub/sub（仅救 Redis CPU）→ node-mesh（经 SPI 切换，业务零改动）。本版本明确以此为适用边界，不假装无限扩展。

**中间件选型已采纳（ADR-001，见 `cluster-design.md`）：Redis-first，NATS 后补。** 1.8.0 落 Redis；NATS 在 1.9.x 以 `NatsBroker` **新增实现**形式追加（规模化档位，非替换，Redis 长期保留服务小集群）。不自研通用 broker、不引自研 Go/Rust 中间件——要 Go 级性能直接 adopt 开源 NATS。因 SPI 共享 ~80% transport-agnostic 代码，"先 Redis 后 NATS"不是重复开发；档位切换对业务零改动、可逆。

建议拆成四刀：

### 第一刀：传输层 SPI + 集群发现与节点生命周期

- ✅ 新增 `netty-spring-websocket-cluster` 模块和 `server.netty.websocket.cluster.*` 配置命名空间。
- ✅ **定义 `ClusterBroker` / `SessionRegistry` 两个 SPI**（fan-out+单播 / presence+路由），1.8.0 落 `RedisPubSubBroker` + `RedisSessionRegistry`；`ClusterMessageSender` 只依赖 SPI 不直接碰 Lettuce。两者 `@ConditionalOnMissingBean` 可覆盖。
- 基于 Redis Heartbeat + Keyspace Notification 完成节点注册、心跳维护、故障检测。
- **故障检测默认 3s/10s**（之前规划的 30s 对实时 WebSocket 太长）；节点状态机 `JOINING → ACTIVE → DRAINING → LEFT`，Redis 重连恢复期插入 `RESYNC` 状态。
- **Keyspace Notification 对账兜底**：通知是 fire-and-forget、无重放，节点断连瞬间会永久错过 `NODE_LEFT`。必须有周期对账（`reconciliation-interval` 默认 15s）扫描 `netty:cluster:nodes` 比对 `lastHeartbeat` 作为慢路径兜底。指标 `netty.cluster.reconciliation.detected`。
- 提供 `ClusterMemberRegistry` 查询接口和故障/加入事件订阅入口。

### 第二刀：分布式 Session Registry + 跨节点广播 + 分布式追踪

- 设计 Redis 数据模型（`netty:session:{uri}:{sessionId}`、`netty:node:{nodeId}:sessions`、`netty:broadcast:{uri}` Pub/Sub）。
- ✅ 实现 `RedisPubSubBroker`：本地 fan-out + Redis Pub/Sub。
- ✅ **🔴 origin 自投递抑制（正确性，非优化）**：envelope 带 `originNodeId`，订阅回调比对本节点 id 命中即丢弃——否则 origin 本地用户会**收到重复消息**（本地 fan-out + 自己 PUBLISH 回环）。统计 `ClusterRuntimeStats.selfDeliveryDropped`，有回归测试。
- ⏳ **（推迟 1.9.x）多 pub/sub 连接解码**：单 Lettuce 连接 ~80k msg/s 即天花板且与 WS I/O 抢 event loop；每节点 2–4 连接按 URI 哈希分片。**1.8.0 实测吞吐（~14–16k msg/s）远低于单连接天花板，属过早优化，待基准证明需要时再加。**
- ⏳ **（推迟 1.9.x）sharded pub/sub（仅 cluster 模式）**：经典 pub/sub 在所有模式可用，sharded（`SSUBSCRIBE`/`SPUBLISH`，须 Lettuce ≥6.5.5）作为 cluster 模式优化推迟。
- ✅ **单播热路径缓存**：`sessionId→nodeId` 本地短 TTL 缓存（`registry-read-cache-ttl-ms` 默认 5000）+ NODE_LEFT 失效；陈旧/错误时移除并回退一次实时 HGET。`ClusterRuntimeStats.cacheHitRatio`。
- ⏳ **（推迟 1.9.x）广播频道基数边界 / `max-subscribed-channels`**：本基线仅覆盖 `@MessageMapping` URI（典型 10–100 条，订阅集在启动期即固定，不会无界增长）；房间/主题级 fan-out + 频道硬上限推迟到 `1.9.x` 的 `ClusterRoomRegistry`。
- ⏳ **（推迟 1.9.x）可靠投递路径（offset/epoch + Redis Streams）**：1.8.0 仅 at-most-once（Pub/Sub fire-and-forget），envelope 已带 `version` 字段为未来格式演进留口；`reliableBroadcast(...)` 与 Streams 生命周期推迟。
- ✅ **顺序契约**：单 publisher × 单 channel 保序；跨 publisher 不保序；本地 fan-out 可能先于远端（Javadoc 说明）。
- ⏳ **（推迟 1.9.x）写放大控制 / 写 pipeline 批量**：1.8.0 registry 写为单条 async（Lettuce 连接层已自动 pipeline）；显式 `publish-batch-size`/lazy registry 推迟。
- ⏳ **（推迟 1.9.x）W3C TraceContext 跨节点传播**：envelope 已预留 `traceparent` 字段，但订阅侧 MDC/Observation Scope 恢复推迟。

### 第三刀：弹性扩缩容与失效模式硬化

- Scale-out：新节点 JOIN 后由 LB 路由新连接，无需迁移现有会话。
- Scale-in：DRAINING 节点停接新连接，向所有 session 发送 `CloseFrame(1001)`，等待客户端重连（**客户端必须实现退避重连**，文档明确此契约）。
- 节点故障：通过 heartbeat TTL + Keyspace Notification + 周期对账（第一刀）双路触发清理，发布 `NODE_LEFT`；跨节点单播目标缺失时**同步返回错误**，不再静默丢消息。
- **分区下广播静默丢失须明示**：部分分区时 A 的广播只是 PUBLISH，连不上 Redis 的 B 收不到、其本地用户静默丢消息直到 B 被判 LEFT。这是 at-most-once 固有后果；需可靠送达走 `reliableBroadcast`（B 恢复后回放）。
- ✅ **Redis SPOF 降级**：`on-redis-loss=degrade-to-local`（默认）——Redis 失联时本节点切 `DEGRADED`，本地 fan-out / 本地 session 保持工作；跨节点暂停。`close-all` 显式 opt-in（关闭全部本地 session）。
- ⏳ **（推迟 1.9.x）Redis 失联宽限期**：`redis-loss-grace-period` 推迟；1.8.0 心跳失败即转 `DEGRADED`（无宽限窗口）。
- ✅/⏳ **重连风暴控制**：✅ 恢复同步携带 `jitter(0, reconnect-jitter-max-seconds)`（默认 10s，可配）；⏳ registry 重建 token-bucket 限速推迟到 1.9.x。
- ⏳ **（推迟 1.9.x）慢订阅者保护**：Lettuce 重连回调触发 `DEGRADED`（✅ 已有）；`client-output-buffer-limit pubsub` 运维章节推迟补充。

### 第四刀：API 诚实化、可观测完整化、文档与 demo

- ✅ `MessageSender` 接口设计修正：本地接口语义不变；集群查询作为新异步接口提供（`getClusterSessionIds` / `isSessionAliveCluster` 返回 `CompletionStage`）。
- ✅/⏳ **运行时统计 + 健康**：✅ 1.8.0 提供 `ClusterRuntimeStats`（broadcastPublished / crossNodeReceived / selfDeliveryDropped / unicastSent / publishFailures / cacheHitRatio）+ `ClusterHealthIndicator`（`/actuator/health` 下 `nettyCluster`，节点/broker 状态 + 计数，actuator 在 classpath 时启用）；⏳ 完整 Micrometer meter-binder 指标集（`netty.cluster.*` 时序）推迟到 1.9.x。
- ⏳ **（推迟 1.9.x）多节点 demo + Docker Compose + Testcontainers 端到端**：1.8.0 用真实 Redis（Docker）跑了 8 项 `RedisIntegrationTest` + 4 项性能基准，但多节点 demo 与 Testcontainers CI 阻塞项推迟。
- ✅ 文档：`cluster-design.md` 标注实现范围与推迟项，README 增加集群快速接入 + 性能基准 + 选型/容量表，`release-notes-1.8.0.md` 覆盖兼容/迁移。

`1.8.0` 完成标准（实际达成情况）：

- ⏳ 多节点 demo（推迟）；✅ 用真实 Redis 跑通双节点跨节点广播端到端测试 + Redis 故障降级路径（`onRedisLoss`）。
- ✅ **origin 自投递抑制有回归测试**：集群下本地用户对一条广播只收一次（不重复）。
- ✅ 不启用集群时行为与 `1.7.x` 完全一致；启用集群仅修改依赖坐标 + 一个配置开关。
- ✅ `ClusterBroker` / `SessionRegistry` SPI 边界清晰：`InMemoryBroker` / `InMemorySessionRegistry` 非 Redis stub 证明 `ClusterMessageSender` 不漏依赖 Lettuce。
- ✅ 容量表与节点数适用边界（≤~10 节点活跃广播）写入文档，并用 `redis-benchmark` + Java 基准实测校准。
- ✅ 全量 `mvn test` 通过（288 测试 / 11 模块）；✅ 集群路径有真实 Redis 集成测试 + auto-config 装配测试（⏳ Testcontainers 化推迟）。
- ⏳ W3C TraceContext 跨节点端到端串联（推迟 1.9.x，envelope 已预留字段）。

`1.8.0` 不作为阻塞项的内容：

- 房间 / 主题级 fan-out（`ClusterRoomRegistry`，推迟到 `1.9.x`）。
- Redis Streams 完整生命周期（消费者重平衡、死信队列），仅提供基础 at-least-once 入口。
- 跨 DC 多 Redis 多活（`2.x` 评估）。
- Kubernetes Operator / Helm Chart（运维专项）。

### `1.8.x` / `1.9.x` 跟随项 backlog

- **`NatsBroker` adapter（规模化中间件档位，ADR-001）**：在 `ClusterBroker` SPI 下新增 NATS 实现，兴趣路由消除 Redis Pub/Sub 的 N× 扇出放大；Redis 实现保留服务小集群。触发条件：有用户撞到 ≤~10 节点活跃广播天花板。
- 直接 node→node 单播（Slack 模式 mesh 第一步，把 Redis 移出单播热路径）。
- 入站背压 / 速率限制（per-session token bucket + `netty.websocket.inbound.dropped` 指标）。
- 房间 / 主题级集群 fan-out（`ClusterRoomRegistry` 含分片频道）。
- Redis Streams 完整生命周期：消费者重平衡、死信队列、离线消息 API。
- 完整 OpenTelemetry instrumentation（在 1.8.0 最小 TraceContext 传播之上扩展）。

## `2.x` 路线说明（Spring Boot 3.x 与企业安全分离）

`1.7.x` 架构评审建议把"Spring Boot 3.x 迁移"和"企业安全准入"拆为两个独立 release——它们风险曲线不同：前者机械、时间压力大、面向所有用户；后者是范围灵活的能力集合，不应阻塞前者。

### `2.0.0` — Spring Boot 3.x 迁移基线（先做）

目标：让 1.7/1.8 全部能力在 Spring Boot 3.x / JDK 17+ 上跑通，保留默认行为。

主要工作：

- `javax.*` → `jakarta.*`（webmvc / cookie / activation）。`javax.activation` 已被 JDK 11+ 移除，本项目目前仍在用 `javax.activation:activation:1.1.1` 显式补回；2.0 切到 `jakarta.activation:jakarta.activation-api` + `org.eclipse.angus:angus-activation`。
- 自动配置从 `spring.factories` 迁移到 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。
- JDK 基线：当前已是 JDK 17（`<java.version>17</java.version>`），保留 17；是否随 Spring Boot 3.x 升至 21 在 RC 阶段视上游决定。**无需 1.8.x 维护分支**——1.x 用户已经在 JDK 17 上。
- GraalVM native-image 基础支持：为 `@MessageMapping` / `@AutowiredMessageSender` / 反射式 controller 扫描提供 `RuntimeHintsRegistrar`，CI 跑 demo 的 native-image smoke。完整性能调优推迟到 `2.1.x`。
- 迁移基线测试矩阵：Spring Boot 3.2+ / 3.3+ × JDK 17 / JDK 21。
- 配置 / API 兼容：保留所有 1.x 用户面对的配置键和注解。

`2.0.0` 完成标准：

- Spring Boot 3.2+ / JDK 17+ 运行通过，单机和集群路径回归通过。
- demo 的 native-image 镜像可启动并响应 `/actuator/health`。
- 完整迁移指南：1.x → 2.0 配置映射、行为差异、回滚路径。
- `1.7.x` 继续维护安全补丁直到 `2.1` 发布。

### `2.1.0` — 企业安全准入（后做）

目标：形成可对外宣称的企业生产准入基线。每项都是可独立交付的 deliverable，避免"企业安全"成为永远做不完的口号。

具体清单：

| 类别 | 项 | 状态承诺 |
|---|---|---|
| 供应链 | Dependency-Check / Dependabot 真实漏洞 triage 闭环 | 高危以上不得入 tag；`dependency-check-suppressions.xml` 必填原因 |
| 供应链 | SLSA Build Provenance + sigstore/cosign 制品签名 | CI 阶段固化 |
| 鉴权 | JWT / OIDC 握手鉴权扩展示例 + 集成测试 | 单独 demo profile |
| 鉴权 | 速率限制（per-IP / per-route / per-connection）+ 计数指标 | `server.netty.security.rate-limit.*` |
| 鉴权 | 多租户资源配额（`max-sessions-per-tenant`、`max-bytes-per-second-per-tenant`） | tenant SPI |
| 网络 | mTLS + 客户端证书校验 | `server.netty.http.ssl.client-auth=required` |
| 网络 | TLS 密钥轮换 + SNI | 显式 reload API |
| 网络 | 完整 CORS 策略（whitelist + preflight cache + credentials 校验） | 拒绝不安全组合 |
| 运维 | 管理端点访问控制（独立端口 / 网关 / IP 白名单） | 文档强约束 |
| 运维 | 审计日志（独立通道、结构化、可签名） | 选配 appender |
| 运维 | 秘密管理（Vault / K8s Secret CSI / 环境变量）集成示例 | 不接受 YAML 明文密钥 |
| 治理 | 企业部署参考架构 + 安全自评 checklist + 性能基线报告 | 随发布发表 |

`2.1.0` 完成标准：上表每项有可独立勾选的 acceptance test 或文档证据，缺一不视为完成。

### `2.x` 远期评估（不承诺时间）

- WebSocket 子协议（STOMP、MQTT-over-WS）。
- HTTP/2 / HTTP/3（QUIC）支持。
- 跨 DC 多活 Redis 拓扑。
- 多区域 / 边缘节点拓扑。
- 全 native-image 性能优化（runtime profile-guided）。
- 完整 OpenTelemetry 自动 instrumentation。

## 跨版本治理原则（带"如何执行"）

以下原则贯穿后续所有版本演进，每条都有具体的执行入口，避免成为口号。

| 原则 | 执行入口 |
|---|---|
| **向后兼容是默认** | minor/patch 不引入破坏性 API；不可避免的不兼容只在 major（如 `2.0.0`）。Micrometer / Spring Boot / Lettuce 等可选依赖的**最低支持版本**写入对应 release notes 和 `dependency-governance.md`。 |
| **可观测性优先** | 新功能必须同时给出指标（命名遵循 `netty.<component>.*`）、日志（MDC 兼容）、健康检查暴露点；缺一不视为完成。 |
| **审计闭环** | 每个 minor 发布前执行 4 路并行审计（功能正确性 / 资源 / 安全 / API 契约）+ 对抗式验证；触发条件：PR 打 `release-candidate` 标签；输出：`audit-finding` 标签 issue 全部 close 或显式 defer 才能发版。单维护者项目使用 `release-checklist.md` 中的自我审计 checklist，但必须留下逐条勾选记录。 |
| **配置兼容** | 旧配置键保持兼容至少一个 major 版本；新增配置项必须有合理默认值，使现有部署零改动可升级。 |
| **测试基线** | 全量 `mvn test` 必须通过；新增能力补端到端测试或集成测试；不接受"仅单元测试覆盖"作为完成判定。 |
| **demo 同步（CI 强制）** | demo 工程的 smoke test（启动、`/actuator/health`、一次 WebSocket roundtrip）作为发布阻塞 CI job；Docker Compose 集群 demo 进 CI 推迟到 `1.9.x`（1.8.0 已有真实 Redis 集成测试覆盖集群路径）。 |
| **可选依赖契约** | 对每个 optional dep 声明"最低支持版本"；CI 矩阵跑最低 + 最新两版；版本升级时显式评估兼容性，破坏只能在 major 版本。 |

## 历史版本一览

| 版本 | 定位 | 阶段 |
| --- | --- | --- |
| `1.0.0`–`1.0.2` | 基线 + Starter 工程化治理 | P0–P3 |
| `1.1.0-RC2` | Starter 收敛与配置模型统一 | P4/P4.1 |
| `1.2.0`–`1.2.3` | WebSocket 产品能力、用户体验、生产就绪代码质量 | P5/P5.x |
| `1.3.0`–`1.3.1` | 可观测性正式版 + 代码质量深度治理 | P6 |
| `1.4.0` | Demo 与文档产品化 | P7 |
| `1.5.0`–`1.5.1` | 压测基线 | 性能分析 |
| `1.6.1`–`1.6.2` | 广播性能优化 + 关键 bug 修复 + 安全稳定性修复 | Phase 1 / Round 1–4 |
| `1.7.0` | 可观测性增强 + 深度修复 + WebSocket 分片支持 | 历史版本 |
| `1.7.1` | `1.7.0` 之上 4 项审计修复 + 依赖安全 | 上一版本 |
| `1.8.0` | **WebSocket 集群支持（Redis Pub/Sub + 5 层 SPI）** | **当前推荐版本** |
| `1.9.x` | 集群扩展项（NATS broker、多连接解码、可靠投递、完整指标、多节点 demo、Redis Cluster 客户端） | 规划中 |
| `2.0.0` | Spring Boot 3.x 迁移基线 | 远期 |
| `2.1.0` | 企业安全准入 | 远期（在 2.0.0 之后） |

各版本详细变更见对应 `docs/release-notes-*.md`。
