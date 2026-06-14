# Offline Queue + User-Addressable Delivery (1.10.0-RC2) — Design Spec

**Target:** netty-spring 1.10.0-RC2
**Branch:** `feature/1.10.0-offline-queue` (off 1.10.0-RC1 master)
**Status:** approved 2026-06-14 (brainstormed; design-review pending before implementation)

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
// Identity extraction — app-specific (JWT sub / query / header). Default reads a configured key.
public interface UserIdResolver {
    /** @return stable userId, or null for anonymous/unauthenticated (no offline queue for them). */
    String resolve(MessageSession session);
}

// userId -> live sessions reverse index (none exists today). RC3 presence extends this.
public interface UserRegistry {
    CompletionStage<Void> bindUser(String userId, String uri, String sessionId, String nodeId);
    CompletionStage<Void> unbindUser(String userId, String uri, String sessionId);
    CompletionStage<Set<SessionRef>> sessionsForUser(String userId);   // SessionRef = (nodeId, uri, sessionId)
    CompletionStage<Boolean> isUserOnline(String userId);
    CompletionStage<Void> removeAllForNode(String nodeId);
    void shutdown();
}

// Per-user durable offline queue. Bounded retention.
public interface OfflineQueueStore {
    CompletionStage<Void> enqueue(String userId, ClusterEnvelope envelope);
    CompletionStage<List<StoredMessage>> drain(String userId);   // ordered FIFO; StoredMessage = (id, envelope)
    CompletionStage<Void> delete(String userId, List<String> messageIds);   // ack after delivery
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
  userId = userIdResolver.resolve(session)          // null → anonymous, skip offline path
  if userId != null:
     register(uri, sid, node, {userId})             // was emptyMap() — now carries userId
     userRegistry.bindUser(userId, uri, sid, node)
     offlineStore.drain(userId).thenAccept(msgs ->  // BACKFILL
        for each StoredMessage m (FIFO):
           localSender.sendMessage(uri', sid, m.envelope.decode())   // deliver to THIS new session
        offlineStore.delete(userId, deliveredIds))   // ack only the delivered ones
  clusterSender.onLocalUriActive(uri)                // unchanged
```

### Send to user
```
sendToUser(userId, msg):
  userRegistry.sessionsForUser(userId)  (cached, short TTL):
    if non-empty → unicast to each session (reuse RC1/1.9.0 per-node unicast path)  // realtime
       on MessageSessionClosedException for a session (went offline mid-send) →
          if NO sessions remained reachable → offlineStore.enqueue(userId, envelope)   // race fallback
    if empty → offlineStore.enqueue(userId, envelope)   // offline → store for backfill
```

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
  redelivers. **Handlers must be idempotent** (same contract as reliable broadcast). Not exactly-once.
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

Metrics `netty.cluster.offline.*`: `enqueued`, `drained`, `dropped_retention`, `sendToUser.realtime`,
`sendToUser.queued` (the online/offline split), `users.online` (gauge).

## 8. Tests

| Test | Coverage |
|---|---|
| `HandshakeUserIdResolverTest` | query/header extraction; null when absent; malformed source config. |
| `RedisUserRegistryTest` (Mockito) + IT | bind/unbind/sessionsForUser/isUserOnline; removeAllForNode; multi-device (2 sessions one user). |
| `RedisOfflineQueueStoreTest` (Mockito) + IT | enqueue/drain(FIFO)/delete; retention trim (MAXLEN) drops oldest; TTL age check. |
| `OfflineDeliveryE2ETest` (two-node real Redis) | **Headline:** send to offline userId → user connects on node B → backfilled in FIFO order → queue empty after. Offline = no session anywhere. |
| `SendToUserRaceTest` | user offline mid-send → unicast fails → fallback enqueue. |
| `InMemory{UserRegistry,OfflineQueueStore}` | no-Lettuce-leak. |
| `NettyWebSocketClusterConfigureTest` (+context) | offline.enable=true → beans present + wired; false → absent, hook emptyMap path. |

## 9. Backward compatibility

- `offline.enable=false` (default): no offline beans, `onSessionRegistered` passes `emptyMap()` exactly as
  RC1 → byte-level identical. No envelope change (offline reuses the existing envelope; `StoredMessage` is a
  Redis-only wrapper). `sendToUser` is a new method on a new `UserOperations` sub-interface (not on
  `MessageSender`). `UserRegistry`/`OfflineQueueStore`/`UserIdResolver` are additive SPIs. Redis-only RC2.
  Boot 2.7 + Lettuce 6.1.
- Interop with RC1 rooms: independent. A user can be in rooms AND have an offline queue; orthogonal keys.

## 10. Scope summary

**In:** UserIdResolver SPI + HandshakeUserIdResolver; UserRegistry SPI + RedisUserRegistry; OfflineQueueStore
SPI + RedisOfflineQueueStore; `sendToUser`; hook resolve+bind+drain; config + metrics + honest docs + tests +
two-node E2E.

**Out (deferred):** message history/scrollback; room/group-to-offline; per-device offline backfill (RC3);
NATS-KV offline store; exactly-once.

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
