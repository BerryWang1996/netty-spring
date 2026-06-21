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
    <version>1.9.0</version>
</dependency>
```

Available on Maven Central as `io.github.berrywang1996:*` (versions `1.4.0`, `1.6.2`, `1.7.0`, `1.8.0`, `1.9.0`). Earlier `com.github.berrywang1996:*` artifacts were only published to a private repository — migrate by changing the groupId in your `pom.xml`.

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

### WebSocket Cluster *(v1.8.0 / reliability-hardened in v1.9.0 / IM-platform foundation in v1.10.0)*

Scale WebSocket across multiple nodes with Redis Pub/Sub. Default is single-node mode (zero overhead). Enable cluster with one config flag:

```xml
<!-- Add the cluster starter alongside your existing starter -->
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-websocket-cluster-spring-boot-starter</artifactId>
    <version>1.9.0</version>
</dependency>
```

```properties
server.netty.websocket.cluster.enable=true
# Use a DEDICATED, network-isolated Redis with auth + TLS in production:
server.netty.websocket.cluster.redis.uri=rediss://:password@your-redis:6379
```

**Zero business code changes** — `MessageSender` automatically switches to `ClusterMessageSender` with cross-node broadcast and unicast.

> ⚠️ **Security:** Redis is the cluster control plane — anyone who can `PUBLISH` to it can inject into or close any session. Use a dedicated, network-isolated, password-protected, TLS Redis. Application-layer AES-GCM does **not** extend across Redis (plaintext is fanned out to remote nodes). See [Cluster Design §Security](docs/cluster-design.md).

#### IM Platform Foundation *(v1.10.0, opt-in)*

1.10.0 adds four opt-in IM primitives on top of the cluster core — all default-off, so `cluster.enable=false` stays byte-identical to 1.9.0:

- **Room-scoped routing** — `roomMessage(uri, room, msg)` reaches only the nodes hosting that room's members (fan-out N/k for bounded rooms). `cluster.room.enable=true`.
- **Offline queue + user-addressed delivery** — `sendToUser(userId, msg)` delivers to an online user or enqueues for FIFO backfill on reconnect (per-user Redis Stream). `cluster.offline.enable=true`.
- **Multi-device presence** — per-user aggregate `ONLINE`/`AWAY`/`OFFLINE` + `PRESENCE_CHANGE` events across devices, incl. an authoritative crash-path `→OFFLINE`. `cluster.presence.enable=true`.
- **Node-to-node mesh transport** — `MeshBroker` rides direct Netty TCP instead of Redis Pub/Sub (Redis stays for discovery only), with **interest-routed** fan-out reduction, hot-path robustness (Redis off the broadcast hot path), and nine `netty.cluster.mesh.*` meters incl. the `fanout.target_nodes` reduction gauge. `cluster.mesh.enable=true`.

> The mesh pushes the ~10-node Redis Pub/Sub broadcast ceiling **for interest-partitioned live audiences** — a global or high-population topic under random load-balancing still saturates the fleet (the honest caveat). See [API Guide §9.4–§9.7](docs/api-guide.md#9-websocket-cluster) and [Cluster Design](docs/cluster-design.md).

#### Performance Benchmarks

**Methodology** — measured 2026-06-08 on Intel i9-14900HX (24c/32t), 64 GB RAM, Windows 11; JDK 17 GraalVM 17.0.11+7, Maven 3.9.16; Docker Desktop 29.5.2 running Redis 7-alpine + NATS 2.10 with JetStream, both on loopback. Zero-dependency `SimpleTextEnvelopeCodec`. 500-message warmup discarded before measurement; main runs of 5,000 (fan-out paths) and 2,000 (cross-node + reliable) messages. Reproduce with `mvn -pl netty-spring-websocket-cluster test -Dtest=PerformanceBenchmark` (9 ordered tests).

| # | Scenario | Throughput | Avg latency | Notes |
| --- | --- | --- | --- | --- |
| 1 | Local broadcast baseline | **2,104,111 msg/s** | **0.5 µs** | `DefaultMessageSender`, no Redis, pure in-memory fan-out |
| 2 | Cluster broadcast (Redis Pub/Sub) | **198,353 msg/s** | **5.0 µs** | `ClusterMessageSender` → local fan-out + async PUBLISH |
| 3 | Raw Redis Pub/Sub | **32,651 msg/s** | **30.6 µs** | Lettuce PUBLISH → SUBSCRIBE E2E |
| 4 | Two-node cross-node (Redis) | **33,389 msg/s** | **30.0 µs** | Node A → Redis → Node B |
| 5 | Raw NATS Pub/Sub | **267,071 msg/s** | **3.7 µs** | jnats publish → message handler E2E |
| 6 | Two-node cross-node (NATS broker + Redis registry) | **32,514 msg/s** | **30.8 µs** | RC9 mixed mode; bounded by `ClusterMessageSender` pipeline |
| 7 | Reliable broadcast (Redis Streams) | **18,047 msg/s** | **55.4 µs** | RC2 `RedisStreamsReliableBroker`, XADD + XREADGROUP at-least-once |
| 8 | Reliable broadcast (NATS JetStream) | **3,391 msg/s** | **294.9 µs** | RC13 `NatsJetStreamReliableBroker`, FILE storage + pull-fetch |
| 9 | HMAC overhead (sign + verify, isolated CPU cost) | 3.59M → 0.37M msg/s | +2.4 µs | RC3 HMAC-SHA256; ~90% throughput hit when isolated, <5% under real I/O |

**Reading the numbers honestly:**

- **Loopback Docker** is the best-case latency floor (no network RTT, no replication, no TLS). Real cross-AZ Redis/NATS adds 0.5–2 ms; the absolute throughput drops accordingly.
- **NATS broker's 8× raw-transport edge (267k vs 33k) doesn't translate into proportional cross-node gains** — the `ClusterMessageSender` pipeline (envelope build, codec, lookup, fan-out) caps both paths at ~30k msg/s here. NATS becomes the better choice when you're approaching Redis Pub/Sub's fan-out wall (see [Cluster Design §Capacity](docs/cluster-design.md)).
- **Reliable brokers cost what you'd expect.** Redis Streams ~5× slower than fire-and-forget Pub/Sub; NATS JetStream slower again due to disk fsync + pull-based fetch loop (`poll-block-ms` default 2s). Reliable is for broadcasts that **must** survive a brief subscriber outage, not the hot path.
- **HMAC's micro-benchmark 90% overhead** is sign+verify in isolation (zero I/O). Add 30 µs of real network and the 2.4 µs HMAC cost is < 8% of total round-trip — negligible in practice. Enable `auth.enable=true` whenever Redis is shared with untrusted publishers.

#### When to Use Cluster vs Single-Node

| Dimension | Single-Node (default) | Cluster — Redis (`cluster.enable=true`) | Cluster — NATS (`nats.servers=...`) |
| --- | --- | --- | --- |
| Use case | ≤ 1 server, ≤ 25k connections | Multiple servers, horizontal scaling | Scaling tier (ADR-001) when Redis Pub/Sub hits its fan-out wall |
| Broadcast latency (loopback) | ~0.5 µs (in-memory) | ~30 µs (via Redis Pub/Sub) | ~31 µs (via NATS) — same `ClusterMessageSender` pipeline cap |
| Cross-node throughput | ≥ 2.1M msg/s local | ~33k msg/s (Redis bound) | ~33k msg/s same; raw NATS headroom 267k for future expansion |
| Reliable broadcast | n/a | RC2 Redis Streams — 18k msg/s, at-least-once | RC13 JetStream — 3.4k msg/s, at-least-once + replay |
| Extra dependency | None | Redis 7+ | NATS 2.10+ (+ JetStream for `nats.registry=true` and reliable) |
| Failure impact | Process dies = all disconnected | One node dies = only its users disconnect | Same |
| Config cost | Zero | One line + Redis URI | One line + NATS URI (registry still on Redis by default) |

#### Capacity Planning

| Target Connections | Nodes | Recommended Transport | Cluster Broadcast Ceiling |
| --- | --- | --- | --- |
| ≤ 25k | 1 (single-node) | Not needed | ≥ 2.1M msg/s (in-memory) |
| 25k – 75k | 2–3 | Redis Standalone or Sentinel | ~33k msg/s |
| 75k – 250k | 4–10 | Redis Sentinel (recommended) or NATS broker | ~33k msg/s (single primary / single NATS server) |
| 250k – 1M | 10–20 | NATS broker (RC9 ADR-001 scaling tier) | NATS raw 267k msg/s headroom — still bottlenecked by sender pipeline ~33k msg/s today |
| > 1M | > 20 | Sharded pub/sub (Boot 3.x / 2.0.0) | Beyond Lettuce 6.1 — see [Boot 3.x compatibility matrix](docs/2.0.0/boot3-compatibility-matrix.md) |

#### Selection Guide — Pick the Right Configuration

Six independent decisions. Default to **NO** at every step unless the listed condition is true — every "yes" adds operational complexity.

##### Decision 1 — Cluster mode? (`cluster.enable`)

- **Single-node (`cluster.enable=false`, default)** if all of: ≤ 1 server; ≤ 25k concurrent WebSocket connections; downtime of the single node is acceptable; you don't operate Redis.
- **Cluster (`cluster.enable=true`)** if any of: > 1 server; > 25k connections expected; you need rolling deployments without dropping all sessions; you already operate Redis 7+.

> Single-node is byte-identical to 1.7.x/1.8.0 — zero broker overhead. Don't enable cluster "for safety" without the use case.

##### Decision 2 — Which transport? (Redis vs NATS)

| Question | Pick Redis Pub/Sub (default) | Pick NATS broker (RC9) | Pick all-NATS stack (RC10) |
|---|---|---|---|
| Already operating Redis | ✅ | (continue Redis for registry) | — |
| Expected broadcast rate | ≤ 30k cross-node msg/s | Approaching the Redis Pub/Sub fan-out wall (see cluster-design §Capacity) | Same as middle column + want a single middleware |
| Operate two middlewares (Redis + NATS) is OK | ✅ | ✅ | ❌ |
| Have a JetStream-enabled NATS server | not required | not required | **required** (`nats-server -js`) |
| Config |  `cluster.redis.uri=...` | `cluster.nats.servers=nats://...` | + `cluster.nats.registry=true` |
| Reliable broadcast supported | Yes (Redis Streams, RC2) | Yes (Redis Streams for the registry tier) | Yes (NATS JetStream, RC13) |

The NATS broker (RC9) replaces ONLY the cross-node broadcast/unicast path. Registry + heartbeat stay on Redis unless you also flip `nats.registry=true` (RC10).

##### Decision 3 — Redis topology? (standalone vs Sentinel vs Cluster)

- **Standalone** (`cluster.redis.uri=redis://...`) — dev, demo, single-master prod ≤ 10 nodes.
- **Sentinel** (`cluster.redis.uri=redis-sentinel://...?sentinelMasterId=mymaster`) — production HA with automatic failover; ≤ 10 cluster nodes; managed Redis offerings (AWS ElastiCache, Aliyun Tair) support this out of the box.
- **Redis Cluster** (`cluster.redis.cluster-nodes=host1:6379,host2:6379,...`, RC7) — shards SessionRegistry + heartbeat across multiple Redis primaries; needed when registry write rate or memory exceeds a single primary. **RC7 caveat**: cluster pub/sub is still regular (no fan-out reduction — that's sharded pub/sub in 2.0.0).

##### Decision 4 — Reliable broadcast? (`reliable.enable`)

Enable **only if** broadcasts have to survive a brief subscriber outage (≤ stream retention window).

- **Off (default)** — `topicMessage()` uses at-most-once Pub/Sub. A node disconnected when the broadcast fires misses it.
- **On (`reliable.enable=true`)** — `reliableBroadcast()` API uses at-least-once via Redis Streams (or NATS JetStream when `nats.registry=true`). Each subscribing node has a durable cursor; on reconnect it replays the backlog (within `stream-max-len`, default 10,000 entries per URI).

Trade-off: ~5× lower throughput, ~6× higher latency vs Pub/Sub (see benchmark §7 vs §3). Use `reliableBroadcast()` selectively — keep the chatty fire-and-forget channels on `topicMessage()`.

##### Decision 5 — HMAC envelope auth? (`auth.enable`)

Enable if **anyone** can `PUBLISH` to your Redis (shared Redis, multi-tenant, weak network isolation). Disable only if Redis is dedicated, network-isolated, and the publisher set is provably trusted.

- **Off (default)** — any party with Redis PUBLISH rights can forge `originNodeId`, inject broadcasts, or force-close any session.
- **On (`auth.enable=true` + `auth.secret=${CLUSTER_AUTH_SECRET}`)** — every envelope is HMAC-SHA256 signed. Rolling rollout: `permissive=true` first (accept unsigned), then flip to strict.

Cost: ~2.4 µs of CPU per envelope (see benchmark §9). At real network latencies (30+ µs), HMAC adds < 8% to round-trip — negligible. **Default-on for anything that crosses an untrusted boundary.**

##### Decision 6 — W3C TraceContext? (`trace-propagation.enable`, RC6)

Enable if you already use distributed tracing (Sleuth, OpenTelemetry, Brave). MDC `traceId` will continue across cross-node broadcasts in logs.

- **Off (default)** — log correlation breaks at the broker hop.
- **On** — `traceparent` is added to the envelope; receiver restores MDC scope around delivery. Zero-config additive — no tracer integration needed for the MDC default. Sleuth/OTel users can plug a custom `ClusterTraceContext` bean for live-span continuation.

##### Decision 7 — Application-layer crypto? (`crypto.enable`, broader than cluster)

WebSocket frames AES-GCM encrypted application-side (in addition to TLS, not instead of). Use only for end-to-end confidentiality requirements where TLS termination at the load balancer is not enough.

- **Off (default)** — TLS is normally sufficient.
- **On** — protect specific URIs with `crypto.include-uris=/ws/secret`. Note: **does NOT extend across the cluster broker** — Redis sees plaintext envelopes. Combine with `auth.enable=true` for in-transit integrity.

#### Common Deployment Recipes

##### Recipe A — Small product, single host (≤ 25k connections)

```properties
server.netty.port=8080
server.netty.websocket.max-connections=25000
server.netty.websocket.heartbeat-interval-seconds=30
server.netty.websocket.heartbeat-timeout-seconds=90
# That's it. No cluster, no Redis. ~2.1M msg/s in-memory.
```

##### Recipe B — Standard cluster product (2-5 nodes, dedicated Redis)

```properties
server.netty.websocket.cluster.enable=true
server.netty.websocket.cluster.redis.uri=rediss://:${REDIS_PASSWORD}@redis.internal:6379

# HMAC if Redis isn't 100% trusted
server.netty.websocket.cluster.auth.enable=true
server.netty.websocket.cluster.auth.secret=${CLUSTER_AUTH_SECRET}

# Trace correlation if you have tracing
server.netty.websocket.cluster.trace-propagation.enable=true
```

##### Recipe C — High-reliability product (broadcasts must survive subscriber blip)

```properties
server.netty.websocket.cluster.enable=true
server.netty.websocket.cluster.redis.uri=rediss://:${REDIS_PASSWORD}@redis.internal:6379
server.netty.websocket.cluster.auth.enable=true
server.netty.websocket.cluster.auth.secret=${CLUSTER_AUTH_SECRET}

# Reliable broadcast for critical channels
server.netty.websocket.cluster.reliable.enable=true
server.netty.websocket.cluster.reliable.stream-max-len=50000   # ~50k msgs retention per URI
```

Use `clusterMessageSender.reliableBroadcast(uri, msg)` for critical channels; keep `topicMessage()` for chatty fire-and-forget.

##### Recipe D — High-scale (approaching Redis Pub/Sub fan-out wall, 5-15 nodes)

```properties
server.netty.websocket.cluster.enable=true
# NATS broker replaces Redis Pub/Sub for cross-node fan-out
server.netty.websocket.cluster.nats.servers=nats://nats-1.internal:4222,nats://nats-2.internal:4222
# Registry/heartbeat stay on Redis
server.netty.websocket.cluster.redis.uri=rediss://:${REDIS_PASSWORD}@redis.internal:6379
server.netty.websocket.cluster.auth.enable=true
server.netty.websocket.cluster.auth.secret=${CLUSTER_AUTH_SECRET}
```

##### Recipe E — All-NATS stack (no Redis at all)

```properties
server.netty.websocket.cluster.enable=true
server.netty.websocket.cluster.nats.servers=nats://nats-1.internal:4222,nats://nats-2.internal:4222
server.netty.websocket.cluster.nats.registry=true                       # NATS JetStream-KV registry/heartbeat/reaper
server.netty.websocket.cluster.reliable.enable=true                     # JetStream-backed reliable
server.netty.websocket.cluster.auth.enable=true
server.netty.websocket.cluster.auth.secret=${CLUSTER_AUTH_SECRET}
```

Requires a JetStream-enabled NATS server (`nats-server -js`). Operationally simpler (one middleware) but reliable throughput is lower than the Redis Streams equivalent (see benchmark §7 vs §8).

##### Recipe F — Redis Cluster (very high registry write rate / sharded registry)

```properties
server.netty.websocket.cluster.enable=true
# Use cluster-nodes instead of uri — auto-selects RedisClusterMode* SPI implementations (RC7)
server.netty.websocket.cluster.redis.cluster-nodes=redis-1.internal:6379,redis-2.internal:6379,redis-3.internal:6379
server.netty.websocket.cluster.auth.enable=true
server.netty.websocket.cluster.auth.secret=${CLUSTER_AUTH_SECRET}
# Note: regular cluster pub/sub does NOT reduce fan-out. Sharded pub/sub is 2.0.0.
```

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

- **`1.10.0` GA is cut + tagged (`v1.10.0`) and FF-merged to master; push + deploy to Maven Central are pending (user-driven).** Adds the **IM-platform foundation** on top of 1.9.0: room-scoped routing, offline queue + user-addressed `sendToUser`, multi-device presence, and the node-to-node **mesh** transport (interest-routed fan-out reduction + nine `netty.cluster.mesh.*` meters). `cluster.enable=false` stays byte-identical to 1.9.0. **644 tests / 11 modules green.** See [Release Notes 1.10.0](docs/release-notes-1.10.0.md) and [API Guide §9](docs/api-guide.md#9-websocket-cluster). *(Until the Central deploy completes, the Maven coordinate below resolves `1.9.0`.)*
- **Latest stable on Maven Central: `1.9.0` GA (released 2026-06-07).** Previous stable: `1.8.0`. The 1.9.0 GA cycle delivered: cluster reliability hardening (5 deferred items + 2 new knobs), reliable broadcast (Redis Streams + NATS JetStream — at-least-once, opt-in), HMAC envelope authentication (`MessageAuthenticator` SPI), full Micrometer cluster metrics, W3C TraceContext propagation, Redis Cluster client, multi/sharded pub/sub multiplexing, NATS broker + all-NATS stack (JetStream-KV registry/heartbeat/reaper), multi-node E2E + Testcontainers CI, and a GA-readiness audit. **444 tests / 11 modules green.** Single-node mode stays production-grade and identical to 1.7.x/1.8.0 — see [Release Notes 1.9.0](docs/release-notes-1.9.0.md) and [Cluster Design §Security](docs/cluster-design.md).
- `1.8.0` delivered WebSocket cluster support (Redis Pub/Sub + 5-layer SPI architecture + 291 tests) — all preserved in 1.9.0 and backward compatible.
- Milestones P0 through P7 are all complete; performance (1.6.x), security/stability (1.6.2), observability (1.7.0), clustering (1.8.0), and cluster hardening (1.9.0) followed.
- Next: `2.0.0` Spring Boot 3.x / Jakarta migration + enterprise security

### Documentation

- [API Usage Guide](docs/api-guide.md) — Complete integration guide with code examples
- [Netty Configuration](docs/netty-configuration.md) — HTTP / TLS / management endpoint reference
- [WebSocket Configuration](docs/websocket-configuration.md) — WebSocket runtime, crypto, observability
- [Release Notes - 1.9.0](docs/release-notes-1.9.0.md) — Current stable (1.9.0 GA — cluster reliability + reliable delivery + HMAC + Micrometer + W3C TraceContext + Redis Cluster + NATS)
- [Release Notes - 1.8.0](docs/release-notes-1.8.0.md) — WebSocket cluster support
- [Release Notes - 1.7.1](docs/release-notes-1.7.1.md)
- [Release Notes - 1.7.0](docs/release-notes-1.7.0.md)
- [Development Plan](docs/development-plan.md) — Roadmap (1.9.0 cluster hardening, 2.0.0 Spring Boot 3.x)
- [Cluster Design](docs/cluster-design.md) — Redis Pub/Sub cluster architecture, 1.8.0/1.9.0 scope vs roadmap, and the security/trust model
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
    <version>1.9.0</version>
</dependency>
```

Maven Central 上以 `io.github.berrywang1996:*` 提供（版本 `1.4.0`、`1.6.2`、`1.7.0`、`1.8.0`、`1.9.0`）。早期 `com.github.berrywang1996:*` 仅发布到私有仓库——迁移时只需把 `pom.xml` 里的 groupId 改成新的即可。

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

### WebSocket 集群 *(v1.8.0 引入 / v1.9.0 可靠性硬化 / v1.10.0 IM 平台基础)*

通过 Redis Pub/Sub 实现跨节点广播和单播。默认单机模式零开销；一个配置开关即可启用集群：

```xml
<!-- 在已有 starter 旁加入集群 starter -->
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-websocket-cluster-spring-boot-starter</artifactId>
    <version>1.9.0</version>
</dependency>
```

```properties
server.netty.websocket.cluster.enable=true
# 生产环境用专用、网络隔离、带认证 + TLS 的 Redis：
server.netty.websocket.cluster.redis.uri=rediss://:password@your-redis:6379
```

**业务代码零改动** — `MessageSender` 自动切换为 `ClusterMessageSender`，跨节点广播和单播即刻生效。

> ⚠️ **安全**：Redis 是集群控制平面——任何能向它 `PUBLISH` 的人都能注入/关闭任意会话。生产必须用专用、网络隔离、带密码 + TLS 的 Redis。应用层 AES-GCM **不**延伸过 Redis（明文会扇出到远端节点）。详见 [集群方案设计 §安全模型](docs/cluster-design.md)。

#### IM 平台基础 *(v1.10.0，按需启用)*

1.10.0 在集群内核之上叠加四个可选 IM 原语——均默认关闭，`cluster.enable=false` 与 1.9.0 逐字节一致：

- **房间维度路由**——`roomMessage(uri, room, msg)` 只定向承载该房间成员的节点（有界房间扇出 N/k）。`cluster.room.enable=true`。
- **离线队列 + 按用户寻址投递**——`sendToUser(userId, msg)` 在线实时投递，离线则入队、重连时 FIFO 回填（每用户 Redis Stream）。`cluster.offline.enable=true`。
- **多设备聚合在线状态**——按用户跨设备聚合 `ONLINE`/`AWAY`/`OFFLINE` + `PRESENCE_CHANGE` 事件，含崩溃路径权威 `→OFFLINE`。`cluster.presence.enable=true`。
- **节点间 mesh 传输**——`MeshBroker` 走直连 Netty TCP 取代 Redis Pub/Sub（Redis 仅用于发现），含**按兴趣路由**的扇出削减、热路径健壮性（Redis 移出广播热路径）、以及九个 `netty.cluster.mesh.*` 指标含 `fanout.target_nodes` 削减 gauge。`cluster.mesh.enable=true`。

> mesh 突破 ~10 节点 Redis Pub/Sub 广播上限**仅对按兴趣分区的活跃受众**成立——全局或高并发人口话题在随机 LB 下仍会饱和整个集群（诚实前提）。详见 [API 指南 §9.4–§9.7](docs/api-guide.md#9-websocket-cluster) 与 [集群方案设计](docs/cluster-design.md)。

#### 性能基准

**测试方法学**：2026-06-08 在 Intel i9-14900HX（24 核 / 32 线程）、64 GB RAM、Windows 11 上测得；JDK 17 GraalVM 17.0.11+7、Maven 3.9.16；Docker Desktop 29.5.2 跑 Redis 7-alpine + NATS 2.10（开启 JetStream），均走 loopback。零依赖 `SimpleTextEnvelopeCodec`。所有测试丢弃 500 条预热消息后再测；扇出路径用 5,000 条主样本、跨节点 / 可靠路径用 2,000 条主样本。复现：`mvn -pl netty-spring-websocket-cluster test -Dtest=PerformanceBenchmark`（9 个有序测试方法）。

| # | 场景 | 吞吐量 | 平均延迟 | 说明 |
| --- | --- | --- | --- | --- |
| 1 | 本地广播基线 | **2,104,111 msg/s** | **0.5 µs** | `DefaultMessageSender`，无 Redis，纯内存扇出 |
| 2 | 集群广播（Redis Pub/Sub） | **198,353 msg/s** | **5.0 µs** | `ClusterMessageSender` → 本地扇出 + 异步 PUBLISH |
| 3 | Raw Redis Pub/Sub | **32,651 msg/s** | **30.6 µs** | Lettuce PUBLISH → SUBSCRIBE 端到端 |
| 4 | 双节点跨节点（Redis） | **33,389 msg/s** | **30.0 µs** | 节点 A → Redis → 节点 B |
| 5 | Raw NATS Pub/Sub | **267,071 msg/s** | **3.7 µs** | jnats publish → message handler 端到端 |
| 6 | 双节点跨节点（NATS 传输 + Redis 注册表） | **32,514 msg/s** | **30.8 µs** | RC9 混合模式；受 `ClusterMessageSender` pipeline 限制 |
| 7 | 可靠广播（Redis Streams） | **18,047 msg/s** | **55.4 µs** | RC2 `RedisStreamsReliableBroker`，XADD + XREADGROUP 至少一次 |
| 8 | 可靠广播（NATS JetStream） | **3,391 msg/s** | **294.9 µs** | RC13 `NatsJetStreamReliableBroker`，FILE 存储 + 拉取循环 |
| 9 | HMAC 签名+验签开销（孤立 CPU） | 3.59M → 0.37M msg/s | +2.4 µs | RC3 HMAC-SHA256；孤立 micro-bench ~90% 吞吐损失，真实 I/O 下 < 5% |

**诚实解读：**

- **Loopback Docker** 是延迟下限场景（无网络 RTT、无副本、无 TLS）。真实跨可用区 Redis/NATS 会增加 0.5-2 ms RTT，吞吐相应下降。
- **NATS 传输层 8× raw 吞吐优势（267k vs 33k）不会按比例转化为跨节点 gains** —— `ClusterMessageSender` 的 pipeline 开销（信封构建、codec、查询、扇出）在此机器上把两边都封顶在 ~30k msg/s。NATS 真正发力在你接近 Redis Pub/Sub 的扇出墙后（详见 [集群方案设计 §容量规划](docs/cluster-design.md)）。
- **可靠 broker 的代价符合直觉。** Redis Streams 比 fire-and-forget Pub/Sub 慢约 5×；NATS JetStream 更慢（磁盘 fsync + pull-based fetch，`poll-block-ms` 默认 2 秒）。可靠路径是给「**必须**容忍订阅方短时下线」的广播用的，**不是热路径**。
- **HMAC 90% 的 micro-bench 开销** 是签名+验签在零 I/O 下的孤立 CPU 成本。加上真实网络 30 µs RTT，2.4 µs 的 HMAC 占总往返不到 8%——可忽略。Redis 与不可信发布方共享时，**坚持开** `auth.enable=true`。

#### 选型建议

| 维度 | 单机（默认） | 集群——Redis (`cluster.enable=true`) | 集群——NATS (`nats.servers=...`) |
| --- | --- | --- | --- |
| 适用场景 | ≤ 1 台服务器、≤ 25k 连接 | 多台服务器水平扩展 | 规模化档位（ADR-001），Redis Pub/Sub 扇出墙之后 |
| 广播延迟（loopback） | ~0.5 µs（纯内存） | ~30 µs（经 Redis Pub/Sub） | ~31 µs（经 NATS）——同 `ClusterMessageSender` pipeline 上限 |
| 跨节点吞吐 | 本地 ≥ 210 万 msg/s | ~33k msg/s（Redis 瓶颈） | ~33k msg/s 同；raw NATS 头空间 267k 留作未来扩展 |
| 可靠广播 | 不适用 | RC2 Redis Streams — 18k msg/s，至少一次 | RC13 JetStream — 3.4k msg/s，至少一次 + 回放 |
| 额外依赖 | 无 | Redis 7+ | NATS 2.10+（+ JetStream 用于 `nats.registry=true` 与可靠广播）|
| 故障影响 | 进程死 = 全断 | 一个节点死 = 仅该节点用户断 | 同 |
| 配置成本 | 零 | 一行 + Redis URI | 一行 + NATS URI（registry 默认仍在 Redis）|

#### 容量规划

| 目标连接 | 节点数 | 推荐传输 | 集群广播上限 |
| --- | --- | --- | --- |
| ≤ 25k | 1（单机） | 不需要 | ≥ 210 万 msg/s（纯内存） |
| 25k – 75k | 2–3 | Redis Standalone / Sentinel | ~33k msg/s |
| 75k – 250k | 4–10 | Redis Sentinel（推荐）或 NATS 传输 | ~33k msg/s（单主 / 单 NATS） |
| 250k – 100 万 | 10–20 | NATS 传输（RC9 ADR-001 规模化档位） | NATS raw 头空间 267k；当前受 sender pipeline 上限 ~33k msg/s |
| > 100 万 | > 20 | Sharded pub/sub（Boot 3.x / 2.0.0） | 超出 Lettuce 6.1 — 详见 [Boot 3.x 兼容矩阵](docs/2.0.0/boot3-compatibility-matrix.md) |

#### 选型指南——什么情况下选什么

六个独立决策。除非明确满足条件，否则每步都默认 **NO**——每一个"是"都意味着增加运维复杂度。

##### 决策 1——要不要开集群？（`cluster.enable`）

- **单机（`cluster.enable=false`，默认）**：如果同时满足——只用 ≤ 1 台服务器；并发 WebSocket ≤ 25k；单节点停机可接受；不想运维 Redis。
- **集群（`cluster.enable=true`）**：如果满足任一——> 1 台服务器；预期 > 25k 连接；需要滚动发布不能全断；已经在运维 Redis 7+。

> 单机模式与 1.7.x/1.8.0 字节级一致，broker 开销为零。**没有具体用例不要"为了安全"开集群。**

##### 决策 2——传输层选 Redis 还是 NATS？

| 问题 | 选 Redis Pub/Sub（默认） | 选 NATS broker（RC9） | 选全 NATS 栈（RC10） |
|---|---|---|---|
| 已有 Redis 运维 | ✅ | （registry 继续用 Redis） | — |
| 预期广播速率 | ≤ 30k msg/s 跨节点 | 接近 Redis Pub/Sub 扇出墙（详见 cluster-design §容量规划） | 同左 + 想只维护一个中间件 |
| 接受同时运维两个中间件（Redis + NATS） | ✅ | ✅ | ❌ |
| 已有 JetStream NATS 服务器 | 不需要 | 不需要 | **必需**（`nats-server -js`） |
| 配置 | `cluster.redis.uri=...` | `cluster.nats.servers=nats://...` | + `cluster.nats.registry=true` |
| 支持可靠广播 | 支持（Redis Streams，RC2） | 支持（用 Redis Streams 的 registry 层） | 支持（NATS JetStream，RC13） |

NATS broker（RC9）**只**替换跨节点广播/单播路径；registry + 心跳仍在 Redis，除非额外开 `nats.registry=true`（RC10）。

##### 决策 3——Redis 拓扑（Standalone / Sentinel / Cluster）？

- **Standalone**（`cluster.redis.uri=redis://...`）——开发、demo、单主生产 ≤ 10 节点。
- **Sentinel**（`cluster.redis.uri=redis-sentinel://...?sentinelMasterId=mymaster`）——生产 HA 自动故障转移；≤ 10 集群节点；托管 Redis（AWS ElastiCache、阿里云 Tair）原生支持。
- **Redis Cluster**（`cluster.redis.cluster-nodes=host1:6379,host2:6379,...`，RC7）——SessionRegistry + 心跳分片跨多个 Redis primary；registry 写入速率或内存超出单 primary 上限时启用。**RC7 注意事项**：cluster pub/sub 仍是常规模式（不削减广播扇出——sharded pub/sub 在 2.0.0）。

##### 决策 4——可靠广播？（`reliable.enable`）

**只有**当广播必须容忍订阅方短时下线（在 stream 保留窗口内）才开。

- **关（默认）**——`topicMessage()` 走 at-most-once Pub/Sub。广播触发瞬间下线的节点丢消息。
- **开（`reliable.enable=true`）**——`reliableBroadcast()` API 走 Redis Streams at-least-once（`nats.registry=true` 时走 NATS JetStream）。每个订阅节点有一个 durable cursor；重连时回放积压（在 `stream-max-len` 范围内，默认每 URI 10,000 条）。

代价：吞吐 ~5× 慢、延迟 ~6× 高（见跑分 §7 vs §3）。**选择性使用** `reliableBroadcast()`——非关键的 fire-and-forget 通道继续用 `topicMessage()`。

##### 决策 5——HMAC 信封鉴权？（`auth.enable`）

如果**任何人**都能向 Redis `PUBLISH` 就开（共享 Redis、多租户、网络隔离弱）。仅当 Redis 独占、网络隔离且发布方可证明可信时关闭。

- **关（默认）**——任何拥有 Redis PUBLISH 权限的人都能伪造 `originNodeId`、注入广播、强制关闭任意会话。
- **开（`auth.enable=true` + `auth.secret=${CLUSTER_AUTH_SECRET}`）**——每个信封都被 HMAC-SHA256 签名。灰度策略：先开 `permissive=true`（允许无签名入站），全量后再切严格。

代价：每个信封约 2.4 µs CPU（见跑分 §9）。真实网络延迟（30+ µs）下，HMAC 占往返 < 8%——可忽略。**任何跨越不可信边界的部署都默认开。**

##### 决策 6——W3C TraceContext？（`trace-propagation.enable`，RC6）

如果已经在用分布式 trace（Sleuth、OpenTelemetry、Brave），开它——日志里跨节点广播的 MDC `traceId` 会延续。

- **关（默认）**——日志关联在 broker 跳点断掉。
- **开**——信封里增加 `traceparent`；接收端在投递前后包一个 MDC scope。默认 MDC 模式零配置加性，不需要接 tracer。Sleuth/OTel 用户可注入自定义 `ClusterTraceContext` bean 接活跃 span。

##### 决策 7——应用层加密？（`crypto.enable`，比集群更广义）

WebSocket 帧用 AES-GCM 在应用层加密（**在 TLS 之外，不是替代 TLS**）。只有端到端机密性确实要求"LB 终止 TLS 不够"时才用。

- **关（默认）**——TLS 通常足够。
- **开**——`crypto.include-uris=/ws/secret` 保护特定 URI。**注意**：**不会跨集群 broker 延伸**——Redis 上看到的是明文信封。配合 `auth.enable=true` 用以保证传输完整性。

#### 常见部署配方

##### 配方 A——小型产品、单机（≤ 25k 连接）

```properties
server.netty.port=8080
server.netty.websocket.max-connections=25000
server.netty.websocket.heartbeat-interval-seconds=30
server.netty.websocket.heartbeat-timeout-seconds=90
# 就这样。无集群、无 Redis。本地内存约 210 万 msg/s。
```

##### 配方 B——标准集群产品（2-5 节点、专用 Redis）

```properties
server.netty.websocket.cluster.enable=true
server.netty.websocket.cluster.redis.uri=rediss://:${REDIS_PASSWORD}@redis.internal:6379

# Redis 不是 100% 可信时开 HMAC
server.netty.websocket.cluster.auth.enable=true
server.netty.websocket.cluster.auth.secret=${CLUSTER_AUTH_SECRET}

# 已有 tracing 就开 trace 关联
server.netty.websocket.cluster.trace-propagation.enable=true
```

##### 配方 C——高可靠产品（广播必须熬过订阅方短时下线）

```properties
server.netty.websocket.cluster.enable=true
server.netty.websocket.cluster.redis.uri=rediss://:${REDIS_PASSWORD}@redis.internal:6379
server.netty.websocket.cluster.auth.enable=true
server.netty.websocket.cluster.auth.secret=${CLUSTER_AUTH_SECRET}

# 关键通道开可靠广播
server.netty.websocket.cluster.reliable.enable=true
server.netty.websocket.cluster.reliable.stream-max-len=50000   # 每个 URI 约 5 万条保留窗口
```

关键通道用 `clusterMessageSender.reliableBroadcast(uri, msg)`；非关键 fire-and-forget 仍用 `topicMessage()`。

##### 配方 D——高规模（接近 Redis Pub/Sub 扇出墙、5-15 节点）

```properties
server.netty.websocket.cluster.enable=true
# NATS broker 替换 Redis Pub/Sub 做跨节点扇出
server.netty.websocket.cluster.nats.servers=nats://nats-1.internal:4222,nats://nats-2.internal:4222
# Registry/心跳保留在 Redis
server.netty.websocket.cluster.redis.uri=rediss://:${REDIS_PASSWORD}@redis.internal:6379
server.netty.websocket.cluster.auth.enable=true
server.netty.websocket.cluster.auth.secret=${CLUSTER_AUTH_SECRET}
```

##### 配方 E——全 NATS 栈（完全不用 Redis）

```properties
server.netty.websocket.cluster.enable=true
server.netty.websocket.cluster.nats.servers=nats://nats-1.internal:4222,nats://nats-2.internal:4222
server.netty.websocket.cluster.nats.registry=true                       # NATS JetStream-KV 注册表/心跳/reaper
server.netty.websocket.cluster.reliable.enable=true                     # JetStream 支撑的可靠广播
server.netty.websocket.cluster.auth.enable=true
server.netty.websocket.cluster.auth.secret=${CLUSTER_AUTH_SECRET}
```

需要 JetStream-enabled NATS 服务器（`nats-server -js`）。运维更简单（只一个中间件），但可靠吞吐比 Redis Streams 低（见跑分 §7 vs §8）。

##### 配方 F——Redis Cluster（registry 写入速率极高 / 分片 registry）

```properties
server.netty.websocket.cluster.enable=true
# 用 cluster-nodes 替代 uri——自动选用 RedisClusterMode* SPI 实现（RC7）
server.netty.websocket.cluster.redis.cluster-nodes=redis-1.internal:6379,redis-2.internal:6379,redis-3.internal:6379
server.netty.websocket.cluster.auth.enable=true
server.netty.websocket.cluster.auth.secret=${CLUSTER_AUTH_SECRET}
# 注意：常规 cluster pub/sub 不削减扇出。Sharded pub/sub 在 2.0.0。
```

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

- **`1.10.0` GA 已切版 + 打标签（`v1.10.0`）、FF 合并到 master；推送 + 部署到 Maven Central 待执行（用户驱动）。** 在 1.9.0 之上叠加 **IM 平台基础**：房间维度路由、离线队列 + 按用户寻址 `sendToUser`、多设备在线状态、以及节点间 **mesh** 传输（按兴趣路由扇出削减 + 九个 `netty.cluster.mesh.*` 指标）。`cluster.enable=false` 与 1.9.0 逐字节一致。**644 个测试 / 11 个模块全绿。** 见 [1.10.0 发布说明](docs/release-notes-1.10.0.md) 与 [API 指南 §9](docs/api-guide.md#9-websocket-cluster)。*（在 Central 部署完成前，下方 Maven 坐标仍解析为 `1.9.0`。）*
- **Maven Central 最新稳定版：`1.9.0` GA**（2026-06-07 发布）。上一稳定版：`1.8.0`。1.9.0 GA 周期交付：集群可靠性硬化（5 项 1.8.0 推迟项 + 2 个新配置项）、可靠投递（Redis Streams + NATS JetStream，至少一次，按需启用）、HMAC 信封鉴权（`MessageAuthenticator` SPI）、完整 Micrometer 集群指标、W3C TraceContext 跨节点透传、Redis Cluster 客户端、多/分片 Pub/Sub 多路复用、NATS 传输 + 全 NATS 技术栈（JetStream-KV 注册表/心跳/leader）、多节点 E2E + Testcontainers CI、以及 GA 就绪审计。**444 个测试 / 11 个模块全绿。** 单机模式生产级、与 1.7.x/1.8.0 完全一致——见 [1.9.0 发布说明](docs/release-notes-1.9.0.md) 与 [集群方案设计 §安全模型](docs/cluster-design.md)。
- `1.8.0` 交付 WebSocket 集群支持（Redis Pub/Sub 跨节点广播/单播 + 5 层 SPI 可插拔架构 + 291 个测试全绿）——在 `1.9.0` 中完整保留，全部向后兼容。
- P0 至 P7 全部里程碑已完成；其后依次推进性能（1.6.x）、安全稳定性（1.6.2）、可观测性（1.7.0）、集群水平扩展（1.8.0）、集群可靠性硬化（1.9.0）。
- 下一步：`2.0.0` Spring Boot 3.x / Jakarta 迁移 + 企业安全版本

### 文档

- [API 使用指南](docs/api-guide.md) — 完整接入指南，含代码示例
- [Netty 配置说明](docs/netty-configuration.md) — HTTP / TLS / 管理端点参考
- [WebSocket 配置说明](docs/websocket-configuration.md) — WebSocket 运行时、加密、可观测性
- [1.9.0 发布说明](docs/release-notes-1.9.0.md) — 当前稳定版（1.9.0 GA — 集群可靠性 + 可靠投递 + HMAC + Micrometer + W3C TraceContext + Redis Cluster + NATS）
- [1.8.0 发布说明](docs/release-notes-1.8.0.md) — WebSocket 集群支持
- [1.7.1 发布说明](docs/release-notes-1.7.1.md)
- [1.7.0 发布说明](docs/release-notes-1.7.0.md)
- [开发计划与阶段状态](docs/development-plan.md) — 路线图（1.9.0 集群硬化、2.0.0 Spring Boot 3.x）
- [集群方案设计](docs/cluster-design.md) — Redis Pub/Sub 集群架构、1.8.0/1.9.0 实现范围 vs 路线图、安全/信任模型
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
