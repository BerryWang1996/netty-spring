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

### 常见排障速查

- `WebSocket message uri "..." is not registered`：检查 `@MessageMapping` 的 URI 是否和发送侧一致，并确认 `server.netty.websocket.enable=true`。
- `No websocket mappings are currently registered`：应用引入了 websocket starter，但还没有任何 `@MessageMapping` 端点，或 WebSocket 装配被关闭。
- `target sessions are closed or missing`：目标 session 已断开或 id 过期，发送前可用 `MessageSender#getSessionIds(uri)` 或 `isSessionAlive()` 刷新状态。
- `Forbidden by origin`：浏览器 `Origin` 不在白名单里，补充 `server.netty.websocket.allowed-origins`。
- `Failed to deserialize websocket text payload`：handler 绑定 JSON 对象时入站文本不是目标结构，先改成 `String` 参数或补 `ON_ERROR` 查看坏 payload。
- `Unencrypted websocket frame rejected by crypto policy`：crypto 已启用但客户端仍发明文，按 AES-GCM envelope 发送，或用 `crypto.include-uris` / `exclude-uris` 做灰度。

## 当前阶段

- `P0` 工程基线：已完成。
- `P1` WebSocket 正确性修复：已完成。
- `P2` WebSocket 并发与稳定性：已完成，核心限流、线程池配置化、广播背压、错误链路统一、运行时统计、停机时 active session 优雅关闭、重复 start/stop 收口、广播/停机交叉回归、MessageSenderSupport 重启后缓存刷新与停机联动关闭已落地。
- 当前稳定版本包括 `1.0.2` 维护线、已发布的 `1.2.0` WebSocket 产品能力线、`1.2.1` 功能/稳定性版本和 `1.2.2` 用户体验版本；下一条主开发线将继续推进 P6/P7 的观测、demo 和文档体系。
- `P4` 已完成主要目标：mapping resolver 延迟获取 controller bean，移除业务侧对 `@Lazy MessageSenderSupport` 的依赖；抽出共用 `netty-spring-boot-autoconfigure` 模块，收敛三套 Starter 里重复的 `nettyServer + properties` 自动装配骨架；把 `MessageSenderSupport` 自动配置并回公共 autoconfigure，同时补上 `server.netty.mvc.enable` / `server.netty.websocket.enable` 开关，明确 starter 场景优先按 `MessageSender` 接口注入，并把 HTTP/file/gzip/ssl 配置收敛到 `server.netty.http.*` 且保留旧键兼容。
- `P4.1` 已继续推进生产准入硬化：静态文件根目录逃逸保护、HTTP 聚合/解码/超时边界配置化、TLS 证书/协议/套件配置、WebSocket Origin 白名单、MVC/静态文件写失败关闭、HTTP 失败路径运行时统计、内置 health/status 管理端点、更保守的 handler 默认线程/permit、handler/sender 线程池配置校验、Netty BOM 版本对齐、`netty-all` 依赖瘦身，以及 SBOM/Dependency-Check 供应链门禁入口。
- `P5` 首批能力已完成：`MessageSender` 新增会话查询快照 API，并提供 `sendToSession()` / `broadcast()` / `closeSession()` / `closeSessions()` 语义化入口；`MessageSession` 新增 URI、path、query 参数和 header 读取 API；消息 handler 可直接绑定 `String`、JSON 业务对象、`ByteBuf` 或 `byte[]`；发送侧新增 `JsonMessage`；P5.x 已完成 WebSocket 心跳和空闲断线第一刀治理，并接入默认关闭的应用层消息加密扩展点和内置 AES-GCM 首版实现；`1.2.1` 已补 URI/session 粒度 crypto 策略、密钥轮换示例、浏览器端加密 demo，并已开始 P6 轻量可观测第一刀，把 WebSocket mapping 数和活跃 session 数接入 `/netty/status`。

## 文档

- [开发计划与阶段状态](docs/development-plan.md)
- [版本发布检查清单](docs/release-checklist.md)
- [1.2.1 发布说明](docs/release-notes-1.2.1.md)
- [1.2.2 发布说明](docs/release-notes-1.2.2.md)
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
