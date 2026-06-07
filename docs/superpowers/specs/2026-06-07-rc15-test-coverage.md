# RC15 Test/IT Coverage + RC14 Nits — Design Spec

**Target:** netty-spring 1.9.0-RC15
**Branch:** `feature/1.9.0-rc15-test-coverage` (off master at RC14 `3e4378b`)
**Status:** approved 2026-06-07 (brainstormed; goal directive "继续开发" — proceed)

---

## 1. Goal

Close the remaining RC13 IT coverage gaps + apply two micro-polish items from RC14 review. **All
changes are test-only, doc-only, or log-string-only.** No SPI / wire / config / behavior change.

## 2. Items

### Track A — NATS reliable IT coverage (closes RC13 review Q1/Q2/Q3)

- **Q1**: Extend the existing kill-container DEGRADED test to also assert recovery — container.start() →
  poll `broker.state() == ACTIVE` within 30s. Validates the reverse CAS (only unit-level today).
- **Q2**: New `reliableBroker_hmacRoundTripWithMatchingSecrets` test — publisher + subscriber with
  matching `auth.secret`, assert receiver gets the message. Positive case complements existing rejection IT.
- **Q3**: New `reliableBroker_publishDoesNotThrowWhenDegraded` test — kill container → assert
  `reliablePublish(...)` returns normally (no exception) → restart → assert receiver gets the message
  eventually. Validates spec §5.1 "DEGRADED still attempts publish".

### Track B — NATS-KV IT polish (closes RC12 review P2 + P4)

- **P2**: Add `@org.junit.jupiter.api.Tag("slow")` to `NatsKvIntegrationTest.reaper_claimExpires_thenReclaimSucceeds`.
  Document the convention in the test class header.
- **P4**: Replace the blind `Thread.sleep(12_000)` with retry-and-assert polling loop:
  poll `r2.tryClaim(...)` at 100ms intervals up to 15s, assert success. Safer against JetStream
  housekeeping jitter on slow CI.

### Track C — Surgical polish (RC12 P3 + RC14 R1 + R2)

- **P3**: One inline comment in `ReliableBroadcastIntegrationTest` L8 DEGRADED test explaining the
  15s `degradedDeadline` rationale (Docker kill + Lettuce channel-inactive + listener-CAS budget).
- **R1**: `ClusterMessageSender.topicMessage()` DEGRADED-else log message includes `broker.state()` so
  an operator triaging during the redis-loss grace window sees both states (today only `nodeManager.state`
  is logged, which reads "ACTIVE" when broker is actually DEGRADED — misleading).
- **R2**: `ClusterMessageSender.closeSession()` javadoc clarifies the false-on-DEGRADED semantics:
  "Returns false either when no such local session OR when the cluster transport is degraded; caller
  cannot distinguish (mirrors L6 sendMessage semantics)."

## 3. Tests delta

| Existing | Change |
|---|---|
| `NatsJetStreamReliableIntegrationTest.reliableBroker_*degraded*` (Q1) | Extend: after kill+DEGRADED assertion, container.start() + poll for ACTIVE within 30s. |
| `NatsJetStreamReliableIntegrationTest` (Q2 new method) | Add `reliableBroker_hmacRoundTripWithMatchingSecrets`. |
| `NatsJetStreamReliableIntegrationTest` (Q3 new method) | Add `reliableBroker_publishDoesNotThrowWhenDegraded`. |
| `NatsKvIntegrationTest.reaper_claimExpires_thenReclaimSucceeds` (P2+P4) | Annotate `@Tag("slow")`; replace blind sleep with polling loop. |
| `ReliableBroadcastIntegrationTest` DEGRADED IT (P3) | Add one comment line above `long degradedDeadline = +15000`. |
| `ClusterMessageSender.topicMessage` log (R1) | Include `broker.state()` in DEGRADED-else log message. |
| `ClusterMessageSender.closeSession` javadoc (R2) | Document false-on-DEGRADED semantics. |

**Expected reactor delta:** +2 new test methods (Q2 + Q3). 438 → 440.

## 4. Backward compatibility

- Single-node + all other modes: zero behavioral impact.
- R1 log refinement is **operationally additive** (existing log-line parsers still see the original prefix
  + extra info appended).
- R2 javadoc is doc-only.
- P3 is an inline comment.
- Q1+Q2+Q3+P2+P4 are pure test code.
- No SPI signature change. No wire format change. No config key change. No bean condition change.

## 5. Documentation updates

1. `docs/release-notes-1.9.0.md` — header `RC14` → `RC15`; append `;RC15 测试覆盖加固 (Q1-Q3+P2-P4+R1+R2)`.
   Add §⑳ noting the 8 items shipped.
2. `docs/pre-ga-audit-backlog.md` — strike Q1, Q2, Q3, P2, P3, P4, R1, R2 (move to "Fixed in RC15" reference
   section); only L1 remains open. Backlog effectively empty after RC15.
3. No api-guide / cluster-design changes (no user-facing surface change).

## 6. Implementation tracks (parallel)

| Track | Files | Items | Independent? |
|---|---|---|---|
| **A** | `NatsJetStreamReliableIntegrationTest.java` (only) | Q1, Q2, Q3 | Yes — single file, sequential within track |
| **B** | `NatsKvIntegrationTest.java` (only) | P2, P4 | Yes |
| **C** | `ClusterMessageSender.java` + `ReliableBroadcastIntegrationTest.java` | P3, R1, R2 | Yes — 2 disjoint files |

Each track gets its own subagent. After all 3 tracks land, controller verifies full reactor + cuts RC15.

## 7. Risk register

| Risk | Mitigation |
|---|---|
| Q1 container restart may race Lettuce/jnats reconnect — broker state CAS could lag | 30s poll window with 200ms intervals; if persistently flaky, extend window or fall back to mock-listener test. |
| Q3 publish-does-not-throw assertion — broker may throw `ClusterBrokerException` if Connection state is CLOSED (not just disconnected) | `killContainerCmd` is what's used elsewhere; Lettuce/jnats observe this as DISCONNECTED → DEGRADED, not CLOSED. Existing Q1 DEGRADED assertion already validates this path. |
| P4 polling loop could prematurely succeed before TTL expires (race the maxAge) | Bucket maxAge is 10s; poll only starts after `Thread.sleep(11_000)` (a small initial wait), then polls for the next 4s. Effectively replicates the previous 12s+2s margin but with bounded fast-success. |
| R1 log format change could break log-aggregation regex | Additive append, not prefix change; format is `node state is {} (broker state is {})` keeping the existing `node state is X` substring. |
| Q1+Q3 use same Testcontainers container, run in same test class — could container.stop()/start() in one test affect another | Tests are JUnit 5 method-scoped via `@Container`; if `@BeforeAll`-scoped, stagger via `@Order` or extract Q3 to its own container. Implementer must verify the existing test isolation. |
