# Multi Pub/Sub Connections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in `pubsub-connections` knob (default 1) that spreads the standalone `RedisPubSubBroker`'s SUBSCRIBE inbound decode across N Lettuce pub/sub connections, partitioned by channel hash.

**Architecture:** Replace the broker's single `subscribeConnection` with N connections; a channel is pinned to `subscribeConnections[floorMod(channel.hashCode(), N)]` for SUBSCRIBE, inbound, and UNSUBSCRIBE. Redis routes each channel's messages to its one connection, so connection *i* decodes only its partition → N-way parallel inbound. Default 1 = byte-identical to today.

**Tech Stack:** Java 17, Lettuce 6.1.10 (pub/sub), Spring Boot 2.7.18 auto-config, JUnit 5 + Mockito, real-Redis integration test.

---

## Environment notes for every task
- Repo: `C:\Users\qq951\IdeaProjects\netty-spring`; `./mvnw`; Java 17. Docker live; Redis on `localhost:16379`.
- Branch `feature/1.9.x-multi-pubsub` (Task 0), cut from `master` @ `5283e53`. Do NOT push/deploy. **No RC tag / no version bump** (rides to the next RC/final cut).
- Cluster module tests use **JUnit 5 + Mockito (NO AssertJ)**.
- Verified facts you can rely on: the inbound listener path (`ClusterMessageSender.onBroadcastMessage`/`onUnicastMessage`) is thread-safe for the cross-channel concurrency this introduces (AtomicLong stats, per-thread MDC, stateless decode, concurrent-safe `localSender`); a channel is pinned to one connection so per-channel ordering is preserved; **n=1 is byte-identical to today**.
- The real-Redis IT (delivery correct under N=3) is the integration oracle — a dropped channel is a real bug, never weaken it.

## File Structure
- `RedisPubSubBroker.java` (modify) — N subscribe connections + hash routing; the only `src/main` logic change.
- `ClusterProperties.java` (modify) — `pubsubConnections` knob.
- `NettyWebSocketClusterConfigure.java` (modify) — pass the count into the `clusterBroker` bean.
- `RedisPubSubBrokerMultiConnTest.java` (new) — Mockito partition/routing unit test.
- `RedisIntegrationTest.java` (modify) — one IT method: delivery under N=3.
- `docs/cluster-design.md`, `docs/api-guide.md` (modify) — scope row ⏳→✅ + config row.

---

## Task 0: Branch
- [ ] **Step 1:** `git checkout master && git checkout -b feature/1.9.x-multi-pubsub && git branch --show-current` → `feature/1.9.x-multi-pubsub`. Confirm `git log --oneline -1` shows `5283e53 docs: multi pub/sub connections design spec ...` (or later).

---

## Task 1: `RedisPubSubBroker` — N subscribe connections + hash routing

**Files:** Modify `netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/redis/RedisPubSubBroker.java`

Apply these exact edits (the file currently has a single `subscribeConnection`).

- [ ] **Step 1: Field — single connection → list**

Replace:
```java
    /** Pub/Sub connection for SUBSCRIBE callbacks. */
    private final StatefulRedisPubSubConnection<String, String> subscribeConnection;
```
with:
```java
    /** Pub/Sub connections for SUBSCRIBE callbacks. Channels are partitioned across these by a stable
     *  hash so inbound decode runs on up to N Lettuce I/O threads. Size N = pubsub-connections (>= 1).
     *  The {@link #channelListeners} map is shared across all of them. */
    private final java.util.List<StatefulRedisPubSubConnection<String, String>> subscribeConnections;
```

- [ ] **Step 2: Constructors — add the 4-arg, delegate the others**

Replace the existing two constructors:
```java
    /** Backward-compat constructor — no authentication (NoOp). */
    public RedisPubSubBroker(RedisClient redisClient, EnvelopeCodec codec) {
        this(redisClient, codec, new NoOpMessageAuthenticator());
    }

    public RedisPubSubBroker(RedisClient redisClient, EnvelopeCodec codec, MessageAuthenticator authenticator) {
        this.codec = codec;
        this.authenticator = java.util.Objects.requireNonNull(authenticator, "authenticator");
```
with (note: the `redisClient.addListener(...)` health block that follows in the file stays EXACTLY as-is — do not edit it):
```java
    /** Backward-compat constructor — no authentication (NoOp), single pub/sub connection. */
    public RedisPubSubBroker(RedisClient redisClient, EnvelopeCodec codec) {
        this(redisClient, codec, new NoOpMessageAuthenticator());
    }

    /** Single pub/sub connection (delegates with {@code pubsubConnections = 1}). */
    public RedisPubSubBroker(RedisClient redisClient, EnvelopeCodec codec, MessageAuthenticator authenticator) {
        this(redisClient, codec, authenticator, 1);
    }

    /**
     * @param pubsubConnections number of Redis pub/sub SUBSCRIBE connections to spread inbound decode
     *                          across (clamped to {@code [1, 16]}); {@code 1} = single connection.
     */
    public RedisPubSubBroker(RedisClient redisClient, EnvelopeCodec codec, MessageAuthenticator authenticator,
                             int pubsubConnections) {
        this.codec = codec;
        this.authenticator = java.util.Objects.requireNonNull(authenticator, "authenticator");

        int n = Math.max(1, Math.min(16, pubsubConnections));
        if (n != pubsubConnections) {
            log.warn("pubsub-connections={} out of range [1,16] — clamped to {}", pubsubConnections, n);
        }
```

- [ ] **Step 3: Connection setup — replace the single subscribe connection + adapter with N**

Replace:
```java
        this.publishConnection = redisClient.connect();
        this.subscribeConnection = redisClient.connectPubSub();

        // Wire up the Lettuce pub/sub listener
        subscribeConnection.addListener(new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String channel, String message) {
                // Inbound size guard — reject oversized messages BEFORE allocating via decode/Base64.
                int max = inboundMaxBytes;
                if (max > 0 && message != null && message.length() > max) {
                    log.warn("Dropping oversized inbound cluster message on channel {} ({} > {} bytes) "
                            + "— possible misbehaving/hostile publisher", channel, message.length(), max);
                    return;
                }
                ClusterMessageListener listener = channelListeners.get(channel);
                if (listener != null) {
                    String inner = authenticator.unwrap(message);
                    if (inner == null) {
                        log.warn("Rejected inbound cluster message on channel {} — missing/invalid HMAC tag", channel);
                        return;
                    }
                    try {
                        ClusterEnvelope envelope = codec.decode(inner);
                        if (envelope != null) { // null = unsupported version, already logged
                            listener.onMessage(envelope);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to decode cluster envelope on channel {}", channel, e);
                    }
                }
            }
        });

        log.info("RedisPubSubBroker initialized (codec={})", codec.getClass().getSimpleName());
    }
```
with:
```java
        this.publishConnection = redisClient.connect();

        // N pub/sub connections; each decodes only the channels that hash to it (Redis routes per
        // connection), parallelising inbound decode across up to N Lettuce I/O threads. The shared
        // channelListeners map lets any connection's adapter resolve the listener for its channel.
        java.util.List<StatefulRedisPubSubConnection<String, String>> conns = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StatefulRedisPubSubConnection<String, String> sub = redisClient.connectPubSub();
            sub.addListener(new RedisPubSubAdapter<String, String>() {
                @Override
                public void message(String channel, String message) {
                    onInboundMessage(channel, message);
                }
            });
            conns.add(sub);
        }
        this.subscribeConnections = conns;

        log.info("RedisPubSubBroker initialized (codec={}, pubsubConnections={})",
                codec.getClass().getSimpleName(), n);
    }

    /**
     * Handles an inbound pub/sub message from any of the N subscribe connections: inbound-size guard,
     * listener lookup, HMAC unwrap, decode, dispatch. May run concurrently on up to N I/O threads for
     * DIFFERENT channels (a channel is pinned to one connection, so same-channel messages stay ordered);
     * the downstream listener is concurrency-safe.
     */
    private void onInboundMessage(String channel, String message) {
        int max = inboundMaxBytes;
        if (max > 0 && message != null && message.length() > max) {
            log.warn("Dropping oversized inbound cluster message on channel {} ({} > {} bytes) "
                    + "— possible misbehaving/hostile publisher", channel, message.length(), max);
            return;
        }
        ClusterMessageListener listener = channelListeners.get(channel);
        if (listener != null) {
            String inner = authenticator.unwrap(message);
            if (inner == null) {
                log.warn("Rejected inbound cluster message on channel {} — missing/invalid HMAC tag", channel);
                return;
            }
            try {
                ClusterEnvelope envelope = codec.decode(inner);
                if (envelope != null) { // null = unsupported version, already logged
                    listener.onMessage(envelope);
                }
            } catch (Exception e) {
                log.warn("Failed to decode cluster envelope on channel {}", channel, e);
            }
        }
    }

    /** Maps a channel to its (stable) subscribe connection, so SUBSCRIBE, inbound and UNSUBSCRIBE for a
     *  channel always use the same connection. */
    private StatefulRedisPubSubConnection<String, String> connectionFor(String channel) {
        return subscribeConnections.get(Math.floorMod(channel.hashCode(), subscribeConnections.size()));
    }
```

- [ ] **Step 4: Route subscribe/subscribeUnicast to the hashed connection**

Replace `subscribeConnection.async().subscribe(channel);` in `subscribe(...)` with:
```java
        connectionFor(channel).async().subscribe(channel);
```
and the identical line in `subscribeUnicast(...)` with the same `connectionFor(channel).async().subscribe(channel);`.

- [ ] **Step 5: Route unsubscribe (in `createSubscription`) to the hashed connection**

Replace:
```java
                    try { subscribeConnection.async().unsubscribe(channel); }
                    catch (Exception e) { log.debug("Unsubscribe from {} failed", channel); }
```
with:
```java
                    try { connectionFor(channel).async().unsubscribe(channel); }
                    catch (Exception e) { log.debug("Unsubscribe from {} failed", channel); }
```

- [ ] **Step 6: Close all N connections on shutdown**

Replace:
```java
        try { subscribeConnection.close(); } catch (Exception e) { log.warn("Error closing pub/sub conn", e); }
        try { publishConnection.close(); } catch (Exception e) { log.warn("Error closing publish conn", e); }
```
with:
```java
        for (StatefulRedisPubSubConnection<String, String> sub : subscribeConnections) {
            try { sub.close(); } catch (Exception e) { log.warn("Error closing pub/sub conn", e); }
        }
        try { publishConnection.close(); } catch (Exception e) { log.warn("Error closing publish conn", e); }
```

`publish`, `unicast`, `state`, `checkActive`, `setInboundMaxBytes`, `setTransportStateListener` are UNCHANGED (publish stays single-connection). Do NOT touch `RedisClusterModePubSubBroker`.

- [ ] **Step 7: Compile**

Run: `./mvnw -B -ntp -pl netty-spring-websocket-cluster -am -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**
```bash
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/redis/RedisPubSubBroker.java
git commit -m "feat(cluster): multiplex RedisPubSubBroker SUBSCRIBE across N pub/sub connections

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: `ClusterProperties.pubsubConnections`

**Files:** Modify `netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterProperties.java`

- [ ] **Step 1: Add the field** immediately AFTER the `commandTimeoutMs` field:

Find:
```java
    /** Redis command timeout in ms for the cluster control plane. Bounds how long any single
     *  Redis operation (incl. the unicast hot-path registry lookup) can block when Redis is
     *  unreachable — much lower than Lettuce's 60s default. Default 2000. */
    private long commandTimeoutMs = 2000;
```
Insert right after it:
```java

    /** Number of Redis Pub/Sub SUBSCRIBE connections to spread inbound decode across. {@code 1}
     *  (default) = single connection, byte-identical to pre-1.9.x behavior. 2–4 is recommended ONLY
     *  when a node approaches the single Lettuce pub/sub connection decode ceiling (~80k msg/s — see
     *  docs/cluster-design.md). Clamped to {@code [1, 16]}. Redis-Pub/Sub-specific (no effect on
     *  other transports). */
    private int pubsubConnections = 1;
```

- [ ] **Step 2: Add the accessors** immediately AFTER the `commandTimeoutMs` accessors:

Find:
```java
    public long getCommandTimeoutMs() { return commandTimeoutMs; }
    public void setCommandTimeoutMs(long v) { this.commandTimeoutMs = v; }
```
Insert right after:
```java

    public int getPubsubConnections() { return pubsubConnections; }
    public void setPubsubConnections(int v) { this.pubsubConnections = v; }
```

- [ ] **Step 3: Compile** — `./mvnw -B -ntp -pl netty-spring-websocket-cluster -am -DskipTests compile` → BUILD SUCCESS.

- [ ] **Step 4: Commit**
```bash
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterProperties.java
git commit -m "feat(cluster): pubsub-connections config property (default 1)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Auto-config wiring

**Files:** Modify `netty-websocket-cluster-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigure.java`

- [ ] **Step 1: Pass the count into the standalone broker**

In the `clusterBroker` bean, replace:
```java
        RedisPubSubBroker broker = new RedisPubSubBroker(redisClient, envelopeCodec, messageAuthenticator);
```
with:
```java
        RedisPubSubBroker broker = new RedisPubSubBroker(redisClient, envelopeCodec, messageAuthenticator,
                properties.getPubsubConnections());
```
(The `setInboundMaxBytes(...)` line after it and everything else in the bean stays the same.)

- [ ] **Step 2: Compile** — `./mvnw -B -ntp -pl netty-websocket-cluster-spring-boot-starter -am -DskipTests compile` → BUILD SUCCESS.

- [ ] **Step 3: Commit**
```bash
git add netty-websocket-cluster-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigure.java
git commit -m "feat(cluster): wire pubsub-connections into the clusterBroker bean

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Unit test — partition routing (Mockito, no live Redis)

**Files:** Create `netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/redis/RedisPubSubBrokerMultiConnTest.java`

(Read `RedisClusterModeSessionRegistryTest` first for the module's Mockito conventions — JUnit 5 + Mockito, no AssertJ.)

- [ ] **Step 1: Write the test**
```java
/* Apache 2.0 header copied from a sibling test file */
package com.github.berrywang1996.netty.spring.web.websocket.cluster.redis;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterMessageListener;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisPubSubBrokerMultiConnTest {

    private static final int N = 4;

    private static int expectedIndex(String uri) {
        return Math.floorMod(("netty:broadcast:" + uri).hashCode(), N);
    }

    @SuppressWarnings("unchecked")
    private static List<RedisPubSubAsyncCommands<String, String>> wireMockClient(RedisClient client) {
        when(client.connect()).thenReturn(mock(StatefulRedisConnection.class));
        List<StatefulRedisPubSubConnection<String, String>> conns = new ArrayList<>();
        List<RedisPubSubAsyncCommands<String, String>> asyncs = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            StatefulRedisPubSubConnection<String, String> conn = mock(StatefulRedisPubSubConnection.class);
            RedisPubSubAsyncCommands<String, String> async = mock(RedisPubSubAsyncCommands.class);
            when(conn.async()).thenReturn(async);
            conns.add(conn);
            asyncs.add(async);
        }
        when(client.connectPubSub()).thenReturn(conns.get(0), conns.get(1), conns.get(2), conns.get(3));
        return asyncs;
    }

    @Test
    void opensNConnectionsAndRoutesEachChannelToItsHashedConnection() {
        RedisClient client = mock(RedisClient.class);
        List<RedisPubSubAsyncCommands<String, String>> asyncs = wireMockClient(client);

        RedisPubSubBroker broker = new RedisPubSubBroker(client, new SimpleTextEnvelopeCodec(),
                new NoOpMessageAuthenticator(), N);

        verify(client, times(N)).connectPubSub();

        // Six distinct URIs chosen so their broadcast channels hit >= 2 of the 4 connections. If a
        // future change to these strings makes them all share one index, pick others (compute
        // Math.floorMod(("netty:broadcast:"+uri).hashCode(), 4) to check).
        String[] uris = {"/ws/a", "/ws/b", "/ws/c", "/ws/d", "/ws/e", "/ws/f"};
        ClusterMessageListener noop = env -> { };
        Set<Integer> indicesUsed = new HashSet<>();
        for (String uri : uris) {
            int idx = expectedIndex(uri);
            indicesUsed.add(idx);
            broker.subscribe(uri, noop);
            verify(asyncs.get(idx)).subscribe("netty:broadcast:" + uri);
        }
        // each channel went ONLY to its hashed connection
        for (String uri : uris) {
            int idx = expectedIndex(uri);
            for (int j = 0; j < N; j++) {
                if (j != idx) {
                    verify(asyncs.get(j), never()).subscribe("netty:broadcast:" + uri);
                }
            }
        }
        assertTrue(indicesUsed.size() >= 2,
                "channels should spread across >= 2 connections, got " + indicesUsed);
    }

    @Test
    void sameChannelAlwaysRoutesToTheSameConnection() {
        RedisClient client = mock(RedisClient.class);
        List<RedisPubSubAsyncCommands<String, String>> asyncs = wireMockClient(client);

        RedisPubSubBroker broker = new RedisPubSubBroker(client, new SimpleTextEnvelopeCodec(),
                new NoOpMessageAuthenticator(), N);

        int idx = expectedIndex("/ws/repeat");
        broker.subscribe("/ws/repeat", env -> { });
        broker.subscribe("/ws/repeat", env -> { });
        verify(asyncs.get(idx), times(2)).subscribe("netty:broadcast:/ws/repeat");
    }

    @Test
    void singleConnectionByDefault() {
        RedisClient client = mock(RedisClient.class);
        when(client.connect()).thenReturn(mock(StatefulRedisConnection.class));
        StatefulRedisPubSubConnection<String, String> conn = mock(StatefulRedisPubSubConnection.class);
        RedisPubSubAsyncCommands<String, String> async = mock(RedisPubSubAsyncCommands.class);
        when(conn.async()).thenReturn(async);
        when(client.connectPubSub()).thenReturn(conn);

        // 3-arg ctor must behave as a single connection (n=1).
        RedisPubSubBroker broker = new RedisPubSubBroker(client, new SimpleTextEnvelopeCodec(),
                new NoOpMessageAuthenticator());

        verify(client, times(1)).connectPubSub();
        broker.subscribe("/ws/x", env -> { });
        broker.subscribe("/ws/y", env -> { });
        verify(async).subscribe("netty:broadcast:/ws/x");
        verify(async).subscribe("netty:broadcast:/ws/y");
    }
}
```
The `@SuppressWarnings("unchecked")` on `wireMockClient` covers the generic-mock warnings. If Mockito complains about mocking `subscribe(String...)` varargs, note that `verify(async).subscribe("netty:broadcast:" + uri)` matches a single-element call; do not pass `any(String[].class)`.

- [ ] **Step 2: Run**

Run: `./mvnw -B -ntp -pl netty-spring-websocket-cluster -am test -Dtest=RedisPubSubBrokerMultiConnTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 3 tests pass. If `opensNConnections...` fails on the `indicesUsed.size() >= 2` assertion, the chosen URIs all hashed to one connection — replace one or two URIs (the routing `verify`s are the real assertion and will still pass).

- [ ] **Step 3: Commit**
```bash
git add netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/redis/RedisPubSubBrokerMultiConnTest.java
git commit -m "test(cluster): pin RedisPubSubBroker channel→connection partitioning

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Integration test — delivery under N=3 (real Redis)

**Files:** Modify `netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/RedisIntegrationTest.java`

(The existing tests already cover n=1 — they all use the 2-arg `RedisPubSubBroker(client, codec)` constructor = single connection. This adds the N=3 case.)

- [ ] **Step 1: Append the IT method** (before the `RecordingSender` helper class at the end of the file):
```java
    // ==================== 8. Multi pub/sub connections (N=3) ====================

    @Test
    @Order(14)
    void multiPubSubConnectionsDeliverEveryChannel() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        RedisClient pubClient = RedisClient.create(REDIS_URI);
        RedisClient subClient = RedisClient.create(REDIS_URI);
        RedisPubSubBroker publisher = new RedisPubSubBroker(pubClient, new SimpleTextEnvelopeCodec());
        // 3 SUBSCRIBE connections — channels partition across them.
        RedisPubSubBroker subscriber = new RedisPubSubBroker(subClient, new SimpleTextEnvelopeCodec(),
                new com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator(), 3);

        String[] uris = {"/ws/mc0", "/ws/mc1", "/ws/mc2", "/ws/mc3", "/ws/mc4"};
        Map<String, List<ClusterEnvelope>> received = new java.util.concurrent.ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(uris.length + 1); // + 1 unicast
        for (String uri : uris) {
            received.put(uri, new CopyOnWriteArrayList<>());
            subscriber.subscribe(uri, env -> { received.get(env.getUri()).add(env); latch.countDown(); });
        }
        List<ClusterEnvelope> unicasts = new CopyOnWriteArrayList<>();
        subscriber.subscribeUnicast("mc-node", env -> { unicasts.add(env); latch.countDown(); });

        Thread.sleep(500); // let SUBSCRIBE settle across all 3 connections

        for (String uri : uris) {
            publisher.publish(uri, new ClusterEnvelope("pub", uri, ClusterEnvelope.MessageKind.BROADCAST,
                    ("T:" + uri).getBytes(), null, null, System.currentTimeMillis()));
        }
        publisher.unicast("mc-node", new ClusterEnvelope("pub", "/ws/mcU", ClusterEnvelope.MessageKind.UNICAST,
                "T:u".getBytes(), "sX", null, System.currentTimeMillis()));

        assertTrue(latch.await(6, TimeUnit.SECONDS),
                "every channel (across 3 pub/sub connections) must deliver");
        for (String uri : uris) {
            assertEquals(1, received.get(uri).size(), "channel " + uri + " must receive exactly its message");
            assertEquals("pub", received.get(uri).get(0).getOriginNodeId());
        }
        assertEquals(1, unicasts.size());
        assertEquals("sX", unicasts.get(0).getTargetSessionId());

        subscriber.shutdown();
        publisher.shutdown();
        subClient.shutdown();
        pubClient.shutdown();
    }
```

- [ ] **Step 2: Run**

Run: `./mvnw -B -ntp -pl netty-spring-websocket-cluster -am test -Dtest=RedisIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all `RedisIntegrationTest` methods pass, **NOT skipped** (Redis on `localhost:16379` or Docker). `multiPubSubConnectionsDeliverEveryChannel` must deliver all 6 messages. If a channel is missing, this is a real routing bug in Task 1 (check `connectionFor`/the per-connection adapter) — fix it, don't weaken the assert.

- [ ] **Step 3: Commit**
```bash
git add netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/RedisIntegrationTest.java
git commit -m "test(cluster): real-Redis delivery under 3 pub/sub connections (the oracle)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Docs

**Files:** Modify `docs/cluster-design.md`, `docs/api-guide.md`. **Edit tool only**; after editing, run a U+FFFD scan on each file (Grep pattern `�`, expect 0 hits) and spot-read an edited Chinese section.

- [ ] **Step 1: cluster-design.md — flip the scope row**

Find the row:
```
| 多 pub/sub 连接并行解码（`pubsub-connections`） | ⏳ 未来版本 | 实测吞吐远低于单连接天花板，过早优化 |
```
Replace with:
```
| 多 pub/sub 连接并行解码（`pubsub-connections`） | ✅ 1.9.x（opt-in，默认 1） | 把 SUBSCRIBE 入站解码按频道哈希分散到 N 个 Lettuce 连接；**默认关闭、零开销**，仅在逼近单连接解码墙（墙②，~80k msg/s）时按需开启。非当前瓶颈，纯前瞻性开关 |
```
If the "walls"/容量 section's wall-② paragraph (the `~80k` single-connection decode ceiling, around line 124) describes "每节点 2–4 个 pub/sub 连接" as a future mitigation, append a short clause noting it is now available opt-in via `pubsub-connections`（默认 1）.

- [ ] **Step 2: api-guide.md — add the config row**

In the §9 cluster config-reference table (the same table that lists `cluster.redis.uri` / `command-timeout-ms`), add a row:
```
| `cluster.pubsub-connections` | `1` | Redis Pub/Sub SUBSCRIBE 连接数；入站解码按频道哈希分散到 N 个连接。`1`=单连接（默认，行为不变）；仅当单节点逼近 ~80k msg/s 解码墙时设 2–4。范围 [1,16]。 |
```
(Match the table's existing column format/language; if the table is English, write the row in English instead.)

- [ ] **Step 3: Encoding check** — Grep `�` over both files → 0 hits; spot-read the edited rows.

- [ ] **Step 4: Commit**
```bash
git add docs/cluster-design.md docs/api-guide.md
git commit -m "docs(cluster): pubsub-connections opt-in (scope row + config reference)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: Full test + finish the branch

- [ ] **Step 1: Full reactor** — `./mvnw -B -ntp test` (Docker + Redis live) → BUILD SUCCESS, 11 modules. The new `RedisPubSubBrokerMultiConnTest` (3) runs; `RedisIntegrationTest` runs incl. `multiPubSubConnectionsDeliverEveryChannel`, NOT skipped. STOP and fix if anything fails or the multi-conn IT skips.

- [ ] **Step 2: Finish** — Use **superpowers:finishing-a-development-branch**: FF-merge `feature/1.9.x-multi-pubsub` into `master`, delete the branch, **NO push, NO deploy, NO RC tag** (this feature does not bump the RC; it rides to the next RC / final cut with the cross-node JSON fix). Confirm `master` has the commits and the tree is clean.

---

## Notes for the implementer
- Default `1` MUST be byte-identical to today (the list has one element, `connectionFor` always returns index 0). The existing `RedisIntegrationTest` methods (all n=1) are the regression — keep them green.
- The real-Redis IT is the oracle: a channel that doesn't deliver under N=3 is a routing bug, never a reason to weaken the test.
- Do not touch `RedisClusterModePubSubBroker`, `publish`/`unicast` (single publish connection is intentional), or the `RedisConnectionStateListener` health block.
