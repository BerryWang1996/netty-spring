# Release Notes — v1.7.0

> 发布日期：2026-05-29

## 版本定位

v1.7.0 是**可观测性增强 + 遗留缺陷深度修复 + WebSocket 分片消息支持**的 minor 版本，补齐 v1.6 roadmap Phase 2 可观测性体系，并修复 v1.6.2 审计报告中"已确认但未修复"的 6 个遗留项。全部改动向后兼容，无破坏性 API 变更。

按"四刀"推进，发布前另经过 4 轮代码审计 + 1 轮对抗式验证，修复了审计发现的 1 个正确性缺陷和若干健壮性问题。

## 第一刀：遗留缺陷修复（6 项）

| # | 模块 | 描述 |
|---|------|------|
| 1 | MessageMappingResolver / WebMappingSupporter | `shutdown()` 等待已提交的 onClose 生命周期任务完成（带超时），避免 `@MessageMapping(ON_CLOSE)` 回调被线程池提前关闭吞掉 |
| 2 | DataBindUtil | `setObjectProperties` 仅匹配 `keys.getFirst()` 并以 `keys.size() == 1` 判定叶子节点，修复根/嵌套同名属性的错误绑定 |
| 3 | MessageMappingResolver | `closeSession` 将 `startClosing()` CAS 提前为唯一入口，消除 `isActive()` 与 CAS 之间的 TOCTOU 竞态 |
| 4 | MessageMappingResolver | WebSocket 握手对 Host 头进行 CRLF/空白字符校验，防止缓存投毒 |
| 5 | RequestMappingResolver | `ResponseEntity` 头部应用复用 `sanitizeHeaderValue`，过滤 CRLF/NUL，防止 HTTP 头注入 |
| 6 | ServiceHandlerUtil | `MimetypesFileTypeMap` 由 `public static` 可变字段改为 `private` + 只读访问方法 |

## 第二刀：Micrometer 指标扩展

在现有 `NettyWebSocketMeterBinder` / `NettyHttpMeterBinder` 基础上扩展，全部为 optional 依赖，无 Micrometer 时自动退化为内置 runtime stats。

WebSocket 指标：

- `netty.websocket.sessions.active`（Gauge）与 `netty.websocket.sessions.active.uri`（按 `uri` 标签分类 Gauge）
- `netty.websocket.connection.duration`（Timer，按 `reason` 标签分类）
- `netty.websocket.message.size`（DistributionSummary，单位 bytes）
- `netty.websocket.broadcast.fanout`（DistributionSummary，每次广播投递的会话数）
- `netty.websocket.handler.latency`（Timer，handler 方法执行耗时）
- 既有 handshake/message/close 计数器保持不变

HTTP / 运行时指标：

- handler 线程池利用率、活跃线程数、队列深度、许可数等 Gauge（来自 `HandlerRuntimeStats` / `ExecutorRuntimeInfo`）
- Netty `PooledByteBufAllocator` 堆内/堆外内存使用 Gauge

push 模型指标（duration / size / fanout / latency）通过 `WebSocketMetricsCallback` 回调桥接到 Micrometer，使 `netty-spring-websocket` 模块不直接依赖 Micrometer。

## 第三刀：结构化日志与健康检查

- **MDC 集成**：新增 `MdcUtil`，在 handler 入口注入 `netty.requestId`（HTTP）/ `netty.sessionId`（WebSocket）/ `netty.uri` / `netty.remoteAddr`，并在 `finally` 中清理；WebSocket 生命周期回调（onConnected/onClose/心跳/错误）在 handler 线程池派发时同样携带会话 MDC 上下文。
- **健康检查**：新增 `NettyServerHealthIndicator`（Spring Boot Actuator），在 `/actuator/health` 暴露端口、线程池大小/活跃/队列、连接许可等运行时状态；`spring-boot-actuator` 为 optional 依赖，缺失时自动跳过。

## 第四刀：WebSocket 分片消息支持

- 新增配置 `server.netty.websocket.max-frame-aggregation-buffer-size`，**默认 0（禁用）以保持向后兼容**；为正值时在握手成功后向 pipeline 动态插入 Netty 内置 `WebSocketFrameAggregator`，将分片 frame 自动组装为完整的 `TextWebSocketFrame` / `BinaryWebSocketFrame`。
- 未启用聚合时，`ContinuationWebSocketFrame` 仍记录警告日志后丢弃，警告文案改为引导启用该配置。

## 发布前审计修复

发布前对全部 v1.7.0 改动进行了 4 路并行代码审计与 1 轮对抗式验证，修复以下问题（全部向后兼容）：

- **（正确性）多 MeterRegistry 路由**：分布式指标（duration/size/fanout/latency）此前仅写入最后绑定的一个 registry。现改为每个 registry 各注册一份回调，并按 registry 身份去重，避免漏写或重复计数。
- 连接时长 Timer 在绑定期按 `CloseReason` 预创建，消除每次关闭的构建分配。
- 分布式记录方法遵循 `enabled` 开关，noop recorder 不再触发回调。
- `WebSocketFrameAggregator` 插入用 try/catch 兜底，pipeline 异常不再跳过会话注册、泄漏连接许可。
- `NettyServerBootstrap.getPort()` 对 `Integer` 进行空安全拆箱（未配置端口返回 -1，不再 NPE）。

## 新增测试

- `MessageMappingResolverTest`：分片聚合（启用/禁用/缓冲区大小校验/Continuation 丢弃）、生命周期回调 MDC 上下文
- `NettyWebSocketMeterBinderTest`：分布式指标回调、按 URI 活跃会话、多 registry 路由、同 registry 重复绑定不重复计数
- `NettyHttpMeterBinderTest`：线程池 Gauge、allocator Gauge
- `MdcUtilTest`：HTTP/WebSocket 上下文设置、清理、空值处理
- `NettyServerHealthIndicatorTest`：运行中 UP（含明细）、已停止 DOWN

## 完成标准核对

- v1.6.2 审计报告 6 个遗留缺陷全部修复并有回归测试 ✅
- `spring-boot-starter-actuator` 用户无需额外配置即可在 `/actuator/health` 看到 Netty 服务健康状态 ✅
- 分片 WebSocket 消息可在启用聚合后被正确接收和处理 ✅
- 全量 `mvn test` 通过（9 个模块）✅

## 升级指南

从 v1.6.2 直接升级，无需修改任何业务代码或配置。

启用 WebSocket 分片消息聚合（可选）：

```properties
# 0（默认）= 禁用；正值 = 聚合缓冲区上限（字节），建议 ≥ max-frame-payload-length
server.netty.websocket.max-frame-aggregation-buffer-size=65536
```

结构化日志（可选，logback 示例）：

```
%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} [%X{netty.requestId}] [%X{netty.sessionId}] - %msg%n
```

可观测性与健康检查在 classpath 存在 `micrometer-core` / `spring-boot-actuator` 时自动启用，无需额外配置。
