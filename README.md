# netty-spring

基于 Netty 的 Spring Web/WebMVC/WebSocket 集成项目，包含核心运行时模块和对应的 Spring Boot Starter。

## 模块概览

- `netty-spring-web`：Netty 启动、通道初始化、HTTP 请求分发等基础能力。
- `netty-spring-webmvc`：MVC 映射支持。
- `netty-spring-websocket`：WebSocket 映射、会话管理、消息发送与生命周期处理。
- `netty-web-spring-boot-starter`：Web 基础 Starter。
- `netty-webmvc-spring-boot-starter`：WebMVC Starter。
- `netty-websocket-spring-boot-starter`：WebSocket Starter。
- `demo-netty-web-spring-boot-starter`：示例工程。

## 当前阶段

- `P0` 工程基线：已完成。
- `P1` WebSocket 正确性修复：已完成。
- `P2` WebSocket 并发与稳定性：已完成，核心限流、线程池配置化、广播背压、错误链路统一、运行时统计、停机时 active session 优雅关闭、重复 start/stop 收口、广播/停机交叉回归、MessageSenderSupport 重启后缓存刷新与停机联动关闭已落地。
- 当前仓库已完成 `1.0.0` 发布基线收口，后续迭代进入 `P3/P4/P5` 产品能力、Starter 重构和可观测性增强阶段。

## 文档

- [开发计划与阶段状态](docs/development-plan.md)
- [WebSocket 配置说明](docs/websocket-configuration.md)

## 验证

- 推荐使用 Maven Wrapper：`./mvnw test`
- 当前阶段已在 `GraalVM JDK 17.0.11` + `Apache Maven 3.9.9` 环境完成全量 `mvn test` 验证
