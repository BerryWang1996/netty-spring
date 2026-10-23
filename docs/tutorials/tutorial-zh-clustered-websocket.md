# 用一个 starter 给 Spring Boot 加上集群 WebSocket（以及它的诚实边界）

> 2026-10-24 更新的 **2.0.0 正式版 / Spring Boot 4.1.2 / JDK 17** 教程草稿。Java handler 已用 `-parameters` 编译，并在全新 Maven 缓存从 Central 解析的独立消费者中通过单机、双节点 Redis 的双向文本广播验证。Central 全部 68 份发布文件已公开核验，教程与回答共用的 8 场景、16 项检查通过，证据与范围见[发布说明](../release-notes-2.0.0.md)。本文尚未对外发表。

我是 netty-spring 的维护者。这个库为 Spring Boot 提供独立的 Netty HTTP/WebSocket 服务，并按需启用集群。本文从单机开始，配置双节点聊天，再介绍四个可选 IM 原语及投递、部署边界。

## 单机配置

先准备 **Spring Boot 4.1.2 / JDK 17** 应用。业务 handler 必须用 `javac -parameters` 编译；Maven 的 `maven-compiler-plugin` 应配置 `<release>17</release>` 和 `<parameters>true</parameters>`。Spring 7 不再从调试信息恢复参数名，本库会在启动时拒绝缺少参数元数据的 handler。应用还需按[迁移说明](../release-notes-2.0.0.md)保留 Jackson、SnakeYAML 和成对 Logback 的安全 BOM 覆盖，并核对实际依赖树，不能仅依赖 starter 的传递版本。

引入组合 starter（HTTP + WebSocket 一个包）：

```xml
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-web-spring-boot-starter</artifactId>
    <version>2.0.0</version>
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
    <version>2.0.0</version>
</dependency>
```

```properties
server.netty.websocket.cluster.enable=true
server.netty.websocket.cluster.redis.uri=rediss://:password@your-redis:6379
```

`MessageSender` 自动换成 `ClusterMessageSender`，上面的 `ChatController` 一行不改：`broadcastText` 现在跨节点扇出，`sendTextToSession` 路由到持有目标 session 的节点。

本地发送先于跨节点发布，但这个顺序不保证关闭、背压或连接失败时的消息必达，也不代表应用已经消费消息；调用方仍需处理拒绝、写失败和失效会话。跨节点广播默认 at-most-once（Redis Pub/Sub 发后即忘，广播瞬间离线的节点会丢，无回放）；Redis 掉线后节点经过宽限期降级为本地投递；单播到已消失的 session 会抛 `MessageSessionClosedException`。

## docker-demo：多节点证明

现有 [Docker demo](../../docker-demo/README.md) 使用两个节点 + nginx 负载均衡 + Redis。以下命令从已发布的 `v2.0.0` checkout 运行：

```bash
# 1) 先构建 demo jar（镜像要 COPY 它）
./mvnw -pl demo-netty-web-spring-boot-starter -am package -DskipTests
# 2) 起 Redis + 两个节点 + nginx LB
cd docker-demo && docker compose up --build
```

另开一个终端，从仓库根目录运行无头冒烟测试：

```bash
cd docker-demo && npm install && node smoke.js
```

`smoke.js` 确认两个客户端经 LB 落到**不同节点**，再检查**两个方向**的 JSON 广播、消息内容及发送方 `originNode`。这是投递冒烟测试，不是延迟基准、可靠重放或零停机证明；拓扑与覆盖范围以 Docker demo README 为准。

## 四个可选 IM 原语（各一个开关）

- **rooms**（`cluster.room.enable=true`）：`roomMessage(uri, room, msg)` 只定向持有该房间成员的节点，有界房间把扇出降到 N/k。热房间成员跨满所有节点时没有削减，那种场景请退回全局 `topicMessage`。
- **sendToUser**（`cluster.offline.enable=true`）：`sendToUser(userId, msg)` 按稳定用户身份投递，在线实时送、离线进每用户 Redis Stream 排队，重连 FIFO 回填。生产必须自己提供 `UserIdResolver`，默认实现读 `?userId=` 只是测试便利、不是身份认证。
- **presence**（`cluster.presence.enable=true`）：按用户跨设备聚合 `ONLINE`/`AWAY`/`OFFLINE`，带 `PRESENCE_CHANGE` 事件与崩溃路径权威 `→OFFLINE`。
- **mesh**（`cluster.mesh.enable=true`）：`MeshBroker` 走节点间直连 Netty TCP 取代 Redis Pub/Sub；Redis 仍承担注册表、心跳、发现和兴趣注册，带按兴趣路由的扇出削减。

## 诚实边界

这些是硬边界，我不会替你软化：

- Redis Pub/Sub 保留文档化约 ≤ 10 节点活跃广播的使用边界；这不是新测得的 2.0.0 容量，需按自身负载验证。
- 跨节点广播默认 at-most-once，不重放。
- 需要一台**专用、网络隔离、带认证 + TLS** 的 Redis，它承载集群控制消息。另按 [API 指南](../api-guide.md)配置 HMAC 严格校验与共享密钥；传输访问控制不能替代消息认证。
- mesh 的扇出削减**只在按兴趣分区的活跃受众上成立**；全局话题或高人口话题在随机负载均衡下仍会饱和整个集群。
- 2.0.0 使用 **Spring Boot 4.1.2 / JDK 17**，handler 编译需 `-parameters`；已发布 1.x 使用 Boot 2.7，不承诺 Boot 3 兼容。状态见[开发计划](../development-plan.md)。
- 它跑自己的 Netty 服务器（`server.netty.port`），**不在 Tomcat 里**。
- 不是完整 IM 平台：没有客户端 SDK、没有管理后台、没有撤回/回执/历史。

## 结论

仓库在 [github.com/BerryWang1996/netty-spring](https://github.com/BerryWang1996/netty-spring)，完整 API 见 `docs/api-guide.md`（§9 是集群）。本次消费者验证只覆盖核心 handler，不推导容量、TLS、所有可选功能或执行器过载时的正常关闭保证。Central 发布与独立消费者验证均已完成；本文尚未对外发表。
