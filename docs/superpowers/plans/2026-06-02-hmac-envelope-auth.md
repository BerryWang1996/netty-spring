# HMAC Envelope Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Opt-in HMAC-SHA256 authentication of every cross-node cluster envelope so a party that can write to Redis but lacks the shared secret cannot forge `originNodeId`, inject messages, or force-close sessions — default off, zero change.

**Architecture:** A new transport-layer `MessageAuthenticator` SPI the brokers call around the codec: `wrap()` after `encode` (sign), `unwrap()` before `decode` (verify; null = reject → drop). Default `NoOpMessageAuthenticator` (identity, but strips an `H1:` tag without verifying so a disabled node can read signed traffic mid-rollout); `HmacMessageAuthenticator` when `auth.enable=true`. Both `RedisPubSubBroker` and `RedisStreamsReliableBroker` get the authenticator via a new constructor arg with a backward-compat overload defaulting to NoOp. Codec-agnostic; no envelope schema change.

**Tech Stack:** Java 17, `javax.crypto.Mac` (HmacSHA256), `java.util.Base64` (url-encoder), Lettuce 6.1.x, Spring Boot 2.7.18 auto-config, JUnit 5. Spec: `docs/superpowers/specs/2026-06-02-hmac-envelope-auth-design.md`. Develops on the `1.9.0-RC2` line.

**Decisions:** anti-forgery only (no replay window); `permissive` migration knob; HMAC-SHA256; secret from externalized config. The reject counter lives on `HmacMessageAuthenticator` (`getRejectedCount()`), not `ClusterRuntimeStats` (lifecycle/ownership).

---

## Environment notes for every task
- Repo: `C:\Users\qq951\IdeaProjects\netty-spring`; Windows (PowerShell + Bash); Maven 3.9.9 (Aliyun mirror); Java 17.
- Git: work on branch `feature/1.9.0-hmac-auth` (created in the setup step before Task 1). Do NOT push or deploy.
- Redis live on `localhost:16379` (integration tests run, not skip).
- Match on quoted code, not line numbers. TDD where a test is specified.

## Wire format (used throughout)
Signed wire string: `H1:{tag}:{payload}` where `tag = base64url-nopad(HMAC_SHA256(secret, payload))` (43 chars for SHA-256), `payload` = the codec-encoded envelope. base64url has no `:`, so the first `:` after the `H1:` prefix delimits tag from payload (payload may contain `:`/`|`).

## File Structure
**New (`netty-spring-websocket-cluster`):**
- `.../cluster/spi/MessageAuthenticator.java` — SPI (`wrap`/`unwrap`).
- `.../cluster/auth/NoOpMessageAuthenticator.java` — identity + H1-strip-without-verify.
- `.../cluster/auth/HmacMessageAuthenticator.java` — HMAC-SHA256 sign/verify + reject counter.

**Modified:**
- `.../cluster/redis/RedisPubSubBroker.java` — authenticator field + compat ctor + wrap (publish/unicast) + unwrap (message callback).
- `.../cluster/redis/RedisStreamsReliableBroker.java` — authenticator field + compat ctor + wrap (reliablePublish) + unwrap (deliver).
- `.../cluster/ClusterProperties.java` — nested `Auth` config.
- `netty-websocket-cluster-spring-boot-starter/.../NettyWebSocketClusterConfigure.java` — `MessageAuthenticator` bean + inject into both brokers.
- `netty-websocket-cluster-spring-boot-starter/.../META-INF/additional-spring-configuration-metadata.json` — 3 `auth.*` keys.

**Tests:**
- `.../cluster/MessageAuthenticatorTest.java` (unit), `.../cluster/ClusterAuthIntegrationTest.java` (real Redis), `NettyWebSocketClusterConfigureTest.java` (context additions).

---

## Task 0: Branch setup
- [ ] **Step 1:** `git checkout -b feature/1.9.0-hmac-auth` (from `master`, which is at RC2). Confirm `git branch --show-current` = `feature/1.9.0-hmac-auth`.

---

## Task 1: MessageAuthenticator SPI + NoOp impl + Auth config + metadata

**Files:** Create `spi/MessageAuthenticator.java`, `auth/NoOpMessageAuthenticator.java`; modify `ClusterProperties.java`, `additional-spring-configuration-metadata.json`.

- [ ] **Step 1: Create the SPI** (Apache header copied from `spi/ClusterBroker.java`):
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.spi;

/**
 * Transport-layer authenticator for cross-node envelopes: wraps the codec-encoded string with an
 * integrity/authenticity tag before publish, and verifies+strips it on receive. Sits OUTSIDE the
 * {@link EnvelopeCodec} so it is codec-agnostic and applies uniformly to broadcast/unicast/close and
 * the reliable Streams path.
 *
 * <p>Default impl is a no-op (no signing). {@code HmacMessageAuthenticator} signs/verifies with a
 * shared secret when {@code server.netty.websocket.cluster.auth.enable=true}. Implementations must be
 * thread-safe.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
public interface MessageAuthenticator {

    /**
     * Produces the wire string from a codec-encoded envelope (e.g. prepends an HMAC tag).
     * @param encoded the codec output (never null)
     * @return the wire string to publish (no-op returns {@code encoded} unchanged)
     */
    String wrap(String encoded);

    /**
     * Verifies and strips the tag, returning the inner codec-encoded envelope, or {@code null} to
     * REJECT the message (missing/invalid tag when verification is required).
     * @param wireData the received wire string
     * @return the inner encoded envelope, or null to drop
     */
    String unwrap(String wireData);
}
```

- [ ] **Step 2: Create the NoOp impl** (Apache header), strips `H1:` without verifying so a disabled node can read signed traffic during rollout:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.auth;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;

/**
 * No-op authenticator (auth disabled): does not sign. On receive it strips a well-formed {@code H1:}
 * tag WITHOUT verifying (so a not-yet-enabled node can still read messages signed by already-enabled
 * peers during a rolling upgrade); plain (untagged) data passes through unchanged.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
public class NoOpMessageAuthenticator implements MessageAuthenticator {

    static final String PREFIX = "H1:";

    @Override
    public String wrap(String encoded) {
        return encoded; // disabled: never sign
    }

    @Override
    public String unwrap(String wireData) {
        if (wireData != null && wireData.startsWith(PREFIX)) {
            int sep = wireData.indexOf(':', PREFIX.length());
            if (sep > 0) {
                return wireData.substring(sep + 1); // strip tag, do not verify
            }
        }
        return wireData;
    }
}
```

- [ ] **Step 3: Add nested Auth config to ClusterProperties** — add a field after the `reliable` field:
```java
    /** Opt-in HMAC authentication of cross-node envelopes. Disabled by default. */
    private Auth auth = new Auth();
```
accessors after the `reliable` accessors:
```java
    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }
```
nested class after the `Reliable` nested class:
```java
    /**
     * HMAC envelope authentication. Off by default. When {@code enable=true}, every cross-node envelope
     * is signed (HMAC-SHA256) and inbound envelopes with a missing/invalid tag are rejected.
     */
    public static class Auth {
        /** Master gate. Default false (no signing; pass-through). */
        private boolean enable = false;
        /** Shared HMAC secret (UTF-8). Required when enable=true. Externalize via ${ENV}; never hardcode. */
        private String secret;
        /** Migration: when true, accept UNSIGNED inbound (still signing outbound) — for rolling rollout.
         *  When false (default), reject unsigned inbound. */
        private boolean permissive = false;

        public boolean isEnable() { return enable; }
        public void setEnable(boolean enable) { this.enable = enable; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public boolean isPermissive() { return permissive; }
        public void setPermissive(boolean permissive) { this.permissive = permissive; }
    }
```

- [ ] **Step 4: Add config metadata** — append to the `properties` array (comma after the last existing entry):
```json
    {
      "name": "server.netty.websocket.cluster.auth.enable",
      "type": "java.lang.Boolean",
      "description": "Opt-in HMAC authentication of cross-node envelopes (anti-forgery). Disabled by default. When true, every envelope is signed (HMAC-SHA256) and inbound envelopes with a missing/invalid tag are rejected — closing the threat where a Redis writer forges originNodeId / injects / force-closes sessions. Enable cluster-wide (shared secret); see the rolling-upgrade note in the release notes.",
      "defaultValue": false
    },
    {
      "name": "server.netty.websocket.cluster.auth.secret",
      "type": "java.lang.String",
      "description": "Shared HMAC secret (UTF-8). REQUIRED when auth.enable=true. Externalize via an environment variable or secret manager (e.g. ${CLUSTER_AUTH_SECRET}); never hardcode. Use >= 32 chars. Redacted in logs."
    },
    {
      "name": "server.netty.websocket.cluster.auth.permissive",
      "type": "java.lang.Boolean",
      "description": "Migration mode: when true, UNSIGNED inbound envelopes are accepted (while still signing outbound) so HMAC can be rolled out node-by-node without cross-node message loss; flip to false (strict) once all nodes sign. Default false.",
      "defaultValue": false
    }
```

- [ ] **Step 5: Compile** — `mvn -q -pl netty-spring-websocket-cluster -am -DskipTests compile` → BUILD SUCCESS.
- [ ] **Step 6: Commit**
```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/spi/MessageAuthenticator.java netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/auth/NoOpMessageAuthenticator.java netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterProperties.java netty-websocket-cluster-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json
git commit -m "feat(cluster): MessageAuthenticator SPI + NoOp impl + auth.* config (HMAC scaffolding)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: HmacMessageAuthenticator + unit tests

**Files:** Create `auth/HmacMessageAuthenticator.java`; create `MessageAuthenticatorTest.java`.

- [ ] **Step 1: Write the failing unit test** — `netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/MessageAuthenticatorTest.java`:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.HmacMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageAuthenticatorTest {

    private static final String SECRET = "this-is-a-32+char-cluster-secret!!";
    private static final String PAYLOAD = "T:hello|/ws/chat|node-A|123"; // a codec-like string with ':' and '|'

    @Test
    void hmacRoundTripsAndIsTagged() {
        HmacMessageAuthenticator a = new HmacMessageAuthenticator(SECRET.getBytes(), true);
        String wire = a.wrap(PAYLOAD);
        assertTrue(wire.startsWith("H1:"), "wrapped wire must carry the H1 tag");
        assertNotEquals(PAYLOAD, wire);
        assertEquals(PAYLOAD, a.unwrap(wire), "unwrap of a valid tag returns the original payload");
        assertEquals(0, a.getRejectedCount());
    }

    @Test
    void tamperedPayloadIsRejected() {
        HmacMessageAuthenticator a = new HmacMessageAuthenticator(SECRET.getBytes(), true);
        String wire = a.wrap(PAYLOAD);
        String tampered = wire + "X"; // mutate the payload after the tag
        assertNull(a.unwrap(tampered), "a tampered payload must be rejected");
        assertEquals(1, a.getRejectedCount());
    }

    @Test
    void wrongSecretRejects() {
        HmacMessageAuthenticator signer = new HmacMessageAuthenticator(SECRET.getBytes(), true);
        HmacMessageAuthenticator verifier = new HmacMessageAuthenticator("a-different-secret-key-32-chars!!".getBytes(), true);
        assertNull(verifier.unwrap(signer.wrap(PAYLOAD)), "a different secret must reject");
        assertEquals(1, verifier.getRejectedCount());
    }

    @Test
    void missingTagRejectedWhenStrictAcceptedWhenPermissive() {
        HmacMessageAuthenticator strict = new HmacMessageAuthenticator(SECRET.getBytes(), true); // requireSigned=true
        assertNull(strict.unwrap(PAYLOAD), "strict rejects an untagged (unsigned) message");
        assertEquals(1, strict.getRejectedCount());

        HmacMessageAuthenticator permissive = new HmacMessageAuthenticator(SECRET.getBytes(), false); // requireSigned=false
        assertEquals(PAYLOAD, permissive.unwrap(PAYLOAD), "permissive accepts an unsigned message");
        assertEquals(0, permissive.getRejectedCount());
        // permissive still verifies a present tag:
        assertEquals(PAYLOAD, permissive.unwrap(permissive.wrap(PAYLOAD)));
    }

    @Test
    void noOpStripsTagWithoutVerifyingAndPassesPlain() {
        NoOpMessageAuthenticator noop = new NoOpMessageAuthenticator();
        assertEquals(PAYLOAD, noop.wrap(PAYLOAD), "no-op never signs");
        // a signed message from an enabled peer is stripped (not verified) so a disabled node can read it:
        String signed = new HmacMessageAuthenticator(SECRET.getBytes(), true).wrap(PAYLOAD);
        assertEquals(PAYLOAD, noop.unwrap(signed));
        assertEquals(PAYLOAD, noop.unwrap(PAYLOAD), "plain passes through");
    }
}
```

- [ ] **Step 2: Run to verify failure** — `mvn -q -pl netty-spring-websocket-cluster test -Dtest=MessageAuthenticatorTest` → FAIL to COMPILE (`HmacMessageAuthenticator` missing).

- [ ] **Step 3: Create HmacMessageAuthenticator** (Apache header):
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.auth;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HMAC-SHA256 authenticator. Wraps the codec-encoded envelope as {@code H1:{base64url(tag)}:{payload}};
 * verifies the tag on receive with a constant-time compare. {@code requireSigned} (= !permissive)
 * controls whether an untagged inbound message is rejected (strict) or accepted (permissive rollout).
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class HmacMessageAuthenticator implements MessageAuthenticator {

    private static final String PREFIX = "H1:";
    private static final String ALGO = "HmacSHA256";

    private final byte[] secret;
    private final boolean requireSigned;
    private final AtomicLong rejectedCount = new AtomicLong();
    /** Mac is not thread-safe — one per thread, keyed once. */
    private final ThreadLocal<Mac> macTl;

    public HmacMessageAuthenticator(byte[] secret, boolean requireSigned) {
        this.secret = secret.clone();
        this.requireSigned = requireSigned;
        this.macTl = ThreadLocal.withInitial(() -> {
            try {
                Mac m = Mac.getInstance(ALGO);
                m.init(new SecretKeySpec(this.secret, ALGO));
                return m;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to init " + ALGO, e);
            }
        });
    }

    /** Number of inbound messages rejected for a missing/invalid tag (observable for tests/health). */
    public long getRejectedCount() { return rejectedCount.get(); }

    @Override
    public String wrap(String encoded) {
        return PREFIX + tag(encoded) + ":" + encoded;
    }

    @Override
    public String unwrap(String wireData) {
        if (wireData == null) { rejectedCount.incrementAndGet(); return null; }
        if (wireData.startsWith(PREFIX)) {
            int sep = wireData.indexOf(':', PREFIX.length());
            if (sep < 0) { rejectedCount.incrementAndGet(); return null; } // malformed
            String tag = wireData.substring(PREFIX.length(), sep);
            String payload = wireData.substring(sep + 1);
            String expected = tag(payload);
            if (MessageDigest.isEqual(tag.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8))) {
                return payload;
            }
            rejectedCount.incrementAndGet();
            return null; // bad MAC
        }
        // untagged (unsigned)
        if (requireSigned) { rejectedCount.incrementAndGet(); return null; }
        return wireData; // permissive: accept unsigned
    }

    private String tag(String payload) {
        Mac m = macTl.get();
        m.reset();
        byte[] raw = m.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
```

- [ ] **Step 4: Run to verify pass** — `mvn -q -pl netty-spring-websocket-cluster test -Dtest=MessageAuthenticatorTest` → PASS (5 tests). Then `mvn -q -pl netty-spring-websocket-cluster test` → module green.
- [ ] **Step 5: Commit**
```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/auth/HmacMessageAuthenticator.java netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/MessageAuthenticatorTest.java
git commit -m "feat(cluster): HmacMessageAuthenticator (HMAC-SHA256 sign/verify, constant-time, reject counter)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Wire HMAC into RedisPubSubBroker

**Files:** Modify `RedisPubSubBroker.java`.

- [ ] **Step 1: Add the authenticator field + compat constructor**
Add an import: `import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;` (and `MessageAuthenticator` is already covered by `spi.*`).
Add a field after `private final EnvelopeCodec codec;`:
```java
    private final MessageAuthenticator authenticator;
```
The current constructor is `public RedisPubSubBroker(RedisClient redisClient, EnvelopeCodec codec) {` — change its signature to add the authenticator, and add a 2-arg compat overload above it:
```java
    /** Backward-compat constructor — no authentication (NoOp). */
    public RedisPubSubBroker(RedisClient redisClient, EnvelopeCodec codec) {
        this(redisClient, codec, new NoOpMessageAuthenticator());
    }

    public RedisPubSubBroker(RedisClient redisClient, EnvelopeCodec codec, MessageAuthenticator authenticator) {
        this.codec = codec;
        this.authenticator = authenticator;
```
(Keep the rest of the original constructor body unchanged — the `redisClient.addListener(...)`, the two `connect()` calls, the pub/sub listener wiring, and the final `log.info`. Only the signature line and the two new lines above change; `this.codec = codec;` stays as the first body statement.)

- [ ] **Step 2: Sign on publish + unicast**
In `publish(...)`: change `String data = codec.encode(envelope);` to `String data = authenticator.wrap(codec.encode(envelope));`.
In `unicast(...)`: change `String data = codec.encode(envelope);` to `String data = authenticator.wrap(codec.encode(envelope));`.

- [ ] **Step 3: Verify on receive**
In the pub/sub `message(String channel, String message)` callback, after the inbound size check and `ClusterMessageListener listener = channelListeners.get(channel);`, replace the `if (listener != null) { try { ClusterEnvelope envelope = codec.decode(message); ... } }` block so it unwraps first:
```java
                ClusterMessageListener listener = channelListeners.get(channel);
                if (listener != null) {
                    String inner = authenticator.unwrap(message);
                    if (inner == null) {
                        log.warn("Rejected inbound cluster message on channel {} — missing/invalid HMAC tag", channel);
                        return;
                    }
                    try {
                        ClusterEnvelope envelope = codec.decode(inner);
                        if (envelope != null) {
                            listener.onMessage(envelope);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to decode cluster envelope on channel {}", channel, e);
                    }
                }
```

- [ ] **Step 4: Compile + existing integration tests** — `mvn -q -pl netty-spring-websocket-cluster test -Dtest=RedisIntegrationTest` → still green (they use the 2-arg ctor → NoOp → unchanged behavior).
- [ ] **Step 5: Commit**
```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/redis/RedisPubSubBroker.java
git commit -m "feat(cluster): RedisPubSubBroker signs (wrap) on publish/unicast, verifies (unwrap) on receive

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Wire HMAC into RedisStreamsReliableBroker

**Files:** Modify `RedisStreamsReliableBroker.java`.

- [ ] **Step 1: Add the authenticator field + compat constructor**
Add import `import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;` (`MessageAuthenticator` via `spi.*`).
Add a field after `private final EnvelopeCodec codec;`:
```java
    private final MessageAuthenticator authenticator;
```
The current constructor is `public RedisStreamsReliableBroker(RedisClient redisClient, EnvelopeCodec codec, int streamMaxLen, long pollBlockMs, int pollCount, int dedupWindow) {`. Add the authenticator as a 7th param and a 6-arg compat overload above it:
```java
    /** Backward-compat constructor — no authentication (NoOp). */
    public RedisStreamsReliableBroker(RedisClient redisClient, EnvelopeCodec codec,
                                      int streamMaxLen, long pollBlockMs, int pollCount, int dedupWindow) {
        this(redisClient, codec, streamMaxLen, pollBlockMs, pollCount, dedupWindow, new NoOpMessageAuthenticator());
    }

    public RedisStreamsReliableBroker(RedisClient redisClient, EnvelopeCodec codec,
                                      int streamMaxLen, long pollBlockMs, int pollCount, int dedupWindow,
                                      MessageAuthenticator authenticator) {
        this.redisClient = redisClient;
        this.codec = codec;
        this.authenticator = authenticator;
```
(Keep the rest of the original constructor body unchanged — the field assignments for streamMaxLen/pollBlockMs/pollCount/dedupWindow, `this.commandConnection = redisClient.connect();`, and the `log.info`.)

- [ ] **Step 2: Sign on publish**
In `reliablePublish(...)`, change `String data = codec.encode(envelope);` to `String data = authenticator.wrap(codec.encode(envelope));`.

- [ ] **Step 3: Verify on deliver**
In `deliver(...)`, the body currently is:
```java
        String data = m.getBody() == null ? null : m.getBody().get(FIELD);
        if (data == null) {
            log.warn("Reliable entry {} on {} has no '{}' field — acking to clear PEL (skipped)", id, streamKey, FIELD);
        } else {
            try {
                ClusterEnvelope env = codec.decode(data);
                ...
```
Insert an unwrap between reading `data` and the decode. Replace the `if (data == null) { ... } else { ...` head so an HMAC-rejected entry is treated like an undeliverable one (log + ack to clear PEL, no delivery). Change to:
```java
        String data = m.getBody() == null ? null : m.getBody().get(FIELD);
        if (data != null) {
            data = authenticator.unwrap(data); // null = rejected (missing/invalid HMAC)
        }
        if (data == null) {
            log.warn("Reliable entry {} on {} dropped (no field, or rejected HMAC) — acking to clear PEL", id, streamKey);
        } else {
            try {
                ClusterEnvelope env = codec.decode(data);
```
(Keep the rest of the `else { try { ... } catch (Throwable ex) { ... } }` body and the trailing `seen.put(id, ...); ack(...)` unchanged.)

- [ ] **Step 4: Compile + existing reliable tests** — `mvn -q -pl netty-spring-websocket-cluster test -Dtest=ReliableBroadcastIntegrationTest` → still green (6-arg ctor → NoOp → unchanged).
- [ ] **Step 5: Commit**
```
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/redis/RedisStreamsReliableBroker.java
git commit -m "feat(cluster): RedisStreamsReliableBroker signs reliablePublish, verifies on deliver

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Auto-config — MessageAuthenticator bean + inject into both brokers

**Files:** Modify `NettyWebSocketClusterConfigure.java`; modify `NettyWebSocketClusterConfigureTest.java`.

- [ ] **Step 1: Add the authenticator bean + inject it**
Add imports:
```java
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.HmacMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;
```
Add a bean method (place it right before the `clusterBroker(...)` bean):
```java
    @Bean
    @ConditionalOnMissingBean(MessageAuthenticator.class)
    public MessageAuthenticator messageAuthenticator(ClusterProperties properties) {
        ClusterProperties.Auth auth = properties.getAuth();
        if (!auth.isEnable()) {
            return new NoOpMessageAuthenticator();
        }
        String secret = auth.getSecret();
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException("server.netty.websocket.cluster.auth.enable=true requires "
                    + "a non-empty server.netty.websocket.cluster.auth.secret");
        }
        if (secret.length() < 32) {
            log.warn("Cluster auth secret is short ({} chars) — use >= 32 chars of high-entropy secret "
                    + "for HMAC-SHA256.", secret.length());
        }
        log.info("Cluster envelope HMAC authentication ENABLED (mode={})",
                auth.isPermissive() ? "permissive" : "strict");
        return new HmacMessageAuthenticator(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                !auth.isPermissive());
    }
```
Change `clusterBroker(...)` to take + pass the authenticator:
```java
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(ClusterBroker.class)
    public RedisPubSubBroker clusterBroker(RedisClient redisClient, EnvelopeCodec envelopeCodec,
                                           ClusterProperties properties, MessageAuthenticator messageAuthenticator) {
        RedisPubSubBroker broker = new RedisPubSubBroker(redisClient, envelopeCodec, messageAuthenticator);
        int maxOut = properties.getMessageMaxSizeBytes();
        broker.setInboundMaxBytes(maxOut > 0 ? (int) Math.min((long) maxOut * 2L, Integer.MAX_VALUE) : 0);
        return broker;
    }
```
Change `reliableBroker(...)` to take + pass the authenticator (add the param and the 7th constructor arg):
```java
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(ReliableBroker.class)
    @ConditionalOnProperty(prefix = "server.netty.websocket.cluster.reliable", name = "enable", havingValue = "true")
    public ReliableBroker reliableBroker(RedisClient redisClient, EnvelopeCodec envelopeCodec,
                                         ClusterProperties properties, MessageAuthenticator messageAuthenticator) {
        ClusterProperties.Reliable r = properties.getReliable();
        log.info("Reliable broadcast ENABLED (Redis Streams; maxlen={}, block={}ms)",
                r.getStreamMaxLen(), r.getPollBlockMs());
        return new RedisStreamsReliableBroker(redisClient, envelopeCodec,
                r.getStreamMaxLen(), r.getPollBlockMs(), r.getPollCount(), r.getDedupWindow(), messageAuthenticator);
    }
```

- [ ] **Step 2: Context-test additions**
In `NettyWebSocketClusterConfigureTest.java`, in the enabled test, add (after existing assertions):
```java
                    // auth off by default → the NoOp authenticator is wired
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator.class);
```
Add a new test:
```java
    @Test
    void authEnabled_wiresHmacAuthenticator() {
        Assumptions.assumeTrue(redisAvailable, "Redis not available on " + REDIS_URI);
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-auth-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30",
                        "server.netty.websocket.cluster.auth.enable=true",
                        "server.netty.websocket.cluster.auth.secret=this-is-a-32+char-cluster-secret!!")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.HmacMessageAuthenticator.class);
                });
    }

    @Test
    void authEnabledWithoutSecret_failsContext() {
        runner.withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + REDIS_URI,
                        "server.netty.websocket.cluster.node-id=ctx-auth-nosecret",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30",
                        "server.netty.websocket.cluster.auth.enable=true")
                .run(context -> assertThat(context).hasFailed());
    }
```
(`authEnabledWithoutSecret_failsContext` does not need Redis — the auth bean fails before any Redis use; no `assumeTrue`.)

- [ ] **Step 3: Run** — `mvn -q -pl netty-websocket-cluster-spring-boot-starter -am test -Dtest=NettyWebSocketClusterConfigureTest` → all PASS (auth-disabled→NoOp; auth-enabled→Hmac; enabled-no-secret→context fails). Then `mvn -q -pl netty-spring-websocket-cluster test` → green.
- [ ] **Step 4: Commit**
```
git add netty-websocket-cluster-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigure.java netty-websocket-cluster-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigureTest.java
git commit -m "feat(cluster): wire MessageAuthenticator bean (NoOp/Hmac by auth.enable) into both brokers

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Real-Redis auth integration test

**Files:** Create `ClusterAuthIntegrationTest.java`.

- [ ] **Step 1: Write the integration test**
`netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterAuthIntegrationTest.java`:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.HmacMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisPubSubBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Real-Redis integration for HMAC envelope auth over the Pub/Sub broker. Skipped without localhost:16379. */
class ClusterAuthIntegrationTest {

    private static final String REDIS_URI = "redis://localhost:16379";
    private static final String SECRET = "this-is-a-32+char-cluster-secret!!";
    private static boolean redisAvailable;
    private static RedisClient probe;

    @BeforeAll
    static void check() {
        try { probe = RedisClient.create(REDIS_URI); StatefulRedisConnection<String,String> c = probe.connect();
            c.sync().ping(); c.close(); redisAvailable = true; } catch (Exception e) { redisAvailable = false; }
    }
    @AfterAll static void done() { if (probe != null) try { probe.shutdown(); } catch (Exception ignored) {} }

    private static ClusterEnvelope env() {
        return new ClusterEnvelope("node-A", "/ws/auth", ClusterEnvelope.MessageKind.BROADCAST,
                "T:secret-msg".getBytes(), null, null, System.currentTimeMillis());
    }

    @Test
    void sameSecretAccepts_differentSecretRejects() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");
        RedisClient ca = RedisClient.create(REDIS_URI);
        RedisClient cb = RedisClient.create(REDIS_URI);
        HmacMessageAuthenticator authB = new HmacMessageAuthenticator(SECRET.getBytes(), true);
        // A signs with SECRET; B verifies with SECRET → accepts.
        RedisPubSubBroker a = new RedisPubSubBroker(ca, new SimpleTextEnvelopeCodec(),
                new HmacMessageAuthenticator(SECRET.getBytes(), true));
        RedisPubSubBroker b = new RedisPubSubBroker(cb, new SimpleTextEnvelopeCodec(), authB);
        List<ClusterEnvelope> got = new CopyOnWriteArrayList<>();
        b.subscribe("/ws/auth", got::add);
        Thread.sleep(300);
        a.publish("/ws/auth", env());
        long deadline = System.currentTimeMillis() + 4000;
        while (got.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(1, got.size(), "same-secret message is accepted");
        assertEquals(0, authB.getRejectedCount());

        // A2 signs with a DIFFERENT secret → B rejects (bad MAC), nothing delivered.
        RedisClient ca2 = RedisClient.create(REDIS_URI);
        RedisPubSubBroker a2 = new RedisPubSubBroker(ca2, new SimpleTextEnvelopeCodec(),
                new HmacMessageAuthenticator("a-totally-different-secret-32ch!!".getBytes(), true));
        got.clear();
        a2.publish("/ws/auth", env());
        Thread.sleep(800);
        assertTrue(got.isEmpty(), "a foreign-secret message must be rejected");
        assertTrue(authB.getRejectedCount() >= 1, "rejection is counted");

        a.shutdown(); b.shutdown(); a2.shutdown(); ca.shutdown(); cb.shutdown(); ca2.shutdown();
    }
}
```

- [ ] **Step 2: Run** — `mvn -q -pl netty-spring-websocket-cluster test -Dtest=ClusterAuthIntegrationTest` → PASS (Redis live). Then `mvn -q -pl netty-spring-websocket-cluster test` → green.
- [ ] **Step 3: Commit**
```
git add netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterAuthIntegrationTest.java
git commit -m "test(cluster): HMAC auth integration — same-secret accept, foreign-secret reject (+count)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: Documentation

**Files:** Modify `docs/release-notes-1.9.0.md`, `docs/api-guide.md`, `docs/cluster-design.md`, `docs/development-plan.md`.

- [ ] **Step 1: Write the docs** (ENCODING SAFETY: use the Edit tool ONLY — never PowerShell file writes; these are bilingual CJK docs. After editing run `for f in docs/release-notes-1.9.0.md docs/api-guide.md docs/cluster-design.md docs/development-plan.md; do iconv -f UTF-8 -t UTF-8 "$f" >/dev/null 2>&1 && echo "$f ok" || echo "$f BAD"; done` and `LC_ALL=C grep -l $'\xef\xbf\xbd' docs/*.md || echo none`.)
Content (accurate): opt-in HMAC-SHA256 envelope auth via `MessageAuthenticator` (transport-layer, codec-agnostic); `auth.enable`/`auth.secret`/`auth.permissive` (3 keys); signs broadcast+unicast+CLOSE+reliable; rejects missing/invalid tag (counted on the authenticator); NoOp default = zero change + strips `H1:` for rollout; **3-phase rolling upgrade** (deploy disabled → enable+permissive → strict); secret externalized + redacted + ≥32 chars; threat closed (forge originNodeId / inject / force-close). RC3. Move "HMAC envelope 认证" from the deferred lists to ✅ shipped (1.9.0 RC3); keep the remaining deferred items (NATS, full metrics, sharded pub/sub, Redis Cluster client, W3C, Testcontainers).
- [ ] **Step 2: Commit**
```
git add docs/
git commit -m "docs(cluster): HMAC envelope authentication — release notes, api-guide, design, roadmap

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: Full test + cut v1.9.0-RC3

**Files:** all 11 poms (`1.9.0-RC2` → `1.9.0-RC3`); `docs/release-notes-1.9.0.md` (test count + status).

- [ ] **Step 1: Full reactor test** — `mvn test` (Redis up) → BUILD SUCCESS, 11 modules. Capture the exact total (was 311; +~7: MessageAuthenticatorTest 5, ClusterAuthIntegrationTest 1, context +2). STOP+report if anything fails.
- [ ] **Step 2: Update release-notes** (Edit tool only) — bump the `## 测试覆盖` count line to the real Step-1 total + the status line to RC3; add a bullet for the auth tests. Verify UTF-8 + U+FFFD as in Task 7.
- [ ] **Step 3: Bump to RC3** — `for f in pom.xml */pom.xml; do sed -i 's|<version>1.9.0-RC2</version>|<version>1.9.0-RC3</version>|g' "$f"; done` ; verify `grep -rl "1.9.0-RC2" --include=pom.xml . | wc -l` = 0 and `grep -rl "1.9.0-RC3" --include=pom.xml . | wc -l` = 11.
- [ ] **Step 4: Re-test** — `mvn -q test` → BUILD SUCCESS.
- [ ] **Step 5: Commit + tag**
```
git add -A
git commit -m "release: 1.9.0-RC3 — HMAC envelope authentication

Opt-in HMAC-SHA256 signing of cross-node envelopes (anti-forgery) via a
transport-layer MessageAuthenticator (codec-agnostic). Gated off by default;
NoOp strips H1 for clean rollout; permissive migration mode. Part of the 1.9.0
cycle; final 1.9.0 when the cycle completes.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git tag -a v1.9.0-RC3 -m "v1.9.0-RC3 — HMAC envelope authentication (1.9.0 cycle, in development)"
```
- [ ] **Step 6: Report** — RC3 cut locally (not pushed/deployed). The 1.9.0 cycle continues until the user says it's complete.

---

## Notes for the implementer
- **Zero change when disabled:** NoOp `wrap` = identity, so unsigned wire is byte-identical to RC2; existing broker tests use the compat constructors (→ NoOp). Confirm via the unchanged `RedisIntegrationTest`/`ReliableBroadcastIntegrationTest`.
- **No SPI breakage:** `MessageAuthenticator` is NEW (`@ConditionalOnMissingBean`); broker constructors gain an overload, no existing signature changes; no envelope schema change.
- **Mac is not thread-safe** — `HmacMessageAuthenticator` uses a `ThreadLocal<Mac>`; do not share a single `Mac`.
- **Enable cluster-wide** (shared secret); the 3-phase rollout (disabled → permissive → strict) avoids mid-rollout loss.
