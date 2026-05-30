# WebSocket 配置说明

配置前缀：`server.netty.websocket`

> **多节点扩展（自 V1.8.0）**：单机 WebSocket 可通过 Redis Pub/Sub 横向扩展为集群。配置前缀
> `server.netty.websocket.cluster.*`（默认关闭，需引入 `netty-websocket-cluster-spring-boot-starter`）。
> 详见 [API 使用指南 §9 WebSocket Cluster](api-guide.md#9-websocket-cluster) 与
> [集群方案设计](cluster-design.md)。

## 配置示例

```yaml
server:
  netty:
    websocket:
      enable: true
      core-pool-size: 4
      max-pool-size: 16
      keep-alive-time: 60
      queue-capacity: 1024
      handler-core-pool-size: 8
      handler-max-pool-size: 24
      handler-keep-alive-time: 5
      handler-queue-capacity: 0
      handler-permit-limit: 400
      max-connections: 2000
      max-frame-payload-length: 65536
      max-frame-aggregation-buffer-size: 0
      allowed-origins: https://app.example.com,https://admin.example.com
      heartbeat-interval-seconds: 30
      heartbeat-timeout-seconds: 90
      crypto:
        enable: false
        algorithm: AES-GCM
        key-id: main
        key-provider: demoProvider
        include-uris: /ws/secure,/ws/private
        exclude-uris: /ws/public
        encrypt-text: true
        encrypt-binary: true
        close-on-decrypt-failure: true
        reject-unencrypted: true
      broadcast-non-writable-channel-policy: SKIP
      broadcast-rejected-execution-policy: DROP
```

## 发送线程池

- `enable`：是否启用 WebSocket mapping 与 `MessageSenderSupport` 自动装配。默认值为 `true`。Starter 场景下可优先按 `MessageSender` 接口注入。

- `core-pool-size`：消息发送线程池核心线程数。默认值为 `max(2, CPU 核数)`。
- `max-pool-size`：消息发送线程池最大线程数。默认值为 `max(core-pool-size, CPU 核数 * 2)`。
- `keep-alive-time`：发送线程空闲存活时间，单位秒。默认值为 `60`。
- `queue-capacity`：发送线程池队列容量。
- `queue-capacity = 0`：使用 `SynchronousQueue`。
- `queue-capacity < 0`：启动期校验失败。
- 当同时显式配置 `core-pool-size` 与 `max-pool-size` 时，`max-pool-size` 必须大于等于 `core-pool-size`。

## Handler 执行模型

- `handler-core-pool-size`：handler 线程池核心线程数。默认值为 `max(2, CPU 核数)`。
- `handler-max-pool-size`：handler 线程池最大线程数。默认值为 `max(handler-core-pool-size, CPU 核数 * 2)`。
- `handler-keep-alive-time`：handler 线程空闲存活时间，单位秒。默认值为 `5`。
- `handler-queue-capacity`：handler 线程池队列容量。
- `handler-queue-capacity <= 0`：使用 `SynchronousQueue`。
- `handler-permit-limit`：handler 总准入上限，覆盖运行中与已提交待执行任务。默认值为 `handler-max-pool-size * 2`。
- `handler-queue-capacity < 0` 或 `handler-permit-limit < 0`：启动期校验失败。
- 当同时显式配置 `handler-core-pool-size` 与 `handler-max-pool-size` 时，`handler-max-pool-size` 必须大于等于 `handler-core-pool-size`。

## 背压与过载策略

- `broadcast-non-writable-channel-policy`：广播遇到不可写 channel 时的策略。
- `SKIP`：跳过该 session，默认值。
- `CLOSE`：关闭该 session，并走统一的 websocket 生命周期清理链路。
- `broadcast-rejected-execution-policy`：广播任务被发送线程池拒绝时的策略。
- `DROP`：丢弃该次广播任务，默认值。
- `CALLER_RUNS`：在调用线程中直接执行发送逻辑。

## 连接与帧限制

- `max-connections`：最大 websocket 连接数。
- `max-connections <= 0`：不限制连接数。
- `max-frame-payload-length`：单个 websocket frame 最大 payload 大小。
- `max-frame-payload-length <= 0`：回退到默认值 `65536`。
- `max-frame-aggregation-buffer-size`（`1.7.0` 起）：分片消息（`ContinuationWebSocketFrame`）聚合缓冲区上限，单位字节。
- `max-frame-aggregation-buffer-size <= 0`：默认值，禁用聚合；分片帧仍记录警告日志后丢弃（与 `1.6.x` 行为一致）。
- `max-frame-aggregation-buffer-size > 0`：握手成功后在 pipeline 中插入 Netty `WebSocketFrameAggregator`，把分片帧自动组装为完整的 `TextWebSocketFrame` / `BinaryWebSocketFrame`；建议取值不小于 `max-frame-payload-length`，否则聚合后的完整帧会再次被单帧上限拒绝并关闭连接。
- `allowed-origins`：允许握手的 `Origin` 白名单，支持逗号或空白分隔的精确值。
- `allowed-origins` 为空：保持兼容，允许所有 Origin。
- `allowed-origins=*`：显式允许所有 Origin，包括缺失 `Origin` 的请求。
- 配置了具体 Origin 后，请求缺失 `Origin` 或不匹配白名单会在握手前返回 `403`。

## 心跳与空闲断线

- `heartbeat-interval-seconds`：服务端发送 WebSocket Ping 帧的间隔，单位秒，默认 `0` 表示关闭。
- `heartbeat-timeout-seconds`：在指定时间内没有收到任何入站 frame 时关闭 session，单位秒，默认 `0` 表示关闭。
- 当 `heartbeat-interval-seconds > 0` 且 `heartbeat-timeout-seconds > 0` 时，`heartbeat-timeout-seconds` 必须大于等于 `heartbeat-interval-seconds`。
- 心跳超时会触发统一 `ON_ERROR` / `ON_CLOSE` 生命周期，并发送 reason 为 `Heartbeat timeout` 的 close frame。

## 应用层消息加密扩展

- `crypto.enable`：是否启用应用层 WebSocket 消息加密/解密扩展，默认 `false`。默认关闭时，发送和接收行为与明文版本完全一致。
- `crypto.algorithm`：交给 `MessageCryptoCodec` 使用的算法标识，默认 `CUSTOM`；设置为 `AES-GCM` 且未提供自定义 `MessageCryptoCodec` Bean 时，会启用框架内置 `AesGcmMessageCryptoCodec`。
- `crypto.key-id` / `crypto.key-provider`：AES-GCM 内置实现使用 `key-id` 写入密文 envelope，并通过 `MessageCryptoKeyProvider` 按 `kid` 解析密钥；`key-provider` 可指定 provider bean 名称，不配置时要求容器中只有一个 `MessageCryptoKeyProvider` Bean。
- `crypto.include-uris`：逗号或空白分隔的 WebSocket path/URI 列表。为空时 crypto 对全部 session 生效；配置后只对匹配的 session 生效。支持精确 path、原始 URI、mapping URL 或 `*`。
- `crypto.exclude-uris`：逗号或空白分隔的 WebSocket path/URI 排除列表。匹配后该 session 不做发送加密、接收解密，也不会因未加密 text/binary 数据帧触发 `reject-unencrypted`。
- `MessageCryptoPolicy`：可选的 session 粒度策略 Bean。未提供时，所有匹配 URI 策略的 session 都会启用 crypto；提供唯一 Bean 时，框架会在 URI include/exclude 之后调用 `shouldUseCrypto(MessageSession)`，允许业务按 query、header、session id 或握手上下文继续灰度放行旧客户端。
- `crypto.encrypt-text`：启用 crypto 后是否处理 `TextWebSocketFrame`，默认 `true`。
- `crypto.encrypt-binary`：启用 crypto 后是否处理 `BinaryWebSocketFrame`，默认 `true`。
- `crypto.close-on-decrypt-failure`：解密失败或未加密帧被拒绝时是否关闭 session，默认 `true`，关闭路径会进入统一 `ON_ERROR` / `ON_CLOSE` 生命周期。
- `crypto.reject-unencrypted`：启用 crypto 后，是否拒绝对应 text/binary 类型上的未加密数据帧，默认 `true`；灰度兼容明文客户端时可显式设置为 `false`。
- 启用 `CUSTOM` crypto 时，应用必须提供唯一一个 `MessageCryptoCodec` Bean；启用 `AES-GCM` 时可以使用内置 codec，但必须提供 `MessageCryptoKeyProvider`，框架不会硬编码密钥。
- 内置 AES-GCM 密文 envelope 包含 `alg`、`kid`、`typ`、`iv`、`ciphertext` 字段；Java AES-GCM authentication tag 会附加在 `ciphertext` 中，不单独暴露 `tag` 字段。
- AES-GCM 密钥轮换建议：把 `crypto.key-id` 切到新 key 后，新发送的密文 envelope 会携带新的 `kid`；过渡期内 `MessageCryptoKeyProvider` 应同时保留旧 key 和新 key，确保旧 `kid` 的历史密文仍可解密。demo 中的 `demoProvider` 只是 toy 示例，生产环境应替换为 KMS、配置中心或等效密钥服务。
- 应用层 crypto 不替代 TLS/WSS，也不承诺浏览器运行时完全不可见明文；如果前端需要解密，密钥或明文仍会在浏览器运行时出现。

- demo 工程提供 `/ws/crypto-demo` 页面用于浏览器端 AES-GCM 联调。使用 `crypto-demo` Spring profile 启动 demo 后打开该页面连接 `/ws/test?room=crypto-demo`，浏览器 Network/WebSocket 面板会看到 JSON envelope，页面日志会展示解密后的 echo 文本。
- `/ws/crypto-demo` 页面内置的 `demo-2026-04` / `demo-2026-05` key 只对应 demoProvider 的 toy key，不能作为生产密钥分发方案。

## 当前行为说明

- `server.netty.websocket.enable=false` 时，不会注册 WebSocket mapping，也不会自动暴露 `MessageSenderSupport` / `MessageSender` 相关 Bean。
- `server.netty.mvc.enable=false` 时，不会注册 MVC `@RequestMapping`。
- handler 准入已经采用 fail-fast 策略，permit 耗尽时不会阻塞 Netty event loop。
- 握手成功回调、写失败关闭、`channelInactive` 关闭等生命周期会统一进入应用侧执行模型，而不是直接在 I/O 线程执行。
- 当应用引入 websocket starter 但没有声明 `@MessageMapping` 时，`MessageSenderSupport` 会退化为空实现，不会再因为空指针导致启动后的调用失败。
- 自动配置默认注册 `messageSenderSupport` Bean，并额外暴露 `messageSender` 别名；业务代码推荐按 `MessageSender` 接口注入，保留对 `MessageSenderSupport` 的兼容。应用自定义 `MessageSender` Bean 时，默认 `MessageSenderSupport` 会自动退让。

## 常见错误与排障

| 现象或错误信息 | 常见原因 | 建议动作 |
| --- | --- | --- |
| `WebSocket message uri "..." is not registered` | 发送、广播或关闭 session 时使用的 URI 没有对应 `@MessageMapping`。 | 对齐 `@MessageMapping` 的 path，检查是否引入 websocket starter，并确认 `server.netty.websocket.enable=true`。 |
| `No websocket mappings are currently registered` | 应用没有声明 WebSocket 端点，或 WebSocket mapping 被关闭。 | 增加至少一个 `@MessageMapping`，或在没有 WebSocket 端点的应用中避免调用 `MessageSender` 发送方法。 |
| `target sessions are closed or missing` | 目标 session 已断开、被心跳/关闭链路清理，或业务缓存了过期 session id。 | 使用 `MessageSender#getSessionIds(uri)` 获取最新快照，或发送前调用 `isSessionAlive(uri, sessionIds...)`。 |
| `Forbidden by origin` | 配置了 `allowed-origins`，但浏览器 `Origin` 缺失或不匹配。 | 把实际前端 Origin 加入 `server.netty.websocket.allowed-origins`；仅本地调试时可显式配置 `*`。 |
| `WebSocket connection limit exceeded` | `max-connections` 已达到上限。 | 清理空闲连接，调大 `server.netty.websocket.max-connections`，或配合心跳/客户端退避。 |
| `No handler permits available` / `Handler executor rejected task` | handler 执行过慢、permit 用尽或线程池/队列过小。 | 优化业务 handler，调大 `handler-permit-limit`、`handler-max-pool-size` 或 `handler-queue-capacity`，并加入客户端重试退避。 |
| `Failed to deserialize websocket text payload` | `TEXT_MESSAGE` handler 绑定 JSON 对象，但客户端发送的文本结构不匹配。 | 先用 `String` 或 `TextWebSocketFrame` 接收原始文本排查，补 `ON_ERROR` 记录坏 payload，再修正 JSON schema。 |
| `MessageCryptoKeyProvider bean` 相关启动失败 | AES-GCM crypto 已开启，但没有唯一 key provider，或 `crypto.key-provider` 名称写错。 | 提供唯一 `MessageCryptoKeyProvider` Bean，或设置 `server.netty.websocket.crypto.key-provider` 指向正确 bean。 |
| `Unencrypted websocket frame rejected by crypto policy` | 当前 session 命中 crypto 策略，但客户端仍发送明文 text/binary frame。 | 让客户端发送 AES-GCM/custom envelope，或通过 `crypto.include-uris` / `exclude-uris` / `reject-unencrypted=false` 做灰度兼容。 |

## MessageSender API

- `getSessionIds(uri)`：按 URI 获取当前 session id 的只读快照。
- `getSession(uri, sessionId)`：按 URI 和 session id 获取当前 session，不存在或已关闭时返回 `null`。
- `getSessions(uri)`：按 URI 获取 session 的只读快照，避免业务代码直接修改内部 session map。
- `sendToSession(uri, message, sessionId)`：向单个 session 发送消息，是 `sendMessage()` 的语义化别名。
- `sendText(uri, text, sessionIds...)` / `sendTextToSession(uri, text, sessionId)`：发送文本消息的便利方法，业务侧不必手动创建 `TextMessage`。
- `sendJson(uri, payload, sessionIds...)` / `sendJsonToSession(uri, payload, sessionId)`：发送 JSON 消息的便利方法，业务侧不必手动创建 `JsonMessage`。
- `broadcast(uri, message)`：向指定 URI 下全部 session 广播消息，是 `topicMessage()` 的语义化别名。
- `broadcastText(uri, text)` / `broadcastJson(uri, payload)`：广播文本或 JSON 消息的便利方法。
- `closeSession(uri, sessionId)` / `closeSession(uri, sessionId, statusCode, reasonText)`：主动关闭单个 session，并走统一 `ON_CLOSE` 和 session 清理链路。
- `closeSessions(uri)` / `closeSessions(uri, statusCode, reasonText)`：主动关闭指定 URI 下的全部 session，返回已启动关闭流程的 session 数。
- 原有 `sendMessage()` / `topicMessage()` 保持兼容，后续文档和 demo 会优先使用更清晰的 `sendToSession()` / `broadcast()`。

## MessageSession API

- `getUri()` / `getPath()`：读取握手请求原始 URI 和 path。
- `getQueryParam(name)`：读取第一个 query 参数值，不存在时返回 `null`。
- `getQueryParams(name)` / `getQueryParams()`：读取 query 参数只读快照。
- `getHeader(name)`：读取第一个 header 值。
- `getHeaders(name)` / `getHeaderNames()`：读取 header 值和 header 名称只读快照。

## Handler 参数绑定

- `TEXT_MESSAGE` 继续支持绑定 `TextWebSocketFrame`，也可以直接绑定 `String` 消息正文。
- `TEXT_MESSAGE` 还支持把 JSON 文本直接绑定到业务对象、`Map`、`Collection`、数组、枚举或基础包装类型；反序列化失败会进入现有 `ON_ERROR` 生命周期。
- `BINARY_MESSAGE` 继续支持绑定 `BinaryWebSocketFrame`，也可以直接绑定 `ByteBuf` 或 `byte[]`。
- `ByteBuf` 参数仅保证在当前回调内可用；如果业务需要异步持有，应自行 `retain()` 或优先使用 `byte[]` 参数。

## 消息类型

- `TextMessage`：发送普通文本帧。
- `BinaryMessage`：发送二进制帧，每次发送会基于原始 `ByteBuf` 创建 retained duplicate。
- `JsonMessage`：发送 JSON 文本帧，将业务对象序列化为 JSON 后写入 `TextWebSocketFrame`。

## 运行时观测入口

- `NettyServerBootstrap#getHandlerRuntimeStats()`：读取 handler 线程池和 permit 运行时快照。
- `NettyServerBootstrap#getHttpRuntimeStats()`：读取 WebSocket handshake/origin 拒绝计数，以及 HTTP/静态文件失败路径计数。
- `NettyServerBootstrap#getWebSocketRuntimeStats()`：读取 WebSocket mapping 数和活跃 session 数；启用 `server.netty.management.enable=true` 后，`/netty/status` 也会包含同一份 `websocket` 快照。
- `MessageSender#getRuntimeStats()`：读取 websocket 发送线程池、广播拒绝、caller-runs 回退、不可写 channel 策略命中和写失败计数。
- Spring Boot Starter 场景下，推荐通过 `MessageSender#getRuntimeStats()` 获取发送侧快照；`MessageSenderSupport#getRuntimeStats()` 继续兼容。

## Micrometer 指标（`1.7.0` 扩展）

classpath 中存在 `micrometer-core` 时自动桥接到 `MeterRegistry`，无需额外配置。除既有 handshake / message / close 计数外，`1.7.0` 新增：

- `netty.websocket.sessions.active.uri`（Gauge，按 `uri` 标签）：分 URI 的活跃 session 数。
- `netty.websocket.connection.duration`（Timer，按 `reason` 标签）：连接从握手到关闭的时长。
- `netty.websocket.message.size`（DistributionSummary，单位 bytes）：入站消息 payload 大小分布。
- `netty.websocket.broadcast.fanout`（DistributionSummary）：单次广播投递的 session 数分布。
- `netty.websocket.handler.latency`（Timer）：handler 方法执行耗时分布。

duration / size / fanout / latency 为 push 模型指标，通过 `WebSocketMetricsCallback` 桥接，会同时写入每个已绑定的 `MeterRegistry`（与 `CompositeMeterRegistry` 或多 registry 共存时不会漏写或重复计数）。

## 结构化日志（MDC，`1.7.0` 新增）

handler 入口和 WebSocket 生命周期回调会在 SLF4J MDC 中注入 `netty.sessionId`、`netty.uri`、`netty.remoteAddr`（HTTP 请求为 `netty.requestId`），处理完成后清理。在 logback/log4j2 pattern 中引用对应 `%X{...}` 即可，无需改动业务代码。`@OnConnected` / `@OnClose` / 心跳 / 错误等在 handler 线程池上执行的回调同样携带会话 MDC 上下文。

## 健康检查（Actuator，`1.7.0` 新增）

引入 `spring-boot-actuator` 后自动注册 `NettyServerHealthIndicator`，在 `/actuator/health` 暴露：服务运行中为 `UP` 并附端口、handler 线程池大小/活跃/队列、连接许可可用/上限等明细；服务未运行为 `DOWN` 并说明原因。
