# RC18 RC17-Audit Polish — Spec

**Target:** netty-spring 1.9.0-RC18
**Branch:** `feature/1.9.0-rc18-rc17-audit-polish` (off master at RC17 `0f82fc0`)
**Status:** approved 2026-06-07 (goal directive "继续开发" — proceed)

---

## 1. Goal

Close the 4 RC17 audit nice-to-haves (D2 ×2 + D6 + D7) that didn't ship in RC17's must-fix bundle. Pure
refactor + doc polish; **0 behavior / SPI / wire / config / test change**.

## 2. Items

### Track A (`NatsJetStreamReliableBroker.java`)

**T1 — Snapshot-then-iterate in `shutdown()`**

Current code iterates `subscriptions.entrySet()` while individual `SubscriptionHandle` close callbacks can
concurrently `subscriptions.remove(uri)`. Concurrent-safe under `ConcurrentHashMap` semantics but non-idiomatic.
Fix: snapshot the values into a `List`, iterate the snapshot, then call `subscriptions.clear()` at the end.

**T2 — Soften `streamCache.clear()` comment in the ConnectionListener**

The current comment claims atomicity between `clear()` and concurrent `computeIfAbsent` — this overstates the
ConcurrentHashMap guarantee. Replace the comment with an accurate description: "Clears visible entries;
concurrent `computeIfAbsent` may re-populate with a stale marker, but the next reconnect clears again — the
defensive design tolerates this".

### Track B (`docs/release-notes-1.9.0.md`)

**T3 — Add `redis.*` config table to §⑪ (RC7)**

§⑪ currently mentions `redis.cluster-nodes` and `redis.uri` in prose but no dedicated table. Other feature
sections (⑥ reliable.*, ⑦ auth.*, ⑭/⑮ nats.*, ⑩ trace-propagation.*) have tables. Add a table to §⑪ for
style consistency:

| Key | Default | Note |
|---|---|---|
| `redis.uri` | `redis://localhost:6379` | Standalone or sentinel — selected by scheme |
| `redis.cluster-nodes` | (empty) | Non-empty → Redis Cluster transport selected; mutually exclusive with `reliable.enable=true` in RC7 |

**T4 — Numeral consistency check**

The 22 section headers use circled Unicode chars ①②③…⑳ then ㉑ ㉒. Verify all are valid Unicode CIRCLED
NUMBER chars (U+2460..U+247F + U+3251..U+325F range). If all consistent — no-op + add a one-line note in
the spec self-review confirming the audit-flagged stylistic concern is closed by inspection. If any
inconsistency found — normalize to circled chars (CIRCLED THREE = ③, ENCLOSED ALPHANUMERIC = different
codepoint, etc.).

## 3. Tests delta

**0.** No new tests, no test changes, no expected reactor count change. 444 tests preserved.

## 4. Backward compatibility

- T1: refactor only. Behavior-preserving — snapshot-then-iterate produces identical observable shutdown
  semantics.
- T2: comment only.
- T3+T4: docs only.

**Zero SPI / wire / config / Java behavior / test impact.**

## 5. Implementation tracks (parallel)

| Track | Files | Items |
|---|---|---|
| **A** | `NatsJetStreamReliableBroker.java` | T1 + T2 |
| **B** | `docs/release-notes-1.9.0.md` | T3 + T4 |

File-disjoint. Safe parallel dispatch.

## 6. Risk register

| Risk | Mitigation |
|---|---|
| T1 refactor introduces subtle ordering change in shutdown | Implementer must run existing `NatsJetStreamReliableBrokerTest` shutdown cases; the snapshot+iterate+clear sequence is a known-good pattern from Java concurrency literature. |
| T3 redis.* table values become stale (default URI / cluster-nodes wording) | Implementer reads `ClusterProperties.Redis` at HEAD to confirm both the type and default value (already-known: uri default = `redis://localhost:6379`; cluster-nodes default = empty string). |
| T4 numeral check produces false positive — perceived inconsistency that's actually consistent in display but uses 2 different Unicode codepoints | Implementer verifies via `grep -P` or codepoint dump (`hexdump -C`). If real inconsistency: normalize. If apparent-only: document closure. |

## 7. Docs

After Track A + B land, release-notes header bumps from RC17 → RC18 with a one-line summary of the audit
nice-to-haves cleared. No new §-section needed (this is RC-housekeeping not feature delivery; mention in
the running status-bar line, not a numbered section).

---

## Spec self-review

- **Placeholder scan:** None.
- **Internal consistency:** Track A items both touch the same file (Java); Track B items both touch the
  same file (docs). Disjoint tracks. Test delta 0 matches §3. Compat claim "zero impact" matches §4.
- **Scope:** Small polish RC, smaller than RC14 (6 items) and RC18 by a margin. Plan-sized.
- **Ambiguity:** T4 specifies fallback behavior (no-op vs normalize) based on inspection outcome.
- T3 redis.* table is small but matches the convention of other §s — comparing to release-notes-1.9.0.md
  §⑥ table proves the pattern.
