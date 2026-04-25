# 版本发布检查清单

更新时间：2026-04-26

## 适用范围

- 适用于 `1.0.x` 维护版本、`1.1.x` RC/正式版本发布。
- 目标是保证发布动作可重复执行，而不是依赖一次性的人工记忆。

## 发布前

1. 确认本次版本类型：`1.0.x` 只承接维护修复；`1.1.x` 承接 P4 Starter/配置模型收敛；P5+ 产品能力不混入 `1.1.0-RC1`。
2. 更新版本号、README 和 [开发计划](development-plan.md) 中的当前阶段说明。
3. 运行全量 `mvn test`。
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
7. `1.1.0` 正式版还需要确认 P4.1 生产准入门槛：
   静态文件服务启用时不能通过 `..`、URL 编码或路径规范化绕过根目录。
   HTTP request line、header、chunk、body、超时、TLS 证书、TLS 协议、TLS cipher suite 等生产关键参数有配置入口和回归测试。
   `read-timeout-seconds` / `write-timeout-seconds` / `idle-timeout-seconds` 在生产环境有明确配置，不能让异常连接长期占用资源。
   启用 SSL 时证书和私钥文件必须在启动期通过校验，不能把缺失证书留到运行期失败；生产环境需要评估是否显式配置 `http.ssl.protocols` 和 `http.ssl.ciphers`。
   WebSocket 生产部署需要显式评估 `allowed-origins`，避免默认兼容模式误放跨站握手。
   MVC 响应、静态文件发送、WebSocket 写失败和过载拒绝有统一日志、关闭策略、计数或指标；至少确认 `getHandlerRuntimeStats()`、`getHttpRuntimeStats()` 和 MessageSender runtime stats 的关键计数可读。
   handler/sender 线程池配置有启动期校验，非法容量和 `max < core` 不会静默兜底。
   发布前完成依赖漏洞扫描，处理或记录 GitHub Dependabot/等效扫描告警。
   README、配置文档和 demo 明确安全接入方式，不把 RC 候选描述为企业生产默认部署版本。

## 发布动作

1. 工作树只保留本次发布相关文件，不带入 `.m2/`、本地缓存和实验文件。
2. 创建发布提交。
3. 创建对应 tag，例如 `v1.0.2` 或 `v1.1.0-RC1`。
4. 推送分支和 tag。

## 发布后

1. 将开发线切到下一个 `-SNAPSHOT` 版本。
2. 更新 [开发计划](development-plan.md) 的当前状态和下一阶段目标。
3. 如果本次补了新的稳定性边界，同步补进本清单，避免后续回归入口再次依赖口头约定。
