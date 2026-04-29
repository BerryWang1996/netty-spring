# 1.2.2 发布说明

`1.2.2` 是 WebSocket 用户体验与开源接入体验增强版本，目标是让新用户更快跑起 demo、选对 starter、写出常见发送逻辑，并在出错时看到可执行的下一步建议。

## 主要变化

- README 增加 5 分钟快速开始、starter 选择表、最小配置、最小 WebSocket controller、demo 启动命令和常见排障速查。
- demo 新增统一首页入口，串起 HTTP、WebSocket text/json/binary、crypto demo、health/status 等可体验能力。
- demo 新增 `crypto-demo` Spring profile，启用应用层 AES-GCM 演示时不再需要手工取消 properties 注释。
- `MessageSender` 新增 `sendText()`、`sendTextToSession()`、`broadcastText()`、`sendJson()`、`sendJsonToSession()`、`broadcastJson()` 便利方法。
- 常见错误提示补齐原因和建议动作，覆盖未注册 URI、空 websocket mapping、session 过期、Origin 拒绝、连接数上限、handler 过载、JSON 反序列化失败、crypto key provider 配置错误和明文帧被拒绝。
- WebSocket 配置文档新增常见错误与排障表，便于从日志或异常反查配置项。

## 兼容性

- 不移除既有 `sendMessage()` / `topicMessage()` API，新增便利方法通过接口默认方法提供，保持二进制兼容。
- `TextMessage` / `JsonMessage` 仍可直接使用；demo 和文档优先展示更直观的便利方法。
- 应用层 crypto 仍默认关闭，`crypto-demo` profile 只用于 demo 联调。

## 验证

- `mvn -Dmaven.repo.local=C:\Users\qq951\IdeaProjects\netty-spring\.m2\repository -B -ntp -pl netty-spring-websocket -am test`
- `mvn -Dmaven.repo.local=C:\Users\qq951\IdeaProjects\netty-spring\.m2\repository -B -ntp -pl demo-netty-web-spring-boot-starter -am test`

## 后续方向

下一阶段建议进入 `1.2.3-SNAPSHOT`，优先推进 P6/P7：Micrometer/Actuator 指标、关闭原因维度、demo 场景化示例、文档体系拆分，以及更完整的运行时诊断。
