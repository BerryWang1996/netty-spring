# Release Notes — v1.6.2

> 发布日期：2026-05-28

## 版本定位

v1.6.2 是安全与稳定性修复版本，修复了 v1.6.1 以来发现的 22 个 bug（10 HIGH / 10 MEDIUM / 2 LOW），涵盖 HTTP 请求处理、WebSocket 会话生命周期、数据绑定、安全防护和 HTTP 协议合规五个方面。全部改动向后兼容。

## HIGH 严重级别（10 个）

| # | 模块 | 描述 |
|---|------|------|
| 1 | ServiceHandler | 移除已释放 ByteBuf 上的 use-after-free 调试日志 |
| 2 | ServiceHandler | If-Modified-Since 比较改用 `>=` 并增加畸形头部 try/catch |
| 3 | MessageMappingResolver | 心跳仅在写成功后调度下一次，写失败则关闭会话 |
| 4 | MessageMappingResolver | doShutdownClose：先关闭 channel 再释放 session |
| 5 | MessageMappingResolver | doCloseSessionOnHeartbeatTimeout：同上关闭顺序修复 |
| 6 | MessageMappingResolver | closeSession API：对已断开的 channel 也执行 onClose 生命周期 |
| 7 | DataBindUtil | 支持原始类型（int/long/boolean 等），防止自动拆箱 NPE |
| 8 | CompressorHandler | Content-Type 为空时（204/304 响应）不再抛 NPE |
| 9 | Cookie | Set-Cookie 头部值进行 CRLF/NUL 字符过滤，防止 HTTP 头注入 |
| 10 | NettyChannelInitializer | SSL 未显式配置协议时默认 TLSv1.2+1.3，阻止 TLS 1.0/1.1 |

## MEDIUM 严重级别（10 个）

| # | 模块 | 描述 |
|---|------|------|
| 11 | ServiceHandler | handler 异常时返回 500 而非静默失败 |
| 12 | ServiceHandler | 线程池拒绝时返回 503 而非直接关闭连接 |
| 13 | ServiceHandlerUtil | sendError 对无 Accept 头的 GET 请求正确返回 HTML |
| 14 | Cookie | parseCookieString 修剪 value 前后空白 |
| 15 | StartupPropertiesUtil | 允许端口 0（操作系统分配临时端口） |
| 16 | RequestMappingResolver | writeResponse 对非 keep-alive 客户端关闭连接 |
| 17 | RequestMappingResolver | CORS 预检响应对非 keep-alive 客户端关闭连接 |
| 18 | RequestMappingResolver | 文件下载响应对非 keep-alive 客户端关闭连接 |
| 19 | Cookie | 新增 SameSite 属性支持（Strict/Lax/None） |
| 20 | MessageMappingResolver | ContinuationWebSocketFrame（分片消息）记录警告日志而非静默丢弃 |

## LOW 严重级别（2 个）

| # | 模块 | 描述 |
|---|------|------|
| 21 | JsonViewHandler / HtmlViewHandler | 使用 getStatus() 而非硬编码 OK |
| 22 | ServiceHandlerUtil | sendNotModified 移除无效 Location 头，改为 Date 头（RFC 7232） |

## 新增测试

- `CompressorHandlerTest`：3 个测试（null Content-Type、可压缩类型、不可压缩类型）
- `CookieTest`：6 个新测试（CRLF 注入、NUL 过滤、SameSite Strict/Lax/None）
- `JsonViewHandlerTest`：1 个新测试（status override）
- `HtmlViewHandlerTest`：2 个新测试（null data、status override）
- `ServiceHandlerUtilErrorResponseTest`：1 个新测试（query string 不二次解码）
- `StartupPropertiesUtilTest`：1 个新测试（ephemeral port 0）

## 已知遗留项（计划在后续版本修复）

- `shutdown()` 不等待异步生命周期任务完成（需要架构调整）
- `DataBindUtil.setObjectProperties` 嵌套属性匹配逻辑可改进（现有行为因约定可工作）
- `closeSession` TOCTOU 竞态（CAS 保护提供基本安全性，完善修复需重构）
- WebSocket 握手 URL 使用未校验的 Host 头（需增加白名单验证）
- ResponseEntity 自定义头部未进行 CRLF 过滤（Netty 4.1.x 编码层提供部分保护）
- `MimetypesFileTypeMap` 作为 public static 字段暴露（封装性问题）

## 升级指南

从 v1.6.1 直接升级，无需修改任何业务代码或配置。

新增可选 API：
```java
// Cookie SameSite 属性
Cookie cookie = new Cookie("session");
cookie.setSameSite("Strict"); // 或 "Lax" / "None"
```
