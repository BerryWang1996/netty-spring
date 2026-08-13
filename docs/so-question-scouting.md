# 追加 Stack Overflow 选题清单（scout · 第二批）

> ⚠️ 发布门槛：本文引用 1.10.1。已发布的 1.10.0 集群 starter 在无 jnats 的默认 classpath 上启动即崩（本地已修复，commit 15a25d7）。**1.10.1 上 Maven Central 之前不得发布本文。**

## 范围与方法

- 本清单是 `docs/so-answer-drafts.md` 的**第二批补充**，已排除其中的 Q42901062 与 Q26853745 两题。
- 时间窗 **2022–2026**（区别于已草拟的两道 2014/2017 老题）；数据取自 Stack Exchange API，快照时间 **2026-08-13**。
- 筛选标准：**只有"原生 WebSocket / 集群 / 会话路由"类回答才成立**的题入选；诚实答案是"照 STOMP 修配置"的题（CORS、SockJS 路径、enum 序列化、`CONNECTED` 帧等）一律剔除。
- 全部 9 题均来自 `spring-websocket` / `spring-boot` / `websocket` 标签的高票与高流量池。每题的"角度"都标注了 **netty-spring 不适用**的情形。
- **诚实范围（本清单统一口径）**：Redis Pub/Sub 文档化约 **≤10 节点**活跃广播，需**专用、安全** Redis（auth+TLS）；mesh 扇出缩减**仅对兴趣分区的活跃受众真实有效**，全局或高人口主题在随机 LB 下仍会饱和整个集群；仅 **Spring Boot 2.7.x / JDK 17**（Boot 3 是未发布的 2.0.0）；跑自有 **Netty 服务器**（`server.netty.port`）而非 Tomcat 内；**无 IM 平台对等**（无客户端 SDK / 管理后台 / 撤回 / 回执 / 历史）。

## 候选总表

| # | 链接 | 标题 | 年份 | 流量（views / score / answers，2026-08-13） | 诚实角度（一句话） | 工作量 |
|---|------|------|------|------|------|--------|
| 1 | https://stackoverflow.com/questions/71020195/scaling-web-sockets-with-spring-boot | Scaling web sockets with Spring Boot | 2022 | 1,565 / 3 / **0** | 提问者**明确"纯 WebSocket 不用 STOMP"**要水平扩展 + 投递保证——正是 cluster 两大子问题（谁持有哪些连接 + 跨节点扇出），且 0 回答可占位。 | 中 |
| 2 | https://stackoverflow.com/questions/71748542/how-to-disconnect-a-stomp-client-session-from-spring | How to disconnect a Stomp client Session from Spring | 2022 | 2,626 / 6 / **0** | Spring STOMP **没有干净的服务端踢人 API**；netty-spring 的 `MessageSender.closeSession`/`closeSessions` 是显式会话管理。 | 中 |
| 3 | https://stackoverflow.com/questions/71422001/is-there-limit-in-websockets | Is there limit in websockets? | 2022 | 5,374 / 2 / 1 | "限制"分三种（单节点连接数 / 消息尺寸 / **跨节点广播扇出天花板**），逐条诚实拆开，正是仓库"诚实范围"口径。 | 短 |
| 4 | https://stackoverflow.com/questions/71501522/how-do-i-send-headers-in-the-initial-handshake-with-a-spring-websocket-server | How do I send headers in the initial handshake with a Spring websocket server? | 2022 | 8,128 / 2 / 2 | 浏览器**无法**自定义握手头；能传什么就用什么——`MessageSession.getQueryParam`/`getHeader` + `ON_HANDSHAKE` 返回 false 拒绝。 | 短 |
| 5 | https://stackoverflow.com/questions/71237812/how-to-disconnect-users-websocket-connection-from-server-when-the-jwt-access-to | How to disconnect users' websocket connection from server when the JWT access token expires using Spring Boot + Websockets? | 2022 | 1,665 / 4 / 1 | 把"连接时校验"与"会话中途过期"分开：`ON_HANDSHAKE` 拒绝 + `closeSession` 主动踢；**无内置 JWT 看门狗**需明说。 | 短 |
| 6 | https://stackoverflow.com/questions/76134623/cant-send-direct-message-to-user-using-spring-boot-websocket-stomp | Can't send direct message to user using Spring Boot Websocket Stomp | 2023 | 1,762 / 2 / 1 | STOMP 按用户私聊仪式感重（Principal/`/user` 前缀/`convertAndSendToUser`）；原生路线的 `sendJsonToSession`/`sendToUser` 显式寻址。 | 短/中 |
| 7 | https://stackoverflow.com/questions/78455991/how-to-send-messages-to-specific-user-from-service-layer-using-spring-boot-web-s | How to send messages to specific user from service layer using spring boot web socket | 2024 | 222 / 0 / **0** | 痛点正是"把身份从 `ChannelInterceptor` 传到 service 层"的 STOMP 仪式；netty-spring 的 `MessageSender` 是可直接注入任意 service/控制器的 bean。 | 短 |
| 8 | https://stackoverflow.com/questions/78568876/spring-data-redis-used-as-message-broker-for-websocket-with-angular-client | Spring Data Redis used as message broker for websocket with Angular client | 2024 | 646 / 1 / **0** | 纠偏"光有 Spring Data Redis 的 pub/sub 不够"——提问者已用它把消息扇出到各 pod，真正缺的是**分布式会话路由 + STOMP `/user` 寻址语义**；**但提问者在 Boot 3.2.4，netty-spring 今天不适用**——候选里唯一"暂不能用，等 2.0.0"的题。 | 短/中 |
| 9 | https://stackoverflow.com/questions/78564331/spring-boot-realtime-notification-with-websocket | spring boot realtime notification with WebSocket | 2024 | 588 / 0 / 1 | HTTP 业务事件（下单）推给指定用户：HTTP+WS 合体 starter 里把 `MessageSender` 注入处理下单的 controller/service 直接推。 | 短 |

## 逐题角度

**1. 71020195 — Scaling web sockets with Spring Boot（中）**
这是本批次**最强一题**：提问者原文写死"uses pure web sockets (without STOMP)"，要"客户端连到哪个副本都无所谓 + 投递有保证"，且**至今 0 回答**。回答把水平扩展拆成两个子问题——(a) 谁持有哪个连接（分布式会话注册表），(b) 跨节点扇出（内存 broker 只能广播到本节点）。然后展示 netty-spring 集群模式：`server.netty.websocket.cluster.enable=true` + `server.netty.websocket.cluster.redis.uri` 两项配置即可，`MessageSender` 被透明替换为 `ClusterMessageSender`（业务代码不变）；投递语义照抄仓库口径——本地投递先发生、从不丢；跨节点默认 at-most-once（Redis Pub/Sub 即发即弃）；要 at-least-once（且仅在保留窗口 `stream-max-len` 内）用 `reliableBroadcast`（Redis Streams）——需先开 `server.netty.websocket.cluster.reliable.enable=true`（默认关闭，未开启时调用抛 `IllegalStateException`）。**天花板照实写**：Redis Pub/Sub 约 ≤10 节点活跃广播、需专用安全 Redis（auth+TLS）；再附 `docker-demo/` 双节点 + nginx + Redis 的可运行证据（smoke.js 断言**单向**跨节点投递、带 originNode 标记，不是往返）。不适用：必须留在 Tomcat/STOMP 的团队走 broker-relay 路线。

**2. 71748542 — How to disconnect a Stomp client Session from Spring（中）**
提问者已知客户端能断开、找不到服务端主动踢人的干净 API，且点名"8 年前的旧帖太绕"。前半段诚实回答 STOMP 现状：Spring STOMP 没有一行的服务端强制断开，只能自己持有并关闭 session。后半段给原生替代：netty-spring 的 `MessageSender` 提供 `closeSession(uri, sessionId)` / `closeSessions(uri)`（可带自定义 close 状态码与 reason），并有 `getSessionIds(uri)` / `isSessionAlive(uri, ...)` 做会话盘点。不适用：若必须留在 STOMP，答案仍只能是自己追踪 session。

**3. 71422001 — Is there limit in websockets?（短）**
提问者要"推送通知"场景下的限制。回答分三层，全部用仓库诚实口径：单节点并发连接数受 OS fd / 线程模型约束（Netty 非阻塞事件循环比 Tomcat 默认线程-per-连接撑得住多得多的连接）；消息尺寸有自己的上限；**真正的天花板是跨节点广播**——Redis Pub/Sub 文档化约 ≤10 节点活跃广播，mesh 传输（`cluster.mesh.enable`）只在兴趣分区受众下真实缩减扇出，全局/高人口主题在随机 LB 下仍会饱和整个集群。这是把"诚实范围"章节直接翻译成 SO 回答。

**4. 71501522 — 握手时如何带 header（短）**
这是本批次**流量最高**的题（8,128 views）。诚实前提必须讲清：浏览器对 WS Upgrade 请求头**无控制权**，想带自定义值只能走 query 参数 / 子协议 / Cookie。然后落到库：连接侧能传的（query 参数、以及非浏览器客户端可控制的标准头），netty-spring 在 `MessageSession` 上暴露 `getQueryParam(...)` / `getHeader(...)`，且 `ON_HANDSHAKE` 回调能拿到完整握手请求、返回 false 即可拒绝升级（鉴权钩子）。不适用：需要"浏览器自定义握手头"这个能力本身不存在，换任何库都做不到。

**5. 71237812 — JWT 过期踢人（短）**
提问者已用 STOMP + RabbitMQ、token 走 query 参数。回答把需求拆成两段：连接时 token 已失效 → `ON_HANDSHAKE` 返回 false 直接拒绝升级（比"先连上再踢"干净）；会话中途过期 → 应用自己按 session 起定时器，到期调用 `MessageSender.closeSession`。**诚实免责**：netty-spring 不内置"JWT 过期看门狗"，中途过期的重校验与定时是应用逻辑，库只提供鉴权钩子 + 显式关闭。

**6. 76134623 — STOMP 私聊发不出去（短/中）**
提问者能发 topic、私聊失败，还做了端到端加密（暗示他未必需要 STOMP 的订阅模型）。前半段诊断 STOMP 私聊三坑（Principal 解析、`/user` 目标前缀、`convertAndSendToUser` 语义）。后半段：能脱离 STOMP 的话，netty-spring 的 `sendJsonToSession(uri, payload, sessionId)` 按会话 id 显式寻址；`ClusterMessageSender.sendToUser(userId, ...)` 按用户寻址（**需 `cluster.offline.enable=true`**，且离线队列 + 多设备在场身份是可选开关）。不适用：继续用 STOMP 就按前半段修。

**7. 78455991 — 从 service 层给指定用户发消息（短）**
痛点原文就是"struggling to get the data from ChannelInterceptor to my service layer"——纯 STOMP 仪式。回答：netty-spring 没有 ChannelInterceptor 这道坎，`MessageSender` 是普通 Spring bean，可 `@Autowired`/构造注入进任意 service 或 HTTP controller，直接按 session id / user id 定向发送（`sendTextToSession` / `sendJsonToSession` / `sendToUser`）。不适用：留在 STOMP 就用 `SimpMessagingTemplate`（这句要写，别假装 STOMP 用户都得换库）。

**8. 78568876 — Spring Data Redis 当消息代理（短/中）**
K8s 里 pod 从 1 扩到 2 后 websocket 失效，想用 Spring Data Redis 当代理。回答先纠偏：Spring Data Redis 提供 pub/sub 原语（提问者已用它把消息扇出到各 pod），但光有 pub/sub 不够——跨 pod 的 WebSocket 还需要分布式会话注册表（谁持有哪个连接）+ 按节点路由，且它不替代 STOMP 的 `/user` 订阅寻址语义。选项：STOMP broker relay（他当前路线）或 Redis 背板的集群传输——netty-spring 集群正是后者（`RedisSessionRegistry` 记录谁在哪、Redis Pub/Sub 做传输，注册表 + 心跳在 Redis，消息热路径在 Pub/Sub）。**硬性不适用必须置顶**：提问者在 Spring Boot 3.2.4，而 netty-spring 今天只支持 **Boot 2.7.x / JDK 17**（Boot 3 是未发布的 2.0.0）。这题的价值是"讲清架构 + 诚实说自己暂时用不上"，属可发可不发，放最后。

**9. 78564331 — 下单实时通知（短）**
电商下单后同时通知买家与卖家。回答给实时通知的通用设计（别把 WS 写进事务路径，业务事件与推送解耦），然后落到库：HTTP+WS 合体 starter（`netty-web-spring-boot-starter`）里，`MessageSender` 可直接注入处理下单的 controller/service，`broadcastJson` 按 URI 广播、`sendToUser` 按用户定向。**诚实免责**：netty-spring 不内置通知/任务队列与持久化，那部分是你的应用；库只提供传输 + 定向寻址。

---

**内部注（不发布）**：上述 API 与配置名均已对照代码树核实——`MessageSender` 的 `broadcastText`/`broadcastJson`/`sendTextToSession`/`sendJsonToSession`/`closeSession`/`closeSessions`/`getSessionIds` 见 `netty-spring-websocket` 模块；`ClusterMessageSender.sendToUser` 见 `netty-spring-websocket-cluster`（其 868 行起，`offline.enable=false` 时报错提示需开 `server.netty.websocket.cluster.offline.enable=true`）；投递语义原文见 `docs/api-guide.md` §9 "Delivery Semantics" 表；reliableBroadcast 的 opt-in 门槛（`reliable.enable=true` 默认关闭、未开启抛 `IllegalStateException`）与保留窗口（`stream-max-len` 默认 10000）见 §9.1；天花板与 mesh 诚实范围见 §9 引言与 §9.7；docker-demo 命令与单向断言见 `docker-demo/README.md`。