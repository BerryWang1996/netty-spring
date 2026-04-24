# 1.0.x 发布检查清单

更新时间：2026-04-24

## 适用范围

- 适用于 `1.0.x` 维护版本发布。
- 目标是保证发布动作可重复执行，而不是依赖一次性的人工记忆。

## 发布前

1. 确认本次变更仍属于 `1.0.x` 维护范围，不混入 `P4/P5` 级别的结构性改动。
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

## 发布动作

1. 工作树只保留本次发布相关文件，不带入 `.m2/`、本地缓存和实验文件。
2. 创建发布提交。
3. 创建对应 tag，例如 `v1.0.2`。
4. 推送分支和 tag。

## 发布后

1. 将开发线切到下一个 `-SNAPSHOT` 版本。
2. 更新 [开发计划](development-plan.md) 的当前状态和下一阶段目标。
3. 如果本次补了新的稳定性边界，同步补进本清单，避免后续回归入口再次依赖口头约定。
