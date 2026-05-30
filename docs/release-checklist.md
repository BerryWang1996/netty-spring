# 版本发布检查清单

更新时间：2026-05-29

## 适用范围

- 适用于所有 `netty-spring` 版本发布（功能/稳定性发布与企业安全发布）。
- 目标是保证发布动作可重复执行，而不是依赖一次性的人工记忆。
- 历史 `1.0.x`–`1.7.x` 的版本特定确认项见对应 `docs/release-notes-*.md`，本清单只保留可复用的通用流程与最新版本口径。

## 版本类型

- **功能/稳定性发布**（默认）：核心要求是全量测试通过、Starter 兼容、配置文档与代码一致、SBOM 生成和基础 CI 链路成功；当前不把依赖漏洞扫描作为阻塞项。
- **企业安全发布**（`2.0.0` 之后引入）：在功能/稳定性要求之上，额外完成 Dependency-Check / Dependabot triage 闭环、CORS / 握手鉴权 / TLS 策略说明与管理端点访问控制确认。

## 通用发布前检查

1. **版本号与文档**：根 `pom.xml` 切到目标版本（含 9 个模块），更新 README "当前推荐版本"、`docs/development-plan.md` 当前发版判断、`docs/release-notes-<version>.md`。
2. **全量测试**：执行 `mvn test`，9 个模块全部 SUCCESS。
3. **SBOM**：`mvn -Psbom -DskipTests org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom`；CI 发布需确认 `Maven Test` + `Generate SBOM` job 成功。
4. **Starter & Demo 回归**：四个 Starter（`netty-web` / `netty-webmvc` / `netty-websocket` / `demo-netty-web`）的集成测试必须通过；demo 启动 smoke 通过。
5. **工程化边界复核**：
   - Starter 启动失败抛 Spring Boot 异常，不直接终止 JVM。
   - `NettyServerBootstrap.stop()` 在 repeated stop、异常 stop、startup failure 后都能完成资源清理。
   - `MessageSenderSupport` 在无 websocket mapping、stop/start、停机联动下行为可预测。
6. **稳定性门槛**（自 `1.1.0` 起持续生效）：
   - 静态文件服务不能通过 `..`、URL 编码或路径规范化绕过根目录。
   - HTTP request line / header / chunk / body 上限、`read-timeout` / `write-timeout` / `idle-timeout` 有显式配置入口与回归测试。
   - 启用 SSL 时证书和私钥在启动期校验；生产环境显式配置 `http.ssl.protocols` 与 `http.ssl.ciphers`。
   - WebSocket `allowed-origins` 在生产部署中显式评估，不默认放开跨站握手。
   - MVC / 静态文件 / WebSocket 写失败与过载拒绝有统一日志、关闭策略和指标；`management.enable=true` 只在受保护网络下暴露。
   - handler/sender 线程池配置有启动期校验，非法容量和 `max < core` 不会静默兜底。
7. **可观测性门槛**（自 `1.7.0` 起）：
   - `micrometer-core` 与 `spring-boot-actuator` 为 optional 依赖，缺失时自动退化。
   - 新增 Micrometer 指标命名遵循 `netty.<component>.*`，标签卡死在框架枚举（如 `CloseReason`）以避免无界基数。
   - 新增可观测能力同步进 `docs/api-guide.md` §9 与对应配置文档。
8. **审计闭环**（自 `1.7.0` 起，minor 版本必做）：
   - 发布前至少一轮独立代码审计（建议参考 `1.7.0` 的 4 路并行审计 + 对抗式验证模型）。
   - 发现的正确性 / 资源 / 安全问题在发版前清零；保留审计纪要在发布说明中体现。
9. **发布说明**：`docs/release-notes-<version>.md` 描述版本定位、改动清单、新增测试、升级指南、向后兼容性声明。

## 企业安全发布附加项

1. 发布前完成依赖漏洞扫描，处理或记录 GitHub Dependabot / 等效扫描告警；高危及以上漏洞或未 triage 的扫描失败不得进入企业安全发布 tag。
2. Dependency-Check 误报必须写入 `dependency-check-suppressions.xml` 并说明原因，不能通过降低扫描门槛绕过。
3. CI 或发布机必须为企业安全发布门禁配置 `NVD_API_KEY`，并复用 `${settings.localRepository}/../dependency-check-data` Dependency-Check 缓存。
4. 握手鉴权、CORS / Origin、TLS 协议/套件和管理端点暴露策略需要在发布说明中明确。

## 发布动作

1. 工作树只保留本次发布相关文件，不带入 `.m2/`、本地缓存或实验文件。
2. 创建发布提交（版本号 + 发布说明 + 开发计划状态）。
3. 创建对应 annotated tag，例如 `v1.7.0`、`v1.8.0-RC1`。
4. 推送分支和 tag 到 `origin`。
5. Maven 制品发布（如需）：在凭据已配置的 CI 或本机执行 `mvn deploy`，目标仓库见根 `pom.xml` `distributionManagement`。

## 发布后

1. 将开发线切到下一个 `-SNAPSHOT` 版本（若采用 SNAPSHOT 流程）。
2. 更新 [开发计划](development-plan.md) 当前状态与下一阶段目标。
3. 如果本次补了新的稳定性 / 可观测性 / 审计边界，回写本清单"通用发布前检查"对应小节，避免后续回归再次依赖口头约定。

## 最新版本口径

### `1.7.0`（当前推荐版本，已发布 tag `v1.7.0`）

定位：可观测性增强 + 遗留缺陷深度修复 + WebSocket 分片消息支持，按"四刀"推进。

完成确认项：

- 全量 reactor `mvn test` 通过（9 个模块）。
- **第一刀**：v1.6.2 审计报告中 6 个遗留缺陷全部修复并有回归测试。
- **第二刀**：Micrometer 指标扩展（连接时长 / 消息大小 / 广播 fanout / handler 延迟分布、分 URI 活跃 session、handler 线程池与 Netty allocator 内存 Gauge），push 模型指标通过 `WebSocketMetricsCallback` 桥接到每个已绑定 `MeterRegistry`。
- **第三刀**：SLF4J MDC 结构化日志（`netty.requestId` / `sessionId` / `uri` / `remoteAddr`）与 Actuator `NettyServerHealthIndicator`（`/actuator/health`）。
- **第四刀**：`server.netty.websocket.max-frame-aggregation-buffer-size` 控制的 `WebSocketFrameAggregator` 分片消息聚合，默认 0（禁用）保持向后兼容。
- `micrometer-core` 与 `spring-boot-actuator` 均为 optional 依赖，缺失时自动退化。
- 发布前完成 4 轮代码审计 + 1 轮对抗式验证，修复多 `MeterRegistry` 指标路由、连接时长 Timer 预创建、聚合器插入兜底、`getPort()` 空安全等问题；全部改动向后兼容。
- 已补 `docs/release-notes-1.7.0.md`，并同步 README、`docs/api-guide.md`、`docs/netty-configuration.md`、`docs/websocket-configuration.md` 与开发计划至 `1.7.0` 状态。

### `1.7.1`（已发布 tag `v1.7.1`）

定位：在 `1.7.0` 之上的审计驱动 patch，全部修复向后兼容。

完成确认项：

- 全量 `mvn test` 通过（9 个模块），新增 6 个回归测试。
- **HIGH 安全**：CORS `origins="*"` + `allowCredentials=true` 不再回写客户端 Origin；不再发出 `Allow-Credentials` 头；记录 WARN 日志。
- **MEDIUM 正确性**：`DefaultMessageSender` 检测到陈旧 channel 时改走 `closeSessionOnTransportError(CHANNEL_INACTIVE)`，保证 `@MessageMapping(ON_CLOSE)` 正常触发。
- **LOW 硬化**：CompressorHandler 类型解析支持逗号与任意空白分隔、Content-Type 比较大小写不敏感；AES-GCM 解密路径显式拒绝非 96-bit IV。
- **依赖 CVE 覆盖**：root POM 显式 pin `logback-classic` / `logback-core` 到 1.2.13（CVE-2023-6378）。
- 路线图修订：`docs/cluster-design.md` 与 `docs/development-plan.md` 应用 6 项架构评审修订（Redis SPOF 降级默认、API 诚实化、TraceContext 必做、`2.0`/`2.1` 拆分、企业安全 12 项清单、治理原则可执行化）。
- 已补 `docs/release-notes-1.7.1.md`，并同步 README、`docs/api-guide.md`、`docs/dependency-governance.md` 至 1.7.1 状态。

### `1.8.0`（当前推荐版本，已发布 tag `v1.8.0`）

定位：WebSocket 集群支持（Redis Pub/Sub + 5 层 SPI），向后兼容，默认单机模式行为与 `1.7.x` 完全一致。

完成确认项：

- 全量 `mvn test` 通过（**288 个测试 / 11 个模块**）；6 个 SPI 隔离 + 6 个配置 knobs + 9 个 Redis 集成（含入站大小上限安全测试）+ 3 个 auto-config 装配测试（ApplicationContextRunner）。`PerformanceBenchmark`（4 方法）是手动 harness，不计入套件。
- **2 个新模块**：`netty-spring-websocket-cluster`、`netty-websocket-cluster-spring-boot-starter`。
- **5 层可插拔 SPI**：`ClusterBroker` / `SessionRegistry` / `EnvelopeCodec` / `MessagePayloadCodec` / `ClusterNodeHeartbeat`，全部 `@ConditionalOnMissingBean` 可覆盖；集群模块**零 Jackson 依赖**（序列化用户自选）。
- **配置诚实化**：`ClusterProperties` 只暴露有实际效果的配置项；设计文档中尚未实现的特性（多 pub/sub 连接、sharded pub/sub、可靠 streams、写 pipeline、限速、宽限期、Redis Cluster 客户端）**不暴露开关**，明确标注推迟到 `1.9.x`。
- 真实 Redis 7.4.9（Docker）实测：本地广播 ~180 万 msg/s，跨节点 ~14k msg/s @ 77µs。
- 已补 `docs/release-notes-1.8.0.md`，并同步 README（集群快速接入 + 性能基准 + 选型/容量表）、`docs/api-guide.md`、`docs/cluster-design.md`（实现范围 vs 设计目标）、`docs/development-plan.md`。

### `1.9.x`（规划中）

集群扩展项：NATS broker（ADR-001）、多 pub/sub 连接并行解码、sharded pub/sub、Redis Streams 可靠投递、完整 Micrometer 指标集 + Actuator 集群健康、多节点 demo + Testcontainers、Redis Cluster 客户端一等支持、W3C TraceContext 跨节点传播。详见 `development-plan.md`。
