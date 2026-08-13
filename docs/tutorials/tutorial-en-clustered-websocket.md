# Clustered WebSocket on Spring Boot in 20 minutes — and where it stops scaling

> ⚠️ Publishing gate: this article references **1.10.1**. The released **1.10.0** cluster starter crashes at startup on any default classpath without `jnats` (`NoClassDefFoundError io/nats/client/...`). Fixed locally (commit `15a25d7`). **Do not publish this article until 1.10.1 is on Maven Central.**

Spring's native WebSocket + STOMP stack is a lot of machinery for a simple realtime need. To get a chat channel or a live status feed, you have to wire up a message broker (or STOMP relay), configure `MessageBrokerRegistry`, deal with `SimpMessagingTemplate` and `/topic`/`/app` destination conventions, and then still reach for a separate story the moment you want two nodes to talk to each other.

I built [netty-spring](https://github.com/BerryWang1996/netty-spring) because I wanted the opposite: an annotation-driven WebSocket layer that starts from one dependency and one config line, runs on a dedicated Netty server, and only asks you to think about clustering when you actually need more than one node. This walkthrough gets you from zero to a two-node clustered chat in about 20 minutes — and then tells you, honestly, where it stops scaling, because that part matters just as much as the happy path.

## Single node, in about 5 minutes

Add one starter. This one pulls in HTTP MVC + WebSocket together:

```xml
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-web-spring-boot-starter</artifactId>
    <version>1.10.1</version>
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

That's it. Single-node mode is zero-overhead and behaviorally identical to the earlier 1.7.x/1.8.x line — local fan-out is pure in-memory, measured at ~2.1M msg/s (0.5 µs) on the benchmark host (I keep the full benchmark table in the README with its methodology and caveats). Don't reach for a cluster unless you actually have a second node.

## Cluster with one flag

Now imagine you need two nodes behind a load balancer so a rolling deploy doesn't drop everyone. Add the cluster starter alongside the one you already have:

```xml
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-websocket-cluster-spring-boot-starter</artifactId>
    <version>1.10.1</version>
</dependency>
```

Flip it on and point it at Redis:

```properties
server.netty.websocket.cluster.enable=true
server.netty.websocket.cluster.redis.uri=redis://localhost:6379
```

Your `ChatController` above does **not change a single line**. The same `MessageSender` you injected is now a `ClusterMessageSender`; `broadcastText`/`broadcastJson` fan out across every node, and unicast routes to whichever node owns the target session.

I want to state the delivery semantics plainly, because they're the actual contract, and they're the trade-offs inherent to any Redis Pub/Sub-backed fan-out: local delivery is guaranteed, the cross-node copy is best-effort unless you opt into the reliable path.

- **Local delivery always happens first and is never lost** — even while the node is degraded.
- **The cross-node copy is at-most-once by default.** Redis Pub/Sub is fire-and-forget; a node that's offline at publish time misses the message. There's no replay. (If you need at-least-once, there's an opt-in `reliableBroadcast` on Redis Streams — a different knob, not the default.)
- **If Redis goes away, the node degrades to local-only delivery after a grace period** (default `5000` ms) instead of dropping its connected users.
- **A unicast to a session that's gone from every node throws `MessageSessionClosedException`** to your caller, so you can handle "user already left" explicitly.

The full URI is `server.netty.websocket.cluster.*` and it's only active when `enable=true`. Production wants a **dedicated, network-isolated Redis with auth and TLS** — `rediss://:password@your-redis:6379` — because Redis is the cluster control plane, and anyone who can `PUBLISH` to it can inject or force-close sessions.

## Prove it on two nodes

Rather than ask you to take my word for it, there's a checked-in demo in the repo at [`docker-demo/`](https://github.com/BerryWang1996/netty-spring/tree/master/docker-demo): two netty-spring nodes behind an nginx load balancer, sharing state over Redis, plus a headless smoke test. Build the demo jar first, then bring the stack up:

```bash
./mvnw -pl demo-netty-web-spring-boot-starter -am package -DskipTests
cd docker-demo && docker compose up --build
```

Then, in a second terminal:

```bash
cd docker-demo && npm install && node smoke.js
```

`smoke.js` does one specific thing, and I'll be precise about what it is and isn't. It opens two clients through the load balancer, **confirms they landed on different nodes**, then broadcasts a message from one and asserts the other receives it stamped with the **correct origin-node id**. That's a **one-way cross-node delivery assertion** — a broadcast from node A reaching a client on node B — not a round-trip or latency benchmark. If it exits `0`, cross-node delivery is confirmed. The `docker-demo/README.md` has the full topology and what each check means.

## The four opt-in IM primitives

Everything below ships in the 1.10.x line and is **default-off**, each behind its own flag, so `cluster.enable=false` stays byte-identical to the single-node path. None of this turns netty-spring into an off-the-shelf IM platform — there are no client SDKs, no admin console, no recall/receipts/history — but these are the building blocks:

- **Rooms** (`cluster.room.enable=true`): a room is a sub-dimension inside one `@MessageMapping` URI. `roomMessage(uri, room, msg)` targets only the nodes actually hosting that room's members, so fan-out drops from N to roughly N/k for bounded rooms. Honest caveat: a hot room whose members span every node sees no reduction — for those, use a global `topicMessage`.
- **Offline queue + `sendToUser`** (`cluster.offline.enable=true`): `sendToUser(userId, msg)` delivers realtime if the user has any live session anywhere, otherwise enqueues per-user and backfills FIFO on reconnect.
- **Presence** (`cluster.presence.enable=true`): per-user aggregate `ONLINE`/`AWAY`/`OFFLINE` computed across all of a user's devices, plus `PRESENCE_CHANGE` events for rosters. Aggregate presence — stable per-device *addressing* is deferred.
- **Mesh** (`cluster.mesh.enable=true`): a `MeshBroker` that carries messages over direct node-to-node Netty TCP instead of Redis Pub/Sub (Redis stays for discovery only), with interest-routed fan-out reduction.

Each one's exact property names, signatures, and honest-scope caveats are in [API Guide §9](https://github.com/BerryWang1996/netty-spring/blob/master/docs/api-guide.md#9-websocket-cluster). I'd rather you read the caveats there than repeat all of them here.

## Where it stops scaling

This is the part I'd want you to read before betting a product on it:

- **Redis Pub/Sub is documented for roughly ≤ 10 nodes of active broadcast.** Beyond that you're approaching the fan-out wall; NATS is the scaling tier (ADR-001) — still capped by the same ~33k msg/s sender pipeline today, with 267k raw headroom reserved for future expansion — and sharded pub/sub waits for 2.0.0.
- **Redis is not optional furniture.** You need a dedicated, secured Redis — auth and TLS in production — because it's the control plane. There's optional HMAC envelope auth (`cluster.auth.enable=true`) for when Redis crosses an untrusted boundary.
- **The mesh fan-out reduction is real, but conditional.** It's genuine for **interest-partitioned live audiences** — a support topic whose agents sit on 4 of 100 nodes, or small tenants. A **global** topic (live sessions on every node) gets zero reduction, and a high-population topic under **random load-balancing** still saturates nearly the whole fleet (coupon-collector). The `fanout.target_nodes` meter exists precisely so you can *see* whether you're getting reduction instead of assuming it.
- **Boot 2.7.x and JDK 17 only.** Spring Boot 3.x/4.x support is future work (the 2.0.0 line), not released.
- **It runs its own Netty server** on `server.netty.port`. That's the point — but it also means it's **not** inside Tomcat, and it's not a drop-in for an existing Servlet stack.
- **Not an IM platform out of the box.** No client SDKs, no admin console, no recall/receipts/history. If you need those, you're building them on top of the primitives, not getting them from the starter.

None of this is a secret — the README and [cluster-design.md](https://github.com/BerryWang1996/netty-spring/blob/master/docs/cluster-design.md) say it in the same tone, because a scaling ceiling you discover at 2 a.m. during an incident is a lot more expensive than one you read about up front.

## Where to go next

- **Repo:** https://github.com/BerryWang1996/netty-spring
- **API guide (13 sections):** [`docs/api-guide.md`](https://github.com/BerryWang1996/netty-spring/blob/master/docs/api-guide.md) — §9 is the cluster deep-dive with every config key, §5 is the full `MessageSender`/`MessageSession` reference.
- **Site:** https://www.mysplitter.com/netty-spring-site/

Twenty minutes gets you a real clustered WebSocket — but the honest ceiling section is what keeps it running in production.