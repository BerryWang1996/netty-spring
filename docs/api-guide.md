# netty-spring API Usage Guide

> Version: 1.9.0 | Updated: 2026-05-31

This guide walks through the most common integration scenarios for `netty-spring`. Each section is self-contained — jump directly to the scenario that matches your use case.

---

## Table of Contents

1. [Starter Selection](#1-starter-selection)
2. [Minimal HTTP MVC Application](#2-minimal-http-mvc-application)
3. [HTTP + WebSocket Application](#3-http--websocket-application)
4. [WebSocket-Only Application](#4-websocket-only-application)
5. [WebSocket Messaging API](#5-websocket-messaging-api)
6. [Chat Room Pattern (Multi-User)](#6-chat-room-pattern-multi-user)
7. [Handshake Authentication](#7-handshake-authentication)
8. [Application-Layer Encryption](#8-application-layer-encryption)
9. [WebSocket Cluster](#9-websocket-cluster)
10. [Metrics & Monitoring](#10-metrics--monitoring)
11. [Configuration Reference](#11-configuration-reference)
12. [Annotation Reference](#12-annotation-reference)
13. [Troubleshooting](#13-troubleshooting)

---

## 1. Starter Selection

Choose a starter based on what protocols you need:

| Scenario | Starter Artifact | What It Activates |
| --- | --- | --- |
| HTTP MVC only | `netty-webmvc-spring-boot-starter` | HTTP routing, `@RequestMapping`, `@GetMapping`, etc. |
| WebSocket only | `netty-websocket-spring-boot-starter` | `@MessageMapping`, `MessageSender`, crypto |
| HTTP + WebSocket | `netty-web-spring-boot-starter` | Both MVC and WebSocket in one server |

All starters share the `server.netty.*` configuration namespace and auto-configure a single Netty server instance.

**Maven dependency example** (HTTP + WebSocket):

```xml
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-web-spring-boot-starter</artifactId>
    <version>1.8.0</version>
</dependency>
```

---

## 2. Minimal HTTP MVC Application

### application.properties

```properties
server.netty.port=8080
```

### Controller

```java
@Controller
public class HelloController {

    @GetMapping("/hello")
    @ResponseBody
    public Map<String, Object> hello(@RequestParam String name) {
        return Map.of("message", "Hello, " + name);
    }

    @PostMapping("/echo")
    @ResponseBody
    public String echo(String body) {
        return body;
    }
}
```

### Available MVC Annotations

| Annotation | Target | Purpose |
| --- | --- | --- |
| `@RequestMapping` | Method, Type | Map by path, HTTP method, and port |
| `@GetMapping` | Method | Shortcut for GET requests |
| `@PostMapping` | Method | Shortcut for POST requests |
| `@PutMapping` | Method | Shortcut for PUT requests |
| `@DeleteMapping` | Method | Shortcut for DELETE requests |
| `@PatchMapping` | Method | Shortcut for PATCH requests |
| `@PathVariable` | Parameter | Bind URL path variable |
| `@RequestParam` | Parameter | Bind query parameter |
| `@ResponseBody` | Method, Type | Write return value directly to response body |
| `@RestController` | Type | `@Controller` + implicit `@ResponseBody` |

### Path Variables

```java
@GetMapping("/users/{id}")
@ResponseBody
public User getUser(@PathVariable String id) {
    return userService.findById(id);
}
```

### Returning HTML

If a method returns a `String` without `@ResponseBody`, it is treated as raw HTML:

```java
@RequestMapping("/page")
public String page() {
    return "<html><body>Hello</body></html>";
}
```

---

## 3. HTTP + WebSocket Application

Use `netty-web-spring-boot-starter` and define both HTTP and WebSocket handlers in the same project.

### application.properties

```properties
server.netty.port=8080
server.netty.mvc.enable=true
server.netty.websocket.enable=true
```

### HTTP Controller

```java
@RestController
public class ApiController {
    @GetMapping("/api/status")
    public Map<String, Object> status() {
        return Map.of("status", "ok");
    }
}
```

### WebSocket Controller

```java
@Controller
public class ChatController {

    private final MessageSender messageSender;

    public ChatController(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    @MessageMapping(value = "/ws/chat", messageType = MessageType.ON_CONNECTED)
    public void onConnected(MessageSession session) {
        // New client connected
    }

    @MessageMapping(value = "/ws/chat", messageType = MessageType.TEXT_MESSAGE)
    public void onMessage(String text, MessageSession session) {
        messageSender.broadcastText("/ws/chat", text);
    }

    @MessageMapping(value = "/ws/chat", messageType = MessageType.ON_CLOSE)
    public void onClose(MessageSession session) {
        // Client disconnected
    }
}
```

---

## 4. WebSocket-Only Application

Use `netty-websocket-spring-boot-starter` and set `server.netty.mvc.enable=false` (or simply omit MVC controllers).

```properties
server.netty.port=8080
server.netty.websocket.enable=true
```

---

## 5. WebSocket Messaging API

### MessageSender

`MessageSender` is the primary API for sending messages to WebSocket clients. Inject it via constructor or `@AutowiredMessageSender`.

**Constructor injection (recommended):**

```java
@Controller
public class MyController {
    private final MessageSender messageSender;

    public MyController(MessageSender messageSender) {
        this.messageSender = messageSender;
    }
}
```

**Field injection (alternative):**

```java
@Controller
public class MyController {
    @AutowiredMessageSender
    private MessageSender messageSender;
}
```

### Sending Messages

```java
// Broadcast text to all clients on a URI
messageSender.broadcastText("/ws/chat", "Hello everyone");

// Broadcast JSON object
messageSender.broadcastJson("/ws/chat", Map.of("type", "alert", "text", "Server restarting"));

// Send to a specific session
messageSender.sendTextToSession("/ws/chat", "Private hello", sessionId);
messageSender.sendJsonToSession("/ws/chat", myObject, sessionId);

// Send to multiple sessions
messageSender.sendText("/ws/chat", "Hello group", sessionId1, sessionId2);
```

### Session Management

```java
// Get number of active sessions
int total = messageSender.getSessionNums();
int onUri = messageSender.getSessionNums("/ws/chat");

// Get session IDs
Set<String> ids = messageSender.getSessionIds("/ws/chat");

// Get a specific session
MessageSession session = messageSender.getSession("/ws/chat", sessionId);

// Check if a session is alive
boolean alive = messageSender.isSessionAlive("/ws/chat", sessionId);

// Close a session
messageSender.closeSession("/ws/chat", sessionId);

// Close all sessions on a URI
messageSender.closeSessions("/ws/chat");
```

### @MessageMapping Lifecycle Events

| MessageType | When | Handler Signature |
| --- | --- | --- |
| `ON_HANDSHAKE` | Before WebSocket upgrade | `boolean onHandshake(MessageSession)` — return `false` to reject |
| `ON_CONNECTED` | Connection established | `void onConnected(MessageSession)` |
| `TEXT_MESSAGE` | Text frame received | `void onMessage(String text, MessageSession)` or `void onMessage(MyPojo pojo, MessageSession)` |
| `BINARY_MESSAGE` | Binary frame received | `void onBinary(byte[] data, MessageSession)` |
| `ON_PING` | Ping frame received | `void onPing(MessageSession)` |
| `ON_ERROR` | Exception in other handlers | `void onError(Exception e)` |
| `ON_CLOSE` | Session closing | `void onClose(MessageSession)` |

### JSON Auto-Binding

Text messages containing JSON can be automatically deserialized to a POJO:

```java
public static class ChatMessage {
    private String type;
    private String text;
    // getters and setters
}

@MessageMapping(value = "/ws/chat", messageType = MessageType.TEXT_MESSAGE)
public void onMessage(ChatMessage msg, MessageSession session) {
    // msg is auto-deserialized from the JSON text frame
}
```

Supported target types: POJO, `Map`, `Collection`, arrays, enums, wrapper types.
Deserialization failures are routed to the `ON_ERROR` handler.

### MessageSession API

```java
// Basic info
String id = session.getSessionId();
String path = session.getPath();         // e.g., "/ws/chat"
String uri = session.getUri();           // e.g., "/ws/chat?room=demo"

// Query parameters
String room = session.getQueryParam("room");
List<String> tags = session.getQueryParams("tag");
Map<String, List<String>> allParams = session.getQueryParams();

// Headers
String auth = session.getHeader("Authorization");
Set<String> headerNames = session.getHeaderNames();
```

---

## 6. Chat Room Pattern (Multi-User)

A complete example demonstrating join/leave notifications, online user list, broadcast, and private messages.

### Controller

```java
@Controller
public class ChatRoomController {

    private static final String CHAT_URI = "/ws/chat";
    private final MessageSender messageSender;
    private final Map<String, String> nicknames = new ConcurrentHashMap<>();

    public ChatRoomController(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    @MessageMapping(value = CHAT_URI, messageType = MessageType.ON_CONNECTED)
    public void onConnected(MessageSession session) {
        String nickname = session.getQueryParam("nickname");
        if (nickname == null || nickname.isBlank()) {
            nickname = "User-" + session.getSessionId().substring(0, 6);
        }
        nicknames.put(session.getSessionId(), nickname);

        // Notify everyone
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "join");
        event.put("nickname", nickname);
        event.put("onlineUsers", new ArrayList<>(nicknames.values()));
        messageSender.broadcastJson(CHAT_URI, event);
    }

    @MessageMapping(value = CHAT_URI, messageType = MessageType.TEXT_MESSAGE)
    public void onMessage(ChatMessage msg, MessageSession session) {
        String nickname = nicknames.getOrDefault(session.getSessionId(), "Unknown");

        if ("private".equals(msg.getType()) && msg.getTarget() != null) {
            // Private message
            String targetId = findSessionByNickname(msg.getTarget());
            if (targetId != null) {
                messageSender.sendJsonToSession(CHAT_URI,
                    Map.of("type", "private", "from", nickname, "text", msg.getText()),
                    targetId);
            }
        } else {
            // Broadcast
            messageSender.broadcastJson(CHAT_URI,
                Map.of("type", "message", "nickname", nickname, "text", msg.getText()));
        }
    }

    @MessageMapping(value = CHAT_URI, messageType = MessageType.ON_CLOSE)
    public void onClose(MessageSession session) {
        String nickname = nicknames.remove(session.getSessionId());
        if (nickname != null) {
            messageSender.broadcastJson(CHAT_URI,
                Map.of("type", "leave", "nickname", nickname));
        }
    }
}
```

### Browser Client

```javascript
const ws = new WebSocket('ws://localhost:8080/ws/chat?nickname=Alice');

ws.onmessage = (e) => {
    const data = JSON.parse(e.data);
    if (data.type === 'join') console.log(data.nickname + ' joined');
    if (data.type === 'message') console.log(data.nickname + ': ' + data.text);
    if (data.type === 'private') console.log('[PM from ' + data.from + '] ' + data.text);
};

// Broadcast message
ws.send(JSON.stringify({ type: 'message', text: 'Hello everyone!' }));

// Private message
ws.send(JSON.stringify({ type: 'private', target: 'Bob', text: 'Hi Bob!' }));
```

---

## 7. Handshake Authentication

### Using WebSocketHandshakeInterceptor

Register a Spring bean implementing `WebSocketHandshakeInterceptor` to authenticate connections before the WebSocket upgrade:

```java
@Bean
public WebSocketHandshakeInterceptor authInterceptor() {
    return new WebSocketHandshakeInterceptor() {
        @Override
        public boolean beforeHandshake(FullHttpRequest request, String uri) {
            // Check query parameter
            QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
            List<String> tokens = decoder.parameters().get("token");
            if (tokens != null && !tokens.isEmpty()) {
                return "my-secret-token".equals(tokens.get(0));
            }
            // Check Authorization header
            String auth = request.headers().get("Authorization");
            return auth != null && auth.startsWith("Bearer ")
                && validateToken(auth.substring(7));
        }

        @Override
        public String rejectionReason() {
            return "Invalid or missing authentication token";
        }
    };
}
```

The interceptor runs after Origin check but before `@MessageMapping(messageType = ON_HANDSHAKE)`. Returning `false` sends HTTP 403 to the client.

### Using ON_HANDSHAKE Callback

For simpler cases, use `@MessageMapping` with `ON_HANDSHAKE`. The callback fires
before the session exists, so the framework injects the `HttpRequest` (the
handshake request) rather than a `MessageSession`:

```java
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

@MessageMapping(value = "/ws/secure", messageType = MessageType.ON_HANDSHAKE)
public boolean onHandshake(HttpRequest request) {
    QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
    List<String> tokens = decoder.parameters().get("token");
    String token = (tokens == null || tokens.isEmpty()) ? null : tokens.get(0);
    return token != null && isValidToken(token);
}
```

Return `false` to reject the handshake (the client sees HTTP 403). For more
sophisticated flows, prefer the `WebSocketHandshakeInterceptor` shown above,
which offers a structured rejection reason.

### Origin Restriction

```properties
# Only allow connections from specific origins
server.netty.websocket.allowed-origins=https://myapp.com,https://staging.myapp.com
```

---

## 8. Application-Layer Encryption

Encrypt WebSocket frames so browser DevTools see ciphertext instead of plaintext business data.

> **Note:** This is an application-layer encryption feature, not a replacement for TLS/WSS. Use WSS in production for transport security.

### Enable Encryption

```properties
server.netty.websocket.crypto.enable=true
server.netty.websocket.crypto.algorithm=AES-GCM
server.netty.websocket.crypto.key-id=my-key-2026
server.netty.websocket.crypto.key-provider=myKeyProvider
```

### Implement a Key Provider

```java
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

@Bean("myKeyProvider")
public MessageCryptoKeyProvider keyProvider() {
    return (keyId, session) -> {
        // In production, load keys from a secure key management service
        if ("my-key-2026".equals(keyId)) {
            byte[] keyBytes = loadKeyBytes(keyId);   // 16/24/32 bytes for AES-128/192/256
            return new SecretKeySpec(keyBytes, "AES");
        }
        return null;   // unknown kid → signals "key not available", caller handles it
    };
}
```

### URI-Level Control

```properties
# Only encrypt specific URIs
server.netty.websocket.crypto.include-uris=/ws/secure,/ws/private

# Or exclude specific URIs from encryption
server.netty.websocket.crypto.exclude-uris=/ws/public,/ws/debug
```

### Session-Level Policy

```java
@Bean
public MessageCryptoPolicy cryptoPolicy() {
    return session -> {
        // Disable crypto for internal monitoring connections
        String clientType = session.getQueryParam("client");
        return !"monitor".equals(clientType);
    };
}
```

### Key Properties

| Property | Default | Description |
| --- | --- | --- |
| `crypto.enable` | `false` | Master switch |
| `crypto.algorithm` | `CUSTOM` | Algorithm identifier (`AES-GCM` for built-in) |
| `crypto.key-id` | | Key identifier passed to key provider |
| `crypto.key-provider` | | Bean name of `MessageCryptoKeyProvider` |
| `crypto.encrypt-text` | `true` | Encrypt text frames |
| `crypto.encrypt-binary` | `true` | Encrypt binary frames |
| `crypto.close-on-decrypt-failure` | `true` | Close session on decryption error |
| `crypto.reject-unencrypted` | `true` | Reject unencrypted frames when crypto is enabled |

---

## 9. WebSocket Cluster

*Since V1.8.0; reliability-hardened in V1.9.0.* Scale WebSocket across multiple nodes via Redis Pub/Sub. **Default is single-node mode** (`cluster.enable=false`) with zero overhead and behavior identical to 1.7.x. Cluster mode is **opt-in** and targets **≤ ~10 nodes with a dedicated, secured Redis**.

### Enable Cluster

Add the cluster starter alongside your existing starter:

```xml
<dependency>
    <groupId>io.github.berrywang1996</groupId>
    <artifactId>netty-websocket-cluster-spring-boot-starter</artifactId>
    <version>1.8.0</version>
</dependency>
```

```properties
server.netty.websocket.cluster.enable=true
# Production: dedicated, network-isolated Redis with auth + TLS
server.netty.websocket.cluster.redis.uri=rediss://:password@your-redis:6379
```

**No business code changes** — your `MessageSender` is transparently replaced by a `ClusterMessageSender`. `broadcastText`/`broadcastJson` now fan out across all nodes; `sendTextToSession`/unicast routes to whichever node owns the target session.

```java
@MessageMapping(value = "/ws/chat", messageType = MessageType.TEXT_MESSAGE)
public void onMessage(String text, MessageSession session) {
    // Local + cross-node broadcast (at-most-once across nodes)
    messageSender.broadcastText("/ws/chat", text);
}
```

### Delivery Semantics (important)

| Path | Guarantee |
| --- | --- |
| Local delivery | **Never lost** — local fan-out happens first, even while degraded |
| Cross-node broadcast | **At-most-once** — Redis Pub/Sub is fire-and-forget; a node offline at publish time misses the message (no replay). Industry-standard default (same as Socket.IO Redis adapter / Spring STOMP relay) |
| Cross-node unicast | Undeliverable sessions are reported to the caller via `MessageSessionClosedException` |

For at-least-once cross-node broadcast, use `reliableBroadcast` (Redis Streams, added in 1.9.0-RC2) — see [§9.1 Reliable Broadcast](#91-reliable-broadcast-at-least-once) below.

### 9.1 Reliable Broadcast (at-least-once)

*Since V1.9.0-RC2.* An opt-in, at-least-once cross-node broadcast backed by Redis Streams. Existing `broadcastText`/`broadcastJson` (Pub/Sub, at-most-once) are **unchanged**.

#### Enable

```yaml
server:
  netty:
    websocket:
      cluster:
        enable: true
        reliable:
          enable: true          # default false — gated off until explicitly opted in
```

When `reliable.enable=false` (the default), no consumer threads or extra Redis connections are created, and calling `reliableBroadcast()` throws `IllegalStateException`.

#### Usage

```java
// Cast MessageSender to ClusterMessageSender, or inject it directly
clusterMessageSender.reliableBroadcast("/ws/chat", message);
```

The call performs an `XADD` to `netty:cluster:rstream:{uri}` and returns after the entry is written. Each live node consumes from its own consumer group (`g:{nodeId}`) via a dedicated blocking Lettuce connection.

#### How replay-on-resync works

A node that was offline when messages were published has its consumer-group cursor frozen at the last-consumed position. On reconnect, `XREADGROUP >` returns the backlog in order — the node processes all missed messages without any application-level intervention.

#### Delivery contract (read before enabling)

| Property | Detail |
| --- | --- |
| Guarantee | **At-least-once within the retention window** |
| Retention window | `stream-max-len` entries (default 10 000) per URI. A node offline longer than this window will miss trimmed entries — a bounded gap, not silent data loss. |
| Durability | Depends on your Redis persistence (AOF/RDB). Redis restart without persistence loses stream data. |
| Duplicates | In-process PEL dedup (`dedup-window`) handles replay duplicates within a process lifetime. Cross-crash duplicates are possible — **application handlers should be idempotent**. |
| Publish-time Redis | `XADD` requires Redis to be reachable. Failure triggers the existing `on-publish-failure` policy (`log`/`drop`) — never silent. |
| Latency | Slightly above Pub/Sub (consumer poll delay). This is why the feature is opt-in. |

#### Configuration reference (`server.netty.websocket.cluster.reliable.*`)

| Property | Default | Description |
| --- | --- | --- |
| `enable` | `false` | Master switch. `false` = zero overhead; `reliableBroadcast()` throws `IllegalStateException`. |
| `stream-max-len` | `10000` | Max entries per URI stream (`MAXLEN ~` approximate trim). Older entries are trimmed automatically. |
| `poll-block-ms` | `2000` | Blocking-read timeout on the consumer connection (ms). |
| `poll-count` | `64` | Max entries fetched per `XREADGROUP` call. |
| `dedup-window` | `1024` | In-process sliding-window size for PEL dedup. |

### Cluster-Wide Queries

`ClusterMessageSender` adds async, network-backed queries (cast `MessageSender` to `ClusterMessageSender`, or inject it directly):

```java
clusterSender.getClusterSessionIds("/ws/chat")          // CompletionStage<Set<String>>
             .thenAccept(ids -> ...);
clusterSender.isSessionAliveCluster("/ws/chat", sid)     // CompletionStage<Boolean>
             .thenAccept(alive -> ...);
clusterSender.getClusterRuntimeStats();                  // counters (publishFailures, cacheHitRatio, ...)
```

Local queries (`getSessionIds`, `isSessionAlive`, `getSessionNums`) remain **local-node only** and O(1) — they are not turned into hidden network calls.

### Security (must read)

> Redis is the cluster **control plane**. Anyone who can `PUBLISH` to it can inject messages into, or force-close, any WebSocket session. For production: use a **dedicated, network-isolated Redis** with a **password** (`redis://:secret@host`) and **TLS** (`rediss://`). 1.8.0 redacts the URI password in logs and warns when the URI has no TLS/auth. Application-layer AES-GCM does **not** extend across Redis — plaintext is fanned out to remote nodes.

### Pluggable Serialization (zero Jackson)

All serialization is SPI-based. Override any default with a `@Bean`:

```java
@Bean
public EnvelopeCodec envelopeCodec() { return new MyProtobufEnvelopeCodec(); }       // wire envelope
@Bean
public MessagePayloadCodec messagePayloadCodec() { return new MyPayloadCodec(); }     // message body
```

Likewise `ClusterBroker`, `SessionRegistry`, and `ClusterNodeHeartbeat` are `@ConditionalOnMissingBean` — provide your own to swap the transport (e.g. a future NATS/mesh impl) without touching application code.

### Health

With `spring-boot-actuator` on the classpath, `GET /actuator/health` includes a `nettyCluster` indicator reporting node state (ACTIVE/DEGRADED/RESYNC/DRAINING/LEFT), broker state, and runtime counters. DEGRADED/RESYNC report `UP` (the node still serves local traffic) so a transient Redis blip won't trigger an orchestrator pod kill.

See [`docs/cluster-design.md`](cluster-design.md) for the full architecture, capacity model, and roadmap.

---

## 10. Metrics & Monitoring

### Built-in Management Endpoints

```properties
server.netty.management.enable=true
server.netty.management.health-path=/netty/health
server.netty.management.status-path=/netty/status
```

- `GET /netty/health` — Health check (returns `{"status":"UP"}`)
- `GET /netty/status` — Runtime snapshot (handler stats, HTTP stats, WebSocket stats, event counters)

### Micrometer / Actuator Integration

Add `spring-boot-starter-actuator` to your dependencies. Netty metrics are automatically bridged to Micrometer when it is on the classpath.

**HTTP metrics:**

- `netty.http.response.write.failures`
- `netty.http.static.rejected`
- `netty.http.static.write.failures`
- `netty.http.idle.closes`
- Handler thread-pool gauges (pool size, active threads, queue depth, available permits, etc.) — *since V1.7.0*
- Netty allocator memory gauges (used heap / used direct bytes) — *since V1.7.0*

**WebSocket metrics:**

- `netty.websocket.handshakes.total` / `.success` / `.rejected`
- `netty.websocket.messages.received` / `.sent`
- `netty.websocket.sessions.closed` (tagged by `reason` — one series per `CloseReason` enum value)
- `netty.websocket.sessions.active` (gauge)
- `netty.websocket.sessions.active.uri` (gauge, tagged by `uri`) — *since V1.7.0*
- `netty.websocket.mappings` (gauge)
- `netty.websocket.connection.duration` (Timer, tagged by `reason`) — *since V1.7.0*
- `netty.websocket.message.size` (DistributionSummary, bytes) — *since V1.7.0*
- `netty.websocket.broadcast.fanout` (DistributionSummary, sessions per broadcast) — *since V1.7.0*
- `netty.websocket.handler.latency` (Timer) — *since V1.7.0*

Distribution metrics (duration / size / fanout / latency) are routed to every bound `MeterRegistry`, so they work correctly alongside a `CompositeMeterRegistry` or multiple registries.

### Structured Logging (MDC) — *since V1.7.0*

The framework populates SLF4J [MDC](http://www.slf4j.org/manual.html#mdc) for the duration of each request / WebSocket frame and clears it afterwards. Available keys:

| MDC key | Set for | Value |
| --- | --- | --- |
| `netty.requestId` | HTTP requests | Generated UUID per request |
| `netty.sessionId` | WebSocket frames & lifecycle callbacks | WebSocket session id |
| `netty.uri` | both | Request URI / WebSocket mapping URI |
| `netty.remoteAddr` | both | Client IP address |

Include them in your logback/log4j2 pattern — no code changes required:

```
%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} [%X{netty.requestId}] [%X{netty.sessionId}] - %msg%n
```

WebSocket lifecycle callbacks (`@OnConnected`, `@OnClose`, heartbeat and error handlers) run on the handler pool but still carry the session MDC context.

### Health Indicator — *since V1.7.0*

When `spring-boot-actuator` is on the classpath, a `NettyServerHealthIndicator` is auto-registered and contributes to `/actuator/health`:

- **UP** with details: bound port, handler pool size / active threads / queue size, connection permits available / limit.
- **DOWN** with a reason when the server is not running.

No configuration is required — it activates automatically when actuator is present.

### Example Actuator Setup

```properties
# Actuator on a separate management port
management.server.port=8081
management.endpoints.web.exposure.include=metrics,health,prometheus
```

Access metrics at: `http://localhost:8081/actuator/metrics/netty.websocket.sessions.active`
Health at: `http://localhost:8081/actuator/health`

---

## 11. Configuration Reference

All properties use the `server.netty.*` prefix.

### Core

| Property | Default | Description |
| --- | --- | --- |
| `port` | `8080` | Server listen port |
| `mvc.enable` | `true` | Enable HTTP MVC |
| `websocket.enable` | `true` | Enable WebSocket |

### HTTP

| Property | Default | Description |
| --- | --- | --- |
| `http.max-content-length` | `65536` | Max HTTP body size (bytes) |
| `http.max-initial-line-length` | `4096` | Max request line size |
| `http.max-header-size` | `8192` | Max header size |
| `http.max-chunk-size` | `8192` | Max chunk size |
| `http.read-timeout-seconds` | `0` | Read timeout (0 = disabled) |
| `http.write-timeout-seconds` | `0` | Write timeout (0 = disabled) |
| `http.idle-timeout-seconds` | `0` | Idle timeout (0 = disabled) |

### GZIP Compression

| Property | Default | Description |
| --- | --- | --- |
| `http.gzip.enable` | `false` | Enable response compression |
| `http.gzip.compression-level` | `6` | 0-9, higher = better compression |
| `http.gzip.content-size-threshold` | `0` | Min response size for compression |

### TLS/SSL

| Property | Default | Description |
| --- | --- | --- |
| `http.ssl.enable` | `false` | Enable TLS |
| `http.ssl.certificate` | | Path to X.509 certificate (PEM) |
| `http.ssl.certificate-key` | | Path to PKCS#8 private key (PEM) |
| `http.ssl.protocols` | | Allowed TLS protocols (comma-separated) |
| `http.ssl.ciphers` | | Allowed cipher suites (comma-separated) |

### WebSocket

| Property | Default | Description |
| --- | --- | --- |
| `websocket.max-connections` | `0` | Max connections (0 = unlimited) |
| `websocket.max-frame-payload-length` | `0` | Max single-frame size (0 = default 64KB) |
| `websocket.max-frame-aggregation-buffer-size` | `0` | Aggregate fragmented frames up to N bytes (0 = disabled). *Since V1.7.0* |
| `websocket.allowed-origins` | | Allowed origins (blank = all) |
| `websocket.heartbeat-interval-seconds` | `0` | Server ping interval (0 = disabled) |
| `websocket.heartbeat-timeout-seconds` | `0` | Inbound frame timeout (0 = disabled) |

### WebSocket Thread Pools

Configuration values of `0` (the field initializer) mean *dynamic default* —
the framework substitutes a value derived from CPU count at startup.

| Property | Default | Description |
| --- | --- | --- |
| `websocket.core-pool-size` | `max(2, CPU)` | Sender executor core threads (set to `0` → dynamic) |
| `websocket.max-pool-size` | `max(core, CPU*2)` | Sender executor max threads (set to `0` → dynamic) |
| `websocket.keep-alive-time` | `60` | Sender thread idle TTL (seconds) |
| `websocket.queue-capacity` | `0` | Sender queue capacity; `0` = `SynchronousQueue` |
| `websocket.handler-core-pool-size` | `max(2, CPU)` | Handler executor core threads (set to `0` → dynamic) |
| `websocket.handler-max-pool-size` | `max(core, CPU*2)` | Handler executor max threads (set to `0` → dynamic) |
| `websocket.handler-keep-alive-time` | `5` | Handler thread idle TTL (seconds) |
| `websocket.handler-queue-capacity` | `0` | Handler queue capacity; `0` = `SynchronousQueue` |
| `websocket.handler-permit-limit` | `max*2` | Total in-flight admission cap (set to `0` → dynamic) |

### WebSocket Broadcast

| Property | Default | Description |
| --- | --- | --- |
| `websocket.broadcast-mode` | `EVENT_LOOP_DIRECT` | `EVENT_LOOP_DIRECT` (v1.6+ zero-copy fan-out) or `THREAD_POOL_LEGACY` (v1.5.x compat) |
| `websocket.broadcast-non-writable-channel-policy` | `SKIP` | `SKIP` or `CLOSE` non-writable channels |
| `websocket.broadcast-rejected-execution-policy` | `DROP` | `DROP` or `CALLER_RUNS` when executor is full |
| `websocket.write-buffer-low-water-mark` | `32768` | Per-channel write buffer low mark (bytes) |
| `websocket.write-buffer-high-water-mark` | `65536` | Per-channel write buffer high mark; above this a channel is non-writable |
| `websocket.flush-consolidation-threshold` | `256` | `FlushConsolidationHandler` threshold; `0` or negative disables |

### WebSocket Cluster — *since V1.8.0; reliability-hardened in V1.9.0*

Namespace `server.netty.websocket.cluster.*`. Only active when `enable=true`; requires the `netty-websocket-cluster-spring-boot-starter`.

| Property | Default | Description |
| --- | --- | --- |
| `cluster.enable` | `false` | Master switch; `false` = single-node (zero overhead) |
| `cluster.node-id` | *(auto UUID)* | Unique node id; empty auto-generates |
| `cluster.redis.uri` | `redis://localhost:6379` | Redis URI; scheme selects topology (`redis://` / `redis-sentinel://`); use `rediss://` + auth in production |
| `cluster.heartbeat-interval-seconds` | `3` | Heartbeat write interval |
| `cluster.heartbeat-timeout-seconds` | `10` | Missing-heartbeat → dead-node threshold |
| `cluster.reconciliation-interval-seconds` | `15` | Slow-path dead-node sweep interval |
| `cluster.drain-timeout-seconds` | `60` | Graceful-shutdown session-close wait |
| `cluster.reconnect-jitter-max-seconds` | `10` | Max jitter before DEGRADED→RESYNC re-register |
| `cluster.registry-read-cache-ttl-ms` | `5000` | sessionId→nodeId unicast hot-path cache TTL |
| `cluster.command-timeout-ms` | `2000` | Redis command timeout — bounds hot-path blocking on Redis loss (vs Lettuce's 60s default) |
| `cluster.message-max-size-bytes` | `1048576` | Max serialized cluster message; larger is not published cross-node (`0` = unlimited) |
| `cluster.on-redis-loss` | `degrade-to-local` | `degrade-to-local` (keep local sessions) or `close-all` |
| `cluster.on-publish-failure` | `log` | `log` or `drop` on publish failure |
| `cluster.redis-loss-grace-period-ms` | `5000` | *(since V1.9.0)* Grace window before node state-machine degrades on Redis loss. Broker `state()` still flips immediately. `0` = instant (V1.8.0 behavior). **This is the only intentional default-behavior change in 1.9.0.** |
| `cluster.session-registry-write-rate` | `1000` | *(since V1.9.0)* Max registry write ops/s/node; token-bucket passes through under the rate (zero latency change); coalesces at limit. `0` = unlimited. Register ops are never dropped. |
| `cluster.reliable.enable` | `false` | *(since V1.9.0-RC2)* Enable Redis Streams reliable broadcast. `false` = zero overhead; `reliableBroadcast()` throws `IllegalStateException`. |
| `cluster.reliable.stream-max-len` | `10000` | *(since V1.9.0-RC2)* Max entries per URI stream (`MAXLEN ~` approximate trim). |
| `cluster.reliable.poll-block-ms` | `2000` | *(since V1.9.0-RC2)* Blocking-read timeout on the consumer connection (ms). |
| `cluster.reliable.poll-count` | `64` | *(since V1.9.0-RC2)* Max entries per `XREADGROUP` call. |
| `cluster.reliable.dedup-window` | `1024` | *(since V1.9.0-RC2)* In-process PEL dedup sliding-window size. |

---

## 12. Annotation Reference

### HTTP MVC

```java
@Controller                         // Mark as MVC controller
@RestController                     // @Controller + @ResponseBody
@RequestMapping("/path")            // Map by path, method, port
@GetMapping("/path")                // GET shortcut
@PostMapping("/path")               // POST shortcut
@PutMapping("/path")                // PUT shortcut
@DeleteMapping("/path")             // DELETE shortcut
@PatchMapping("/path")              // PATCH shortcut
@ResponseBody                       // Serialize return value to response body
@PathVariable("id")                 // Bind URI path segment
@RequestParam("name")               // Bind query parameter
@DateFormat(pattern = "yyyy-MM-dd") // Date format for field binding
```

### WebSocket

```java
@MessageMapping(value = "/ws/path", messageType = MessageType.TEXT_MESSAGE)
// Map WebSocket events by URI and message type

@AutowiredMessageSender             // Inject MessageSender into a field
```

---

## 13. Troubleshooting

### Common Issues

**"No mapping found for URI /ws/xxx"**
- Ensure a `@MessageMapping` handler exists for the URI.
- Verify `server.netty.websocket.enable=true`.

**"WebSocket handshake rejected: Origin not allowed"**
- Check `server.netty.websocket.allowed-origins`. Set to `*` to allow all origins during development.

**"Connection limit reached"**
- The server has reached `server.netty.websocket.max-connections`. Increase it or set to `0` for unlimited.

**"Handler executor overloaded"**
- The handler thread pool is full. Increase `websocket.handler-max-pool-size` or `handler-permit-limit`.

**"Crypto key provider not found"**
- Ensure the bean name in `crypto.key-provider` matches a registered Spring bean of type `MessageCryptoKeyProvider`.

**"Decryption failed / Unencrypted frame rejected"**
- Client is sending plaintext when `crypto.reject-unencrypted=true`. Either encrypt on the client side or set `reject-unencrypted=false` for mixed-mode.

**JSON deserialization error in WebSocket handler**
- Ensure the text frame is valid JSON matching your POJO structure. Deserialization failures are routed to the `ON_ERROR` handler.

### Targeted Testing

```bash
# Test a specific module
mvn test -pl netty-spring-web -am
mvn test -pl netty-spring-webmvc -am
mvn test -pl netty-spring-websocket -am
mvn test -pl demo-netty-web-spring-boot-starter -am

# Test all modules
mvn test
```
