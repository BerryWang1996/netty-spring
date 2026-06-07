# RC17 GA-Readiness Final Audit — Spec

**Target:** netty-spring 1.9.0-RC17
**Branch:** `feature/1.9.0-rc17-ga-readiness-audit` (off master at RC16 `37f408e`)
**Status:** approved 2026-06-07 (goal directive "继续开发" — proceed)

---

## 1. Goal

Independent multi-agent re-audit of the **cumulative 16-RC 1.9.0 work product** to surface anything review fatigue
or per-RC tunnel-vision may have missed. The pre-GA audit (RC11) ran in isolation on 1.9.0-RC10's state; this
audit covers RC11→RC16 *added* fixes/features as well — both the new code and the regression interactions.

**Two possible RC17 shapes (driven by audit verdict):**
1. **Audit-clean** → RC17 ships ONLY the audit report (`docs/audits/2026-06-07-ga-readiness-final.md`) +
   release-notes §㉒ summarizing the audit. No code change. Tag is a "GA-readiness-attested" milestone.
2. **Audit finds must-fix** → RC17 ships the audit report + the fix commits + release-notes summarizing
   both. Either shape is acceptable.

Either way: after RC17, **1.9.0 GA is the next user-driven decision**.

## 2. Audit dimensions (10)

| ID | Lens | Adversarial question |
|---|---|---|
| **D1** | Cumulative correctness | Re-validate the 24 confirmed pre-GA findings — are all still fixed in post-RC16 code? Any regressed by a later RC? |
| **D2** | Cross-RC interaction risk | Files touched by multiple RCs (`ClusterMessageSender.java`, `ClusterNodeManager.java`, `RedisStreamsReliableBroker.java`, `NatsJetStreamReliableBroker.java`, `NettyWebSocketClusterConfigure.java`) — any subtle cross-interaction not caught per-RC? |
| **D3** | Security posture | HMAC end-to-end (all paths), ACL/credential leakage, secret in logs, threat model coverage, untrusted Redis/NATS scenarios. Anything Cluster-mode operator could foot-gun themselves with. |
| **D4** | Performance posture | RC10 → RC16 micro-benchmark: did the polish RCs introduce silent perf regressions (gate adds, log additions, stream-cache clears, etc.)? Sample broadcast/unicast/reliable paths. |
| **D5** | API/SPI compatibility | RC10 → RC16 SPI signatures (`ClusterBroker` / `SessionRegistry` / `ClusterMessageSender` etc.) — strict diff vs spec promises. Any silent breakage to user-overridable extension points? |
| **D6** | Documentation completeness | release-notes, api-guide §9, cluster-design ADR-001, README cluster section. Consistency across docs. Every shipped feature has a §, every § has a Since-V version. |
| **D7** | Configuration completeness | Every config key in `ClusterProperties` has: javadoc + `additional-spring-configuration-metadata.json` entry + release-notes mention + default-value-rationale. Any orphaned knob? |
| **D8** | Test coverage gaps | Run line-coverage thinking: are there production code paths not exercised by any test? Particularly: degraded-state branches, exception handlers, transport failure modes. |
| **D9** | Release engineering | All 11 POMs at exactly RC16; no SNAPSHOT leaks; dependency versions sane; Maven Central readiness (GPG signing OK; autoPublish=false honored); GitHub Actions CI green; tag conventions consistent. |
| **D10** | "Smell test" / GA-blocker scan | Any TODO/FIXME in shipped code? Any "this should be cleaner" the team postponed? Any tests with `@Disabled` / `assumeTrue`-skips that hide failures? Anything that whispers "don't ship me yet"? |

## 3. Workflow shape

Three phases, mirroring the RC11 pre-GA audit pattern:

1. **Audit** (10 parallel agents) — one per dimension, returns structured findings with severity (nit/low/medium/high) + location + recommendation.
2. **Skeptic verify** (1 agent per CONFIRMED HIGH or MEDIUM finding) — try to refute each. Refuted findings are dropped.
3. **Synthesize** (1 agent) — return: `rc17ShipShape ∈ {audit-only, fix-bundle}`, list of must-fix items, list of nice-to-have items (1.9.1 backlog), executive summary.

## 4. RC17 shape decision

| Synthesis verdict | RC17 action |
|---|---|
| 0 must-fix | Ship audit report only. Release-notes §㉒ summarizes findings + attests GA-readiness. Tag `v1.9.0-RC17`. |
| ≥1 must-fix | Ship fix bundle + audit report. Release-notes §㉒ summarizes both. Tag `v1.9.0-RC17`. |

Either path: 1.9.x backlog stays empty after RC17 (or gains 1.9.1 items if nice-to-haves are filed).
**1.9.0 GA cut decision is user-driven; this audit only attests readiness.**

## 5. Backward compatibility

- Audit-only path: doc-only changes; zero code impact.
- Fix-bundle path: must remain additive / defensive; **must not introduce SPI/wire/config-default change** without
  being called out as a known limitation. Any HIGH-severity fix that requires a behavior change escalates back to
  brainstorm.

## 6. Documentation updates

1. **NEW:** `docs/audits/2026-06-07-ga-readiness-final.md` — the audit report (workflow output committed verbatim,
   plus a brief intro/conclusion).
2. `docs/release-notes-1.9.0.md` — header `RC16` → `RC17`; add §㉒ summarizing the audit (verdict + headline
   findings + GA-readiness attestation) + (if fix-bundle path) the specific fixes shipped.
3. `docs/pre-ga-audit-backlog.md` — append any nice-to-haves the audit surfaced under a new section.

## 7. Test count

Audit-only path: **444 tests, unchanged**.
Fix-bundle path: bump by however many tests the fixes add.

## 8. Risk register

| Risk | Mitigation |
|---|---|
| Workflow may find issues that should have been caught earlier — "embarrassment risk" | Treat as the workflow doing its job; each finding goes through skeptic verify so we're not chasing false positives. Reframe the audit's purpose as **insurance**, not blame. |
| 10 agents may overfit findings to fit their dimension (false positives) | Skeptic phase explicitly tries to refute. Synthesis gates on confirmed severity. |
| Audit may find issues whose fix needs new SPI/config (i.e. larger than a "polish RC") | Synthesis will categorize those as 1.9.1 candidates rather than RC17 must-fix. Backlog regrows; GA still cuttable. |
| Audit may find a real GA-blocker we missed | This is exactly what the audit is for. Better to find it now than 1 hour after deploying to Maven Central. RC17 fix → adversarial verify → re-cut. |

---

## Spec self-review

- **Placeholder scan:** None. All sections concrete.
- **Internal consistency:** Two-path shape is explicit in §1 + §4; doc-update list in §6 matches §1; risk
  register addresses each branch.
- **Scope:** Single audit workflow, single decision tree on outcome. Plan-sized.
- **Ambiguity:** Synthesis verdict criteria explicit in §4 table.
