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
    http:
      handle-file: true
      file-location: ./public
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
```

- `http.handle-file`：未匹配到 MVC/WebSocket mapping 时，是否尝试按静态文件处理。
- `http.file-location`：静态文件根目录。启用 `handle-file` 时必须配置。
- `http.info-location`：预留的信息目录配置，当前不参与运行时强校验。
- `http.gzip.*`：HTTP 响应压缩配置。
- `http.ssl.*`：SSL 证书配置。启用 SSL 时会在 Netty channel 初始化阶段读取证书文件。

## 兼容策略

- `server.netty.http.*` 是 `1.1.x` 起推荐使用的新命名空间。
- 旧的 `server.netty.handle-file`、`server.netty.file-location`、`server.netty.info-location`、`server.netty.gzip.*`、`server.netty.ssl.*` 继续兼容。
- 新旧配置会映射到同一份运行时属性对象，运行时代码统一通过 `getHttp()` 视图读取 HTTP 相关配置。

## WebSocket 配置

WebSocket 线程池、背压、连接数和帧大小配置见 [WebSocket 配置说明](websocket-configuration.md)。
