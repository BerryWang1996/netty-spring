# RC1 review → RC2 backlog (room registry hardening)

From the 1.10.0-RC1 adversarial review (`docs/superpowers/notes/` review workflow, verdict
`rc1ReadyToCut=true, 0 must-fix`). None corrupts distributed Redis state (all Lua ops are atomic
single-EVALs); these are local-index staleness / cleanup robustness / observability gaps, bounded by the
existing `roomFanoutStaleTarget` waste meter and gated behind opt-in `room.enable`. Land in RC2.

- **RB1 (high):** `RedisRoomRegistry.removeAllForNode` never updates the local in-process index
  (`localMembers`/`roomsBySession`). Harmless in the normal dead-*remote*-node case (a remote node's
  sessions were never in a surviving node's local index), but leaves a stale-membership window if a
  still-running node is falsely declared dead. Fix: have `removeAllForNode` also prune the local index for
  the dead node id; add a regression test.
- **RB2 (medium):** `removeAllForNode` is fire-and-forget from `invalidateCacheForNode` with no
  timeout/retry and only debug-level failure logging. A hung SCAN/pipeline leaves dead-node member sets
  lingering in Redis invisibly. Fix: bounded timeout + warn-level failure log + a failure counter meter.
- **RB3 (medium):** shutdown uses a fixed 5s `awaitTermination`; a large SCAN+pipeline `removeAllForNode`
  can exceed it and be force-killed mid-cleanup, leaving dangling Redis member keys. Fix: configurable drain
  budget or explicit pending-task drain.
- **RB4 (low):** `removeAllForSession` local-index cleanup is read-then-iterate-then-remove, not atomic with
  the EVAL — a concurrent join for the same session could re-insert it (theoretical; session is mid-teardown
  on disconnect). Fix: clear the local index in lockstep with the EVAL callback.
- **RB5 (nit):** concurrent joins on a newly-occupied node can both return `added=1` from `JOIN_LUA`,
  double-counting the node-set-added metric. Node-set stays correct (SADD idempotent); observability-only.
- **RB6 (low):** node-set send-path cache has no SLA/meter on how often a stale-target round occurs between
  NODE_LEFT and the wholesale cache clear. Receive-side waste is metered; cache-staleness frequency is not.
- **RB7 (nit):** add an explicit test for hash-tag `b64(room)` round-trip on UTF-8/multibyte/null-byte room
  names (the Java-side token must exactly match the token re-used inside `REMOVE_ALL_FOR_SESSION_LUA`;
  base64url-no-padding is deterministic so practical risk is low).

**Fixed already in RC1 (not deferred):** the misleading "byte-identical to 1.9.0" comments (the wire is v2;
runtime behavior is identical but the wire is not byte-for-byte) — corrected in `ClusterProperties.java` +
`ClusterMessageSender.java` before the RC1 cut.
