# RC15 Test/IT Coverage + RC14 Nits — Implementation Plan

> **For agentic workers:** 3 parallel implementer tracks (file-disjoint), then controller-side cut.

**Goal:** Ship Q1+Q2+Q3 (NATS reliable IT gaps) + P2+P3+P4 (NATS-KV IT polish + L8 IT doc) +
R1+R2 (sender log + javadoc) as `1.9.0-RC15`.

**Spec:** `docs/superpowers/specs/2026-06-07-rc15-test-coverage.md`.

**Branch:** `feature/1.9.0-rc15-test-coverage` (off RC14 `3e4378b`).

---

## File Structure

| Path | Action |
|---|---|
| `netty-spring-websocket-cluster/src/test/java/.../cluster/NatsJetStreamReliableIntegrationTest.java` | Modify (Track A: Q1+Q2+Q3) |
| `netty-spring-websocket-cluster/src/test/java/.../cluster/NatsKvIntegrationTest.java` | Modify (Track B: P2+P4) |
| `netty-spring-websocket-cluster/src/main/java/.../cluster/ClusterMessageSender.java` | Modify (Track C: R1+R2) |
| `netty-spring-websocket-cluster/src/test/java/.../cluster/ReliableBroadcastIntegrationTest.java` | Modify (Track C: P3) |
| `docs/release-notes-1.9.0.md` | Modify (header + §⑳) |
| `docs/pre-ga-audit-backlog.md` | Modify (strike Q1-Q3, P2-P4, R1, R2) |
| 11 POMs | Modify (RC14 → RC15) |

---

## Track A: Q1+Q2+Q3 — NATS reliable IT

**File:** `NatsJetStreamReliableIntegrationTest.java`

### Q1 — DEGRADED→ACTIVE recovery

- [ ] **Step 1:** Open the file; find the existing DEGRADED-on-kill test (likely named
  `reliableBroker_*degraded*` or similar — grep for `DEGRADED` and `killContainerCmd`).

- [ ] **Step 2:** After the existing `assertEquals(BrokerState.DEGRADED, broker.state())`, append:

```java
// Q1: assert recovery — restart container + poll for ACTIVE within 30s
container.start();
long activeDeadline = System.currentTimeMillis() + 30_000;
while (broker.state() != BrokerState.ACTIVE && System.currentTimeMillis() < activeDeadline) {
    Thread.sleep(200);
}
assertEquals(BrokerState.ACTIVE, broker.state(),
    "broker should recover to ACTIVE after NATS container restart");
```

If `container` is `@Container`-scoped per-class, sequencing matters; if per-method, this is safe.
Check the existing pattern in the file before placing.

### Q2 — Positive HMAC round-trip

- [ ] **Step 3:** Find the existing HMAC rejection test (`reliableBroker_hmacRejection*`).

- [ ] **Step 4:** Add a new test method that mirrors the rejection setup but uses **matching** secrets
  for both publisher and subscriber:

```java
@Test
void reliableBroker_hmacRoundTripWithMatchingSecrets() throws Exception {
    String secret = "shared-secret-for-rc15-test-32chars";   // ≥32 chars
    HmacMessageAuthenticator auth = new HmacMessageAuthenticator(secret);
    // construct two brokers with the SAME authenticator (or two with the same secret)
    // publish from broker A; assert broker B's listener received the message
    CompletableFuture<ClusterEnvelope> received = new CompletableFuture<>();
    NatsJetStreamReliableBroker subscriber = /* build with auth */;
    subscriber.reliableSubscribe("/ws/hmac-positive", "node-B",
        env -> received.complete(env));
    NatsJetStreamReliableBroker publisher = /* build with auth */;
    publisher.reliablePublish("/ws/hmac-positive", envelope("hello-positive"));
    ClusterEnvelope env = received.get(10, TimeUnit.SECONDS);
    assertEquals("hello-positive", payloadString(env));
    publisher.shutdown();
    subscriber.shutdown();
}
```

Match the existing IT's helper functions (`envelope(...)`, `payloadString(...)`, broker construction
pattern). Re-use what the file already has.

### Q3 — Publish during DEGRADED does not throw

- [ ] **Step 5:** Add a new test method:

```java
@Test
void reliableBroker_publishDoesNotThrowWhenDegraded() throws Exception {
    // Subscribe before kill so we have a consumer ready
    CompletableFuture<ClusterEnvelope> received = new CompletableFuture<>();
    broker.reliableSubscribe("/ws/q3", "node-Q3",
        env -> received.complete(env));

    // Kill container → wait for DEGRADED
    container.getDockerClient().killContainerCmd(container.getContainerId()).exec();
    long degradedDeadline = System.currentTimeMillis() + 15_000;
    while (broker.state() != BrokerState.DEGRADED && System.currentTimeMillis() < degradedDeadline) {
        Thread.sleep(100);
    }
    assertEquals(BrokerState.DEGRADED, broker.state());

    // Publish should NOT throw (publishAsync swallows + on-publish-failure handles it)
    assertDoesNotThrow(() -> broker.reliablePublish("/ws/q3", envelope("during-degraded")));

    // Restart, expect eventual delivery (within retention window — single message, well within MAXMSGS)
    container.start();
    long activeDeadline = System.currentTimeMillis() + 30_000;
    while (broker.state() != BrokerState.ACTIVE && System.currentTimeMillis() < activeDeadline) {
        Thread.sleep(200);
    }
    // After reconnect, the publishAsync from above may or may not have hit NATS — that's the at-least-once
    // tradeoff. If we want the message to actually be delivered eventually, publish ANOTHER one post-reconnect
    // and assert THAT one arrives.
    broker.reliablePublish("/ws/q3", envelope("post-reconnect"));
    ClusterEnvelope env = received.get(15, TimeUnit.SECONDS);
    assertNotNull(env);
}
```

**Note for implementer:** the "publish-during-DEGRADED is silently dropped" is a known semantics (spec §5.1
informational). The point of Q3 is to prove it doesn't THROW. Eventual delivery of a post-reconnect publish
proves the broker recovered. If `publishAsync` happens to retry through reconnect, the during-DEGRADED
message MAY arrive — fine either way; the test only asserts non-throw + eventual recovery.

- [ ] **Step 6:** Run: `mvn -pl netty-spring-websocket-cluster test -Dtest=NatsJetStreamReliableIntegrationTest`. All tests pass.

- [ ] **Step 7:** Commit. `git commit -m "test(cluster/nats): reliable IT covers DEGRADED→ACTIVE recovery + positive HMAC round-trip + publish-during-DEGRADED (Q1+Q2+Q3)"`

---

## Track B: P2+P4 — NATS-KV IT polish

**File:** `NatsKvIntegrationTest.java`

### P2 — `@Tag("slow")` annotation

- [ ] **Step 1:** Open the file; find `reaper_claimExpires_thenReclaimSucceeds`.

- [ ] **Step 2:** Add `@org.junit.jupiter.api.Tag("slow")` above the `@Test` annotation. Add a class-header
  comment noting the convention:

```java
/**
 * NATS JetStream KV integration tests.
 * <p>
 * Long-running tests (≥10 s) are tagged {@code @Tag("slow")} so CI profiles can optionally
 * skip them via {@code -DexcludedGroups=slow}.
 */
```

### P4 — Retry-and-assert replaces blind sleep

- [ ] **Step 3:** In the same test method, find `Thread.sleep(12_000)`. Replace the sleep + immediate
  `assertTrue(r2.tryClaim(...))` with a polling pattern:

```java
// P4: replaced blind 12s sleep with poll-until-success (bounded 15s) — safer against
// JetStream housekeeping jitter on slow CI. Bucket maxAge is 10s, so wait at least 11s before polling.
Thread.sleep(11_000);
long deadline = System.currentTimeMillis() + 4_000;
boolean won = false;
while (!won && System.currentTimeMillis() < deadline) {
    won = r2.tryClaim("expiry-target", "node-2", 5000);
    if (!won) Thread.sleep(100);
}
assertTrue(won, "after maxAge expiry, re-claim by a different reaper must succeed");
```

- [ ] **Step 4:** Run: `mvn -pl netty-spring-websocket-cluster test -Dtest=NatsKvIntegrationTest`. All tests pass.

- [ ] **Step 5:** Commit. `git commit -m "test(cluster/nats): @Tag(slow) + retry-and-assert replaces blind 12s sleep in reaper IT (P2+P4)"`

---

## Track C: P3 + R1 + R2 — Surgical polish

**Files:** `ClusterMessageSender.java` + `ReliableBroadcastIntegrationTest.java`

### P3 — L8 IT timing constant doc

- [ ] **Step 1:** Open `ReliableBroadcastIntegrationTest.java`; find `long degradedDeadline = +15000` (search for `degradedDeadline`).

- [ ] **Step 2:** Add a comment one line above:

```java
// P3: 15s = Docker killContainerCmd latency (~1s) + Lettuce channel-inactive detection
// (~1-3s default) + listener-CAS application + Testcontainers slack. Tuned empirically; raise if flakey on slow CI.
long degradedDeadline = System.currentTimeMillis() + 15_000;
```

### R1 — `topicMessage` DEGRADED-else log

- [ ] **Step 3:** Open `ClusterMessageSender.java`; find the `topicMessage` DEGRADED-else log message added
  in RC14. It should say something like `"node state is {}"`.

- [ ] **Step 4:** Modify to include `broker.state()`:

```java
log.debug("topicMessage skipped cross-node publish: node state={}, broker state={}",
    nodeManager.getState(), broker.state());
```

Match the existing log-level (likely `debug` or `trace`). Preserve the surrounding context — only the
format string and arg list change.

### R2 — `closeSession` javadoc clarifies false-on-DEGRADED

- [ ] **Step 5:** Open `ClusterMessageSender.java`; find `closeSession` method.

- [ ] **Step 6:** Update the javadoc:

```java
/**
 * Closes a WebSocket session by id. Tries local first; if not local and the cluster transport is ACTIVE,
 * looks up the owning node and dispatches a CLOSE control message.
 *
 * @return {@code true} if the close was definitely actioned (locally or via cross-node CLOSE);
 *         {@code false} otherwise. <strong>A {@code false} return is overloaded:</strong> it means
 *         EITHER no such session exists locally AND no remote owner was found, OR the cluster
 *         transport is degraded (Redis-loss grace period, broker DEGRADED) and the cross-node
 *         lookup was short-circuited. Callers cannot distinguish these cases — mirrors the L6
 *         {@link #sendMessage} semantics from 1.9.0-RC12.
 */
```

- [ ] **Step 7:** Run: `mvn -pl netty-spring-websocket-cluster test`. Full module pass.

- [ ] **Step 8:** Commit. `git commit -m "fix(cluster): L8 IT timing comment (P3) + topicMessage log includes broker state (R1) + closeSession javadoc clarifies false-on-DEGRADED (R2)"`

---

## Task: Release notes + backlog cleanup

- [ ] **Step 1:** `docs/release-notes-1.9.0.md` — update header line: append `;RC15 测试覆盖加固 (Q1-Q3 + P2-P4 + R1 + R2)`.

- [ ] **Step 2:** Insert a new §⑳ section before the existing config-reference section. Mirror the §⑲ pattern:

```markdown
### ⑳ RC15 测试覆盖加固 / RC15 test/IT coverage hardening

*Since V1.9.0-RC15.* 8 项 backlog 落地（无 SPI 变更、无线格式变更、无新配置键、无行为变更——纯测试 / 日志 / 文档）：

- **Q1 — NATS reliable IT 覆盖 DEGRADED→ACTIVE 恢复**：kill→DEGRADED 后 container.start() + 30 s 轮询 ACTIVE。
- **Q2 — NATS reliable HMAC 正向 round-trip IT**：匹配密钥下消息抵达；与 Q5(RC13) 反向拒绝 IT 互补。
- **Q3 — NATS reliable DEGRADED-publish-doesn't-throw IT**：验证 spec §5.1 informational 语义。
- **P2 — NATS-KV reaper IT `@Tag("slow")`**：12 s+ 测试可被 CI profile 选择性跳过（`-DexcludedGroups=slow`）。
- **P3 — L8 IT `degradedDeadline=15s` 由来注释**：Docker kill + Lettuce channel-inactive + listener-CAS budget。
- **P4 — NATS-KV reaper IT 轮询取代盲等**：`Thread.sleep(12_000)` → `Thread.sleep(11_000) + poll-until-success (max 4 s)`，对慢 CI 更稳。
- **R1 — `ClusterMessageSender.topicMessage` DEGRADED-else 日志包含 `broker.state()`**：redis-loss 宽限期内 node ACTIVE 但 broker DEGRADED 的真相在日志中可见。
- **R2 — `ClusterMessageSender.closeSession` javadoc 明确 false-on-DEGRADED 重载**：caller 无法区分「无 session」与「transport degraded」，与 RC12 L6 sendMessage 语义对齐。

#### 向后兼容

纯测试 / 日志格式 / javadoc 变更。R1 日志为加性追加（原有 "node state=" 前缀保留），R2 仅澄清既有语义。0 行为变更。
```

- [ ] **Step 3: Test count line** — bump 438 → 440 after final reactor.

- [ ] **Step 4: `docs/pre-ga-audit-backlog.md`** — strike Q1, Q2, Q3, P2, P3, P4, R1, R2 (move to "Fixed in
  RC15" section). After RC15, **only L1 remains open**.

- [ ] **Step 5:** Commit. `git commit -m "docs(cluster): RC15 release notes §⑳ + backlog cleanup"`

---

## Task: Final reactor + RC15 cut + finish

- [ ] **Step 1:** Bump POMs:

```bash
sed -i "s/1\.9\.0-RC14/1.9.0-RC15/g" pom.xml demo-netty-web-spring-boot-starter/pom.xml netty-spring-boot-autoconfigure/pom.xml netty-spring-web/pom.xml netty-spring-webmvc/pom.xml netty-spring-websocket/pom.xml netty-spring-websocket-cluster/pom.xml netty-web-spring-boot-starter/pom.xml netty-webmvc-spring-boot-starter/pom.xml netty-websocket-spring-boot-starter/pom.xml netty-websocket-cluster-spring-boot-starter/pom.xml
```

- [ ] **Step 2:** `mvn test` — BUILD SUCCESS, **440 tests**.

- [ ] **Step 3:** Update release-notes test count to 440.

- [ ] **Step 4:** Commit pom + count. `git commit -m "release: 1.9.0-RC15 - test/IT coverage hardening (Q1-Q3 + P2-P4 + R1 + R2)"`

- [ ] **Step 5:** Finish branch: FF-merge to master + tag `v1.9.0-RC15` + delete branch. STOP before push.

---

## Self-Review

- **Spec coverage:** Q1+Q2+Q3 in Track A; P2+P4 in Track B; P3+R1+R2 in Track C.
- **Parallel safety:** A, B, C all file-disjoint — no overlapping edits. Safe for parallel dispatch.
- **Tests delta:** +2 new (Q2+Q3); Q1 extends existing, others are non-test changes.
- **Risk:** Q1 + Q3 share the same Testcontainers container — implementer must verify `@Container` scoping
  to ensure Q3's kill doesn't bleed into other tests (or extract to its own container).
- **No SPI / wire / config / behavior change.**
