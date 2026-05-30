# netty-spring

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

A Spring Boot integration for Netty, providing HTTP MVC and WebSocket capabilities with auto-configuration support. Built for developers who need high-performance networking with the convenience of Spring Boot.

### Features

- **HTTP MVC** — `@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PathVariable`, `@RequestParam`, `@ResponseBody`, `@RestController`
- **WebSocket** — `@MessageMapping` lifecycle (handshake, connect, message, close, error), JSON auto-binding, binary support, optional fragmented-message aggregation
- **MessageSender API** — broadcast, unicast, session management, JSON/text/binary convenience methods
- **Application-layer encryption** — pluggable AES-GCM crypto for WebSocket frames, URI/session-level policy control
- **Handshake authentication** — `WebSocketHandshakeInterceptor` extension point with Origin whitelisting
- **Observability** — built-in `/netty/health` and `/netty/status` endpoints, Micrometer metrics (connection/message/broadcast/latency distributions), Actuator `/actuator/health` indicator, and SLF4J MDC structured logging
- **Production-ready** — heartbeat, idle timeout, connection limits, thread pool tuning, TLS/SSL, GZIP compression

### Modules

| Module | Description |
| --- | --- |
| `netty-spring-web` | Core Netty bootstrap, channel init, HTTP dispatch |
| `netty-spring-webmvc` | MVC routing and parameter binding |
| `netty-spring-websocket` | WebSocket mapping, session management, message sending |
| `netty-spring-websocket-cluster` | *(v1.8.0)* Cluster SPI + Redis Pub/Sub transport |
| `netty-spring-boot-autoconfigure` | Shared auto-configuration for all starters |
| `netty-web-spring-boot-starter` | Combined HTTP MVC + WebSocket starter |
| `netty-webmvc-spring-boot-starter` | HTTP MVC only starter |
| `netty-websocket-spring-boot-starter` | WebSocket only starter |
| `netty-websocket-cluster-spring-boot-starter` | *(v1.8.0)* Cluster auto-configuration (opt-in) |
| `demo-netty-web-spring-boot-starter` | Demo application |

### Quick Start (5 minutes)

#### 1. Choose a Starter

| Scenario | Artifact | Notes |
| --- | --- | --- |
| HTTP MVC only | `netty-webmvc-spring-boot-starter` | `@RequestMapping`, `@GetMapping`, etc. |
| WebSocket only | `netty-websocket-spring-boot-starter` | `@MessageMapping`, `MessageSender` |
| HTTP + WebSocket | `netty-web-spring-boot-starter` | Both capabilities in one server |

#### 2. Add Maven Dependency

```xml
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-web-spring-boot-starter</artifactId>
    <version>1.8.0</version>
</dependency>
```

Available on Maven Central as `io.github.berrywang1996:*` (versions `1.4.0`, `1.6.2`, `1.7.0`, `1.8.0`). Earlier `com.github.berrywang1996:*` artifacts were only published to a private repository — migrate by changing the groupId in your `pom.xml`.

#### 3. Configure

```properties
server.netty.port=8080
```

#### 4. Write a Controller

```java
@Controller
public class ChatController {

    private final MessageSender messageSender;

    public ChatController(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    @MessageMapping(value = "/ws/chat", messageType = MessageType.TEXT_MESSAGE)
    public void onText(String text, MessageSession session) {
        messageSender.broadcastText("/ws/chat", text);
    }
}
```

#### 5. Run the Demo

```bash
# Linux / macOS
./mvnw -pl demo-netty-web-spring-boot-starter -am spring-boot:run

# Windows PowerShell
.\mvnw.cmd -pl demo-netty-web-spring-boot-starter -am spring-boot:run
```

Open `http://localhost:8080/` to see the demo cockpit with links to HTTP, WebSocket, JSON messaging, chat room, crypto, health/status, and metrics endpoints.

**Chat room demo:** Visit `http://localhost:8080/chat` to try the multi-user chat with join/leave notifications, online user list, broadcast and private messaging.

**Crypto demo:** Run with `--spring.profiles.active=crypto-demo` profile, then visit `/ws/crypto-demo`.

**Auth demo:** Run with `--spring.profiles.active=auth-demo` profile. WebSocket connections require `?token=demo-token-2026` or `Authorization: Bearer demo-token-2026` header.

### Metrics & Monitoring

#### Built-in Endpoints (no extra dependencies)

```properties
server.netty.management.enable=true
```

- `GET /netty/health` — Health check
- `GET /netty/status` — Runtime snapshot (thread pool stats, HTTP failure counts, WebSocket event counters)

#### Micrometer / Actuator (recommended)

Add `spring-boot-starter-actuator` to your project. Netty metrics are automatically bridged to `MeterRegistry`:

- `netty.websocket.handshakes.total/success/rejected` — Handshake counters
- `netty.websocket.messages.received/sent` — Message counters
- `netty.websocket.sessions.closed` (tagged by `reason`) — Close counters (one series per `CloseReason` enum value)
- `netty.websocket.sessions.active` + `.uri` (tagged by `uri`) — Active session gauges
- `netty.websocket.connection.duration` / `.message.size` / `.broadcast.fanout` / `.handler.latency` — Distribution metrics *(v1.7.0)*
- HTTP failure counters + handler thread-pool & Netty allocator gauges *(v1.7.0)*

Distribution metrics are routed to every bound registry, so they work with a `CompositeMeterRegistry`. No extra configuration needed — the bridge activates automatically when `micrometer-core` is on the classpath.

#### Actuator Health & Structured Logging *(v1.7.0)*

With `spring-boot-actuator` present, `/actuator/health` reports a `NettyServerHealthIndicator` (port, thread pool, connection permits — `UP`/`DOWN`).

Handler and WebSocket lifecycle code populates SLF4J **MDC** (`netty.requestId`, `netty.sessionId`, `netty.uri`, `netty.remoteAddr`). Add them to your log pattern — no code changes needed:

```
%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} [%X{netty.requestId}] [%X{netty.sessionId}] - %msg%n
```

### WebSocket Cluster *(v1.8.0)*

Scale WebSocket across multiple nodes with Redis Pub/Sub. Default is single-node mode (zero overhead). Enable cluster with one config flag:

```xml
<!-- Add the cluster starter alongside your existing starter -->
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-websocket-cluster-spring-boot-starter</artifactId>
    <version>1.8.0</version>
</dependency>
```

```properties
server.netty.websocket.cluster.enable=true
# Use a DEDICATED, network-isolated Redis with auth + TLS in production:
server.netty.websocket.cluster.redis.uri=rediss://:password@your-redis:6379
```

**Zero business code changes** — `MessageSender` automatically switches to `ClusterMessageSender` with cross-node broadcast and unicast.

> ⚠️ **Security:** Redis is the cluster control plane — anyone who can `PUBLISH` to it can inject into or close any session. Use a dedicated, network-isolated, password-protected, TLS Redis. Application-layer AES-GCM does **not** extend across Redis (plaintext is fanned out to remote nodes). See [Cluster Design §Security](docs/cluster-design.md).

#### Performance Benchmarks

Tested with Redis 7.4.9, JDK 17, Docker Desktop, zero-dependency `SimpleTextEnvelopeCodec`:

| Scenario | Throughput | Latency | Notes |
| --- | --- | --- | --- |
| Local broadcast (no Redis) | **1,808,057 msg/s** | **0.6 µs** | Baseline — pure in-memory |
| Raw Redis Pub/Sub | **16,281 msg/s** | **61 µs** | Lettuce PUBLISH→SUBSCRIBE E2E |
| Two-node cross-node broadcast | **~14,000 msg/s** | **77 µs** | Node A → Redis → Node B |

#### When to Use Cluster vs Single-Node

| Dimension | Single-Node (default) | Cluster (`cluster.enable=true`) |
| --- | --- | --- |
| Use case | ≤ 1 server, ≤ 25k connections | Multiple servers, horizontal scaling |
| Broadcast latency | ~0.6 µs (in-memory) | ~77 µs (via Redis) |
| Throughput ceiling | ≥ 1.8M msg/s | ~14k msg/s cross-node (Redis bound) |
| Extra dependency | None | Redis 7+ |
| Failure impact | Process dies = all disconnected | One node dies = only that node's users disconnect |
| Config cost | Zero | One line + Redis address |

#### Capacity Planning

| Target Connections | Nodes | Recommended Redis | Cluster Broadcast Ceiling |
| --- | --- | --- | --- |
| ≤ 25k | 1 (single-node) | Not needed | ≥ 1.8M msg/s |
| 25k – 75k | 2–3 | Standalone or Sentinel | ~14k msg/s |
| 75k – 250k | 4–10 | Sentinel (recommended) | ~14k msg/s (single primary) |
| > 250k | > 10 | Need SPI switch (NATS/mesh) | Beyond Redis Pub/Sub |

#### Pluggable Serialization

All serialization is SPI-based — **zero Jackson dependency** in the cluster module. Override any layer with a Spring `@Bean`:

```java
// Custom envelope format (e.g. Protobuf)
@Bean
public EnvelopeCodec envelopeCodec() { return new MyProtobufEnvelopeCodec(); }

// Custom message body format
@Bean
public MessagePayloadCodec messagePayloadCodec() { return new MyProtobufPayloadCodec(); }
```

### Production Deployment

#### Recommended Configuration

```properties
server.netty.port=8080

# --- HTTP limits ---
server.netty.http.max-content-length=1048576
server.netty.http.max-header-size=8192
server.netty.http.read-timeout-seconds=30
server.netty.http.write-timeout-seconds=30
server.netty.http.idle-timeout-seconds=60

# --- TLS (strongly recommended for production) ---
server.netty.http.ssl.enable=true
server.netty.http.ssl.certificate=/path/to/server.crt
server.netty.http.ssl.certificate-key=/path/to/server.key
server.netty.http.ssl.protocols=TLSv1.2,TLSv1.3

# --- WebSocket ---
server.netty.websocket.max-connections=10000
server.netty.websocket.heartbeat-interval-seconds=30
server.netty.websocket.heartbeat-timeout-seconds=90
server.netty.websocket.allowed-origins=https://yourdomain.com

# --- Thread pool (adjust based on CPU cores and workload) ---
server.netty.websocket.handler-core-pool-size=8
server.netty.websocket.handler-max-pool-size=32
server.netty.websocket.handler-queue-capacity=256
server.netty.websocket.handler-permit-limit=64

# --- Observability ---
server.netty.management.enable=true
```

#### Thread Pool Tuning

| Property | Default | Guideline |
| --- | --- | --- |
| `handler-core-pool-size` | `max(2, CPU)` | Increase for IO-heavy handlers |
| `handler-max-pool-size` | `max(core, CPU*2)` | Burst buffer; don't set too high |
| `handler-queue-capacity` | `0` (synchronous handoff) | Queue smooths bursts but adds latency |
| `handler-permit-limit` | `max*2` | Caps in-flight requests to prevent OOM |

When the thread pool is full, the handler throws `RejectedExecutionException` and closes the channel. If rejections occur frequently, check for blocking IO or long-running operations in your handlers.

#### Handshake Authentication

Implement `WebSocketHandshakeInterceptor` and register as a Spring Bean:

```java
@Component
public class TokenInterceptor implements WebSocketHandshakeInterceptor {
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

The interceptor runs after Origin check but before `@MessageMapping(ON_HANDSHAKE)`. Returning `false` sends HTTP 403.

### Troubleshooting

| Symptom | Cause & Solution |
| --- | --- |
| `WebSocket message uri "..." is not registered` | No `@MessageMapping` for this URI, or `server.netty.websocket.enable=false` |
| `No websocket mappings are currently registered` | WebSocket starter is present but no `@MessageMapping` endpoints exist |
| `target sessions are closed or missing` | Target session disconnected; use `isSessionAlive()` to check before sending |
| `Forbidden by origin` | Browser Origin not in whitelist; add to `server.netty.websocket.allowed-origins` |
| `Failed to deserialize websocket text payload` | Inbound text doesn't match target POJO; use `String` parameter or add `ON_ERROR` handler |
| `Unencrypted websocket frame rejected` | Crypto enabled but client sends plaintext; use `crypto.include-uris` for gradual rollout |
| `Forbidden by handshake interceptor` | `WebSocketHandshakeInterceptor` rejected the connection; check token/header |

### Configuration Quick Reference

| Property | Default | Description |
| --- | --- | --- |
| `server.netty.port` | `8080` | Server port |
| `server.netty.mvc.enable` | `true` | Enable HTTP MVC |
| `server.netty.websocket.enable` | `true` | Enable WebSocket |
| `server.netty.http.ssl.enable` | `false` | Enable TLS |
| `server.netty.http.gzip.enable` | `false` | Enable GZIP compression |
| `server.netty.management.enable` | `false` | Enable built-in health/status endpoints |
| `server.netty.websocket.allowed-origins` | (all) | Origin whitelist (comma-separated) |
| `server.netty.websocket.max-connections` | `0` (unlimited) | Max WebSocket connections |
| `server.netty.websocket.max-frame-aggregation-buffer-size` | `0` (disabled) | Aggregate fragmented frames up to N bytes (v1.7.0) |
| `server.netty.websocket.heartbeat-interval-seconds` | `0` (disabled) | Server ping interval |
| `server.netty.websocket.heartbeat-timeout-seconds` | `0` (disabled) | Inbound frame timeout |
| `server.netty.websocket.broadcast-mode` | `EVENT_LOOP_DIRECT` | `EVENT_LOOP_DIRECT` (v1.6+ zero-copy) or `THREAD_POOL_LEGACY` (v1.5.x compat) |
| `server.netty.websocket.crypto.enable` | `false` | Application-layer WebSocket frame encryption |

Full configuration reference: [API Usage Guide](docs/api-guide.md#11-configuration-reference)

### Current Status

- **Current recommended version: `1.8.0`** (WebSocket cluster support via Redis Pub/Sub + 5-layer SPI architecture + 291 tests). Single-node mode is production-grade and unchanged from 1.7.x; cluster mode targets ≤~10 nodes with a dedicated, secured Redis — see [Cluster Design §Security](docs/cluster-design.md).
- `1.7.0` delivered, across four work streams: Micrometer metrics expansion (connection/message/broadcast/latency distributions, per-URI & thread-pool & allocator gauges), SLF4J MDC structured logging, an Actuator `/actuator/health` indicator, optional WebSocket fragmented-message aggregation, and 6 audited legacy defect fixes — all preserved in 1.8.0 and backward compatible
- Milestones P0 through P7 are all complete; performance (1.6.x), security/stability (1.6.2) and observability (1.7.0) hardening followed
- Next: `1.8.0` Redis Pub/Sub clustering; later `2.0.0` Spring Boot 3.x / Jakarta migration + enterprise security

### Documentation

- [API Usage Guide](docs/api-guide.md) — Complete integration guide with code examples
- [Netty Configuration](docs/netty-configuration.md) — HTTP / TLS / management endpoint reference
- [WebSocket Configuration](docs/websocket-configuration.md) — WebSocket runtime, crypto, observability
- [Release Notes - 1.8.0](docs/release-notes-1.8.0.md) — Current recommended version (cluster)
- [Release Notes - 1.7.1](docs/release-notes-1.7.1.md)
- [Release Notes - 1.7.0](docs/release-notes-1.7.0.md)
- [Development Plan](docs/development-plan.md) — Roadmap (1.8.0 cluster, 2.0.0 Spring Boot 3.x)
- [Cluster Design](docs/cluster-design.md) — Redis Pub/Sub cluster architecture, 1.8.0 scope vs roadmap, and the security/trust model
- [Release Checklist](docs/release-checklist.md) — Release process & gates
- [Dependency Governance](docs/dependency-governance.md) — SBOM, vulnerability scanning
- Older release notes: see `docs/release-notes-*.md`

### Testing

```bash
# Full reactor test (all 11 modules)
./mvnw test

# Test a specific module
./mvnw -pl netty-spring-websocket -am test
./mvnw -pl demo-netty-web-spring-boot-starter -am test
./mvnw -pl netty-spring-boot-autoconfigure -am test
```

Verified on `GraalVM JDK 17.0.11` + `Apache Maven 3.9.9`. CI workflow runs full test suite, SBOM generation, and dependency check gate.

### License

[Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)

---

<a id="中文"></a>

## 中文

基于 Netty 的 Spring Boot 集成框架，提供 HTTP MVC 和 WebSocket 能力，支持自动装配。为需要高性能网络通信且希望保留 Spring Boot 开发便利性的开发者而构建。

### 核心能力

- **HTTP MVC** — `@RequestMapping`、`@GetMapping`、`@PostMapping`、`@PathVariable`、`@RequestParam`、`@ResponseBody`、`@RestController`
- **WebSocket** — `@MessageMapping` 生命周期（握手、连接、消息、关闭、错误），JSON 自动绑定，二进制消息支持，可选分片消息聚合
- **MessageSender API** — 广播、单播、会话管理，JSON/文本/二进制便捷方法
- **应用层加密** — 可插拔 AES-GCM WebSocket 帧加密，支持 URI/会话级别策略控制
- **握手鉴权** — `WebSocketHandshakeInterceptor` 扩展点 + Origin 白名单
- **可观测性** — 内置 `/netty/health` 和 `/netty/status` 端点，Micrometer 指标（连接/消息/广播/延迟分布）、Actuator `/actuator/health` 健康检查、SLF4J MDC 结构化日志
- **生产就绪** — 心跳检测、空闲超时、连接数限制、线程池调优、TLS/SSL、GZIP 压缩

### 模块概览

| 模块 | 说明 |
| --- | --- |
| `netty-spring-web` | Netty 启动、通道初始化、HTTP 请求分发 |
| `netty-spring-webmvc` | MVC 路由与参数绑定 |
| `netty-spring-websocket` | WebSocket 映射、会话管理、消息发送 |
| `netty-spring-websocket-cluster` | *(v1.8.0)* 集群 SPI + Redis Pub/Sub 传输 |
| `netty-spring-boot-autoconfigure` | Starter 共用自动装配骨架 |
| `netty-web-spring-boot-starter` | HTTP MVC + WebSocket 组合 Starter |
| `netty-webmvc-spring-boot-starter` | 仅 HTTP MVC 的 Starter |
| `netty-websocket-spring-boot-starter` | 仅 WebSocket 的 Starter |
| `netty-websocket-cluster-spring-boot-starter` | *(v1.8.0)* 集群自动装配（按需开启） |
| `demo-netty-web-spring-boot-starter` | 示例工程 |

### 5 分钟快速开始

#### 1. 选择 Starter

| 使用场景 | 推荐依赖 | 说明 |
| --- | --- | --- |
| 只需要 HTTP MVC | `netty-webmvc-spring-boot-starter` | `@RequestMapping`、`@GetMapping` 等 |
| 只需要 WebSocket | `netty-websocket-spring-boot-starter` | `@MessageMapping`、`MessageSender` |
| HTTP + WebSocket | `netty-web-spring-boot-starter` | 两种能力合并在同一个服务器 |

#### 2. 引入 Maven 依赖

```xml
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-web-spring-boot-starter</artifactId>
    <version>1.8.0</version>
</dependency>
```

Maven Central 上以 `io.github.berrywang1996:*` 提供（版本 `1.4.0`、`1.6.2`、`1.7.0`、`1.8.0`）。早期 `com.github.berrywang1996:*` 仅发布到私有仓库——迁移时只需把 `pom.xml` 里的 groupId 改成新的即可。

#### 3. 配置

```properties
server.netty.port=8080
```

#### 4. 编写 Controller

```java
@Controller
public class ChatController {

    private final MessageSender messageSender;

    public ChatController(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    @MessageMapping(value = "/ws/chat", messageType = MessageType.TEXT_MESSAGE)
    public void onText(String text, MessageSession session) {
        messageSender.broadcastText("/ws/chat", text);
    }
}
```

业务侧推荐按接口注入 `MessageSender`，不需要显式 `@Lazy`。

#### 5. 运行 Demo

```bash
# Linux / macOS
./mvnw -pl demo-netty-web-spring-boot-starter -am spring-boot:run

# Windows PowerShell
.\mvnw.cmd -pl demo-netty-web-spring-boot-starter -am spring-boot:run
```

启动后打开 `http://localhost:8080/`，demo 首页列出了 HTTP、WebSocket、JSON 消息、聊天室、加密演示、health/status、指标监控等入口。

**聊天室 demo：** 访问 `http://localhost:8080/chat`，体验多用户聊天——加入/离开通知、在线用户列表、广播消息和私聊功能。

**加密 demo：** 使用 `--spring.profiles.active=crypto-demo` 启动，然后访问 `/ws/crypto-demo`。

**鉴权 demo：** 使用 `--spring.profiles.active=auth-demo` 启动。WebSocket 连接需要 `?token=demo-token-2026` 参数或 `Authorization: Bearer demo-token-2026` 头。

### 指标与监控

#### 内置管理端点（无需额外依赖）

```properties
server.netty.management.enable=true
```

- `GET /netty/health` — 健康检查
- `GET /netty/status` — 运行时快照（线程池状态、HTTP 失败路径计数、WebSocket 事件计数器）

#### Micrometer / Actuator（推荐）

引入 `spring-boot-starter-actuator`，Netty 运行时指标自动注册到 `MeterRegistry`：

- `netty.websocket.handshakes.total/success/rejected` — 握手计数
- `netty.websocket.messages.received/sent` — 消息收发计数
- `netty.websocket.sessions.closed`（按 `reason` 标签分维度）— 关闭计数（每个 `CloseReason` 枚举值一条序列）
- `netty.websocket.sessions.active` + `.uri`（按 `uri` 标签）— 活跃 session 数 Gauge
- `netty.websocket.connection.duration` / `.message.size` / `.broadcast.fanout` / `.handler.latency` — 分布指标 *(1.7.0)*
- HTTP 失败计数 + handler 线程池 & Netty allocator 内存 Gauge *(1.7.0)*

分布指标会写入每个已绑定的 registry，可与 `CompositeMeterRegistry` 共存。无需额外配置——当 classpath 中存在 `micrometer-core` 时，桥接自动激活。

#### Actuator 健康检查与结构化日志 *(1.7.0)*

引入 `spring-boot-actuator` 后，`/actuator/health` 会包含 `NettyServerHealthIndicator`（端口、线程池、连接许可——`UP`/`DOWN`）。

handler 与 WebSocket 生命周期会写入 SLF4J **MDC**（`netty.requestId`、`netty.sessionId`、`netty.uri`、`netty.remoteAddr`）。在日志 pattern 中引用即可，无需改业务代码：

```
%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} [%X{netty.requestId}] [%X{netty.sessionId}] - %msg%n
```

### WebSocket 集群 *(v1.8.0)*

通过 Redis Pub/Sub 实现跨节点广播和单播。默认单机模式零开销；一个配置开关即可启用集群：

```xml
<!-- 在已有 starter 旁加入集群 starter -->
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-websocket-cluster-spring-boot-starter</artifactId>
    <version>1.8.0</version>
</dependency>
```

```properties
server.netty.websocket.cluster.enable=true
# 生产环境用专用、网络隔离、带认证 + TLS 的 Redis：
server.netty.websocket.cluster.redis.uri=rediss://:password@your-redis:6379
```

**业务代码零改动** — `MessageSender` 自动切换为 `ClusterMessageSender`，跨节点广播和单播即刻生效。

> ⚠️ **安全**：Redis 是集群控制平面——任何能向它 `PUBLISH` 的人都能注入/关闭任意会话。生产必须用专用、网络隔离、带密码 + TLS 的 Redis。应用层 AES-GCM **不**延伸过 Redis（明文会扇出到远端节点）。详见 [集群方案设计 §安全模型](docs/cluster-design.md)。

#### 性能基准

Redis 7.4.9 / JDK 17 / Docker / 零依赖 SimpleTextEnvelopeCodec：

| 场景 | 吞吐量 | 延迟 | 说明 |
| --- | --- | --- | --- |
| 本地广播（无 Redis） | **1,808,057 msg/s** | **0.6 µs** | 基线——纯内存 |
| Raw Redis Pub/Sub | **16,281 msg/s** | **61 µs** | Lettuce PUBLISH→SUBSCRIBE 端到端 |
| 双节点跨节点广播 | **~14,000 msg/s** | **77 µs** | 节点 A → Redis → 节点 B |

#### 选型建议

| 维度 | 单机（默认） | 集群（`cluster.enable=true`） |
| --- | --- | --- |
| 适用场景 | ≤ 1 台服务器、≤ 25k 连接 | 多台服务器水平扩展 |
| 广播延迟 | ~0.6 µs（纯内存） | ~77 µs（经 Redis） |
| 吞吐上限 | ≥ 180 万 msg/s | ~14k msg/s 跨节点（Redis 瓶颈） |
| 额外依赖 | 无 | Redis 7+ |
| 故障影响 | 进程死 = 全断 | 一个节点死 = 仅该节点用户断 |

#### 容量规划

| 目标连接 | 节点数 | 推荐 Redis | 集群广播上限 |
| --- | --- | --- | --- |
| ≤ 25k | 1（单机） | 不需要 | ≥ 180 万/s |
| 25k – 75k | 2–3 | Standalone / Sentinel | ~14k/s |
| 75k – 250k | 4–10 | Sentinel（推荐） | ~14k/s（单主限制） |
| > 250k | > 10 | 需 SPI 切换（NATS/mesh） | 超出 Redis Pub/Sub 范围 |

#### 可插拔序列化

集群模块**零 Jackson 依赖**，所有序列化均通过 SPI 可替换：

```java
@Bean
public EnvelopeCodec envelopeCodec() { return new MyProtobufEnvelopeCodec(); }

@Bean
public MessagePayloadCodec messagePayloadCodec() { return new MyProtobufPayloadCodec(); }
```

### 生产部署建议

#### 推荐配置参考

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

#### 线程池调优

| 配置项 | 默认值 | 建议 |
| --- | --- | --- |
| `handler-core-pool-size` | `max(2, CPU)` | IO 密集型业务可适当调高 |
| `handler-max-pool-size` | `max(core, CPU*2)` | 突发流量缓冲，不宜设过大 |
| `handler-queue-capacity` | `0`（同步移交） | 有队列可平滑突发，但增加延迟 |
| `handler-permit-limit` | `max*2` | 控制同时在途请求数，防止 OOM |

线程池满时会抛出 `RejectedExecutionException` 并关闭对应 channel。频繁出现拒绝时，优先检查 handler 中是否有阻塞 IO 或长耗时操作。

#### 握手鉴权

实现 `WebSocketHandshakeInterceptor` 接口并注册为 Spring Bean：

```java
@Component
public class TokenInterceptor implements WebSocketHandshakeInterceptor {
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

拦截器在 Origin 校验之后、`@MessageMapping(ON_HANDSHAKE)` 回调之前执行。返回 `false` 会以 HTTP 403 拒绝连接。

### 常见排障速查

| 现象 | 原因与解决方案 |
| --- | --- |
| `WebSocket message uri "..." is not registered` | 没有对应的 `@MessageMapping`，或 `server.netty.websocket.enable=false` |
| `No websocket mappings are currently registered` | 引入了 WebSocket Starter 但没有任何 `@MessageMapping` 端点 |
| `target sessions are closed or missing` | 目标 session 已断开；发送前用 `isSessionAlive()` 检查 |
| `Forbidden by origin` | 浏览器 Origin 不在白名单；补充 `server.netty.websocket.allowed-origins` |
| `Failed to deserialize websocket text payload` | 入站文本不匹配目标 POJO；先改成 `String` 参数或补 `ON_ERROR` handler |
| `Unencrypted websocket frame rejected` | 启用了 crypto 但客户端发送明文；用 `crypto.include-uris` 做灰度 |
| `Forbidden by handshake interceptor` | `WebSocketHandshakeInterceptor` 拒绝连接；检查 token/header |

### 配置速查表

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `server.netty.port` | `8080` | 服务端口 |
| `server.netty.mvc.enable` | `true` | 启用 HTTP MVC |
| `server.netty.websocket.enable` | `true` | 启用 WebSocket |
| `server.netty.http.ssl.enable` | `false` | 启用 TLS |
| `server.netty.http.gzip.enable` | `false` | 启用 GZIP 压缩 |
| `server.netty.management.enable` | `false` | 启用内置 health/status 端点 |
| `server.netty.websocket.allowed-origins` | （全部放行） | Origin 白名单（逗号分隔） |
| `server.netty.websocket.max-connections` | `0`（不限） | 最大 WebSocket 连接数 |
| `server.netty.websocket.max-frame-aggregation-buffer-size` | `0`（禁用） | 分片帧聚合缓冲区上限，单位字节（1.7.0） |
| `server.netty.websocket.heartbeat-interval-seconds` | `0`（禁用） | 服务端 ping 间隔 |
| `server.netty.websocket.heartbeat-timeout-seconds` | `0`（禁用） | 入站帧空闲超时 |
| `server.netty.websocket.broadcast-mode` | `EVENT_LOOP_DIRECT` | `EVENT_LOOP_DIRECT`（v1.6+ 零拷贝）或 `THREAD_POOL_LEGACY`（v1.5.x 兼容） |
| `server.netty.websocket.crypto.enable` | `false` | 应用层 WebSocket 帧加密 |

完整配置参考：[API 使用指南](docs/api-guide.md#11-configuration-reference)

### 当前阶段

- **当前推荐版本：`1.8.0`**（WebSocket 集群支持：Redis Pub/Sub 跨节点广播/单播 + 5 层 SPI 可插拔架构 + 291 个测试全绿）。单机模式生产级、与 1.7.x 完全一致；集群模式面向 ≤~10 节点 + 专用加密 Redis——见 [集群方案设计 §安全模型](docs/cluster-design.md)。
- `1.7.0` 按四刀交付：Micrometer 指标扩展（连接/消息/广播/延迟分布，分 URI、线程池、allocator 内存 Gauge）、SLF4J MDC 结构化日志、Actuator `/actuator/health` 健康检查、可选 WebSocket 分片消息聚合，以及 6 项经审计的遗留缺陷修复——这些能力在 `1.8.0` 中完整保留，全部向后兼容
- P0 至 P7 全部里程碑已完成；其后依次推进性能（1.6.x）、安全稳定性（1.6.2）、可观测性（1.7.0）加固
- 下一步：`1.8.0` Redis Pub/Sub 集群支持；之后 `2.0.0` Spring Boot 3.x / Jakarta 迁移 + 企业安全版本

### 文档

- [API 使用指南](docs/api-guide.md) — 完整接入指南，含代码示例
- [Netty 配置说明](docs/netty-configuration.md) — HTTP / TLS / 管理端点参考
- [WebSocket 配置说明](docs/websocket-configuration.md) — WebSocket 运行时、加密、可观测性
- [1.8.0 发布说明](docs/release-notes-1.8.0.md) — 当前推荐版本（集群）
- [1.7.1 发布说明](docs/release-notes-1.7.1.md)
- [1.7.0 发布说明](docs/release-notes-1.7.0.md)
- [开发计划与阶段状态](docs/development-plan.md) — 路线图（1.8.0 集群、2.0.0 Spring Boot 3.x）
- [集群方案设计](docs/cluster-design.md) — Redis Pub/Sub 集群架构、1.8.0 实现范围 vs 路线图、安全/信任模型
- [版本发布检查清单](docs/release-checklist.md) — 发布流程与门槛
- [依赖治理与供应链门禁](docs/dependency-governance.md) — SBOM、漏洞扫描
- 历史发布说明：见 `docs/release-notes-*.md`

### 验证

```bash
# 全量测试（11 个模块）
./mvnw test

# 测试指定模块
./mvnw -pl netty-spring-websocket -am test
./mvnw -pl demo-netty-web-spring-boot-starter -am test
./mvnw -pl netty-spring-boot-autoconfigure -am test
```

已在 `GraalVM JDK 17.0.11` + `Apache Maven 3.9.9` 环境完成全量验证。GitHub Actions CI workflow 串起全量测试、SBOM 生成和依赖检查门禁。

### 许可证

[Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)
