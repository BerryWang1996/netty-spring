# 开发计划与阶段状态

更新时间：2026-05-27

## 当前结论

- `1.0.2` 已完成 `P3.2` 发布后治理收口，可作为当前 `1.0.x` 稳定发布版本。
- `1.1.0-RC2` 已从 P4/P4.1 稳定线切出并发布；`1.2.0` 已作为 P5/P5.x WebSocket 产品能力正式版发布，`1.2.1` 已按功能/稳定性正式版口径完成收口；`1.2.2` 已按用户体验与开源接入体验增强版本口径完成收口；**`1.2.3` 已按生产就绪代码质量版本口径完成收口并发布**。
- `1.2.3` 集中修复了影响生产稳定性的核心代码缺陷：ByteBuf 生命周期安全、WebSocket session 创建死循环防护、心跳检测逻辑完善、线程可见性保证、Spring Boot 自动配置规范化和废弃 API 替换。
- `1.1.0-RC2` 发布线已具备框架功能候选基础：P4 配置边界、自动配置兼容性、`@Lazy` 依赖消除、`P4.1` 首批容量、失败路径、运行时统计、内置 health/status、线程池校验、SBOM/Dependency-Check 入口、Netty BOM 版本对齐、`netty-all` 瘦身和 GitHub Actions 门禁均已推进，并已在本地正常 Maven 环境完成全量 reactor `mvn test` 复验。
- 按当前决策，后续暂时不把安全问题作为主线阻塞项：Dependency-Check、Dependabot、安全扩展、CORS/鉴权/TLS 策略等进入冻结 backlog，后续需要企业生产安全版时再集中处理。
- **P6 核心已完成**：关闭原因维度化（`CloseReason` 枚举 + `WebSocketEventRecorder` 事件计数器）、握手鉴权扩展点（`WebSocketHandshakeInterceptor` 接口）已完成核心实现并接入 `MessageMappingResolver` 和 `DefaultMessageSender`，所有 close path 均已标注具体 `CloseReason`，管理端点 `/netty/status` 已包含 eventCounters；Micrometer 可选指标桥接已完成（`NettyWebSocketMeterBinder` + `NettyHttpMeterBinder` + `NettyMicrometerConfigure` 条件装配），demo 已增加 `auth-demo` profile 的 token 鉴权拦截器示例和 Actuator 指标端点。下一步：demo 聊天室升级、README 生产部署建议和 1.3.0 发布准备。

## 当前发版判断

- `1.0.x`：`1.0.2` 仍是当前可发布/可回退的稳定线。
- `1.1.0-RC2`：已从 P4/P4.1 复验点切出并推送 tag，定位为功能/稳定性候选，不混入 P5 产品能力。
- `1.2.0`：已发布的 WebSocket 产品能力正式版，承接 P5 首批 API、心跳/空闲断线和 AES-GCM 应用层消息加密首版。
- `1.2.1`：已发布的功能/稳定性正式版，URI/session 粒度 crypto 策略、密钥轮换基础示例、浏览器端加密 demo 和 WebSocket 轻量 runtime stats 已进入发布范围；不定位为企业安全正式版。
- `1.2.2`：用户体验版本，重点降低开源用户第一次接入、demo 运行、配置选择、常见错误排查和常用发送 API 的使用门槛。
- **`1.2.3`：生产就绪代码质量版本，集中修复了 10 项影响生产稳定性的核心缺陷，包括 ByteBuf 安全、session 死循环、心跳检测、线程可见性、RejectedExecutionException 泄漏、Spring Boot 自动配置规范化和废弃 API 替换。**
- `1.1.0-RC1`：历史候选口径，已被 `1.1.0-RC2` 取代。
- `1.1.0-RC2`：已作为”非安全阻塞版候选”发布，重点验证 Starter 收敛、配置兼容、核心 runtime 回归、SBOM 生成、Netty 依赖瘦身和 CI 基础链路；暂不要求 Dependency-Check/Dependabot triage 闭环。
- 可以进入当前口径下 `1.1.0` 功能正式版的前置条件：RC 后没有新增 P1/P2 级功能/稳定性 review finding，README/配置文档/发布检查清单与代码一致，版本号从 `1.1.0-RC2` 切到 `1.1.0` 后完成全量 `mvn test` 或 CI 等效验证。
- 企业生产安全版另行设门槛：Dependency-Check、Dependabot、安全扩展、CORS/鉴权/TLS 策略不再阻塞当前开发计划，但也不应被误认为已经完成。

## `1.2.1` 正式版门槛

本次 `1.2.1` 定位为 P5.x 功能/稳定性正式版，不定位为企业安全正式版。正式版需要完成以下功能和验收项：

- URI 级 crypto 策略闭环：`crypto.include-uris` / `crypto.exclude-uris` 对发送加密、接收解密和未加密帧拒绝策略保持一致，并覆盖公开 echo、调试通道和旧客户端灰度兼容场景。
- Session 级 crypto 策略闭环：`MessageCryptoPolicy` 可以在 URI 策略之后按 session 上下文二次决策，且入站、出站、拒绝未加密帧三条路径语义一致。
- AES-GCM 密钥轮换基础路径闭环：新 `key-id` 用于新消息加密，过渡期 `MessageCryptoKeyProvider` 仍可解析旧 `kid` 的历史密文。
- 浏览器端 crypto demo 闭环：`/ws/crypto-demo` 可以展示浏览器 WebCrypto envelope 与服务端 AES-GCM envelope 兼容，文档明确 demo key 不能作为生产密钥分发方案。
- 轻量可观测闭环：`NettyServerBootstrap#getWebSocketRuntimeStats()` 和内置 `/netty/status` 能读取 WebSocket mapping 数和活跃 session 数，作为 P6 前的最小观测入口。
- 回归测试闭环：全量 reactor `mvn test` 通过；如本地沙箱 Maven 仓库权限导致失败，需要在正常 Maven 环境或 CI 中完成等效验证。
- 文档闭环：README、WebSocket 配置文档、Netty 配置文档、发布检查清单和 `1.2.1` 发布说明都必须与代码一致。
- 发版动作闭环：根 `pom.xml` 已从 `1.2.1-SNAPSHOT` 切到 `1.2.1`；发布前再次完成全量测试，再创建发布提交、`v1.2.1` tag，并推送分支和 tag。

不作为本次 `1.2.1` 阻塞项的内容：

- Dependency-Check / Dependabot 漏洞 triage、标准握手鉴权扩展、完整 CORS/TLS 安全策略、Micrometer/Actuator 指标和完整聊天室/推送 demo，继续保留在后续企业安全版、`1.3.0` P6 和 `1.3.x` P7 中推进。

若以上门槛通过，`1.2.1` 可以发布为功能/稳定性正式版；若出现新的 P1/P2 级稳定性 finding，则停止发版并回到快照线修复。

## Review finding 状态

- Finding 1 静态文件根目录逃逸：已通过 URL decode、canonical path 和根目录包含校验闭环，并补 plain/encoded traversal 回归测试。
- Finding 2 MVC HTTP 写失败缺少处理：已为 MVC 响应写入增加 `ChannelFuture` 监听，失败时记录 HTTP 写失败计数并关闭 channel。
- Finding 3 HTTP max content length 硬编码：已接入 `server.netty.http.max-content-length`，并补默认值、配置值和非法值回退测试。
- Finding 4 handler 默认线程/permit 过大：默认值已从 `CPU * 200/300` 收敛为保守 CPU 基线，并补默认值和非法配置校验。
- 当前这些旧 finding 不再是下一步开发阻塞；全量回归复验已完成，后续阻塞从安全门禁调整为 P5 产品能力和 P6 可观测能力。

## 下一步判断

`1.2.3` 已作为生产就绪代码质量版本收口并发布（tag `v1.2.3`）。核心代码缺陷已修复，Spring Boot 自动配置已规范化，项目代码质量达到可发布新版本的标准。下一步正式进入 P6/P7 阶段。

优先级建议：

1. **P6 可观测与运维能力（下一个主要方向）**：Micrometer/Actuator 指标接入、关闭原因维度化、运行时诊断增强、统一事件模型。目标版本 `1.3.0`。
2. **P7 Demo 与文档体系**：随 P6 能力滚动补齐，demo 升级为更完整的聊天室/推送场景，README 和配置文档同步更新。
3. **握手鉴权扩展**：作为 P5 遗留项，可在 P6 阶段并行推进，为 WebSocket 连接增加标准的 token/session 鉴权扩展点。
4. **企业安全版本**：Dependency-Check、Dependabot、CORS/鉴权/TLS 策略深化继续保留冻结 backlog，不阻塞 P6/P7 推进。

## `1.2.3` 生产就绪代码质量版本

目标：对核心代码进行生产就绪审查，修复所有影响稳定性、线程安全和 Spring Boot 集成规范性的问题。

已完成修复（按严重级别排序）：

### P0 级（可能导致生产事故）

1. **BinaryMessage ByteBuf 生命周期问题**：原实现持有 `ByteBuf` 引用，跨线程广播时存在引用计数安全隐患。改为内部持有 `byte[]`，`responseMsg()` 每次返回新的 `BinaryWebSocketFrame`。
2. **MessageMappingResolver session 创建无限递归**：`createMessageSession()` 在 sessionId 冲突时递归调用自身，极端情况下可能栈溢出。改为有限循环（最多 5 次重试），失败后关闭连接。
3. **ServiceHandler RejectedExecutionException 消息泄漏**：线程池满拒绝任务时，已 retain 的 `msg` 未被 release，导致堆外内存泄漏。修复后在 catch 块中先 release 再关闭连接。

### P1 级（影响功能正确性）

4. **心跳检测逻辑不完整**：原实现只检查 `lastReadTimeMillis`，未校验 `lastPongTimeMillis`。修复后同时检查两者，确保真正的 pong 响应超时能被检测到。
5. **MessageSession URI 重复解析**：每次调用 `getPath()` / `getQueryParams()` 都重新解析 URI。改为构造时一次性解析并缓存到 `cachedPath` 和 `cachedQueryParams` 不可变字段。
6. **DefaultMessageSender.isSessionAlive 空参数校验**：传入 null 或空数组时未做防御处理。修复后返回 false。

### P2 级（代码质量与规范性）

7. **TextMessage/JsonMessage 字段缺少 volatile**：多线程环境下 content/objectMapper 字段缺乏可见性保证。添加 volatile 修饰。
8. **废弃 API 使用**：`LocalVariableTableParameterNameDiscoverer` 在 Spring 6+ 已废弃。所有模块统一替换为 `DefaultParameterNameDiscoverer` 静态实例。
9. **字段拼写错误**：`webSockeMappingtResolverMap` → `webSocketMappingResolverMap`，涉及 getter、反射和所有引用点。
10. **Spring Boot 自动配置不规范**：`@ConfigurationProperties` 类不应直接列在 `spring.factories` 中。改为标准 `@EnableConfigurationProperties` 模式注册。

### 验证

- 全量 reactor `mvn verify` 通过（9 个模块、约 138 个测试）。
- 已提交 `release: prepare 1.2.3` 并推送 tag `v1.2.3`。

## `1.3.0` 可观测与运维能力版本规划（下一版本）

目标：让运行中的 Netty/WebSocket 服务可观测、可诊断、可运维，同时补齐 P5 遗留的握手鉴权扩展点。

版本定位：

- `1.3.0` 是 P6 可观测性正式版，同时承接 P5 遗留的握手鉴权扩展和 P7 首批文��/demo 升级。
- 新增能力以标准 Spring Boot Actuator/Micrometer 集成为优先方向。
- 不引入 Spring Boot 3.x 迁移或 Jakarta namespace 变更（留给 `2.0.0`）。

建议拆成四刀：

1. **第一刀：Micrometer 指标接入** ✅ 已完成
   - 在 `netty-spring-boot-autoconfigure` 中通过 `@ConditionalOnClass(MeterRegistry.class)` 条件装配。
   - `micrometer-core` 作为 optional 依赖，无 Micrometer 时自动退化为内置 runtime stats。
   - `NettyWebSocketMeterBinder` 桥接 WebSocket 事件计数器到 Micrometer `MeterRegistry`，暴露指标：
     - `netty.websocket.handshakes.total/success/rejected`（握手计数）
     - `netty.websocket.messages.received/sent`（消息收发计数）
     - `netty.websocket.sessions.closed`（tagged by `reason`，覆盖全部 15 种 `CloseReason`）
     - `netty.websocket.sessions.active`（活跃 session 数 Gauge）
     - `netty.websocket.mappings`（注册 mapping 数 Gauge）
   - `NettyHttpMeterBinder` 桥接 HTTP 运行时计数器，暴露指标：
     - `netty.http.response.write.failures`、`netty.http.static.rejected`、`netty.http.static.write.failures`
     - `netty.http.idle.closes`、`netty.http.websocket.handshake.rejected`、`netty.http.websocket.origin.rejected`
   - `NettyMicrometerConfigure` 自动注册两个 `MeterBinder` Bean，WebSocket 部分额外条件要求 websocket 模块在 classpath。
   - Demo 已添加 `spring-boot-starter-actuator`，Actuator metrics 端点在 `localhost:8081/actuator/metrics` 可访问。
   - 已有完整单元测试覆盖。

2. **第二刀：关闭原因维度化** ✅ 已完成
   - 新增 `CloseReason` 枚举（15 个关闭原因，含 `client_close`、`heartbeat_timeout`、`transport_error`、`frame_too_large`、`decrypt_failure`、`write_failure`、`channel_not_writable` 等）。
   - 新增 `WebSocketEventRecorder`（线程安全的 AtomicLong 计数器，支持 enabled/noop 模式）和 `WebSocketEventStats`（不可变快照 DTO）。
   - `MessageMappingResolver` 所有 close path 已标注具体 `CloseReason`（共 10 个入口点）。
   - `DefaultMessageSender` 的 `WRITE_FAILURE` 和 `CHANNEL_NOT_WRITABLE` close path 也已标注。
   - `/netty/status` 管理端点已包含 `eventCounters`（通过 `WebSocketRuntimeStats` 聚合输出）。
   - 已有完整单元测试覆盖。

3. **第三刀：握手鉴权扩展点（P5 遗留）** ✅ 已完成
   - 新增 `WebSocketHandshakeInterceptor` 接口，支持 `beforeHandshake(FullHttpRequest, uri)` 和 `rejectionReason()` 方法。
   - 拦截器在 Origin 校验之后、`ON_HANDSHAKE` 回调之前执行；返回 `false` 返回 HTTP 403。
   - `MessageMappingSupporter` 自动从 ApplicationContext 发现 interceptor Bean 并注入所有 resolver。
   - 拦截器异常自动捕获并返回 HTTP 500，不影响服务端稳定性。
   - 已有 interceptor 拒绝/异常/通过三种场景的单元测试。
   - Demo 增加 `auth-demo` profile 的 token 鉴权拦截器示例（`WebSocketAuthDemoConfiguration`），支持 query parameter 和 Authorization Bearer header 两种传参方式。

4. **第四刀：运行时诊断与 demo 升级（P7 首批）**
   - 为常见运维场景（连接上限、线程池满、心跳超时、crypto 失败）增加结构化诊断日志。
   - demo 升级为简易聊天室示例：支持加入/离开通知、在线用户列表、广播消息。
   - README 文档更新：新增"生产部署建议"章节，覆盖线程池调优、超时配置、指标接入和常见问题排障。

`1.3.0` 完成标准：

- Micrometer 指标或等效可观测入口可以监控核心运行时状态。
- session 关闭原因可聚合、可告警，而不只是日志。
- 业务侧可以在握手阶段做自定义鉴权，而不需要在 `ON_HANDSHAKE` 回调中手写 channel close。
- 新用户可以通过 demo 聊天室示例快速理解框��的生产场景用法。
- 全量 reactor `mvn verify` 通过。

`1.3.0` 不作为阻塞项的内容：

- Spring Boot 3.x / Jakarta namespace 迁移（留给 `2.0.0`）。
- 完整企业安全准入（Dependency-Check triage、标准 CORS 策略）。
- 完整的集群/分布式 session 方案。
- WebSocket 子协议（STOMP 等）支持。

## `1.2.2` 用户体验版本规划

目标：让一个第一次接触项目的开源用户，在不读源码、不翻历史 issue、不理解全部底层 Netty 细节的情况下，可以快速完成依赖接入、启动 demo、连上 WebSocket、发送消息、启用常见配置，并在出错时知道下一步该查什么。

版本定位：

- `1.2.2` 是用户体验 patch 版本，不引入大规模核心架构调整。
- 优先解决“会用但不好上手”的问题，而不是继续堆底层能力。
- 不把企业安全准入、完整 Micrometer/Actuator、完整聊天室产品 demo 作为本版本阻塞项。

从当前项目使用路径观察到的主要不便：

- 快速开始路径不够短：README 说明了阶段状态，但缺少从 Maven 依赖、最小配置、示例 controller、浏览器连接到消息发送的 5 分钟闭环。
- Starter 选择不够直观：新用户不容易判断应该引入 web、webmvc、websocket 还是组合 starter，也不清楚 `server.netty.mvc.enable` / `server.netty.websocket.enable` 对装配行为的影响。
- 配置项较多但缺少场景化推荐：HTTP、WebSocket、线程池、心跳、crypto、管理端点都有配置说明，但缺少“开发环境最小配置”“公网生产建议配置”“只启用 WebSocket”“启用 crypto demo”这样的 presets。
- demo 缺少统一入口：当前 demo 有 HTTP 接口、WebSocket echo/json/binary、crypto 页面，但没有一个首页把这些能力、连接地址、操作步骤和状态端点串起来。
- crypto demo 启用成本偏高：用户需要手工取消 properties 注释，且 toy key、include-uris、浏览器 WebCrypto envelope 的关系需要来回读文档才能理解。
- 常用发送 API 仍有样板代码：业务侧发送文本或 JSON 时需要显式 new `TextMessage` / `JsonMessage`，对新手不如 `sendText()` / `broadcastJson()` 直观。
- 错误体验偏底层：部分异常和 demo 日志仍偏框架/Netty 视角，例如 mapping 不存在、session 不存在、crypto 未配置 key provider、解密失败、握手被 Origin 拒绝时，用户需要从日志和源码中反推解决方案。
- API 文档发现性不足：`MessageSession`、`MessageSender`、`MessageCryptoPolicy`、`MessageCryptoKeyProvider` 已经可用，但 README 没有面向业务开发者的“应该怎么写”示例索引。
- 测试入口对使用者不够友好：开发者知道 `mvn test`，但不知道改 demo、改 starter、改 websocket crypto 后应该优先跑哪组 targeted tests。
- 发版后版本节奏需要继续保持显性：`1.2.2` 发布后，下一条开发线应明确聚焦 P6/P7 的可观测性、demo 和文档体系，避免和安全专项同时拉扯。

`1.2.2` 建议拆成四刀：

1. 第一刀：开源接入文档重写。
   补 README 快速开始：依赖选择、最小 application 配置、最小 WebSocket controller、浏览器连接、发送文本/JSON、主动关闭 session、常见测试命令。
   增加 starter 选择表：只用 Netty 基础、HTTP MVC、WebSocket、HTTP + WebSocket 四种场景分别应该引入什么。
2. 第二刀：demo 体验入口。
   新增 demo 首页或导航页，列出 HTTP 示例、WebSocket echo、JSON 消息、binary 消息、crypto demo、health/status，并给出可直接复制的 URL。
   增加 demo profile 或独立配置文件，例如 `application-crypto-demo.properties`，避免用户通过手工取消注释来启用 crypto。
3. 第三刀：常用 API 便利方法。
   在不破坏现有 API 的前提下，为 `MessageSender` 增加 `sendText()`、`broadcastText()`、`sendJson()`、`broadcastJson()` 等默认方法，减少业务侧样板代码。
   同步 demo 和文档优先使用便利方法，保留 `TextMessage` / `JsonMessage` 作为高级入口。
4. 第四刀：错误提示与排障体验。
   梳理常见错误场景：未注册 URI、session 不存在、未配置 crypto key provider、多个 crypto Bean、Origin 拒绝、handler 过载、JSON 反序列化失败。
   为异常消息、日志和文档补“原因 + 建议动作”，避免只输出底层异常或 `printStackTrace`。

当前 `1.2.2` 已完成进度：

- 第一刀已完成首批：README 增加 5 分钟快速开始、starter 选择表、最小配置、最小 controller、demo 启动命令和 targeted test 命令。
- 第二刀已完成首批：demo 增加统一首页入口，并新增 `crypto-demo` Spring profile，避免通过手工取消注释启用加密演示。
- 第三刀已完成首批：`MessageSender` 增加 `sendText()`、`sendTextToSession()`、`broadcastText()`、`sendJson()`、`sendJsonToSession()`、`broadcastJson()` 便利方法，demo 已优先使用新 API。
- 第四刀已完成首批：未注册 URI、空 websocket mapping、session 过期、Origin 拒绝、连接数上限、handler 过载、JSON 反序列化失败和 crypto key provider/明文拒绝等常见错误，已补“原因 + 建议动作”的异常、日志或文档说明。

`1.2.2` 完成标准：

- 新用户可以只读 README 在 5 分钟内跑起 demo，并完成一次 WebSocket 文本 echo 和一次 JSON 消息收发。
- demo 首页能直接发现当前可体验的能力和连接地址，不需要翻源码找 URL。
- 启用 crypto demo 有明确 profile/配置入口，且文档解释 toy key 与生产 key provider 的边界。
- 常用发送文本/JSON 不再必须手写 `new TextMessage()` / `new JsonMessage()`。
- 常见错误日志或异常消息包含可执行的下一步建议。
- 开发计划、README、配置文档和 demo smoke test 与 `1.2.2` 状态一致。

## 企业生产就绪度评估

结论：在”暂时不考虑安全问题”的前提下，`1.2.3` 已完成核心代码质量审查并修复所有已发现的生产稳定性缺陷。`1.2.3` 是当前推荐的生产部署版本，代码质量达到发布新版本的标准。

P4.1 首批已完成：

- 静态文件服务已增加 URL decode、canonical path 和根目录包含校验，覆盖 plain/encoded traversal 回归测试。
- `server.netty.http.max-content-length` 已接入 HTTP 聚合上限配置，默认仍为 `65536`，无效值回退默认值。
- `server.netty.http.max-initial-line-length`、`max-header-size`、`max-chunk-size` 已接入 `HttpServerCodec`，HTTP request line/header/chunk 边界可以按服务调参。
- `server.netty.http.read-timeout-seconds`、`write-timeout-seconds`、`idle-timeout-seconds` 已接入 Netty timeout handler，默认关闭，显式负数启动失败。
- 启用 `server.netty.http.ssl.enable=true` 时，证书和私钥路径会在启动期校验，缺失或不是普通文件会直接启动失败。
- `server.netty.websocket.allowed-origins` 已接入握手前置校验，配置具体 Origin 后缺失或不匹配会返回 `403`。
- MVC 响应和静态文件发送写失败已监听 `ChannelFuture`，失败时记录诊断日志并关闭 channel。
- handler 默认 core/max/permit 已从 `CPU * 200/300` 收敛为更保守的 `max(2, CPU)` / `max(core, CPU * 2)` / `max * 2`，并补默认值回归测试。
- handler/sender 线程池已补启动期配置校验，拒绝负数容量和显式 `max < core` 配置。

仍需继续补齐的生产准入缺口：

- HTTP 请求容量与超时边界已有基础配置入口：body/request line/header/chunk 上限和 idle/read/write timeout 已配置化；后续需要继续结合真实部署压测调整推荐默认值和示例。
- HTTP/静态文件失败路径已有轻量运行时计数：MVC 写失败、静态文件拒绝/写失败、idle 关闭、WebSocket handshake/origin 拒绝可通过 `NettyServerBootstrap#getHttpRuntimeStats()` 读取；内置 health/status 管理端点已提供运维读取入口，后续仍需接入 Micrometer/Actuator，并补齐关闭原因维度。
- 线程池配置校验已有基础约束，后续还需要把校验错误接入更清晰的 starter 启动失败诊断和文档示例。
- 安全基线暂时冻结：TLS 证书文件校验和 Origin 白名单已补齐，但标准握手鉴权扩展、完整 CORS 策略、TLS 策略深化和安全示例暂不进入当前主线。
- 可观测性已从快照/API 层推进到轻量管理端点：已有 handler/http/websocket/sender runtime stats，且 handler/http/websocket 可通过内置 health/status 读取；后续还缺 Micrometer/Actuator 指标、拒绝/过载/写失败统一事件和更完整的关闭原因维度。
- 依赖与供应链治理暂时冻结：新增 `sbom` / `dependency-scan` Maven profile、Dependency-Check suppression 占位文件、处理规则、Netty BOM 版本对齐、`netty-all` 瘦身和 GitHub Actions 门禁已完成；Dependabot/Dependency-Check 漏洞 triage 暂不作为当前版本阻塞。
- Demo 已补统一首页、crypto profile 和更直观的 MessageSender 便利 API 示例；后续还需要继续把它扩展为更完整的企业接入、配置推荐和运维排障示范。

建议把当前阶段拆成两个门槛：

- `1.1.0-RC1`：历史候选，用于验证 Starter 收敛、配置命名空间、`MessageSender` 注入语义和兼容性。
- `1.1.0-RC2` / `1.1.0`：当前只按功能/稳定性门槛推进；安全扫描、漏洞 triage 和安全产品化能力推迟到后续企业生产安全版。

## 当前代码状态总结

### 已完成的基础面

- `netty-spring-web` 已具备基础 Netty 启动、HTTP 分发、静态文件处理、统一 handler 执行模型和运行时快照能力。
- `netty-spring-websocket` 已完成握手失败保护、握手成功后发布 session、错误路径统一、发送失败关闭链路、连接数限制、帧大小限制、广播背压策略和停机优雅关闭。
- `NettyServerBootstrap.stop()` 已具备资源回收、active websocket session 关闭、重复 stop 幂等和 stop/start 后 runtime 重建能力。
- `MessageSenderSupport` 已补齐空实现退化、重启后缓存刷新和停机联动关闭。
- mapping resolver 已支持按 bean name 延迟解析 controller，业务侧 websocket controller 可直接构造注入 `MessageSender` 或 `MessageSenderSupport`，不再需要显式 `@Lazy`。
- Starter 已抽出共用 `netty-spring-boot-autoconfigure` 模块，`NettyServerBootstrapConfigure` / `NettyServerStartupPropertiesWrapper` 不再在三个 Starter 中各维护一份完全相同的实现。
- `MessageSenderSupport` 自动配置已统一回公共 autoconfigure，Starter 自身不再重复持有 `spring.factories` 和 sender 配置骨架。
- `MessageSenderSupport` 默认 Bean 已同时暴露 `messageSenderSupport` / `messageSender` 名称，并在用户自定义 `MessageSender` Bean 时正确 back off，业务侧可以优先按接口注入。
- `server.netty.mvc.enable` / `server.netty.websocket.enable` 已接入真实 mapping 初始化路径，并补了 starter 级回归测试验证开关生效。
- `server.netty.http.*` 已作为 HTTP/file/gzip/ssl 的推荐新命名空间引入，旧的 `server.netty.gzip.*`、`server.netty.file-location` 等顶层配置继续兼容。
- 已补 `server.netty.http.*` 新旧配置绑定测试，覆盖静态文件、gzip、SSL；并补 `StartupPropertiesUtil` 运行时校验测试，确认静态文件路径读取统一走 HTTP 配置视图。
- `P4.1` 已继续落地生产硬化：静态文件根目录逃逸保护、HTTP 聚合/解码/超时边界配置化、TLS 证书/协议/套件配置、WebSocket Origin 白名单、MVC/静态文件写失败关闭、HTTP 失败路径运行时计数、内置 health/status 管理端点、handler 默认线程/permit 收敛、handler/sender 配置校验均已有回归测试；SBOM 和依赖漏洞扫描已加入显式 Maven profile，Netty 子模块已通过 BOM 对齐到 `${netty.version}`，runtime 已移除 `netty-all` 改为最小 Netty 模块集合，并接入 GitHub Actions 门禁入口。
- 本轮 P4.1/P5 首批变更后，已在本地 `GraalVM JDK 17.0.11 + Maven 3.9.9` 环境完成全量 reactor `mvn test` 验证，9 个模块均通过。
- `1.2.3` 已完成生产就绪代码质量审查：BinaryMessage 改为 byte[] 内部存储消除 ByteBuf 跨线程引用计数风险；MessageMappingResolver session 创建改为有限重试消除栈溢出风险；ServiceHandler 修复 RejectedExecutionException 时的 ByteBuf 泄漏；心跳检测同时校验 lastReadTime 和 lastPongTime；MessageSession 构造时缓存 path/queryParams；TextMessage/JsonMessage 字段添加 volatile；所有模块统一使用 DefaultParameterNameDiscoverer 替换废弃 API；Spring Boot autoconfigure 改为标准 @EnableConfigurationProperties 模式。

### 代码里已经暴露出的下一阶段问题

- **可观测性已完成核心链路**：`CloseReason` 枚举 + `WebSocketEventRecorder` + Micrometer `MeterBinder` 桥接 + `/netty/status` eventCounters 均已落地；后续可选扩展：handler 线程池指标、广播耗时直方图、Prometheus endpoint 演示。
- **握手鉴权扩展点已完成**：`WebSocketHandshakeInterceptor` 接口 + `auth-demo` profile 示例已可用。
- **Demo 场景偏基础**：demo 已有首页入口、crypto 演示、Actuator metrics 和 auth 演示，但还不足以展示真实的聊天室/推送/订阅场景。
- Starter 层集成测试覆盖面仍偏基础，后续需要补更多装配/兼容场景（特别是自定义 Bean、开关禁用组合）。
- 远期需要评估 Spring Boot 3.x / Jakarta namespace 迁移路径。

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

- 统一配置入口，明确 `server.netty.*`、`server.netty.http.*` 与 `server.netty.websocket.*` 的边界。
- 收敛重复的 `NettyServerBootstrapConfigure` / `NettyServerStartupPropertiesWrapper`。
- 明确 MVC/WebSocket 是否启用的开关，而不是依赖模块存在与否隐式决定行为。
- 统一 `MessageSender` / `MessageSenderSupport` 的 Bean 暴露方式，并明确推荐业务侧优先面向接口注入。
- 调整 mapping 扫描与 resolver 持有模型，避免在 `nettyServer` 启动扫描阶段提前实例化 controller，消除业务侧注入 `MessageSenderSupport` 时必须显式 `@Lazy` 的要求。
- 避免多个 Starter 中同包同名自动配置类长期并行带来的维护成本。

完成标准：

- Starter 配置入口单一且文档一致。
- 自动配置职责边界清晰，重复代码明显减少。
- controller 可以直接构造注入 `MessageSender` 或 `MessageSenderSupport`，而不再依赖 `@Lazy` 作为循环依赖规避手段。
- 引入或移除某个 Starter 时，行为差异可预测、可测试。

### P4.1 稳定性与发布门禁硬化

目标：把 `1.1.0-RC2` 从“框架功能候选”推进到“功能/稳定性可发布候选”。安全、漏洞扫描和企业生产安全准入暂时冻结，不作为当前主线阻塞。

当前进度：

- 已完成第一刀：静态文件根目录逃逸保护、`server.netty.http.max-content-length`、MVC 写失败处理、handler 默认线程/permit 收敛。
- 已完成第二刀：HTTP request line/header/chunk 解码上限配置化、静态文件发送失败关闭 channel、handler/sender 线程池启动期配置校验。
- 第二刀新增回归覆盖 HTTP codec 上限配置绑定、静态文件写失败关闭、线程池非法配置拒绝。
- 已完成第三刀：HTTP read/write/idle timeout 配置化，空闲事件统一关闭 channel，负数超时配置启动期失败。
- 第三刀新增回归覆盖 timeout 配置绑定、默认关闭、负数校验和 idle 事件关闭。
- 已完成第四刀：TLS 证书和私钥路径启动期校验，缺失或非普通文件直接启动失败。
- 第四刀新增回归覆盖 SSL 启用时证书/私钥必填、文件必须存在、合法配置可通过。
- 已完成第五刀：`server.netty.websocket.allowed-origins` 握手前置校验，支持精确 Origin 列表和显式 `*` 通配。
- 第五刀新增回归覆盖 Origin 不匹配拒绝、匹配放行、`*` 放行，以及 Boot 配置绑定。
- 已完成第六刀：`server.netty.http.ssl.protocols` / `server.netty.http.ssl.ciphers` 接入 TLS 协议和 cipher suite 白名单，默认保持 Netty/JDK 行为，生产环境可显式收紧 TLS 策略。
- 第六刀新增回归覆盖 TLS 协议/套件配置绑定和逗号/空白分隔解析。
- 已完成第七刀：`server.netty.management.*` 接入内置 health/status 管理端点，默认关闭，开启后可读取健康状态和 handler/http 运行时快照。
- 第七刀新增回归覆盖管理端点配置绑定、路径校验、health 响应和 status runtime snapshot 响应。
- 已完成第八刀：新增 `sbom` / `dependency-scan` Maven profile，分别生成 CycloneDX SBOM 和 OWASP Dependency-Check 报告，并补 `dependency-check-suppressions.xml`、依赖治理文档和发布门禁规则。
- 第九刀调整为收尾状态：已把 SBOM/Dependency-Check 入口接入 GitHub Actions，固定 Dependency-Check cache 目录，补 Netty BOM 避免 Netty 子模块被 Spring Boot BOM 压回旧版本，并移除 `netty-all` 降低无关协议模块带来的依赖面；真实漏洞 triage 暂时冻结，不作为当前 tag 阻塞。

第九刀建议拆成可验收的小步：

1. 已新增 CI workflow，执行全量 `mvn test`。
2. 已在同一门禁中生成 CycloneDX SBOM，并保留 `target/netty-spring-sbom.json` / `target/netty-spring-sbom.xml` 作为可下载 artifact。
3. 已在 POM 和 CI 中固定 Dependency-Check cache 目录为 `${settings.localRepository}/../dependency-check-data`；`NVD_API_KEY` 和真实漏洞扫描暂时不作为当前阻塞。
4. Dependency-Check 与 Dependabot 告警 triage 转入冻结 backlog，后续做企业生产安全版时恢复。
5. 全量 reactor 复验已完成，且当前未发现新的 P1/P2 功能/稳定性 finding；`1.1.0-RC2` 已从 P4.1 复验点切出，正式 `1.1.0` 仍需完成 RC 验证和最终版本号切换。

重点项：

- 继续配置化 HTTP 运行时边界：已补 `server.netty.http.max-content-length`、`max-initial-line-length`、`max-header-size`、`max-chunk-size`、`read-timeout-seconds`、`write-timeout-seconds`、`idle-timeout-seconds`，后续根据压测结果沉淀推荐生产默认值。
- 继续补齐 HTTP/MVC 写失败处理：MVC 响应和静态文件发送失败已处理，轻量失败计数已接入 `getHttpRuntimeStats()`，后续补关闭原因维度和指标暴露。
- 继续收敛线程池默认值与配置校验：handler 默认 core/max/permit 已调整，handler/sender 已补基础启动期校验，后续补更完整的错误提示和 starter 诊断。
- 建立生产观测基线：短期已通过运行时快照和内置 health/status 管理端点暴露 handler/http 计数与线程池状态；后续通过 Micrometer/Actuator 暴露连接数、拒绝数、写失败数、广播耗时和关闭原因。
- 安全与依赖治理冻结项：握手鉴权扩展、完整 CORS、安全失败响应策略、Dependency-Check 真实扫描、Dependabot 告警处理暂不进入当前主线。

完成标准：

- 启用静态文件服务时不能逃逸根目录，且有路径穿越回归测试。
- HTTP 请求大小、超时、TLS 基础参数等关键稳定性参数可配置、可文档化、可测试。
- 过载、写失败、关闭、拒绝等路径都能被指标或状态端点观察到。
- 安全扫描和漏洞 triage 暂不作为当前 `1.1.0` 功能正式版门槛，但必须在计划中保留为后续企业生产安全版门槛。

### P5 WebSocket 产品能力增强

目标：在稳定基线之上补齐真正面向业务使用的 WebSocket 能力。

当前进度：

- 第一刀已完成：`MessageSender` 新增会话查询 API，支持按 URI 获取 session id 快照、单个 session、session 快照。
- 第一刀已完成：`MessageSender` 新增 `sendToSession()` 和 `broadcast()` 语义化别名，保留原有 `sendMessage()` / `topicMessage()` 兼容入口。
- 第一刀已补回归测试：覆盖空 websocket mapping 场景、重启后 sender 缓存刷新、只读会话快照和单播别名。
- 第二刀已完成：`MessageSession` 新增 URI、path、query 参数和 header 读取 API，减少业务侧直接操作底层 `FullHttpRequest`。
- 第三刀已完成：`TEXT_MESSAGE` 支持直接绑定 `String` 参数，`BINARY_MESSAGE` 支持直接绑定 `ByteBuf` 或 `byte[]` 参数，同时保留原始 frame 参数兼容。
- 第四刀已完成：新增 `JsonMessage`，支持将业务对象序列化为 JSON text frame 后发送。
- 第五刀已完成：`TEXT_MESSAGE` 支持把 JSON 文本直接绑定到业务 POJO、`Map`、`Collection`、数组、枚举或基础包装类型，反序列化失败进入现有 `ON_ERROR` 生命周期。
- 第六刀已完成：`MessageSender` 新增 `closeSession()` / `closeSessions()`，业务侧可以主动关闭单个 session 或 URI 下全部 session，关闭仍走统一 `ON_CLOSE` 与清理链路。
- Demo 已同步第一批 P5 API：示例控制器改用 `broadcast()`、`sendToSession()`、`closeSession()`、`JsonMessage`、`String`、JSON POJO 和 `byte[]` 参数绑定。
- P5.x 第一刀已完成：新增 `heartbeat-interval-seconds` / `heartbeat-timeout-seconds`，服务端可定时发送 Ping，并在空闲超时后走统一 `ON_ERROR` / `ON_CLOSE` 清理链路，已补配置绑定、参数校验和生命周期回归测试。
- P5.x 第二刀已完成首版并随 `1.2.0` 发布：新增默认关闭的 `server.netty.websocket.crypto.*` 配置命名空间、`MessageCryptoCodec` 扩展点、`MessageCryptoKeyProvider` 密钥解析接口和内置 `AES-GCM` 实现；发送侧在写出 frame 前加密，接收侧在 handler 参数绑定前解密，默认拒绝对应 text/binary 类型上的未加密数据帧。
- P5.x 第三刀已随 `1.2.1` 收口：新增 `crypto.include-uris` / `crypto.exclude-uris` 和可选 `MessageCryptoPolicy`，支持按 WebSocket path、原始 URI、mapping URL 或 session 上下文控制是否启用应用层 crypto，便于灰度兼容公开 echo、调试通道和旧客户端；同时补 AES-GCM 密钥轮换基础回归、demo key provider 示例和浏览器端 WebCrypto 联调页面。

重点项：

- 握手鉴权扩展点和鉴权失败统一返回模型。
- query/header/session 信息读取抽象，减少业务层直接操作底层对象。
- text/json/binary 编解码能力，支持更贴近业务对象的 handler 签名。
- WebSocket 应用层消息加密/解密扩展，避免浏览器 Network/WebSocket 面板直接看到业务原始明文帧。
- 心跳、空闲超时、断线清理等连接生命周期能力。
- 按 URI 广播、单播、会话查询、会话关闭等更完整的 API。

完成标准：

- 业务控制器不必长期直接围绕 `WebSocketFrame` 编程。
- 常见聊天/推送/订阅场景可以用框架原生 API 完成。
- 新能力具备端到端示例和回归测试。

#### P5.x WebSocket 应用层消息加密专项

目标：在不替代 TLS 的前提下，为业务消息提供可插拔的应用层加密能力，让浏览器开发者工具的 WebSocket frame 面板看到的是密文 envelope，而不是直接可读的业务 JSON/text/binary 明文。

边界说明：

- 该能力用于隐藏 WebSocket frame 中的业务原始数据，降低浏览器 Network 面板、代理日志或中间层日志直接暴露明文的概率。
- 该能力不能替代 HTTPS/WSS/TLS，也不能承诺“浏览器端完全不可见明文”；如果前端需要解密，浏览器运行时必然能接触密钥或明文。
- 企业级强安全场景仍需要结合 TLS、鉴权、密钥分发、密钥轮换、前端运行时保护和审计策略统一设计。

建议拆分：

- 新增 `MessageCryptoCodec` / `MessageCipher` 扩展点，负责 `encrypt()` / `decrypt()`，默认关闭。
- 第一阶段已落 `MessageCryptoCodec`、`MessageCryptoKeyProvider`、`server.netty.websocket.crypto.*`、唯一 Bean 装配校验、发送/接收链路接线和默认关闭兼容性测试。
- 首个内置实现已采用 `AES-GCM`，密文 envelope 包含 `alg`、`kid`、`typ`、`iv`、`ciphertext` 字段；Java AES-GCM authentication tag 附加在 `ciphertext` 中，不单独暴露 `tag` 字段。
- 新增 `server.netty.websocket.crypto.*` 配置命名空间，包含 `enable`、`algorithm`、`key-id`、`key-provider`、`encrypt-text`、`encrypt-binary`、`close-on-decrypt-failure`、`reject-unencrypted` 等开关或策略。
- 发送侧支持 `EncryptedMessage` 或发送管线自动加密，把 `TextMessage` / `JsonMessage` / `BinaryMessage` 转换为密文帧。
- 接收侧在 handler 参数绑定前完成解密，解密失败或未加密数据帧被策略拒绝时进入统一 `ON_ERROR` / `ON_CLOSE`，并可通过 `close-on-decrypt-failure` 和 `reject-unencrypted` 调整兼容策略。
- 支持按 URI 或按 session 选择是否加密，避免和公开 echo、健康检查、调试通道混用；`1.2.1` 已落地 URI include/exclude 策略，并新增 `MessageCryptoPolicy` 作为 session 粒度二次筛选扩展点。
- demo 增加 key provider 与 `/ws/crypto-demo` 浏览器端加密/解密示例，展示 Network 面板看到密文、业务回调拿到明文对象的完整流程。
- 回归测试覆盖加密发送、解密接收、错误密钥/篡改 tag 失败、未启用时保持兼容、按 URI/session 策略开关和密钥轮换基础路径。

完成标准：

- 加密能力默认关闭，不破坏当前明文 WebSocket 使用方式。
- 启用后，服务端 handler 仍可使用 `String` / JSON POJO / `byte[]` 等高层签名，不需要业务侧手写解密逻辑。
- 密钥不硬编码在框架内，至少提供可替换的 key provider 入口。
- 文档明确说明它是应用层混淆/加密能力，不是 TLS 或完整端到端安全方案的替代品。

### P6 可观测与运维能力

目标：让运行中的 Netty/WebSocket 服务可观测、可诊断、可运维。

重点项：

- 暴露连接数、消息收发数、失败数、广播耗时、线程池状态等指标。
- 在已有内置 health/status 管理端点基础上，继续补 Micrometer/Actuator 对接和更标准的指标命名；`/netty/status` 已开始输出 WebSocket mapping 数和活跃 session 数。
- 统一关键过载、拒绝、关闭路径的日志格式和诊断信息。
- 补齐关闭原因、拒绝原因、写失败原因等可聚合维度，而不是只停留在日志和内存快照。

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

1. ~~先做 `P3`，优先治理 Starter 启动失败行为和补齐 Starter 集成测试。~~ **已完成（1.0.1/1.0.2）**
2. ~~再做 `P4`，收敛配置模型和自动配置结构，降低后续演进成本。~~ **已完成（1.1.0-RC2）**
3. ~~在 `P4` 后追加 `P4.1` 稳定性与发布门禁硬化。~~ **已完成（1.1.0-RC2）**
4. ~~暂停安全门禁后，优先推进 `P5`，补齐真正面向业务的 WebSocket 能力。~~ **已完成（1.2.0/1.2.1）**
5. ~~`1.2.2` 用户体验增强。~~ **已完成（1.2.2）**
6. ~~`1.2.3` 生产就绪代码质量审查。~~ **已完成（1.2.3）**
7. **→ 当前阶段：`P6` 可观测与运维能力 + P5 遗留握手鉴权（目标 1.3.0）**
8. `P7` demo 和文档体系持续补齐，随 P6 滚动完善。
9. 远期 `2.0.0`：Spring Boot 3.x 迁移 + 企业安全版本。

## 版本节奏建议

- `1.0.0`：已发布基线，对应 `P0/P1/P2` 收口结果。
- `1.0.1`：`P3.1`，先修 Starter 启动失败传播、补 Starter 最小集成测试和 demo smoke test。
- `1.0.2`：已发布，完成 `P3.2` 的发布清单、异常 stop/startup failure 清理回归和 `1.0.x` 维护基线。
- `1.1.0-RC2`：已从 P4/P4.1 稳定线发布，包含 resolver 延迟取 bean、`MessageSenderSupport` 构造注入去 `@Lazy`、`netty-spring-boot-autoconfigure` 共用模块抽取、MVC/WebSocket enable 开关接线、`MessageSender` 接口注入语义固化，以及 `server.netty.http.*` 子命名空间引入并兼容旧顶层配置键。
- `1.1.0-RC1`：历史候选版本，仅用于承接 P4 已完成内容的预发布验收；定位为受控环境验证，不作为企业生产默认部署版本。
- `1.1.0-RC2`：建议用于承接 `P4.1` 首批稳定性硬化后的候选验证，重点验证容量、失败路径、轻量管理端点、SBOM、Netty 依赖一致性和 CI 基础链路；暂不要求安全扫描/漏洞 triage 闭环。
- `1.1.0`：目标功能正式版本，完成 Starter 收敛、配置模型统一和稳定性硬化。这一阶段触及配置入口、自动配置结构、兼容模型和发布门禁，适合进入新的 minor 版本。
- `1.2.0`：已发布的正式版本，`P5` 首批 WebSocket 产品能力增强已落地，心跳/空闲断线第一刀已完成，应用层消息加密扩展点和 AES-GCM 首版实现已完成。
- `1.2.1`：目标功能/稳定性正式版，承接 URI/session 粒度 crypto 策略、密钥轮换基础示例、浏览器端 crypto demo、WebSocket runtime stats 与对应配置文档/回归测试；发布条件见本文 `1.2.1 正式版门槛`；不承诺企业安全准入，不混入 Micrometer/Actuator 或大规模 demo 重写。
- `1.2.2`：用户体验正式版，通过标准见本文 `1.2.2 用户体验版本规划`；不混入企业安全准入和完整 P6 指标体系。
- `1.2.3`：**已发布**。生产就绪代码质量版本，集中修复 ByteBuf 安全、session 死循环、心跳检测、RejectedExecutionException 泄漏、线程可见性、Spring Boot 自动配置规范化和废弃 API 替换共 10 项。
- `1.3.0-SNAPSHOT`：建议作为下一条开发线，正式承接 P6 可观测性（Micrometer/Actuator 指标、关闭原因维度、统一事件模型）和 P5 遗留的握手鉴权扩展。
- `1.3.0`：`P6`，可观测与运维能力建设正式版。
- `1.3.x`：`P7`，demo 和文档体系持续补齐，跟随能力版本滚动完善，而不是等到最后一次性补文档。
- `2.0.0`（远期）：企业安全版本 + Spring Boot 3.x 适配，Dependency-Check/Dependabot 闭环、标准鉴权/CORS/TLS 策略、Jakarta namespace 迁移。

## 近期建议拆成四个小里程碑

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
- 统一 `MessageSender`、`MessageSenderSupport` 和 MVC/WebSocket 开关的装配语义，并继续梳理 `server.netty.*` 的子命名空间边界。

通过标准：

- Starter 内部重复代码显著下降。
- 自动配置职责边界可以被文字说明清楚，也能被测试证明。
- 后续新增 WebSocket 能力时，不需要反复改三套近似配置骨架。

### 里程碑 C：P4.1 稳定性与发布门禁硬化

在正式 `1.1.0` 前增加功能/稳定性发布门槛；安全生产准入暂时不纳入当前里程碑：

- 静态文件根目录逃逸保护。
- HTTP 请求大小、header、超时和 TLS 参数配置化。
- MVC/HTTP 写失败、静态文件发送失败和过载拒绝进入统一诊断链路。
- handler/sender 线程池默认值收敛，并增加启动期配置校验。
- SBOM、CI 基础链路和依赖版本一致性进入发布检查。
- 握手鉴权扩展、Origin/CORS 安全策略、依赖漏洞扫描和安全示例暂时冻结。

通过标准：

- 容量、超时、失败路径、指标入口和健康检查有明确入口。
- `1.1.0` 功能正式版发布前不再依赖口头约定判断稳定性。
- 安全生产准入另设后续里程碑，不作为本阶段通过标准。

### 里程碑 D：P5 能力增强与上层 API 补齐

只有在入口和自动配置稳定后，再推进面向业务的能力扩展：

- 握手鉴权扩展点。
- query/header/session 访问抽象。
- text/json/binary 编解码。
- WebSocket 应用层消息加密/解密，支持密文 frame、可插拔算法、按 URI/session 策略和浏览器端 demo。
- 心跳、空闲超时、断线清理。
- 广播/单播/会话查询等上层 API。

通过标准：

- 业务控制器签名不再长期绑定底层 `WebSocketFrame`。
- 启用加密后，浏览器 Network/WebSocket 面板不能直接看到业务原始明文，服务端业务 handler 仍能拿到解密后的高层对象。
- 新能力都能落到 demo 和回归测试，而不是只落到 README。
- `1.1.x` 的能力扩展建立在稳定 Starter 和稳定配置模型之上。

## 阶段闸门

- `P3` 未完成前，不建议继续扩写 WebSocket 新能力，否则会把 Starter 和测试债务一起放大。
- `P4` 未完成前，不建议大规模扩展对外配置项，否则后续收敛时兼容成本会更高。
- 当前暂停安全门禁后，`P4.1` 只阻塞功能/稳定性质量，不阻塞 P5 产品能力启动。
- `P5` 进入后，每新增一个能力，都应同时补一条端到端验证路径，避免再次只靠静态审查推进。

## 为什么这样调整更合理

- 当前 runtime 核心正确性和稳定性已经基本达标，继续深挖底层并不是最短板。
- 真正阻碍后续演进的，是 Starter 重复实现、失败启动行为不够库化、缺少集成测试这些工程化问题。
- 如果不先解决 Starter 和测试基建，再往上叠鉴权、编解码和心跳能力，后续重构成本会更高。
- 可观测性和 demo 很重要，但它们更适合放在能力模型趋于稳定之后集中完善。

## 说明

- 当前计划以仓库中的最新代码状态为准，不再按早期 review 数量统计阶段结论。
- `1.0.0` 版本以当前 P0/P1/P2 收口结果为发布基线，后续阶段从发布后工程化治理开始推进，而不是直接跳到大规模功能扩展。
