# Clustered WebSocket on Spring Boot — setup, delivery semantics, and limits

> Draft updated 2026-10-24 for the **2.0.0 candidate / Spring Boot 4.1.2 / JDK 17**. The Java handler was compiled with `-parameters` and exercised in single-node and two-node Redis modes using the frozen candidate's libraries. Maven Central availability and independent consumer dependency resolution remain pending in the [release notes](../release-notes-2.0.0.md). This tutorial has not been published externally; the dependency below is still a candidate coordinate.

Spring's native WebSocket + STOMP stack is a lot of machinery for a simple realtime need. To get a chat channel or a live status feed, you have to wire up a message broker (or STOMP relay), configure `MessageBrokerRegistry`, deal with `SimpMessagingTemplate` and `/topic`/`/app` destination conventions, and then still reach for a separate story the moment you want two nodes to talk to each other.

I'm the author of [netty-spring](https://github.com/BerryWang1996/netty-spring), an annotation-driven WebSocket layer that runs on a dedicated Netty server and enables clustering when needed. This walkthrough starts with one node, adds a two-node chat, and explains the delivery and deployment limits.

## Single-node setup

Start with a Spring Boot **4.1.2** application on **JDK 17**. Compile handler code with `javac -parameters`; for Maven, configure `maven-compiler-plugin` with `<release>17</release>` and `<parameters>true</parameters>`. Spring 7 no longer recovers parameter names from debug symbols, and this library rejects handlers without that metadata at startup. Also retain the application BOM security overrides for Jackson, SnakeYAML, and paired Logback components described in the [migration notes](../release-notes-2.0.0.md); verify the application's resolved dependencies instead of relying only on a transitive starter POM.

Add one starter. This one pulls in HTTP MVC + WebSocket together:

```xml
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-web-spring-boot-starter</artifactId>
    <version>2.0.0</version>
</dependency>
```

One config line. netty-spring runs its **own** Netty server — this is `server.netty.port`, not Tomcat's `server.port`:

```properties
server.netty.port=8080
```

Now a handler. WebSocket endpoints are `@MessageMapping` methods keyed by URI and a `MessageType` lifecycle enum. Here's a minimal chat handler with `ON_CONNECTED` and `TEXT_MESSAGE`:

```java
import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.MessageMapping;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class ChatController {

    private final MessageSender messageSender;

    public ChatController(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    @MessageMapping(value = "/ws/chat", messageType = MessageType.ON_CONNECTED)
    public void onConnected(MessageSession session) {
        messageSender.broadcastJson("/ws/chat",
                Map.of("type", "system", "text", session.getSessionId() + " joined"));
    }

    @MessageMapping(value = "/ws/chat", messageType = MessageType.TEXT_MESSAGE)
    public void onText(String text, MessageSession session) {
        messageSender.broadcastText("/ws/chat", text);
    }
}
```

Three things worth internalizing here:

1. **The handler is a singleton Spring bean.** The `MessageSession` is passed in per-event, and it's your per-connection handle: `getSessionId()`, `getQueryParam("name")`, `getHeader("Authorization")`, `getPath()`. Key any per-session state by `session.getSessionId()` in a map — don't try to hold a "session" as instance state.
2. **`MessageSender` is injectable anywhere**, including plain HTTP controllers — you can push a WebSocket message from a `@RestController` that handles an HTTP POST.
3. **`broadcastText(uri, text)` / `broadcastJson(uri, payload)`** fan out to every session on that URI; `sendTextToSession(uri, text, sessionId)` and `sendJsonToSession(uri, payload, sessionId)` target one session.

From the browser, it's plain WebSocket — no STOMP, no SockJS, no library required:

```javascript
const ws = new WebSocket('ws://localhost:8080/ws/chat');

ws.onopen = () => ws.send('hello from the browser');
ws.onmessage = (e) => console.log('received:', e.data);
```

Single-node mode uses local in-memory session routing and does not start a cluster broker. Add clustering when you need multiple nodes; no throughput or connection-capacity promise follows from this setup. Version-specific measurements and their conditions belong in the release notes.

## Cluster with one flag

When you need two nodes behind a load balancer, add the cluster starter alongside the one you already have. Deployment and rollback support depend on the combinations documented in the migration notes:

```xml
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-websocket-cluster-spring-boot-starter</artifactId>
    <version>2.0.0</version>
</dependency>
```

Flip it on and point it at Redis:

```properties
server.netty.websocket.cluster.enable=true
server.netty.websocket.cluster.redis.uri=redis://localhost:6379
```

Your `ChatController` above does **not change a single line**. The same `MessageSender` you injected is now a `ClusterMessageSender`; `broadcastText`/`broadcastJson` fan out across every node, and unicast routes to whichever node owns the target session.

Local sending runs before cross-node publication. That ordering does not guarantee application consumption or delivery during shutdown, connection failure, or backpressure; the cross-node copy is best-effort unless you opt into the separate reliable path.

- **Local sending is attempted first**, including degraded mode; applications must still handle closed sessions, rejected work, and failed writes.
- **The cross-node copy is at-most-once by default.** Redis Pub/Sub is fire-and-forget; a node that's offline at publish time misses the message. There's no replay. (If you need at-least-once, there's an opt-in `reliableBroadcast` on Redis Streams — a different knob, not the default.)
- **If Redis goes away, the node degrades to local-only delivery after a grace period** (default `5000` ms) instead of dropping its connected users.
- **A unicast to a session that's gone from every node throws `MessageSessionClosedException`** to your caller, so you can handle "user already left" explicitly.

The property prefix is `server.netty.websocket.cluster.*` and it's only active when `enable=true`. Production wants a **dedicated, network-isolated Redis with auth and TLS** — `rediss://:password@your-redis:6379` — because Redis carries cluster control messages. The cluster also supports HMAC verification; configure its strict mode and shared secret as described in the API guide rather than treating transport access controls as message authentication.

## Prove it on two nodes

The checked-in [Docker demo](../../docker-demo/README.md) uses two nodes behind an nginx load balancer, sharing state over Redis, plus a headless smoke test. Run these commands from the candidate checkout (use the released `v2.0.0` tag only after it exists and Central is confirmed). Build the demo jar first, then bring the stack up:

```bash
./mvnw -pl demo-netty-web-spring-boot-starter -am package -DskipTests
cd docker-demo && docker compose up --build
```

Then, in a second terminal:

```bash
cd docker-demo && npm install && node smoke.js
```

`smoke.js` opens two clients through the load balancer, confirms they landed on different nodes, and checks JSON broadcasts in **both directions**, including message content and the sender's `originNode`. This is a delivery smoke test, not a latency benchmark, reliable replay test, or zero-downtime claim. The Docker demo README records the topology and check scope.

## The four opt-in IM primitives

These existing 1.10.x capabilities are carried into the 2.0.0 candidate and remain **default-off** behind their feature flags. None makes netty-spring an off-the-shelf IM platform: there are no client SDKs, admin console, recall, receipts, or history API.

- **Rooms** (`cluster.room.enable=true`): a room is a sub-dimension inside one `@MessageMapping` URI. `roomMessage(uri, room, msg)` targets only the nodes actually hosting that room's members, so fan-out drops from N to roughly N/k for bounded rooms. Honest caveat: a hot room whose members span every node sees no reduction — for those, use a global `topicMessage`.
- **Offline queue + `sendToUser`** (`cluster.offline.enable=true`): `sendToUser(userId, msg)` delivers realtime if the user has any live session anywhere, otherwise enqueues per-user and backfills FIFO on reconnect.
- **Presence** (`cluster.presence.enable=true`): per-user aggregate `ONLINE`/`AWAY`/`OFFLINE` computed across all of a user's devices, plus `PRESENCE_CHANGE` events for rosters. Aggregate presence — stable per-device *addressing* is deferred.
- **Mesh** (`cluster.mesh.enable=true`): a `MeshBroker` that carries messages over direct node-to-node Netty TCP instead of Redis Pub/Sub, with interest-routed fan-out reduction. Redis still holds registries, heartbeat, discovery, and routing interests.

Each one's exact property names, signatures, and scope caveats are in [API Guide §9](../api-guide.md#9-websocket-cluster).

## Where it stops scaling

This is the part I'd want you to read before betting a product on it:

- **Redis Pub/Sub retains its documented roughly ≤ 10-node active-broadcast envelope.** This is an operating limit, not a new 2.0.0 capacity measurement. Validate your workload; NATS and mesh do not establish an automatic capacity increase. Sharded pub/sub is deferred beyond 2.0.0.
- **Redis is not optional furniture.** You need a dedicated, secured Redis — auth and TLS in production — because it's the control plane. There's optional HMAC envelope auth (`cluster.auth.enable=true`) for when Redis crosses an untrusted boundary.
- **The mesh fan-out reduction is real, but conditional.** It's genuine for **interest-partitioned live audiences** — a support topic whose agents sit on 4 of 100 nodes, or small tenants. A **global** topic (live sessions on every node) gets zero reduction, and a high-population topic under **random load-balancing** still saturates nearly the whole fleet (coupon-collector). The `fanout.target_nodes` meter exists precisely so you can *see* whether you're getting reduction instead of assuming it.
- **This candidate targets Boot 4.1.2 / JDK 17**, with `-parameters` required for handlers. Published 1.x targets Boot 2.7; Boot 3.x compatibility is not promised. Candidate and release status are in the [development plan](../development-plan.md).
- **It runs its own Netty server** on `server.netty.port`. That's the point — but it also means it's **not** inside Tomcat, and it's not a drop-in for an existing Servlet stack.
- **Not an IM platform out of the box.** No client SDKs, no admin console, no recall/receipts/history. If you need those, you're building them on top of the primitives, not getting them from the starter.

The README and [cluster design](../cluster-design.md) describe these deployment and delivery limits in more detail.

## Where to go next

- **Repo:** https://github.com/BerryWang1996/netty-spring
- **API guide:** [`docs/api-guide.md`](../api-guide.md) — §9 is the cluster deep-dive with every config key, §5 is the full `MessageSender`/`MessageSession` reference.
- **Site:** https://www.mysplitter.com/netty-spring-site/

The local handler check covers connected-event JSON and text broadcasts in both directions. It does not establish capacity, TLS handling, or all optional feature behavior. Before publication, confirm Central availability and independently resolved consumer dependencies as recorded in the release and promotion documents.
