# netty-spring

基于 Netty 的 Spring Web/WebMVC/WebSocket 集成项目，包含核心运行时模块和对应的 Spring Boot Starter。

## 模块概览

- `netty-spring-web`：Netty 启动、通道初始化、HTTP 请求分发等基础能力。
- `netty-spring-webmvc`：MVC 映射支持。
- `netty-spring-websocket`：WebSocket 映射、会话管理、消息发送与生命周期处理。
- `netty-spring-boot-autoconfigure`：Starter 共用的 Netty 启动与配置绑定自动装配骨架。
- `netty-web-spring-boot-starter`：Web 基础 Starter。
- `netty-webmvc-spring-boot-starter`：WebMVC Starter。
- `netty-websocket-spring-boot-starter`：WebSocket Starter。
- `demo-netty-web-spring-boot-starter`：示例工程。

## 当前阶段

- `P0` 工程基线：已完成。
- `P1` WebSocket 正确性修复：已完成。
- `P2` WebSocket 并发与稳定性：已完成，核心限流、线程池配置化、广播背压、错误链路统一、运行时统计、停机时 active session 优雅关闭、重复 start/stop 收口、广播/停机交叉回归、MessageSenderSupport 重启后缓存刷新与停机联动关闭已落地。
- 当前稳定版本仍是 `1.0.2`，开发线已切到 `1.1.0-SNAPSHOT`；当前代码已通过全量测试，适合作为 `1.1.0-RC1` 预发布候选，但尚不建议作为企业生产环境默认部署版本。
- `P4` 已完成主要目标：mapping resolver 延迟获取 controller bean，移除业务侧对 `@Lazy MessageSenderSupport` 的依赖；抽出共用 `netty-spring-boot-autoconfigure` 模块，收敛三套 Starter 里重复的 `nettyServer + properties` 自动装配骨架；把 `MessageSenderSupport` 自动配置并回公共 autoconfigure，同时补上 `server.netty.mvc.enable` / `server.netty.websocket.enable` 开关，明确 starter 场景优先按 `MessageSender` 接口注入，并把 HTTP/file/gzip/ssl 配置收敛到 `server.netty.http.*` 且保留旧键兼容。
- `P4.1` 已继续推进生产准入硬化：静态文件根目录逃逸保护、HTTP 聚合/解码/超时边界配置化、TLS 证书/协议/套件配置、WebSocket Origin 白名单、MVC/静态文件写失败关闭、HTTP 失败路径运行时统计、内置 health/status 管理端点、更保守的 handler 默认线程/permit，以及 handler/sender 线程池配置校验。

## 文档

- [开发计划与阶段状态](docs/development-plan.md)
- [版本发布检查清单](docs/release-checklist.md)
- [依赖治理与供应链门禁](docs/dependency-governance.md)
- [Netty 配置说明](docs/netty-configuration.md)
- [WebSocket 配置说明](docs/websocket-configuration.md)

## 验证

- 推荐使用 Maven Wrapper：`./mvnw test`
- 当前阶段已在 `GraalVM JDK 17.0.11` + `Apache Maven 3.9.9` 环境完成全量 `mvn test` 验证
- SBOM 与漏洞扫描使用显式 profile：`mvn -Psbom -DskipTests package org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom`、`mvn -Pdependency-scan org.owasp:dependency-check-maven:12.2.1:aggregate`

## 当前配置重点

- `server.netty.mvc.enable=false`：关闭 MVC mapping 装配。
- `server.netty.websocket.enable=false`：关闭 WebSocket mapping 和 `MessageSenderSupport` 自动装配。
- `server.netty.http.*`：新的 HTTP/file/gzip/ssl 配置子命名空间；旧的 `server.netty.gzip.*`、`server.netty.file-location` 等写法继续兼容。
- `server.netty.management.enable=true`：显式开启内置 health/status 管理端点，默认关闭。
- Spring Boot Starter 场景下，推荐业务侧构造注入 `MessageSender`，`MessageSenderSupport` 保留为兼容实现；用户自定义 `MessageSender` Bean 时默认 sender 会自动退让。
