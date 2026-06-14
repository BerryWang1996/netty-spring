# Offline Queue + User-Addressable Delivery (1.10.0-RC2) — Design Spec

**Target:** netty-spring 1.10.0-RC2
**Branch:** `feature/1.10.0-offline-queue` (off 1.10.0-RC1 master)
**Status:** approved 2026-06-14 (brainstormed + 4-lens adversarial design review → 3 must-fixes folded in:
security auth-contract, multi-device drain lock, no-cache offline detection). Review archived at
`docs/superpowers/notes/2026-06-14-rc2-design-review.json`. Verdict was FIX_DESIGN_FIRST → fixes applied below.

---

## 0. Problem (distinct from reliable broadcast)

Reliable broadcast (RC2 of 1.9.0) replays to briefly-disconnected **nodes** via per-node consumer groups.
It does **not** help a **user** who is offline (no live WebSocket session anywhere in the cluster) when a
message is sent to them. IM needs: store messages addressed to an offline user, deliver on reconnect
(backfill). The crux is a **stable recipient identity** — the send API is `sendMessage(uri, msg,
sessionIds…)` keyed on ephemeral per-connection `sessionId`, but an offline user has no sessionId. So RC2
introduces a stable `userId` and user-addressed delivery.

## 1. Scope (locked decisions)

- **Offline queue only** — store undelivered-to-offline messages, drain + delete on reconnect. Full
  message-history/scrollback (retain-everything + fetch API) is deferred (different weight class).
- **Identity:** a pluggable `UserIdResolver` SPI extracts a stable `userId` from the handshake (default:
  configurable query-param/header) → flows into register metadata + a new minimal `UserRegistry`
  (userId→sessions index). **RC3 multi-device presence builds on `UserRegistry`.**
- **Queue scope:** user-unicast only — `sendToUser(userId, msg)` → offline → enqueue. Room/group-to-offline
  deferred.
- **Opt-in, default off** (`cluster.offline.enable=false`): no offline beans, no userId resolution,
  byte-level behavior identical to RC1.

## 2. Grounding (existing code)

- `ClusterSessionHookImpl.onSessionRegistered(session, uri)` is the connect/reconnect hook — **it currently
  passes `Collections.emptyMap()`** to `register`, so no userId flows today. RC2 changes this to resolve +
  pass userId, and to drain the offline queue here.
- `MessageSession` has **no generic attribute store** — only the handshake `FullHttpRequest`
  (`getFirstRequest`, `getQueryParam`, `getHeader`). So userId cannot be "set on the session" generically;
  it is **extracted from the handshake** by `UserIdResolver`.
- `SessionRegistry.register(uri, sid, node, metadata)` — metadata javadoc already says "(e.g. userId,
  connectedAt)"; the channel exists, just unused.
- `sendMessage` (ClusterMessageSender ~L401) already resolves sessions → local/remote/closed and throws
  `MessageSessionClosedException` for unreachable sessions — the **race-fallback** hook for "went offline
  mid-send → enqueue".

## 3. SPI surface (all additive — no existing SPI signature change)

```java
// Identity extraction — app-specific. Default reads a configured handshake key.
public interface UserIdResolver {
    /**
     * @return stable userId, or null for anonymous/unauthenticated (no offline queue for them).
     *
     * <p><b>SECURITY CONTRACT (load-bearing):</b> the returned userId IS treated as the recipient's
     * identity for offline delivery, presence, and queue ownership. It MUST be validated against the
     * session's <b>authenticated</b> principal — never a raw, client-controllable value.
     * <pre>
     *   WRONG (impersonation hole): return session.getQueryParam("userId");  // ?userId=bob steals bob's queue
     *   RIGHT:                       return verifiedJwt(session.getHeader("Authorization")).getSubject();
     * </pre>
     * The default {@code HandshakeUserIdResolver} is <b>convenience/testing only</b> — it trusts a
     * configured query-param/header verbatim. Production IM MUST supply a resolver that verifies identity
     * (typically in a {@code WebSocketHandshakeInterceptor} that has already authenticated the connection).
     */
    String resolve(MessageSession session);
}

// userId -> live sessions reverse index. A DERIVED routing/presence index (NOT a durable replica of
// SessionRegistry) — reconciled via removeAllForNode on dead-node cleanup. RC3 multi-device presence
// extends this same SPI (chose a dedicated SPI over adding methods to SessionRegistry to keep the 1.9.0
// SessionRegistry signature — and its 3 impls — untouched; see §10 decision record).
public interface UserRegistry {
    CompletionStage<Void> bindUser(String userId, String uri, String sessionId, String nodeId);
    CompletionStage<Void> unbindUser(String userId, String uri, String sessionId);
    /** NOT cached — every call hits the store. See §5/§6: caching this would create a false-online
     *  silent-loss window (stale "online" → fire-and-forget unicast to a dead session → no fallback). */
    CompletionStage<Set<SessionRef>> sessionsForUser(String userId);   // SessionRef = (nodeId, uri, sessionId)
    CompletionStage<Boolean> isUserOnline(String userId);
    CompletionStage<Void> removeAllForNode(String nodeId);
    void shutdown();
}

// Per-user durable offline queue. Bounded retention. Drain is EXCLUSIVE per userId (distributed lock)
// to prevent concurrent multi-device reconnects from double-delivering.
public interface OfflineQueueStore {
    CompletionStage<Void> enqueue(String userId, ClusterEnvelope envelope);
    /** Acquire a per-userId lock, XRANGE the whole stream up to the current tail, return FIFO. If the
     *  lock is already held (another device draining), returns empty (the holder delivers). */
    CompletionStage<List<StoredMessage>> drain(String userId);   // StoredMessage = (id, envelope)
    CompletionStage<Void> delete(String userId, List<String> messageIds);   // ack after delivery, then unlock
    CompletionStage<Void> removeAllForUser(String userId);
    void shutdown();
}
```

## 4. Components

| File | Role |
|---|---|
| `spi/UserIdResolver.java` **(new)** | The resolver SPI. |
| `room/HandshakeUserIdResolver.java` **(new, default)** | Reads userId from a configured handshake source (`query:userId` / `header:X-User-Id`); null when absent. |
| `spi/UserRegistry.java` + `spi/SessionRef.java` **(new)** | userId→sessions index SPI + value type. |
| `room/RedisUserRegistry.java` **(new, default)** | Redis Set `netty:user:{b64userId}` = `nodeId|b64uri|sessionId` members; atomic bind/unbind; cluster-aware `removeAllForNode`. |
| `spi/OfflineQueueStore.java` + `spi/StoredMessage.java` **(new)** | offline queue SPI + value type. |
| `room/RedisOfflineQueueStore.java` **(new, default)** | Redis Stream per user `netty:offline:{b64userId}` (FIFO, `MAXLEN ~ max-messages-per-user`); per-message TTL via stream trim + a lazy age check on drain. |
| `room/InMemoryUserRegistry.java` + `room/InMemoryOfflineQueueStore.java` **(test stubs)** | no-Lettuce-leak proof. |
| `ClusterMessageSender.java` **(touched)** | `sendToUser(userId, msg)` on a new `UserOperations` sub-interface. |
| `ClusterSessionHookImpl.java` **(touched)** | resolve userId → register-with-metadata + `bindUser` + **drain-on-connect**; `unbindUser` on remove. |
| `ClusterProperties.java` + metadata json **(touched)** | `offline.*` config. |
| `metrics/NettyClusterMeterBinder.java` + `ClusterRuntimeStats` **(touched)** | offline meters. |
| `NettyWebSocketClusterConfigure.java` **(touched)** | gated beans (`offline.enable=true`) + wiring. |

**Deferred:** `NatsKv{UserRegistry,OfflineQueueStore}` (all-NATS parity, later RC — Redis-first cadence).

## 5. Data flow

### Connect (register + backfill)
```
onSessionRegistered(session, uri):
  if offline.enable:                                 // resolve ONLY when enabled (else emptyMap, as RC1)
     userId = userIdResolver.resolve(session)        // null → anonymous, skip offline path
  if userId != null:
     register(uri, sid, node, {userId})              // was emptyMap() — now carries userId
     userRegistry.bindUser(userId, uri, sid, node).thenRun(() ->
        offlineStore.drain(userId).thenAccept(msgs ->   // BACKFILL — drain AFTER bind completes
           for each StoredMessage m (FIFO):
              deliver m.envelope to THIS new session, with X-Offline-Message-Id = m.id metadata
           offlineStore.delete(userId, deliveredIds)))   // ack delivered, then release the drain lock
  clusterSender.onLocalUriActive(uri)                // unchanged

// drain() mechanic (concrete, closes the alleged drain-vs-enqueue race):
//   1. SET netty:offline-lock:{b64userId} {node} NX PX <drain-lock-ms>   — exclusive per userId
//      (if not acquired → another device is draining → return empty; the holder delivers)
//   2. XRANGE netty:offline:{b64userId} - +   — reads ALL entries up to the tail AT CALL TIME.
//      Redis serializes XADD id generation, so any message enqueued before this XRANGE — including in the
//      bind→drain window — is in the read set and WILL be drained. Messages enqueued AFTER arrive on the
//      now-online session in realtime (bindUser already completed).
//   3. caller delivers, then delete(ids) XDELs them, then the lock is released (DEL).
```

### Send to user
```
sendToUser(userId, msg):
  userRegistry.sessionsForUser(userId)   // NOT cached — fresh store read (avoids false-online silent loss)
    if non-empty → unicast to each session (reuse RC1/1.9.0 per-node unicast path)  // realtime
       on MessageSessionClosedException (LOCAL send-time failure) →
          if NO sessions remained reachable → offlineStore.enqueue(userId, envelope)   // send-time fallback
    if empty → offlineStore.enqueue(userId, envelope)   // offline → store for backfill
    on offlineStore.enqueue failure → stats.fallbackEnqueueFailures++ + ERROR log (never a silent drop)
```

> **Send-time-only boundary (honest):** `broker.unicast()` is **fire-and-forget** (verified
> `ClusterMessageSender` L461 throws only on send-BUILD/broker-accept failure, never on post-accept
> delivery). So the offline queue is a fallback for **send-time** failures (zero reachable sessions, or a
> local `MessageSessionClosedException`). A **remote** session that closes *after* the broker accepted the
> unicast but *before* receiving the frame produces no exception and triggers no enqueue — that message is
> **not** recovered by the offline queue. This is documented in §6 + metered as `offline.unicast_failures`;
> exactly-once is out of scope (handlers must be idempotent; the app reconciles at its layer).

### Disconnect
```
onSessionRemoved(session, uri):
  userId = userIdResolver.resolve(session)   // same source (handshake req still available)
  if userId != null: userRegistry.unbindUser(userId, uri, sid)
  deregister(uri, sid); clusterSender.onLocalUriInactive(uri)   // unchanged
```

## 6. Honest semantics (the IM contract — must be documented verbatim)

- **At-least-once to offline users, within the retention window** (`max-messages-per-user` default 1000,
  `ttl-seconds` default 7 days). Beyond it → trimmed (bounded gap, like reliable's MAXLEN). Metered via
  `offline.dropped_retention`.
- **Ordering:** per-user FIFO (Redis Stream). Cross-sender order not guaranteed (same caveat as reliable).
- **Cross-crash duplicate:** drain delivers then deletes; if delete fails after delivery, the next connect
  redelivers. **Handlers must be idempotent** (same contract as reliable broadcast). Not exactly-once. The
  drain delivers each message with an **`X-Offline-Message-Id`** = the Redis stream entry id, so apps can
  dedup on the infra id (no need to embed their own key, though they may).
- **Multi-device double-delivery is prevented** by the per-userId drain lock (§3/§5): when two devices of one
  user reconnect concurrently, only the lock holder drains + delivers + deletes; the other's `drain()` returns
  empty. Without the lock this would be a *deterministic* duplicate (both XRANGE before either deletes), not a
  rare race — hence a lock, not just "idempotent handlers."
- **Post-accept loss is NOT covered (send-time-only):** see the §5 boundary note — `broker.unicast` is
  fire-and-forget; a remote session closing after broker-accept but before frame-receive is not enqueued.
  Metered `offline.unicast_failures`. Apps reconcile via idempotency at their layer.
- **Offline = zero sessions cluster-wide.** A multi-device user with any online session is "online" → no
  enqueue (delivered to the online device(s)). Per-device offline backfill is **RC3**, not RC2.
- **Identity required:** anonymous sessions (resolver → null) get no userId, no offline queue. Offline
  delivery requires a resolvable identity — stated plainly, not hidden.
- **Drain races a concurrent send:** a `sendToUser` arriving during drain (user just connected) → the user
  is now online (bound) → delivered realtime; the drain handles only what was enqueued before bind. A
  message enqueued in the tiny window between bind and drain is still drained (drain reads the whole stream
  up to a snapshot id). Edge documented.

## 7. Config + metrics

`cluster.offline.*`:
| Key | Default | Meaning |
|---|---|---|
| `enable` | `false` | Master switch. Off = no offline beans, hook passes emptyMap as in RC1. |
| `user-id-source` | `query:userId` | Where `HandshakeUserIdResolver` reads userId (`query:<name>` or `header:<name>`). |
| `max-messages-per-user` | `1000` | Redis Stream `MAXLEN ~` per user. |
| `ttl-seconds` | `604800` | Per-message age cap (7 days); lazy-checked on drain + stream trim. |
| `drain-batch-size` | `100` | Max messages drained per connect (rest drained on next read loop / subsequent connect). |
| `drain-lock-ms` | `5000` | Per-userId drain lock TTL (`SET NX PX`); auto-expires so a crashed drainer can't wedge a user's queue. |

Metrics `netty.cluster.offline.*`: `enqueued`, `drained`, `dropped_retention` (MAXLEN/TTL trim — bounded-gap
honesty meter; alert on `rate(...dropped_retention[5m]) > 0`), `sendToUser.realtime` / `sendToUser.queued`
(the online/offline split), `unicast_failures` (broker send-time exceptions, distinct from queued),
`fallback_enqueue_failures` (enqueue itself failed after all unicast paths — never a silent drop),
`resolved_identities` / `unresolved_sessions` (auth vs anonymous), `users.online` (gauge).

## 8. Tests

| Test | Coverage |
|---|---|
| `HandshakeUserIdResolverTest` | query/header extraction; null when absent; malformed source config. |
| `RedisUserRegistryTest` (Mockito) + IT | bind/unbind/sessionsForUser/isUserOnline; removeAllForNode; multi-device (2 sessions one user). |
| `RedisOfflineQueueStoreTest` (Mockito) + IT | enqueue/drain(FIFO)/delete; retention trim (MAXLEN) drops oldest; TTL age check. |
| `OfflineDeliveryE2ETest` (two-node real Redis) | **Headline:** send to offline userId → user connects on node B → backfilled in FIFO order → queue empty after. Offline = no session anywhere. **+ bind→drain-window case:** enqueue a message during the bind→drain window → delivered exactly once (not lost, not duped). |
| `MultiDeviceDrainLockTest` (real Redis) | **Two devices of one user reconnect concurrently → each offline message delivered exactly once** (drain lock serializes; the non-holder's drain returns empty). The double-delivery regression gate. |
| `HandshakeUserIdResolverSecurityTest` | asserts the SPI javadoc carries the auth contract + the default resolver is documented as testing-only (so the impersonation footgun can't silently ship). |
| `SendToUserRaceTest` | user offline (no cached online — sessionsForUser hits the store) → enqueue, not unicast-to-stale; local mid-send close → fallback enqueue. |
| `InMemory{UserRegistry,OfflineQueueStore}` | no-Lettuce-leak. |
| `NettyWebSocketClusterConfigureTest` (+context) | offline.enable=true → beans present + wired; false → absent, hook emptyMap path. |

## 9. Backward compatibility

- `offline.enable=false` (default): no offline beans, the resolver bean is **not created**, and
  `onSessionRegistered` resolves nothing + passes `Collections.emptyMap()` exactly as RC1 (the hook guards
  `if (offline.enable)` before resolving) → byte-level identical. When `enable=true` but the resolver returns
  null (anonymous), `register` still passes `emptyMap()`. The context test asserts all three paths
  (off, on+anonymous, on+authenticated). No envelope change (offline reuses the existing envelope;
  `StoredMessage` is a Redis-only wrapper carrying the stream id). `sendToUser` is a new method on a new
  `UserOperations` sub-interface (not on `MessageSender`), paired with `isUserOnline` so the sub-interface has
  dual purpose (mirrors `RoomOperations` having multiple methods). `UserRegistry`/`OfflineQueueStore`/
  `UserIdResolver` are additive SPIs. Redis-only RC2. Boot 2.7 + Lettuce 6.1.
- Interop with RC1 rooms: independent. A user can be in rooms AND have an offline queue; orthogonal keys.

## 10. Scope summary

**In:** UserIdResolver SPI + HandshakeUserIdResolver; UserRegistry SPI + RedisUserRegistry; OfflineQueueStore
SPI + RedisOfflineQueueStore; `sendToUser`; hook resolve+bind+drain; config + metrics + honest docs + tests +
two-node E2E.

**Out (deferred):** message history/scrollback; room/group-to-offline; per-device offline backfill (RC3);
NATS-KV offline store; exactly-once.

### Decision record (design review)

- **UserRegistry as a dedicated SPI, not added to SessionRegistry** — Option B from the review. Rationale:
  adding `sessionsForUserId` to `SessionRegistry` would change the 1.9.0 SPI signature and force all 3 impls
  (Redis / RedisClusterMode / NatsKv) + any user impl to change. A dedicated additive `UserRegistry`
  preserves the SPI-stability invariant the project has held; it is documented as a **derived routing/presence
  index** (reconciled via `removeAllForNode`), not a durable replica. RC3 multi-device presence extends the
  same SPI, so it is built once.
- **`sessionsForUser` is NOT cached** — the offline-detection correctness decision. Caching it (e.g. mirroring
  the 5s per-sessionId `nodeCache`) would let a just-disconnected user read "online" → fire-and-forget unicast
  to a dead session → no exception → no fallback → silent loss. The per-userId reverse lookup hits the store
  every time (it is on the relatively cold `sendToUser` path, not the per-session hot path).
- **Per-userId drain lock** — kills deterministic multi-device double-delivery (not a rare race).

## 11. Security (load-bearing — the default resolver is a footgun if mis-used)

The offline queue, presence, and user-addressed delivery all key on the `userId` returned by `UserIdResolver`.
**A wrong identity = cross-user data exposure** (read another user's queued messages, impersonate their
presence, hijack their delivery).

- The `UserIdResolver` SPI javadoc carries a **SECURITY CONTRACT**: the returned userId MUST be derived from
  the session's **authenticated** principal (verified JWT `sub`, OAuth, SAML NameID), never a raw
  client-controllable value.
- The default `HandshakeUserIdResolver` (reads `query:userId` / `header:X-User-Id` verbatim) is **explicitly
  labeled convenience/testing-only**. Its javadoc + the release notes + the api-guide must state: *production
  IM MUST supply a resolver that validates identity* — typically a `WebSocketHandshakeInterceptor` that
  authenticates the connection, with the resolver reading the already-verified principal.
- `offline.enable=false` (default) → the resolver is never invoked → no identity surface at all.
- This mirrors the project's existing honesty about the Redis trust model (cluster-design §Security): the
  framework provides the mechanism; the operator MUST secure identity. Stated loudly, not buried.

---

## Spec self-review

- **Placeholder scan:** none. Each SPI method + Redis key + config key is concrete.
- **Consistency:** identity (§3 UserIdResolver) ↔ flow (§5 connect/send/disconnect) ↔ semantics (§6) all
  use the same userId-from-handshake + userId→sessions + per-user-stream model. The race-fallback (§5 send)
  matches the existing `MessageSessionClosedException` path (§2 grounding).
- **Scope:** single feature (user-addressed offline delivery), Redis-only, additive SPIs. Plan-sized.
- **Ambiguity:** the drain-vs-send race (§6 last bullet) is the one subtle spot — resolved with "drain reads
  to a snapshot id; online users get realtime; window-enqueued msgs still drained." The design review will
  stress this + the multi-device "offline = zero sessions" definition + cross-crash dup.
- **The RC1 lesson applied:** the load-bearing risk here is the **identity/offline-detection correctness**
  (is "zero sessions cluster-wide" reliably detectable given registry cache staleness? does the
  drain-on-connect race a concurrent enqueue?) — flagged for the design review, exactly as the shard-collapse
  was caught for RC1.
