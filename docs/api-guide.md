# netty-spring API Usage Guide

> Version: 1.7.0 | Updated: 2026-05-29

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
9. [Metrics & Monitoring](#9-metrics--monitoring)
10. [Configuration Reference](#10-configuration-reference)
11. [Annotation Reference](#11-annotation-reference)
12. [Troubleshooting](#12-troubleshooting)

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
    <groupId>com.github.berrywang1996</groupId>
    <artifactId>netty-web-spring-boot-starter</artifactId>
    <version>1.7.0</version>
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

For simpler cases, use `@MessageMapping` with `ON_HANDSHAKE`:

```java
@MessageMapping(value = "/ws/secure", messageType = MessageType.ON_HANDSHAKE)
public boolean onHandshake(MessageSession session) {
    String token = session.getQueryParam("token");
    return token != null && isValidToken(token);
}
```

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
@Bean("myKeyProvider")
public MessageCryptoKeyProvider keyProvider() {
    return (keyId, session) -> {
        // In production, load keys from a secure key management service
        if ("my-key-2026".equals(keyId)) {
            return mySecretKeyBytes;  // 16/24/32 bytes for AES-128/192/256
        }
        return null;
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

## 9. Metrics & Monitoring

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
- `netty.websocket.sessions.closed` (tagged by `reason` — 15 close reasons)
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

## 10. Configuration Reference

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

| Property | Default | Description |
| --- | --- | --- |
| `websocket.core-pool-size` | `0` | Sender executor core threads |
| `websocket.max-pool-size` | `0` | Sender executor max threads |
| `websocket.handler-core-pool-size` | `0` | Handler executor core threads |
| `websocket.handler-max-pool-size` | `0` | Handler executor max threads |
| `websocket.handler-permit-limit` | `0` | Max concurrent handler tasks |

### WebSocket Broadcast Policy

| Property | Default | Description |
| --- | --- | --- |
| `websocket.broadcast-non-writable-channel-policy` | `SKIP` | `SKIP` or `CLOSE` non-writable channels |
| `websocket.broadcast-rejected-execution-policy` | `DROP` | `DROP` or `CALLER_RUNS` when executor is full |

---

## 11. Annotation Reference

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

## 12. Troubleshooting

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
