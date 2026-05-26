# 1.2.3 发布说明

`1.2.3` 是生产就绪代码质量版本，集中修复了 10 项影响生产稳定性、线程安全和 Spring Boot 集成规范性的核心缺陷。

## 主要变化

### P0 级修复（可能导致生产事故）

- **BinaryMessage ByteBuf 生命周期**：改为内部持有 `byte[]`，消除跨线程广播时的引用计数安全隐患。
- **MessageMappingResolver session 创建死循环**：sessionId 冲突时改为有限重试（最多 5 次），避免栈溢出。
- **ServiceHandler 消息泄漏**：线程池拒绝任务时正确 release 已 retain 的消息，避免堆外内存泄漏。

### P1 级修复（影响功能正确性）

- **心跳检测逻辑**：同时检查 `lastReadTimeMillis` 和 `lastPongTimeMillis`，确保 pong 超时能被正确检测。
- **MessageSession URI 缓存**：构造时一次性解析 path 和 queryParams，避免重复解析开销。
- **DefaultMessageSender.isSessionAlive**：传入 null 或空数组时返回 false，而不是异常。

### P2 级修复（代码质量与规范性）

- **TextMessage/JsonMessage volatile**：content 和 objectMapper 字段添加 volatile，保证跨线程可见性。
- **废弃 API 替换**：所有模块统一使用 `DefaultParameterNameDiscoverer` 替换废弃的 `LocalVariableTableParameterNameDiscoverer`。
- **字段拼写修正**：`webSockeMappingtResolverMap` → `webSocketMappingResolverMap`。
- **Spring Boot 自动配置规范化**：改用标准 `@EnableConfigurationProperties` 模式，移除 `spring.factories` 中的 `@ConfigurationProperties` 类直接注册。

## 兼容性

- 所有修复为内部实现变更，不涉及公共 API 签名变化，保持完全向后兼容。
- `BinaryMessage` 新增 `BinaryMessage(byte[])` 构造器，原有 `BinaryMessage(ByteBuf)` 构造器保留但内部改为 copy 数据。

## 验证

- 全量 reactor `mvn verify` 通过（9 个模块、约 138 个测试）。

## 后续方向

下一版本 `1.3.0` 进入 P6 可观测性阶段：Micrometer/Actuator 指标接入、关闭原因维度化、握手鉴权扩展点和 demo 聊天室升级。
