# 开发计划与阶段状态

更新时间：2026-04-24

## 当前结论

- `P0` 已完成，工程基线已经建立，项目具备一键测试能力。
- `P1` 已完成，WebSocket 主链路正确性问题已经收口。
- `P2` 正在推进，工作重点已经从“修正基本正确性”转向“并发治理、过载行为和停机稳定性”。

## 已完成范围

### P0 工程基线

- 已补齐 Maven Wrapper。
- 已统一 Java、Spring Boot 和核心依赖版本。
- 已建立最小回归测试框架。
- 已验证项目可以完成全量 `mvn test`。

### P1 WebSocket 正确性

- 修复了握手失败后仍继续创建 session 的问题。
- 修复了握手成功后过早发布 session 的问题，改为握手成功后再发布。
- 修复了 `ON_CLOSE` 和 `ON_ERROR` 生命周期链路，确保关闭和异常能走统一清理流程。
- 修复了写失败场景提前移除 session 导致生命周期被绕过的问题。
- 修复了 `submitHandle()` permit 生命周期过早释放的问题。
- 已补齐 `MessageMappingResolver`、`DefaultMessageSender` 等核心回归测试。

### P2 已落地项

- 已将 handler 与 message sender 线程池参数配置化。
- 已为 handler 执行链引入有界执行模型，避免在业务线程池外直接执行用户回调。
- 已将握手成功回调、写失败关闭、`channelInactive` 关闭等生命周期统一调度到应用侧执行模型。
- 已将 handler 准入改为 fail-fast，避免 semaphore 阻塞 Netty event loop。
- 已支持最大连接数限制。
- 已支持最大 WebSocket 帧大小限制。
- 已支持消息发送线程池队列容量限制。
- 已支持广播时对不可写 channel 的策略化处理。
- 已支持广播任务被线程池拒绝时的策略化处理。
- 已增加 handler/message sender 的运行时快照对象与饱和计数器，可观测 permit、queue、active count 和 reject 行为。
- 已修复无 `@MessageMapping` 场景下 `MessageSenderSupport` 的空指针问题，改为安全空实现。
- 已补充 `NettyServerBootstrap.stop()` 的资源关闭逻辑，包括 server channel、handler 线程池、event loop group 和自建 Spring 上下文。
- 已新增启动/停止和关键错误路径的单元测试。
- 已在当前本地环境完成全量 `mvn test` 验证。

## P2 剩余工作

### P2-1 过载可观测性

- 已补充统一的运行时快照和饱和诊断日志。
- 下一步评估是否增加轻量级指标导出，而不只是日志和快照读取。
- 继续细化不同过载路径的行为文档和示例，避免“配置已存在但行为不可见”。

### P2-2 停机与会话收口

- 在 `NettyServerBootstrap.stop()` 场景下，进一步评估 active websocket session 的优雅关闭策略。
- 明确停机时 `ON_ERROR`、`ON_CLOSE`、session 清理之间的顺序与幂等性。
- 补充“服务停止时仍有活跃连接”的回归测试。

### P2-3 压测与回归补强

- 增加针对背压、拒绝策略、连接上限、帧大小上限的回归测试。
- 增加 repeated start/stop、资源重复关闭、并发广播等稳定性测试。
- 审查是否仍存在其他无界并发、无界缓存或在 event loop 上执行用户代码的遗漏点。

## 下一轮开发顺序

1. 先完成线程池饱和与背压的可观测性补强，让过载行为可诊断、可验收。
2. 再完善停机时的 websocket session 优雅关闭与资源回收。
3. 然后补齐 P2 稳定性回归测试，形成可持续验收基线。
4. P2 关单后，再进入 P3 产品能力扩展。

## P2 关单标准

- 过载时不会阻塞 Netty event loop。
- 用户生命周期回调不会逃逸到 I/O 完成线程直接执行。
- 连接数、帧大小、线程池队列和广播过载策略均可配置且文档明确。
- 启动、停止、重复关闭等关键生命周期具备可预测且幂等的行为。
- 全量 `mvn test` 持续通过，且关键并发/失败场景有专门回归用例覆盖。

## 后续阶段规划

### P3 WebSocket 产品能力

- 握手鉴权扩展点。
- query/header/session 信息读取。
- text/json/binary 消息编解码。
- 心跳、空闲超时、断线清理。
- 按 URI 广播、单播、会话查询 API。

### P4 Starter 重构

- 继续整理 `netty-websocket-spring-boot-starter` 的配置入口。
- 暴露更稳定的 `MessageSender` Bean 使用方式。
- 支持开启/关闭 HTTP MVC 映射。
- 避免多个 starter 中自动配置类互相干扰。

### P5 可观测与示例

- 增加连接数、消息收发、失败数、广播耗时、线程池状态指标。
- 提供完整 demo，例如聊天室或推送服务。
- 以 WebSocket 快速接入为核心重写 README/示例文档。

## 说明

- 当前计划以仓库中的最新代码状态为准，不再按早期 review 数量统计阶段结论。
- P2 当前不是“从零开始”，而是在已有稳定性底座上继续做观测、停机治理和压力回归收口。
