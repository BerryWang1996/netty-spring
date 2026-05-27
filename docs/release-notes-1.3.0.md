# 1.3.0 发布说明

发布日期：2026-05-27

版本定位：P6 可观测性与运维能力正式版，同时承接 P5 遗留的握手鉴权扩展点。

## 新增能力

### 关闭原因维度化

- 新增 `CloseReason` 枚举，覆盖 15 种 WebSocket session 关闭原因：`CLIENT_CLOSE`、`API_CLOSE`、`SERVER_SHUTDOWN`、`HEARTBEAT_TIMEOUT`、`TRANSPORT_ERROR`、`FRAME_TOO_LARGE`、`DECRYPT_FAILURE`、`HANDSHAKE_FAILURE`、`CONNECTED_HANDLER_ERROR`、`CHANNEL_INACTIVE`、`CHANNEL_NOT_WRITABLE`、`WRITE_FAILURE`、`INTERCEPTOR_REJECTED`、`UNKNOWN`。
- 新增 `WebSocketEventRecorder`，线程安全的事件计数器，支持 enabled/noop 模式。
- 新增 `WebSocketEventStats`，不可变快照 DTO，包含所有计数器值和 closesByReason 维度分布。
- `MessageMappingResolver` 所有 close path（共 10 个入口点）和 `DefaultMessageSender` 的 `WRITE_FAILURE`/`CHANNEL_NOT_WRITABLE` 路径已标注具体 `CloseReason`。
- `/netty/status` 管理端点已包含 `eventCounters`，输出握手、消息和关闭维度统计。

### Micrometer 指标桥接

- 在 `netty-spring-boot-autoconfigure` 中新增可选 Micrometer 集成（`micrometer-core` 为 optional 依赖）。
- `NettyWebSocketMeterBinder` 暴露 WebSocket 指标：
  - `netty.websocket.handshakes.total/success/rejected` — 握手计数
  - `netty.websocket.messages.received/sent` — 消息收发计数
  - `netty.websocket.sessions.closed` (tagged by `reason`) — 按关闭原因分维度计数
  - `netty.websocket.sessions.active` — 活跃 session 数 Gauge
  - `netty.websocket.mappings` — 注册 mapping 数 Gauge
- `NettyHttpMeterBinder` 暴露 HTTP 运行时指标：
  - `netty.http.response.write.failures`、`netty.http.static.rejected`、`netty.http.static.write.failures`
  - `netty.http.idle.closes`、`netty.http.websocket.handshake.rejected`、`netty.http.websocket.origin.rejected`
- `NettyMicrometerConfigure` 自动条件装配，classpath 有 `MeterRegistry` 时激活，无 Micrometer 时自动退化为内置 runtime stats。

### 握手鉴权扩展点

- 新增 `WebSocketHandshakeInterceptor` 接口，支持 `beforeHandshake(FullHttpRequest, uri)` 和 `rejectionReason()` 方法。
- 拦截器在 Origin 校验之后、`ON_HANDSHAKE` 回调之前执行；返回 `false` 返回 HTTP 403。
- `MessageMappingSupporter` 自动从 ApplicationContext 发现 interceptor Bean 并注入所有 resolver。
- 拦截器异常自动捕获并返回 HTTP 500，不影响服务端稳定性。

### Demo 升级

- 新增 `auth-demo` Spring profile 的 token 鉴权拦截器示例（`WebSocketAuthDemoConfiguration`），支持 query parameter 和 Authorization Bearer header 两种传参方式。
- Demo 集成 `spring-boot-starter-actuator`，Actuator metrics 端点在 `localhost:8081/actuator/metrics` 可访问。
- Demo 首页新增 Micrometer 和 Auth 卡片入口。

### 文档

- README 新增"生产部署建议"章节，覆盖推荐配置、线程池调优、可观测接入和握手鉴权示例。
- README 新增 Micrometer / Actuator 指标说明和 auth-demo 启动命令。
- 常见排障速查新增 `Forbidden by handshake interceptor` 和多 interceptor Bean 条目。

## 新增文件

| 模块 | 文件 | 说明 |
| --- | --- | --- |
| netty-spring-websocket | `CloseReason.java` | 关闭原因枚举 |
| netty-spring-websocket | `WebSocketEventRecorder.java` | 线程安全事件计数器 |
| netty-spring-websocket | `WebSocketEventStats.java` | 不可变快照 DTO |
| netty-spring-websocket | `WebSocketHandshakeInterceptor.java` | 握手鉴权扩展点接口 |
| netty-spring-boot-autoconfigure | `NettyWebSocketMeterBinder.java` | WebSocket Micrometer 桥接 |
| netty-spring-boot-autoconfigure | `NettyHttpMeterBinder.java` | HTTP Micrometer 桥接 |
| netty-spring-boot-autoconfigure | `NettyMicrometerConfigure.java` | Micrometer 条件自动配置 |
| demo | `WebSocketAuthDemoConfiguration.java` | 鉴权 demo 配置 |

## 修改文件

- `MessageMappingResolver.java` — 全部 close path 标注 `CloseReason`，新增 handshake interceptor 和 event recorder 支持
- `DefaultMessageSender.java` — write failure 和 channel not writable 路径标注 `CloseReason`，发送成功记录 messageSent
- `MessageMappingSupporter.java` — 自动创建 event recorder，自动发现 interceptor Bean
- `AbstractMappingResolver.java` — 新增 `getEventCounters()` 泛型桥接方法
- `WebSocketRuntimeStats.java` — 新增 eventCounters 字段
- `WebMappingSupporter.java` — 聚合 eventCounters 到 runtime stats
- `netty-spring-boot-autoconfigure/pom.xml` — 新增 micrometer-core optional 依赖
- `spring.factories` — 注册 NettyMicrometerConfigure

## 测试

- `CloseReasonTest` — 4 个测试用例：unique tags、descriptions、toString、expected constants
- `WebSocketEventRecorderTest` — 10 个测试用例：全面覆盖计数器、noop、null、snapshot 不可变性
- `MessageMappingResolverTest` — 14 个 P6 新增测试：事件记录、各关闭原因、interceptor 拒绝/异常/通过
- `NettyWebSocketMeterBinderTest` — 8 个测试用例：指标注册、计数器值、Gauge 值、空/null map
- `NettyHttpMeterBinderTest` — 3 个测试用例：指标注册、值验证、空 stats

## 兼容性

- 完全向后兼容 1.2.x。
- Micrometer 为可选依赖，无 Micrometer 时所有现有功能不受影响。
- `WebSocketHandshakeInterceptor` 为可选扩展点，不注册 Bean 时行为与 1.2.x 一致。
- Spring Boot 2.7.x 兼容。

## 升级指南

从 1.2.3 升级到 1.3.0：

1. 更新依赖版本号为 `1.3.0`。
2. （可选）引入 `spring-boot-starter-actuator` 以启用 Micrometer 指标。
3. （可选）实现 `WebSocketHandshakeInterceptor` 接口并注册为 Spring Bean 以启用握手鉴权。
4. 无需修改现有业务代码。
