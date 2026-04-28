# 版本发布检查清单

更新时间：2026-04-28

## 适用范围

- 适用于 `1.0.x` 维护版本、`1.1.x` RC/正式版本、`1.2.x` WebSocket 产品能力版本发布。
- 目标是保证发布动作可重复执行，而不是依赖一次性的人工记忆。
- 当前计划暂时不把安全扫描和漏洞 triage 作为功能/稳定性版本发布阻塞项；企业安全发布另设更高门槛。

## 版本类型

- 功能/稳定性发布：面向 `1.1.0-RC2`、`1.1.0` 和 `1.2.x`，重点确认全量测试、Starter 兼容、WebSocket 产品能力回归、配置文档、SBOM 生成和基础 CI 链路。
- 企业安全发布：面向后续安全加固版本，除功能/稳定性发布要求外，还必须完成 Dependency-Check、Dependabot triage、CORS/鉴权/TLS 策略等安全项。

## `1.2.1` 发布口径

`1.2.1` 定位为 WebSocket P5.x 功能/稳定性正式版。本次可以发布的前提是：

- 根版本从 `1.2.1-SNAPSHOT` 切到 `1.2.1` 后，全量 reactor `mvn test` 通过。
- README、开发计划、WebSocket 配置文档、Netty 配置文档和本清单均已说明 `1.2.1` 的功能边界与延期项。
- 已补 `docs/release-notes-1.2.1.md`，明确本次新增能力、验证方式和已知延期。
- URI/session 粒度 crypto 策略、AES-GCM 密钥轮换基础路径、浏览器端 crypto demo 和 WebSocket runtime stats 均已有测试或文档验收入口。
- 工作树不包含 `.m2/`、`target/`、本地缓存或实验文件。

以下内容不阻塞 `1.2.1` 功能/稳定性发布，但必须保留在后续计划中：

- Dependency-Check / Dependabot 漏洞 triage。
- 标准握手鉴权扩展、完整 CORS/TLS 策略和企业级密钥分发方案。
- Micrometer/Actuator 指标、关闭原因维度和完整 demo 体系重写。

## 发布前

1. 确认本次版本类型：`1.0.x` 只承接维护修复；`1.1.x` 承接 P4 Starter/配置模型收敛；`1.2.x` 承接 P5/P5.x WebSocket 产品能力。
2. 更新版本号、README 和 [开发计划](development-plan.md) 中的当前阶段说明。
3. 运行全量 `mvn test`。
   生成 SBOM：`mvn -Psbom -DskipTests org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom`。
   如果通过 GitHub Actions 发布功能/稳定性版本，必须确认 `CI` workflow 的 `Maven Test` 和 `Generate SBOM` job 成功。
   企业安全发布额外运行依赖漏洞扫描：`mvn -Pdependency-scan org.owasp:dependency-check-maven:12.2.1:aggregate`，并确认 `Dependency Scan` job 成功。
4. 额外确认 starter/demo 相关回归：
   `netty-web-spring-boot-starter`
   `netty-webmvc-spring-boot-starter`
   `netty-websocket-spring-boot-starter`
   `demo-netty-web-spring-boot-starter`
5. 检查关键工程化边界：
   Starter 启动失败会向 Spring Boot 抛异常，而不是终止 JVM。
   `NettyServerBootstrap.stop()` 在 repeated stop、异常 stop、startup failure 后都能完成资源清理。
   `MessageSenderSupport` 在无 websocket mapping、stop/start、停机联动时行为可预测。
6. `1.1.x` RC/正式版本还需要确认 P4 边界：
   三个 Starter 复用 `netty-spring-boot-autoconfigure`，不再维护重复的同包同名自动配置类。
   `server.netty.mvc.enable` / `server.netty.websocket.enable` 与真实 mapping 初始化、sender Bean 装配一致。
   `server.netty.http.*` 新命名空间可用，旧的 HTTP/file/gzip/ssl 顶层配置键继续兼容。
   WebSocket controller 可直接构造注入 `MessageSender` 或 `MessageSenderSupport`，不需要显式 `@Lazy`。
7. 当前功能/稳定性版本还需要确认 P4.1 稳定性门槛：
   静态文件服务启用时不能通过 `..`、URL 编码或路径规范化绕过根目录。
   HTTP request line、header、chunk、body、超时、TLS 证书、TLS 协议、TLS cipher suite 等生产关键参数有配置入口和回归测试。
   `read-timeout-seconds` / `write-timeout-seconds` / `idle-timeout-seconds` 在生产环境有明确配置，不能让异常连接长期占用资源。
   启用 SSL 时证书和私钥文件必须在启动期通过校验，不能把缺失证书留到运行期失败；生产环境需要评估是否显式配置 `http.ssl.protocols` 和 `http.ssl.ciphers`。
   WebSocket 生产部署需要显式评估 `allowed-origins`，避免默认兼容模式误放跨站握手。
   MVC 响应、静态文件发送、WebSocket 写失败和过载拒绝有统一日志、关闭策略、计数或指标；至少确认 `getHandlerRuntimeStats()`、`getHttpRuntimeStats()`、`getWebSocketRuntimeStats()`、MessageSender runtime stats 和内置 health/status 管理端点的关键计数可读。
   如启用 `server.netty.management.enable=true`，必须确认管理端点只在受保护网络、网关或等效访问控制下暴露。
   handler/sender 线程池配置有启动期校验，非法容量和 `max < core` 不会静默兜底。
   README、配置文档和 demo 明确安全接入方式，不把 RC 候选描述为企业生产默认部署版本。
8. `1.2.x` WebSocket 产品能力版本还需要确认：
   P5 会话查询、单播、按 URI 广播、主动关闭、payload 绑定、JSON 发送、心跳/空闲断线和 crypto 链路均有回归测试。
   启用 `server.netty.websocket.crypto.algorithm=AES-GCM` 时，必须提供 `MessageCryptoKeyProvider`，且 `reject-unencrypted` 默认拒绝对应 text/binary 明文数据帧。
   如配置 `crypto.include-uris` / `crypto.exclude-uris`，必须确认发送加密、接收解密和未加密帧拒绝策略都按 URI 策略一致生效。
   如提供 `MessageCryptoPolicy`，必须确认入站解密、出站加密和未加密帧拒绝都按 session 策略一致生效。
   如进行 AES-GCM 密钥轮换，必须确认新 `key-id` 用于新消息加密，同时 `MessageCryptoKeyProvider` 在过渡期仍能解析旧 `kid` 的历史密文。
   demo `/ws/crypto-demo` 页面至少完成 smoke 验证，确保浏览器端 WebCrypto envelope 与服务端 AES-GCM envelope 格式一致。
   文档明确应用层 crypto 不替代 TLS/WSS，也不承诺浏览器运行时完全不可见明文。
9. `1.2.1` 正式发布前额外确认：
   `docs/release-notes-1.2.1.md` 已同步本次功能边界。
   `README.md` 不再把已完成能力描述为未完成计划。
   `docs/development-plan.md` 明确后续进入 P6/P7，而不是继续扩大 `1.2.1` 范围。
   根 `pom.xml` 版本号已切到 `1.2.1`，并在切换后重新跑全量测试。

## 企业安全发布附加项

1. 发布前完成依赖漏洞扫描，处理或记录 GitHub Dependabot/等效扫描告警；高危及以上漏洞或未 triage 的扫描失败不得进入企业安全发布 tag。
2. Dependency-Check 误报必须写入 `dependency-check-suppressions.xml` 并说明原因，不能通过降低扫描门槛绕过。
3. CI 或发布机必须为企业安全发布门禁配置 `NVD_API_KEY`，并复用 `${settings.localRepository}/../dependency-check-data` Dependency-Check 缓存，避免首次漏洞库初始化影响发布判断。
4. 握手鉴权、CORS/Origin、TLS 协议/套件和管理端点暴露策略需要在发布说明中明确。

## 发布动作

1. 工作树只保留本次发布相关文件，不带入 `.m2/`、本地缓存和实验文件。
2. 创建发布提交。
3. 创建对应 tag，例如 `v1.0.2` 或 `v1.1.0-RC1`。
4. 推送分支和 tag。

## 发布后

1. 将开发线切到下一个 `-SNAPSHOT` 版本。
2. 更新 [开发计划](development-plan.md) 的当前状态和下一阶段目标。
3. 如果本次补了新的稳定性边界，同步补进本清单，避免后续回归入口再次依赖口头约定。
