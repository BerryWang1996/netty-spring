# 开发计划与阶段状态

更新时间：2026-04-27

## 当前结论

- `1.0.2` 已完成 `P3.2` 发布后治理收口，可作为当前 `1.0.x` 稳定发布版本。
- `1.1.0-RC2` 已从 P4/P4.1 稳定线切出并发布；`1.2.0` 已作为 P5/P5.x WebSocket 产品能力正式版发布，当前主开发线已切到 `1.2.1-SNAPSHOT`。
- `1.1.0-RC2` 发布线已具备框架功能候选基础：P4 配置边界、自动配置兼容性、`@Lazy` 依赖消除、`P4.1` 首批容量、失败路径、运行时统计、内置 health/status、线程池校验、SBOM/Dependency-Check 入口、Netty BOM 版本对齐、`netty-all` 瘦身和 GitHub Actions 门禁均已推进，并已在本地正常 Maven 环境完成全量 reactor `mvn test` 复验。
- 按当前决策，后续暂时不把安全问题作为主线阻塞项：Dependency-Check、Dependabot、安全扩展、CORS/鉴权/TLS 策略等进入冻结 backlog，后续需要企业生产安全版时再集中处理。
- 后续计划调整为“先完成可发布功能基线复验，再做 WebSocket 产品能力，再做观测与 demo”，避免当前阶段继续被安全门禁拖住主线演进。

## 当前发版判断

- `1.0.x`：`1.0.2` 仍是当前可发布/可回退的稳定线。
- `1.1.0-RC2`：已从 P4/P4.1 复验点切出并推送 tag，定位为功能/稳定性候选，不混入 P5 产品能力。
- `1.2.0`：已发布的 WebSocket 产品能力正式版，承接 P5 首批 API、心跳/空闲断线和 AES-GCM 应用层消息加密首版。
- `1.2.1-SNAPSHOT`：当前开发线，优先补齐 URI 粒度 crypto include/exclude 策略、密钥轮换示例和浏览器端加密 demo；该版本仍是快照线，不创建发布 tag。
- `1.1.0-RC1`：历史候选口径，已被 `1.1.0-RC2` 取代。
- `1.1.0-RC2`：已作为“非安全阻塞版候选”发布，重点验证 Starter 收敛、配置兼容、核心 runtime 回归、SBOM 生成、Netty 依赖瘦身和 CI 基础链路；暂不要求 Dependency-Check/Dependabot triage 闭环。
- 可以进入当前口径下 `1.1.0` 功能正式版的前置条件：RC 后没有新增 P1/P2 级功能/稳定性 review finding，README/配置文档/发布检查清单与代码一致，版本号从 `1.1.0-RC2` 切到 `1.1.0` 后完成全量 `mvn test` 或 CI 等效验证。
- 企业生产安全版另行设门槛：Dependency-Check、Dependabot、安全扩展、CORS/鉴权/TLS 策略不再阻塞当前开发计划，但也不应被误认为已经完成。

## Review finding 状态

- Finding 1 静态文件根目录逃逸：已通过 URL decode、canonical path 和根目录包含校验闭环，并补 plain/encoded traversal 回归测试。
- Finding 2 MVC HTTP 写失败缺少处理：已为 MVC 响应写入增加 `ChannelFuture` 监听，失败时记录 HTTP 写失败计数并关闭 channel。
- Finding 3 HTTP max content length 硬编码：已接入 `server.netty.http.max-content-length`，并补默认值、配置值和非法值回退测试。
- Finding 4 handler 默认线程/permit 过大：默认值已从 `CPU * 200/300` 收敛为保守 CPU 基线，并补默认值和非法配置校验。
- 当前这些旧 finding 不再是下一步开发阻塞；全量回归复验已完成，后续阻塞从安全门禁调整为 P5 产品能力和 P6 可观测能力。

## 下一步判断

下一步暂时不继续推进安全问题，不再把 Dependency-Check、Dependabot、CORS/鉴权/TLS 安全扩展作为当前阻塞。非安全发版基线复验已经完成，当前主线进入 `P5 WebSocket 产品能力增强`。

优先级建议：

1. `1.1.0-RC2` 已从 P4.1 全量回归通过点切出并发布；后续若要发正式 `1.1.0`，只做 RC 验证与最终版本号切换，不混入 P5。
2. P5 首批能力已完成并提交：query/header/session 访问抽象、text/json/binary 编解码、会话查询/单播/按 URI 广播和主动关闭 API。
3. 当前主线已进入 `1.2.1-SNAPSHOT`，P5.x 心跳/空闲断线和 AES-GCM 首版已随 `1.2.0` 发布；下一步集中收口 URI 粒度 crypto 策略和配套回归。
4. P5 基本稳定后进入 `P6`：Micrometer/Actuator 指标、关闭原因维度、日志与运行时诊断增强。

## 企业生产就绪度评估

结论：在“暂时不考虑安全问题”的前提下，`1.1.0-RC2` 已可用于功能/稳定性候选验证；`1.2.0` 已完成 P5/P5.x 正式版发布，当前主线继续推进 `1.2.1-SNAPSHOT` 的 crypto 可用性增强。

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
- 可观测性已从快照/API 层推进到轻量管理端点：已有 handler/http/sender runtime stats，且 handler/http 可通过内置 health/status 读取；后续还缺 Micrometer/Actuator 指标、拒绝/过载/写失败统一事件和更完整的关闭原因维度。
- 依赖与供应链治理暂时冻结：新增 `sbom` / `dependency-scan` Maven profile、Dependency-Check suppression 占位文件、处理规则、Netty BOM 版本对齐、`netty-all` 瘦身和 GitHub Actions 门禁已完成；Dependabot/Dependency-Check 漏洞 triage 暂不作为当前版本阻塞。
- Demo 仍是基础示例：demo 中仍有 `printStackTrace` 和极简 echo/send 用法，不足以作为企业接入、安全配置和运维排障示范。

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

### 代码里已经暴露出的下一阶段问题

- Starter 层虽然已经有最小集成测试，但覆盖面仍偏基础，后续自动配置收敛时还需要补更多装配/兼容场景。
- Starter 入口已经集中到公共 autoconfigure，`server.netty.http.*` 也已承接 HTTP/file/gzip/ssl 配置；`1.1.0` 功能正式版前仍建议通过 RC 阶段继续观察真实项目的配置兼容性。
- `MessageSender` 接口注入语义已经通过 demo 和 starter 回归测试固化，后续重点转为自定义 Bean、开关禁用、无 websocket mapping 等 starter 组合场景的验收。
- WebSocket API 仍偏底层，业务侧主要围绕 `HttpRequest`、`WebSocketFrame`、`MessageSession` 直接编程，还缺少更高层的鉴权、编解码和会话访问抽象。
- 可观测性已具备运行时快照和内置 health/status 管理端点，但还没有 Micrometer/Actuator 指标、统一事件模型和更完整的关闭原因维度。
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
- P5.x 第三刀正在 `1.2.1-SNAPSHOT` 推进：新增 `crypto.include-uris` / `crypto.exclude-uris`，支持按 WebSocket path、原始 URI、mapping URL 或 `*` 控制 session 是否启用应用层 crypto，便于灰度兼容公开 echo、调试通道和旧客户端。

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
- 支持按 URI 或按 session 选择是否加密，避免和公开 echo、健康检查、调试通道混用；`1.2.1-SNAPSHOT` 先落地 URI include/exclude 策略，session 粒度策略保留为后续增强。
- demo 增加浏览器端加密/解密示例，展示 Network 面板看到密文、业务回调拿到明文对象的完整流程。
- 回归测试覆盖加密发送、解密接收、错误密钥/篡改 tag 失败、未启用时保持兼容、按 URI 开关和密钥轮换基础路径。

完成标准：

- 加密能力默认关闭，不破坏当前明文 WebSocket 使用方式。
- 启用后，服务端 handler 仍可使用 `String` / JSON POJO / `byte[]` 等高层签名，不需要业务侧手写解密逻辑。
- 密钥不硬编码在框架内，至少提供可替换的 key provider 入口。
- 文档明确说明它是应用层混淆/加密能力，不是 TLS 或完整端到端安全方案的替代品。

### P6 可观测与运维能力

目标：让运行中的 Netty/WebSocket 服务可观测、可诊断、可运维。

重点项：

- 暴露连接数、消息收发数、失败数、广播耗时、线程池状态等指标。
- 在已有内置 health/status 管理端点基础上，继续补 Micrometer/Actuator 对接和更标准的指标命名。
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

1. 先做 `P3`，优先治理 Starter 启动失败行为和补齐 Starter 集成测试。
2. 再做 `P4`，收敛配置模型和自动配置结构，降低后续演进成本。
3. 在 `P4` 后追加 `P4.1` 稳定性与发布门禁硬化，先处理容量、失败路径、观测入口、CI/SBOM 和依赖版本一致性。
4. 暂停安全门禁后，优先推进 `P5`，补齐真正面向业务的 WebSocket 能力。
5. 在产品能力基本稳定后推进 `P6`，完善指标和运维能力。
6. 最后集中完成 `P7`，把 demo 和文档体系补齐。

## 版本节奏建议

- `1.0.0`：已发布基线，对应 `P0/P1/P2` 收口结果。
- `1.0.1`：`P3.1`，先修 Starter 启动失败传播、补 Starter 最小集成测试和 demo smoke test。
- `1.0.2`：已发布，完成 `P3.2` 的发布清单、异常 stop/startup failure 清理回归和 `1.0.x` 维护基线。
- `1.1.0-RC2`：已从 P4/P4.1 稳定线发布，包含 resolver 延迟取 bean、`MessageSenderSupport` 构造注入去 `@Lazy`、`netty-spring-boot-autoconfigure` 共用模块抽取、MVC/WebSocket enable 开关接线、`MessageSender` 接口注入语义固化，以及 `server.netty.http.*` 子命名空间引入并兼容旧顶层配置键。
- `1.1.0-RC1`：历史候选版本，仅用于承接 P4 已完成内容的预发布验收；定位为受控环境验证，不作为企业生产默认部署版本。
- `1.1.0-RC2`：建议用于承接 `P4.1` 首批稳定性硬化后的候选验证，重点验证容量、失败路径、轻量管理端点、SBOM、Netty 依赖一致性和 CI 基础链路；暂不要求安全扫描/漏洞 triage 闭环。
- `1.1.0`：目标功能正式版本，完成 Starter 收敛、配置模型统一和稳定性硬化。这一阶段触及配置入口、自动配置结构、兼容模型和发布门禁，适合进入新的 minor 版本。
- `1.2.0`：已发布的正式版本，`P5` 首批 WebSocket 产品能力增强已落地，心跳/空闲断线第一刀已完成，应用层消息加密扩展点和 AES-GCM 首版实现已完成。
- `1.2.1-SNAPSHOT`：当前开发版本，承接 URI 粒度 crypto include/exclude 策略与对应配置文档/回归测试；满足发布门槛后再切 `1.2.1` tag。
- `1.2.x`：`P5.x` 继续补齐高级产品能力，优先承接握手鉴权扩展、session 粒度 crypto 策略、密钥轮换示例和浏览器端加密 demo。
- `1.3.0`：`P6`，可观测与运维能力建设。
- `1.3.x`：`P7`，demo 和文档体系持续补齐，跟随能力版本滚动完善，而不是等到最后一次性补文档。

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
