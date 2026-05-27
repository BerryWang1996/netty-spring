# 1.3.1 发布说明

发布日期：2026-05-27

版本定位：代码质量深度治理版本，修复所有遗留的生产代码缺陷，补齐 webmvc 模块测试覆盖。

## 修复内容

### 日志规范化

- 消除所有生产代码中的 `e.printStackTrace()`（共 8 处），替换为 SLF4J `log.error()` / `log.warn()`。
- 涉及文件：`ClassUtil.java`（2 处）、`ServiceHandlerUtil.java`（1 处）、`DataBindUtil.java`（5 处）。
- POST 请求参数读取失败时不再静默吞掉 `IOException`，改为 `log.warn()` 记录。

### SimpleDateFormat 线程安全修复

- 将 6 处多线程环境中使用的 `SimpleDateFormat` 替换为线程安全的 `DateTimeFormatter`。
- 涉及文件：`ServiceHandlerUtil.java`（`setDateAndCacheHeaders` + `HttpErrorMessage` 构造函数）、`ServiceHandler.java`（`If-Modified-Since` 解析）、`Cookie.java`（expires 格式化）。
- `DataBindUtil.parseStringToDate()` 改为优先使用 `DateTimeFormatter`，不兼容时回退到方法内局部 `SimpleDateFormat`（不跨线程共享）。
- `JsonViewHandler` 中的 `SimpleDateFormat` 移入 `ObjectMapper` 静态初始化块，由 `ObjectMapper` 内部保证线程安全。

### HTTP 错误响应实现

- `ServiceHandlerUtil.errorResponseHtml()` 不再返回空字符串，改为返回包含状态码、错误类型、时间戳、路径和消息的结构化 HTML 错误页面。
- `ServiceHandlerUtil.errorResponseJson()` 不再返回空字符串，改为返回包含 `timestamp`、`status`、`error`、`message`、`path` 字段的 JSON 错误响应。
- HTML 输出包含 XSS 转义（`escapeHtml`），JSON 输出包含特殊字符转义（`escapeJson`）。

### 代码质量改进

- `JsonViewHandler` 中的 `ObjectMapper` 从每请求创建改为静态单例，减少不必要的对象创建开销。
- `HttpErrorMessage.timestrap` 字段拼写错误修复为 `timestamp`，原 `getTimestrap()` 方法标记为 `@Deprecated` 保留向后兼容。
- `ServiceHandlerUtil.decodeRequestString()` 使用 `StandardCharsets.UTF_8` 替代字符串 `"utf-8"`，消除不可能的 `UnsupportedEncodingException`。
- `HtmlViewHandler` 和 `JsonViewHandler` 移除无效的 `response.headers().get(HttpHeaderNames.COOKIE, "")` 死代码。
- POST 请求中 `text/plain` 等非表单类型的参数解析限制已在注释中明确说明。

## 新增测试

| 模块 | 测试类 | 测试方法数 | 说明 |
| --- | --- | --- | --- |
| netty-spring-web | `ServiceHandlerUtilErrorResponseTest` | 8 | HttpErrorMessage 字段、timestamp、URL 解码、GET 参数解析 |
| netty-spring-webmvc | `HttpRequestMethodTest` | 3 | 枚举匹配、大小写不敏感、未知方法 |
| netty-spring-webmvc | `CookieTest` | 10 | 序列化、域、路径、过期、max-age、Secure/HttpOnly、解析 |
| netty-spring-webmvc | `JsonViewHandlerTest` | 4 | JSON 响应、null、字符串、默认配置 |
| netty-spring-webmvc | `HtmlViewHandlerTest` | 3 | HTML 响应、默认配置、空字符串 |

`netty-spring-webmvc` 模块从 1 个测试方法增长到 21 个测试方法。

## 兼容性

- 完全向后兼容 1.3.0。
- `HttpErrorMessage.getTimestrap()` 标记为 `@Deprecated`，推荐使用 `getTimestamp()`。
- HTTP 错误响应格式从空字符串变为结构化内容，不影响 API 行为。
- 无需修改业务代码。

## 升级指南

从 1.3.0 升级到 1.3.1：更新依赖版本号即可，无需其他变更。
