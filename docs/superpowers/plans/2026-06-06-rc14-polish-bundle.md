# RC14 Polish Bundle — Implementation Plan

> **For agentic workers:** Single-track sequential implementer + TDD per task. Use **subagent-driven-development**.

**Goal:** Ship P1 + P5 + P6 + Q5 + Q6 + Q7 (6 small polish items) as `1.9.0-RC14`.

**Spec:** `docs/superpowers/specs/2026-06-06-rc14-polish-bundle.md`.

**Branch:** `feature/1.9.0-rc14-polish-bundle` (off RC13 master at 66089c0).

---

## File Structure

| Path | Action |
|---|---|
| `netty-spring-websocket-cluster/src/main/java/.../cluster/ClusterMessageSender.java` | Modify (P1) |
| `netty-spring-websocket-cluster/src/test/java/.../cluster/ClusterMessageSenderTest.java` | Modify (P1 tests) |
| `netty-spring-websocket-cluster/src/main/java/.../cluster/nats/NatsJetStreamReliableBroker.java` | Modify (Q5) |
| `netty-spring-websocket-cluster/src/test/java/.../cluster/nats/NatsJetStreamReliableBrokerTest.java` | Modify (Q5 test) |
| `netty-spring-websocket-cluster/src/main/java/.../cluster/nats/NatsKvSessionRegistry.java` | Modify (P5 comment) |
| `netty-spring-websocket-cluster/src/main/java/.../cluster/redis/RedisPubSubBroker.java` | Modify (P5 import style) |
| `netty-spring-websocket-cluster/src/test/java/.../cluster/ClusterNodeManagerReliabilityTest.java` | Modify (P6 timing) |
| `docs/superpowers/specs/2026-06-06-nats-jetstream-reliable-rc13.md` | Modify (Q6 §3, Q7 §4 table) |
| `docs/release-notes-1.9.0.md` | Modify (RC13→RC14 header + §⑲) |
| `docs/pre-ga-audit-backlog.md` | Modify (mark P1/P5/P6/Q5/Q6/Q7 as fixed in RC14; note Q4 refuted) |
| 11 POMs | Modify (RC13 → RC14) |

---

## Task 1: P1 — closeSession + topicMessage broker-state gate

- [ ] **Step 1:** Open `ClusterMessageSender.java`. Find `closeSession()` (~line 458). Find the gate `if (nodeManager.getState() == NodeState.ACTIVE)`. Change to:

```java
if (nodeManager.getState() == NodeState.ACTIVE && broker.state() == BrokerState.ACTIVE) {
```

- [ ] **Step 2:** Find `topicMessage()` in the same file. Apply the same change to the gate that triggers the remote-publish path.

- [ ] **Step 3:** Add 2 tests to `ClusterMessageSenderTest`:

```java
@Test
void closeSessionShortCircuitsRemoteWhenBrokerDegraded() throws Exception {
    InMemorySessionRegistry reg = new InMemorySessionRegistry();
    reg.register("/ws/x", "sid-remote", "node-other", Map.of()).toCompletableFuture().join();
    InMemoryBroker broker = new InMemoryBroker();
    broker.setState(BrokerState.DEGRADED);
    ClusterNodeManager nodeMgr = /* construct ACTIVE */;
    ClusterMessageSender sender = /* construct with broker+reg+nodeMgr */;
    sender.start();
    int before = reg.getLookupNodeCalls();
    sender.closeSession("sid-remote");                    // should short-circuit
    assertEquals(before, reg.getLookupNodeCalls(), "lookup must NOT be called when broker DEGRADED");
    sender.shutdown();
}

@Test
void topicMessageShortCircuitsRemoteWhenBrokerDegraded() throws Exception {
    // Analogous — assert publish() on broker not invoked, or lookup not invoked, depending on the path
}
```

(Match the existing test setup style in the file. Look at `sendMessageShortCircuitsRemoteWhenBrokerDegraded` from RC12 L6 for the exact pattern.)

- [ ] **Step 4:** `mvn -pl netty-spring-websocket-cluster test -Dtest=ClusterMessageSenderTest`. All green.

- [ ] **Step 5:** Commit. `git commit -m "fix(cluster): closeSession + topicMessage also gate on broker.state==ACTIVE (P1)"`

---

## Task 2: Q5 — Stream-name length guard

- [ ] **Step 1:** Open `NatsJetStreamReliableBroker.java`. Find `ensureStream(String b64uri)`. Right after constructing `streamName`, add:

```java
if (streamName.length() > 255) {
    throw new ClusterBrokerException("Stream name too long: " + streamName.length()
        + " > 255 (max NATS stream name length); reduce URI length (uri-b64=" + b64uri + ")");
}
```

(Place BEFORE the `jsm.getStreamInfo` call — the guard short-circuits with a clear diagnostic.)

- [ ] **Step 2:** Add unit test:

```java
@Test
void ensureStream_rejectsExcessivelyLongStreamName() throws Exception {
    // base64url-encoded URI that produces streamName.length() > 255
    // streamName prefix is "netty-cluster-reliable-" (23 chars), so b64uri must be > 232 chars
    // Raw URI bytes → base64url ratio is ~4/3; need raw URI ≥ 175 bytes
    String longUri = "/ws/" + "a".repeat(200);
    ClusterBrokerException ex = assertThrows(ClusterBrokerException.class,
        () -> broker.reliablePublish(longUri, envelope("hello")));
    assertTrue(ex.getMessage().contains("Stream name too long"),
        "expected diagnostic, got: " + ex.getMessage());
    verify(jsm, never()).getStreamInfo(anyString());
    verify(jsm, never()).addStream(any());
}
```

- [ ] **Step 3:** `mvn -pl netty-spring-websocket-cluster test -Dtest=NatsJetStreamReliableBrokerTest`. Green.

- [ ] **Step 4:** Commit. `git commit -m "fix(cluster/nats): stream-name length guard for clearer diagnostic on long URIs (Q5)"`

---

## Task 3: P5 — Style polish

- [ ] **Step 1: NatsKvSessionRegistry.java lines 157-160.** Read the existing verbose comment from RC11 L2. Condense to ≤ 2 lines while keeping the meaning (cleanup-finds-over-set rationale).

- [ ] **Step 2: RedisPubSubBroker.java line 166.** Look at the existing usage:
```java
int sz = message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
```
Change to:
```java
int sz = message.getBytes(StandardCharsets.UTF_8).length;
```
And add `import java.nio.charset.StandardCharsets;` near the top of the file.

Apply the same change to **`RedisClusterModePubSubBroker.java`** and **`RedisStreamsReliableBroker.java`** if they also inline (verify by reading).

- [ ] **Step 3:** `mvn -pl netty-spring-websocket-cluster test`. Green (no test changes, must not regress).

- [ ] **Step 4:** Commit. `git commit -m "style(cluster): condense NATS-KV register() comment + use StandardCharsets import in Redis brokers (P5)"`

---

## Task 4: P6 — Test timing margin

- [ ] **Step 1:** Open `ClusterNodeManagerReliabilityTest.shutdownAwaitsSchedulerTerminationBeforeDeregister`. Find the `latch.await(2, TimeUnit.SECONDS)` call.

- [ ] **Step 2:** Change to `latch.await(5, TimeUnit.SECONDS)`.

- [ ] **Step 3:** `mvn -pl netty-spring-websocket-cluster test -Dtest=ClusterNodeManagerReliabilityTest`. Green.

- [ ] **Step 4:** Commit. `git commit -m "test(cluster/node): bump shutdown-await latch 2s→5s for slow-CI margin (P6)"`

---

## Task 5: Q6 + Q7 — RC13 spec edits

- [ ] **Step 1: Q6 — §3 connection-bean naming.** Open `docs/superpowers/specs/2026-06-06-nats-jetstream-reliable-rc13.md`. Find §3 Components, the row about "JetStream connection acquisition reuses the existing all-NATS JetStream Connection bean". Append: `(specifically the bean qualified as @Qualifier("nettyClusterNatsKvConnection") established in RC10).`

- [ ] **Step 2: Q7 — §4 table durable-consumer name.** Find the row `Durable consumer name | g.<b64url(nodeId)> | ...`. Change to `g_<b64url(nodeId)>` with the rationale: `Mirrors Redis g:{nodeId}. NATS jnats client-validator rejects '.' in durable names, so '_' is used as separator (RC13 implementer discovery; matches code at NatsJetStreamReliableBroker.CONSUMER_PREFIX).`

- [ ] **Step 3:** Commit. `git commit -m "docs(spec): RC13 §3 explicit Connection bean qualifier + §4 g_ vs g. (Q6 + Q7)"`

---

## Task 6: Release notes + backlog cleanup

- [ ] **Step 1:** Open `docs/release-notes-1.9.0.md`. Update header line: change `RC13` to `RC14` and append `;RC14 polish bundle (P1/P5/P6/Q5/Q6/Q7 — 6 items)`.

- [ ] **Step 2:** Insert a new §⑲ section "RC14 polish bundle" before the existing config-reference section. Bilingual mini structure:

```markdown
### ⑲ RC14 polish 打包 / RC14 polish bundle

*Since V1.9.0-RC14.* 6 项 backlog polish 落地（无 SPI 变更、无线格式变更、无新增配置键、除 Q5 pathological URI 外无行为变更）：

- **P1 — `closeSession()` / `topicMessage()` 远端路径也按 `broker.state() == ACTIVE` gate**：与 RC12 L6 `sendMessage()` 对齐，redis-loss 宽限期 broker 已 DEGRADED 时 short-circuit，节省 ≤2 s `command-timeout-ms` 的注定失败 lookup。
- **P5 — 风格统一**：NATS-KV registry 的 RC11 L2 注释压缩；Redis broker 的 `java.nio.charset.StandardCharsets` 改用 import 形式与测试一致。
- **P6 — 慢 CI margin**：`ClusterNodeManagerReliabilityTest.shutdownAwaitsSchedulerTerminationBeforeDeregister` 的 latch 从 2 s 改 5 s。
- **Q5 — JetStream stream-name 长度 guard**：`ensureStream()` 预检 `streamName.length() > 255` → `ClusterBrokerException("Stream name too long: ...")`，比 jnats 错误更清晰。
- **Q6 — RC13 spec §3 明确 Connection bean qualifier**（`@Qualifier("nettyClusterNatsKvConnection")`）。
- **Q7 — RC13 spec §4 表格同步代码实际使用的 `g_<b64url(nodeId)>`**（jnats client-validator 拒绝 durable name 含 `.`，RC13 实现期发现并修正）。

**Q4 — DedupRing capacity 经 RC14 brainstorm 复核为 reviewer false positive**：`removeEldestEntry` 返回 `size() > cap` 在每次 `put` 后立即驱逐 eldest，元素数严格上限 cap；`LinkedHashMap(cap*2, 0.75f, true)` 中的 `cap*2` 是 *table*-capacity 哈希提示（避免 rehash），不是元素阈值。不予修改。

#### 向后兼容

纯 polish。Q5 仅影响 pathological 长 URI（先前在 jnats 抛错；现在在 broker 抛 `ClusterBrokerException` 含清晰诊断）。其他项 0 行为变更。
```

- [ ] **Step 3: Update test count line** to the new total (435 + 3 = 438) after full reactor passes.

- [ ] **Step 4: `docs/pre-ga-audit-backlog.md`** — strike P1, P5, P6, Q5, Q6, Q7 (move to "Fixed in RC14" reference section at the bottom); explicitly add a note that Q4 is refuted by RC14 brainstorm; **L1 + P2 + P3 + P4 + Q1 + Q2 + Q3 remain deferred** (these are the still-open items).

- [ ] **Step 5:** Verify UTF-8 + no U+FFFD on the edited docs.

- [ ] **Step 6:** Commit. `git commit -m "docs(cluster): RC14 polish bundle release notes + backlog cleanup"`

---

## Task 7: Final reactor + RC14 cut + finish

- [ ] **Step 1:** Bump 11 POMs RC13 → RC14:

```bash
sed -i "s/1\.9\.0-RC13/1.9.0-RC14/g" pom.xml demo-netty-web-spring-boot-starter/pom.xml netty-spring-boot-autoconfigure/pom.xml netty-spring-web/pom.xml netty-spring-webmvc/pom.xml netty-spring-websocket/pom.xml netty-spring-websocket-cluster/pom.xml netty-web-spring-boot-starter/pom.xml netty-webmvc-spring-boot-starter/pom.xml netty-websocket-spring-boot-starter/pom.xml netty-websocket-cluster-spring-boot-starter/pom.xml
```

Verify with `grep -r "1.9.0-RC13" --include="pom.xml" .` (empty).

- [ ] **Step 2:** Full reactor:

```bash
mvn test 2>&1 | tail -20
```

Expect: BUILD SUCCESS, **438 tests** total (+3 from RC13).

- [ ] **Step 3:** Update test count in release notes if it differs.

- [ ] **Step 4:** Commit. `git commit -m "release: 1.9.0-RC14 - polish bundle (P1/P5/P6/Q5/Q6/Q7)"`

- [ ] **Step 5:** `finishing-a-development-branch` skill:
- Option 1: merge to master locally
- Tag `v1.9.0-RC14`
- STOP before push

---

## Self-Review

**Spec coverage:** Every spec section (P1, P5, P6, Q5, Q6, Q7) maps to a task. Q4 refutation captured in
Task 6 §⑲. Test delta (+3) honored.

**Placeholder scan:** None.

**Type consistency:** `BrokerState.ACTIVE/DEGRADED`, `NodeState.ACTIVE`, `ClusterBrokerException`,
`StandardCharsets.UTF_8`, `jsm.getStreamInfo`, `jsm.addStream` — all consistent across tasks.

**Sequential rationale:** P1 (Java + tests) → Q5 (Java + tests) → P5 (style across multiple files, no tests)
→ P6 (test only) → Q6+Q7 (docs) → docs+backlog → cut. No file conflicts; sequential is appropriate given
the small total LOC.

**Note for implementer:** Verify each gate-change-to-impl matches the existing test pattern from RC12 L6
before writing the new tests. The `closeSession` and `topicMessage` paths may need slightly different
test setup than `sendMessage`.
