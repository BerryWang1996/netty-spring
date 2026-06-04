# W3C TraceContext Cross-Node Propagation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Populate the (already wire-carried) `ClusterEnvelope.traceparent` on every cross-node send and restore it into SLF4J MDC on receive, so a distributed trace's `traceId`/`spanId` correlate across nodes in logs. Opt-in, tracer-agnostic, additive.

**Architecture:** A new `ClusterTraceContext` SPI (default `MdcClusterTraceContext`, MDC-based, zero-dep) is read on send (3 envelope builders) and applied on receive (3 listener delivery blocks wrapped in a try-with-resources `Scope`). Gated by `server.netty.websocket.cluster.trace-propagation.enable` (default false). No wire-format change (the codec already encodes `traceparent`). Micrometer Observation/active-span continuation is a 2.0.0 (Boot 3.x) follow-up — Boot 2.7 ships Micrometer 1.9 (no Observation API).

**Tech Stack:** Java 17, SLF4J MDC, Spring Boot 2.7.18 auto-config, Lettuce/Redis, JUnit 5. Spec: `docs/superpowers/specs/2026-06-04-w3c-tracecontext-propagation-design.md`. Develops on `1.9.0-RC5`.

---

## Environment notes for every task
- Repo: `C:\Users\qq951\IdeaProjects\netty-spring`; Windows (PowerShell + Bash); Maven 3.9.9; Java 17. Redis live on `localhost:16379`; Docker live.
- Git: branch `feature/1.9.0-tracecontext` (Task 0). Do NOT push/deploy.
- For `mvn -pl <module> -am -Dtest=<name>` ALWAYS add `-Dsurefire.failIfNoSpecifiedTests=false`.
- Docs edits: Edit tool only; verify UTF-8 + U+FFFD after.
- Match on quoted code, not line numbers.

## Confirmed facts (verified — do not re-derive)
- `ClusterEnvelope` (`…cluster.spi`) has `traceparent` (field + `getTraceparent()`), a 7-arg ctor `(originNodeId, uri, kind, payload, targetSessionId, traceparent, timestamp)` and an 8-arg version ctor. **No change needed.**
- `SimpleTextEnvelopeCodec` already encodes/decodes `traceparent` as field 5 of 8. **No change needed.**
- `MdcUtil` (`netty-spring-web/src/main/java/com/github/berrywang1996/netty/spring/web/util/MdcUtil.java`) has `KEY_REQUEST_ID`/`KEY_SESSION_ID`/`KEY_URI`/`KEY_REMOTE_ADDR` + `clear()`. The cluster module sees it transitively (cluster → websocket → web).
- `ClusterMessageSender` (`…cluster`): `private volatile ReliableBroker reliableBroker;` (~L98); `public void setReliableBroker(...)` (~L158); builders `buildBroadcastEnvelope` (~L657) and `buildUnicastEnvelope` (~L665) pass `null` as the 6th (traceparent) arg; a CLOSE envelope is built inline (~L399-404) also with `null` traceparent; receive listeners `onBroadcastMessage` (~L441), `onUnicastMessage` (~L459), `onReliableMessage` (~L580) each do origin-suppression then a `try { deserialize…; localSender.X } catch {…}` delivery block.
- `ClusterProperties` (`…cluster`): nested static classes `Reliable` + `Auth` (each `private boolean enable=false;` + `isEnable`/`setEnable`); fields `private Reliable reliable = new Reliable();` / `private Auth auth = new Auth();` + getters/setters. Bound `@ConfigurationProperties(prefix="server.netty.websocket.cluster")` by the `clusterProperties()` `@Bean` in `NettyWebSocketClusterConfigure`.
- `NettyWebSocketClusterConfigure`: the `clusterMessageSender` `@Bean` (~L267) takes `@Autowired(required=false) ReliableBroker reliableBroker` and calls `sender.setReliableBroker(...)` — mirror this for trace context. `@ConditionalOnProperty` gating pattern used for the `reliableBroker` bean (~L207).

## File Structure
- New: `…cluster/spi/ClusterTraceContext.java` (SPI); `…cluster/MdcClusterTraceContext.java` (default impl); tests `MdcClusterTraceContextTest`, `ClusterTraceIntegrationTest`.
- Modified: `MdcUtil.java` (constant only); `ClusterMessageSender.java` (field/setter/helpers/3 builders/3 listeners); `ClusterProperties.java` (TracePropagation block); `NettyWebSocketClusterConfigure.java` (gated bean + inject); `NettyWebSocketClusterConfigureTest.java` (context test).
- Docs + poms in the last task.

---

## Task 0: Branch setup
- [ ] **Step 1:** `git checkout -b feature/1.9.0-tracecontext` (from `master` at RC5). Confirm `git branch --show-current`.

---

## Task 1: `ClusterTraceContext` SPI + `MdcClusterTraceContext` default + `MdcUtil` constant

**Files:** create `ClusterTraceContext.java`, `MdcClusterTraceContext.java`, `MdcClusterTraceContextTest.java`; modify `MdcUtil.java`.

- [ ] **Step 1: Add the MdcUtil constant**
In `MdcUtil.java`, after the `KEY_REMOTE_ADDR` constant, add:
```java
    /** MDC key for the W3C traceparent restored on a cross-node delivery (cluster mode). */
    public static final String KEY_TRACEPARENT = "netty.traceparent";
```
Also add a `<li>` to the class Javadoc "MDC Keys" list:
```java
 *   <li>{@code netty.traceparent} – W3C traceparent restored during cross-node cluster delivery</li>
```
Do **NOT** change `clear()` (it must not touch tracer-managed `traceId`/`spanId`).

- [ ] **Step 2: Create the SPI**
Create `netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/spi/ClusterTraceContext.java` (Apache header copied from a sibling in that package, e.g. `ClusterEnvelope.java`), then:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.spi;

/**
 * SPI for propagating a W3C {@code traceparent} across cluster nodes so a distributed trace
 * correlates in logs on the receiving node. Tracer-agnostic: the default
 * {@code MdcClusterTraceContext} is MDC-based and zero-dependency; integrators can supply a
 * bean that reads/writes their tracer (Sleuth/Brave) directly.
 *
 * <p>Wired only when {@code server.netty.websocket.cluster.trace-propagation.enable=true}.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
public interface ClusterTraceContext {

    /** The current thread's W3C traceparent (e.g. from MDC / the active span), or null if none. */
    String currentTraceparent();

    /**
     * Restore a traceparent into the ambient context (e.g. MDC) for a cross-node delivery.
     * Returns a {@link Scope} that reverts the restoration on close. A null/blank/malformed
     * value yields {@link #NOOP}.
     */
    Scope restore(String traceparent);

    /** Closeable that reverts what {@link #restore} set. {@code close()} never throws. */
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /** No-op scope (trace propagation disabled or nothing to restore). */
    Scope NOOP = () -> { };
}
```

- [ ] **Step 3: Write the failing unit test**
Create `netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/MdcClusterTraceContextTest.java`:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterTraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

class MdcClusterTraceContextTest {

    private final ClusterTraceContext tc = new MdcClusterTraceContext();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void currentTraceparent_prefersExplicitMdcTraceparent() {
        MDC.put("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        assertEquals("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", tc.currentTraceparent());
    }

    @Test
    void currentTraceparent_synthesizesFrom32HexTraceIdAndSpanId() {
        MDC.put("traceId", "0af7651916cd43dd8448eb211c80319c");
        MDC.put("spanId", "b7ad6b7169203331");
        assertEquals("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", tc.currentTraceparent());
    }

    @Test
    void currentTraceparent_leftPads64BitTraceId() {
        MDC.put("traceId", "8448eb211c80319c");   // 16 hex (64-bit)
        MDC.put("spanId", "b7ad6b7169203331");
        assertEquals("00-00000000000000008448eb211c80319c-b7ad6b7169203331-01", tc.currentTraceparent());
    }

    @Test
    void currentTraceparent_nullWhenAbsentOrMalformed() {
        assertNull(tc.currentTraceparent());
        MDC.put("traceId", "not-hex-xxxx");
        MDC.put("spanId", "b7ad6b7169203331");
        assertNull(tc.currentTraceparent());
    }

    @Test
    void restore_putsTraceKeys_andScopeClearsThem() {
        String tp = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        try (ClusterTraceContext.Scope s = tc.restore(tp)) {
            assertEquals("0af7651916cd43dd8448eb211c80319c", MDC.get("traceId"));
            assertEquals("b7ad6b7169203331", MDC.get("spanId"));
            assertEquals(tp, MDC.get("netty.traceparent"));
        }
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("spanId"));
        assertNull(MDC.get("netty.traceparent"));
    }

    @Test
    void restore_nullOrMalformed_isNoop() {
        assertSame(ClusterTraceContext.NOOP, tc.restore(null));
        assertSame(ClusterTraceContext.NOOP, tc.restore("garbage"));
        assertSame(ClusterTraceContext.NOOP, tc.restore("00-tooShort-x-01"));
        assertNull(MDC.get("traceId"));
    }
}
```

- [ ] **Step 4: Run — verify it fails to compile** (`MdcClusterTraceContext` missing)
`mvn -q -pl netty-spring-websocket-cluster -am test -Dtest=MdcClusterTraceContextTest -Dsurefire.failIfNoSpecifiedTests=false` → compile failure.

- [ ] **Step 5: Create the default impl**
Create `netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/MdcClusterTraceContext.java` (Apache header), then:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.util.MdcUtil;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterTraceContext;
import org.slf4j.MDC;

/**
 * Zero-dependency, MDC-based {@link ClusterTraceContext}. Works with any tracer that writes the
 * conventional {@code traceId}/{@code spanId} MDC keys (Sleuth, Brave). On send it reads an explicit
 * {@code traceparent} MDC key, else synthesizes a W3C value from {@code traceId}+{@code spanId}; on
 * receive it parses the traceparent back into {@code traceId}/{@code spanId} (so existing
 * {@code %X{traceId}} log patterns light up) plus {@code netty.traceparent}.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
public class MdcClusterTraceContext implements ClusterTraceContext {

    private static final String TRACEPARENT = "traceparent";
    private static final String TRACE_ID = "traceId";
    private static final String SPAN_ID = "spanId";
    private static final String ZERO_PAD_16 = "0000000000000000"; // 16 hex zeros

    @Override
    public String currentTraceparent() {
        String explicit = MDC.get(TRACEPARENT);
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        return synthesize(MDC.get(TRACE_ID), MDC.get(SPAN_ID));
    }

    /** Build a W3C {@code 00-{trace32}-{span16}-01} from MDC trace/span ids, or null if unusable. */
    static String synthesize(String traceId, String spanId) {
        if (traceId == null || spanId == null) {
            return null;
        }
        String span = spanId.trim().toLowerCase();
        String trace = traceId.trim().toLowerCase();
        if (span.length() != 16 || !isHex(span)) {
            return null;
        }
        if (trace.length() == 16) {
            trace = ZERO_PAD_16 + trace;
        }
        if (trace.length() != 32 || !isHex(trace)) {
            return null;
        }
        return "00-" + trace + "-" + span + "-01";
    }

    @Override
    public Scope restore(String traceparent) {
        if (traceparent == null) {
            return NOOP;
        }
        String tp = traceparent.trim();
        String[] p = tp.split("-");
        if (p.length != 4 || p[1].length() != 32 || p[2].length() != 16 || !isHex(p[1]) || !isHex(p[2])) {
            return NOOP;
        }
        MDC.put(TRACE_ID, p[1]);
        MDC.put(SPAN_ID, p[2]);
        MDC.put(MdcUtil.KEY_TRACEPARENT, tp);
        return () -> {
            MDC.remove(TRACE_ID);
            MDC.remove(SPAN_ID);
            MDC.remove(MdcUtil.KEY_TRACEPARENT);
        };
    }

    private static boolean isHex(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}
```

- [ ] **Step 6: Run — verify pass** (6 tests)
`mvn -q -pl netty-spring-websocket-cluster -am test -Dtest=MdcClusterTraceContextTest -Dsurefire.failIfNoSpecifiedTests=false` → BUILD SUCCESS.

- [ ] **Step 7: Commit**
```
git add netty-spring-web/src/main/java/com/github/berrywang1996/netty/spring/web/util/MdcUtil.java netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/spi/ClusterTraceContext.java netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/MdcClusterTraceContext.java netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/MdcClusterTraceContextTest.java
git commit -m "feat(cluster): ClusterTraceContext SPI + MdcClusterTraceContext (W3C traceparent <-> MDC)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Wire trace context into `ClusterMessageSender`

**Files:** `ClusterMessageSender.java`.

- [ ] **Step 1: Add the field + setter + helpers**
Add an import near the other `…cluster.spi` imports:
```java
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterTraceContext;
```
After the `private volatile ReliableBroker reliableBroker;` field, add:
```java
    /** Optional W3C trace propagation (null = disabled). */
    private volatile ClusterTraceContext traceContext;
```
After the `setReliableBroker(...)` method, add:
```java
    /** Inject the W3C trace context (cross-node traceparent propagation). Null disables it. */
    public void setTraceContext(ClusterTraceContext traceContext) {
        this.traceContext = traceContext;
    }

    /** Current traceparent for an outgoing envelope, or null when propagation is off. */
    private String currentTraceparent() {
        ClusterTraceContext tc = this.traceContext;
        return tc != null ? tc.currentTraceparent() : null;
    }

    /** Restore scope for an incoming envelope's traceparent, or NOOP when propagation is off. */
    private ClusterTraceContext.Scope traceScope(ClusterEnvelope envelope) {
        ClusterTraceContext tc = this.traceContext;
        return tc != null ? tc.restore(envelope.getTraceparent()) : ClusterTraceContext.NOOP;
    }
```

- [ ] **Step 2: Inject traceparent on send (3 builders)**
In `buildBroadcastEnvelope`, change the args `null, null, System.currentTimeMillis()` to `null, currentTraceparent(), System.currentTimeMillis()`:
```java
    private ClusterEnvelope buildBroadcastEnvelope(String uri, AbstractMessage message) {
        return new ClusterEnvelope(
                nodeManager.getNodeId(), uri,
                ClusterEnvelope.MessageKind.BROADCAST,
                serializePayload(message),
                null, currentTraceparent(), System.currentTimeMillis());
    }
```
In `buildUnicastEnvelope`, change `sessionId, null, System.currentTimeMillis()` to `sessionId, currentTraceparent(), System.currentTimeMillis()`:
```java
    private ClusterEnvelope buildUnicastEnvelope(String uri, String sessionId, AbstractMessage message) {
        return new ClusterEnvelope(
                nodeManager.getNodeId(), uri,
                ClusterEnvelope.MessageKind.UNICAST,
                serializePayload(message),
                sessionId, currentTraceparent(), System.currentTimeMillis());
    }
```
In the inline CLOSE-envelope construction (~L399-404), change its 6th constructor arg (the `traceparent`, currently `null`) to `currentTraceparent()`. (Read the exact `new ClusterEnvelope(...)` there; only that one arg changes.)

- [ ] **Step 3: Restore traceparent on receive (3 listeners)**
`onBroadcastMessage` — wrap the delivery `try` block as try-with-resources:
```java
    private void onBroadcastMessage(ClusterEnvelope envelope) {
        if (nodeManager.getNodeId().equals(envelope.getOriginNodeId())) {
            clusterStats.selfDeliveryDropped.incrementAndGet();
            return;
        }
        clusterStats.crossNodeBroadcastReceived.incrementAndGet();
        try (ClusterTraceContext.Scope ts = traceScope(envelope)) {
            AbstractMessage message = deserializePayload(envelope.getPayload());
            localSender.topicMessage(envelope.getUri(), message);
        } catch (Exception e) {
            log.warn("Failed to deliver cluster broadcast for URI {}", envelope.getUri(), e);
        }
    }
```
`onReliableMessage` — same wrap:
```java
    private void onReliableMessage(ClusterEnvelope envelope) {
        if (nodeManager.getNodeId().equals(envelope.getOriginNodeId())) {
            return;
        }
        clusterStats.reliableReceived.incrementAndGet();
        try (ClusterTraceContext.Scope ts = traceScope(envelope)) {
            AbstractMessage message = deserializePayload(envelope.getPayload());
            localSender.topicMessage(envelope.getUri(), message);
        } catch (Exception e) {
            log.warn("Failed to deliver reliable broadcast for URI {}", envelope.getUri(), e);
        }
    }
```
`onUnicastMessage` — wrap the whole body after the `sessionId`/`uri` extraction (a `return` inside try-with-resources still closes the scope):
```java
    private void onUnicastMessage(ClusterEnvelope envelope) {
        String sessionId = envelope.getTargetSessionId();
        String uri = envelope.getUri();
        try (ClusterTraceContext.Scope ts = traceScope(envelope)) {
            if (envelope.getKind() == ClusterEnvelope.MessageKind.CLOSE) {
                try {
                    String closePayload = new String(envelope.getPayload(), StandardCharsets.UTF_8);
                    int sep = closePayload.indexOf('|');
                    int statusCode = sep > 0 ? Integer.parseInt(closePayload.substring(0, sep)) : 1000;
                    String reasonText = sep > 0 ? closePayload.substring(sep + 1) : "Remote close";
                    localSender.closeSession(uri, sessionId, statusCode, reasonText);
                } catch (Exception e) {
                    log.warn("Failed to execute remote close for session {}", sessionId, e);
                }
                return;
            }
            // Regular data message (UNICAST kind)
            try {
                AbstractMessage message = deserializePayload(envelope.getPayload());
                localSender.sendMessage(uri, message, sessionId);
            } catch (MessageSessionClosedException e) {
                log.debug("Unicast target session {} not found locally — may have disconnected", sessionId);
            } catch (Exception e) {
                log.warn("Failed to deliver cluster unicast for session {}", sessionId, e);
            }
        }
    }
```
(This preserves every existing inner try/catch and the early `return`; only the outer try-with-resources is added.)

- [ ] **Step 4: Compile + run cluster module tests** (no behavior change when disabled)
`mvn -q -pl netty-spring-websocket-cluster -am test -Dsurefire.failIfNoSpecifiedTests=false` → BUILD SUCCESS (existing cluster tests still green; `traceContext` is null in all of them so behavior is unchanged).

- [ ] **Step 5: Commit**
```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterMessageSender.java
git commit -m "feat(cluster): inject traceparent on send + restore MDC scope on receive

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Config property + gated auto-config bean + context test

**Files:** `ClusterProperties.java`, `NettyWebSocketClusterConfigure.java`, `NettyWebSocketClusterConfigureTest.java`.

- [ ] **Step 1: Add the property**
In `ClusterProperties.java`, after the `private Auth auth = new Auth();` field, add:
```java
    /** Opt-in W3C TraceContext (traceparent) cross-node propagation + MDC restore. Disabled by default. */
    private TracePropagation tracePropagation = new TracePropagation();
```
After the `getAuth`/`setAuth` accessors, add:
```java
    public TracePropagation getTracePropagation() { return tracePropagation; }
    public void setTracePropagation(TracePropagation v) { this.tracePropagation = v; }
```
After the `Auth` nested class, add:
```java
    /**
     * W3C TraceContext propagation. Off by default. When {@code enable=true}, the current
     * traceparent is carried in the envelope and restored into MDC on the receiving node so
     * cross-node deliveries log with the originating trace id.
     */
    public static class TracePropagation {
        /** Master gate. Default false. */
        private boolean enable = false;

        public boolean isEnable() { return enable; }
        public void setEnable(boolean enable) { this.enable = enable; }
    }
```

- [ ] **Step 2: Add the gated bean + inject into the sender**
In `NettyWebSocketClusterConfigure.java`, add imports:
```java
import com.github.berrywang1996.netty.spring.web.websocket.cluster.MdcClusterTraceContext;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterTraceContext;
```
Add a bean (place it near the `messageAuthenticator` bean):
```java
    @Bean
    @ConditionalOnMissingBean(ClusterTraceContext.class)
    @ConditionalOnProperty(prefix = "server.netty.websocket.cluster.trace-propagation",
            name = "enable", havingValue = "true")
    public ClusterTraceContext clusterTraceContext() {
        log.info("Cluster W3C TraceContext propagation ENABLED (MDC-based traceparent across nodes)");
        return new MdcClusterTraceContext();
    }
```
In the `clusterMessageSender(...)` `@Bean` method signature, add a parameter (mirroring the `reliableBroker` one):
```java
            @org.springframework.beans.factory.annotation.Autowired(required = false) ClusterTraceContext traceContext,
```
and in the body, after the `if (reliableBroker != null) { sender.setReliableBroker(reliableBroker); }` block, add:
```java
        if (traceContext != null) {
            sender.setTraceContext(traceContext);
        }
```

- [ ] **Step 3: Context test**
In `NettyWebSocketClusterConfigureTest.java`, add (it needs Redis + the cluster enabled; mirror the existing enabled tests):
```java
    @Test
    void tracePropagationEnabled_wiresClusterTraceContext() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-trace-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30",
                        "server.netty.websocket.cluster.trace-propagation.enable=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterTraceContext.class);
                });
    }

    @Test
    void tracePropagationDisabledByDefault_noClusterTraceContextBean() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-notrace-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterTraceContext.class);
                });
    }
```

- [ ] **Step 4: Run**
`mvn -pl netty-websocket-cluster-spring-boot-starter -am test -Dtest=NettyWebSocketClusterConfigureTest -Dsurefire.failIfNoSpecifiedTests=false` → BUILD SUCCESS, the two new tests run (not skipped).

- [ ] **Step 5: Commit**
```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterProperties.java netty-websocket-cluster-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigure.java netty-websocket-cluster-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigureTest.java
git commit -m "feat(cluster): trace-propagation.enable gate + wire ClusterTraceContext into the sender

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Real-Redis integration test (traceparent survives the wire)

**Files:** create `ClusterTraceIntegrationTest.java` (cluster module test). Mirrors `ClusterAuthIntegrationTest` (uses `ClusterTestRedis`).

- [ ] **Step 1: Write the test**
Create `netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterTraceIntegrationTest.java`:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisPubSubBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Real-Redis: a traceparent set on a published envelope survives the wire to a subscriber on another node. */
class ClusterTraceIntegrationTest {

    private static final String TP = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    private static ClusterEnvelope env(String origin, String uri, String traceparent) {
        return new ClusterEnvelope(origin, uri, ClusterEnvelope.MessageKind.BROADCAST,
                "T:hello".getBytes(), null, traceparent, System.currentTimeMillis());
    }

    @Test
    void traceparentSurvivesCrossNodeDelivery() throws Exception {
        Assumptions.assumeTrue(ClusterTestRedis.available(), "no Redis and no Docker");
        RedisClient ca = ClusterTestRedis.newClient();
        RedisClient cb = ClusterTestRedis.newClient();
        RedisPubSubBroker a = new RedisPubSubBroker(ca, new SimpleTextEnvelopeCodec());
        RedisPubSubBroker b = new RedisPubSubBroker(cb, new SimpleTextEnvelopeCodec());
        List<ClusterEnvelope> got = new CopyOnWriteArrayList<>();
        b.subscribe("/ws/trace", got::add);
        Thread.sleep(300);
        a.publish("/ws/trace", env("node-A", "/ws/trace", TP));
        long deadline = System.currentTimeMillis() + 4000;
        while (got.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(1, got.size(), "node B should receive the broadcast");
        assertEquals(TP, got.get(0).getTraceparent(), "traceparent must survive the cross-node wire");
        a.shutdown();
        b.shutdown();
    }
}
```
(Confirm the `RedisPubSubBroker(RedisClient, EnvelopeCodec)` 2-arg constructor exists — `ClusterAuthIntegrationTest` uses a 3-arg `(client, codec, authenticator)`; if there is no 2-arg ctor, pass a `new NoOpMessageAuthenticator()` as the 3rd arg like that test does.)

- [ ] **Step 2: Run** — `mvn -pl netty-spring-websocket-cluster -am test -Dtest=ClusterTraceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` → 1 test passes (Redis live, not skipped).

- [ ] **Step 3: Commit**
```
git add netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterTraceIntegrationTest.java
git commit -m "test(cluster): real-Redis traceparent cross-node round-trip

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Documentation

**Files:** `docs/release-notes-1.9.0.md`, `docs/api-guide.md`, `docs/cluster-design.md`, `docs/development-plan.md`, `docs/release-checklist.md`.

- [ ] **Step 1: Write docs** (Edit tool only; verify UTF-8 + U+FFFD after: `for f in docs/release-notes-1.9.0.md docs/api-guide.md docs/cluster-design.md docs/development-plan.md docs/release-checklist.md; do iconv -f UTF-8 -t UTF-8 "$f" >/dev/null 2>&1 && echo "$f ok" || echo "$f BAD"; done; LC_ALL=C grep -l $'\xef\xbf\xbd' docs/*.md || echo none`)
Content (accurate): RC6 = W3C TraceContext cross-node propagation. `ClusterTraceContext` SPI (default `MdcClusterTraceContext`, MDC-based, tracer-agnostic); opt-in via `server.netty.websocket.cluster.trace-propagation.enable` (default false); on send the current traceparent (explicit MDC `traceparent` or synthesized from `traceId`/`spanId`) is carried in the envelope (wire format already supported it); on receive it's restored into MDC (`traceId`/`spanId`/`netty.traceparent`) for the cross-node delivery scope so logs correlate; no wire change, zero-cost when off. **Micrometer Observation / active-span continuation explicitly deferred to 2.0.0 (Boot 3.x — Boot 2.7 ships Micrometer 1.9, no Observation API).** api-guide: document the `netty.traceparent` MDC key + the `trace-propagation.enable` config row. cluster-design + development-plan: move "W3C TraceContext 跨节点传播" from deferred to ✅ RC6 **at the MDC-correlation level**, noting the Observation tier is 2.0.0. release-checklist: note it landed at RC6.

- [ ] **Step 2: Commit**
```
git add docs/
git commit -m "docs(cluster): W3C TraceContext propagation (RC6) — release notes, api-guide, roadmap

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Full test + cut v1.9.0-RC6

**Files:** all 11 poms (`1.9.0-RC5` → `1.9.0-RC6`); `docs/release-notes-1.9.0.md` (count + status).

- [ ] **Step 1: Full reactor test** — `mvn test` (Redis + Docker up) → BUILD SUCCESS, 11 modules. Capture the exact total (was 327; +~9: `MdcClusterTraceContextTest` 6 + `ClusterTraceIntegrationTest` 1 + 2 context tests). STOP + report if anything fails or unexpectedly skips.
- [ ] **Step 2: Update release-notes** (Edit tool only) — set the `## 测试覆盖` count line to the real Step-1 total + the status line to `1.9.0-RC6` (+ an RC6 clause); add a `（RC6）` test bullet. Verify UTF-8 + U+FFFD.
- [ ] **Step 3: Bump to RC6** — `for f in $(grep -rl "1.9.0-RC5" --include=pom.xml .); do sed -i 's|<version>1.9.0-RC5</version>|<version>1.9.0-RC6</version>|g' "$f"; done`; verify `grep -rl "1.9.0-RC5" --include=pom.xml . | wc -l` = 0 and `grep -rl "1.9.0-RC6" --include=pom.xml . | wc -l` = 11.
- [ ] **Step 4: Re-test** — `mvn -q test` → BUILD SUCCESS.
- [ ] **Step 5: Commit + tag**
```
git add -A
git commit -m "release: 1.9.0-RC6 — W3C TraceContext cross-node propagation

Opt-in traceparent propagation (ClusterTraceContext SPI + MdcClusterTraceContext) carries the
trace across nodes and restores it into MDC on receive for log correlation. Additive, no wire
change. Micrometer Observation continuation deferred to 2.0.0 (Boot 3.x). Part of the 1.9.0
cycle; final 1.9.0 when the cycle completes.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git tag -a v1.9.0-RC6 -m "v1.9.0-RC6 — W3C TraceContext cross-node propagation (1.9.0 cycle, in development)"
```
- [ ] **Step 6: Report** — RC6 cut locally (not pushed/deployed). The 1.9.0 cycle continues until the user says it's complete.

---

## Notes for the implementer
- **Opt-in + zero-cost when off:** `traceContext` stays null unless `trace-propagation.enable=true`; the builders pass `null` traceparent and the listeners use `ClusterTraceContext.NOOP` — byte-identical to RC5 behavior.
- **No wire change:** `ClusterEnvelope`/`SimpleTextEnvelopeCodec` already carry `traceparent`. Do not touch them.
- **Don't change `MdcUtil.clear()`** — it must not remove tracer-managed `traceId`/`spanId`; the receive `Scope.close()` owns cleanup on the broker thread.
- **try-with-resources + catch** is valid Java: the `Scope` closes after the try body (before any catch), and `Scope.close()` never throws.
