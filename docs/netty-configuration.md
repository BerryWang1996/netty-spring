# Netty 配置说明

配置前缀：`server.netty`

## 基础配置

```yaml
server:
  netty:
    port: 8080
    mvc:
      enable: true
    websocket:
      enable: true
```

- `port`：Netty 服务端口，必须大于 `0`。
- `mvc.enable`：是否注册 MVC `@RequestMapping`。默认值为 `true`。
- `websocket.enable`：是否注册 WebSocket `@MessageMapping` 以及相关 sender Bean。默认值为 `true`。

## HTTP 配置

`server.netty.http.*` 是 HTTP、静态文件、gzip、SSL 的推荐配置入口。

```yaml
server:
  netty:
    management:
      enable: false
      health-path: /netty/health
      status-path: /netty/status
    http:
      handle-file: true
      file-location: ./public
      max-initial-line-length: 4096
      max-header-size: 8192
      max-chunk-size: 8192
      max-content-length: 65536
      read-timeout-seconds: 0
      write-timeout-seconds: 0
      idle-timeout-seconds: 0
      gzip:
        enable: true
        compression-level: 6
        window-bits: 15
        mem-level: 8
        content-size-threshold: 0
        types: text/html text/plain application/json
      ssl:
        enable: true
        certificate: ./cert/server.crt
        certificate-key: ./cert/server.key
        protocols: TLSv1.2,TLSv1.3
        ciphers: TLS_AES_128_GCM_SHA256 TLS_AES_256_GCM_SHA384
```

- `http.handle-file`：未匹配到 MVC/WebSocket mapping 时，是否尝试按静态文件处理。
- `http.file-location`：静态文件根目录。启用 `handle-file` 时必须配置。
- `http.info-location`：预留的信息目录配置，当前不参与运行时强校验。
- `http.max-initial-line-length`：HTTP request line 最大长度，默认 `4096`；配置小于等于 `0` 时回退默认值。
- `http.max-header-size`：HTTP header 最大长度，默认 `8192`；配置小于等于 `0` 时回退默认值。
- `http.max-chunk-size`：HTTP 解码单个 chunk 最大长度，默认 `8192`；配置小于等于 `0` 时回退默认值。
- `http.max-content-length`：HTTP 聚合请求体最大长度，默认 `65536`；配置小于等于 `0` 时回退默认值。
- `http.read-timeout-seconds`：读超时时间，单位秒，默认 `0` 表示关闭；配置小于 `0` 时启动失败。
- `http.write-timeout-seconds`：写超时时间，单位秒，默认 `0` 表示关闭；配置小于 `0` 时启动失败。
- `http.idle-timeout-seconds`：读写全空闲超时时间，单位秒，默认 `0` 表示关闭；配置小于 `0` 时启动失败，触发后关闭 channel。
- `http.gzip.*`：HTTP 响应压缩配置。
- `http.ssl.*`：SSL 证书配置。启用 SSL 时会在启动期校验证书和私钥路径，随后在 Netty channel 初始化阶段读取证书文件。
- `http.ssl.protocols`：可选 TLS 协议白名单，支持逗号或空白分隔；为空时使用 Netty/JDK 默认值。
- `http.ssl.ciphers`：可选 cipher suite 白名单，支持逗号或空白分隔；为空时使用 Netty/JDK 默认值。
- `management.enable`：是否开启内置轻量管理端点，默认 `false`，避免默认暴露运行时信息。
- `management.health-path`：健康检查路径，默认 `/netty/health`，开启后返回 `{"status":"UP"}`。
- `management.status-path`：状态快照路径，默认 `/netty/status`，开启后返回 handler/http/websocket 运行时快照和 mapping 数量。

## 生产边界说明

- 启用静态文件服务时，请始终把 `http.file-location` 指向专用公开目录；框架会对请求路径做 URL decode、canonical path 和根目录包含校验，拒绝 `..` 或编码后的路径穿越。
- `max-initial-line-length`、`max-header-size`、`max-chunk-size`、`max-content-length` 是 HTTP 请求容量边界，生产环境建议按业务实际请求大小显式配置。
- `read-timeout-seconds`、`write-timeout-seconds`、`idle-timeout-seconds` 是 HTTP 连接时间边界，生产环境建议显式开启，避免慢请求或异常连接长期占用资源。
- 启用 `http.ssl.enable=true` 时，必须同时配置 `http.ssl.certificate` 和 `http.ssl.certificate-key`，并且二者都必须是已存在的普通文件；生产环境建议显式配置 `http.ssl.protocols` 和 `http.ssl.ciphers`，避免依赖运行时默认 TLS 策略。
- MVC 响应和静态文件发送失败时会记录 warn 日志并关闭 channel，避免失败连接继续占用资源；同时可通过 `NettyServerBootstrap#getHttpRuntimeStats()` 读取 HTTP 响应写失败、静态文件拒绝/写失败、idle 关闭、WebSocket handshake/origin 拒绝等轻量运行时计数，并可通过 `NettyServerBootstrap#getWebSocketRuntimeStats()` 读取 WebSocket mapping 数和活跃 session 数。
- 管理端点默认关闭；生产环境如需开启，建议仅在内网、网关保护或独立监听策略下暴露，并按实际运维规范配置 `health-path` / `status-path`。

## 可观测性增强（Micrometer / Actuator / 结构化日志，`1.7.0`）

内置 `/netty/status` 快照之外，`1.7.0` 提供与标准 Spring Boot 生态对接的可观测能力，均为可选依赖，缺失时自动跳过：

- **Micrometer 指标**：classpath 存在 `micrometer-core` 时，HTTP 与 WebSocket 运行时计数自动桥接到 `MeterRegistry`。HTTP 侧新增 handler 线程池利用率/队列深度/许可数和 Netty allocator 堆内/堆外内存 Gauge；WebSocket 侧新增连接时长、消息大小、广播 fanout、handler 延迟等分布指标。完整指标清单见 [WebSocket 配置说明](websocket-configuration.md) 与 [API 使用指南](api-guide.md#10-metrics--monitoring)。
- **Actuator 健康检查**：classpath 存在 `spring-boot-actuator` 时自动注册 `NettyServerHealthIndicator`，在 `/actuator/health` 暴露端口、线程池和连接许可状态（运行中 `UP` / 未运行 `DOWN`）。
- **结构化日志（MDC）**：handler 入口与 WebSocket 生命周期回调自动注入 `netty.requestId`（HTTP）/ `netty.sessionId`（WebSocket）/ `netty.uri` / `netty.remoteAddr` 到 SLF4J MDC，处理完成后清理；在日志 pattern 中引用对应 `%X{...}` 即可使用，无需改动业务代码。

## 兼容策略

- `server.netty.http.*` 是 `1.1.x` 起推荐使用的新命名空间。
- 旧的 `server.netty.handle-file`、`server.netty.file-location`、`server.netty.info-location`、`server.netty.gzip.*`、`server.netty.ssl.*` 继续兼容。
- 新旧配置会映射到同一份运行时属性对象，运行时代码统一通过 `getHttp()` 视图读取 HTTP 相关配置。

## WebSocket 配置

WebSocket 线程池、背压、连接数和帧大小配置见 [WebSocket 配置说明](websocket-configuration.md)。
