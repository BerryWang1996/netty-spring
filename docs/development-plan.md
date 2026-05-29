# 开发计划与阶段状态

更新时间：2026-05-29

## 当前结论

- **当前推荐版本：`1.7.1`**（在 `1.7.0` 之上修复架构评审发现的 CORS 通配符+credentials 安全问题、`@MessageMapping(ON_CLOSE)` 在陈旧 channel 路径上未触发的正确性问题、CompressorHandler 解析与大小写问题、AES-GCM IV 长度校验，并 pin logback 1.2.13 修复 CVE-2023-6378。详见 `docs/release-notes-1.7.1.md`）。
- `P0`–`P7` 全部里程碑已完成；项目历经"功能建设期 → 质量深化 → 产品化 → 性能优化 → 安全稳定性加固 → 可观测性增强"五个阶段。
- 下一步：**`1.8.0`** Redis Pub/Sub 集群支持，**`2.0.0`** Spring Boot 3.x 迁移基线，**`2.1.0`** 企业安全准入。`2.0` / `2.1` 拆分见下方 `2.x` 路线说明。

## 当前发版判断

`1.7.1`（当前推荐）在 `1.7.0` 之上做小幅、向后兼容的修复：

- **CORS 通配符 + credentials 安全修复**：`@CrossOrigin(origins="*", allowCredentials=true)` 配置不再回写客户端 Origin（之前等价于"允许任意源带凭证"），改为发出 `*` 并丢弃 Allow-Credentials 头，同时记录 WARN 日志暴露错误配置。
- **`@MessageMapping(ON_CLOSE)` 正确性修复**：`DefaultMessageSender` 在发送 / 广播路径上检测到 channel 已死时，原先调用裸 `removeSession` 绕过了关闭生命周期；改走 `closeSessionOnTransportError(CHANNEL_INACTIVE)`，保证用户 onClose 回调与关闭原因指标正常触发。
- **CompressorHandler 解析与大小写硬化**：`server.netty.http.gzip.types` 现接受逗号或任意空白分隔（之前仅切单空格，逗号分隔配置静默不生效）；Content-Type 比较改为大小写不敏感（RFC 7231 §3.1.1.1）。
- **AES-GCM IV 长度校验**：解密时显式拒绝非 96-bit IV，与加密路径对齐并符合 NIST SP 800-38D §8.2 推荐。
- **依赖修复**：root pom 显式 pin `logback-classic` / `logback-core` 到 1.2.13（修复 CVE-2023-6378，1.2.x 系列最后版本）。
- 发布前再次执行 4 路并行审计 + 对抗式验证，全量 `mvn test` 通过（9 个模块）；所有改动向后兼容。

`1.7.0` 的能力清单（指标扩展、MDC、健康检查、分片支持等）在 `1.7.1` 中完整保留，详见 `docs/release-notes-1.7.0.md`。

## `1.8.0` Redis 集群支持版本规划（下一版本）

目标：让 netty-spring 服务可以多节点水平扩展，跨节点广播和单播对业务代码尽可能透明，节点扩缩容/故障自恢复可控；**同时对失败模式保持诚实**——Redis 中断、分区、慢订阅者在 API 层和指标层有清晰语义，而不是被吞掉。详细技术设计见 [`docs/cluster-design.md`](cluster-design.md)。

版本定位：

- 新增 `netty-spring-cluster` 模块和 `netty-cluster-spring-boot-starter`；底层直接依赖 `io.lettuce:lettuce-core`（而非 `spring-boot-starter-data-redis`，避免与用户已有 Spring Data Redis 配置冲突，提供 `@ConditionalOnMissingBean` 兼容点）。
- **传输层 SPI 是本版本最重要的结构决策**：把"跨节点传输"和"会话注册表"抽象成 `ClusterBroker` / `SessionRegistry` 两个 SPI（借鉴 Centrifugo 引擎拆分），1.8.0 唯一实现是 Redis Pub/Sub。这样 mesh / NATS 是未来的 drop-in 实现、不破坏 `MessageSender` API。**现在不做、以后改不动。**
- 本地 `MessageSender` 查询接口语义保持不变；**新增**异步的集群查询接口（`getClusterSessionIds` / `isSessionAliveCluster` / `closeSessionCluster` 返回 `CompletionStage`），避免把网络 RTT 偷塞进同步签名。
- 不启用集群（`server.netty.cluster.enable=false` 或缺省）时行为与 `1.7.x` 完全一致；集群依赖在单机模式下不参与运行时路径。
- Sentinel / Redis Cluster 一等支持（Lettuce 原生）；`mode=cluster` 默认用 sharded pub/sub（须 Lettuce ≥6.5.5）。
- 配置子键与现有约定一致使用 `enable`（不是 `enabled`），与 `Mvc.enable` / `WebSocket.enable` / `Crypto.enable` 等齐平。

**已知扩展边界（二次评审量化结论，详见 `cluster-design.md §深度瓶颈`）：** Redis Pub/Sub 方案因扇出放大 `M·(f·N−1)` 和单 Lettuce 连接解码天花板（~80k msg/s/node），对**活跃广播 URI 仅在 ≤~10 节点安全**；超出后路径是 sharded pub/sub（仅救 Redis CPU）→ node-mesh（经 SPI 切换，业务零改动）。本版本明确以此为适用边界，不假装无限扩展。

建议拆成四刀：

### 第一刀：传输层 SPI + 集群发现与节点生命周期

- 新增 `netty-spring-cluster` 模块和 `server.netty.cluster.*` 配置命名空间。
- **定义 `ClusterBroker` / `SessionRegistry` 两个 SPI**（fan-out+单播 / presence+路由），1.8.0 落 `RedisPubSubBroker` + `RedisSessionRegistry`；`ClusterMessageSender` 只依赖 SPI 不直接碰 Lettuce。两者 `@ConditionalOnMissingBean` 可覆盖。
- 基于 Redis Heartbeat + Keyspace Notification 完成节点注册、心跳维护、故障检测。
- **故障检测默认 3s/10s**（之前规划的 30s 对实时 WebSocket 太长）；节点状态机 `JOINING → ACTIVE → DRAINING → LEFT`，Redis 重连恢复期插入 `RESYNC` 状态。
- **Keyspace Notification 对账兜底**：通知是 fire-and-forget、无重放，节点断连瞬间会永久错过 `NODE_LEFT`。必须有周期对账（`reconciliation-interval` 默认 15s）扫描 `netty:cluster:nodes` 比对 `lastHeartbeat` 作为慢路径兜底。指标 `netty.cluster.reconciliation.detected`。
- 提供 `ClusterMemberRegistry` 查询接口和故障/加入事件订阅入口。

### 第二刀：分布式 Session Registry + 跨节点广播 + 分布式追踪

- 设计 Redis 数据模型（`netty:session:{uri}:{sessionId}`、`netty:node:{nodeId}:sessions`、`netty:broadcast:{uri}` Pub/Sub）。
- 实现 `RedisPubSubBroker`：本地 fan-out + Redis Pub/Sub；URI 无跨节点 session 时短路为本地路径。
- **🔴 origin 自投递抑制（正确性，非优化）**：envelope 带 `originNodeId`，订阅回调比对本节点 id 命中即丢弃——否则 origin 本地用户会**收到重复消息**（本地 fan-out + 自己 PUBLISH 回环）。指标 `netty.cluster.pubsub.self-dropped`。
- **多 pub/sub 连接解码**：单 Lettuce 连接 ~80k msg/s 即天花板且与 WS I/O 抢 event loop；每节点 2–4 连接（`pubsub-connections`）按 URI 哈希分片，解码后立即移交业务线程池。指标 `netty.cluster.subscribe.decode.lag`。**Redis 分片救不了这个天花板。**
- **sharded pub/sub（仅 cluster 模式）**：`mode=cluster` 默认 `SSUBSCRIBE`/`SPUBLISH`，避免经典 pub/sub 的 cluster-bus 全节点广播税；须 Lettuce ≥6.5.5，启动期校验。standalone/sentinel 无收益保持经典。
- **单播热路径缓存**：`sessionId→nodeId` 本地短 TTL 缓存（`registry-read-cache-ttl-ms` 默认 5000）+ NODE_LEFT 失效，避免每条跨节点 DM 一次 HGET（≳100k DM/s 时 registry 读会先于广播撞墙）。陈旧时回退一次实时 HGET。
- **广播频道基数边界**：本基线仅覆盖 `@MessageMapping` URI（典型 10–100 条），`cluster.max-subscribed-channels` 硬上限默认 1024；房间/主题级 fan-out 推迟到 `1.9.x` 的 `ClusterRoomRegistry`。
- **投递契约（at-most-once 默认 + offset/epoch 可靠路径）**：Pub/Sub fire-and-forget，Javadoc 明确 at-most-once + drop 计数。`reliableBroadcast(...)` 走 Redis Streams，采用 Centrifugo 的 `offset`+`epoch` 契约（陈旧 offset 恢复时给"无法恢复"显式信号而非静默空洞）+ 周期 offset 同步 + **重连从 last-consumed ID 续读**（否则是假可靠）。
- **顺序契约写入 Javadoc**：单 publisher × 单 channel 保序；跨 publisher 不保序；本地 fan-out 可能先于远端。
- **写放大控制**：connect/close 默认走 pipeline（`publish-batch-size` 64、`publish-flush-interval` 10ms）；可选 lazy registry——仅在 session 第一次成为跨节点目标时写 Redis。
- **W3C TraceContext 跨节点传播**：Pub/Sub 信封注入 `traceparent`，订阅侧恢复 SLF4J MDC + Micrometer Observation Scope。分布式系统最小可调试基线，**必须随集群同步落地**。

### 第三刀：弹性扩缩容与失效模式硬化

- Scale-out：新节点 JOIN 后由 LB 路由新连接，无需迁移现有会话。
- Scale-in：DRAINING 节点停接新连接，向所有 session 发送 `CloseFrame(1001)`，等待客户端重连（**客户端必须实现退避重连**，文档明确此契约）。
- 节点故障：通过 heartbeat TTL + Keyspace Notification + 周期对账（第一刀）双路触发清理，发布 `NODE_LEFT`；跨节点单播目标缺失时**同步返回错误**，不再静默丢消息。
- **分区下广播静默丢失须明示**：部分分区时 A 的广播只是 PUBLISH，连不上 Redis 的 B 收不到、其本地用户静默丢消息直到 B 被判 LEFT。这是 at-most-once 固有后果；需可靠送达走 `reliableBroadcast`（B 恢复后回放）。
- **Redis SPOF 降级**：`cluster.on-redis-loss=degrade-to-local`（默认）——Redis 失联时本节点切 `DEGRADED`，本地 fan-out / 本地 session 保持工作；跨节点暂停。`close-all` 改为显式 opt-in。
- **Redis 失联宽限期**：`cluster.redis-loss-grace-period`（默认 60s）内不改状态机，避免抖动误降级。
- **重连风暴控制**：恢复同步携带 `jitter(0, reconnect-jitter-max)`（默认 10s）；registry 重建 token-bucket 限速；订阅重建按 100 URI / pipeline 批量。
- **慢订阅者保护**：Lettuce 重连回调触发 `DEGRADED` + 计数；运维章节明确 Redis `client-output-buffer-limit pubsub` 配置要求。

### 第四刀：API 诚实化、可观测完整化、文档与 demo

- `MessageSender` 接口设计修正：本地接口语义不变；集群查询作为新异步接口提供（详见 `cluster-design.md §API 契约`）。
- 集群版 Micrometer 指标完整集：`netty.cluster.nodes.active`、`.state`、`netty.cluster.broadcast.published`、`netty.cluster.unicast.routed.cross-node / .local / .unknown-target`、`netty.cluster.pubsub.drops.unknown`、`.subscriber.disconnected`、`.self-dropped`、`netty.cluster.subscribe.decode.lag`、`netty.cluster.registry.read.cache.hit-ratio`、`netty.cluster.reconciliation.detected`、`netty.cluster.degraded.duration`、`netty.cluster.publish.latency`、`netty.cluster.registry.writes / .reads`。
- `/actuator/health` 集成集群健康（Redis 连通性、本节点状态、降级窗口）。
- 多节点 demo + Docker Compose；端到端 smoke test（Testcontainers）作为 CI 阻塞项。
- 文档：`cluster-design.md` 同步实现细节、容量表与适用边界，README 增加集群快速接入小节，发布说明覆盖兼容/迁移注意事项。

`1.8.0` 完成标准：

- 3 节点集群跨节点聊天 demo 走通：广播、私聊、缩扩容、Redis 故障演练（拔 Redis 仍能本地工作 ≥ 60s，恢复后无雷击）。
- **origin 自投递抑制有回归测试**：集群下本地用户对一条广播只收一次（不重复）。
- 不启用集群时行为与 `1.7.x` 完全一致；启用集群仅修改依赖坐标 + 一个配置开关。
- `ClusterBroker` / `SessionRegistry` SPI 边界清晰：有一个非 Redis 的 stub 实现证明 `ClusterMessageSender` 不漏依赖 Lettuce。
- 容量表与节点数适用边界（≤~10 节点活跃广播）写入文档，并用 `redis-benchmark` / `pubsub-sub-bench` 实测校准规划数字。
- 全量 `mvn test` 通过；集群路径补 Testcontainers 端到端测试。
- W3C TraceContext 跨节点端到端串联（demo 可视化）。

`1.8.0` 不作为阻塞项的内容：

- 房间 / 主题级 fan-out（`ClusterRoomRegistry`，推迟到 `1.9.x`）。
- Redis Streams 完整生命周期（消费者重平衡、死信队列），仅提供基础 at-least-once 入口。
- 跨 DC 多 Redis 多活（`2.x` 评估）。
- Kubernetes Operator / Helm Chart（运维专项）。

### `1.8.x` / `1.9.x` 跟随项 backlog

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
| **demo 同步（CI 强制）** | demo 工程的 smoke test（启动、`/actuator/health`、一次 WebSocket roundtrip）作为发布阻塞 CI job；`1.8.0` 起 Docker Compose 集群 demo 也进 CI。 |
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
| `1.7.0` | 可观测性增强 + 深度修复 + WebSocket 分片支持 | 上一推荐版本 |
| `1.7.1` | **`1.7.0` 之上 4 项审计修复 + 依赖安全** | **当前推荐版本** |
| `1.8.0` | Redis Pub/Sub 集群支持 | 规划中 |
| `2.0.0` | Spring Boot 3.x 迁移基线 | 远期 |
| `2.1.0` | 企业安全准入 | 远期（在 2.0.0 之后） |

各版本详细变更见对应 `docs/release-notes-*.md`。
