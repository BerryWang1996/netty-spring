# 1.10.0-RC3 — Multi-device presence (`PresenceRegistry`) — Design Spec

> Status: design-review-folded (gate verdict `fixDesignFirst=TRUE`, 3 BLOCKER + 3 MAJOR + 3 MINOR — ALL folded here).
> Review archived: `docs/superpowers/notes/2026-06-15-rc3-presence-design-review.json`.
> Builds on RC2 identity (`UserIdResolver` / `UserRegistry`). Redis-only. Opt-in (`presence.enable=false`).

## 1. Goal & honest scope

Deliver **per-user aggregate presence** — `ONLINE` / `AWAY` / `OFFLINE`, correctly derived across **all** of a
user's live connections — plus **live presence-change events** so apps can build rosters ("a contact just came
online"). Opt-in; byte-identical to RC2 when `presence.enable=false`.

**Honest scope cut (recorded, like RC1's shard→node-set correction):** RC3 ships *aggregate* presence + events. It
does **NOT** ship stable per-device *addressing* ("sign out my laptop specifically", "read on device X"). That needs a
stable device identity (a `DeviceIdResolver` SPI parallel to `UserIdResolver`); per the design review it is a
**one-way-door API decision** and the only identity RC3 could synthesize today (`nodeId|sessionId`) is ephemeral +
topology-leaking. So RC3 exposes **aggregate + per-status connection counts**, never a leaked device map, and defers
per-device addressing to a follow-on. The feature is still "multi-device presence" because the aggregate is
multi-device-aware; what's deferred is addressing individual devices.

**Also in scope (forced by the review):** RC3 fixes a **latent RC2 bug** — `UserRegistry.removeAllForNode` is
implemented but never wired into the dead-node fan-out, so a crashed node's `netty:user:*` bindings leak forever
(false-ONLINE → `sendToUser` fire-and-forget to a dead session → silent loss). RC3 touches the reconciliation path to
add presence reap, so it closes this gap too.

## 2. The presence model

- **Per-connection status ∈ {ONLINE, AWAY}** — app-reported. A connection is `ONLINE` on connect (hook default);
  the app may mark it `AWAY` (idle). `OFFLINE` is **derived** (zero live connections), never stored as a status.
- **User aggregate (computed, never stored as truth):**
  - `ONLINE` if **any** connection is `ONLINE`;
  - `AWAY` if ≥1 connection exists and **none** is `ONLINE`;
  - `OFFLINE` if **zero** connections.
- No `dnd` / `invisible` / auto-away-timeout in RC3 (app policy; deferred).

## 3. SPIs (new, additive — no existing SPI signature changes)

```java
// spi/PresenceStatus.java
public enum PresenceStatus { ONLINE, AWAY, OFFLINE }   // OFFLINE only ever appears as an AGGREGATE, never a stored connection status
```

```java
// spi/UserPresence.java — the public read shape (NO device map; aggregate + counts only — design-review MAJOR)
public final class UserPresence {
    private final PresenceStatus aggregate;   // ONLINE | AWAY | OFFLINE
    private final int onlineConnections;       // # connections currently ONLINE
    private final int awayConnections;         // # connections currently AWAY
    // total live connections = onlineConnections + awayConnections; aggregate derivable from the two
    // getters only; immutable
}
```

```java
// spi/PresenceChangeListener.java — app callback on an AGGREGATE transition (the roster hook)
public interface PresenceChangeListener {
    /** Fired once per user aggregate transition (old != new), on every RC3 node (local-first on the origin,
     *  and on receive elsewhere — origin self-suppresses its own echo). The app owns the roster (who-watches-whom)
     *  and pushes to watchers via sendToUser. The library guarantees the EVENT, not the subscription graph. */
    void onPresenceChange(String userId, PresenceStatus oldAggregate, PresenceStatus newAggregate);
}
```

```java
// spi/PresenceRegistry.java — Redis-only, parallel to RC2 UserRegistry (NOT bolted onto it)
public interface PresenceRegistry {

    /** Set ONE connection's status. Atomic Lua on the single user hash: read old aggregate, HSET the field,
     *  recompute new aggregate from HVALS, return (old,new). Returns the transition (old,new) — caller publishes
     *  iff old != new. */
    CompletionStage<PresenceTransition> setPresence(String userId, String nodeId, String sessionId, PresenceStatus status);

    /** Set ALL of a user's connections to a status (the "set me away" convenience — covers the per-session
     *  ergonomics gap). One Lua over the whole hash; returns (old,new). */
    CompletionStage<PresenceTransition> setPresenceForUser(String userId, PresenceStatus status);

    /** Clear ONE connection (graceful disconnect). Atomic Lua: read old, HDEL the field, recompute new, return
     *  (old,new). When the last connection clears, new == OFFLINE. */
    CompletionStage<PresenceTransition> clearPresence(String userId, String nodeId, String sessionId);

    /** Aggregate read for a user. NOT cached (same anti-false-online contract as UserRegistry.sessionsForUser).
     *  Returns last-known state — see §10 staleness contract; this is ADVISORY, not a liveness probe. */
    CompletionStage<UserPresence> getPresence(String userId);

    /** Transition-aware dead-node reap (the BLOCKER fix). For EVERY presence hash holding a field prefixed
     *  "nodeId|": Lua reads old aggregate, HDELs that node's fields, recomputes new aggregate, and collects
     *  (userId, old, new) for every user whose aggregate changed. Returns the full list so the LEADER can publish
     *  one PRESENCE_CHANGE per changed user. Leader-elected (one reaper per dead node) ⇒ exactly-once per user. */
    CompletionStage<List<PresenceTransition>> removeAllForNode(String nodeId);

    void shutdown();
}
```

```java
// spi/PresenceTransition.java — value type (userId, old, new); old==new means "no event"
public final class PresenceTransition {
    private final String userId;            // null for the single-user setters (caller knows it); set for reap results
    private final PresenceStatus oldAggregate;
    private final PresenceStatus newAggregate;
    public boolean changed() { return oldAggregate != newAggregate; }
}
```

## 4. Storage + the atomic Lua (the correctness core)

- Redis hash **`netty:presence:{b64userId}`**, hash-tagged `{b64userId}` (single slot, co-located with
  `netty:user:{b64userId}`). Field = **`nodeId|sessionId`** (unique per connection, **nodeId leading** so reap can
  prefix-match). Value = the status string (`ONLINE` / `AWAY`). The hash is self-contained: aggregate is computable
  from `HVALS` alone.
- **Aggregate function** (shared Lua snippet, used by every op): given the field values — `ONLINE` if any value
  `== "ONLINE"`; else `AWAY` if `#fields > 0`; else `OFFLINE`.
- `setPresence` / `setPresenceForUser` / `clearPresence` each run as **one `EVAL`** on the single hash:
  `oldAgg = aggregate(HVALS)` → apply (`HSET field status` / `HSET` all fields / `HDEL field`) → `newAgg =
  aggregate(HVALS)` → `return {oldAgg, newAgg}`. Lua serializes concurrent multi-node ops on the single slot, so two
  simultaneous first-connections yield **exactly one** `OFFLINE→ONLINE` transition (the second sees `old==ONLINE`).
- `removeAllForNode(nodeId)`: a `SCAN netty:presence:*` (dedicated executor, mirrors `RedisUserRegistry`), and for
  each key a **transition-aware Lua**: `oldAgg = aggregate(HVALS)` → `HDEL` all fields whose name starts with
  `nodeId|` → `newAgg = aggregate(HVALS)` → return `(oldAgg,newAgg)`. Collect `(userId, old, new)` where `old != new`.
- Stream/hash key carries no per-member TTL (presence is liveness-bounded by reconciliation, §10), but the hash is
  emptied by `clearPresence`/reap; an all-cleared hash is `DEL`'d so abandoned keys don't accumulate.
- `InMemoryPresenceRegistry` (test scope) mirrors the same transition semantics with an in-process lock.

## 5. Reconciliation / dead-node reap — the BLOCKER fix (also fixes RC2)

**Problem the review found:** `clearPresence` runs only on graceful `onSessionRemoved`. A hard crash (kill -9 / OOM /
partition) never calls it. The existing dead-node fan-out reaps only `sessionRegistry` (primary path,
`ClusterNodeManager.doReconciliation`) and `roomRegistry` (best-effort `deadNodeCallback`/`invalidateCacheForNode`,
which **swallows exceptions to `log.debug`**); **`userRegistry.removeAllForNode` is never called at all.**

**Fix (folded):**
1. **Add a registry-reap fan-out on the LEADER-ELECTED PRIMARY path.** In `ClusterNodeManager.doReconciliation`, after
   `sessionRegistry.removeAllForNode(dead)` (which already re-queues on failure via `.exceptionally`), chain
   `userRegistry.removeAllForNode(dead)` (when present) and `presenceRegistry.removeAllForNode(dead)` (when present),
   on the same retried path — **not** the exception-swallowing `deadNodeCallback`. This is gated by the existing
   `reaper.tryClaim` leader-election so exactly one node reaps a given dead node.
   - Wiring: `ClusterNodeManager` gains optional `userRegistry` / `presenceRegistry` references (nullable, set by
     auto-config like the existing `deadNodeCallback`), OR a small `DeadNodeReaper` fan-out list the auto-config
     populates. Spec the exact call site + failure/retry semantics (mirror the `sessionRegistry` `.exceptionally`
     re-queue).
2. **Publish the reap-induced OFFLINE events.** `presenceRegistry.removeAllForNode` returns
   `List<PresenceTransition>`; the reaping (leader) node publishes one `PRESENCE_CHANGE` per changed user on the
   reserved channel (§6). This is what makes the **dominant crash path** actually deliver `→OFFLINE` to watchers.
3. **Fix the latent RC2 gap + the false javadoc.** `UserRegistry`'s "Reconciled via removeAllForNode on dead-node
   cleanup" is true only after step 1. Add a regression test: a dead node's `netty:user:*` members are gone after a
   reconciliation sweep (today they leak). This restores RC2's no-silent-loss contract under crash.

## 6. Events — dedicated channel + new MessageKind (MAJOR fix)

- **New `MessageKind.PRESENCE_CHANGE`** (mirrors RC1 `ROOM_BROADCAST` — a new kind dispatched by kind, **no envelope
  version bump**; `CURRENT_VERSION` stays 2). Payload = `userId|oldAggregate|newAggregate` (simple text, codec-encoded;
  HMAC-wrapped consistent with the broker if auth is on).
- **Dedicated reserved channel** (a control name, e.g. constant `PRESENCE_CHANNEL`). Presence does **NOT** ride the
  broadcast-topic path: `onBroadcastMessage` is hard-wired to `deserializePayload` + `localSender.topicMessage(uri,…)`
  and would mis-dispatch a presence envelope as an app message. Instead:
  - A **dedicated listener `onPresenceMessage(envelope)`** is subscribed via `broker.subscribe(PRESENCE_CHANNEL,
    this::onPresenceMessage)` from `ClusterMessageSender.start()`, **gated on `presence.enable`**, and
    **UNCONDITIONAL** (not driven by `getRegisteredUri`) — a node with zero local sessions still receives presence
    events for users it watches.
  - **Origin self-suppression (MINOR fold):** `onPresenceMessage` drops the envelope when
    `envelope.getOriginNodeId().equals(localNodeId)` (mirror `onBroadcastMessage`), counted as a presence
    self-delivery drop. **Local-first fire-site:** the transition hook fires the local `PresenceChangeListener`
    directly and publishes for OTHER nodes; the origin suppresses its own echo (matches the documented
    local-first/cluster-second principle — the listener fires exactly once locally and once per remote node).
- **Reserved-name guard (MAJOR fold):** fail fast at startup if any `@MessageMapping` URI equals the reserved presence
  channel name (real enforcement, not a javadoc note).
- **Rolling-upgrade:** an RC2 (v2) node never subscribes to `PRESENCE_CHANNEL`, so it never decodes a
  `PRESENCE_CHANGE` kind — topic isolation, no version bump, mixed RC2/RC3 cluster safe for all normal traffic.
  `EnvelopeRollingUpgradeTest` gets a case proving the new kind round-trips on RC3 and that the codec's kind handling
  is forward-safe.

## 7. Hook flag-split (MAJOR fix) — identity / offline / presence

`ClusterSessionHookImpl` today collapses identity+offline into ONE `offlineEnabled` flag, so a presence-only config
(`offline.enable=false`) takes the else branch and **never binds the user** → presence can't function. Split:

```
identityEnabled = userIdResolver != null && userRegistry != null;
offlineEnabled  = identityEnabled && offlineStore != null;
presenceEnabled = identityEnabled && presenceRegistry != null;
```

- Gate **resolve userId + register-with-userId-metadata + bindUser/unbindUser** on `identityEnabled` (so presence-only
  binds).
- Gate **drainOnConnect + delete-ack** on `offlineEnabled`.
- Gate **`setPresence(ONLINE)` after bindUser / `clearPresence` on removal + publish-on-transition** on
  `presenceEnabled`.
- Add `presenceRegistry` to the hook constructor; the config bean computes all three flags and passes it.
- `presence.enable=false` **and** `offline.enable=false` → identity off → `register(emptyMap())`, byte-identical to
  RC1/RC2 (tripwire-tested).

## 8. Auto-config (gating)

- **`PresenceRegistry` bean** (default `RedisPresenceRegistry`): `@ConditionalOnExpression(STANDALONE_REDIS_REGISTRY)` +
  `@ConditionalOnProperty(presence.enable=true)` + `@ConditionalOnMissingBean(PresenceRegistry.class)` (mirrors the RC2
  offline beans incl. the RC2-review FIX 7 transport-gate).
- **Identity-gating refactor:** `userIdResolver` + `userRegistry` beans move from `offline.enable=true` to
  **`offline.enable OR presence.enable`** (shared identity). A combined SpEL or a small condition.
- **`OnAnyRedisSpiRequired` (BLOCKER fix):** add `presenceEnabled = parse(presence.enable) && !hasBean(PresenceRegistry)`
  to `matches()`, so a presence-only + all-custom-core-SPI deployment still creates the Redis client/connection. Context
  test: presence-only + all-4-custom-core-SPI ⇒ `nettyClusterRedisConnection` exists (mirror the RC2 offline test).
- **`sendToUser`-without-queue note:** when `presence.enable=true` but `offline.enable=false`, `UserRegistry` exists so
  `sendToUser` is available but there is no offline queue → an offline user's message is **dropped, not queued**.
  Document this explicitly (it's correct given offline is off, but a footgun if unstated) — `sendToUser`'s "queued"
  path requires `offline.enable=true`.
- **Reconciliation wiring:** auto-config passes `userRegistry` + `presenceRegistry` into the `ClusterNodeManager`
  reap fan-out (§5) and wires the leader's publish path to the sender's `PRESENCE_CHANNEL`.

## 9. Public API — `PresenceOperations` sub-interface on `ClusterMessageSender`

```java
public interface PresenceOperations {
    /** Set THIS connection's status (the session being handled). */
    CompletionStage<Void> setPresence(MessageSession session, PresenceStatus status);
    /** Set ALL of a user's connections (the "set me away" convenience). */
    CompletionStage<Void> setPresenceForUser(String userId, PresenceStatus status);
    /** Aggregate read — last-known state, ADVISORY (see §10), NOT a liveness probe. */
    CompletionStage<UserPresence> getPresence(String userId);
}
```
- Implemented by `ClusterMessageSender`; base `MessageSender` untouched. Throws `IllegalStateException` when
  `presence.enable=false` (explicit, not a silent drop) — mirrors `RoomOperations`/`UserOperations`.
- `setPresence`/`setPresenceForUser` resolve userId via the resolver (cheap, same as `onSessionRemoved`), run the Lua,
  and publish on transition (local-first + remote).

## 10. Honest positioning (folded MINORs)

- **Broadcast ceiling:** presence-change events are a **broadcast** — same ~10-node Redis Pub/Sub ceiling as
  `topicMessage`; high-scale presence wants the RC4 mesh. Stated, metered, not hidden. Presence flap (mobile network
  blips toggling online/away) ⇒ N-node fan-out per transition; debounce is the **app's** job (the library publishes
  one event per *aggregate* transition, and the Lua suppresses no-op transitions, which already absorbs same-status
  re-sets).
- **Stale-ONLINE window (advisory read):** after a crash, a dead connection lingers `ONLINE` in the hash until
  reconciliation reaps it — bounded by `heartbeatTimeoutMs (10000) + reconciliationIntervalMs (15000) + leader-claim +
  SCAN` (~25s at defaults). `getPresence` reflects **last-known state, NOT a liveness probe**; latency-sensitive
  consumers treat it as advisory and rely on the `sendToUser` delivery-time fallback (fresh lookup + offline-queue
  fallback) for correctness — exactly as RC2 framed the no-cache decision. The reap-emitted `→OFFLINE` event (§5) is
  the authoritative self-heal correction.
- **Per-user, multi-device-aware:** aggregate accounts for all connections; the per-status counts expose multiplicity
  without leaking ids. Per-device *addressing* (stable `DeviceIdResolver`) deferred.

## 11. Config + metrics

- `ClusterProperties.Presence`: `enable=false`, `publishChanges=true` (some apps want query-only — no event fan-out).
- Meters `netty.cluster.presence.*`: `changes` (aggregate transitions detected locally), `events_published`,
  `events_received`, `self_delivery_dropped`, `set` (connection-level updates), `reap_offline` (OFFLINE events emitted
  by dead-node reap — the crash-path correction meter).

## 12. Backward compatibility

- `presence.enable=false` (default): no presence beans, hook presence path inert; combined with `offline.enable=false`
  → identity off → `register(emptyMap())`, **byte-identical to RC1/RC2** (tripwire-tested).
- **Behavior change (intentional, a fix):** the dead-node reap now also reaps `UserRegistry` (closing the RC2 leak).
  This only changes crash-recovery behavior (stale bindings now cleared); the happy path is unchanged. Documented in
  release notes as a correctness fix.
- New SPIs (`PresenceRegistry`/`PresenceStatus`/`UserPresence`/`PresenceChangeListener`/`PresenceTransition`) are
  additive. `PRESENCE_CHANGE` is a new kind on a dedicated channel; no envelope version bump; mixed RC2/RC3 safe.
- Redis-only RC3; Boot 2.7 + Lettuce 6.1.

## 13. Design-review record (gate verdict `fixDesignFirst=TRUE`, all 9 findings folded)

- **BLOCKER** crash-path OFFLINE event dropped → §5 (transition-aware reap + leader publish).
- **BLOCKER** presence/userRegistry reap unwired (latent RC2 leak) → §5 (leader-elected primary-path fan-out + RC2 fix).
- **BLOCKER** `OnAnyRedisSpiRequired` omits presence → §8 (presence clause + context test).
- **MAJOR** reserved topic mis-dispatch via `onBroadcastMessage` → §6 (dedicated `onPresenceMessage` + `PRESENCE_CHANGE`
  kind + reserved-name guard).
- **MAJOR** hook single-flag → §7 (identity/offline/presence split).
- **MAJOR** `getPresence` device-key one-way-door → §3/§1 (Option (b): aggregate + counts, no leaked map; per-device
  addressing deferred).
- **MINOR** stale-ONLINE window / advisory read → §10. **MINOR** self-suppression + listener fire-site → §6.
  **MINOR** per-session ergonomics → §9 (`setPresenceForUser`).

## 14. Deferred to later RCs

Stable per-device addressing (`DeviceIdResolver` SPI + per-device map), `invisible`/`dnd`/custom statuses, auto-away
timeout, last-seen timestamps, server-side watcher/roster graph, `NatsKvPresenceRegistry` (all-NATS parity).
