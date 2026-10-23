# netty-spring — Frequently Asked Questions

> Release status: see [1.10.1 release notes](release-notes-1.10.1.md). Do not adopt or promote the 1.10.1 cluster examples until its Maven Central publication is confirmed there.

netty-spring is an Apache-2.0 Spring Boot integration for Netty — HTTP MVC, WebSocket, application-layer AES-GCM crypto, and Micrometer observability — plus an opt-in WebSocket cluster and IM-platform foundation on top. It runs its own Netty server (`server.netty.port`, not Tomcat), targets Spring Boot 2.7.x / JDK 17, and is published on Maven Central as `io.github.berrywang1996:netty-*`.

---

### 1. Do I need Redis?

**No, unless you turn on cluster mode.** The default is single-node (`server.netty.websocket.cluster.enable=false`), which has zero broker overhead and no Redis dependency at all — just set `server.netty.port=8080` and go. A single node serves roughly up to ~25k concurrent WebSocket connections with local in-memory fan-out.

Redis enters only when you opt into clustering: `server.netty.websocket.cluster.enable=true` plus `server.netty.websocket.cluster.redis.uri=redis://host:6379`. Redis is the cluster control plane, and the docs are explicit that production requires a **dedicated, network-isolated Redis with auth + TLS** — anyone who can `PUBLISH` to it can inject or close any session. (A no-Redis cluster alternative exists via the NATS all-stack path — `cluster.nats.servers` + `cluster.nats.registry=true` — but the default registry and heartbeat stay on Redis.)

### 2. When should I NOT enable cluster mode?

Keep `cluster.enable=false` when **all** of these hold: you run one server; you expect ≤25k concurrent WebSocket connections; downtime of that single node is acceptable; and you don't want to operate Redis. The README's selection guide says it plainly: don't enable cluster "for safety" without a concrete use case — every "yes" adds operational complexity. Single-node mode is byte-identical to 1.7.x/1.8.0, so you lose nothing by staying off the cluster.

Enable cluster when **any** of these is true: more than one server, more than ~25k connections expected, rolling deployments without dropping all sessions, or you already operate Redis 7+.

### 3. How is this different from Spring's STOMP + broker relay?

Spring's built-in WebSocket story is STOMP over WebSocket (typically with SockJS), fronting either the in-memory `SimpleBroker` (which Spring's own docs say is **not suitable for clustering**) or an external broker relay to RabbitMQ/ActiveMQ/STOMP-speaking middleware. netty-spring takes a different path:

- **No STOMP wire protocol, no SockJS** — you write plain-WebSocket `@MessageMapping` handlers (`@MessageMapping(value = "/ws/chat", messageType = MessageType.TEXT_MESSAGE)`), and JSON auto-binding + binary support are built in.
- **One `MessageSender` API** — `broadcastText`/`broadcastJson`, targeted `sendTextToSession`, etc. — that you inject anywhere (including HTTP controllers). Enabling the cluster swaps it for a `ClusterMessageSender` with **zero business-code changes**.
- **Clustering is Redis Pub/Sub**, not a separate STOMP broker you also have to run and configure as a relay.

If you're already committed to STOMP and want broker-relay semantics, Spring's native stack is fine; netty-spring is for people who want plain WebSocket + Spring Boot annotations and a same-API cluster.

### 4. How does it compare to netty-socketio?

**Protocol.** netty-socketio is a Socket.IO **wire-protocol** server — you get the Socket.IO client SDKs, but you're bound to that protocol. netty-spring speaks **native WebSocket (RFC 6455)** with no wire-protocol lock-in: plain `@MessageMapping` annotations, Spring Boot auto-configuration, and an injectable `MessageSender`.

**Clustering.** netty-spring ships an opt-in Redis-backed cluster with documented delivery semantics (see Q10). Evaluate alternatives against their current upstream documentation and the protocol your clients require.

The honest read: if you need Socket.IO-protocol client compatibility, netty-spring is **not** for you — it's aimed at greenfield / native-WebSocket builds.

### 5. How does it compare to YeautyYE/netty-websocket-spring-boot-starter?

YeautyYE is a single-node Netty + Spring Boot WebSocket starter — a solid, narrow tool for one-server WebSocket. netty-spring is a broader server-side toolkit, but **not a drop-in superset**: it uses a different programming model — `@MessageMapping(value, messageType)` annotations with a `MessageType` enum, rather than YeautyYE's `@ServerEndpoint`/`@OnOpen`/`@OnMessage` style — and adds **opt-in multi-node clustering (Redis)**, **application-layer AES-GCM frame encryption**, **Micrometer observability**, and the 1.10.x IM primitives (rooms, `sendToUser` + offline queue, presence, mesh transport). YeautyYE has no cluster or IM primitives. If you only ever need one server and never need crypto or metrics, YeautyYE is simpler; if you might scale past one node or want the IM foundation, netty-spring is the broader bet.

### 6. Does it work with Spring Boot 3 or 4?

**No — not today.** The 1.x line targets **Spring Boot 2.7.x (2.7.18) and JDK 17 only**, and runs its own Netty server rather than inside Tomcat. **2.0.0 targets Spring Boot 4.1**, as detailed in the [development plan](development-plan.md); it has not shipped. Boot 3.x is not a supported target of that release. Note honestly: Spring Boot 2.7's upstream OSS support has ended, so the migration to a supported Boot line is the project's next major work item rather than an afterthought.

### 7. Is it production-ready?

**Scoped yes.** Single-node mode is production-grade and behaviorally identical to 1.7.x/1.8.0. Release verification results are recorded in the release notes. Cluster mode is production-ready **within its documented envelope**: roughly ≤10 nodes of active broadcast, a dedicated/secured Redis, and the stated delivery semantics (local never lost; cross-node at-most-once; Redis loss degrades to local delivery after a grace period instead of dropping connections).

It is **not** production-ready as a full IM platform, at unlimited scale, or on Boot 3/4 — none of which the project claims. For the 1.10.0 optional-NATS startup defect, its fix, and the adoption gate, use the [1.10.1 release notes](release-notes-1.10.1.md).

### 8. What's the license?

**Apache License 2.0.** Artifacts are on Maven Central under `io.github.berrywang1996:*` (combined `netty-web-spring-boot-starter`, WebSocket-only, HTTP-MVC-only, and cluster starters). Earlier `com.github.berrywang1996:*` artifacts were only on a private repository — migrating is just a groupId change. The project is maintained by a single maintainer; that's a governance fact worth knowing before you bet a product on it.

### 9. Can I use it for a full IM product?

**No — not as a drop-in.** netty-spring is a server-side IM *foundation*, not an out-of-the-box IM server.

What **ships** (all opt-in, each behind its own flag):
- **Rooms** — `roomMessage(uri, room, msg)` targets only the nodes hosting that room's members (`cluster.room.enable`).
- **`sendToUser` + offline queue** — realtime if the user is online anywhere, else FIFO backfill on reconnect (`cluster.offline.enable`).
- **Multi-device presence** — per-user aggregate `ONLINE`/`AWAY`/`OFFLINE` plus `PRESENCE_CHANGE` events (`cluster.presence.enable`).
- **Mesh transport** — node-to-node TCP instead of Redis Pub/Sub (`cluster.mesh.enable`).

What **does not ship** (and the project does not claim): client SDKs, an admin console, message recall, read receipts, and message history. Those are the difference between a foundation and a product like OpenIM or WildFireChat.

One security requirement if you do build IM on it: the default `UserIdResolver` reads `query:userId` verbatim and is **testing-only** — production must supply its own authenticated `UserIdResolver` bean, or cross-user data exposure is on you.

### 10. What scale can it actually handle?

The project publishes its ceilings, not just its wins:

- **Single node:** ~2.1M msg/s in-memory broadcast (measured loopback benchmark), ≤~25k connections, no Redis.
- **Cluster (Redis Pub/Sub):** documented for **roughly ≤10 nodes** of active broadcast, ~33k msg/s cross-node, and it needs a **dedicated, secured Redis** (auth + TLS in production).
- **Mesh transport:** pushes the ~10-node ceiling, but the fan-out reduction is **genuine only for interest-partitioned live audiences** — a global topic or a high-population topic under random load-balancing still saturates the fleet.
- **Delivery semantics:** local delivery happens first and is never lost; cross-node broadcast is **at-most-once** (fire-and-forget Pub/Sub); a node offline at publish time misses the message. Undeliverable unicast throws `MessageSessionClosedException`. Redis loss degrades the node to local delivery after a grace period.
- **Platform:** Spring Boot 2.7.x / JDK 17 only; the planned 2.0.0 target is Boot 4.1.

There's a checked-in multi-node proof you can run yourself — `docker-demo/` spins up two nodes behind an nginx load balancer + Redis, and `smoke.js` asserts one-way cross-node delivery (a broadcast from one node reaches a client on the other with the correct origin-node stamp).