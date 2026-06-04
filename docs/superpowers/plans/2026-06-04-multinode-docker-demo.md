# Multi-Node Docker Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A runnable, CI-guarded multi-node Docker demo (2 app nodes + nginx LB + standalone Redis) that visibly proves cross-node WebSocket broadcast by reusing the existing demo chat.

**Architecture:** The existing `demo-netty-web-spring-boot-starter` gains the cluster starter + a `cluster` Spring profile; `ChatRoomController` stamps each broadcast with its origin node id and exposes `/whoami`. A `docker-demo/` infra dir (compose + nginx + Dockerfile) runs two nodes behind a round-robin LB over Redis; a headless `smoke.js` (the CI oracle) asserts a broadcast from one node reaches a client on the other, origin-stamped.

**Tech Stack:** Spring Boot 2.7.18, netty-spring cluster (Redis pub/sub via Lettuce), Docker Compose, nginx, Node `ws`, GitHub Actions.

---

## Environment notes for every task
- Repo: `C:\Users\qq951\IdeaProjects\netty-spring`; Maven 3.9.9 / `./mvnw`; Java 17. Docker live.
- Branch `feature/1.9.x-docker-demo` (Task 0), cut from `master` @ `b05e345`. Do NOT push/deploy. No RC tag (this feature does not bump the RC).
- The demo is **Netty-only (no servlet)** → there is NO `/actuator/*` HTTP endpoint. The health URL is **`/netty/health`** (UP/DOWN), enabled by `server.netty.management.enable=true` (already set). Use `/netty/health` for ALL healthchecks/polls.
- The demo jar is `demo-netty-web-spring-boot-starter/target/demo-netty-web-spring-boot-starter-1.9.0-RC7.jar` (the Spring Boot fat jar; the thin jar is `*.jar.original`, so a `*.jar` glob matches exactly the fat jar).
- The headless `docker-demo/smoke.js` (cross-node broadcast received with correct `originNode`) is the verification ORACLE — a smoke failure is a real bug to fix, never to weaken.

## Grounding to read before starting (do not skip)
- `stress-test/docker-compose.yml` + `stress-test/Dockerfile.server` — the repo's docker idiom (prebuilt-jar COPY on `eclipse-temurin:17-jre-alpine`, `wget` healthcheck, bridge network).
- `demo-netty-web-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/demo/controller/ChatRoomController.java` — the chat being extended. Note: `messageSender.broadcastJson(CHAT_URI, <Map>)`, the `join`/`leave`/`message` payload maps, the inline `CHAT_HTML` Java text block, and `@RequestMapping("/chat")` returning a String.
- `demo-netty-web-spring-boot-starter/pom.xml`, `.../src/main/resources/application.properties`, `.../src/test/java/.../DemoApplicationSmokeTest.java`.
- `netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/node/ClusterNodeManager.java` — `public String getNodeId()` returns this node's id.
- `.github/workflows/ci.yml` — existing CI (a `test` job on `ubuntu-latest`, `actions/setup-java@v4` temurin 17, `bash ./mvnw $MAVEN_ARGS test`).

## File Structure (what each new/changed file is responsible for)
- `demo-…/pom.xml` — adds the cluster starter so cluster classes are on the demo's compile/runtime classpath (cluster still OFF by default).
- `demo-…/src/main/resources/application-cluster.properties` — the `cluster` profile that turns a demo instance into a cluster node (Redis + node id from env).
- `demo-…/controller/ChatRoomController.java` — additive: origin-node stamp on broadcasts + `/whoami` + a node badge in the inline UI.
- `docker-demo/Dockerfile` — packages the built demo jar as a cluster-node image.
- `docker-demo/docker-compose.yml` — the topology: `redis`, `node-a`, `node-b`, `lb`.
- `docker-demo/nginx.conf` — round-robin WebSocket-aware reverse proxy.
- `docker-demo/smoke.js` + `docker-demo/package.json` — the headless cross-node assertion (CI oracle).
- `docker-demo/README.md` — how to run + how the proof works.
- `.github/workflows/docker-demo-smoke.yml` — builds the stack and runs `smoke.js` on relevant changes.

---

## Task 0: Branch

- [ ] **Step 1: Create the feature branch from master**

Run:
```bash
git checkout master
git checkout -b feature/1.9.x-docker-demo
git branch --show-current
```
Expected: `feature/1.9.x-docker-demo`. Confirm `git log --oneline -1` shows `b05e345 docs: multi-node Docker demo design spec (1.9.x cycle)` (or later if master advanced).

---

## Task 1: Add the cluster starter to the demo module (regression-gated)

**Files:**
- Modify: `demo-netty-web-spring-boot-starter/pom.xml`
- Test (regression): `demo-netty-web-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/demo/DemoApplicationSmokeTest.java` (unchanged — must still pass)

- [ ] **Step 1: Add the dependency**

In `demo-netty-web-spring-boot-starter/pom.xml`, add this block immediately AFTER the existing `netty-web-spring-boot-starter` dependency (keep all other deps):
```xml
        <!-- WebSocket cluster starter — enables the multi-node Docker demo (cluster OFF by default;
             only activated by --spring.profiles.active=cluster, see application-cluster.properties). -->
        <dependency>
            <groupId>io.github.berrywang1996</groupId>
            <artifactId>netty-websocket-cluster-spring-boot-starter</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Step 2: Verify the demo still starts with cluster OFF (the regression gate)**

Run:
```bash
./mvnw -B -ntp -pl demo-netty-web-spring-boot-starter -am test
```
Expected: BUILD SUCCESS; `DemoApplicationSmokeTest` runs **3 tests, 0 failures** (the context starts with the cluster classes on the classpath but `cluster.enable` unset → no cluster beans, no Redis needed). If it fails because a cluster bean activated, STOP and investigate (the cluster auto-config must be gated by `cluster.enable=true`, which is not set in the default profile).

- [ ] **Step 3: Commit**

```bash
git add demo-netty-web-spring-boot-starter/pom.xml
git commit -m "build(demo): add websocket-cluster starter (cluster off by default)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: The `cluster` Spring profile

**Files:**
- Create: `demo-netty-web-spring-boot-starter/src/main/resources/application-cluster.properties`

- [ ] **Step 1: Create the profile properties**

Create `demo-netty-web-spring-boot-starter/src/main/resources/application-cluster.properties` with EXACTLY:
```properties
# Activated by --spring.profiles.active=cluster (used only by the docker-demo).
# Turns this demo instance into a cluster node. Without this profile the demo is single-node
# (cluster auto-config stays off), so the normal demo and its smoke test are unaffected.
server.netty.websocket.cluster.enable=true
server.netty.websocket.cluster.redis.uri=redis://${REDIS_HOST:redis}:6379
server.netty.websocket.cluster.node-id=${NODE_ID:node-x}
```
`${REDIS_HOST}` / `${NODE_ID}` resolve from container environment variables (Spring reads env vars as a property source); the `:redis` / `:node-x` defaults apply when unset.

- [ ] **Step 2: Verify it compiles into the jar (no context start — that needs Redis)**

Run:
```bash
./mvnw -B -ntp -pl demo-netty-web-spring-boot-starter -am -DskipTests package
```
Expected: BUILD SUCCESS and Maven copied the resource into the build output (portable check, no `unzip` needed):
```bash
test -f demo-netty-web-spring-boot-starter/target/classes/application-cluster.properties && echo "profile resource packaged"
```
Expected: `profile resource packaged`. (Do NOT start the app with `--spring.profiles.active=cluster` here — it would try to connect Redis and fail; that path is exercised live in Task 8.)

- [ ] **Step 3: Commit**

```bash
git add demo-netty-web-spring-boot-starter/src/main/resources/application-cluster.properties
git commit -m "feat(demo): cluster Spring profile (Redis + node-id from env)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Origin-node stamp + `/whoami` + node badge in `ChatRoomController`

**Files:**
- Modify: `demo-netty-web-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/demo/controller/ChatRoomController.java`

All changes are ADDITIVE — do not rewrite the file. Apply these exact edits.

- [ ] **Step 1: Add imports**

After the existing import `import org.springframework.stereotype.Controller;` add:
```java
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import org.springframework.beans.factory.ObjectProvider;
```

- [ ] **Step 2: Add the node-id field and inject it via the constructor**

Replace this exact block:
```java
    /** Injected message sender for broadcasting and targeted WebSocket messaging. */
    private final MessageSender messageSender;

    /** Maps WebSocket session IDs to user-chosen nicknames for all connected users. */
    private final Map<String, String> nicknames = new ConcurrentHashMap<>();

    /**
     * Constructs the chat room controller with the required message sender.
     *
     * @param messageSender the WebSocket message sender for broadcast and targeted delivery
     */
    public ChatRoomController(MessageSender messageSender) {
        this.messageSender = messageSender;
    }
```
with:
```java
    /** Injected message sender for broadcasting and targeted WebSocket messaging. */
    private final MessageSender messageSender;

    /** This node's cluster id when running in cluster mode (profile=cluster); {@code null} single-node. */
    private final String nodeId;

    /** Maps WebSocket session IDs to user-chosen nicknames for all connected users. */
    private final Map<String, String> nicknames = new ConcurrentHashMap<>();

    /**
     * Constructs the chat room controller. The {@link ClusterNodeManager} is optional — present only
     * when the cluster starter is active ({@code cluster.enable=true}); in single-node mode it is absent
     * and {@link #nodeId} stays {@code null}, so all cluster-specific behavior is skipped.
     *
     * @param messageSender             the WebSocket message sender for broadcast and targeted delivery
     * @param clusterNodeManagerProvider optional provider of the cluster node manager
     */
    public ChatRoomController(MessageSender messageSender,
                              ObjectProvider<ClusterNodeManager> clusterNodeManagerProvider) {
        this.messageSender = messageSender;
        ClusterNodeManager mgr = clusterNodeManagerProvider.getIfAvailable();
        this.nodeId = (mgr != null) ? mgr.getNodeId() : null;
    }
```

- [ ] **Step 3: Stamp `originNode` on the join broadcast**

In `onConnected(...)`, replace:
```java
        event.put("onlineUsers", getOnlineUsers());
        event.put("onlineCount", nicknames.size());
        messageSender.broadcastJson(CHAT_URI, event);
```
with:
```java
        event.put("onlineUsers", getOnlineUsers());
        event.put("onlineCount", nicknames.size());
        if (nodeId != null) {
            event.put("originNode", nodeId);
        }
        messageSender.broadcastJson(CHAT_URI, event);
```

- [ ] **Step 4: Stamp `originNode` on the public message broadcast**

In `onMessage(...)`, in the `else` (broadcast) branch, replace:
```java
            // Broadcast message
            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "message");
            broadcast.put("nickname", nickname);
            broadcast.put("text", msg.getText());
            messageSender.broadcastJson(CHAT_URI, broadcast);
```
with:
```java
            // Broadcast message
            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "message");
            broadcast.put("nickname", nickname);
            broadcast.put("text", msg.getText());
            if (nodeId != null) {
                broadcast.put("originNode", nodeId);
            }
            messageSender.broadcastJson(CHAT_URI, broadcast);
```

- [ ] **Step 5: Stamp `originNode` on the leave broadcast**

In `onClose(...)`, replace:
```java
            event.put("onlineUsers", getOnlineUsers());
            event.put("onlineCount", nicknames.size());
            messageSender.broadcastJson(CHAT_URI, event);
```
with:
```java
            event.put("onlineUsers", getOnlineUsers());
            event.put("onlineCount", nicknames.size());
            if (nodeId != null) {
                event.put("originNode", nodeId);
            }
            messageSender.broadcastJson(CHAT_URI, event);
```

- [ ] **Step 6: Add the `/whoami` endpoint**

Immediately AFTER the `chatPage()` method (the one annotated `@RequestMapping("/chat")`), add:
```java
    /**
     * Reports which cluster node served this request (or {@code "single-node"} when cluster mode is
     * off). The chat UI calls this to show a node badge and to prove cross-node delivery in the
     * multi-node Docker demo.
     *
     * @return a small JSON document {@code {"nodeId":"<id>"}}
     */
    @RequestMapping("/whoami")
    public String whoami() {
        String id = (nodeId != null) ? nodeId : "single-node";
        return "{\"nodeId\":\"" + id + "\"}";
    }
```

- [ ] **Step 7: Show the node badge in the page header (HTML)**

In `CHAT_HTML`, replace:
```html
    <div class="header">Chat Room</div>
```
with:
```html
    <div class="header">Chat Room <span id="nodeBadge" style="font-size:.8rem;color:var(--muted);font-weight:400;margin-left:8px"></span></div>
```

- [ ] **Step 8: Track my node + render the badge (JS)**

In `CHAT_HTML`, replace:
```javascript
let ws, myNickname, privateTarget = null;
```
with:
```javascript
let ws, myNickname, myNode = null, privateTarget = null;
fetch('/whoami').then(r => r.json()).then(d => { myNode = d.nodeId; renderNodeBadge(); }).catch(() => {});
function renderNodeBadge() { const b = document.getElementById('nodeBadge'); if (b && myNode) b.textContent = 'Node: ' + myNode; }
```

- [ ] **Step 9: Render the cross-node `(via …)` tag on messages and join/leave (JS)**

In `CHAT_HTML`'s `handleMsg(data)`, replace:
```javascript
  if(data.type === 'join') {
    addSystem(data.nickname + ' joined the chat');
    updateUsers(data.onlineUsers);
  } else if(data.type === 'leave') {
    addSystem(data.nickname + ' left the chat');
    updateUsers(data.onlineUsers);
  } else if(data.type === 'message') {
    const isMe = data.nickname === myNickname;
    addChat(data.nickname, data.text, isMe ? 'out' : 'in');
  } else if(data.type === 'private') {
```
with:
```javascript
  if(data.type === 'join') {
    addSystem(data.nickname + ' joined the chat' + viaSuffix(data));
    updateUsers(data.onlineUsers);
  } else if(data.type === 'leave') {
    addSystem(data.nickname + ' left the chat' + viaSuffix(data));
    updateUsers(data.onlineUsers);
  } else if(data.type === 'message') {
    const isMe = data.nickname === myNickname;
    addChat(data.nickname, data.text, isMe ? 'out' : 'in', crossNode(data) ? data.originNode : null);
  } else if(data.type === 'private') {
```

- [ ] **Step 10: Add the `crossNode`/`viaSuffix` helpers and the `via` arg on `addChat` (JS)**

In `CHAT_HTML`, replace:
```javascript
function addChat(nick, text, cls) {
  const d = document.createElement('div');
  d.className = 'msg ' + cls;
  d.innerHTML = '<div class="nick">' + esc(nick) + '</div>' + esc(text);
  msgDiv.appendChild(d); msgDiv.scrollTop = msgDiv.scrollHeight;
}
```
with:
```javascript
function crossNode(data) { return data.originNode && myNode && data.originNode !== myNode; }
function viaSuffix(data) { return crossNode(data) ? ' (via ' + data.originNode + ')' : ''; }

function addChat(nick, text, cls, via) {
  const d = document.createElement('div');
  d.className = 'msg ' + cls;
  const viaTag = via ? ' <span class="pm-label">via ' + esc(via) + '</span>' : '';
  d.innerHTML = '<div class="nick">' + esc(nick) + viaTag + '</div>' + esc(text);
  msgDiv.appendChild(d); msgDiv.scrollTop = msgDiv.scrollHeight;
}
```

- [ ] **Step 11: Verify compile + the single-node regression**

Run:
```bash
./mvnw -B -ntp -pl demo-netty-web-spring-boot-starter -am test
```
Expected: BUILD SUCCESS; `DemoApplicationSmokeTest` still **3 tests, 0 failures** (single-node: `ObjectProvider.getIfAvailable()` → null → `nodeId` null → `originNode` never added, `/whoami` returns `single-node`; behavior byte-identical). The controller now compiles against `ClusterNodeManager` (on the classpath via Task 1's starter dep).

- [ ] **Step 12: Commit**

```bash
git add demo-netty-web-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/demo/controller/ChatRoomController.java
git commit -m "feat(demo): stamp origin node on chat broadcasts + /whoami node badge

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: `docker-demo/` infra — Dockerfile, compose, nginx

**Files:**
- Create: `docker-demo/Dockerfile`, `docker-demo/docker-compose.yml`, `docker-demo/nginx.conf`

- [ ] **Step 1: Dockerfile**

Create `docker-demo/Dockerfile`:
```dockerfile
# Multi-node cluster demo image — runs the netty-spring demo app as one cluster node.
# Build context MUST be the repo root so the Maven-built jar is COPYable. Build the jar first:
#   ./mvnw -pl demo-netty-web-spring-boot-starter -am package -DskipTests
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# target/ holds exactly one *.jar (the Spring Boot fat jar; the thin jar is *.jar.original),
# so this glob is version-agnostic — no RC string to maintain here.
COPY demo-netty-web-spring-boot-starter/target/*.jar app.jar

EXPOSE 8080

# NODE_ID and REDIS_HOST are injected per-service by docker-compose and read by
# application-cluster.properties. busybox `wget` (present in alpine) backs the compose healthcheck.
ENTRYPOINT ["sh", "-c", "java -jar app.jar --spring.profiles.active=cluster --logging.level.root=info"]
```

- [ ] **Step 2: docker-compose.yml**

Create `docker-demo/docker-compose.yml`:
```yaml
name: netty-spring-cluster-demo

services:
  redis:
    image: redis:7
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 3s
      timeout: 2s
      retries: 10
    networks: [demonet]

  node-a:
    build: &node-build
      context: ..
      dockerfile: docker-demo/Dockerfile
    image: netty-spring-cluster-demo:latest
    environment:
      NODE_ID: node-a
      REDIS_HOST: redis
    depends_on:
      redis:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/netty/health"]
      interval: 5s
      timeout: 3s
      retries: 15
      start_period: 25s
    networks: [demonet]

  node-b:
    build: *node-build
    image: netty-spring-cluster-demo:latest
    environment:
      NODE_ID: node-b
      REDIS_HOST: redis
    depends_on:
      redis:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/netty/health"]
      interval: 5s
      timeout: 3s
      retries: 15
      start_period: 25s
    networks: [demonet]

  lb:
    image: nginx:alpine
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
    ports:
      - "8080:80"
    depends_on:
      node-a:
        condition: service_healthy
      node-b:
        condition: service_healthy
    networks: [demonet]

networks:
  demonet:
    driver: bridge
```
(The YAML anchor `&node-build` / `*node-build` makes both nodes build the same image tag from one Dockerfile — compose builds it once.)

- [ ] **Step 3: nginx.conf**

Create `docker-demo/nginx.conf`:
```nginx
events { worker_connections 1024; }

http {
    # Round-robin (nginx default) across the two app nodes. A WebSocket connection is upgraded
    # here and pinned to one backend for its lifetime — no sticky config needed; round-robin
    # spreads new connections so two browser tabs land on different nodes.
    upstream backend {
        server node-a:8080;
        server node-b:8080;
    }

    server {
        listen 80;

        location / {
            proxy_pass http://backend;
            proxy_http_version 1.1;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection "upgrade";
            proxy_set_header Host $host;
            # Keep idle WebSocket connections open (default would cut them at 60s).
            proxy_read_timeout 3600s;
        }
    }
}
```

- [ ] **Step 4: Validate compose syntax**

Run:
```bash
docker compose -f docker-demo/docker-compose.yml config -q
```
Expected: no output, exit 0 (the compose file parses and resolves). (A live `up` happens in Task 8 once `smoke.js` exists.)

- [ ] **Step 5: Commit**

```bash
git add docker-demo/Dockerfile docker-demo/docker-compose.yml docker-demo/nginx.conf
git commit -m "feat(docker-demo): compose topology (2 nodes + nginx LB + redis)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: `smoke.js` — the headless cross-node oracle

**Files:**
- Create: `docker-demo/smoke.js`, `docker-demo/package.json`

- [ ] **Step 1: package.json**

Create `docker-demo/package.json`:
```json
{
  "name": "netty-spring-cluster-demo-smoke",
  "version": "1.0.0",
  "private": true,
  "description": "Headless cross-node broadcast smoke test for the netty-spring cluster Docker demo",
  "dependencies": {
    "ws": "^8.18.0"
  }
}
```

- [ ] **Step 2: smoke.js**

Create `docker-demo/smoke.js`:
```javascript
// Headless cross-node broadcast smoke test for the netty-spring multi-node Docker demo.
//
// Opens two WebSocket clients through the nginx load balancer, verifies they were served by
// DIFFERENT cluster nodes, then asserts a broadcast from one is delivered to the other STAMPED
// with the sender's origin node id — proving cross-node delivery over Redis. Exit 0 = pass, 1 = fail.
const WebSocket = require('ws');

const LB = process.env.LB_URL || 'ws://localhost:8080';
const CHAT = LB + '/ws/chat';
const CONNECT_TIMEOUT_MS = 10000;
const RECEIVE_TIMEOUT_MS = 5000;
const MAX_NODE_RETRIES = 8;

function log(...a) { console.log('[smoke]', ...a); }
function fail(msg) { console.error('[smoke] FAIL:', msg); process.exit(1); }

// Open a chat client; resolve {ws, node} once we learn which node served us — read from the
// originNode of our OWN join broadcast (each node stamps the joins it originates).
function connectClient(nickname) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(CHAT + '?nickname=' + encodeURIComponent(nickname));
    const to = setTimeout(() => { try { ws.close(); } catch (e) {} reject(new Error('timeout learning node for ' + nickname)); }, CONNECT_TIMEOUT_MS);
    ws.on('message', (raw) => {
      let d; try { d = JSON.parse(raw.toString()); } catch (e) { return; }
      if (d.type === 'join' && d.nickname === nickname && d.originNode) {
        clearTimeout(to);
        resolve({ ws, node: d.originNode });
      }
    });
    ws.on('error', (e) => { clearTimeout(to); reject(e); });
  });
}

// Connect a client that lands on a node DIFFERENT from avoidNode (round-robin over 2 nodes alternates,
// so this converges quickly); each retry uses a fresh nickname.
async function connectOnDistinctNode(baseNick, avoidNode) {
  for (let i = 0; i < MAX_NODE_RETRIES; i++) {
    const c = await connectClient(baseNick + '-' + i);
    if (c.node !== avoidNode) return c;
    log(baseNick + ' landed on ' + c.node + ' (same as ' + avoidNode + '); retrying for a distinct node');
    try { c.ws.close(); } catch (e) {}
  }
  throw new Error('could not reach a node distinct from ' + avoidNode + ' after ' + MAX_NODE_RETRIES + ' tries');
}

(async () => {
  let c1, c2;
  try {
    c1 = await connectClient('alice');
    log('client1 (alice) served by ' + c1.node);
    c2 = await connectOnDistinctNode('bob', c1.node);
    log('client2 (bob) served by ' + c2.node);

    const MARK = 'cross-node-' + Date.now();
    const received = new Promise((resolve, reject) => {
      const to = setTimeout(() => reject(new Error('client2 did not receive the broadcast within ' + RECEIVE_TIMEOUT_MS + 'ms')), RECEIVE_TIMEOUT_MS);
      c2.ws.on('message', (raw) => {
        let d; try { d = JSON.parse(raw.toString()); } catch (e) { return; }
        if (d.type === 'message' && d.text === MARK) { clearTimeout(to); resolve(d); }
      });
    });

    c1.ws.send(JSON.stringify({ type: 'message', text: MARK }));
    const msg = await received;
    log('client2 received: ' + JSON.stringify(msg));

    if (!msg.originNode) fail('received message has no originNode stamp');
    if (msg.originNode !== c1.node) fail('expected originNode=' + c1.node + ' but got ' + msg.originNode);
    if (msg.originNode === c2.node) fail('originNode equals the receiver node — not cross-node');

    log('PASS: broadcast from ' + c1.node + ' reached a client on ' + c2.node + ' with correct origin stamp');
    process.exit(0);
  } catch (e) {
    fail(e && e.message ? e.message : String(e));
  } finally {
    try { c1 && c1.ws.close(); } catch (e) {}
    try { c2 && c2.ws.close(); } catch (e) {}
  }
})();
```

- [ ] **Step 3: Verify it parses (syntax only — a live run is Task 8)**

Run:
```bash
node --check docker-demo/smoke.js
```
Expected: no output, exit 0 (valid JS). (`node smoke.js` is NOT run here — it needs the live stack + `ws` installed; that is Task 8 / CI.)

- [ ] **Step 4: Commit**

```bash
git add docker-demo/smoke.js docker-demo/package.json
git commit -m "test(docker-demo): headless cross-node broadcast smoke (the oracle)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: `docker-demo/README.md`

**Files:**
- Create: `docker-demo/README.md`

- [ ] **Step 1: Write the README**

Create `docker-demo/README.md`:
````markdown
# Multi-Node WebSocket Cluster Demo (Docker)

A runnable, two-node demo proving **cross-node WebSocket broadcast**: two app nodes behind an nginx
load balancer share state over Redis. A chat message typed in a browser served by `node-a` appears in
a browser served by `node-b`, tagged `(via node-a)`.

```
 browser ─┐                       ┌─ node-a ─┐
 browser ─┴─ nginx (lb, :8080) ───┤          ├─ redis (pub/sub fan-out)
                                  └─ node-b ─┘
```

## Run it

From the repo root (Docker must be running):

```bash
# 1. Build the demo jar (the image COPYs it)
./mvnw -pl demo-netty-web-spring-boot-starter -am package -DskipTests

# 2. Bring up redis + 2 app nodes + the nginx LB
docker compose -f docker-demo/docker-compose.yml up --build
```

Then open **http://localhost:8080/chat** in **two browser tabs**:

1. Each tab's header shows a **`Node: node-a`** / **`Node: node-b`** badge — the LB round-robins, so the
   two tabs land on different nodes. (If both show the same node, open a third tab or reload one.)
2. Join with two different nicknames and send a public message from one tab.
3. The other tab receives it tagged **`via node-a`** (or `node-b`) — proving the message crossed nodes
   via Redis, not a shared process.

Tear down with `Ctrl-C`, then `docker compose -f docker-demo/docker-compose.yml down -v`.

## How the proof works

Each node knows its own id (`NODE_ID` env → `server.netty.websocket.cluster.node-id` →
`ClusterNodeManager.getNodeId()`). When a node originates a chat broadcast it stamps `originNode=<self>`
into the JSON **before** publishing. The cluster fans the broadcast out over Redis; the receiving node
delivers it with `originNode` intact. Each browser also asks `GET /whoami` for its own node id, so it can
render the badge and show `(via …)` only when a message's origin differs from its own node.

## Headless check

`smoke.js` is the automated version of the two-tab test (also run in CI): it connects two clients through
the LB, confirms they are on different nodes, broadcasts from one, and asserts the other receives it with
the correct `originNode`.

```bash
cd docker-demo && npm install && node smoke.js   # exit 0 = cross-node delivery confirmed
```

## Notes

- **Online-user list/count is per-node** in this lean demo — each node only lists the users connected to
  it. A global cross-node directory (and cross-node private messages) is intentionally out of scope; the
  cross-node proof here is the broadcast messages with their origin tags, not the roster.
- **Standalone Redis** is used (the common `RedisPubSubBroker` path), not a Redis *Cluster*.
- **Scale to 3+ nodes:** add a `node-c` service (copy `node-b`, set `NODE_ID: node-c`) and add
  `server node-c:8080;` to the `upstream backend` block in `nginx.conf`.
````

- [ ] **Step 2: Commit**

```bash
git add docker-demo/README.md
git commit -m "docs(docker-demo): run instructions + how the cross-node proof works

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: CI smoke workflow

**Files:**
- Create: `.github/workflows/docker-demo-smoke.yml`

- [ ] **Step 1: Create the workflow**

Create `.github/workflows/docker-demo-smoke.yml`:
```yaml
name: Docker Demo Smoke

on:
  push:
    branches: [master]
    paths:
      - "docker-demo/**"
      - "demo-netty-web-spring-boot-starter/**"
      - "netty-spring-websocket-cluster/**"
      - "netty-websocket-cluster-spring-boot-starter/**"
      - ".github/workflows/docker-demo-smoke.yml"
  pull_request:
    paths:
      - "docker-demo/**"
      - "demo-netty-web-spring-boot-starter/**"
      - "netty-spring-websocket-cluster/**"
      - "netty-websocket-cluster-spring-boot-starter/**"
      - ".github/workflows/docker-demo-smoke.yml"
  workflow_dispatch:

permissions:
  contents: read

jobs:
  smoke:
    name: Multi-node cross-node broadcast smoke
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
          cache: maven

      - name: Build demo jar
        run: bash ./mvnw -B -ntp -pl demo-netty-web-spring-boot-starter -am package -DskipTests

      - name: Set up Node
        uses: actions/setup-node@v4
        with:
          node-version: "20"

      - name: Install smoke dependencies
        working-directory: docker-demo
        run: npm install

      - name: Start the cluster demo stack
        run: docker compose -f docker-demo/docker-compose.yml up -d --build

      - name: Wait for the load balancer
        run: |
          for i in $(seq 1 40); do
            if wget -qO- http://localhost:8080/netty/health >/dev/null 2>&1; then
              echo "LB healthy after $i tries"; exit 0
            fi
            echo "waiting for LB... ($i)"; sleep 3
          done
          echo "::error::LB did not become healthy in time"; exit 1

      - name: Run cross-node smoke test
        working-directory: docker-demo
        run: node smoke.js

      - name: Dump compose logs on failure
        if: failure()
        run: docker compose -f docker-demo/docker-compose.yml logs

      - name: Tear down
        if: always()
        run: docker compose -f docker-demo/docker-compose.yml down -v
```

- [ ] **Step 2: Validate YAML**

Run:
```bash
node -e "const fs=require('fs');const s=fs.readFileSync('.github/workflows/docker-demo-smoke.yml','utf8');if(!s.includes('node smoke.js'))process.exit(1);console.log('workflow present')"
```
Expected: `workflow present`. (Full validation happens when GitHub runs it; the live behavior is proven locally in Task 8.)

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/docker-demo-smoke.yml
git commit -m "ci(docker-demo): build the stack and run the cross-node smoke on relevant changes

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: Live end-to-end verification + finish the branch

**Files:** none (verification + merge)

- [ ] **Step 1: Build the demo jar**

```bash
./mvnw -B -ntp -pl demo-netty-web-spring-boot-starter -am package -DskipTests
```
Expected: BUILD SUCCESS; `demo-netty-web-spring-boot-starter/target/demo-netty-web-spring-boot-starter-1.9.0-RC7.jar` exists.

- [ ] **Step 2: Bring up the stack**

```bash
docker compose -f docker-demo/docker-compose.yml up -d --build
```
Expected: `redis`, `node-a`, `node-b`, `lb` start. Wait for health:
```bash
for i in $(seq 1 40); do wget -qO- http://localhost:8080/netty/health && break; echo "waiting ($i)"; sleep 3; done
```
Expected: a health response (UP) once both nodes are ready.

- [ ] **Step 3: Run the smoke oracle (the real verification)**

```bash
cd docker-demo && npm install && node smoke.js; echo "exit=$?"; cd ..
```
Expected: `[smoke] PASS: broadcast from node-X reached a client on node-Y …` and `exit=0`. **If it fails, this is a real bug** — debug with `docker compose -f docker-demo/docker-compose.yml logs node-a node-b` (check: cluster profile active? Redis reachable? `originNode` present in the broadcast? nginx upgrading the WS?). Fix the cause; do NOT weaken the assertion.

- [ ] **Step 4: Tear down**

```bash
docker compose -f docker-demo/docker-compose.yml down -v
```

- [ ] **Step 5: Full reactor regression**

```bash
./mvnw -B -ntp test
```
Expected: BUILD SUCCESS, all 11 modules green (the demo dep addition did not break the reactor; the demo smoke test still passes).

- [ ] **Step 6: Finish the development branch**

Use **superpowers:finishing-a-development-branch**. Expected flow (per this project's standing directive): FF-merge `feature/1.9.x-docker-demo` into `master`, delete the feature branch, **do NOT push, do NOT deploy, and do NOT create an RC tag** (this feature does not bump the RC). Confirm `master` contains the demo commits and the working tree is clean.

---

## Notes for the implementer
- The demo is **Netty-only** — health is `/netty/health`, NOT `/actuator/health`. Using the wrong URL makes the compose/CI healthcheck hang.
- All `ChatRoomController` changes are additive and gated on `nodeId != null`; single-node mode (and `DemoApplicationSmokeTest`) must stay byte-identical.
- `smoke.js` is the oracle. A red smoke = a real defect in the wiring (profile, Redis URI, origin stamp, nginx WS upgrade). Fix the wiring.
- Do not hard-code the RC version in the Dockerfile (the `*.jar` glob handles it). The jar *name* only appears in verification commands here.
```
