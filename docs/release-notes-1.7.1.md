# Release Notes — v1.7.1

> 发布日期：2026-05-29

## 版本定位

v1.7.1 是 v1.7.0 之上的小幅、向后兼容的修复版本，**地址了发布前架构评审发现的 2 项 HIGH/MEDIUM 严重级别问题、若干 LOW 项硬化、1 项依赖 CVE 覆盖，以及围绕 `1.8.0` 集群路线图的关键设计修订**。所有改动向后兼容，无 API 变更。

发布前再次执行了 4 路并行代码审计（HTTP / WebSocket 广播 / 加密 / 安全 surfaces）+ 1 路架构评审（`1.8.0` 集群、`2.0.0` 远期）+ 1 路依赖审计 + 1 路文档审计；本 release notes 末尾的"审计纪要"汇总修复与未阻塞项。

## 修复

### 安全 — HIGH

- **`@CrossOrigin(origins="*", allowCredentials=true)` 不再回写客户端 Origin**（CWE-942 风险消除）。pre-1.7.1，wildcard origin 与 `allowCredentials=true` 组合时，框架会把请求侧的 `Origin` 头原样写入 `Access-Control-Allow-Origin`，等价于"允许任意源带凭证"——这是 OWASP 列出的典型 CORS 配置陷阱。1.7.1 改为发出安全的 `*`、**抑制** `Access-Control-Allow-Credentials` 头，并记录 WARN 日志暴露该配置错误。已配置显式 origin 列表的合法用法不受影响。
  - 影响文件：`RequestMappingResolver.applyCorsHeaders()`
  - 回归测试：`corsWildcardWithAllowCredentialsRefusesToEchoOrigin` / `corsExplicitOriginWithCredentialsStillWorks`

### 正确性 — MEDIUM

- **`@MessageMapping(ON_CLOSE)` 不再在陈旧 channel 路径上被吞掉**。pre-1.7.1，`DefaultMessageSender.sendMessage` 与 `isSessionActiveWithCleanup`（广播路径）在检测到 `!channel.isActive()` 时调用裸 `MessageMappingResolver.removeSession(sessionId)`，该方法绕过 `startClosing()` CAS、关闭生命周期分发、关闭原因指标。当应用线程先于 Netty 的 `channelInactive` 观察到死 channel 时，业务的 `@MessageMapping(ON_CLOSE)` 回调会静默不触发——依赖 onClose 做资源释放、状态同步的代码会泄漏。1.7.1 改走 `closeSessionOnTransportError(session, ..., CHANNEL_INACTIVE)`，与 Netty 自身的 `channelInactive` 通过 CAS 幂等共存。
  - 影响文件：`DefaultMessageSender.java`（行 271、714）
  - 回归测试：`sendMessageOnStaleChannelStillFiresOnCloseLifecycle` / `topicMessageOnStaleChannelStillFiresOnCloseLifecycle`

### 硬化 — LOW

- **`CompressorHandler` GZIP types 解析与大小写**：
  - pre-1.7.1，`server.netty.http.gzip.types=text/html,application/json`（逗号分隔）会被 `split(" ")` 收成一个不可匹配的整体串，导致压缩静默不生效。改为 `[,\\s]+` 切分，与 `allowed-origins` 解析器一致。
  - Content-Type 比较改为大小写不敏感（RFC 7231 §3.1.1.1：媒体类型大小写不敏感）。
  - 影响文件：`CompressorHandler.java`
  - 回归测试：`gzipTypesParserAcceptsCommaAndWhitespaceSeparators`

- **AES-GCM IV 长度校验**：解密路径显式拒绝非 96-bit IV，与加密路径对齐并符合 NIST SP 800-38D §8.2 推荐。攻击者可控的异常 IV 长度被早拒于明确的 `IllegalArgumentException`，避免落入 GHASH 推导的较弱安全余量。
  - 影响文件：`AesGcmMessageCryptoCodec.java`（decrypt 路径）
  - 回归测试：`rejectsEnvelopeWithNon96BitIv`

### 依赖 — CVE 覆盖

- **logback 1.2.13 显式 pin**（CVE-2023-6378）。Spring Boot 2.7.18 BOM 默认 logback 1.2.12 仍有 `SocketReceiver` / `SocketAppender` 序列化漏洞；root POM `<dependencyManagement>` 在 `spring-boot-dependencies` 之前声明 `logback-classic:1.2.13` 与 `logback-core:1.2.13` 覆盖。供应链扫描会显示这一显式 pin 已修复该 CVE。
  - 影响文件：根 `pom.xml`

## 文档与路线图修订

发布前架构评审对 `1.8.0` Redis 集群路线图作出 6 项关键修订，已写入 `docs/cluster-design.md` 与 `docs/development-plan.md`。其中影响 `1.8.0` 实施细节的最关键 3 项：

1. **Redis SPOF 降级模式变更默认**：原"保守策略"是 Redis 失联时主动关闭所有本地 session，会把 Redis 故障放大为整个 WebSocket 集群下线。1.8.0 改为 `cluster.on-redis-loss=degrade-to-local`（默认）——Redis 失联时本地 fan-out 与本地 session 保持工作，跨节点暂停；`close-all` 改为显式 opt-in。配套 60s 宽限期、重连风暴抖动、Sentinel/Redis Cluster 一等支持。
2. **`MessageSender` 接口契约诚实化**：原承诺"接口完全不变"在跨集群语义下做不到（同步签名内偷塞 Redis RTT）。修订为：本地查询接口语义不变（热路径安全），新增**异步**集群查询接口（`getClusterSessionIds`、`isSessionAliveCluster`、`closeSessionCluster` 返回 `CompletionStage`），at-most-once vs at-least-once 通过 `reliableBroadcast` 显式 opt-in。
3. **W3C TraceContext 必须随集群同步落地**：原计划推迟分布式追踪；评审指出分布式系统的最小可调试基线必须随第一个跨节点版本同步，否则首个生产事故就要做"消息在哪丢的"考古。改为在 `1.8.0` 第二刀强制完成 Pub/Sub 信封中的 `traceparent` 注入与 MDC 恢复。

另：`2.0.0` "Spring Boot 3.x 迁移"与"企业安全准入"拆分为两个独立 release——`2.0.0`（迁移基线，机械、时间敏感）与 `2.1.0`（企业安全，可独立勾选的 12 项 deliverable）。`2.0.0` 同时纳入 GraalVM native-image 基础支持。

详细 backlog 与路线图见 `docs/development-plan.md` 与 `docs/cluster-design.md`。

## 文档纠错（HIGH）

- 修复 `api-guide.md` `MessageCryptoKeyProvider` lambda 示例：原例返回 `byte[]`，但接口需要 `javax.crypto.SecretKey`。改为 `new SecretKeySpec(keyBytes, "AES")`。
- 修复 `api-guide.md` `ON_HANDSHAKE` 回调示例：原例对 `MessageSession` 参数调用 `getQueryParam`，但握手阶段 session 尚不存在（框架注入 `HttpRequest`）。改为 `HttpRequest` + `QueryStringDecoder` 提取 token，与 demo 实际代码一致。
- "15 close reasons" 描述更正为"每个 `CloseReason` 枚举值一条序列"（CloseReason 现有 14 个值，避免固定数字漂移）。
- 历史版本表修正：删除不存在的 `v1.6.0` 行（实际只有 `v1.6.1`/`v1.6.2`），`1.5.1` 行扩为 `1.5.0`–`1.5.1`。
- `dependency-governance.md` 空 `## Maven Profiles` 标题修复，并补充 logback 1.2.13 pin 与 Spring Boot OSS-EOL 说明。
- `cluster.enabled` 全局改名为 `cluster.enable`，与 `Mvc.enable` / `WebSocket.enable` / `Crypto.enable` 等现有命名约定一致——避免在 `1.8.0` 切配置键引发破坏性变更。

## 完成标准核对

- 全量 `mvn test` 通过（9 个模块，无新失败、无新警告） ✅
- 新增 6 个回归测试（CORS 2 + ON_CLOSE 2 + Compressor 1 + AES-GCM 1） ✅
- 发布前完成 4 路并行代码审计 + 架构评审 + 依赖审计 + 文档审计 ✅
- 所有改动向后兼容；无配置键变更（cluster 命名是未发布的规划阶段调整）✅
- README、`docs/api-guide.md`、`docs/netty-configuration.md`、`docs/websocket-configuration.md`、`docs/development-plan.md`、`docs/cluster-design.md`、`docs/dependency-governance.md` 已同步至 1.7.1 状态 ✅

## 升级指南

从 v1.7.0 升级：仅修改依赖版本号 `1.7.0` → `1.7.1`，无需修改任何业务代码或配置。

从更早 1.6.x / 1.5.x：同样仅需依赖坐标变更；行为差异详见对应版本 release notes。

如果之前手工配置了 `@CrossOrigin(origins="*", allowCredentials=true)`（强烈不建议），升级到 1.7.1 后该端点的 CORS 行为会变化：
- 不再回写客户端 Origin；
- 不再发出 `Access-Control-Allow-Credentials: true`；
- 日志会出现一条 WARN 提示该配置组合不安全。

正确做法是改用显式 origin 列表，例如 `@CrossOrigin(origins = {"https://myapp.com"}, allowCredentials = true)`。

## 审计纪要（已修复 vs 推迟）

发布前审计共发现 ~22 个 finding，按严重级分流：

| 严重级 | 已在 1.7.1 修复 | 推迟到 1.8.0+（需 API 设计或更大改动） |
|---|---|---|
| HIGH（安全） | CORS wildcard+credentials | — |
| MEDIUM（正确性） | DefaultMessageSender ON_CLOSE bypass | OPTIONS preflight 拦截 user-defined OPTIONS handler；POJO 绑定 prefix 匹配过宽；exception message 泄漏给客户端；EVENT_LOOP_DIRECT 路径上 crypto 加密阻塞 EventLoop |
| LOW（硬化） | CompressorHandler 解析/大小写、AES-GCM IV 长度 | Cookie `HTTPOnly` 拼写、静态文件接受所有 HTTP 方法、RFC 6266 文件名编码、AES-GCM envelope 错误信息差异 |
| 依赖 | logback 1.2.13 pin | Spring Boot 2.7.x OSS-EOL（架构层面议题，2.0.0 处理） |
| 架构 | `1.8.0` / `2.x` 路线图修订（cluster-design + dev-plan） | — |
| 文档 | 6 个 HIGH/MEDIUM 误导项 | — |
