# RC18 RC17-Audit Polish — Plan

> 2 parallel file-disjoint implementer tracks + controller-side cut.

**Goal:** Ship T1+T2+T3+T4 (RC17 audit nice-to-haves) as `1.9.0-RC18`.

**Spec:** `docs/superpowers/specs/2026-06-07-rc18-rc17-audit-polish.md`.

**Branch:** `feature/1.9.0-rc18-rc17-audit-polish` (off RC17 `0f82fc0`).

---

## Track A — `NatsJetStreamReliableBroker.java`

- [ ] **T1: `shutdown()` snapshot-then-iterate.** Open the file; find the `shutdown()` body where it
  iterates `subscriptions.entrySet()` while calling close on each handle (~lines 343-350). Refactor:

```java
@Override
public void shutdown() {
    state.set(BrokerState.SHUTDOWN);
    // Snapshot first to avoid iterating-while-removing (concurrent-safe but non-idiomatic).
    java.util.List<SubscriptionHandle> handles;
    synchronized (subscriptions) {
        handles = new java.util.ArrayList<>(subscriptions.values());
    }
    for (SubscriptionHandle h : handles) {
        try {
            h.stop.set(true);
            try { h.sub.unsubscribe(); } catch (Exception ignored) {}
            try { h.thread.join(2_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        } catch (Exception e) {
            log.warn("Error closing subscription handle during shutdown", e);
        }
    }
    subscriptions.clear();
    streamCache.clear();
    // any existing post-loop cleanup stays
}
```

Adjust to match the actual existing structure (some fields may differ — preserve all existing
`executor.shutdownNow()`, `js`/`jsm`/connection close behaviors). The KEY change is: snapshot via
`new ArrayList<>(subscriptions.values())`, iterate snapshot, then `subscriptions.clear()`. Do not change
any other semantics.

- [ ] **T2: Soften `streamCache.clear()` comment.** Find the `ConnectionListener` callback's
  RECONNECTED/CONNECTED branch (~lines 168-171). The current comment claims atomicity. Replace with:

```java
} else if (ev == Events.RECONNECTED || ev == Events.CONNECTED) {
    // Clears visible cache entries on transport reconnect (S1, RC16). A concurrent computeIfAbsent may
    // re-populate with a stale STREAM_MARKER, but the next publish on that URI calls ensureStream which
    // re-validates via getStreamInfo (and re-creates if missing); the next reconnect clears again.
    // Defensive — tolerates the race without correctness impact.
    streamCache.clear();
    if (state.compareAndSet(BrokerState.DEGRADED, BrokerState.ACTIVE)) {
        log.info("NatsJetStreamReliableBroker transport reconnected — state ACTIVE; streamCache cleared");
    }
}
```

- [ ] **T3: Run** `mvn -pl netty-spring-websocket-cluster test -Dtest=NatsJetStreamReliableBrokerTest`. All 18+ tests must still pass with 0 failures.

- [ ] **T4: Commit.** `git commit -m "refactor(cluster/nats): snapshot-then-iterate shutdown (T1) + accurate streamCache atomicity comment (T2)"`

---

## Track B — `docs/release-notes-1.9.0.md`

- [ ] **T3a: Locate §⑪ (RC7 Redis Cluster).** Search for `### ⑪`. Read the section; find a good place
  to insert a `redis.*` config table — likely near "配置选择器" subsection.

- [ ] **T3b: Insert the config table.** Match the table style used in other §s (look at §⑥'s
  `reliable.*` table for the exact column header convention):

```markdown
#### 配置项（`server.netty.websocket.cluster.redis.*`）

| 配置项 | 默认 | 说明 |
|---|---|---|
| `uri` | `redis://localhost:6379` | 单点或 sentinel；按 scheme 选择拓扑（`redis://`、`rediss://`、`redis-sentinel://`）。 |
| `cluster-nodes` | （空） | 非空 → 切到 RedisClusterClient 传输（RC7 客户端）；与 `reliable.enable=true` 互斥（RC7 边界）。 |
```

- [ ] **T4: Numeral consistency check.** Run:

```bash
grep -nP '^### [^A-Za-z0-9 ]' docs/release-notes-1.9.0.md | head -30
```

Examine the section markers. They should be circled-number Unicode (U+2460-U+247F: ①-⑳, then U+3251-U+325F or U+24EB+ for ㉑+). If every numeral is in a single consistent Unicode range / form, document the finding (no-op). If a mix (e.g. some circled digit, some ENCLOSED CJK), normalize to one form.

- [ ] **T4-Verify:** `python3 -c "import sys; [print(repr(c), hex(ord(c))) for c in open('docs/release-notes-1.9.0.md', encoding='utf-8').read() if 0x2460 <= ord(c) <= 0x24FF or 0x3251 <= ord(c) <= 0x32BF]" | sort | uniq -c` — confirms all numeral codepoints used.

  If they all fall in a consistent block (e.g. all U+2460-U+247B which covers ①-⑳ + ㉑ ㉒ via the same range — actually U+2460-U+2473 covers ①-⑳, and ㉑ is U+3251), accept as stylistic — document closure. If inconsistent, normalize.

- [ ] **T5: Header status update.** Change the release-notes header line from `RC17` to `RC18` and append a short summary: `;RC18 RC17 audit nice-to-haves polish (T1-T4 — 0 behavior change)`.

- [ ] **T6: Commit.** `git commit -m "docs(rc18): RC7 redis.* config table + numeral consistency check (T3+T4) + RC18 header"`

---

## Controller tasks

### Pom bump + reactor + finish

- [ ] **Step 1:** Bump 11 POMs RC17 → RC18:

```bash
sed -i "s/1\.9\.0-RC17/1.9.0-RC18/g" pom.xml demo-netty-web-spring-boot-starter/pom.xml netty-spring-boot-autoconfigure/pom.xml netty-spring-web/pom.xml netty-spring-webmvc/pom.xml netty-spring-websocket/pom.xml netty-spring-websocket-cluster/pom.xml netty-web-spring-boot-starter/pom.xml netty-webmvc-spring-boot-starter/pom.xml netty-websocket-spring-boot-starter/pom.xml netty-websocket-cluster-spring-boot-starter/pom.xml
```

- [ ] **Step 2:** Full reactor: `mvn test`. Expect BUILD SUCCESS, **444 tests** (unchanged from RC17).

- [ ] **Step 3:** Commit. `git commit -m "release: 1.9.0-RC18 - RC17 audit nice-to-haves polish (T1+T2+T3+T4; 0 behavior change)"`

- [ ] **Step 4:** Finish branch: FF-merge to master + tag `v1.9.0-RC18` + delete branch. STOP before push.

---

## Self-Review

- **Spec coverage:** T1+T2 in Track A; T3+T4 in Track B. 1:1 mapping.
- **Parallel safety:** Track A touches 1 file; Track B touches 1 file. Disjoint.
- **Tests delta:** 0 (matches spec).
- **No SPI / wire / config change.**
- **Total LOC delta:** very small (~30 lines added across 2 files; some lines moved/refactored).
