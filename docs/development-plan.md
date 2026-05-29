# 开发计划与阶段状态

更新时间：2026-05-29

## 当前结论

- **当前推荐版本：`1.7.0`**（可观测性增强 + 遗留缺陷深度修复 + WebSocket 分片消息支持，详见 `docs/release-notes-1.7.0.md`）。
- `P0`–`P7` 全部里程碑已完成；项目历经"功能建设期 → 质量深化 → 产品化 → 性能优化 → 安全稳定性加固 → 可观测性增强"五个阶段。
- 下一步面向 **`1.8.0`** Redis Pub/Sub 集群支持，之后 **`2.0.0`** Spring Boot 3.x / Jakarta namespace 迁移 + 企业安全版本。

## 当前发版判断

`1.7.0` 作为当前推荐版本提供：

- **第一刀**：修复 v1.6.2 审计遗留的 6 个缺陷（shutdown 异步生命周期等待、DataBindUtil 嵌套属性匹配、closeSession TOCTOU、Host 头校验、ResponseEntity CRLF 过滤、MimetypesFileTypeMap 封装）。
- **第二刀**：Micrometer 指标扩展——连接时长/消息大小/广播 fanout/handler 延迟分布、分 URI 活跃 session、handler 线程池与 Netty allocator 内存 Gauge；push 模型指标通过 `WebSocketMetricsCallback` 桥接。
- **第三刀**：SLF4J MDC 结构化日志（`netty.requestId` / `sessionId` / `uri` / `remoteAddr`）和 Actuator `NettyServerHealthIndicator`（`/actuator/health`）。
- **第四刀**：可选 WebSocket 分片消息支持，`server.netty.websocket.max-frame-aggregation-buffer-size` 控制聚合缓冲区，默认 0（禁用）保持向后兼容。
- 发布前 4 轮代码审计 + 1 轮对抗式验证修复多 `MeterRegistry` 指标路由、连接时长 Timer 预创建、聚合器插入兜底、`getPort()` 空安全等问题。
- 全部改动向后兼容，全量 `mvn test` 通过（9 个模块）。

## `1.8.0` Redis 集群支持版本规划（下一版本）

目标：让 netty-spring 服务可以多节点水平扩展，跨节点广播和单播对业务代码透明，节点扩缩容/故障自恢复可控。

版本定位：

- 新增 `netty-spring-cluster` 模块和 `netty-cluster-spring-boot-starter`，引入 Spring Data Redis 作为集群通信底座。
- `MessageSender` 接口保持不变；启用集群后由 `ClusterMessageSender` 自动路由，业务代码零迁移。
- 不启用集群（`server.netty.cluster.enabled=false` 或缺省）时行为与 `1.7.x` 完全一致；集群依赖在单机模式下不参与运行时路径。
- 详细技术设计见 `docs/cluster-design.md`。

建议拆成四刀：

### 第一刀：集群发现与节点生命周期

- 新增 `netty-spring-cluster` 模块和 `server.netty.cluster.*` 配置命名空间。
- 基于 Redis Heartbeat + Keyspace Notification 完成节点注册、心跳维护、故障检测；节点状态机 `JOINING → ACTIVE → DRAINING → LEFT`。
- 提供 `ClusterMemberRegistry` 查询接口和故障/加入事件订阅入口。
- 涉及文件：新增 `netty-spring-cluster/` 模块，`netty-spring-boot-autoconfigure` 增加集群自动装配。

### 第二刀：分布式 Session Registry + 跨节点广播

- 设计 Redis 数据模型（`netty:session:{uri}:{sessionId}`、`netty:node:{nodeId}:sessions`、`netty:broadcast:{uri}` Pub/Sub）。
- 实现 `ClusterMessageSender`：本地扇出 + Redis Pub/Sub 广播，URI 无跨节点 session 时短路为本地路径。
- 私聊路由：先查 session 所在节点，本节点直发，跨节点通过 `netty:cluster:unicast:{targetNodeId}` 转发。

### 第三刀：弹性扩缩容与自恢复

- Scale-out：新节点 JOIN 后由 LB 路由新连接，无需迁移现有会话。
- Scale-in：DRAINING 节点停接新连接，向所有 session 发送 `CloseFrame(1001)`，等待客户端重连。
- 节点故障：通过 heartbeat key TTL + Keyspace Notification 触发清理，发布 `NODE_LEFT` 事件。
- 脑裂防护：Redis 失联超过阈值时主动关闭本地 session（保守策略）。

### 第四刀：可观测、文档与 demo

- 集群版 Micrometer 指标：`netty.cluster.nodes.active`、`netty.cluster.broadcast.published`、`netty.cluster.unicast.routed.cross-node` 等。
- `/actuator/health` 集成集群健康（Redis 连通性、本节点状态）。
- 多节点 demo + Docker Compose 示例，浏览器端可观察跨节点广播。
- 文档：`cluster-design.md` 完善实现细节，README 增加集群快速接入小节，发布说明覆盖兼容/迁移注意事项。

`1.8.0` 完成标准：

- 3 节点集群跨节点聊天 demo 走通：广播、私聊、缩扩容、Redis 故障演练。
- Redis 不可用时单机模式继续工作（集群相关启动校验在 `cluster.enabled=true` 时才严格生效）。
- 默认配置下 `1.7.x` 用户升级到 `1.8.0` 仅修改依赖坐标，行为完全一致。
- 全量 `mvn test` 通过；集群路径补端到端集成测试（嵌入式 Redis 或 Testcontainers）。

`1.8.0` 不作为阻塞项的内容：

- OpenTelemetry 分布式追踪（推迟到 `1.9.x` 或 `2.x`）。
- Redis Streams 持久化/离线消息（可选扩展，集群基线达标后再做）。
- Spring Boot 3.x 迁移（`2.0.0`）。
- Kubernetes Operator / Helm Chart（运维侧建议另作专项）。

## `2.0.0` 远期规划

目标：完成 Spring Boot 3.x / Jakarta namespace 迁移，并形成可对外宣称的企业安全准入版本。

主要工作流：

- **Spring Boot 3.x 迁移**：`javax.*` → `jakarta.*`、自动装配迁移到 `AutoConfiguration.imports`、最低 JDK 升至 17、Spring Boot 3.x 测试矩阵。
- **企业安全准入**：恢复 Dependency-Check / Dependabot 漏洞 triage 闭环、扩展握手鉴权能力（JWT/OIDC 集成示例）、完整 CORS 策略、TLS 策略深化（mTLS、密钥轮换、SNI）、管理端点访问控制。
- **API 治理**：清理 `1.x` 累积的兼容入口（旧顶层配置、`MessageSenderSupport` 直接调用、`responseMsg()` 等），明确新基线。
- **文档与认证**：发布企业部署参考架构、安全自评 checklist、性能基线报告。

`2.0.0` 完成标准（候选）：

- Spring Boot 3.2+ / JDK 17+ 运行通过，单机和集群路径回归通过。
- 至少一个企业安全发布通道（高危漏洞 triage 闭环、TLS/鉴权/CORS/管理端点策略全部可配置可校验）。
- 完整迁移指南：`1.x → 2.0` 配置映射、行为差异、回滚路径。
- 当前 `1.x` 进入维护分支，仅接收必要安全修复。

## 跨版本治理原则

以下原则贯穿后续所有版本演进：

- **向后兼容是默认**：minor / patch 版本不引入破坏性 API 变更；不可避免的不兼容只允许出现在 major（如 `2.0.0`）。
- **可观测性优先**：新功能必须同时考虑指标、日志、健康检查的暴露；不允许"功能 ready，可观测稍后补"。
- **审计闭环**：每个 minor 版本发布前进行至少一轮独立代码审计（参考 `1.7.0` 的 4 路并行审计 + 对抗式验证模型）；发现的正确性 / 资源 / 安全问题在发版前清零。
- **配置兼容**：旧配置键保持兼容至少一个 major 版本；新增配置项必须有合理默认值，使现有部署零改动可升级。
- **测试基线**：全量 `mvn test` 必须通过；新增能力补端到端测试或集成测试，而不仅是单测。
- **demo 同步**：核心新功能在 demo 工程中提供可运行示例，避免文档与代码脱节。

## 历史版本一览

| 版本 | 定位 | 阶段 |
| --- | --- | --- |
| `1.0.0`–`1.0.2` | 基线 + Starter 工程化治理 | P0–P3 |
| `1.1.0-RC2` | Starter 收敛与配置模型统一 | P4/P4.1 |
| `1.2.0`–`1.2.3` | WebSocket 产品能力、用户体验、生产就绪代码质量 | P5/P5.x |
| `1.3.0`–`1.3.1` | 可观测性正式版 + 代码质量深度治理 | P6 |
| `1.4.0` | Demo 与文档产品化 | P7 |
| `1.5.1` | 压测基线 | 性能分析 |
| `1.6.0`–`1.6.2` | 广播性能优化 + 关键 bug 修复 + 安全稳定性修复 | Phase 1 / Round 1–4 |
| `1.7.0` | **可观测性增强 + 深度修复 + WebSocket 分片支持** | **当前推荐版本** |
| `1.8.0` | Redis Pub/Sub 集群支持 | 规划中 |
| `2.0.0` | Spring Boot 3.x 迁移 + 企业安全版本 | 远期 |

各版本详细变更见对应 `docs/release-notes-*.md`。
