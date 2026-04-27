# WebSocket 配置说明

配置前缀：`server.netty.websocket`

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
- `crypto.encrypt-text`：启用 crypto 后是否处理 `TextWebSocketFrame`，默认 `true`。
- `crypto.encrypt-binary`：启用 crypto 后是否处理 `BinaryWebSocketFrame`，默认 `true`。
- `crypto.close-on-decrypt-failure`：解密失败或未加密帧被拒绝时是否关闭 session，默认 `true`，关闭路径会进入统一 `ON_ERROR` / `ON_CLOSE` 生命周期。
- `crypto.reject-unencrypted`：启用 crypto 后，是否拒绝对应 text/binary 类型上的未加密数据帧，默认 `true`；灰度兼容明文客户端时可显式设置为 `false`。
- 启用 `CUSTOM` crypto 时，应用必须提供唯一一个 `MessageCryptoCodec` Bean；启用 `AES-GCM` 时可以使用内置 codec，但必须提供 `MessageCryptoKeyProvider`，框架不会硬编码密钥。
- 内置 AES-GCM 密文 envelope 包含 `alg`、`kid`、`typ`、`iv`、`ciphertext` 字段；Java AES-GCM authentication tag 会附加在 `ciphertext` 中，不单独暴露 `tag` 字段。
- 应用层 crypto 不替代 TLS/WSS，也不承诺浏览器运行时完全不可见明文；如果前端需要解密，密钥或明文仍会在浏览器运行时出现。

## 当前行为说明

- `server.netty.websocket.enable=false` 时，不会注册 WebSocket mapping，也不会自动暴露 `MessageSenderSupport` / `MessageSender` 相关 Bean。
- `server.netty.mvc.enable=false` 时，不会注册 MVC `@RequestMapping`。
- handler 准入已经采用 fail-fast 策略，permit 耗尽时不会阻塞 Netty event loop。
- 握手成功回调、写失败关闭、`channelInactive` 关闭等生命周期会统一进入应用侧执行模型，而不是直接在 I/O 线程执行。
- 当应用引入 websocket starter 但没有声明 `@MessageMapping` 时，`MessageSenderSupport` 会退化为空实现，不会再因为空指针导致启动后的调用失败。
- 自动配置默认注册 `messageSenderSupport` Bean，并额外暴露 `messageSender` 别名；业务代码推荐按 `MessageSender` 接口注入，保留对 `MessageSenderSupport` 的兼容。应用自定义 `MessageSender` Bean 时，默认 `MessageSenderSupport` 会自动退让。

## MessageSender API

- `getSessionIds(uri)`：按 URI 获取当前 session id 的只读快照。
- `getSession(uri, sessionId)`：按 URI 和 session id 获取当前 session，不存在或已关闭时返回 `null`。
- `getSessions(uri)`：按 URI 获取 session 的只读快照，避免业务代码直接修改内部 session map。
- `sendToSession(uri, message, sessionId)`：向单个 session 发送消息，是 `sendMessage()` 的语义化别名。
- `broadcast(uri, message)`：向指定 URI 下全部 session 广播消息，是 `topicMessage()` 的语义化别名。
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
- `MessageSender#getRuntimeStats()`：读取 websocket 发送线程池、广播拒绝、caller-runs 回退、不可写 channel 策略命中和写失败计数。
- Spring Boot Starter 场景下，推荐通过 `MessageSender#getRuntimeStats()` 获取发送侧快照；`MessageSenderSupport#getRuntimeStats()` 继续兼容。
