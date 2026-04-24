# 开发计划与阶段状态

更新时间：2026-04-24

## 当前结论

- `1.0.1` 已完成首个发布后治理切片，可作为当前稳定发布版本。
- 当前代码最值得优先推进的，不再是继续堆 WebSocket 新功能，而是发布后工程化治理和 Starter 收敛。
- 后续计划应以“先稳住发布面，再统一入口，再扩能力”为顺序，这比直接进入产品功能扩展更符合仓库当前状态。

## 当前代码状态总结

### 已完成的基础面

- `netty-spring-web` 已具备基础 Netty 启动、HTTP 分发、静态文件处理、统一 handler 执行模型和运行时快照能力。
- `netty-spring-websocket` 已完成握手失败保护、握手成功后发布 session、错误路径统一、发送失败关闭链路、连接数限制、帧大小限制、广播背压策略和停机优雅关闭。
- `NettyServerBootstrap.stop()` 已具备资源回收、active websocket session 关闭、重复 stop 幂等和 stop/start 后 runtime 重建能力。
- `MessageSenderSupport` 已补齐空实现退化、重启后缓存刷新和停机联动关闭。
- 当前仓库已在本地 `GraalVM JDK 17.0.11 + Maven 3.9.9` 环境完成全量 `mvn test` 验证。

### 代码里已经暴露出的下一阶段问题

- 三个 Starter 仍然各自维护一套近似重复的 `NettyServerBootstrapConfigure` 和 `NettyServerStartupPropertiesWrapper`。
- Starter 启动失败时仍直接 `System.exit(1)`，这对库/Starter 场景不够友好，优先级应该高于新增产品功能。
- Starter 层目前缺少独立的集成测试，后续一旦重构自动配置，回归风险会明显升高。
- WebSocket API 仍偏底层，业务侧主要围绕 `HttpRequest`、`WebSocketFrame`、`MessageSession` 直接编程，还缺少更高层的鉴权、编解码和会话访问抽象。
- 可观测性目前主要是运行时快照和日志，还没有指标、健康检查、运维友好的暴露面。
- Demo 只覆盖了基础 HTTP 和简单 WebSocket echo/send，还不足以支撑 `1.x` 阶段的产品能力演示和回归验证。

## 1.0.0 基线范围

### P0 工程基线

- Maven Wrapper。
- 统一 Java、Spring Boot 和核心依赖版本。
- 最小回归测试框架。
- 全量 `mvn test` 可运行。

### P1 WebSocket 正确性

- 握手失败后不会继续创建 session。
- 握手成功后再发布 session。
- `ON_CLOSE`、`ON_ERROR`、transport error、`channelInactive` 生命周期已打通。
- 写失败不会提前绕过 session 清理链路。
- 关键路径已有单元测试覆盖。

### P2 WebSocket 并发与稳定性

- handler/message sender 线程池参数配置化。
- handler 准入 fail-fast，不阻塞 Netty event loop。
- 握手成功回调、写失败关闭、`channelInactive` 等回调统一进入应用侧执行模型。
- 最大连接数、最大帧大小、广播过载和不可写 channel 策略均已落地。
- 停机时 active websocket session 可优雅关闭，stop/start 生命周期已具备幂等行为。
- 已补 repeated stop、广播/停机交叉、sender 缓存刷新和空实现退化等回归测试。

## 更合理的后续阶段规划

### P3 发布后工程化治理

目标：把 `1.0.0` 从“功能可发”推进到“工程化可维护、可继续演进”。

重点项：

- 去掉三个 Starter 中的 `System.exit(1)`，改为标准 Spring Boot 启动失败传播。
- 为三个 Starter 和 demo 补最小集成测试，至少覆盖自动配置装配、Bean 暴露和启动失败行为。
- 整理发布清单、版本演进规则和回归验收清单，形成 `1.0.x` 维护基线。
- 继续补充启动失败、资源重复释放、异常 stop 路径的边界回归。

完成标准：

- Starter 失败时不会直接终止 JVM。
- Starter 具备基础集成测试，不再完全依赖 runtime 层单测兜底。
- 仓库形成可重复执行的发布前检查清单。

### P4 Starter 收敛与配置模型统一

目标：把当前“三套平行 Starter”收敛成更清晰、可扩展的配置与装配模型。

重点项：

- 统一配置入口，明确 `server.netty.*` 与 `server.netty.websocket.*` 的边界。
- 收敛重复的 `NettyServerBootstrapConfigure` / `NettyServerStartupPropertiesWrapper`。
- 明确 MVC/WebSocket 是否启用的开关，而不是依赖模块存在与否隐式决定行为。
- 统一 `MessageSender` / `MessageSenderSupport` 的 Bean 暴露方式。
- 避免多个 Starter 中同包同名自动配置类长期并行带来的维护成本。

完成标准：

- Starter 配置入口单一且文档一致。
- 自动配置职责边界清晰，重复代码明显减少。
- 引入或移除某个 Starter 时，行为差异可预测、可测试。

### P5 WebSocket 产品能力增强

目标：在稳定基线之上补齐真正面向业务使用的 WebSocket 能力。

重点项：

- 握手鉴权扩展点和鉴权失败统一返回模型。
- query/header/session 信息读取抽象，减少业务层直接操作底层对象。
- text/json/binary 编解码能力，支持更贴近业务对象的 handler 签名。
- 心跳、空闲超时、断线清理等连接生命周期能力。
- 按 URI 广播、单播、会话查询、会话关闭等更完整的 API。

完成标准：

- 业务控制器不必长期直接围绕 `WebSocketFrame` 编程。
- 常见聊天/推送/订阅场景可以用框架原生 API 完成。
- 新能力具备端到端示例和回归测试。

### P6 可观测与运维能力

目标：让运行中的 Netty/WebSocket 服务可观测、可诊断、可运维。

重点项：

- 暴露连接数、消息收发数、失败数、广播耗时、线程池状态等指标。
- 补充健康检查或轻量运维接口，至少覆盖运行时快照读取。
- 统一关键过载、拒绝、关闭路径的日志格式和诊断信息。
- 评估 Micrometer/Actuator 对接方式，而不是只停留在日志和内存快照。

完成标准：

- 问题排查不再主要依赖阅读业务日志。
- 线程池饱和、连接上限、广播拒绝等场景可被快速识别。
- 至少有一种稳定的指标或状态导出方式。

### P7 Demo 与文档体系

目标：把“能跑”升级到“能让别人快速接入并理解边界”。

重点项：

- 把 demo 从简单 echo/send 升级为更完整的聊天室、推送或订阅示例。
- README 以快速接入、配置说明、Starter 使用方式和常见问题为主线重写。
- 增加从 `1.0.0` 基线到后续能力的演进文档和示例。
- 补文档中的行为说明，而不只列配置项。

完成标准：

- 新用户能基于 README 和 demo 在短时间内跑通真实场景。
- 文档能解释配置项、行为差异和运维边界，而不仅是 API 罗列。

## 调整后的推荐顺序

1. 先做 `P3`，优先治理 Starter 启动失败行为和补齐 Starter 集成测试。
2. 再做 `P4`，收敛配置模型和自动配置结构，降低后续演进成本。
3. 然后推进 `P5`，补齐真正面向业务的 WebSocket 能力。
4. 在产品能力基本稳定后推进 `P6`，完善指标和运维能力。
5. 最后集中完成 `P7`，把 demo 和文档体系补齐。

## 版本节奏建议

- `1.0.0`：已发布基线，对应 `P0/P1/P2` 收口结果。
- `1.0.1`：`P3.1`，先修 Starter 启动失败传播、补 Starter 最小集成测试和 demo smoke test。
- `1.0.2`：`P3.2`，补齐发布清单、异常 stop/重复释放回归和 `1.0.x` 维护基线。
- `1.1.0`：`P4`，Starter 收敛与配置模型统一。这一阶段会触及配置入口和自动配置结构，适合进入新的 minor 版本。
- `1.2.0`：`P5`，WebSocket 产品能力增强，以新增能力为主。
- `1.3.0`：`P6`，可观测与运维能力建设。
- `1.3.x`：`P7`，demo 和文档体系持续补齐，跟随能力版本滚动完善，而不是等到最后一次性补文档。

## 近期建议拆成三个小里程碑

### 里程碑 A：P3.1 启动与测试基线收口

建议先把“发布后最容易踩坑、但修复收益最高”的问题一次收口：

- 去掉三个 Starter 中的 `System.exit(1)`。
- 为三个 Starter 分别补最小启动/装配集成测试。
- 为 demo 补一条最小 smoke test，确保样例工程能随版本演进持续启动。
- 整理一份发布前检查清单，固定 `1.0.x` 的回归入口。

通过标准：

- 启动失败由 Spring Boot 异常向上抛出，而不是直接终止 JVM。
- Starter 层第一次具备独立回归能力。
- 后续改 Starter 代码时，不需要完全依赖手工验证。

### 里程碑 B：P3.2/P4 配置入口与自动配置收敛

在测试兜底具备之后，再做结构收敛，避免边重构边失去回归抓手：

- 提炼三个 Starter 中重复的 bootstrap/configure/wrapper 逻辑。
- 明确 `server.netty.*`、`server.netty.http.*`、`server.netty.websocket.*` 的配置边界。
- 对外保留兼容策略，避免因为配置收敛直接打破 `1.0.x` 使用方式。
- 统一 `MessageSender`、`MessageSenderSupport` 和 MVC/WebSocket 开关的装配语义。

通过标准：

- Starter 内部重复代码显著下降。
- 自动配置职责边界可以被文字说明清楚，也能被测试证明。
- 后续新增 WebSocket 能力时，不需要反复改三套近似配置骨架。

### 里程碑 C：P5 能力增强与上层 API 补齐

只有在入口和自动配置稳定后，再推进面向业务的能力扩展：

- 握手鉴权扩展点。
- query/header/session 访问抽象。
- text/json/binary 编解码。
- 心跳、空闲超时、断线清理。
- 广播/单播/会话查询等上层 API。

通过标准：

- 业务控制器签名不再长期绑定底层 `WebSocketFrame`。
- 新能力都能落到 demo 和回归测试，而不是只落到 README。
- `1.1.x` 的能力扩展建立在稳定 Starter 和稳定配置模型之上。

## 阶段闸门

- `P3` 未完成前，不建议继续扩写 WebSocket 新能力，否则会把 Starter 和测试债务一起放大。
- `P4` 未完成前，不建议大规模扩展对外配置项，否则后续收敛时兼容成本会更高。
- `P5` 进入后，每新增一个能力，都应同时补一条端到端验证路径，避免再次只靠静态审查推进。

## 为什么这样调整更合理

- 当前 runtime 核心正确性和稳定性已经基本达标，继续深挖底层并不是最短板。
- 真正阻碍后续演进的，是 Starter 重复实现、失败启动行为不够库化、缺少集成测试这些工程化问题。
- 如果不先解决 Starter 和测试基建，再往上叠鉴权、编解码和心跳能力，后续重构成本会更高。
- 可观测性和 demo 很重要，但它们更适合放在能力模型趋于稳定之后集中完善。

## 说明

- 当前计划以仓库中的最新代码状态为准，不再按早期 review 数量统计阶段结论。
- `1.0.0` 版本以当前 P0/P1/P2 收口结果为发布基线，后续阶段从发布后工程化治理开始推进，而不是直接跳到大规模功能扩展。
