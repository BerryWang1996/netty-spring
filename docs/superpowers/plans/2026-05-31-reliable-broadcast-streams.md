# Reliable Broadcast via Redis Streams — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in `clusterMessageSender.reliableBroadcast(uri, message)` giving at-least-once cross-node broadcast (within a bounded retention window) over Redis Streams, so a briefly-offline node catches up instead of silently missing broadcasts. Existing `topicMessage()` (Pub/Sub, at-most-once) is untouched.

**Architecture:** New `ReliableBroker` SPI beside `ClusterBroker`. Default `RedisStreamsReliableBroker`: per-URI Redis Stream (`netty:cluster:rstream:{uri}`), one consumer group per node (`g:{nodeId}`), a dedicated blocking Lettuce connection + per-URI consume thread doing `XREADGROUP >` (auto-replays backlog on reconnect) + a startup PEL drain (`XREADGROUP 0`), `XACK` after delivery, bounded entry-id dedup ring. `ClusterMessageSender.reliableBroadcast` does local fan-out then async `XADD`; `onReliableMessage` does origin self-suppression. Gated by `reliable.enable=false` (zero overhead when off). Dead-node consumer groups are reaped via the existing 1.9.0 dead-node callback.

**Tech Stack:** Java 17, Lettuce 6.1.x (Redis Streams: XADD/XGROUP/XREADGROUP/XACK), Spring Boot 2.7.18 auto-config, JUnit 5, Maven (11 modules). Spec: `docs/superpowers/specs/2026-05-31-reliable-broadcast-streams-design.md`. Develops on the `1.9.0-RC1` line.

**Decisions (confirmed):** disabled `reliableBroadcast` → throws `IllegalStateException`; retention default 10000 entries/URI; dead-node group cleanup in-scope.

---

## Environment notes for every task
- Repo: `C:\Users\qq951\IdeaProjects\netty-spring`; OS Windows (PowerShell + Bash); Maven 3.9.9 (Aliyun mirror); Java 17.
- Git: work on branch `master` (the RC line) — do NOT create branches, push, or deploy.
- Redis is live on `localhost:16379` (container `redis-standalone`) so integration tests run, not skip.
- Match on quoted code, not line numbers. TDD: write the failing test, watch it fail, implement, watch it pass, commit.
- **Lettuce blocking-read hazard:** an `XREADGROUP ... BLOCK` holds its connection for the block duration. Use a **dedicated** connection for blocking reads (never the shared command connection), and set that connection's timeout **longer than** `poll-block-ms` (otherwise the command times out mid-block). Non-blocking `XADD`/`XACK`/`XGROUP` use a separate command connection.

## File Structure

**New — `netty-spring-websocket-cluster`:**
- `.../cluster/spi/ReliableBroker.java` — SPI (publish / subscribe / dead-node group cleanup / state / shutdown).
- `.../cluster/redis/RedisStreamsReliableBroker.java` — Redis Streams impl.
- `.../cluster/ClusterRuntimeStats.java` — add `reliablePublished` / `reliableReceived` counters (modify existing).

**Modified:**
- `.../cluster/ClusterProperties.java` — nested `Reliable` config + accessors.
- `.../cluster/ClusterMessageSender.java` — `reliableBroadcast()`, reliable-subscribe lifecycle, `onReliableMessage`, `setReliableBroker`, dead-node group cleanup hook.
- `netty-websocket-cluster-spring-boot-starter/.../NettyWebSocketClusterConfigure.java` — gated `ReliableBroker` bean + dedicated connection + inject into sender.
- `netty-websocket-cluster-spring-boot-starter/.../META-INF/additional-spring-configuration-metadata.json` — 5 `reliable.*` keys.

**New tests:**
- `.../cluster/InMemoryReliableBroker.java` — test stub.
- `.../cluster/ClusterReliableSenderTest.java` — sender unit (disabled-throws, local-fan-out-then-publish, self-suppression).
- `.../cluster/ReliableBroadcastIntegrationTest.java` — real-Redis (publish/consume, replay-on-resync headline, dead-group cleanup).
- Modify `NettyWebSocketClusterConfigureTest.java` — reliable bean present (enabled) / absent (disabled).

---

## Task 1: ReliableBroker SPI + config + metadata

**Files:**
- Create: `netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/spi/ReliableBroker.java`
- Modify: `netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterProperties.java`
- Modify: `netty-websocket-cluster-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json`

- [ ] **Step 1: Create the SPI**

Create `ReliableBroker.java` (copy the 15-line Apache header from `ClusterBroker.java`), then:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.spi;

/**
 * SPI for opt-in at-least-once cross-node BROADCAST (a durable complement to the at-most-once
 * {@link ClusterBroker} Pub/Sub path). The default impl ({@code RedisStreamsReliableBroker}) uses
 * Redis Streams: a per-URI stream + one consumer group per node, so a node that was briefly offline
 * replays the backlog it missed.
 *
 * <p>Only constructed when {@code server.netty.websocket.cluster.reliable.enable=true}.
 * Implementations must be thread-safe.
 *
 * @author berrywang1996
 * @since V1.9.0
 * @see ClusterBroker
 */
public interface ReliableBroker {

    /**
     * Durably publishes a broadcast envelope for the given URI (e.g. XADD to the URI stream).
     * Non-blocking / fire-and-log: a failure to persist is surfaced by the caller's publish-failure
     * policy, not thrown to the hot path.
     *
     * @param uri      the WebSocket mapping URI
     * @param envelope the broadcast envelope (kind {@code BROADCAST}; carries {@code originNodeId})
     */
    void reliablePublish(String uri, ClusterEnvelope envelope);

    /**
     * Subscribes this node to the reliable stream for a URI: ensures the per-node consumer group
     * exists and starts consuming (delivering each new entry to {@code listener}, then acking).
     * On reconnect after an outage the backlog replays automatically. Idempotent per URI.
     *
     * @param uri      the WebSocket mapping URI
     * @param nodeId   this node's id (consumer-group/consumer name)
     * @param listener callback for each received envelope (does origin self-suppression + delivery)
     * @return a handle to stop consuming this URI
     */
    ClusterSubscription reliableSubscribe(String uri, String nodeId, ClusterMessageListener listener);

    /**
     * Destroys the consumer groups owned by a dead node across all known reliable streams, so its
     * group + pending-entries-list don't leak. Called once per dead node from reconciliation cleanup.
     *
     * @param nodeId the dead node's id
     */
    void destroyConsumerGroupsForNode(String nodeId);

    /** @return the broker's transport state (never null). */
    BrokerState state();

    /** Stops all consume loops and releases connections. */
    void shutdown();
}
```

- [ ] **Step 2: Add nested config to ClusterProperties**

In `ClusterProperties.java`, add a field after the `messageMaxSizeBytes` field:
```java
    /** Opt-in reliable (at-least-once) broadcast over Redis Streams. Disabled by default. */
    private Reliable reliable = new Reliable();
```
Add accessors after the `messageMaxSizeBytes` accessors:
```java
    public Reliable getReliable() { return reliable; }
    public void setReliable(Reliable reliable) { this.reliable = reliable; }
```
Add the nested class after the `Redis` nested class (before the enums):
```java
    /**
     * Reliable (at-least-once) broadcast settings. Off by default — when {@code enable=false} there
     * are no consumer threads, no extra Redis connection, and {@code reliableBroadcast()} throws.
     */
    public static class Reliable {
        /** Master gate for reliable broadcast. Default false. */
        private boolean enable = false;
        /** Per-URI stream MAXLEN (approx) — the at-least-once retention window. Default 10000. */
        private int streamMaxLen = 10000;
        /** XREADGROUP BLOCK timeout (ms) for the consume loop. Default 2000. */
        private long pollBlockMs = 2000;
        /** XREADGROUP COUNT per read. Default 64. */
        private int pollCount = 64;
        /** Per-URI ring size of recently-acked entry ids (in-process redelivery dedup). Default 1024. */
        private int dedupWindow = 1024;

        public boolean isEnable() { return enable; }
        public void setEnable(boolean enable) { this.enable = enable; }
        public int getStreamMaxLen() { return streamMaxLen; }
        public void setStreamMaxLen(int v) { this.streamMaxLen = v; }
        public long getPollBlockMs() { return pollBlockMs; }
        public void setPollBlockMs(long v) { this.pollBlockMs = v; }
        public int getPollCount() { return pollCount; }
        public void setPollCount(int v) { this.pollCount = v; }
        public int getDedupWindow() { return dedupWindow; }
        public void setDedupWindow(int v) { this.dedupWindow = v; }
    }
```

- [ ] **Step 3: Add config metadata**

In `additional-spring-configuration-metadata.json`, add to the `properties` array (after the `session-registry-write-rate` entry — add a comma after that entry's closing `}`):
```json
    {
      "name": "server.netty.websocket.cluster.reliable.enable",
      "type": "java.lang.Boolean",
      "description": "Opt-in at-least-once reliable broadcast over Redis Streams (reliableBroadcast()). Disabled by default — when false there are no consumer threads, no extra Redis connection, and reliableBroadcast() throws. Existing topicMessage() (Pub/Sub, at-most-once) is unaffected either way.",
      "defaultValue": false
    },
    {
      "name": "server.netty.websocket.cluster.reliable.stream-max-len",
      "type": "java.lang.Integer",
      "description": "Per-URI Redis Stream MAXLEN (approximate) for reliable broadcast — the at-least-once retention window. A node offline longer than this many entries of history on a URI misses the trimmed ones (bounded gap).",
      "defaultValue": 10000
    },
    {
      "name": "server.netty.websocket.cluster.reliable.poll-block-ms",
      "type": "java.lang.Long",
      "description": "XREADGROUP BLOCK timeout (ms) for the reliable consume loop.",
      "defaultValue": 2000
    },
    {
      "name": "server.netty.websocket.cluster.reliable.poll-count",
      "type": "java.lang.Integer",
      "description": "XREADGROUP COUNT (max entries per read) for the reliable consume loop.",
      "defaultValue": 64
    },
    {
      "name": "server.netty.websocket.cluster.reliable.dedup-window",
      "type": "java.lang.Integer",
      "description": "Per-URI ring size of recently-acked stream entry ids, to de-duplicate in-process redelivery (at-least-once still allows cross-crash duplicates; handlers should be idempotent).",
      "defaultValue": 1024
    }
```

- [ ] **Step 4: Compile**

Run: `mvn -q -pl netty-spring-websocket-cluster -am -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**
```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/spi/ReliableBroker.java netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterProperties.java netty-websocket-cluster-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json
git commit -m "feat(cluster): ReliableBroker SPI + reliable.* config (Redis Streams scaffolding)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: ClusterRuntimeStats counters + InMemoryReliableBroker stub

**Files:**
- Modify: `netty-spring-websocket-cluster/.../cluster/ClusterRuntimeStats.java`
- Create: `netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/InMemoryReliableBroker.java`

- [ ] **Step 1: Add counters to ClusterRuntimeStats**

Read `ClusterRuntimeStats.java` first to match its style (it uses public `AtomicLong` fields + getters, per the 1.8.0 code). Add two `AtomicLong` fields next to the existing ones (e.g. next to `broadcastPublished`):
```java
    /** Reliable broadcasts published (XADD issued). */
    public final java.util.concurrent.atomic.AtomicLong reliablePublished = new java.util.concurrent.atomic.AtomicLong();
    /** Reliable broadcasts received from the stream and delivered locally. */
    public final java.util.concurrent.atomic.AtomicLong reliableReceived = new java.util.concurrent.atomic.AtomicLong();
```
And matching getters next to the existing getters (match the existing getter style, e.g. `getReliablePublished()` returning `reliablePublished.get()`):
```java
    public long getReliablePublished() { return reliablePublished.get(); }
    public long getReliableReceived() { return reliableReceived.get(); }
```
(If the existing fields are private with getters rather than public finals, mirror that exact style instead.)

- [ ] **Step 2: Create the in-memory stub**

Create `InMemoryReliableBroker.java`:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.*;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory {@link ReliableBroker} stub for SPI-isolation unit tests (no Redis). */
public class InMemoryReliableBroker implements ReliableBroker {

    private final List<ClusterEnvelope> published = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, ClusterMessageListener> listeners = new ConcurrentHashMap<>();
    private volatile BrokerState state = BrokerState.ACTIVE;
    private final java.util.List<String> destroyedForNodes = new CopyOnWriteArrayList<>();

    @Override
    public void reliablePublish(String uri, ClusterEnvelope envelope) {
        published.add(envelope);
        // Echo to a subscribed listener on the same URI (mimics every node — incl. origin — consuming).
        ClusterMessageListener l = listeners.get(uri);
        if (l != null) l.onMessage(envelope);
    }

    @Override
    public ClusterSubscription reliableSubscribe(String uri, String nodeId, ClusterMessageListener listener) {
        listeners.put(uri, listener);
        AtomicBoolean active = new AtomicBoolean(true);
        return new ClusterSubscription() {
            @Override public void unsubscribe() { active.set(false); listeners.remove(uri); }
            @Override public boolean isActive() { return active.get(); }
        };
    }

    @Override public void destroyConsumerGroupsForNode(String nodeId) { destroyedForNodes.add(nodeId); }
    @Override public BrokerState state() { return state; }
    @Override public void shutdown() { state = BrokerState.SHUTDOWN; listeners.clear(); }

    // ---- test helpers ----
    public List<ClusterEnvelope> getPublished() { return published; }
    public java.util.List<String> getDestroyedForNodes() { return destroyedForNodes; }
    public void setState(BrokerState s) { this.state = s; }
}
```

- [ ] **Step 3: Compile tests**

Run: `mvn -q -pl netty-spring-websocket-cluster -am -DskipTests test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**
```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterRuntimeStats.java netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/InMemoryReliableBroker.java
git commit -m "test(cluster): reliable stats counters + InMemoryReliableBroker stub

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: ClusterMessageSender — reliableBroadcast + lifecycle + self-suppression

**Why first (before the Redis impl):** the sender's behavior (local-fan-out-then-publish, disabled-throws, origin self-suppression) is fully testable against the in-memory stub, independent of Redis.

**Files:**
- Modify: `netty-spring-websocket-cluster/.../cluster/ClusterMessageSender.java`
- Test: `netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterReliableSenderTest.java` (create)

- [ ] **Step 1: Write the failing tests**

Create `ClusterReliableSenderTest.java`:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Sender-side reliable-broadcast behavior, against the in-memory broker (no Redis). */
class ClusterReliableSenderTest {

    private InMemoryBroker broker;
    private InMemorySessionRegistry registry;
    private CountingLocalSender localSender;
    private ClusterNodeManager nodeManager;
    private ClusterMessageSender sender;
    private InMemoryReliableBroker reliableBroker;

    static class CountingLocalSender implements MessageSender {
        final AtomicInteger topicCount = new AtomicInteger();
        final Set<String> uris = new HashSet<>();
        void addUri(String u) { uris.add(u); }
        @Override public int getSessionNums() { return 0; }
        @Override public int getSessionNums(String uri) { return 0; }
        @Override public Set<String> getRegisteredUri() { return Collections.unmodifiableSet(uris); }
        @Override public boolean isSessionAlive(String uri, String... ids) { return false; }
        @Override public void sendMessage(String uri, AbstractMessage m, String... ids) {}
        @Override public void topicMessage(String uri, AbstractMessage m) { topicCount.incrementAndGet(); }
    }
    static class NoOpHeartbeat implements ClusterNodeHeartbeat {
        @Override public void register(String n, long t) {}
        @Override public void renewHeartbeat(String n, long t) {}
        @Override public void deregister(String n) {}
        @Override public List<String> findExpiredNodes(long t) { return Collections.emptyList(); }
    }

    @BeforeEach
    void setUp() {
        broker = new InMemoryBroker();
        registry = new InMemorySessionRegistry();
        localSender = new CountingLocalSender();
        nodeManager = new ClusterNodeManager("node-A", 60000, 600000, 60000, 60000, new NoOpHeartbeat(), registry);
        nodeManager.setRedisLossGracePeriodMs(0);
        sender = new ClusterMessageSender(localSender, broker, registry, nodeManager, 5000);
        reliableBroker = new InMemoryReliableBroker();
    }

    @AfterEach
    void tearDown() {
        try { sender.shutdown(); } catch (Exception ignored) {}
        try { nodeManager.shutdown(); } catch (Exception ignored) {}
    }

    @Test
    void reliableBroadcastThrowsWhenDisabled() {
        // No reliable broker wired → reliable delivery disabled → must throw, not silently degrade.
        localSender.addUri("/ws/r");
        nodeManager.start();
        sender.start();
        assertThrows(IllegalStateException.class,
                () -> sender.reliableBroadcast("/ws/r", new TextMessage("x")));
    }

    @Test
    void reliableBroadcastDoesLocalFanOutThenPublishes() {
        localSender.addUri("/ws/r");
        sender.setReliableBroker(reliableBroker);   // enable
        nodeManager.start();
        sender.start();
        sender.reliableBroadcast("/ws/r", new TextMessage("hello"));
        assertEquals(1, localSender.topicCount.get(), "local fan-out happens first");
        assertEquals(1, reliableBroker.getPublished().size(), "then it XADDs to the reliable stream");
        assertEquals("node-A", reliableBroker.getPublished().get(0).getOriginNodeId());
        assertEquals(1, sender.getClusterRuntimeStats().getReliablePublished());
    }

    @Test
    void onReliableMessageSuppressesOriginEcho() {
        // The in-memory broker echoes the published envelope back to the subscribed listener (origin
        // also consumes its own entry). The sender must suppress its own echo (already delivered locally),
        // so local topicMessage is called exactly ONCE (the pre-publish fan-out), not twice.
        localSender.addUri("/ws/r");
        sender.setReliableBroker(reliableBroker);
        nodeManager.start();
        sender.start(); // subscribes reliably to /ws/r
        sender.reliableBroadcast("/ws/r", new TextMessage("hello"));
        assertEquals(1, localSender.topicCount.get(),
                "origin's own reliable echo must be suppressed (no double local delivery)");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl netty-spring-websocket-cluster test -Dtest=ClusterReliableSenderTest`
Expected: FAIL to COMPILE — `reliableBroadcast` / `setReliableBroker` don't exist.

- [ ] **Step 3: Implement in ClusterMessageSender**

Add imports (with the existing ones): `import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ReliableBroker;` (the other spi types are already imported via `spi.*`).

Add fields after `private volatile long nodeLookupTimeoutMs = 2000;`:
```java
    /** Reliable (at-least-once) broadcast broker; null when reliable.enable=false. */
    private volatile ReliableBroker reliableBroker;
    /** Active reliable subscriptions keyed by URI. */
    private final ConcurrentHashMap<String, com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterSubscription>
            reliableSubscriptions = new ConcurrentHashMap<>();
```

Add a setter near the other setters (e.g. after `setNodeLookupTimeoutMs`):
```java
    /** Injects the reliable broker (enables reliableBroadcast). Null = reliable delivery disabled. */
    public void setReliableBroker(ReliableBroker reliableBroker) {
        this.reliableBroker = reliableBroker;
    }
```

In `start()`, after the existing broadcast-subscription loop (`for (String uri : localSender.getRegisteredUri()) { subscribeBroadcast(uri); }`), add:
```java
        // Reliable (Streams) subscriptions for locally-active URIs (only when reliable.enable wired a broker)
        if (reliableBroker != null) {
            for (String uri : localSender.getRegisteredUri()) {
                subscribeReliable(uri);
            }
        }
```

In `onLocalUriActive(String uri)` (currently just `subscribeBroadcast(uri);`), add the reliable join:
```java
    public void onLocalUriActive(String uri) {
        subscribeBroadcast(uri);
        if (reliableBroker != null) {
            subscribeReliable(uri);
        }
    }
```

Add the new methods (near `subscribeBroadcast` / `onBroadcastMessage`):
```java
    private void subscribeReliable(String uri) {
        reliableSubscriptions.computeIfAbsent(uri,
                u -> reliableBroker.reliableSubscribe(u, nodeManager.getNodeId(), this::onReliableMessage));
    }

    /**
     * Publishes a broadcast with at-least-once cross-node delivery (Redis Streams). Local fan-out
     * happens first (origin's own echo is suppressed in {@link #onReliableMessage}); then the envelope
     * is durably appended. Requires {@code reliable.enable=true}.
     *
     * @throws IllegalStateException if reliable delivery is disabled
     */
    public void reliableBroadcast(String uri, AbstractMessage message) throws MessageUriNotDefinedException {
        ReliableBroker rb = reliableBroker;
        if (rb == null) {
            throw new IllegalStateException("Reliable delivery is disabled; set "
                    + "server.netty.websocket.cluster.reliable.enable=true to use reliableBroadcast()");
        }
        // 1. Local fan-out first (always) — same contract as topicMessage.
        localSender.topicMessage(uri, message);
        // 2. Durable append for remote nodes.
        ClusterEnvelope envelope = buildBroadcastEnvelope(uri, message);
        if (exceedsSizeLimit(envelope)) {
            handlePublishFailure("reliable broadcast for URI " + uri + " exceeds messageMaxSizeBytes ("
                    + envelope.getPayload().length + " > " + messageMaxSizeBytes + ")", null);
            return;
        }
        try {
            rb.reliablePublish(uri, envelope);
            clusterStats.reliablePublished.incrementAndGet();
        } catch (Exception e) {
            handlePublishFailure("reliable broadcast for URI " + uri
                    + " — local delivery succeeded, but durable append failed (not persisted for remotes)", e);
        }
    }

    /** Consume callback for the reliable stream: suppress origin echo, then deliver locally. */
    private void onReliableMessage(ClusterEnvelope envelope) {
        if (nodeManager.getNodeId().equals(envelope.getOriginNodeId())) {
            return; // origin already did local fan-out before publishing — suppress the echo
        }
        clusterStats.reliableReceived.incrementAndGet();
        try {
            AbstractMessage message = deserializePayload(envelope.getPayload());
            localSender.topicMessage(envelope.getUri(), message);
        } catch (Exception e) {
            log.warn("Failed to deliver reliable broadcast for URI {}", envelope.getUri(), e);
        }
    }
```

In `shutdown()`, before/after the existing broadcast-subscription teardown, add reliable teardown:
```java
        for (com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterSubscription sub
                : reliableSubscriptions.values()) {
            sub.unsubscribe();
        }
        reliableSubscriptions.clear();
```

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q -pl netty-spring-websocket-cluster test -Dtest=ClusterReliableSenderTest`
Expected: PASS (3 tests). Then `mvn -q -pl netty-spring-websocket-cluster test` → full module green.

- [ ] **Step 5: Commit**
```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterMessageSender.java netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterReliableSenderTest.java
git commit -m "feat(cluster): reliableBroadcast() + reliable-subscribe lifecycle + origin suppression

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: RedisStreamsReliableBroker — publish + consume + dead-group cleanup

**This is the core Redis Streams impl.** It is large; implement it whole, then drive it with the Task 5 integration tests.

**Files:**
- Create: `netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/redis/RedisStreamsReliableBroker.java`

- [ ] **Step 1: Implement the broker**

Create `RedisStreamsReliableBroker.java` (Apache header from a sibling), then:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.redis;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.*;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Redis Streams implementation of {@link ReliableBroker} (at-least-once broadcast).
 *
 * <p>Per URI: stream {@code netty:cluster:rstream:{uri}}; one consumer group per node ({@code g:{nodeId}}).
 * {@code reliablePublish} = async XADD (MAXLEN~). Each subscribed URI runs a dedicated blocking connection
 * + consume thread doing {@code XREADGROUP >} (which auto-replays the backlog a briefly-offline node missed),
 * preceded by a one-time PEL drain ({@code XREADGROUP 0}) on start/after-reconnect. Entries are acked after
 * delivery; an in-process entry-id ring de-dups redelivery.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class RedisStreamsReliableBroker implements ReliableBroker {

    private static final String STREAM_PREFIX = "netty:cluster:rstream:";
    private static final String STREAMS_SET = "netty:cluster:rstreams";
    private static final String GROUP_PREFIX = "g:";
    private static final String FIELD = "e";

    private final RedisClient redisClient;
    private final EnvelopeCodec codec;
    private final int streamMaxLen;
    private final long pollBlockMs;
    private final int pollCount;
    private final int dedupWindow;

    /** Connection for non-blocking commands (XADD/XACK/XGROUP/SADD/SMEMBERS). */
    private final StatefulRedisConnection<String, String> commandConnection;
    private final AtomicReference<BrokerState> state = new AtomicReference<>(BrokerState.ACTIVE);
    private final ConcurrentHashMap<String, Consumer> consumers = new ConcurrentHashMap<>(); // uri -> consumer

    public RedisStreamsReliableBroker(RedisClient redisClient, EnvelopeCodec codec,
                                      int streamMaxLen, long pollBlockMs, int pollCount, int dedupWindow) {
        this.redisClient = redisClient;
        this.codec = codec;
        this.streamMaxLen = streamMaxLen;
        this.pollBlockMs = pollBlockMs;
        this.pollCount = pollCount;
        this.dedupWindow = Math.max(16, dedupWindow);
        this.commandConnection = redisClient.connect();
        log.info("RedisStreamsReliableBroker initialized (maxlen={}, block={}ms, count={})",
                streamMaxLen, pollBlockMs, pollCount);
    }

    @Override
    public void reliablePublish(String uri, ClusterEnvelope envelope) {
        if (state.get() == BrokerState.SHUTDOWN) {
            throw new ClusterBrokerException("Reliable broker shut down");
        }
        String streamKey = STREAM_PREFIX + uri;
        String data = codec.encode(envelope);
        // Register the stream so dead-node cleanup knows it exists (idempotent SADD).
        commandConnection.async().sadd(STREAMS_SET, uri);
        // Async XADD (MAXLEN ~) — fire-and-log; the caller (ClusterMessageSender) surfaces failure.
        commandConnection.async()
                .xadd(streamKey, XAddArgs.Builder.maxlen(streamMaxLen), Collections.singletonMap(FIELD, data))
                .exceptionally(ex -> {
                    log.warn("Reliable XADD to {} failed — not persisted for remotes", streamKey, ex);
                    return null;
                });
    }

    @Override
    public ClusterSubscription reliableSubscribe(String uri, String nodeId, ClusterMessageListener listener) {
        String streamKey = STREAM_PREFIX + uri;
        String group = GROUP_PREFIX + nodeId;
        consumers.putIfAbsent(uri, Consumer.from(group, nodeId));

        // Ensure the group exists (start at $ = new entries only; BUSYGROUP if it already exists → keep cursor).
        try {
            commandConnection.sync().xgroupCreate(
                    XReadArgs.StreamOffset.from(streamKey, "$"), group, XGroupCreateArgs.Builder.mkstream());
        } catch (RedisCommandExecutionException e) {
            if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) {
                throw e;
            }
        }

        AtomicBoolean running = new AtomicBoolean(true);
        // Dedicated blocking connection — its timeout must exceed the block duration.
        StatefulRedisConnection<String, String> blockingConn = redisClient.connect();
        blockingConn.setTimeout(Duration.ofMillis(pollBlockMs + 5000));

        Thread t = new Thread(() -> consumeLoop(uri, streamKey, group, nodeId, listener, blockingConn, running),
                "cluster-rstream-" + nodeId.substring(0, Math.min(8, nodeId.length())) + "-" + Integer.toHexString(uri.hashCode()));
        t.setDaemon(true);
        t.start();

        return new ClusterSubscription() {
            @Override public void unsubscribe() {
                if (running.compareAndSet(true, false)) {
                    consumers.remove(uri);
                    try { blockingConn.close(); } catch (Exception ignored) {}
                }
            }
            @Override public boolean isActive() { return running.get(); }
        };
    }

    private void consumeLoop(String uri, String streamKey, String group, String consumerName,
                            ClusterMessageListener listener, StatefulRedisConnection<String, String> conn,
                            AtomicBoolean running) {
        Consumer consumer = Consumer.from(group, consumerName);
        // Bounded id ring for in-process redelivery dedup (access-ordered LRU).
        Map<String, Boolean> seen = Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(dedupWindow * 2, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > dedupWindow; }
        });
        boolean drainPending = true; // drain PEL on start + after each reconnect
        while (running.get() && state.get() != BrokerState.SHUTDOWN) {
            try {
                if (drainPending) {
                    while (running.get()) {
                        List<StreamMessage<String, String>> pend = conn.sync().xreadgroup(consumer,
                                XReadArgs.Builder.count(pollCount), XReadArgs.StreamOffset.from(streamKey, "0"));
                        if (pend == null || pend.isEmpty()) break;
                        for (StreamMessage<String, String> m : pend) deliver(streamKey, group, m, listener, seen);
                        if (pend.size() < pollCount) break;
                    }
                    drainPending = false;
                }
                List<StreamMessage<String, String>> msgs = conn.sync().xreadgroup(consumer,
                        XReadArgs.Builder.count(pollCount).block(pollBlockMs),
                        XReadArgs.StreamOffset.lastConsumed(streamKey));
                if (msgs != null) {
                    for (StreamMessage<String, String> m : msgs) deliver(streamKey, group, m, listener, seen);
                }
            } catch (Exception e) {
                if (!running.get() || state.get() == BrokerState.SHUTDOWN) break;
                log.warn("Reliable consume loop for {} errored — retrying (will re-drain PEL)", uri, e);
                drainPending = true;
                try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        log.debug("Reliable consume loop for {} stopped", uri);
    }

    private void deliver(String streamKey, String group, StreamMessage<String, String> m,
                         ClusterMessageListener listener, Map<String, Boolean> seen) {
        String id = m.getId();
        if (seen.containsKey(id)) { ack(streamKey, group, id); return; } // in-process redelivery → drop, re-ack
        String data = m.getBody() == null ? null : m.getBody().get(FIELD);
        if (data != null) {
            try {
                ClusterEnvelope env = codec.decode(data);
                if (env != null) listener.onMessage(env); // listener does origin self-suppression + delivery
            } catch (Exception ex) {
                log.warn("Failed to decode reliable entry {} on {}", id, streamKey, ex);
            }
        }
        seen.put(id, Boolean.TRUE);
        ack(streamKey, group, id);
    }

    private void ack(String streamKey, String group, String id) {
        try { commandConnection.async().xack(streamKey, group, id); }
        catch (Exception e) { log.debug("XACK {} on {} failed", id, streamKey, e); }
    }

    @Override
    public void destroyConsumerGroupsForNode(String nodeId) {
        String group = GROUP_PREFIX + nodeId;
        try {
            Set<String> uris = commandConnection.sync().smembers(STREAMS_SET);
            if (uris == null) return;
            for (String uri : uris) {
                try { commandConnection.sync().xgroupDestroy(STREAM_PREFIX + uri, group); }
                catch (Exception e) { log.debug("XGROUP DESTROY {} on {} failed", group, uri, e); }
            }
            log.info("Destroyed reliable consumer group {} across {} streams", group, uris.size());
        } catch (Exception e) {
            log.debug("destroyConsumerGroupsForNode({}) failed", nodeId, e);
        }
    }

    @Override public BrokerState state() { return state.get(); }

    @Override
    public void shutdown() {
        state.set(BrokerState.SHUTDOWN);
        try { commandConnection.close(); } catch (Exception e) { log.warn("Error closing reliable cmd conn", e); }
        log.info("RedisStreamsReliableBroker shut down");
    }
}
```

- [ ] **Step 2: Compile**

Run: `mvn -q -pl netty-spring-websocket-cluster -am -DskipTests compile`
Expected: BUILD SUCCESS. If any Lettuce method signature differs in this version (e.g. `XAddArgs.Builder.maxlen`, `XReadArgs.Builder.block`, `XGroupCreateArgs.Builder.mkstream`, `setTimeout`), fix to the available overload — the API shapes above are Lettuce 6.1.x. Do NOT change behavior.

- [ ] **Step 3: Commit**
```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/redis/RedisStreamsReliableBroker.java
git commit -m "feat(cluster): RedisStreamsReliableBroker — XADD publish + XREADGROUP consume + PEL drain + dead-group cleanup

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Integration tests (publish/consume, replay-on-resync headline, dead-group cleanup)

**Files:**
- Create: `netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ReliableBroadcastIntegrationTest.java`

- [ ] **Step 1: Write the integration tests**

Create `ReliableBroadcastIntegrationTest.java`:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisStreamsReliableBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterSubscription;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real-Redis integration for reliable broadcast (Redis Streams). Skipped without localhost:16379. */
class ReliableBroadcastIntegrationTest {

    private static final String REDIS_URI = "redis://localhost:16379";
    private static RedisClient client;
    private static StatefulRedisConnection<String, String> conn;
    private static boolean redisAvailable;

    @BeforeAll
    static void check() {
        try {
            client = RedisClient.create(REDIS_URI);
            conn = client.connect();
            conn.sync().ping();
            redisAvailable = true;
            conn.sync().eval("for _,k in ipairs(redis.call('keys','netty:cluster:rstream*')) do redis.call('del',k) end "
                    + "redis.call('del','netty:cluster:rstreams')", io.lettuce.core.ScriptOutputType.STATUS);
        } catch (Exception e) { redisAvailable = false; }
    }

    @AfterAll
    static void cleanup() {
        if (conn != null) { try {
            conn.sync().eval("for _,k in ipairs(redis.call('keys','netty:cluster:rstream*')) do redis.call('del',k) end "
                    + "redis.call('del','netty:cluster:rstreams')", io.lettuce.core.ScriptOutputType.STATUS);
            conn.close();
        } catch (Exception ignored) {} }
        if (client != null) { try { client.shutdown(); } catch (Exception ignored) {} }
    }

    private static ClusterEnvelope env(String origin, String uri, String text) {
        return new ClusterEnvelope(origin, uri, ClusterEnvelope.MessageKind.BROADCAST,
                ("T:" + text).getBytes(), null, null, System.currentTimeMillis());
    }

    @Test
    void publishThenSubscribeDeliversToOtherNode() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");
        RedisStreamsReliableBroker a = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        RedisStreamsReliableBroker b = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        List<ClusterEnvelope> bGot = new CopyOnWriteArrayList<>();
        ClusterSubscription bs = b.reliableSubscribe("/ws/r1", "node-B", bGot::add);
        Thread.sleep(300); // group ready
        a.reliablePublish("/ws/r1", env("node-A", "/ws/r1", "m1"));
        long deadline = System.currentTimeMillis() + 5000;
        while (bGot.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(1, bGot.size(), "node B should receive the reliable broadcast");
        assertEquals("node-A", bGot.get(0).getOriginNodeId());
        bs.unsubscribe(); a.shutdown(); b.shutdown();
    }

    @Test
    void offlineNodeCatchesUpOnResubscribe() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");
        RedisStreamsReliableBroker a = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        RedisStreamsReliableBroker b = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        List<ClusterEnvelope> bGot = new CopyOnWriteArrayList<>();

        // B joins (creates its group at $), then "goes offline" (unsubscribe stops consuming; the group + cursor remain).
        ClusterSubscription first = b.reliableSubscribe("/ws/r2", "node-B", bGot::add);
        Thread.sleep(300);
        first.unsubscribe();
        Thread.sleep(200);

        // While B is offline, A publishes 5 reliable broadcasts — Pub/Sub would lose these.
        for (int i = 0; i < 5; i++) a.reliablePublish("/ws/r2", env("node-A", "/ws/r2", "x" + i));
        Thread.sleep(300);
        assertTrue(bGot.isEmpty(), "nothing delivered while B was offline");

        // B comes back (same group g:node-B) → XREADGROUP > replays the 5 missed entries.
        ClusterSubscription second = b.reliableSubscribe("/ws/r2", "node-B", bGot::add);
        long deadline = System.currentTimeMillis() + 6000;
        while (bGot.size() < 5 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(5, bGot.size(), "B must replay ALL 5 broadcasts missed while offline (at-least-once)");

        second.unsubscribe(); a.shutdown(); b.shutdown();
    }

    @Test
    void destroyConsumerGroupsForNodeRemovesGroup() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");
        RedisStreamsReliableBroker a = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        ClusterSubscription s = a.reliableSubscribe("/ws/r3", "dead-node", e -> {});
        Thread.sleep(200);
        // group g:dead-node exists on the stream
        assertFalse(conn.sync().xinfoGroups("netty:cluster:rstream:/ws/r3").isEmpty());
        s.unsubscribe();
        a.destroyConsumerGroupsForNode("dead-node");
        assertTrue(conn.sync().xinfoGroups("netty:cluster:rstream:/ws/r3").isEmpty(),
                "dead node's consumer group must be destroyed");
        a.shutdown();
    }
}
```

- [ ] **Step 2: Run the integration tests**

Run: `mvn -q -pl netty-spring-websocket-cluster test -Dtest=ReliableBroadcastIntegrationTest`
Expected: 3 PASS (Redis is live). The headline `offlineNodeCatchesUpOnResubscribe` proves at-least-once replay. If `xinfoGroups` return type differs, adjust the emptiness check to the available API (e.g. inspect the returned `List<Object>` size).

- [ ] **Step 3: Run the full module**

Run: `mvn -q -pl netty-spring-websocket-cluster test`
Expected: all green.

- [ ] **Step 4: Commit**
```
git add netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ReliableBroadcastIntegrationTest.java
git commit -m "test(cluster): reliable broadcast integration — publish/consume, replay-on-resync, dead-group cleanup

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Auto-config — gated ReliableBroker bean + wiring + dead-node cleanup hook

**Files:**
- Modify: `netty-websocket-cluster-spring-boot-starter/.../configure/NettyWebSocketClusterConfigure.java`
- Modify: `netty-spring-websocket-cluster/.../cluster/ClusterMessageSender.java` (dead-node cleanup hook)
- Modify: `netty-websocket-cluster-spring-boot-starter/.../NettyWebSocketClusterConfigureTest.java`

- [ ] **Step 1: Wire dead-node group cleanup into the sender's dead-node callback**

In `ClusterMessageSender.java`, the existing `invalidateCacheForNode(String nodeId)` is registered as the node manager's dead-node callback (`nodeManager.setDeadNodeCallback(this::invalidateCacheForNode)` in `start()`). Extend it to also reap the dead node's reliable consumer groups:
```java
    public void invalidateCacheForNode(String nodeId) {
        nodeCache.entrySet().removeIf(e -> nodeId.equals(e.getValue().nodeId));
        ReliableBroker rb = reliableBroker;
        if (rb != null) {
            try { rb.destroyConsumerGroupsForNode(nodeId); }
            catch (Exception e) { log.debug("reliable group cleanup for dead node {} failed", nodeId, e); }
        }
    }
```
(This rides the existing once-per-dead-node reaper-gated callback — no `ClusterNodeManager` change, stays transport-agnostic.)

- [ ] **Step 2: Add the gated bean + wiring in auto-config**

In `NettyWebSocketClusterConfigure.java`, add imports:
```java
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisStreamsReliableBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ReliableBroker;
```
Add a gated bean after the `clusterBroker(...)` bean:
```java
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(ReliableBroker.class)
    @ConditionalOnProperty(prefix = "server.netty.websocket.cluster.reliable", name = "enable", havingValue = "true")
    public ReliableBroker reliableBroker(RedisClient redisClient, EnvelopeCodec envelopeCodec,
                                         ClusterProperties properties) {
        ClusterProperties.Reliable r = properties.getReliable();
        log.info("Reliable broadcast ENABLED (Redis Streams; maxlen={}, block={}ms)",
                r.getStreamMaxLen(), r.getPollBlockMs());
        return new RedisStreamsReliableBroker(redisClient, envelopeCodec,
                r.getStreamMaxLen(), r.getPollBlockMs(), r.getPollCount(), r.getDedupWindow());
    }
```
In the `clusterMessageSender(...)` bean method, add an optional `ReliableBroker` parameter and wire it before `sender.start()`. Change the method signature to add `@org.springframework.beans.factory.annotation.Autowired(required = false) ReliableBroker reliableBroker` and, before `sender.start();`, add:
```java
        if (reliableBroker != null) {
            sender.setReliableBroker(reliableBroker);
        }
```
So the bean becomes:
```java
    @Bean(destroyMethod = "shutdown")
    @Primary
    public ClusterMessageSender clusterMessageSender(
            @org.springframework.beans.factory.annotation.Qualifier("messageSender") MessageSender localSender,
            ClusterBroker broker,
            SessionRegistry sessionRegistry,
            ClusterNodeManager nodeManager,
            ClusterProperties properties,
            MessagePayloadCodec messagePayloadCodec,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ReliableBroker reliableBroker) {
        ClusterMessageSender sender = new ClusterMessageSender(
                localSender, broker, sessionRegistry, nodeManager,
                properties.getRegistryReadCacheTtlMs(), messagePayloadCodec);
        sender.setMessageMaxSizeBytes(properties.getMessageMaxSizeBytes());
        sender.setOnPublishFailure(properties.getOnPublishFailure());
        sender.setOnRedisLoss(properties.getOnRedisLoss());
        sender.setNodeLookupTimeoutMs(properties.getCommandTimeoutMs());
        if (reliableBroker != null) {
            sender.setReliableBroker(reliableBroker);
        }
        sender.start();
        log.info("ClusterMessageSender started — cluster mode is ACTIVE (onRedisLoss={}, onPublishFailure={}, maxMsgBytes={}, reliable={})",
                properties.getOnRedisLoss(), properties.getOnPublishFailure(), properties.getMessageMaxSizeBytes(),
                reliableBroker != null);
        return sender;
    }
```
**Important:** `setReliableBroker` must be called BEFORE `sender.start()` so `start()`'s reliable-subscribe loop runs.

- [ ] **Step 3: Add context-test assertions**

In `NettyWebSocketClusterConfigureTest.java`, in `enabled_primaryMessageSenderIsClusterSender_andHealthIndicatorRegistered()`, the default (no reliable props) must NOT create a reliable broker. Add after the existing assertions in that test:
```java
                    // reliable broadcast is OFF by default → no ReliableBroker bean
                    assertThat(context).doesNotHaveBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ReliableBroker.class);
```
Add a new test:
```java
    @Test
    void reliableEnabled_createsReliableBrokerBean() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-reliable-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30",
                        "server.netty.websocket.cluster.reliable.enable=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ReliableBroker.class);
                    assertThat(context.getBean(MessageSender.class)).isInstanceOf(ClusterMessageSender.class);
                });
    }
```

- [ ] **Step 4: Run the tests**

Run: `mvn -q -pl netty-websocket-cluster-spring-boot-starter -am test -Dtest=NettyWebSocketClusterConfigureTest`
Expected: all PASS (disabled path: no reliable bean; enabled path: reliable bean present). Also re-run `mvn -q -pl netty-spring-websocket-cluster test`.

- [ ] **Step 5: Commit**
```
git add netty-websocket-cluster-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigure.java netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterMessageSender.java netty-websocket-cluster-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigureTest.java
git commit -m "feat(cluster): gate ReliableBroker bean on reliable.enable + wire into sender + dead-node group cleanup

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: Documentation

**Files:**
- Modify: `docs/release-notes-1.9.0.md` — add a "可靠投递（Redis Streams）" section under 核心能力 + the 5 `reliable.*` config keys + the new tests; the delivery-contract note.
- Modify: `docs/api-guide.md` — §9 WebSocket Cluster: document `reliableBroadcast()` + the `reliable.*` config rows + the at-least-once contract.
- Modify: `docs/cluster-design.md` — move "Redis Streams 可靠投递 / reliableBroadcast" from the deferred list to ✅ (1.9.0); add the stream/consumer-group design + retention/durability note.
- Modify: `docs/development-plan.md` — mark reliable delivery done in the 1.9.0 cycle; remove it from the still-deferred backlog.

- [ ] **Step 1: Write the docs**

For each file, add accurate content (no code changes). Facts: opt-in `reliableBroadcast(uri, message)` (at-least-once, broadcast-only); per-URI stream + consumer-group-per-node; auto replay-on-resync; self-suppression + PEL dedup; gated `reliable.enable=false` default; 5 config keys (`enable`/`stream-max-len`=10000/`poll-block-ms`=2000/`poll-count`=64/`dedup-window`=1024); contract = at-least-once within the retention window, durability = your Redis, latency above Pub/Sub; dead-node groups reaped via reconciliation. Keep the still-deferred list (NATS, full metrics, HMAC, sharded, Redis Cluster client, W3C, Testcontainers).

**Encoding safety:** use the Edit tool only (NOT PowerShell file writes). After editing, run `for f in docs/*.md; do iconv -f UTF-8 -t UTF-8 "$f" >/dev/null && echo "$f ok" || echo "$f BAD"; done` and a U+FFFD scan `LC_ALL=C grep -lc $'\xef\xbf\xbd' docs/*.md` (expect none).

- [ ] **Step 2: Commit**
```
git add docs/
git commit -m "docs(cluster): reliable broadcast (Redis Streams) — release notes, api-guide, design

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: Full verification + cut RC2

**Files:**
- Modify: all 11 poms (`1.9.0-RC1` → `1.9.0-RC2`).

- [ ] **Step 1: Full reactor test (with Redis up)**

Run: `mvn test`
Expected: BUILD SUCCESS, all 11 modules. New tests: `ClusterReliableSenderTest` (3) always run; `ReliableBroadcastIntegrationTest` (3) + the new context test run with Redis up. Record the new aggregate total (was 304; ~+6 non-integration + integration). If anything fails, STOP and report.

- [ ] **Step 2: Bump to RC2**

Run (Bash, targeted — won't touch unrelated substrings):
```
for f in pom.xml */pom.xml; do sed -i 's|<version>1.9.0-RC1</version>|<version>1.9.0-RC2</version>|g' "$f"; done
grep -rl "1.9.0-RC1" --include=pom.xml . | wc -l   # expect 0
grep -rl "1.9.0-RC2" --include=pom.xml . | wc -l   # expect 11
```

- [ ] **Step 3: Re-test on RC2**

Run: `mvn -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit + tag**
```
git add -A
git commit -m "release: 1.9.0-RC2 — reliable broadcast via Redis Streams

Opt-in reliableBroadcast() (at-least-once, broadcast-only) over per-URI Redis
Streams + consumer-group-per-node, auto replay-on-resync, self-suppression,
PEL dedup, dead-node group cleanup. Gated off by default (reliable.enable=false).
Part of the 1.9.0 cycle; final 1.9.0 cut when the cycle completes.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git tag -a v1.9.0-RC2 -m "v1.9.0-RC2 — reliable broadcast via Redis Streams (1.9.0 cycle, in development)"
```

- [ ] **Step 5: Report** — RC2 cut locally (not pushed/deployed). The 1.9.0 cycle continues until the user says it's complete (then final 1.9.0 + v1.9.0 tag).

---

## Notes for the implementer

- **No SPI breakage:** `ReliableBroker`/`ReliableSubscription`(reused `ClusterSubscription`) are NEW; `ClusterBroker`/`SessionRegistry`/`EnvelopeCodec`/`MessagePayloadCodec`/`ClusterNodeHeartbeat`/`ClusterReaper` are untouched. `ClusterNodeManager` stays Redis-free (dead-node reliable cleanup rides the existing callback in `ClusterMessageSender`).
- **Disabled = zero overhead:** with `reliable.enable=false` (default) the `ReliableBroker` bean is never created (`@ConditionalOnProperty`), `setReliableBroker` is never called, no consume threads/connections exist, and `reliableBroadcast` throws.
- **At-least-once caveat:** the entry-id dedup ring is in-process — a cross-crash redelivery can still duplicate (that is what at-least-once means); document that app handlers should be idempotent.
- **Lettuce version drift:** if a Streams method signature differs from the 6.1.x shapes used here, adapt to the available overload without changing behavior; verify with `mvn compile`.
```
