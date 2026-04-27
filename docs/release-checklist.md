# 版本发布检查清单

更新时间：2026-04-27

## 适用范围

- 适用于 `1.0.x` 维护版本、`1.1.x` RC/正式版本发布。
- 目标是保证发布动作可重复执行，而不是依赖一次性的人工记忆。
- 当前计划暂时不把安全扫描和漏洞 triage 作为功能/稳定性版本发布阻塞项；企业安全发布另设更高门槛。

## 版本类型

- 功能/稳定性发布：面向 `1.1.0-RC2` 和当前口径下的 `1.1.0`，重点确认全量测试、Starter 兼容、配置文档、SBOM 生成和基础 CI 链路。
- 企业安全发布：面向后续安全加固版本，除功能/稳定性发布要求外，还必须完成 Dependency-Check、Dependabot triage、CORS/鉴权/TLS 策略等安全项。

## 发布前

1. 确认本次版本类型：`1.0.x` 只承接维护修复；`1.1.x` 承接 P4 Starter/配置模型收敛；P5+ 产品能力不混入 `1.1.0-RC1`。
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
   MVC 响应、静态文件发送、WebSocket 写失败和过载拒绝有统一日志、关闭策略、计数或指标；至少确认 `getHandlerRuntimeStats()`、`getHttpRuntimeStats()`、MessageSender runtime stats 和内置 health/status 管理端点的关键计数可读。
   如启用 `server.netty.management.enable=true`，必须确认管理端点只在受保护网络、网关或等效访问控制下暴露。
   handler/sender 线程池配置有启动期校验，非法容量和 `max < core` 不会静默兜底。
   README、配置文档和 demo 明确安全接入方式，不把 RC 候选描述为企业生产默认部署版本。

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
