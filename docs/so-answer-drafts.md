# Stack Overflow 回答草稿（待你用自己的账号发布）

> 状态：2026-08-03 起草，所有 API 签名/配置项/命令均已对照 1.10.0 代码树逐条核实；
> 同日对抗性核实后修订 4 处措辞（BinaryMessage 绑定目标、跨节点"往返"→单向投递、两处语气）。
> 发布的是**你的 SO 账号**（我这边没有也不应该有你的 SO 凭据）。
>
> Answer 2 的发布门槛与验证状态统一见 [1.10.1 发布说明](release-notes-1.10.1.md)。
> Maven Central 可用性确认前，不得照其依赖示例采用或发布 Answer 2。Answer 1 使用单机
> WebSocket starter，不受这项集群发布门槛影响。

## 发布须知（先读）

1. **披露声明已内置在每篇草稿里**（"Full disclosure: I'm the author..."）。SO 的自我推广规则要求披露利益关系，删掉会有封号风险，不要删。
2. **两篇不要同一天发**。先发 Q1（0 回答、竞争小，且不受 1.10.1 门槛约束）；Q2 等 1.10.1
   发布后再发（见 Answer 2 检查清单第 1 条）。
3. 回答以解决问题为主、库为辅——即使读者不用这个库，前半部分也应对他有用。不要加更多链接（每篇最多 2-3 个，已是最小集）。
4. 发布后**盯评论**：有人指出 API 细节错误就立刻更正（响应速度是单人维护库最强的信任信号）。
5. 不要用任何小号点赞（SO 有投票欺诈检测，会连坐封号）。
6. 草稿里所有代码都按真实签名构造，但发布前建议你把示例在 demo 模块里跑一遍做最后确认。

---

## Answer 1 — Q42901062

- **链接**: https://stackoverflow.com/questions/42901062
- **标题**: Spring websockets without STOMP and SockJs but with message broker and routing support
- **数据**: 2017 年提问，score 8，2,381 views，**0 answers**（至今无人回答）
- **提问者要什么**: 专有协议跑在标准 Text WebSocket 上（不能上 STOMP/SockJS），但要 Spring 的
  DI、每会话处理、以及"把一个端点收到的消息转发到另一端点的多个客户端"这种 broker/路由能力。
  他已试过 javax.websocket（太 hacky、没 DI）和 Spring `@MessageMapping`（被 STOMP 绑死）。

### 草稿正文（SO Markdown，直接粘贴）

```
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
    <version>1.10.0</version>
</dependency>
```

```
new WebSocket("ws://host:8080/trade?id=foo")   // plain browser WebSocket, nothing else
```

## Where this won't fit (so you don't waste time)

- It runs on its own Netty server (`server.netty.port`), not inside Tomcat — fine for a dedicated realtime service or a Netty-served app, wrong if the endpoint must live inside an existing servlet container.
- Spring Boot 2.7.x and JDK 17 today (2.0.0 targets Boot 4.1; it is not released and does not promise Boot 3 compatibility).
- If you genuinely need per-connection handler *instances* rather than per-session state in a singleton, Spring's own `PerConnectionWebSocketHandler` remains the in-container route.
```

### 发布检查清单

- [ ] 用你的账号粘贴到 Q42901062 的回答框
- [ ] 预览确认代码块渲染正常（SO 的 ``` 围栏 + 语言标签）
- [ ] 保留披露声明
- [ ] 发布后 24h 内盯评论

---

## Answer 2 — Q26853745

- **链接**: https://stackoverflow.com/questions/26853745
- **标题**: Spring Websocket in a tomcat cluster
- **数据**: 2014 年提问，score 33，**21,158 views**，3 answers（已被采纳的是 2015 年的
  Spring Session + STOMP broker relay 方案；41 分那篇讲清了 HTTP session ≠ WebSocket session，
  但**没有人提供可直接运行的多节点样例**——提问者明确要了 "a working sample"，这是切入点）。

### 草稿正文（SO Markdown，直接粘贴）

```
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
    <version>1.10.1</version>
</dependency>
```

```properties
server.netty.websocket.cluster.enable=true
server.netty.websocket.cluster.redis.uri=redis://your-redis:6379
```

Semantics, stated plainly (they're documented this way in the repo too): local delivery always happens first and is never lost; the cross-node copy is at-most-once by default (Redis Pub/Sub is fire-and-forget); if Redis is lost the node degrades to local delivery after a grace period instead of dropping connections; a unicast to a session that no longer exists anywhere throws `MessageSessionClosedException` so callers learn about stale references.

## The working sample (what the question actually asked for)

The repo ships a checked-in multi-node proof you can start yourself — two app nodes behind an nginx load balancer + Redis, plus a headless test that asserts cross-node delivery (a broadcast from one node reaches a session connected to the other, with the correct origin stamp):

```bash
git clone https://github.com/BerryWang1996/netty-spring.git
cd netty-spring
./mvnw -pl demo-netty-web-spring-boot-starter -am package -DskipTests
docker compose -f docker-demo/docker-compose.yml up --build
```

Open `http://localhost:8080/chat` in two browser tabs — each tab shows which node serves it (`Node: node-a` / `Node: node-b`), and a message typed in one arrives in the other stamped with the sender's node id. The automated version:

```bash
cd docker-demo && npm install && node smoke.js
# exit 0 = two clients on DIFFERENT nodes, broadcast delivered cross-node with correct origin stamp
```

## Where this won't fit

- It replaces Tomcat with Netty as the server — this is a different route from clustering Tomcat itself, not a patch for it. If you must stay on Tomcat + STOMP, the broker-relay answers above remain the correct approach.
- The Redis Pub/Sub transport is documented for roughly ≤ 10 nodes of active broadcast; it needs a dedicated, secured Redis (auth + TLS in production).
- Spring Boot 2.7.x / JDK 17 today.
```

### 发布检查清单

- [ ] 核对 [1.10.1 发布说明](release-notes-1.10.1.md)中的 Central 可用性与冒烟验证，满足本文顶部发布门槛。
- [ ] 用你的账号粘贴到 Q26853745 的回答框
- [ ] 预览确认代码块渲染正常
- [ ] 保留披露声明
- [ ] 发布后 24h 内盯评论（这题流量大，更可能收到质疑）

---

## 草稿依据（内部参考，不发布）

- 全部 API 事实来自 1.10.0 代码树提取（3 代理并行核实，含文件行号）：
  - `@MessageMapping`/`MessageType`/`MessageSender`/`MessageSession` 签名 → netty-spring-websocket 模块
  - `server.netty.websocket.cluster.*` 全量配置表、`ClusterMessageSender` drop-in 语义、投递语义原文引用 → netty-spring-websocket-cluster + 集群 starter
  - docker-demo 命令、smoke.js 断言、Maven 坐标、Boot/JDK 版本 → docker-demo/ + 各 pom
- 已知不可引用项（草稿已规避）：`@AutowiredMessageSender` 字段注入（注解存在但树中无处理器，
  疑似未接线——已另行报告）；WS 路径变量（不支持）；Boot 3 兼容（不承诺；2.0.0 目标为 Boot 4.1）。
- 投递语义措辞逐字取自 `ClusterMessageSender`/`ClusterBroker` javadoc。
- 修订记录（2026-08-03 对抗性核实）：① `BinaryMessage` 是发送侧包装器，二进制帧的真实绑定目标是
  `byte[]`/`ByteBuf`/`BinaryWebSocketFrame`（MessageMappingResolver:867–876）；② smoke.js 断言的是
  单向跨节点投递（含来源节点标记），不是"往返"；③ "built for exactly this shape of problem" 软化为
  "covers this shape of problem"；④ "solves both sub-problems with one property" 改为
  "once it is enabled"（启用集群实际要 enable + redis.uri 两项）；⑤ at-most-once 补 "by default"
  （可靠重投路径是 NATS JetStream 另一条传输）。
- Answer 2 所涉 optional-NATS 缺陷、修复与验证记录统一见 [1.10.1 发布说明](release-notes-1.10.1.md)。
