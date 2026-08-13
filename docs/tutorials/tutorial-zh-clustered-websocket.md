# 用一个 starter 给 Spring Boot 加上集群 WebSocket（以及它的诚实边界）

> ⚠️ 发布门槛：本文引用 1.10.1。已发布的 1.10.0 集群 starter 在无 jnats 的默认 classpath 上启动即崩（本地已修复，commit 15a25d7）。**1.10.1 上 Maven Central 之前不得发布本文。**

我是 netty-spring 的维护者。这个库做了一件很克制的事：给 Spring Boot 换一个 Netty 作为 HTTP/WebSocket 服务器，顺手把「多节点集群 WebSocket」压进一个 starter，同时把它的边界写在明面上。本文带你走一遍：单机 5 分钟跑通 → 一个开关开集群 → 多节点证明 → 四个可选 IM 原语 → 最后是它不骗你的边界。

## 单机：5 分钟上手

引入组合 starter（HTTP + WebSocket 一个包）：

```xml
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-web-spring-boot-starter</artifactId>
    <version>1.10.1</version>
</dependency>
```

配置一个端口：

```properties
server.netty.port=8080
```

写一个 controller。`@MessageMapping` 按 `MessageType` 挂生命周期回调，方法拿到 `MessageSession`：

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

跑起来，`ws://localhost:8080/ws/chat` 就能收发。`MessageSender` 可以注入任何地方（包括 HTTP controller），广播用 `broadcastText`/`broadcastJson`，单播用 `sendTextToSession`/`sendJsonToSession`。这一步没碰 Redis、没碰集群，纯内存扇出。

## 一个开关开集群（业务代码零改动）

集群是 opt-in 的。加一个 starter，开一个开关：

```xml
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-websocket-cluster-spring-boot-starter</artifactId>
    <version>1.10.1</version>
</dependency>
```

```properties
server.netty.websocket.cluster.enable=true
server.netty.websocket.cluster.redis.uri=rediss://:password@your-redis:6379
```

`MessageSender` 自动换成 `ClusterMessageSender`，上面的 `ChatController` 一行不改：`broadcastText` 现在跨节点扇出，`sendTextToSession` 路由到持有目标 session 的节点。

投递语义是它的品牌，得说清楚：本地投递永远先发生、绝不丢；跨节点广播默认 at-most-once（Redis Pub/Sub 发后即忘，广播瞬间离线的节点会丢，无回放）；Redis 掉线后节点经过一个宽限期降级为本地投递而不是断连；单播一个在所有节点都消失的 session 会抛 `MessageSessionClosedException` 给调用方。

## docker-demo：多节点证明

仓库里带了一个 `docker-demo/`，两个节点 + nginx 负载均衡 + Redis，可以直接跑：

```bash
# 1) 先构建 demo jar（镜像要 COPY 它）
./mvnw -pl demo-netty-web-spring-boot-starter -am package -DskipTests
# 2) 起 Redis + 两个节点 + nginx LB
cd docker-demo && docker compose up --build
# 3) 无头冒烟测试
cd docker-demo && npm install && node smoke.js
```

`smoke.js` 断言的是**单向跨节点投递 + 来源节点标记**：两个客户端经 LB 落到**不同**节点，从一个节点发广播，另一个节点收到、且 `originNode` 标记正确。注意它不是往返（round-trip）断言——没有回程校验，别把它叫成「双向」。

## 四个可选 IM 原语（各一个开关）

- **rooms**（`cluster.room.enable=true`）：`roomMessage(uri, room, msg)` 只定向持有该房间成员的节点，有界房间把扇出降到 N/k。热房间成员跨满所有节点时没有削减，那种场景请退回全局 `topicMessage`。
- **sendToUser**（`cluster.offline.enable=true`）：`sendToUser(userId, msg)` 按稳定用户身份投递，在线实时送、离线进每用户 Redis Stream 排队，重连 FIFO 回填。生产必须自己提供 `UserIdResolver`，默认实现读 `?userId=` 只是测试便利、不是身份认证。
- **presence**（`cluster.presence.enable=true`）：按用户跨设备聚合 `ONLINE`/`AWAY`/`OFFLINE`，带 `PRESENCE_CHANGE` 事件与崩溃路径权威 `→OFFLINE`。
- **mesh**（`cluster.mesh.enable=true`）：`MeshBroker` 走节点间直连 Netty TCP 取代 Redis Pub/Sub（Redis 只留发现），带按兴趣路由的扇出削减。

## 诚实边界

这些是硬边界，我不会替你软化：

- Redis Pub/Sub 传输文档化约 ≤ 10 个节点做活跃广播。
- 跨节点广播默认 at-most-once，不重放。
- 需要一台**专用、网络隔离、带认证 + TLS** 的 Redis——它是集群控制平面，任何能 `PUBLISH` 它的人都能注入或关闭会话。
- mesh 的扇出削减**只在按兴趣分区的活跃受众上成立**；全局话题或高人口话题在随机负载均衡下仍会饱和整个集群。
- 只支持 Spring Boot 2.7.x / JDK 17（Boot 3/4 是未来版本，尚未发布）。
- 它跑自己的 Netty 服务器（`server.netty.port`），**不在 Tomcat 里**。
- 不是完整 IM 平台：没有客户端 SDK、没有管理后台、没有撤回/回执/历史。

## 结论

单机零开销、集群一行配置、边界写在明面——这是我想给它的定位。仓库在 [github.com/BerryWang1996/netty-spring](https://github.com/BerryWang1996/netty-spring)，完整文档看 `docs/api-guide.md`（§9 是集群）。