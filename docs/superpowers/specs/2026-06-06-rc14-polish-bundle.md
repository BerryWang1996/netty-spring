# RC14 Polish Bundle — Design Spec

**Target:** netty-spring 1.9.0-RC14
**Branch:** `feature/1.9.0-rc14-polish-bundle` (off master at RC13 `66089c0`)
**Status:** approved 2026-06-06 (brainstormed; awaiting user spec review)

---

## 1. Goal

Land 6 small polish items from the post-RC11/RC12/RC13 review backlog as a single batch RC. **Pure
polish** — no SPI change, no wire-format change, no new config key, no default behavior change.

Items in scope: P1, P5, P6, Q5, Q6, Q7.
Items refuted (not shipped): **Q4** — DedupRing capacity (reviewer conflated table-capacity with
element-count; element count is correctly capped at `cap` via `removeEldestEntry` returning `size() > cap`).

## 2. Items

### P1 — closeSession + topicMessage broker-state gate consistency

- **Where:** `netty-spring-websocket-cluster/.../cluster/ClusterMessageSender.java`. After RC12 L6, only
  `sendMessage()` was tightened to require `broker.state() == BrokerState.ACTIVE`; `closeSession()` (~line 458)
  and `topicMessage()` still gate only on `nodeManager.getState()`.
- **Issue:** During the `redis-loss-grace-period-ms` window, `nodeManager.state` is still ACTIVE but
  `broker.state` is DEGRADED. Both paths waste up to `command-timeout-ms` (default 2 s) on a registry lookup
  the broker can't act on. Inconsistent with the L6 design.
- **Fix:** Add `&& broker.state() == BrokerState.ACTIVE` to both gates. **Internal tightening only — never
  changes a successful operation into a failure.** The avoided lookup was already wasted work; the
  resulting publish/close would have failed downstream.

### P5 — Style polish

- **NatsKvSessionRegistry.java:157-160** — verbose explanatory comment (RC11 L2 fix). Condense to 2 lines.
- **`StandardCharsets` import style** — `RedisPubSubBroker.java` line 166 inlines
  `java.nio.charset.StandardCharsets.UTF_8`; tests (e.g. `RedisBrokerInboundSizingTest`) use the
  `import java.nio.charset.StandardCharsets;` form. Switch the impl to use the import for consistency.

### P6 — Test timing margin

- **Where:** `netty-spring-websocket-cluster/.../ClusterNodeManagerReliabilityTest.shutdownAwaitsSchedulerTerminationBeforeDeregister`.
- **Issue:** 2 s latch on a 50 ms reconciliation interval (40-cycle margin). On extremely slow CI this could
  flake.
- **Fix:** Bump the latch timeout to 5 s (100-cycle margin). Assertion unchanged.

### Q5 — Stream-name length guard

- **Where:** `NatsJetStreamReliableBroker.ensureStream()` (~line 358).
- **Issue:** NATS rejects stream names > 255 chars; very long base64url-encoded URIs fail at
  `jsm.getStreamInfo` with a less-friendly diagnostic.
- **Fix:** Pre-check `streamName.length() > 255` → throw `ClusterBrokerException("Stream name too long: " +
  streamName.length() + " > 255; reduce URI length (URI=" + uri + ")")` for a clear diagnostic.

### Q6 — Spec wording fix

- **Where:** `docs/superpowers/specs/2026-06-06-nats-jetstream-reliable-rc13.md` §3 (Components).
- **Issue:** Spec says "reuses the same Connection" but doesn't name the bean qualifier.
- **Fix:** Edit spec to name `@Qualifier("nettyClusterNatsKvConnection")` explicitly (doc only).

### Q7 — Spec table sync

- **Where:** `docs/superpowers/specs/2026-06-06-nats-jetstream-reliable-rc13.md` §4 table.
- **Issue:** Spec table says `g.<b64url(nodeId)>`; code uses `g_<b64url(nodeId)>` because jnats validates
  against `.` in durable names. Code comment + release-notes §⑱ already note this; only the spec table is
  stale.
- **Fix:** Edit the table row to `g_<b64url(nodeId)>` with footnote about jnats validator constraint.

## 3. Tests

| Item | Test change |
|---|---|
| P1 | Add `closeSessionShortCircuitsRemoteWhenBrokerDegraded` + `topicMessageShortCircuitsRemoteWhenBrokerDegraded` to `ClusterMessageSenderTest`. Mirror the RC12 L6 `sendMessageShortCircuits...` pattern: spy `InMemorySessionRegistry`, set `broker.setState(BrokerState.DEGRADED)`, verify `lookupNode` is **never** called. |
| P5 | No new tests — style polish; existing tests must still pass. |
| P6 | The test itself is the change — assertion is unchanged. |
| Q5 | Add `ensureStream_rejectsExcessivelyLongStreamName` to `NatsJetStreamReliableBrokerTest`: construct broker, call `reliablePublish` with a URI whose base64url encoding produces `streamName.length() > 255`, assert `ClusterBrokerException` with "Stream name too long" in message and `jsm.getStreamInfo` / `jsm.addStream` **never** invoked. |
| Q6 / Q7 | No new tests — doc only. |

**Expected delta:** +3 new tests (P1×2 + Q5×1), 435 → 438.

## 4. Auto-config / wiring

No changes. Pure polish — no bean conditions modified, no new beans, no new config keys.

## 5. Backward compatibility

- **Single-node mode (default):** zero impact.
- **All-Redis / mixed / all-NATS deployments:** byte-level identical for successful operations. P1 tightens
  `closeSession` + `topicMessage` to also short-circuit during the redis-loss grace window when the broker
  is degraded; the avoided lookup was already wasted work. Operations that **previously succeeded** still
  succeed (gate only fires when broker is DEGRADED — same condition under which the downstream publish
  would fail anyway).
- **Q5 stream-name guard:** previously, very long URIs would surface as a jnats-side error on first publish.
  Now they surface as a clearer `ClusterBrokerException` at the same point. **Behavior change for
  pathological URIs only** — operationally an improvement.
- **No SPI signature change. No wire format change. No config key change.**

## 6. Documentation updates

1. `docs/release-notes-1.9.0.md` — header status line: `RC13` → `RC14`; append short summary "RC14 polish
   (P1/P5/P6/Q5/Q6/Q7 — 6 items)". Add a small §⑲ section noting the items with one-line each (mirroring
   the RC12 "1.9.1 backlog polish" pattern).
2. `docs/pre-ga-audit-backlog.md` — strike out P1, P5, P6, Q5, Q6, Q7 (move to "Fixed in RC14" reference
   section); keep L1 + P2/P3/P4 + Q1/Q2/Q3 (and Q4 with a note "verified by RC14 brainstorm: correct, not
   shipped").
3. **No api-guide.md changes** (no user-facing config or API change).
4. **No cluster-design.md changes** (no architectural change).

## 7. Risk register

| Risk | Mitigation |
|---|---|
| P1 changes `closeSession` / `topicMessage` semantics during grace window | Tests assert lookup not invoked; matches L6 precedent. The gate is purely defensive (avoids a doomed lookup). |
| P5 import style switch could mask an unused import | IDE warning if so; compiler/checkstyle would catch. |
| P6 5 s latch could now mask a real-regression | The assertion content is unchanged — only the wait window grows. A regression would still fail. |
| Q5 length guard could over-reject (NATS spec is "≤ 255 chars" in some versions, "≤ 256" in others) | Use 255 as a conservative upper bound — matches the jnats source. Real-world URIs are far shorter. |
| Spec edits (Q6, Q7) could conflict with the RC13 spec on master | RC14 branch is off RC13's commit, so any edits to the spec file in the working tree apply cleanly. |

## 8. Implementation order

1. P1 (`ClusterMessageSender` + 2 tests)
2. Q5 (`NatsJetStreamReliableBroker` + 1 test)
3. P5 (style polish, no tests)
4. P6 (test timing bump)
5. Q6 + Q7 (spec doc edits — single doc commit)
6. Release notes + backlog cleanup
7. Full reactor verify + pom bump + finish branch (T7 of the plan)

---

## Spec self-review

- **Placeholder scan:** None.
- **Internal consistency:** §2 items reference correct file paths verified against current master. §3 test
  delta (+3) = §1 P1 (2) + Q5 (1). §4 confirms no auto-config drift. §5 explicit on no-SPI / no-wire change.
- **Scope check:** 6 items, all in distinct files (or a small number of shared files); single-track
  sequential implementer is appropriate. Small enough to skip parallel subagent dispatch.
- **Ambiguity check:**
  - P1: gate change is mechanical — two lines, same pattern as L6 already shipped.
  - P5: explicit list of file:line changes; not ambiguous.
  - P6: bump magnitude is explicit (2 s → 5 s).
  - Q5: error message text + threshold (255) are explicit.
  - Q6 / Q7: spec file paths + the exact rewording given.
- **Q4 dropped rationale documented in §1.**
