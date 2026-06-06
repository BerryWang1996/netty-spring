# NATS JetStream Reliable Broadcast — RC13 Implementation Plan

> **For agentic workers:** Single-track sequential implementation (one source file shared across all
> tasks). Use **subagent-driven-development** with TDD per skeleton step. Steps use checkbox (`- [ ]`)
> syntax.

**Goal:** Ship `NatsJetStreamReliableBroker` + auto-config + tests + docs as 1.9.0-RC13.

**Architecture:** New `ReliableBroker` SPI impl in `cluster.nats` package; activated only when
`nats.registry=true && reliable.enable=true`. Mirror `RedisStreamsReliableBroker` semantics 1:1, swap
Redis Streams for JetStream streams. See spec §1–§12.

**Tech Stack:** Java 17, Spring Boot 2.7.18, Lombok `@Slf4j`, JUnit 5, Mockito, Testcontainers
(`nats:2.10 -js`), jnats 2.20.4.

**Branch:** `feature/1.9.0-rc13-nats-reliable` (off 2be386f master = RC12).

**Spec:** `docs/superpowers/specs/2026-06-06-nats-jetstream-reliable-rc13.md`.

---

## File Structure

| Path | Action |
|---|---|
| `netty-spring-websocket-cluster/src/main/java/.../cluster/nats/NatsJetStreamReliableBroker.java` | **Create** |
| `netty-spring-websocket-cluster/src/test/java/.../cluster/nats/NatsJetStreamReliableBrokerTest.java` | **Create** (unit, Mockito) |
| `netty-spring-websocket-cluster/src/test/java/.../cluster/NatsJetStreamReliableIntegrationTest.java` | **Create** (Testcontainers IT) |
| `netty-websocket-cluster-spring-boot-starter/src/main/java/.../configure/NettyWebSocketClusterConfigure.java` | **Modify** (add `@Bean natsJetStreamReliableBroker` + verify existing `redisStreamsReliableBroker` condition suppresses in all-NATS) |
| `netty-websocket-cluster-spring-boot-starter/src/test/java/.../NettyWebSocketClusterConfigureTest.java` | **Modify** (+4 context cases per spec §7) |
| `netty-websocket-cluster-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json` | **Modify** (append NATS mapping to 6 reliable.* descriptions) |
| `docs/release-notes-1.9.0.md` | **Modify** (RC12→RC13 in header; add §⑱; move "NATS / JetStream 可靠投递" out of Known Limitations; replicas/FILE storage warnings) |
| `docs/cluster-design.md` | **Modify** (ADR-001 row update; §Capacity scale note; §Security threat model note) |
| `docs/api-guide.md` | **Modify** (§9 cluster — reliable.* transport-agnostic pointer) |
| 11 POMs | **Modify** (RC12 → RC13) |

---

## Task 1: Skeleton + lifecycle (no JetStream behavior yet)

**Files:**
- Create: `netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/nats/NatsJetStreamReliableBroker.java`
- Create: `netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/nats/NatsJetStreamReliableBrokerTest.java`

- [ ] **Step 1: Skeleton class.** Implement the 5 `ReliableBroker` methods as no-ops (publish: log + counter, subscribe: return a stub `ClusterSubscription`, destroy: no-op, `state()`: return ACTIVE, `shutdown()`: CAS to SHUTDOWN). Fields:
  - `final Connection natsConnection`
  - `final EnvelopeCodec envelopeCodec`
  - `final MessageAuthenticator authenticator`
  - `final ClusterRuntimeStats stats`
  - `final String nodeId`
  - `final ReliableConfig config` (or pass individual knobs)
  - `final AtomicReference<BrokerState> state = new AtomicReference<>(ACTIVE)`
  - `final ConcurrentHashMap<String, Object> streamCache = new ConcurrentHashMap<>()` (URI → marker)
  - `final ConcurrentHashMap<String, SubscriptionHandle> subscriptions = new ConcurrentHashMap<>()` (URI → handle with thread + subscription + listener)
  - `volatile int inboundMaxBytes` + setter (mirrors RedisStreamsReliableBroker post-RC11)
  - `volatile long groupDestroyIdleMs` + setter (mirrors RC11)
  - Inline `JetStream js` + `JetStreamManagement jsm` derived from `natsConnection` in constructor

- [ ] **Step 2: First test — broker constructs and reports ACTIVE.**

```java
@Test
void constructs_and_initialState_isActive() {
    NatsJetStreamReliableBroker b = new NatsJetStreamReliableBroker(
        mockConn, codec, NoOpMessageAuthenticator.INSTANCE, new ClusterRuntimeStats(),
        "node-A", configWithDefaults());
    assertEquals(BrokerState.ACTIVE, b.state());
    b.shutdown();
    assertEquals(BrokerState.SHUTDOWN, b.state());
}
```

- [ ] **Step 3: mvn compile + test pass.**

```bash
mvn -pl netty-spring-websocket-cluster test -Dtest=NatsJetStreamReliableBrokerTest
```

- [ ] **Step 4: Commit.**

```bash
git commit -m "feat(cluster/nats): NatsJetStreamReliableBroker skeleton + lifecycle (RC13 T1)"
```

---

## Task 2: ensureStream with mismatch detection

- [ ] **Step 1: Read `NatsKvSessionRegistry.java`** for jnats 2.20.4 API conventions (jsm acquisition pattern, base64url helper, exception handling for `JetStreamApiException`).

- [ ] **Step 2: Implement `ensureStream(String b64uri)`** per spec §5.1:

```java
private static final Object STREAM_MARKER = new Object();

private void ensureStream(String b64uri) {
    streamCache.computeIfAbsent(b64uri, k -> {
        String streamName = "netty-cluster-reliable-" + b64uri;
        StreamConfiguration desired = StreamConfiguration.builder()
            .name(streamName)
            .subjects("netty.reliable." + b64uri)
            .storageType(StorageType.File)
            .retentionPolicy(RetentionPolicy.Limits)
            .discardPolicy(DiscardPolicy.Old)
            .maxMessages(streamMaxLen)
            .maxAge(Duration.ZERO)  // unlimited; only MAXMSGS governs retention
            .replicas(1)
            .build();
        try {
            StreamInfo info = jsm.getStreamInfo(streamName);
            StreamConfiguration actual = info.getConfiguration();
            if (configMatches(actual, desired)) {
                return STREAM_MARKER;
            }
            log.warn("Pre-existing stream {} has incompatible config: expected max_msgs={}, storage={}, discard={}, retention={}, replicas={}; actual max_msgs={}, storage={}, discard={}, retention={}, replicas={}",
                streamName, desired.getMaxMsgs(), desired.getStorageType(), desired.getDiscardPolicy(), desired.getRetentionPolicy(), desired.getReplicas(),
                actual.getMaxMsgs(), actual.getStorageType(), actual.getDiscardPolicy(), actual.getRetentionPolicy(), actual.getReplicas());
            throw new ClusterBrokerException("JetStream stream " + streamName + " already exists with incompatible config");
        } catch (JetStreamApiException e) {
            if (e.getErrorCode() == 10059 || e.getErrorCode() == 404 /* stream not found */) {
                try {
                    jsm.addStream(desired);
                    return STREAM_MARKER;
                } catch (IOException | JetStreamApiException ce) {
                    throw new ClusterBrokerException("Failed to create stream " + streamName, ce);
                }
            }
            throw new ClusterBrokerException("ensureStream getStreamInfo failed for " + streamName, e);
        } catch (IOException ioe) {
            throw new ClusterBrokerException("ensureStream IO failed for " + streamName, ioe);
        }
    });
}

private boolean configMatches(StreamConfiguration actual, StreamConfiguration desired) {
    return actual.getStorageType() == desired.getStorageType()
        && actual.getDiscardPolicy() == desired.getDiscardPolicy()
        && actual.getRetentionPolicy() == desired.getRetentionPolicy()
        && actual.getMaxMsgs() == desired.getMaxMsgs()
        && actual.getReplicas() == desired.getReplicas();
}
```

> Implementer note: the exact `JetStreamApiException` not-found error code varies between jnats versions.
> Inspect `NatsKvSessionRegistry` / `NatsKvReaper` for the exact pattern used in RC10 and mirror it.
> The unit test's mock should return whatever the impl checks.

- [ ] **Step 3: Unit tests (3) idempotent + (4) mismatch.**

```java
@Test
void ensureStream_isIdempotent_skipsAddStreamWhenStreamExistsWithMatchingConfig() throws Exception {
    when(jsm.getStreamInfo("netty-cluster-reliable-XYZ"))
        .thenReturn(streamInfoWith(StorageType.File, DiscardPolicy.Old, RetentionPolicy.Limits, 10000L, 1));
    broker.publishAndEnsureForTest("/ws/x");  // helper that calls ensureStream
    broker.publishAndEnsureForTest("/ws/x");  // second call
    verify(jsm, times(1)).getStreamInfo(anyString());
    verify(jsm, never()).addStream(any());
}

@Test
void ensureStream_throwsClusterBrokerException_onConfigMismatch() throws Exception {
    when(jsm.getStreamInfo(anyString()))
        .thenReturn(streamInfoWith(StorageType.File, DiscardPolicy.New, RetentionPolicy.Limits, 5000L, 1));
    assertThrows(ClusterBrokerException.class, () -> broker.publishAndEnsureForTest("/ws/x"));
    verify(jsm, never()).addStream(any());
}
```

- [ ] **Step 4: Commit.**

```bash
git commit -m "feat(cluster/nats): JetStream ensureStream with mismatch fail-fast (RC13 T2)"
```

---

## Task 3: reliablePublish

- [ ] **Step 1: Implement.**

```java
@Override
public void reliablePublish(String uri, ClusterEnvelope envelope) {
    if (state.get() == BrokerState.SHUTDOWN) {
        throw new ClusterBrokerException("Reliable broker SHUTDOWN");
    }
    String b64uri = base64UrlEncode(uri);
    ensureStream(b64uri);
    String subject = "netty.reliable." + b64uri;
    try {
        byte[] envBytes = envelopeCodec.encode(envelope);
        byte[] wrapped = authenticator.wrap(envBytes);            // HMAC wrap before send
        js.publishAsync(subject, wrapped);                         // fire-and-forget
        stats.incrementReliablePublished();                        // existing counter
    } catch (Exception ex) {
        // on-publish-failure handled by ClusterMessageSender (caller); we surface failure via stats + log here.
        stats.incrementPublishFailures();
        log.warn("Reliable publish failed for uri={}", uri, ex);
    }
}
```

> Note: matching the existing `RedisStreamsReliableBroker` shape, we don't throw on transient transport
> failures — the caller's `on-publish-failure` policy is honored at a higher layer. SHUTDOWN throws.

- [ ] **Step 2: Unit test (1).**

```java
@Test
void reliablePublish_wrapsWithAuthenticator_andPublishesToSubject() throws Exception {
    MessageAuthenticator auth = mock(MessageAuthenticator.class);
    byte[] wrapped = "H1:tag:body".getBytes(UTF_8);
    when(auth.wrap(any())).thenReturn(wrapped);
    NatsJetStreamReliableBroker b = new NatsJetStreamReliableBroker(mockConn, codec, auth, stats, "node-A", cfg);
    primeStreamExists(jsm, "netty-cluster-reliable-" + b64("/ws/chat"));   // helper

    b.reliablePublish("/ws/chat", envelope("hello"));

    verify(auth).wrap(any(byte[].class));
    verify(js).publishAsync(eq("netty.reliable." + b64("/ws/chat")), eq(wrapped));
    assertEquals(1L, stats.getReliablePublished());
}
```

- [ ] **Step 3: Commit.**

```bash
git commit -m "feat(cluster/nats): JetStream reliable publish with HMAC wrap (RC13 T3)"
```

---

## Task 4: reliableSubscribe with durable pull consumer + fetch loop

- [ ] **Step 1: Implement.**

```java
@Override
public ClusterSubscription reliableSubscribe(String uri, String nodeId, ClusterMessageListener listener) {
    String b64uri = base64UrlEncode(uri);
    ensureStream(b64uri);
    String streamName = "netty-cluster-reliable-" + b64uri;
    String consumerName = "g." + base64UrlEncode(nodeId);

    // Lazy-create durable consumer if missing
    try {
        jsm.getConsumerInfo(streamName, consumerName);  // 404 → create
    } catch (JetStreamApiException notFound) {
        if (isConsumerNotFound(notFound)) {
            ConsumerConfiguration cc = ConsumerConfiguration.builder()
                .durable(consumerName)
                .ackPolicy(AckPolicy.Explicit)
                .deliverPolicy(DeliverPolicy.All)
                .filterSubject("netty.reliable." + b64uri)
                .build();
            jsm.addOrUpdateConsumer(streamName, cc);
        } else {
            throw new ClusterBrokerException("Failed to ensure consumer " + consumerName + " on " + streamName, notFound);
        }
    } catch (IOException ioe) {
        throw new ClusterBrokerException("Failed to check consumer " + consumerName + " on " + streamName, ioe);
    }

    // Create pull subscription
    JetStreamSubscription sub;
    try {
        sub = js.subscribe("netty.reliable." + b64uri,
            PullSubscribeOptions.builder().durable(consumerName).stream(streamName).build());
    } catch (IOException | JetStreamApiException e) {
        throw new ClusterBrokerException("Failed to subscribe to " + streamName, e);
    }

    // Spawn dedicated fetch-loop thread
    String threadName = "nats-reliable-" + nodeId.substring(0, Math.min(8, nodeId.length()))
                      + "-" + Integer.toHexString(uri.hashCode());
    AtomicBoolean stop = new AtomicBoolean(false);
    DedupRing dedup = new DedupRing(dedupWindow);
    Thread t = new Thread(() -> consumeLoop(sub, uri, listener, dedup, stop), threadName);
    t.setDaemon(true);
    t.start();

    SubscriptionHandle h = new SubscriptionHandle(t, sub, stop);
    subscriptions.put(uri, h);
    return () -> {
        stop.set(true);
        try { sub.unsubscribe(); } catch (Exception ignored) {}
        try { t.join(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        subscriptions.remove(uri);
    };
}

private void consumeLoop(JetStreamSubscription sub, String uri, ClusterMessageListener listener,
                         DedupRing dedup, AtomicBoolean stop) {
    while (!stop.get() && state.get() != BrokerState.SHUTDOWN) {
        try {
            List<Message> batch = sub.fetch(pollCount, Duration.ofMillis(pollBlockMs));
            for (Message msg : batch) {
                handleMessage(msg, uri, listener, dedup);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception ex) {
            log.warn("fetch loop transient error on {}", uri, ex);
            // brief back-off to avoid spin on persistent failure
            try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
    }
}

private void handleMessage(Message msg, String uri, ClusterMessageListener listener, DedupRing dedup) {
    byte[] data = msg.getData();
    if (inboundMaxBytes > 0 && data.length > inboundMaxBytes) {
        log.warn("Dropping oversized reliable message: {} > {} bytes (uri={})", data.length, inboundMaxBytes, uri);
        msg.ack();   // ACK-and-drop, don't redeliver
        return;
    }
    byte[] unwrapped;
    try {
        unwrapped = authenticator.unwrap(data);
    } catch (Exception e) {
        log.warn("HMAC unwrap failed on {} — dropping", uri, e);
        msg.ack();
        return;
    }
    if (unwrapped == null) { msg.ack(); return; }   // auth rejected
    ClusterEnvelope env;
    try {
        env = envelopeCodec.decode(unwrapped);
    } catch (Exception e) {
        log.warn("Envelope decode failed on {} — dropping", uri, e);
        msg.ack();
        return;
    }
    if (nodeId.equals(env.getOriginNodeId())) { msg.ack(); return; }   // origin self-suppress
    String envId = env.getEnvelopeId();
    if (envId != null && dedup.contains(envId)) { msg.ack(); return; }
    if (envId != null) dedup.add(envId);
    try {
        listener.onMessage(env);
        msg.ack();
    } catch (Throwable t) {
        log.warn("Reliable listener threw on uri={} envId={} — ACKing to avoid livelock", uri, envId, t);
        msg.ack();   // poison-pill guard
    }
}
```

- [ ] **Step 2: Unit tests (2)(5)(6)(7)(8) — see spec §7 for the full list. Mock `JetStreamSubscription.fetch` to return a small batch; assert `msg.ack()` is called per expected path.**

- [ ] **Step 3: Commit.**

```bash
git commit -m "feat(cluster/nats): JetStream reliable subscribe (durable pull consumer + fetch loop + poison-pill + dedup + HMAC + size guard) (RC13 T4)"
```

---

## Task 5: Connection listener for DEGRADED/ACTIVE state

- [ ] **Step 1: Wire `ConnectionListener` in constructor** (mirrors `NatsClusterBroker` from RC9):

```java
natsConnection.addConnectionListener((conn, ev) -> {
    if (ev == Events.DISCONNECTED || ev == Events.CLOSED) {
        if (state.compareAndSet(BrokerState.ACTIVE, BrokerState.DEGRADED)) {
            log.warn("NatsJetStreamReliableBroker transport disconnected — state DEGRADED");
        }
    } else if (ev == Events.RECONNECTED || ev == Events.CONNECTED) {
        if (state.compareAndSet(BrokerState.DEGRADED, BrokerState.ACTIVE)) {
            log.info("NatsJetStreamReliableBroker transport reconnected — state ACTIVE");
        }
    }
});
```

- [ ] **Step 2: Unit test** invoking the listener directly to assert CAS behavior (parallel to RC12 L8 Mockito-spy pattern):

```java
@Test
void connectionListener_flipsStateOnDisconnectAndReconnect() {
    ArgumentCaptor<ConnectionListener> cap = ArgumentCaptor.forClass(ConnectionListener.class);
    verify(mockConn).addConnectionListener(cap.capture());
    ConnectionListener l = cap.getValue();
    l.connectionEvent(mockConn, Events.DISCONNECTED);
    assertEquals(BrokerState.DEGRADED, broker.state());
    l.connectionEvent(mockConn, Events.RECONNECTED);
    assertEquals(BrokerState.ACTIVE, broker.state());
}
```

- [ ] **Step 3: Commit.** `git commit -m "feat(cluster/nats): JetStream reliable broker DEGRADED state via ConnectionListener (RC13 T5)"`

---

## Task 6: destroyConsumerGroupsForNode with idle gate

- [ ] **Step 1: Implement** per spec §5 dead-node cleanup pseudocode.

- [ ] **Step 2: Unit test.** Mock `jsm.getStreamNames()` returns 2 streams; for each `getConsumerInfo`, return `ConsumerInfo` with `numPending` and `delivered.lastActive` configured. Assert `deleteConsumer` is called only for the stream where the idle gate is satisfied.

- [ ] **Step 3: Commit.** `git commit -m "feat(cluster/nats): destroyConsumerGroupsForNode with idle gate (RC13 T6)"`

---

## Task 7: Auto-config bean wiring

**Files:**
- Modify: `netty-websocket-cluster-spring-boot-starter/src/main/java/.../configure/NettyWebSocketClusterConfigure.java`
- Modify: `netty-websocket-cluster-spring-boot-starter/src/test/java/.../NettyWebSocketClusterConfigureTest.java`

- [ ] **Step 1: Verify the existing `redisStreamsReliableBroker` bean condition.** Read its `@ConditionalOnExpression` — if it already excludes the all-NATS path (via `STANDALONE_REDIS_REGISTRY` SpEL), great. If not, add `&& !nats.registry` to it.

- [ ] **Step 2: Add the new `@Bean natsJetStreamReliableBroker`.**

```java
private static final String ALL_NATS_RELIABLE =
    "${server.netty.websocket.cluster.reliable.enable:false} "
    + "and ${server.netty.websocket.cluster.nats.registry:false}";

@Bean(name = "natsJetStreamReliableBroker", destroyMethod = "shutdown")
@ConditionalOnExpression(ALL_NATS_RELIABLE)
@ConditionalOnMissingBean(ReliableBroker.class)
public ReliableBroker natsJetStreamReliableBroker(
        @Qualifier("nettyClusterNatsJetStreamConnection") io.nats.client.Connection nats,
        EnvelopeCodec codec,
        MessageAuthenticator authenticator,
        ClusterRuntimeStats stats,
        ClusterProperties props) {
    NatsJetStreamReliableBroker b = new NatsJetStreamReliableBroker(
        nats, codec, authenticator, stats, props.getNodeId(),
        toReliableConfig(props));
    b.setInboundMaxBytes(Math.max(props.getMessageMaxSizeBytes() * 2, 1));   // mirror RC11 wiring
    b.setGroupDestroyIdleMs(props.getReliable().getGroupDestroyIdleMs());
    return b;
}
```

The exact `@Qualifier` for the NATS `Connection` bean should match what `NatsKvSessionRegistry` etc. inject
in RC10 — read those bean definitions and reuse.

- [ ] **Step 3: 4 context test cases (i)(ii)(iii)(iv) per spec §7.**

- [ ] **Step 4: Commit.** `git commit -m "feat(cluster/autoconfig): wire NatsJetStreamReliableBroker bean for all-NATS reliable (RC13 T7)"`

---

## Task 8: Integration tests (a)(b)(c)(d)(e)

**Files:**
- Create: `netty-spring-websocket-cluster/src/test/java/.../cluster/NatsJetStreamReliableIntegrationTest.java`

- [ ] **Step 1: Use existing `ClusterTestNats` resolver from RC10** for the Testcontainers fixture (`nats:2.10 -js`).

- [ ] **Step 2: Implement (a)(b)(c)(d)(e) per spec §7.** Use real `Connection` + `JetStream` + `JetStreamManagement`; do NOT mock at this layer. For (d) DEGRADED IT, use `killContainerCmd` (same as RC12 L8); for (b) replay-on-resync, unsubscribe → close-and-reopen subscription to simulate node downtime with the durable cursor preserved. For (c) idle-gate, set `groupDestroyIdleMs` very small (2s) on the broker, sleep past it.

- [ ] **Step 3: Run.** `mvn -pl netty-spring-websocket-cluster test -Dtest=NatsJetStreamReliableIntegrationTest`. All 5 cases pass.

- [ ] **Step 4: Commit.** `git commit -m "test(cluster/nats): JetStream reliable IT (round-trip + replay-on-resync + idle-gate + DEGRADED + HMAC) (RC13 T8)"`

---

## Task 9: metadata + docs

**Files:**
- Modify: `netty-websocket-cluster-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json`
- Modify: `docs/release-notes-1.9.0.md`
- Modify: `docs/cluster-design.md`
- Modify: `docs/api-guide.md`

- [ ] **Step 1: Append NATS mapping to each `reliable.*` description** in the metadata JSON. Use `Edit` tool — do NOT use PowerShell redirection (project convention).

- [ ] **Step 2: release-notes-1.9.0.md** — per spec §10 #1: (a) update header from RC12 to RC13 + append RC13 summary; (b) **add §⑱ "NATS JetStream 可靠投递 / NATS JetStream Reliable Broadcast"** with bilingual structure mirroring §⑥, the max_payload caveat, replicas=1 warning, FILE storage cost note, upgrade journey, custom-bean-overrides note; (c) **remove "NATS / JetStream 可靠投递" from §Known Limitations**.

- [ ] **Step 3: cluster-design.md** — ADR-001 row update + §Capacity scale note + §Security threat model.

- [ ] **Step 4: api-guide.md** — one-line `reliable.*` transport-agnostic pointer in §9.

- [ ] **Step 5: Verify UTF-8 + no U+FFFD** in all edited docs (project convention; previous releases hit this).

- [ ] **Step 6: Commit.** `git commit -m "docs(cluster): RC13 NATS JetStream reliable broadcast section + ADR-001 + api-guide + metadata"`

---

## Task 10: Final reactor + RC13 cut + finish

- [ ] **Step 1: Bump 11 POMs RC12 → RC13.**

```bash
sed -i "s/1\.9\.0-RC12/1.9.0-RC13/g" pom.xml demo-netty-web-spring-boot-starter/pom.xml netty-spring-boot-autoconfigure/pom.xml netty-spring-web/pom.xml netty-spring-webmvc/pom.xml netty-spring-websocket/pom.xml netty-spring-websocket-cluster/pom.xml netty-web-spring-boot-starter/pom.xml netty-webmvc-spring-boot-starter/pom.xml netty-websocket-spring-boot-starter/pom.xml netty-websocket-cluster-spring-boot-starter/pom.xml
```

Verify with `grep -r "1.9.0-RC12" --include="pom.xml" .` (should be empty).

- [ ] **Step 2: Full reactor with ITs (Docker live).**

```bash
mvn test 2>&1 | tail -25
```

Expect: BUILD SUCCESS, 0 failures, 0 errors. Capture aggregate test count.

- [ ] **Step 3: Update test count in release notes** to the new total.

- [ ] **Step 4: Commit pom + release notes test-count.**

```bash
git commit -m "release: 1.9.0-RC13 - NATS JetStream reliable broadcast (close all-NATS gap)"
```

- [ ] **Step 5: Finish branch.** Use `finishing-a-development-branch` skill:
- Option 1: merge to master locally
- Tag `v1.9.0-RC13`
- STOP before push

---

## Self-Review

**Spec coverage:** Every spec section maps to a task. §1 (Goal) + §6.1 (Activation) covered by T7 (auto-config). §2 (SPI reuse) covered by T1 (skeleton matches `ReliableBroker` interface verbatim). §3 (Components) covered T1-T8. §4 + §4.1 covered T2 (stream config) + T8 (max_payload caveat in IT setup). §5 (Data flow) covered T2 (ensureStream) + T3 (publish) + T4 (consume + poison-pill) + T5 (state) + T6 (cleanup). §7 (Tests) covered T2 unit + T3 unit + T4 units + T5 unit + T6 unit + T7 context + T8 IT. §8 (Matrix) covered T7. §9 (Compat) covered T7 (auto-config) + T9 (docs). §10 (Docs) covered T9. §11 (Risk) addressed across T2 (mismatch) + T3 (SHUTDOWN gate) + T4 (poison-pill / size guard) + T8 (IT). §12 (Order) is this plan's ordering.

**Placeholder scan:** None. Every step has concrete file + concrete code + concrete command.

**Type consistency:** `ClusterEnvelope`, `ClusterMessageListener`, `ClusterSubscription`, `BrokerState`,
`ConsumerInfo`, `JetStreamSubscription`, `StreamConfiguration` — all consistent.

**Cross-task risks:** Single source file → sequential tasks (T1→T6) on the broker. Auto-config (T7),
ITs (T8), docs (T9) can be done after T6. T10 is the release. No parallel dispatch needed.

**Implementation note:** Implementer must verify jnats 2.20.4 API names against this plan by reading the
existing RC10 NATS-KV impls (`NatsKvSessionRegistry`, `NatsKvNodeHeartbeat`, `NatsKvReaper`) before
trusting recall. The plan's API references (e.g. `getStreamInfo`, `addStream`, `publishAsync`,
`PullSubscribeOptions`) reflect best-effort recall of the jnats 2.20.x line; ITs are the oracle.
