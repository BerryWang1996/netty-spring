# NATS Broker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An additive `NatsClusterBroker` (NATS core pub/sub) implementing the `ClusterBroker` SPI, selected by `nats.servers`, parallel to `RedisPubSubBroker` — ADR-001's "scaling tier".

**Architecture:** `ClusterMessageSender` depends only on the `ClusterBroker` + `SessionRegistry` SPIs, so swapping the broker bean to NATS is transparent. NATS subjects `netty.broadcast.<b64url(uri)>` / `netty.unicast.<b64url(nodeId)>`; registry/heartbeat stay Redis (mixed model). Core pub/sub is at-most-once.

**Tech Stack:** Java 17, `io.nats:jnats` 2.20.x (optional), Spring Boot 2.7 auto-config, JUnit 5 + Mockito, NATS Testcontainers (`nats:2.10`).

---

## Environment notes for every task
- Repo: `C:\Users\qq951\IdeaProjects\netty-spring`; `./mvnw`; Java 17. Docker live (Testcontainers for NATS + Redis).
- Branch `feature/1.9.x-nats-broker` (Task 0), from `master` @ `a6a8c54`. Do NOT push/deploy. This feature **DOES bump the RC → v1.9.0-RC9** (Task 8).
- Cluster module tests: **JUnit 5 + Mockito (NO AssertJ)**.

## ⚠️ jnats API verification mandate (read first)
The NATS code below is written from jnats 2.20.x API knowledge and **must be verified empirically** — the `NatsIntegrationTest` (Task 6) is the oracle. Each impl task ends by compiling/running against the real jar. If an `io.nats.client` call doesn't compile or the IT doesn't receive, **fix the API against the actual jnats jar** (`Connection`, `Dispatcher`, `Message`, `ConnectionListener`/`Events`, `Options`, `Nats`) until publish→receive works. Do NOT weaken the IT. Expected API: `Nats.connect(Options)` (throws IOException/InterruptedException); `Options.builder().server(String).connectionListener(ConnectionListener).maxReconnects(int).build()`; `Connection.publish(String,byte[])`, `Connection.createDispatcher(MessageHandler)→Dispatcher`, `Connection.close()` (throws InterruptedException); `Dispatcher.subscribe(String)`, `Dispatcher.unsubscribe(String)`; `Message.getSubject()`, `Message.getData()→byte[]`; `ConnectionListener.connectionEvent(Connection, ConnectionListener.Events)` with `Events.{CONNECTED,DISCONNECTED,RECONNECTED,CLOSED,...}`.

## File Structure
- New (main): `…cluster/nats/NatsClusterBroker.java` — the broker (only src/main logic file).
- Modified (main): `ClusterProperties.java` (`Nats.servers`); `NettyWebSocketClusterConfigure.java` (NATS broker bean + `&& NO_NATS` on the Redis brokers); 3 poms (jnats dep); metadata json.
- New (test): `…cluster/nats/NatsClusterBrokerTest.java` (Mockito); `…cluster/ClusterTestNats.java` + `ClusterTestNatsSelfTest.java` + `NatsIntegrationTest.java`; starter `…boot/configure/ClusterTestNats.java` (duplicate).
- Modified (test): starter `NettyWebSocketClusterConfigureTest.java`.
- Docs: `cluster-design.md`, `api-guide.md`, `release-notes-1.9.0.md` (Task 8).

---

## Task 0: Branch
- [ ] `git checkout master && git checkout -b feature/1.9.x-nats-broker && git branch --show-current` → `feature/1.9.x-nats-broker`. Confirm `git log --oneline -1` shows `a6a8c54 docs: NATS broker design spec ...` (or later).

---

## Task 1: jnats dependency

**Files:** `pom.xml` (parent), `netty-spring-websocket-cluster/pom.xml`, `netty-websocket-cluster-spring-boot-starter/pom.xml`

- [ ] **Step 1: Parent — version property + dependencyManagement.** In the parent `pom.xml`, add `<jnats.version>2.20.4</jnats.version>` to `<properties>`, and inside `<dependencyManagement><dependencies>` add:
```xml
            <dependency>
                <groupId>io.nats</groupId>
                <artifactId>jnats</artifactId>
                <version>${jnats.version}</version>
            </dependency>
```
- [ ] **Step 2: Cluster module — optional jnats.** In `netty-spring-websocket-cluster/pom.xml`, after the `lettuce-core` (optional) dependency, add:
```xml
        <!-- NATS client (optional — only needed for the NATS transport impl) -->
        <dependency>
            <groupId>io.nats</groupId>
            <artifactId>jnats</artifactId>
            <optional>true</optional>
        </dependency>
```
- [ ] **Step 3: Starter — optional jnats.** In `netty-websocket-cluster-spring-boot-starter/pom.xml`, after the `lettuce-core` dependency, add:
```xml
        <!-- NATS client (optional — enables the NATS cluster broker when present) -->
        <dependency>
            <groupId>io.nats</groupId>
            <artifactId>jnats</artifactId>
            <optional>true</optional>
        </dependency>
```
- [ ] **Step 4: Verify** — `./mvnw -B -ntp -pl netty-spring-websocket-cluster -am -DskipTests compile` → BUILD SUCCESS and jnats resolves (no "cannot find symbol io.nats" later). If `2.20.4` doesn't resolve via the mirror, pick the nearest available 2.x (e.g. `2.20.5`/`2.17.2`) and note it.
- [ ] **Step 5: Commit**
```bash
git add pom.xml netty-spring-websocket-cluster/pom.xml netty-websocket-cluster-spring-boot-starter/pom.xml
git commit -m "build(cluster): optional io.nats:jnats dependency

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: `NatsClusterBroker`

**Files:** Create `netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/nats/NatsClusterBroker.java`

(Read `…cluster/redis/RedisPubSubBroker.java` first — this is its NATS twin, same codec/auth/inbound-cap/state/createSubscription contract.)

- [ ] **Step 1: Write the broker** (15-line Apache header from a sibling, then):
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.nats;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.*;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NATS core Pub/Sub implementation of {@link ClusterBroker} — ADR-001's "scaling tier", the NATS twin of
 * {@code RedisPubSubBroker}. Broadcast → subject {@code netty.broadcast.<b64url(uri)>}, unicast →
 * {@code netty.unicast.<b64url(nodeId)>}. Same {@link EnvelopeCodec} + {@link MessageAuthenticator} +
 * inbound-size-cap + {@link TransportStateListener} contract; core pub/sub is at-most-once (JetStream
 * reliable is a separate future impl). Transport only — the SessionRegistry/heartbeat stay on Redis.
 *
 * <p>The NATS {@link Connection} is injected via {@link #attach(Connection)} after construction so the
 * connection's build-time {@link ConnectionListener} can target {@link #onConnectionEvent}.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class NatsClusterBroker implements ClusterBroker {

    private static final String BROADCAST_PREFIX = "netty.broadcast.";
    private static final String UNICAST_PREFIX = "netty.unicast.";

    private final EnvelopeCodec codec;
    private final MessageAuthenticator authenticator;
    private final AtomicReference<BrokerState> state = new AtomicReference<>(BrokerState.ACTIVE);

    /** Subject → listener (resolved by the single shared dispatcher's handler). */
    private final ConcurrentHashMap<String, ClusterMessageListener> channelListeners = new ConcurrentHashMap<>();

    private volatile Connection connection;
    private volatile Dispatcher dispatcher;
    private volatile int inboundMaxBytes = 0;
    private volatile TransportStateListener transportStateListener;

    /** Backward-compat constructor — no authentication (NoOp). */
    public NatsClusterBroker(EnvelopeCodec codec) {
        this(codec, new NoOpMessageAuthenticator());
    }

    public NatsClusterBroker(EnvelopeCodec codec, MessageAuthenticator authenticator) {
        this.codec = codec;
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    /**
     * Injects the NATS connection (built by the auto-config with {@link #onConnectionEvent} as its
     * ConnectionListener) and creates the single shared subscribe dispatcher. Called once after construction.
     */
    public void attach(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.dispatcher = connection.createDispatcher(msg -> onInboundMessage(msg.getSubject(), msg.getData()));
        log.info("NatsClusterBroker initialized (codec={})", codec.getClass().getSimpleName());
    }

    /** NATS connection-state callback (registered as the connection's ConnectionListener at build time). */
    public void onConnectionEvent(Connection conn, ConnectionListener.Events type) {
        switch (type) {
            case DISCONNECTED:
            case CLOSED:
                if (state.compareAndSet(BrokerState.ACTIVE, BrokerState.DEGRADED)) {
                    log.warn("NATS transport {} — broker DEGRADED (cross-node paused, local fan-out continues)", type);
                    TransportStateListener l = transportStateListener;
                    if (l != null) {
                        try { l.onTransportLost(); } catch (Exception e) { log.debug("onTransportLost failed", e); }
                    }
                }
                break;
            case CONNECTED:
            case RECONNECTED:
                if (state.compareAndSet(BrokerState.DEGRADED, BrokerState.ACTIVE)) {
                    log.info("NATS transport {} — broker ACTIVE", type);
                    TransportStateListener l = transportStateListener;
                    if (l != null) {
                        try { l.onTransportRestored(); } catch (Exception e) { log.debug("onTransportRestored failed", e); }
                    }
                }
                break;
            default:
                log.debug("NATS connection event {}", type);
        }
    }

    /** Encodes a uri/nodeId into a single NATS-subject-safe token (base64url, no padding). */
    private static String subjectToken(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Sets the max accepted size of an inbound message (bytes) before decode. 0 = unlimited. */
    public void setInboundMaxBytes(int inboundMaxBytes) {
        this.inboundMaxBytes = Math.max(0, inboundMaxBytes);
    }

    @Override
    public void publish(String uri, ClusterEnvelope envelope) {
        checkActive();
        String subject = BROADCAST_PREFIX + subjectToken(uri);
        byte[] data = authenticator.wrap(codec.encode(envelope)).getBytes(StandardCharsets.UTF_8);
        connection.publish(subject, data);
    }

    @Override
    public void unicast(String targetNodeId, ClusterEnvelope envelope) {
        checkActive();
        String subject = UNICAST_PREFIX + subjectToken(targetNodeId);
        byte[] data = authenticator.wrap(codec.encode(envelope)).getBytes(StandardCharsets.UTF_8);
        connection.publish(subject, data);
    }

    /** Handles an inbound NATS message: inbound-size guard → listener lookup → HMAC unwrap → decode → dispatch.
     *  Runs on a NATS dispatcher thread; the downstream listener is concurrency-safe. */
    private void onInboundMessage(String subject, byte[] bytes) {
        int max = inboundMaxBytes;
        if (max > 0 && bytes != null && bytes.length > max) {
            log.warn("Dropping oversized inbound cluster message on subject {} ({} > {} bytes) "
                    + "— possible misbehaving/hostile publisher", subject, bytes.length, max);
            return;
        }
        ClusterMessageListener listener = channelListeners.get(subject);
        if (listener != null) {
            String inner = authenticator.unwrap(new String(bytes, StandardCharsets.UTF_8));
            if (inner == null) {
                log.warn("Rejected inbound cluster message on subject {} — missing/invalid HMAC tag", subject);
                return;
            }
            try {
                ClusterEnvelope envelope = codec.decode(inner);
                if (envelope != null) {
                    listener.onMessage(envelope);
                }
            } catch (Exception e) {
                log.warn("Failed to decode cluster envelope on subject {}", subject, e);
            }
        }
    }

    @Override
    public void setTransportStateListener(TransportStateListener listener) {
        this.transportStateListener = listener;
    }

    @Override
    public ClusterSubscription subscribe(String uri, ClusterMessageListener listener) {
        String subject = BROADCAST_PREFIX + subjectToken(uri);
        channelListeners.put(subject, listener);
        dispatcher.subscribe(subject);
        log.debug("Subscribed to broadcast subject {}", subject);
        return createSubscription(subject);
    }

    @Override
    public ClusterSubscription subscribeUnicast(String nodeId, ClusterMessageListener listener) {
        String subject = UNICAST_PREFIX + subjectToken(nodeId);
        channelListeners.put(subject, listener);
        dispatcher.subscribe(subject);
        log.debug("Subscribed to unicast subject {}", subject);
        return createSubscription(subject);
    }

    @Override
    public BrokerState state() {
        return state.get();
    }

    @Override
    public void shutdown() {
        state.set(BrokerState.SHUTDOWN);
        Connection c = connection;
        if (c != null) {
            try {
                c.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Error closing NATS connection", e);
            }
        }
        channelListeners.clear();
        log.info("NatsClusterBroker shut down");
    }

    private ClusterSubscription createSubscription(String subject) {
        AtomicBoolean active = new AtomicBoolean(true);
        return new ClusterSubscription() {
            @Override
            public void unsubscribe() {
                if (active.compareAndSet(true, false)) {
                    channelListeners.remove(subject);
                    try { dispatcher.unsubscribe(subject); }
                    catch (Exception e) { log.debug("Unsubscribe from {} failed", subject); }
                }
            }

            @Override
            public boolean isActive() {
                return active.get();
            }
        };
    }

    private void checkActive() {
        BrokerState s = state.get();
        if (s != BrokerState.ACTIVE) {
            throw new ClusterBrokerException("Broker is not active: " + s);
        }
    }
}
```
- [ ] **Step 2: Compile** — `./mvnw -B -ntp -pl netty-spring-websocket-cluster -am -DskipTests compile` → BUILD SUCCESS. If an `io.nats.client` symbol doesn't resolve, fix it against the jar (per the verification mandate) — e.g. confirm `createDispatcher(MessageHandler)`, `Dispatcher.subscribe(String)`, `ConnectionListener.Events` names.
- [ ] **Step 3: Commit**
```bash
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/nats/NatsClusterBroker.java
git commit -m "feat(cluster): NatsClusterBroker (NATS core pub/sub ClusterBroker)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: `ClusterProperties.Nats.servers`

**Files:** Modify `netty-spring-websocket-cluster/.../ClusterProperties.java`

- [ ] **Step 1: Field + accessors.** Near the `Auth auth = new Auth();` field add `private Nats nats = new Nats();`, and near the `getAuth()/setAuth()` accessors add:
```java
    public Nats getNats() { return nats; }
    public void setNats(Nats nats) { this.nats = nats; }
```
- [ ] **Step 2: Nested class.** Near the `Auth`/`Reliable` nested static classes add:
```java
    /**
     * NATS broker (ADR-001 scaling tier) settings. When {@code servers} is non-empty, the
     * {@code NatsClusterBroker} replaces the Redis Pub/Sub broker (transport only — SessionRegistry and
     * heartbeat stay on Redis). Empty/absent (default) = the Redis broker. Requires {@code io.nats:jnats}
     * on the classpath.
     */
    public static class Nats {
        /** Comma-separated NATS server URLs ({@code nats://host:port,...}). Default empty. */
        private String servers;

        public String getServers() { return servers; }
        public void setServers(String servers) { this.servers = servers; }
    }
```
- [ ] **Step 3: Compile** — `./mvnw -B -ntp -pl netty-spring-websocket-cluster -am -DskipTests compile` → BUILD SUCCESS.
- [ ] **Step 4: Commit** (`feat(cluster): nats.servers config property`).

---

## Task 4: Auto-config — NATS broker bean + 3-way selection

**Files:** Modify `netty-websocket-cluster-spring-boot-starter/.../NettyWebSocketClusterConfigure.java`

- [ ] **Step 1: SpEL constants.** After the existing `STANDALONE_TRANSPORT`/`CLUSTER_TRANSPORT` constants add:
```java
    /** SpEL: nats.servers is empty/absent → use a Redis broker. */
    static final String NO_NATS_TRANSPORT =
            "'${server.netty.websocket.cluster.nats.servers:}'.trim().isEmpty()";
    /** SpEL: nats.servers is non-empty → use the NATS broker. */
    static final String NATS_TRANSPORT =
            "!('${server.netty.websocket.cluster.nats.servers:}'.trim().isEmpty())";
    /** Standalone Redis broker: cluster-nodes empty AND nats.servers empty. */
    static final String STANDALONE_REDIS_BROKER = STANDALONE_TRANSPORT + " and " + NO_NATS_TRANSPORT;
    /** Cluster Redis broker: cluster-nodes set AND nats.servers empty. */
    static final String CLUSTER_REDIS_BROKER = CLUSTER_TRANSPORT + " and " + NO_NATS_TRANSPORT;
```
- [ ] **Step 2: Re-gate the two Redis broker beans.** Change `clusterBroker`'s `@ConditionalOnExpression(STANDALONE_TRANSPORT)` to `@ConditionalOnExpression(STANDALONE_REDIS_BROKER)`, and `clusterBrokerCluster`'s `@ConditionalOnExpression(CLUSTER_TRANSPORT)` to `@ConditionalOnExpression(CLUSTER_REDIS_BROKER)`. (Leave their `@ConditionalOnMissingBean(ClusterBroker.class)` and bodies unchanged. Do NOT touch the standalone Redis registry/heartbeat/reaper beans — they stay `STANDALONE_TRANSPORT`/`CLUSTER_TRANSPORT` and remain active in NATS mode for the registry.)
- [ ] **Step 3: Add the NATS broker bean.** Next to the other broker beans add:
```java
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnClass(io.nats.client.Connection.class)
    @ConditionalOnExpression(NATS_TRANSPORT)
    @ConditionalOnMissingBean(ClusterBroker.class)
    public ClusterBroker clusterBrokerNats(ClusterProperties properties, EnvelopeCodec envelopeCodec,
            MessageAuthenticator messageAuthenticator) throws Exception {
        com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsClusterBroker broker =
                new com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsClusterBroker(
                        envelopeCodec, messageAuthenticator);
        // 2-phase init: build the NATS connection with the broker's ConnectionListener (set at build time),
        // then attach it. The broker owns + closes the connection (destroyMethod="shutdown").
        io.nats.client.Connection connection = io.nats.client.Nats.connect(io.nats.client.Options.builder()
                .server(properties.getNats().getServers())
                .connectionListener(broker::onConnectionEvent)
                .maxReconnects(-1)
                .build());
        broker.attach(connection);
        int maxOut = properties.getMessageMaxSizeBytes();
        broker.setInboundMaxBytes(maxOut > 0 ? (int) Math.min((long) maxOut * 2L, Integer.MAX_VALUE) : 0);
        log.info("Cluster broker = NATS ({}) — registry/heartbeat remain on Redis (ADR-001 mixed model)",
                properties.getNats().getServers());
        return broker;
    }
```
(`@ConditionalOnClass` is already imported. `io.nats.*` is fully-qualified to avoid import churn.)
- [ ] **Step 4: Compile** — `./mvnw -B -ntp -pl netty-websocket-cluster-spring-boot-starter -am -DskipTests compile` → BUILD SUCCESS.
- [ ] **Step 5: Commit** (`feat(cluster): nats.servers selects the NATS broker (registry stays Redis)`).

---

## Task 5: Unit test (Mockito, no live NATS)

**Files:** Create `netty-spring-websocket-cluster/src/test/java/.../cluster/nats/NatsClusterBrokerTest.java`

- [ ] **Step 1: Write the test** (mirror the cluster module's Mockito conventions — JUnit 5, no AssertJ):
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.nats;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterMessageListener;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.MessageHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NatsClusterBrokerTest {

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static ClusterEnvelope env(String origin, String uri) {
        return new ClusterEnvelope(origin, uri, ClusterEnvelope.MessageKind.BROADCAST,
                "T:hi".getBytes(StandardCharsets.UTF_8), null, null, System.currentTimeMillis());
    }

    @Test
    void publishesToBase64UrlBroadcastSubject_andStateActive() {
        Connection conn = mock(Connection.class);
        Dispatcher dispatcher = mock(Dispatcher.class);
        when(conn.createDispatcher(any())).thenReturn(dispatcher);

        NatsClusterBroker broker = new NatsClusterBroker(new SimpleTextEnvelopeCodec(), new NoOpMessageAuthenticator());
        broker.attach(conn);

        assertEquals(BrokerState.ACTIVE, broker.state());
        broker.publish("/ws/x", env("node-A", "/ws/x"));
        verify(conn).publish(eq("netty.broadcast." + b64("/ws/x")), any(byte[].class));
    }

    @Test
    void subscribeRoutesToBroadcastSubject() {
        Connection conn = mock(Connection.class);
        Dispatcher dispatcher = mock(Dispatcher.class);
        when(conn.createDispatcher(any())).thenReturn(dispatcher);

        NatsClusterBroker broker = new NatsClusterBroker(new SimpleTextEnvelopeCodec());
        broker.attach(conn);
        broker.subscribe("/ws/x", e -> { });
        verify(dispatcher).subscribe("netty.broadcast." + b64("/ws/x"));
    }

    @Test
    void inboundMessageRoutesToTheRegisteredListener() throws Exception {
        Connection conn = mock(Connection.class);
        Dispatcher dispatcher = mock(Dispatcher.class);
        when(conn.createDispatcher(any())).thenReturn(dispatcher);

        NatsClusterBroker broker = new NatsClusterBroker(new SimpleTextEnvelopeCodec());
        // capture the dispatcher MessageHandler so we can feed it a message
        ArgumentCaptor<MessageHandler> handlerCap = ArgumentCaptor.forClass(MessageHandler.class);
        broker.attach(conn);
        verify(conn).createDispatcher(handlerCap.capture());

        List<ClusterEnvelope> got = new CopyOnWriteArrayList<>();
        broker.subscribe("/ws/x", got::add);

        String subject = "netty.broadcast." + b64("/ws/x");
        byte[] payload = new SimpleTextEnvelopeCodec().encode(env("node-A", "/ws/x")).getBytes(StandardCharsets.UTF_8);
        Message msg = mock(Message.class);
        when(msg.getSubject()).thenReturn(subject);
        when(msg.getData()).thenReturn(payload);
        handlerCap.getValue().onMessage(msg);

        assertEquals(1, got.size());
        assertEquals("node-A", got.get(0).getOriginNodeId());
    }

    @Test
    void connectionEventsDriveBrokerState() {
        Connection conn = mock(Connection.class);
        when(conn.createDispatcher(any())).thenReturn(mock(Dispatcher.class));
        NatsClusterBroker broker = new NatsClusterBroker(new SimpleTextEnvelopeCodec());
        broker.attach(conn);

        broker.onConnectionEvent(conn, ConnectionListener.Events.DISCONNECTED);
        assertEquals(BrokerState.DEGRADED, broker.state());
        broker.onConnectionEvent(conn, ConnectionListener.Events.RECONNECTED);
        assertEquals(BrokerState.ACTIVE, broker.state());
    }
}
```
- [ ] **Step 2: Run** — `./mvnw -B -ntp -pl netty-spring-websocket-cluster -am test -Dtest=NatsClusterBrokerTest -Dsurefire.failIfNoSpecifiedTests=false` → 4 tests pass. (If `SimpleTextEnvelopeCodec.encode` / `ClusterEnvelope` shapes differ, adjust to the real types — see RedisIntegrationTest for the `ClusterEnvelope` ctor and `SimpleTextEnvelopeCodec` usage.)
- [ ] **Step 3: Commit** (`test(cluster): NatsClusterBroker subject/routing/state unit test`).

---

## Task 6: Integration test + NATS resolver (the oracle)

**Files:** Create `…cluster/ClusterTestNats.java`, `…cluster/ClusterTestNatsSelfTest.java`, `…cluster/NatsIntegrationTest.java`

- [ ] **Step 1: `ClusterTestNats` resolver** (mirror `ClusterTestRedisCluster` — INCLUDING the `api.version` static initializer):
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Resolves a usable NATS server for the NATS broker integration tests, once per JVM:
 *   env CLUSTER_TEST_NATS_URL → a Testcontainers nats:2.10 → none (tests skip).
 */
public final class ClusterTestNats {

    static {
        // Same docker-java API-probe workaround as ClusterTestRedisCluster (modern Docker rejects the
        // hardcoded /v1.32 probe). Pin a supported negotiated API version before the first Docker call.
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", "1.43");
        }
    }

    private static volatile boolean resolved;
    private static volatile String url;
    @SuppressWarnings("resource")
    private static GenericContainer<?> container; // singleton; reaped by Ryuk at JVM exit

    private ClusterTestNats() {
    }

    public static synchronized boolean available() {
        resolve();
        return url != null;
    }

    public static synchronized String url() {
        resolve();
        if (url == null) {
            throw new IllegalStateException("No NATS server available (no env and no Docker)");
        }
        return url;
    }

    /** A fresh NATS connection to the resolved server (caller closes it). */
    public static Connection newConnection() throws Exception {
        return Nats.connect(Options.builder().server(url()).build());
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        String env = System.getenv("CLUSTER_TEST_NATS_URL");
        if (env != null && !env.isBlank()) {
            url = env.trim();
            return;
        }
        if (!dockerAvailable()) {
            return;
        }
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse("nats:2.10"))
                .withExposedPorts(4222)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));
        c.start();
        container = c;
        url = "nats://" + c.getHost() + ":" + c.getMappedPort(4222);
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
```
- [ ] **Step 2: `ClusterTestNatsSelfTest`** (proves the harness):
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import io.nats.client.Connection;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ClusterTestNatsSelfTest {
    @Test
    void connectsToNats() throws Exception {
        Assumptions.assumeTrue(ClusterTestNats.available(), "no NATS and no Docker");
        try (Connection conn = ClusterTestNats.newConnection()) {
            assertNotNull(conn.getStatus());
            conn.flush(java.time.Duration.ofSeconds(2));
        }
    }
}
```
- [ ] **Step 3: Run the self-test** — `./mvnw -B -ntp -pl netty-spring-websocket-cluster -am test -Dtest=ClusterTestNatsSelfTest -Dsurefire.failIfNoSpecifiedTests=false` → 1 PASS, **NOT skipped** (Docker live). Fix the resolver/API until the NATS container comes up + connects.
- [ ] **Step 4: `NatsIntegrationTest`** (the headline oracle — broadcast + unicast publish→receive on a real NATS):
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsClusterBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NatsIntegrationTest {

    private static boolean natsAvailable;

    @BeforeAll
    static void up() {
        natsAvailable = ClusterTestNats.available();
    }

    private static NatsClusterBroker broker() throws Exception {
        NatsClusterBroker b = new NatsClusterBroker(new SimpleTextEnvelopeCodec(), new NoOpMessageAuthenticator());
        Connection c = Nats.connect(Options.builder().server(ClusterTestNats.url()).build());
        b.attach(c);
        return b;
    }

    @Test
    void broadcastPublishReachesSubscriber() throws Exception {
        Assumptions.assumeTrue(natsAvailable, "no NATS");
        NatsClusterBroker a = broker();
        NatsClusterBroker b = broker();
        try {
            List<ClusterEnvelope> got = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            b.subscribe("/ws/nbc", e -> { got.add(e); latch.countDown(); });
            Thread.sleep(300); // subscription propagation
            a.publish("/ws/nbc", new ClusterEnvelope("node-A", "/ws/nbc", ClusterEnvelope.MessageKind.BROADCAST,
                    "T:hello".getBytes(StandardCharsets.UTF_8), null, null, System.currentTimeMillis()));
            assertTrue(latch.await(6, TimeUnit.SECONDS), "subscriber must receive the NATS broadcast");
            assertEquals(1, got.size());
            assertEquals("node-A", got.get(0).getOriginNodeId());
        } finally {
            a.shutdown();
            b.shutdown();
        }
    }

    @Test
    void unicastPublishReachesTargetNode() throws Exception {
        Assumptions.assumeTrue(natsAvailable, "no NATS");
        NatsClusterBroker a = broker();
        NatsClusterBroker b = broker();
        try {
            List<ClusterEnvelope> got = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            b.subscribeUnicast("node-B", e -> { got.add(e); latch.countDown(); });
            Thread.sleep(300);
            a.unicast("node-B", new ClusterEnvelope("node-A", "/ws/nbc", ClusterEnvelope.MessageKind.UNICAST,
                    "T:dm".getBytes(StandardCharsets.UTF_8), "sess-1", null, System.currentTimeMillis()));
            assertTrue(latch.await(6, TimeUnit.SECONDS), "target node must receive the NATS unicast");
            assertEquals(1, got.size());
            assertEquals("sess-1", got.get(0).getTargetSessionId());
        } finally {
            a.shutdown();
            b.shutdown();
        }
    }
}
```
- [ ] **Step 5: Run** — `./mvnw -B -ntp -pl netty-spring-websocket-cluster -am test -Dtest=ClusterTestNatsSelfTest,NatsClusterBrokerTest,NatsIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` → all pass, the ITs **NOT skipped**. **If `broadcastPublishReachesSubscriber`/`unicastPublishReachesTargetNode` don't receive, this is the jnats API/subject/dispatcher issue — fix per the verification mandate (e.g. confirm `createDispatcher`/`subscribe` semantics, that `publish` flushes, that the subject encoding matches) until they receive. Do NOT weaken the asserts.**
- [ ] **Step 6: Commit** (`test(cluster): NATS broker resolver + real-NATS publish/receive IT (the oracle)`).

---

## Task 7: Context test + metadata + docs

- [ ] **Step 1: Duplicate the resolver into the starter test sources.** Create `netty-websocket-cluster-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/boot/configure/ClusterTestNats.java` — IDENTICAL to the cluster-module `ClusterTestNats` from Task 6 Step 1 except the `package` line = `com.github.berrywang1996.netty.spring.boot.configure;`. (Mirrors the `ClusterTestRedis`/`ClusterTestRedisCluster` cross-module duplication; the starter already has testcontainers test-scope, and jnats is optional-compile so it's present at test time.)
- [ ] **Step 2: Context selection test.** In `…boot/configure/NettyWebSocketClusterConfigureTest.java` add (the starter already has a `ClusterTestRedis` for the Redis registry):
```java
    @Test
    void natsServersSet_usesNatsBroker_redisRegistryStays() {
        Assumptions.assumeTrue(redisAvailable, "Redis needed for the registry");
        Assumptions.assumeTrue(ClusterTestNats.available(), "no NATS (no env + no Docker)");
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.nats.servers=" + ClusterTestNats.url(),
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-nats-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // broker is NATS:
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsClusterBroker.class);
                    // registry stays Redis (the mixed model):
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisSessionRegistry.class);
                    assertThat(context.getBean(MessageSender.class)).isInstanceOf(ClusterMessageSender.class);
                });
    }
```
- [ ] **Step 3: metadata.** In `netty-websocket-cluster-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json`, add (after the `command-timeout-ms` / `pubsub-connections` entries):
```json
    {
      "name": "server.netty.websocket.cluster.nats.servers",
      "type": "java.lang.String",
      "description": "Comma-separated NATS server URLs (nats://host:port,...). When non-empty, the NatsClusterBroker replaces the Redis Pub/Sub broker (transport only; SessionRegistry and heartbeat stay on Redis). Requires io.nats:jnats on the classpath. Empty (default) = Redis broker."
    },
```
- [ ] **Step 4: Docs** (Edit tool only; U+FFFD scan after). `docs/cluster-design.md`: flip the row `| NATS broker（ADR-001 规模化档位） | ⏳ 1.9.x | SPI 下新增实现，非替换 |` to `| NATS broker（ADR-001 规模化档位） | ✅ 1.9.0 RC9（传输层） | NatsClusterBroker（core pub/sub，at-most-once）；由 nats.servers 选择；**仅传输层**，registry/心跳仍在 Redis（混合部署）；JetStream 可靠投递 → 后续 |`. `docs/api-guide.md`: add a §9 config row `| `cluster.nats.servers` | （空） | 逗号分隔 NATS 服务器（nats://host:port,...）；非空时用 NatsClusterBroker 替换 Redis broker（仅传输层，registry 仍在 Redis）；需 io.nats:jnats。 |` (match the table's language).
- [ ] **Step 5: Run** — `./mvnw -B -ntp -pl netty-websocket-cluster-spring-boot-starter -am test -Dtest=NettyWebSocketClusterConfigureTest -Dsurefire.failIfNoSpecifiedTests=false` → passes; the new test runs (NOT skipped if Redis + Docker/NATS available). U+FFFD scan on both docs = 0.
- [ ] **Step 6: Commit** (`feat(cluster): NATS selection context test + metadata + docs`).

---

## Task 8: Full test + cut v1.9.0-RC9

- [ ] **Step 1: Full reactor** — `./mvnw -B -ntp test` (Docker live → Testcontainers NATS + Redis) → BUILD SUCCESS, 11 modules; `NatsIntegrationTest` + `NatsClusterBrokerTest` + the context test run, ITs **NOT skipped**. Capture the total test count. STOP if anything fails or the NATS ITs unexpectedly skip.
- [ ] **Step 2: Release-notes RC9 section.** In `docs/release-notes-1.9.0.md` (Edit tool, UTF-8): add a `### ⑭ NATS broker（ADR-001 规模化档位 / scaling tier）` section after the ⑬ section — covering: `NatsClusterBroker` over the ClusterBroker SPI (NATS core pub/sub, **at-most-once**), selected by `nats.servers`; **transport only** — registry/heartbeat stay Redis (混合 NATS+Redis 部署，per ADR-001); base64url subjects; `io.nats:jnats` optional (设 nats.servers 需 jnats 在 classpath); JetStream reliable → 后续. Update the status line `RC8`→`RC9` + clause; bump the test-count number to the Step-1 total; add an RC9 test bullet (`NatsClusterBrokerTest` + `ClusterTestNatsSelfTest` + `NatsIntegrationTest` broadcast/unicast + the selection context test). Verify U+FFFD = 0.
- [ ] **Step 3: Bump poms** — `for f in $(grep -rl "1.9.0-RC8" --include=pom.xml .); do sed -i 's|<version>1.9.0-RC8</version>|<version>1.9.0-RC9</version>|g' "$f"; done`; verify `grep -rl "1.9.0-RC8" --include=pom.xml . | wc -l` = 0 and `grep -rl "1.9.0-RC9" --include=pom.xml . | wc -l` = 11.
- [ ] **Step 4: Re-test** — `./mvnw -B -ntp -q test` → BUILD SUCCESS.
- [ ] **Step 5: Commit + tag**
```bash
git add -A
git commit -m "release: 1.9.0-RC9 - NATS broker (ADR-001 scaling tier)

NatsClusterBroker (NATS core pub/sub, at-most-once) over the ClusterBroker SPI, selected
by nats.servers; registry/heartbeat stay Redis (ADR-001 mixed model). Additive/opt-in
(io.nats:jnats optional). Part of the 1.9.0 cycle.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git tag -a v1.9.0-RC9 -m "v1.9.0-RC9 - NATS broker (1.9.0 cycle, in development)"
```
(Note: `git add -A` is acceptable here only if `git status` shows just poms + release-notes; otherwise stage explicit paths.)
- [ ] **Step 6: Finish the branch.** Use **superpowers:finishing-a-development-branch**: FF-merge `feature/1.9.x-nats-broker` into `master`, keep the `v1.9.0-RC9` tag, delete the branch, **NO push, NO deploy**.

---

## Notes for the implementer
- **The `NatsIntegrationTest` is the verification of the jnats API.** A failing/skipped NATS IT is a blocker to investigate, not to weaken. (Docker must be up for the `nats:2.10` container.)
- **Mixed model:** a NATS deployment still needs Redis for the registry/heartbeat (unicast routing). The auto-config leaves the Redis registry/heartbeat/reaper beans active; only the broker bean changes. Don't gate those off.
- **3-way selection is order-independent** (SpEL on `nats.servers`/`cluster-nodes` presence). Don't introduce `@ConditionalOnMissingBean`-ordering dependencies.
- **Don't touch** `RedisPubSubBroker`, `RedisClusterModePubSubBroker`, or the registry/heartbeat impls.
