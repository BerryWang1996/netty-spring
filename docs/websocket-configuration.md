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

## 当前行为说明

- `server.netty.websocket.enable=false` 时，不会注册 WebSocket mapping，也不会自动暴露 `MessageSenderSupport` / `MessageSender` 相关 Bean。
- `server.netty.mvc.enable=false` 时，不会注册 MVC `@RequestMapping`。
- handler 准入已经采用 fail-fast 策略，permit 耗尽时不会阻塞 Netty event loop。
- 握手成功回调、写失败关闭、`channelInactive` 关闭等生命周期会统一进入应用侧执行模型，而不是直接在 I/O 线程执行。
- 当应用引入 websocket starter 但没有声明 `@MessageMapping` 时，`MessageSenderSupport` 会退化为空实现，不会再因为空指针导致启动后的调用失败。
- 自动配置默认注册 `messageSenderSupport` Bean，并额外暴露 `messageSender` 别名；业务代码推荐按 `MessageSender` 接口注入，保留对 `MessageSenderSupport` 的兼容。应用自定义 `MessageSender` Bean 时，默认 `MessageSenderSupport` 会自动退让。

## 运行时观测入口

- `NettyServerBootstrap#getHandlerRuntimeStats()`：读取 handler 线程池和 permit 运行时快照。
- `NettyServerBootstrap#getHttpRuntimeStats()`：读取 WebSocket handshake/origin 拒绝计数，以及 HTTP/静态文件失败路径计数。
- `MessageSender#getRuntimeStats()`：读取 websocket 发送线程池、广播拒绝、caller-runs 回退、不可写 channel 策略命中和写失败计数。
- Spring Boot Starter 场景下，推荐通过 `MessageSender#getRuntimeStats()` 获取发送侧快照；`MessageSenderSupport#getRuntimeStats()` 继续兼容。
