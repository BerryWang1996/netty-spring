# netty-spring

基于 Netty 的 Spring Web/WebMVC/WebSocket 集成项目，包含核心运行时模块和对应的 Spring Boot Starter。

## 模块概览

- `netty-spring-web`：Netty 启动、通道初始化、HTTP 请求分发等基础能力。
- `netty-spring-webmvc`：MVC 映射支持。
- `netty-spring-websocket`：WebSocket 映射、会话管理、消息发送与生命周期处理。
- `netty-spring-boot-autoconfigure`：Starter 共用的 Netty 启动与配置绑定自动装配骨架。
- `netty-web-spring-boot-starter`：Web 基础 Starter。
- `netty-webmvc-spring-boot-starter`：WebMVC Starter。
- `netty-websocket-spring-boot-starter`：WebSocket Starter。
- `demo-netty-web-spring-boot-starter`：示例工程。

## 5 分钟快速开始

### 选择 Starter

| 使用场景 | 推荐依赖 | 说明 |
| --- | --- | --- |
| 只需要 Netty 基础启动能力 | `netty-web-spring-boot-starter` | 提供 Netty server 与基础 HTTP 分发。 |
| 需要 HTTP MVC 映射 | `netty-webmvc-spring-boot-starter` | 提供 `@RequestMapping` / `@GetMapping` 等 MVC 能力。 |
| 只需要 WebSocket | `netty-websocket-spring-boot-starter` | 提供 `@MessageMapping`、`MessageSender` 和 WebSocket 生命周期。 |
| HTTP MVC + WebSocket | 同时引入 WebMVC 与 WebSocket Starter | 适合控制台接口 + WebSocket 推送服务。 |

### 最小配置

```properties
server.netty.port=8080
server.netty.websocket.enable=true
server.netty.mvc.enable=true
```

业务侧推荐按接口注入 `MessageSender`，不需要显式 `@Lazy`：

```java
@Controller
public class ChatController {

    private final MessageSender messageSender;

    public ChatController(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    @MessageMapping(value = "/ws/chat", messageType = MessageType.TEXT_MESSAGE)
    public void onText(String text, MessageSession session) {
        messageSender.sendTextToSession("/ws/chat", "echo: " + text, session.getSessionId());
    }
}
```

### 运行 Demo

```bash
./mvnw -pl demo-netty-web-spring-boot-starter -am spring-boot:run
```

Windows PowerShell 可使用：

```powershell
.\mvnw.cmd -pl demo-netty-web-spring-boot-starter -am spring-boot:run
```

启动后打开 `http://localhost:8080/`，demo 首页会列出 HTTP、WebSocket、JSON、crypto、health/status 的入口。

启用应用层 crypto demo：

```bash
./mvnw -pl demo-netty-web-spring-boot-starter -am spring-boot:run -Dspring-boot.run.profiles=crypto-demo
```

然后访问 `http://localhost:8080/ws/crypto-demo`。

启用握手鉴权 demo：

```bash
./mvnw -pl demo-netty-web-spring-boot-starter -am spring-boot:run -Dspring-boot.run.profiles=auth-demo
```

WebSocket 连接时需要携带 `?token=demo-token-2026` 参数或 `Authorization: Bearer demo-token-2026` 头。

### Micrometer / Actuator 指标

Demo 已集成 `spring-boot-starter-actuator`，启动后可在 `http://localhost:8081/actuator/metrics` 查看所有指标。Netty 运行时指标自动注册，包括：

- `netty.websocket.handshakes.total/success/rejected` — 握手计数
- `netty.websocket.messages.received/sent` — 消息收发计数
- `netty.websocket.sessions.closed` (tagged by `reason`) — 关闭计数
- `netty.websocket.sessions.active` — 活跃 session 数
- `netty.http.response.write.failures` 等 — HTTP 失败路径计数

业务项目只需引入 `micrometer-core`（或 `spring-boot-starter-actuator`），指标桥接自动激活，无需额外配置。

### 常见排障速查

- `WebSocket message uri "..." is not registered`：检查 `@MessageMapping` 的 URI 是否和发送侧一致，并确认 `server.netty.websocket.enable=true`。
- `No websocket mappings are currently registered`：应用引入了 websocket starter，但还没有任何 `@MessageMapping` 端点，或 WebSocket 装配被关闭。
- `target sessions are closed or missing`：目标 session 已断开或 id 过期，发送前可用 `MessageSender#getSessionIds(uri)` 或 `isSessionAlive()` 刷新状态。
- `Forbidden by origin`：浏览器 `Origin` 不在白名单里，补充 `server.netty.websocket.allowed-origins`。
- `Failed to deserialize websocket text payload`：handler 绑定 JSON 对象时入站文本不是目标结构，先改成 `String` 参数或补 `ON_ERROR` 查看坏 payload。
- `Unencrypted websocket frame rejected by crypto policy`：crypto 已启用但客户端仍发明文，按 AES-GCM envelope 发送，或用 `crypto.include-uris` / `exclude-uris` 做灰度。
- `Forbidden by handshake interceptor`：注册了 `WebSocketHandshakeInterceptor` Bean 但握手未通过验证，检查 token/header 是否正确传递。
- `Expected at most one WebSocketHandshakeInterceptor bean`：容器中存在多个 interceptor Bean，合并为一个或移除多余的。

## 生产部署建议

### 推荐配置参考

```properties
server.netty.port=8080

# --- HTTP 边界 ---
server.netty.http.max-content-length=1048576
server.netty.http.max-header-size=8192
server.netty.http.read-timeout-seconds=30
server.netty.http.write-timeout-seconds=30
server.netty.http.idle-timeout-seconds=60

# --- TLS（生产环境强烈建议启用） ---
server.netty.http.ssl.enable=true
server.netty.http.ssl.certificate=/path/to/server.crt
server.netty.http.ssl.certificate-key=/path/to/server.key
server.netty.http.ssl.protocols=TLSv1.2,TLSv1.3

# --- WebSocket ---
server.netty.websocket.enable=true
server.netty.websocket.max-connections=10000
server.netty.websocket.heartbeat-interval-seconds=30
server.netty.websocket.heartbeat-timeout-seconds=90
server.netty.websocket.allowed-origins=https://yourdomain.com

# --- 线程池（根据 CPU 核数和业务负载调整） ---
server.netty.websocket.handler-core-pool-size=8
server.netty.websocket.handler-max-pool-size=32
server.netty.websocket.handler-queue-capacity=256
server.netty.websocket.handler-permit-limit=64

# --- 可观测 ---
server.netty.management.enable=true
```

### 线程池调优

| 配置项 | 默认值 | 建议 |
| --- | --- | --- |
| `handler-core-pool-size` | `max(2, CPU)` | IO 密集型业务可适当调高 |
| `handler-max-pool-size` | `max(core, CPU*2)` | 突发流量缓冲，不宜设过大 |
| `handler-queue-capacity` | `0`（同步移交） | 有队列可平滑突发，但增加延迟 |
| `handler-permit-limit` | `max*2` | 控制同时在途请求数，防止 OOM |

当线程池满时，handler 会抛出 `RejectedExecutionException` 并关闭对应 channel。如果频繁出现拒绝，优先检查 handler 中是否有阻塞 IO 或长耗时操作。

### 可观测接入

**内置管理端点**（无需额外依赖）：

- `GET /netty/health` — 健康检查
- `GET /netty/status` — 运行时快照（handler 线程池、HTTP 失败路径、WebSocket 事件计数器）

**Micrometer / Actuator**（推荐）：

引入 `spring-boot-starter-actuator`，Netty 运行时指标自动注册到 `MeterRegistry`，可直接对接 Prometheus、Grafana、Datadog 等监控系统。核心指标前缀：`netty.websocket.*`、`netty.http.*`。

### 握手鉴权

实现 `WebSocketHandshakeInterceptor` 接口并注册为 Spring Bean：

```java
@Component
public class TokenHandshakeInterceptor implements WebSocketHandshakeInterceptor {
    @Override
    public boolean beforeHandshake(FullHttpRequest request, String uri) {
        String token = request.headers().get("Authorization");
        return tokenService.isValid(token);
    }

    @Override
    public String rejectionReason() {
        return "Invalid or missing token";
    }
}
```

拦截器在 Origin 校验之后、`ON_HANDSHAKE` 回调之前执行。返回 `false` 会以 HTTP 403 拒绝连接。

## 当前阶段

- **当前推荐版本：`1.3.0`**（P6 可观测与运维能力正式版）。
- `P0` 至 `P6` 全部阶段已完成，项目进入质量深化与产品化阶段。
- `P6` 已完成：关闭原因维度化（`CloseReason` 枚举 + `WebSocketEventRecorder`）、握手鉴权扩展点（`WebSocketHandshakeInterceptor`）、Micrometer 指标桥接（`NettyWebSocketMeterBinder` + `NettyHttpMeterBinder`）、生产部署建议文档。
- 下一步：`1.3.1` 代码质量深度治理（遗留代码缺陷修复 + webmvc 测试补齐），`1.4.0` P7 Demo 与文档产品化。

## 文档

- [开发计划与阶段状态](docs/development-plan.md)
- [版本发布检查清单](docs/release-checklist.md)
- [1.2.1 发布说明](docs/release-notes-1.2.1.md)
- [1.2.2 发布说明](docs/release-notes-1.2.2.md)
- [1.2.3 发布说明](docs/release-notes-1.2.3.md)
- [1.3.0 发布说明](docs/release-notes-1.3.0.md)
- [依赖治理与供应链门禁](docs/dependency-governance.md)
- [Netty 配置说明](docs/netty-configuration.md)
- [WebSocket 配置说明](docs/websocket-configuration.md)

## 验证

- 推荐使用 Maven Wrapper：`./mvnw test`
- 当前 P4.1/P5 首批变更已在 `GraalVM JDK 17.0.11` + `Apache Maven 3.9.9` 环境完成全量 reactor `mvn test` 验证。
- P5 WebSocket 子链路已用 `-pl netty-spring-websocket -am test` 复验通过，demo/starter 依赖链已用 `-pl demo-netty-web-spring-boot-starter -am test` 复验通过。
- 启用 demo 的 `crypto-demo` Spring profile 后，可访问 `/ws/crypto-demo` 使用浏览器 WebCrypto 发送 AES-GCM envelope，验证 Network 面板密文与服务端明文 handler 的闭环。
- SBOM 与漏洞扫描使用显式 profile：`mvn -Psbom -DskipTests org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom`、`mvn -Pdependency-scan org.owasp:dependency-check-maven:12.2.1:aggregate`
- GitHub Actions `CI` workflow 已串起全量测试、SBOM artifact 和发布门禁 Dependency-Check；当前功能/稳定性版本暂不把安全扫描作为阻塞，企业安全发布前需要配置 `NVD_API_KEY` secret 并处理 Dependabot/扫描告警。

常用局部验证命令：

```bash
./mvnw -pl netty-spring-websocket -am test
./mvnw -pl demo-netty-web-spring-boot-starter -am test
./mvnw -pl netty-spring-boot-autoconfigure -am test
```

## 当前配置重点

- `server.netty.mvc.enable=false`：关闭 MVC mapping 装配。
- `server.netty.websocket.enable=false`：关闭 WebSocket mapping 和 `MessageSenderSupport` 自动装配。
- `server.netty.http.*`：新的 HTTP/file/gzip/ssl 配置子命名空间；旧的 `server.netty.gzip.*`、`server.netty.file-location` 等写法继续兼容。
- `server.netty.management.enable=true`：显式开启内置 health/status 管理端点，默认关闭。
- Spring Boot Starter 场景下，推荐业务侧构造注入 `MessageSender`，`MessageSenderSupport` 保留为兼容实现；用户自定义 `MessageSender` Bean 时默认 sender 会自动退让。
