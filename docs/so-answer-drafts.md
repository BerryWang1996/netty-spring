# Stack Overflow 回答草稿（待你用自己的账号发布）

> 状态：2026-10-24 更新为 **2.0.0 发布候选 / Spring Boot 4.1.2 / JDK 17** 草稿；两篇均未对外发表。Java 片段已在补齐文中省略的 DTO、服务与目标变量后，以冻结候选依赖通过单机/双节点 Redis 本地验证；独立消费者的 Central 依赖解析仍待完成。
> 两篇依赖示例均须等待 [2.0.0 发布说明](release-notes-2.0.0.md)确认 Maven Central 可用，当前仅可先安装候选源码后本地测试。
> handler 必须以 `-parameters` 编译；Maven Compiler 配置 `<release>17</release>`、`<parameters>true</parameters>`，并按迁移说明保留应用 BOM 中 Jackson、SnakeYAML、成对 Logback 的安全覆盖。
> 题目热度、回答数和原始 API 核查来自 2026-08 历史快照；发布前复查当前问题与代码，不将这些数据视为当前事实。

## 发布须知（先读）

1. **披露声明已内置在每篇草稿里**（"Full disclosure: I'm the author..."）。SO 的自我推广规则要求披露利益关系，删掉会有封号风险，不要删。
2. 两篇都先等待 Central 确认和独立消费者依赖解析，再根据问题当前状态与回答价值选择是否发表；不因完成本地验证而记录已发布。
3. 回答以解决问题为主、库为辅——即使读者不用这个库，前半部分也应对他有用。不要加更多链接（每篇最多 2-3 个，已是最小集）。
4. 发布后**盯评论**：有人指出 API 细节错误就立刻更正（响应速度是单人维护库最强的信任信号）。
5. 不要用任何小号点赞（SO 有投票欺诈检测，会连坐封号）。
6. 本地复验覆盖两篇核心 Java 片段的真实装配、广播与定向发送；应用业务代码由夹具补齐，不等于所有代码块可独立复制运行。发布前仍须核对 Central 取得的 2.0.0 制品和应用实际依赖。

---

## Answer 1 — Q42901062

- **链接**: https://stackoverflow.com/questions/42901062
- **标题**: Spring websockets without STOMP and SockJs but with message broker and routing support
- **数据（2026-08 快照）**: 2017 年提问，score 8，2,381 views，**0 answers**；当前状态待复查。
- **提问者要什么**: 专有协议跑在标准 Text WebSocket 上（不能上 STOMP/SockJS），但要 Spring 的
  DI、每会话处理、以及"把一个端点收到的消息转发到另一端点的多个客户端"这种 broker/路由能力。
  他已试过 javax.websocket（太 hacky、没 DI）和 Spring `@MessageMapping`（被 STOMP 绑死）。

### 草稿正文（SO Markdown，直接粘贴）

````markdown
Two honest parts to this answer: what Spring itself can and cannot do here, and an alternative if it can't.

## What Spring proper offers

Spring's broker/routing abstraction (`@MessageMapping` / `@SendTo` / `enableSimpleBroker` / STOMP broker relay) is part of the *STOMP subprotocol* support. The `MessageChannel`/broker machinery is wired to STOMP destinations; there is no supported way to keep the broker abstraction while dropping STOMP. So within Spring proper you are left with exactly your option 3: a `TextWebSocketHandler` registered via `WebSocketConfigurer`, where DI works (the handler is a Spring bean) but routing/broadcasting is code you write yourself by iterating sessions.

Two smaller notes about your option-3 pain points:

- `registry.addHandler(handler, "/trade/{id}")` does not extract `{id}` for you even in Spring proper — URI-template variables are an MVC/STOMP feature, not a raw-WebSocket-handler feature. The usual move is a query parameter (`/trade?id=foo`) or parsing the path yourself.
- Per-connection handler instances exist via `PerConnectionWebSocketHandler`, but you lose singleton-bean DI that way; most people keep a singleton handler and key per-session state by `session.getId()`.

## An alternative: raw-WebSocket starter on Netty (no STOMP anywhere)

Full disclosure: I'm the author of [netty-spring](https://github.com/BerryWang1996/netty-spring), a Spring Boot starter that serves HTTP MVC and WebSocket from Netty. It covers this shape of problem — annotated raw WebSocket endpoints with Spring ergonomics and a built-in send/broadcast API, with no subprotocol imposed on you. Mapping your four requirements onto it:

**1. DI into handlers** — handlers are plain Spring beans:

```java
@Component
public class TradeHandler {

    private final MessageSender messageSender;
    private final TradeRepository repo;   // any Spring bean injects normally

    public TradeHandler(MessageSender messageSender, TradeRepository repo) {
        this.messageSender = messageSender;
        this.repo = repo;
    }
}
```

**2. Per-session handling** — the model is a singleton bean plus a `MessageSession` parameter injected into each handler method; you key per-session state by session id yourself (there's deliberately no STOMP-like session abstraction in the way):

```java
@MessageMapping(value = "/trade", messageType = MessageType.ON_CONNECTED)
public void onConnected(MessageSession session) {
    String id = session.getQueryParam("id");   // client connects to /trade?id=foo
    // headers via session.getHeader(...), full handshake request also available
}
```

**3. Re-transmitting to clients on another endpoint** — a `MessageSender` (inject it anywhere, also usable from HTTP controllers) does targeted sends and per-endpoint broadcasts; your proprietary wire format is just a `String`:

```java
@MessageMapping(value = "/trade", messageType = MessageType.TEXT_MESSAGE)
public void onMessage(String rawText, MessageSession session) {
    // parse your proprietary protocol however you like — nothing is imposed
    messageSender.broadcastText("/positions", transform(rawText)); // every client on /positions
    messageSender.sendText("/positions", payload, sessionIdA, sessionIdB); // targeted
}
```

If you *do* want JSON binding instead of raw text, declare the parameter as your DTO type and it's Jackson-deserialized automatically; for binary frames, a `BINARY_MESSAGE` handler takes `byte[]` (or `ByteBuf`, or the raw `BinaryWebSocketFrame`).

**4. Proprietary protocol over a standard text WebSocket** — the lifecycle is annotation-driven with no subprotocol: `ON_HANDSHAKE` (return `false` to reject the upgrade — this is where auth goes), `ON_CONNECTED`, `TEXT_MESSAGE`, `BINARY_MESSAGE`, `ON_PING` (auto-pong if unmapped), `ON_CLOSE`, `ON_ERROR`.

```xml
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-websocket-spring-boot-starter</artifactId>
    <version>2.0.0</version>
</dependency>
```

```
new WebSocket("ws://host:8080/trade?id=foo")   // plain browser WebSocket, nothing else
```

## Where this won't fit (so you don't waste time)

- It runs on its own Netty server (`server.netty.port`), not inside Tomcat — fine for a dedicated realtime service or a Netty-served app, wrong if the endpoint must live inside an existing servlet container.
- This draft targets the unpublished 2.0.0 candidate on Spring Boot 4.1.2 / JDK 17. Compile handlers with `-parameters` (Maven Compiler `<parameters>true</parameters>`); retain the application BOM security overrides documented in the 2.0.0 migration notes. Boot 3 compatibility is not promised.
- If you need per-connection handler *instances* rather than per-session state in a singleton, Spring's own `PerConnectionWebSocketHandler` remains the in-container route.
````

### 发布检查清单

- [x] 核心 Java 片段已在候选依赖下验证构造器注入、连接、跨端点广播和定向文本发送。
- [ ] 确认 2.0.0 Central 可用并验证独立消费者解析的依赖；更新正文中的候选状态。
- [ ] 用你的账号粘贴到 Q42901062 的回答框
- [ ] 预览确认代码块渲染正常（SO 的 ``` 围栏 + 语言标签）
- [ ] 保留披露声明
- [ ] 发布后 24h 内盯评论

---

## Answer 2 — Q26853745

- **链接**: https://stackoverflow.com/questions/26853745
- **标题**: Spring Websocket in a tomcat cluster
- **数据（2026-08 快照）**: 2014 年提问，score 33，**21,158 views**，3 answers（当时已被采纳的是 2015 年的
  Spring Session + STOMP broker relay 方案；41 分那篇讲清了 HTTP session ≠ WebSocket session，
  但**没有人提供可直接运行的多节点样例**——提问者明确要了 "a working sample"，这是切入点）。

### 草稿正文（SO Markdown，直接粘贴）

````markdown
The existing answers (external STOMP broker relay; the HTTP-session-vs-WebSocket-session distinction) are still the right mental model, so this answer adds what's changed since and — since the question asks for one — a *runnable* multi-node sample.

## Recap: the two sub-problems

1. **Who holds which connection.** A WebSocket session lives in exactly one JVM. Every node must be able to answer "which node holds session X / user Y" — that's a distributed session registry.
2. **Fan-out across nodes.** With `enableSimpleBroker`, subscriptions are in-memory per node, so a publish on node A never reaches a subscriber on node B. You either relay through an external broker (RabbitMQ/ActiveMQ STOMP relay, as in the other answers) or you run your own cross-node transport.

Note (per the higher-voted answer): putting the *HTTP* session in Redis does nothing for either of these.

## Option if you're not tied to STOMP

Full disclosure: I'm the author of [netty-spring](https://github.com/BerryWang1996/netty-spring), a Spring Boot starter (HTTP MVC + WebSocket on Netty) with an opt-in cluster mode that covers both sub-problems once it is enabled: a Redis-backed `SessionRegistry` (who-holds-what, with heartbeat + dead-node reconciliation) and a Redis Pub/Sub transport for cross-node broadcast/unicast.

Handler code doesn't change at all between single-node and clustered — you keep injecting the plain `MessageSender`:

```java
@MessageMapping(value = "/ws/chat", messageType = MessageType.TEXT_MESSAGE)
public void onMessage(ChatMessage msg, MessageSession session) {
    messageSender.broadcastJson("/ws/chat", msg);      // local fan-out + cross-node
    messageSender.sendJsonToSession("/ws/chat", pm, targetSessionId); // unicasts cross-node if needed
}
```

Clustering is switched on purely by configuration:

```xml
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-websocket-cluster-spring-boot-starter</artifactId>
    <version>2.0.0</version>
</dependency>
```

```properties
server.netty.websocket.cluster.enable=true
server.netty.websocket.cluster.redis.uri=redis://your-redis:6379
```

Local sending is attempted before cross-node publication; this ordering does not guarantee delivery during shutdown, backpressure, or connection failure. The cross-node copy is at-most-once by default (Redis Pub/Sub is fire-and-forget). If Redis is lost, the node degrades to local delivery after a grace period; a unicast to a session that no longer exists throws `MessageSessionClosedException`. Applications must still handle rejected work and failed writes.

## The working sample (what the question actually asked for)

The repo contains a two-node application behind nginx with Redis. Its smoke test verifies JSON broadcasts in both directions between clients on distinct nodes, including content and the sender's origin stamp. Run it from the candidate checkout; use the release tag only after it exists and Central is confirmed:

```bash
git clone --branch codex/2.0.0-spring-boot-4.1 https://github.com/BerryWang1996/netty-spring.git
cd netty-spring
./mvnw -pl demo-netty-web-spring-boot-starter -am package -DskipTests
docker compose -f docker-demo/docker-compose.yml up --build
```

Open `http://localhost:8080/chat` in two browser tabs — each tab shows which node serves it (`Node: node-a` / `Node: node-b`), and a message typed in one arrives in the other stamped with the sender's node id. The automated version:

```bash
cd docker-demo && npm install && node smoke.js
# exit 0 = two clients on DIFFERENT nodes, broadcasts verified in BOTH directions with correct origin stamps
```

## Where this won't fit

- It replaces Tomcat with Netty as the server — this is a different route from clustering Tomcat itself, not a patch for it. If you must stay on Tomcat + STOMP, the broker-relay answers above remain the correct approach.
- The Redis Pub/Sub transport is documented for roughly ≤ 10 nodes of active broadcast; it needs a dedicated, secured Redis (auth + TLS in production).
- This draft uses the unpublished 2.0.0 candidate / Spring Boot 4.1.2 / JDK 17. Compile handlers with `-parameters` and retain the application BOM security overrides in the migration notes. The core Java fragment has been exercised locally with candidate libraries; independent Central consumer resolution remains pending.
````

### 发布检查清单

- [x] 核心 Java 片段已在候选依赖下验证 DTO 绑定、双向 JSON 广播及跨节点定向 JSON。
- [ ] 核对 [2.0.0 发布说明](release-notes-2.0.0.md)中的 Central 可用性和独立消费者解析结果，再把候选 checkout/状态切换为实际发布标签。
- [ ] 用你的账号粘贴到 Q26853745 的回答框
- [ ] 预览确认代码块渲染正常
- [ ] 保留披露声明
- [ ] 发布后 24h 内盯评论（这题流量大，更可能收到质疑）

---

## 草稿依据（内部参考，不发布）

- 2026-08 的原稿基于 1.10.0；当前候选更新对照下列接口与配置，并于 2026-10-24 完成核心 Java 片段本地复验：
  - `@MessageMapping`/`MessageType`/`MessageSender`/`MessageSession` 签名 → netty-spring-websocket 模块
  - `server.netty.websocket.cluster.*` 全量配置表、`ClusterMessageSender` drop-in 语义、投递语义原文引用 → netty-spring-websocket-cluster + 集群 starter
  - docker-demo 命令、smoke.js 断言、Maven 坐标、Boot/JDK 版本 → docker-demo/ + 各 pom
- 不应宣传 `@AutowiredMessageSender` 为有效字段注入能力；使用 Spring 构造器注入。WS 路径变量和 Boot 3 兼容也不作承诺。
- 当前 `smoke.js` 检查双向 JSON 投递，不是延迟基准或可靠重放。可靠传输需按 Redis Streams/JetStream 的具体配置与保留窗口说明，不能从普通 Pub/Sub smoke 推导。
- 当前修订补充参数元数据与应用 BOM 要求，并删除本地消息“绝不丢”的过度承诺；题目数据和外部规则尚未刷新。
- 两篇教程与两篇回答共 6 个 Java 块，按原文抽取并补齐明确的应用支撑代码；JDK 17 的 `--release 17 -parameters` 编译与 8 个单机/双节点场景通过。12 个 JVM 正常退出，18 个 WebSocket 均以主动请求后的 1000 关闭；本地依赖来自冻结 GA 候选，未把该结果当作 Central 消费者 BOM 解析证据。
- Answer 2 所涉 optional-NATS 缺陷、修复与验证记录统一见 [1.10.1 发布说明](release-notes-1.10.1.md)。
