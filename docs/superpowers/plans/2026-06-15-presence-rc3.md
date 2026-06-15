# RC3 Multi-device Presence — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship per-user aggregate presence (ONLINE/AWAY/OFFLINE, multi-device-aware) + live PRESENCE_CHANGE events as
1.10.0-RC3, and fix the latent RC2 dead-node user-binding leak.

**Architecture:** A new Redis-only `PresenceRegistry` SPI (hash `netty:presence:{b64userId}`, atomic Lua transition
detection) parallel to RC2's `UserRegistry`. Events ride a dedicated reserved broker channel with a new
`PRESENCE_CHANGE` envelope kind (no version bump). The dead-node reconciliation path is fixed to reap **and emit
OFFLINE events** for presence + finally reap `UserRegistry` (closing the RC2 leak). The session hook splits into
identity / offline / presence capabilities.

**Tech Stack:** Spring Boot 2.7.18, Lettuce 6.1.10, JDK 17, JUnit 5, Mockito, Testcontainers (real Redis on
localhost:16379 for ITs).

**Spec:** `docs/superpowers/specs/2026-06-15-presence-rc3.md` (design-review-folded). **Branch:**
`feature/1.10.0-presence` (already created; spec + review archive already committed at `c79c528`).

---

## File Structure

**Module `netty-spring-websocket-cluster`** (package root
`com/github/berrywang1996/netty/spring/web/websocket/cluster`):
- `spi/PresenceStatus.java` — enum ONLINE/AWAY/OFFLINE.
- `spi/UserPresence.java` — immutable {aggregate, onlineConnections, awayConnections}.
- `spi/PresenceTransition.java` — immutable {userId, oldAggregate, newAggregate} + `changed()`.
- `spi/PresenceChangeListener.java` — `onPresenceChange(userId, old, new)`.
- `spi/PresenceRegistry.java` — the SPI (5 ops + shutdown).
- `room/RedisPresenceRegistry.java` — Redis hash + atomic Lua impl.
- `InMemoryPresenceRegistry.java` (test scope) — in-process stub mirroring transition semantics.
- `spi/PresenceOperations.java` — public sub-interface (setPresence / setPresenceForUser / getPresence).
- `spi/ClusterEnvelope.java` — add `PRESENCE_CHANGE` to `MessageKind`.
- `ClusterMessageSender.java` — implement `PresenceOperations`; `onPresenceMessage` listener; reserved-channel
  subscribe at `start()`; publish-on-transition; self-suppression; nullable `presenceRegistry`; stats; the
  leader-side reap-publish entry point.
- `ClusterSessionHookImpl.java` — flag-split (identity/offline/presence) + presence write on connect/disconnect.
- `node/ClusterNodeManager.java` — reap fan-out for userRegistry + presenceRegistry on the leader-elected primary path.
- `ClusterRuntimeStats.java` — presence counters.

**Module `netty-websocket-cluster-spring-boot-starter`** (package
`com/github/berrywang1996/netty/spring/boot/configure`):
- `NettyWebSocketClusterConfigure.java` — presence beans, identity-gating refactor, reconciliation wiring, reserved-URI
  guard.
- `NettyClusterMeterBinder.java` — presence meters.
- `support/OnAnyRedisSpiRequired.java` — presence clause.
- `ClusterProperties.java` — `Presence` inner class.
- `resources/META-INF/.../additional-spring-configuration-metadata.json` — presence keys.

---

## Conventions (read before any task)

- Property naming: `enable` (never `enabled`). Lombok `@Slf4j`. Mockito only in tests.
- Run module tests WITHOUT `-q` (it hides surefire counts): `mvn -pl netty-spring-websocket-cluster test -o`.
- Real Redis live on `localhost:16379`; Docker up. If a real-Redis test flakes once, run it 3×.
- Base64url userId encoding + hash-tag `{b64userId}` exactly as `RedisUserRegistry` (read it first:
  `room/RedisUserRegistry.java`). Dedicated 2-thread executor named `cluster-presence-N`.
- DO NOT bump any POM version (controller does the RC3 cut). DO NOT push.
- Commit per task with the message in the task's final step.

---

## Task 1: Presence value types + SPI + InMemory stub

**Files:**
- Create: `.../cluster/spi/PresenceStatus.java`, `.../spi/UserPresence.java`, `.../spi/PresenceTransition.java`,
  `.../spi/PresenceChangeListener.java`, `.../spi/PresenceRegistry.java`
- Create: `.../cluster/InMemoryPresenceRegistry.java` (test sources:
  `netty-spring-websocket-cluster/src/test/java/.../cluster/InMemoryPresenceRegistry.java`)
- Test: `.../cluster/InMemoryPresenceRegistryTest.java`

- [ ] **Step 1: Write the value types + SPI** (no behavior yet to test; these are types Task 2 tests against)

`PresenceStatus.java`:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.spi;
/** Presence status. OFFLINE only ever appears as a user AGGREGATE (zero connections), never a stored connection status. */
public enum PresenceStatus { ONLINE, AWAY, OFFLINE }
```

`PresenceTransition.java` (immutable; `userId` null for single-user setters where the caller already knows it, set for
reap results):
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.spi;
public final class PresenceTransition {
    private final String userId;
    private final PresenceStatus oldAggregate;
    private final PresenceStatus newAggregate;
    public PresenceTransition(String userId, PresenceStatus oldAggregate, PresenceStatus newAggregate) {
        this.userId = userId; this.oldAggregate = oldAggregate; this.newAggregate = newAggregate;
    }
    public String getUserId() { return userId; }
    public PresenceStatus getOldAggregate() { return oldAggregate; }
    public PresenceStatus getNewAggregate() { return newAggregate; }
    public boolean changed() { return oldAggregate != newAggregate; }
    public PresenceTransition withUserId(String u) { return new PresenceTransition(u, oldAggregate, newAggregate); }
}
```

`UserPresence.java`:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.spi;
public final class UserPresence {
    private final PresenceStatus aggregate;
    private final int onlineConnections;
    private final int awayConnections;
    public UserPresence(PresenceStatus aggregate, int onlineConnections, int awayConnections) {
        this.aggregate = aggregate; this.onlineConnections = onlineConnections; this.awayConnections = awayConnections;
    }
    public PresenceStatus getAggregate() { return aggregate; }
    public int getOnlineConnections() { return onlineConnections; }
    public int getAwayConnections() { return awayConnections; }
    public int getTotalConnections() { return onlineConnections + awayConnections; }
    /** Derive an aggregate from connection counts (shared by every impl). */
    public static PresenceStatus aggregateOf(int online, int away) {
        if (online > 0) return PresenceStatus.ONLINE;
        if (away > 0) return PresenceStatus.AWAY;
        return PresenceStatus.OFFLINE;
    }
}
```

`PresenceChangeListener.java`:
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.spi;
/** App callback fired once per user AGGREGATE transition (old != new): local-first on the origin node, and on receive
 *  on every other RC3 node (origin self-suppresses its own echo). The app owns the roster and pushes to watchers. */
public interface PresenceChangeListener {
    void onPresenceChange(String userId, PresenceStatus oldAggregate, PresenceStatus newAggregate);
}
```

`PresenceRegistry.java` (copy the §3 javadoc from the spec verbatim onto each method):
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.spi;
import java.util.List;
import java.util.concurrent.CompletionStage;
public interface PresenceRegistry {
    CompletionStage<PresenceTransition> setPresence(String userId, String nodeId, String sessionId, PresenceStatus status);
    CompletionStage<PresenceTransition> setPresenceForUser(String userId, PresenceStatus status);
    CompletionStage<PresenceTransition> clearPresence(String userId, String nodeId, String sessionId);
    CompletionStage<UserPresence> getPresence(String userId);
    /** Transition-aware dead-node reap: returns one PresenceTransition (userId set) per user whose aggregate changed. */
    CompletionStage<List<PresenceTransition>> removeAllForNode(String nodeId);
    void shutdown();
}
```

- [ ] **Step 2: Write the failing InMemory test** (`InMemoryPresenceRegistryTest`)

```java
// key cases (one @Test each):
// 1. firstConnect_offlineToOnline: setPresence(u,nA,s1,ONLINE) → transition OFFLINE→ONLINE, changed()==true
// 2. secondConnect_noTransition: after s1 ONLINE, setPresence(u,nA,s2,ONLINE) → ONLINE→ONLINE, changed()==false
// 3. allAway_aggregateAway: s1,s2 both AWAY → getPresence aggregate==AWAY, onlineConnections==0, awayConnections==2
// 4. lastClear_onlineToOffline: clear s1 then s2 → final clearPresence transition ONLINE→OFFLINE
// 5. setPresenceForUser_allAway: s1 ONLINE,s2 ONLINE → setPresenceForUser(u,AWAY) → ONLINE→AWAY, both fields AWAY
// 6. removeAllForNode_emitsTransitions: u has s1 on nodeA(ONLINE), v has s2 on nodeA(ONLINE) + s3 on nodeB(ONLINE);
//    removeAllForNode(nodeA) → list contains (u: ONLINE→OFFLINE) and NOT v (v still ONLINE via nodeB) → size 1
// 7. getPresence_unknownUser_offline: getPresence("nobody") aggregate==OFFLINE, counts 0
```

- [ ] **Step 3: Run it — verify FAIL** (`InMemoryPresenceRegistry` does not exist).
  Run: `mvn -pl netty-spring-websocket-cluster test -o -Dtest=InMemoryPresenceRegistryTest` → FAIL (compile error).

- [ ] **Step 4: Implement `InMemoryPresenceRegistry`** — a `ConcurrentHashMap<String userId, Map<String field,
  PresenceStatus>>` (field = `nodeId|sessionId`), guarded per-userId so transitions are atomic. Compute aggregate via
  `UserPresence.aggregateOf`. `removeAllForNode` iterates all users, removes `nodeId|`-prefixed fields, collects
  changed transitions. Mirror the no-cache reads (snapshot each call).

- [ ] **Step 5: Run — verify PASS.** Run the same command → all 7 pass.

- [ ] **Step 6: Commit**
```bash
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/spi/Presence*.java \
        netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/spi/UserPresence.java \
        netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/InMemoryPresenceRegistry*.java
git commit -m "feat(cluster): PresenceRegistry SPI + value types + InMemory stub (RC3 T1)"
```

---

## Task 2: RedisPresenceRegistry — atomic Lua transition detection

**Files:**
- Create: `.../cluster/room/RedisPresenceRegistry.java`
- Test: `.../cluster/redis/RedisPresenceRegistryTest.java` (Mockito)

**Grounding:** read `room/RedisUserRegistry.java` first — copy its base64url `b64(userId)`, hash-tag `{b64userId}`,
dedicated executor, and `CompletionStage` async patterns verbatim. Key prefix: `PRESENCE_PREFIX = "netty:presence:"`,
key = `netty:presence:{b64userId}`. Field = `nodeId + "|" + sessionId`.

- [ ] **Step 1: Write the Lua scripts as constants.** Shared aggregate function inlined into each script.

`SET_LUA` (KEYS[1]=hashKey; ARGV[1]=field; ARGV[2]=status):
```lua
local function agg(k)
  local vals = redis.call('HVALS', k)
  if #vals == 0 then return 'OFFLINE' end
  for _,v in ipairs(vals) do if v == 'ONLINE' then return 'ONLINE' end end
  return 'AWAY'
end
local old = agg(KEYS[1])
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
local new = agg(KEYS[1])
return {old, new}
```

`SET_USER_LUA` (KEYS[1]=hashKey; ARGV[1]=status): set ALL fields to status:
```lua
local function agg(k) ... end -- same as above
local old = agg(KEYS[1])
local fields = redis.call('HKEYS', KEYS[1])
for _,f in ipairs(fields) do redis.call('HSET', KEYS[1], f, ARGV[1]) end
local new = agg(KEYS[1])
return {old, new}
```

`CLEAR_LUA` (KEYS[1]=hashKey; ARGV[1]=field): HDEL one field, DEL key if empty:
```lua
local function agg(k) ... end
local old = agg(KEYS[1])
redis.call('HDEL', KEYS[1], ARGV[1])
local new = agg(KEYS[1])
if redis.call('HLEN', KEYS[1]) == 0 then redis.call('DEL', KEYS[1]) end
return {old, new}
```

`REAP_LUA` (KEYS[1]=hashKey; ARGV[1]=nodePrefix e.g. "nodeA|"): HDEL fields starting with the prefix:
```lua
local function agg(k) ... end
local old = agg(KEYS[1])
local fields = redis.call('HKEYS', KEYS[1])
local p = ARGV[1]
for _,f in ipairs(fields) do
  if string.sub(f, 1, string.len(p)) == p then redis.call('HDEL', KEYS[1], f) end
end
local new = agg(KEYS[1])
if redis.call('HLEN', KEYS[1]) == 0 then redis.call('DEL', KEYS[1]) end
return {old, new}
```

- [ ] **Step 2: Write the failing Mockito test** (`RedisPresenceRegistryTest`). Mock
  `StatefulRedisConnection<String,String>` → `sync()`/`async()` → `RedisCommands`/`RedisAsyncCommands`. Verify:

```java
// 1. setPresence_runsSetLuaAndMapsTransition: stub sync().eval(SET_LUA, ...) → returns List.of("OFFLINE","ONLINE");
//    assert setPresence(...).toCompletableFuture().get() == PresenceTransition(null, OFFLINE, ONLINE), changed()==true;
//    verify eval called with KEYS=[netty:presence:{b64(user)}], ARGV=["nodeA|s1","ONLINE"].
// 2. clearPresence_runsClearLua: stub eval(CLEAR_LUA,...) → ["ONLINE","OFFLINE"]; assert transition + key/arg shape.
// 3. setPresenceForUser_runsSetUserLua: stub → ["ONLINE","AWAY"]; verify ARGV=["AWAY"].
// 4. getPresence_readsHvalsAndDerives: stub sync().hvals(key) → ["ONLINE","AWAY"]; assert UserPresence aggregate ONLINE,
//    online==1, away==1.
// 5. getPresence_fresh_noCache: call twice → hvals invoked TWICE (no caching) — verify(times(2)).
// 6. removeAllForNode_scansAndReapsPerHash: stub scan → 2 presence keys; stub eval(REAP_LUA,...) per key →
//    key1 ["ONLINE","OFFLINE"] (changed), key2 ["ONLINE","ONLINE"] (unchanged); assert result list size 1 with the
//    decoded userId of key1; verify REAP_LUA ARGV[0]=="nodeA|".
```

- [ ] **Step 3: Run — verify FAIL** (`RedisPresenceRegistry` missing).
  Run: `mvn -pl netty-spring-websocket-cluster test -o -Dtest=RedisPresenceRegistryTest` → FAIL.

- [ ] **Step 4: Implement `RedisPresenceRegistry`.** Constructor `(StatefulRedisConnection<String,String> conn)`.
  `setPresence/clearPresence/setPresenceForUser`: `conn.async().eval(LUA, ScriptOutputType.MULTI, new
  String[]{key(userId)}, args...)` → map the returned `List<String>` (old,new) → `PresenceTransition(null,
  PresenceStatus.valueOf(old), PresenceStatus.valueOf(new))`, run on the dedicated executor. `getPresence`:
  `conn.async().hvals(key)` → count ONLINE/AWAY → `new UserPresence(aggregateOf(...), online, away)`. `removeAllForNode`:
  `SCAN MATCH netty:presence:*` (cursor loop on the executor, like `RedisUserRegistry.removeAllForNode`), for each key
  run `REAP_LUA` with `ARGV=nodeId+"|"`, decode userId from the key (strip prefix + `{}`, base64url-decode), collect
  `PresenceTransition(userId, old, new)` where `old != new`. `key(userId)` = `PRESENCE_PREFIX + "{" + b64(userId) +
  "}"`. Dedicated executor; `shutdown()` mirrors `RedisUserRegistry`.

- [ ] **Step 5: Run — verify PASS** (all 6).

- [ ] **Step 6: Commit**
```bash
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/room/RedisPresenceRegistry.java \
        netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/redis/RedisPresenceRegistryTest.java
git commit -m "feat(cluster/redis): RedisPresenceRegistry — atomic Lua transition detection (RC3 T2)"
```

---

## Task 3: PRESENCE_CHANGE envelope kind + rolling-upgrade safety

**Files:**
- Modify: `.../cluster/spi/ClusterEnvelope.java` (add `PRESENCE_CHANGE` to `MessageKind`)
- Test: `.../cluster/EnvelopeRollingUpgradeTest.java` (add a case) + the codec test for the new kind

**Grounding:** read `spi/ClusterEnvelope.java` (MessageKind enum + version) and the codec
(`SimpleTextEnvelopeCodec` / wherever `MessageKind` is (de)serialized) + `EnvelopeRollingUpgradeTest`. Confirm the kind
is encoded such that adding an enum value does NOT shift the field layout and **does not bump `CURRENT_VERSION`** (stays
2).

- [ ] **Step 1: Write the failing test** in `EnvelopeRollingUpgradeTest`:
```java
// presenceChange_roundTripsOnV2_noVersionBump:
//   ClusterEnvelope e = new ClusterEnvelope(node, PRESENCE_CHANNEL, MessageKind.PRESENCE_CHANGE,
//       "u|ONLINE|OFFLINE".getBytes(UTF_8), null, null, 123L);
//   byte[] wire = codec.encode(e); ClusterEnvelope d = codec.decode(wire);
//   assertEquals(MessageKind.PRESENCE_CHANGE, d.getKind());
//   assertEquals(ClusterEnvelope.CURRENT_VERSION, 2); // unchanged
```
Also assert the codec does not throw on a normal v2 BROADCAST after the enum addition (forward-safety smoke).

- [ ] **Step 2: Run — verify FAIL** (`PRESENCE_CHANGE` undefined).
  Run: `mvn -pl netty-spring-websocket-cluster test -o -Dtest=EnvelopeRollingUpgradeTest` → FAIL.

- [ ] **Step 3: Add `PRESENCE_CHANGE`** to `MessageKind` (append as the last enum constant so existing ordinals are
  unchanged). If the codec serializes kind by `name()`, no further change; if by ordinal, confirm append keeps prior
  ordinals stable. Do NOT change `CURRENT_VERSION` or `FIELD_COUNT`.

- [ ] **Step 4: Run — verify PASS.**

- [ ] **Step 5: Commit**
```bash
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/spi/ClusterEnvelope.java \
        netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/EnvelopeRollingUpgradeTest.java
git commit -m "feat(cluster): PRESENCE_CHANGE envelope kind — no version bump, rolling-upgrade safe (RC3 T3)"
```

---

## Task 4: ClusterMessageSender — PresenceOperations + dedicated listener + publish

**Files:**
- Create: `.../cluster/spi/PresenceOperations.java`
- Modify: `.../cluster/ClusterMessageSender.java` (implement `PresenceOperations`; reserved-channel constant +
  subscribe at `start()`; `onPresenceMessage`; publish-on-transition helper; self-suppression; nullable
  `presenceRegistry` + `presenceChangeListener` + `publishChanges`; stats; `setPresenceRegistry`/`setPresenceChangeListener` setters)
- Modify: `.../cluster/ClusterRuntimeStats.java` (presence counters)
- Test: `.../cluster/ClusterPresenceTest.java` (InMemory-backed)

**Grounding:** read how `ClusterMessageSender` implements `RoomOperations`/`UserOperations`, how `start()` subscribes
(`subscribeBroadcast`, `subscribeUnicast`), `onBroadcastMessage`/`onRoomMessage` self-suppression
(`if (nodeManager.getNodeId().equals(env.getOriginNodeId())) { ...Dropped++; return; }`), and `getClusterRuntimeStats()`.

- [ ] **Step 1: Write `PresenceOperations.java`:**
```java
package com.github.berrywang1996.netty.spring.web.websocket.cluster.spi;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import java.util.concurrent.CompletionStage;
public interface PresenceOperations {
    CompletionStage<Void> setPresence(MessageSession session, PresenceStatus status);
    CompletionStage<Void> setPresenceForUser(String userId, PresenceStatus status);
    CompletionStage<UserPresence> getPresence(String userId);
}
```

- [ ] **Step 2: Add presence counters to `ClusterRuntimeStats`** (AtomicLong + inc + getters):
  `presenceChanges`, `presenceEventsPublished`, `presenceEventsReceived`, `presenceSelfDeliveryDropped`,
  `presenceSet`, `presenceReapOffline`.

- [ ] **Step 3: Write the failing test** `ClusterPresenceTest` (construct `ClusterMessageSender` with `InMemoryPresenceRegistry`
  + a stub broker capturing publishes; wire a recording `PresenceChangeListener`):
```java
// 1. setPresence_online_publishesTransition_andFiresListenerLocally:
//    resolver returns "u" for the session; setPresence(session, AWAY) on a user that was ONLINE → listener fired
//    once locally with (u, ONLINE, AWAY); broker.publish called once on PRESENCE_CHANNEL with kind PRESENCE_CHANGE
//    and payload "u|ONLINE|AWAY"; presenceChanges incremented.
// 2. noTransition_noPublish_noListener: setPresence to same aggregate (ONLINE→ONLINE) → no publish, no listener.
// 3. onPresenceMessage_remote_firesListener: feed an envelope with a DIFFERENT originNodeId → listener fired,
//    presenceEventsReceived incremented.
// 4. onPresenceMessage_selfEcho_suppressed: feed an envelope with THIS node's originNodeId → listener NOT fired,
//    presenceSelfDeliveryDropped incremented.
// 5. getPresence_delegates: getPresence("u") returns the registry's UserPresence.
// 6. presenceDisabled_throws: with presenceRegistry==null, setPresence/getPresence throw IllegalStateException.
// 7. publishChanges_false_noPublish_butListenerStillLocal: publishChanges=false → local listener fires, broker.publish NOT called.
```

- [ ] **Step 3b: Run — verify FAIL.** `mvn -pl netty-spring-websocket-cluster test -o -Dtest=ClusterPresenceTest`.

- [ ] **Step 4: Implement in `ClusterMessageSender`:**
  - Constant `public static final String PRESENCE_CHANNEL = "__netty_cluster_presence__";` (a control name; Task 8
    forbids it as an app URI).
  - Fields: `volatile PresenceRegistry presenceRegistry;` `volatile PresenceChangeListener presenceChangeListener;`
    `volatile boolean presencePublishChanges = true;` + setters.
  - `implements ... , PresenceOperations`.
  - `setPresence(session, status)`: resolve userId (via the injected resolver; if null throw IllegalState if disabled),
    nodeId = local, call `presenceRegistry.setPresence(userId, nodeId, sessionId, status)` → on the returned
    `PresenceTransition` call `firePresenceTransition(userId, t)`. `setPresenceForUser` similarly via
    `presenceRegistry.setPresenceForUser`. `getPresence` delegates. All throw `IllegalStateException("presence disabled")`
    when `presenceRegistry == null`.
  - `firePresenceTransition(userId, t)`: if `!t.changed()` return. `presenceChanges++`. Fire the local listener
    directly (local-first): `if (presenceChangeListener != null) presenceChangeListener.onPresenceChange(userId,
    old, new)`. Then if `presencePublishChanges`: build a `PRESENCE_CHANGE` envelope (origin=localNode,
    uri=PRESENCE_CHANNEL, payload=`userId|old|new` UTF-8) and `broker.publish(PRESENCE_CHANNEL, env)`;
    `presenceEventsPublished++`. (This method is also the entry point Task 6's reaper calls for reap-emitted events,
    with a flag to skip the local listener when the reap happens on a non-watcher node — see Task 6.)
  - `onPresenceMessage(envelope)`: `if (localNode.equals(env.getOriginNodeId())) { presenceSelfDeliveryDropped++;
    return; }` (self-suppression). Parse `userId|old|new` from payload; `presenceEventsReceived++`; if
    `presenceChangeListener != null` fire it.
  - In `start()`: `if (presenceRegistry != null) broker.subscribe(PRESENCE_CHANNEL, this::onPresenceMessage);`
    UNCONDITIONAL (not gated on `getRegisteredUri`).

- [ ] **Step 5: Run — verify PASS** (all 7).

- [ ] **Step 6: Commit**
```bash
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/spi/PresenceOperations.java \
        netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterMessageSender.java \
        netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterRuntimeStats.java \
        netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterPresenceTest.java
git commit -m "feat(cluster): PresenceOperations + dedicated presence listener + publish-on-transition (RC3 T4)"
```

---

## Task 5: Session hook flag-split + presence write on connect/disconnect

**Files:**
- Modify: `.../cluster/ClusterSessionHookImpl.java`
- Test: `.../cluster/PresenceHookTest.java`

**Grounding:** read the current `ClusterSessionHookImpl` (the single `offlineEnabled` flag at L84, the
`onSessionRegistered` branch L91-135, `onSessionRemoved` L170-199).

- [ ] **Step 1: Write the failing test** `PresenceHookTest` (InMemory registries + recording sender):
```java
// 1. presenceOnly_connect_bindsAndSetsOnline: offline OFF, presence ON. onSessionRegistered → userRegistry.bindUser
//    called AND presenceRegistry.setPresence(u, node, s, ONLINE) called (proving identity-split: presence-only binds).
//    drainOnConnect NOT called (offline off).
// 2. presenceOnly_disconnect_clears: onSessionRemoved → presenceRegistry.clearPresence(u,node,s) called + unbindUser.
// 3. presenceAndOffline_connect_bindsSetsOnlineAndDrains: both ON → bind + setPresence(ONLINE) + drainOnConnect all fire.
// 4. identityOff_connect_emptyMap: presence OFF, offline OFF → register(emptyMap), no resolve, no bind, no setPresence
//    (byte-identical to RC2).
// 5. connect_offlineToOnline_firesTransitionPublish: first device of a user → sender.firePresenceTransition path
//    invoked with ONLINE transition (assert via the recording sender).
```

- [ ] **Step 2: Run — verify FAIL.** `mvn -pl netty-spring-websocket-cluster test -o -Dtest=PresenceHookTest`.

- [ ] **Step 3: Refactor the hook.** Add constructor param `PresenceRegistry presenceRegistry`. Compute:
```java
this.identityEnabled = userIdResolver != null && userRegistry != null;
this.offlineEnabled  = identityEnabled && offlineStore != null;
this.presenceEnabled = identityEnabled && presenceRegistry != null;
```
  In `onSessionRegistered`: gate `resolve userId + register-with-userId + bindUser` on `identityEnabled` (was
  `offlineEnabled`). After `bindUser(...).thenRun(...)`: if `offlineEnabled` → `drainOnConnect`; if `presenceEnabled` →
  `clusterSender.setPresenceFromHook(userId, nodeId, sessionId, ONLINE)` (a thin internal entry that calls
  `presenceRegistry.setPresence` then `firePresenceTransition`). In `onSessionRemoved`: if `presenceEnabled` →
  `clusterSender.clearPresenceFromHook(userId, nodeId, sessionId)`; keep `unbindUser` on `identityEnabled`. Keep the
  RC1-compat 3-arg + RC2 7-arg constructors delegating with `presenceRegistry=null`.

- [ ] **Step 4: Run — verify PASS** (all 5).

- [ ] **Step 5: Commit**
```bash
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterSessionHookImpl.java \
        netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterMessageSender.java \
        netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/PresenceHookTest.java
git commit -m "feat(cluster): hook identity/offline/presence flag-split + presence-on-connect (RC3 T5)"
```

---

## Task 6: Reconciliation reap fix — wire userRegistry + presenceRegistry on the leader path (BLOCKER + RC2 fix)

**Files:**
- Modify: `.../cluster/node/ClusterNodeManager.java` (reap fan-out on the leader-elected primary path)
- Modify: `.../cluster/ClusterMessageSender.java` (a `reapPresenceForDeadNode(nodeId)` entry that runs
  `presenceRegistry.removeAllForNode` and publishes one PRESENCE_CHANGE per changed user — NO local listener double-fire)
- Test: `.../cluster/ReapPresenceTest.java`

**Grounding:** read `ClusterNodeManager.doReconciliation` (L492-543) — the `sessionRegistry.removeAllForNode(dead)
.thenRunAsync(...)` chain on `reconScheduler`, the `.exceptionally` re-queue (L528-533), and `reaper.tryClaim` (L499).
Read `ClusterMessageSender.invalidateCacheForNode` (the existing roomRegistry reap at L1069).

- [ ] **Step 1: Write the failing test** `ReapPresenceTest`:
```java
// 1. reap_lastDeviceOnDeadNode_publishesOffline: InMemoryPresenceRegistry: user u has ONLY a connection on nodeA.
//    Call sender.reapPresenceForDeadNode("nodeA") → broker.publish called once on PRESENCE_CHANNEL with payload
//    "u|ONLINE|OFFLINE"; presenceReapOffline incremented; local PresenceChangeListener NOT double-fired here
//    (the publish carries it to watcher nodes; the reaping node may not be a watcher).
// 2. reap_userStillOnElsewhere_noEvent: user v on nodeA AND nodeB; reapPresenceForDeadNode("nodeA") → still ONLINE
//    via nodeB → NO publish for v.
// 3. userRegistryReap_wired (RC2 gap regression): a stub UserRegistry recording removeAllForNode; trigger the
//    ClusterNodeManager dead-node path → assert userRegistry.removeAllForNode(dead) WAS called (today it is not).
```
  For case 3, unit-test `ClusterNodeManager` with mock `sessionRegistry` + mock `userRegistry` + mock
  `presenceReaper` callback, a heartbeat returning one expired node, and a reaper that claims → assert all three reap
  calls happen on the chained path.

- [ ] **Step 2: Run — verify FAIL.** `mvn -pl netty-spring-websocket-cluster test -o -Dtest=ReapPresenceTest`.

- [ ] **Step 3: Implement.**
  - `ClusterMessageSender.reapPresenceForDeadNode(String deadNodeId)`: if `presenceRegistry == null` return completed;
    else `presenceRegistry.removeAllForNode(deadNodeId)` → for each returned `PresenceTransition` (userId set,
    changed): `presenceReapOffline++`; if `presencePublishChanges` build + `broker.publish(PRESENCE_CHANNEL, env)`
    with payload `userId|old|new`. Do NOT fire the local listener here (publish reaches watcher nodes incl. self-echo
    which `onPresenceMessage` suppresses — so to still notify local watchers, do NOT suppress for reap... — instead:
    publish WITHOUT self-suppression concern by also firing the local listener once: simplest correct rule — fire the
    local listener AND publish; `onPresenceMessage` self-suppression prevents the origin's own echo from double-firing).
    Implement: for each changed transition, call `firePresenceTransition(userId, t)` (which fires local + publishes) —
    reuse the Task 4 helper. This guarantees exactly-once locally + one publish; remote nodes get it via the channel.
  - `ClusterNodeManager`: add nullable `Function<String, CompletionStage<Void>>`-style hooks
    `userRegistryReaper` and `presenceReaper` (set by auto-config, like `deadNodeCallback`). In `doReconciliation`,
    chain them on the SAME leader-elected path as `sessionRegistry.removeAllForNode`, after it completes, BEFORE/with
    the existing `.exceptionally` re-queue so a failure re-runs next sweep. Concretely: after
    `sessionRegistry.removeAllForNode(dead).thenComposeAsync(v -> userRegistryReaper.apply(dead), reconScheduler)
    .thenComposeAsync(v -> presenceReaper.apply(dead), reconScheduler).thenRunAsync(() -> { heartbeat.deregister(dead);
    deadNodeCallback... }, reconScheduler).exceptionally(...re-queue...)`. The `presenceReaper` is
    `dead -> clusterSender.reapPresenceForDeadNode(dead)` (returns a CompletionStage). The `userRegistryReaper` is
    `dead -> userRegistry.removeAllForNode(dead)`.

- [ ] **Step 4: Run — verify PASS** (all 3, incl. the RC2-gap regression).

- [ ] **Step 5: Commit**
```bash
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/node/ClusterNodeManager.java \
        netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterMessageSender.java \
        netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ReapPresenceTest.java
git commit -m "fix(cluster): leader-elected reap of userRegistry + presenceRegistry + reap-OFFLINE events (RC3 T6, closes RC2 userRegistry-reap gap)"
```

---

## Task 7: Config + metrics + metadata

**Files:**
- Modify: `.../cluster/ClusterProperties.java` (add `Presence` inner class + accessor)
- Modify: starter `.../configure/NettyClusterMeterBinder.java` (presence meters)
- Modify: starter `.../resources/META-INF/spring-configuration-metadata`/`additional-spring-configuration-metadata.json`
- Test: extend the meter-binder test

- [ ] **Step 1: Add `ClusterProperties.Presence`** (mirror the `Offline` inner class):
```java
public static class Presence {
    private boolean enable = false;
    private boolean publishChanges = true;
    // getters/setters
}
private Presence presence = new Presence();
public Presence getPresence() { return presence; }
public void setPresence(Presence p) { this.presence = p; }
```

- [ ] **Step 2: Add presence meters** in `NettyClusterMeterBinder` (read-through counters, mirror the offline block):
  `netty.cluster.presence.changes`, `.events_published`, `.events_received`, `.self_delivery_dropped`, `.set`,
  `.reap_offline`. Extend the meter-binder unit test asserting the 6 meters are registered.

- [ ] **Step 3: Add metadata json** entries for `server.netty.websocket.cluster.presence.enable` and
  `...presence.publish-changes` (description + default), mirroring the offline metadata entries.

- [ ] **Step 4: Run — verify PASS.** `mvn -pl netty-websocket-cluster-spring-boot-starter -am test -o`.

- [ ] **Step 5: Commit**
```bash
git add netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/ClusterProperties.java \
        netty-websocket-cluster-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/boot/configure/NettyClusterMeterBinder.java \
        netty-websocket-cluster-spring-boot-starter/src/main/resources/META-INF/*configuration-metadata.json \
        netty-websocket-cluster-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/boot/configure/*MeterBinder*Test.java
git commit -m "feat(cluster): presence.* config + netty.cluster.presence.* meters + metadata (RC3 T7)"
```

---

## Task 8: Auto-config — gated presence beans, identity-gating refactor, OnAnyRedisSpiRequired, reserved-URI guard

**Files:**
- Modify: starter `.../configure/NettyWebSocketClusterConfigure.java`
- Modify: `.../cluster/support/OnAnyRedisSpiRequired.java`
- Test: `.../configure/NettyWebSocketClusterConfigureTest.java` (add presence context cases)

**Grounding:** read the offline bean block (~L520-564), the `userIdResolver`/`userRegistry` gates, `OnAnyRedisSpiRequired`
(L70-93), and how the sender + hook + nodeManager beans are wired (`setUserRegistry`, the hook constructor, the
`deadNodeCallback`).

- [ ] **Step 1: Write failing context tests** in `NettyWebSocketClusterConfigureTest`:
```java
// 1. presenceEnabled_createsPresenceRegistryAndWiresSenderHook: presence.enable=true (standalone Redis) →
//    PresenceRegistry bean present; sender.getPresenceRegistry()!=null; hook presenceEnabled.
// 2. presenceDisabled_noPresenceBean: default → no PresenceRegistry bean.
// 3. presenceOnly_offlineOff_identityBeansPresent: presence.enable=true, offline.enable=false →
//    UserIdResolver + UserRegistry beans EXIST (identity-gating moved to offline||presence); OfflineQueueStore ABSENT.
// 4. presenceOnly_allFourCustomCoreSpi_redisConnectionExists (the BLOCKER #3 regression): presence.enable=true,
//    offline.enable=false, user supplies custom ClusterBroker+SessionRegistry+ClusterNodeHeartbeat+ClusterReaper →
//    nettyClusterRedisConnection bean STILL created (OnAnyRedisSpiRequired honors presence).
// 5. presenceEnabled_onClusterTransport_noPresenceBean: presence.enable=true + redis.cluster-nodes set →
//    no RedisPresenceRegistry (transport-gated to standalone), no orphan.
// 6. reservedUri_failsFast: register a @MessageMapping whose URI == ClusterMessageSender.PRESENCE_CHANNEL →
//    context startup FAILS with a clear message.
```

- [ ] **Step 2: Run — verify FAIL.** `mvn -pl netty-websocket-cluster-spring-boot-starter -am test -o -Dtest=NettyWebSocketClusterConfigureTest`.

- [ ] **Step 3: Implement.**
  - **`OnAnyRedisSpiRequired.matches`:** add
    `boolean presenceEnabled = Boolean.parseBoolean(env.getProperty("server.netty.websocket.cluster.presence.enable","false"));`
    and `|| (presenceEnabled && !hasBean(bf, PresenceRegistry.class))`.
  - **Identity-gating refactor:** change the `userIdResolver` and `userRegistry` bean conditions from
    `@ConditionalOnProperty(...offline... enable=true)` to an expression matching `offline.enable OR presence.enable`
    (e.g. `@ConditionalOnExpression("${server.netty.websocket.cluster.offline.enable:false} or
    ${server.netty.websocket.cluster.presence.enable:false}")`, keeping `STANDALONE_REDIS_REGISTRY` and
    `@ConditionalOnMissingBean`). Leave `offlineQueueStore` gated on `offline.enable` only.
  - **`presenceRegistry` bean:** `@Bean(destroyMethod="shutdown") @ConditionalOnExpression(STANDALONE_REDIS_REGISTRY)
    @ConditionalOnProperty(prefix="server.netty.websocket.cluster.presence", name="enable", havingValue="true")
    @ConditionalOnMissingBean(PresenceRegistry.class)` → `new RedisPresenceRegistry(nettyClusterRedisConnection)`.
  - **Wire sender + hook + nodeManager:** pass `presenceRegistry` (nullable) into the hook constructor; call
    `sender.setPresenceRegistry(presenceRegistry)`, `sender.setPresencePublishChanges(presence.publishChanges)`, and
    `sender.setPresenceChangeListener(presenceChangeListener)` (a `@Autowired(required=false)` app bean — null if the
    app supplies none). Wire `nodeManager.setUserRegistryReaper(userRegistry)` and
    `nodeManager.setPresenceReaper(sender::reapPresenceForDeadNode)` (Task 6) whenever those beans exist — **including
    fixing the RC2 gap: always wire `userRegistryReaper` when `userRegistry != null`**, independent of presence.
  - **Reserved-URI guard:** at startup (a `SmartInitializingSingleton` or the existing post-startup wiring), assert no
    registered `@MessageMapping` URI equals `ClusterMessageSender.PRESENCE_CHANNEL`; throw `IllegalStateException` with
    a clear message if so.

- [ ] **Step 4: Run — verify PASS** (all 6).

- [ ] **Step 5: Commit**
```bash
git add netty-websocket-cluster-spring-boot-starter/src/main/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigure.java \
        netty-spring-websocket-cluster/src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/support/OnAnyRedisSpiRequired.java \
        netty-websocket-cluster-spring-boot-starter/src/test/java/com/github/berrywang1996/netty/spring/boot/configure/NettyWebSocketClusterConfigureTest.java
git commit -m "feat(cluster/autoconfig): gated presence beans + identity-gating(offline||presence) + OnAnyRedisSpiRequired presence clause + reserved-URI guard + RC2 userRegistry-reaper wiring (RC3 T8)"
```

---

## Task 9: Integration tests + multi-node E2E (the headline crash→OFFLINE)

**Files:**
- Create: `.../cluster/RedisPresenceRegistryIntegrationTest.java`
- Create: `.../cluster/PresenceCrashReapE2ETest.java` (the headline)
- Create: `.../cluster/UserRegistryReapRegressionIT.java` (RC2-gap regression on real Redis)

**Grounding:** read `ClusterTestRedis` (the IT Redis resolver) + an existing two-node E2E
(`OfflineDeliveryE2ETest`) for the two-node harness pattern.

- [ ] **Step 1: `RedisPresenceRegistryIntegrationTest`** (real Redis): round-trips for setPresence/clearPresence/
  setPresenceForUser/getPresence aggregate; `removeAllForNode` deletes only the target node's fields and returns the
  right transitions; concurrent two-"node" first-connect yields exactly one ONLINE transition (drive two setPresence
  calls and assert only one reports `OFFLINE→ONLINE`).

- [ ] **Step 2: `PresenceCrashReapE2ETest` (HEADLINE):** two `ClusterMessageSender` instances (node-A, node-B) on one
  real Redis, both subscribed to `PRESENCE_CHANNEL`, each with a recording `PresenceChangeListener`. Connect user `u`'s
  only device on node-A (→ ONLINE event observed on B). **Simulate a hard crash of node-A** (do NOT call graceful
  disconnect — instead let node-A's heartbeat expire / invoke node-B's reconciliation reap for node-A directly). Assert
  node-B's listener receives `u: ONLINE→OFFLINE` (the reap-emitted event) and `getPresence("u").aggregate == OFFLINE`.
  This is the dominant-path guarantee the design review flagged as the BLOCKER.

- [ ] **Step 3: `UserRegistryReapRegressionIT`:** bind user `u` with a member on `nodeDead` directly via
  `RedisUserRegistry`; run the reconciliation reap for `nodeDead`; assert `isUserOnline("u")` is now false / the
  `nodeDead|...` member is gone (today, pre-fix, it would leak). Proves the RC2 gap is closed end-to-end.

- [ ] **Step 4: Run — verify PASS** (`mvn -pl netty-spring-websocket-cluster test -o`; if any IT flakes once, run 3×).

- [ ] **Step 5: Commit**
```bash
git add netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/RedisPresenceRegistryIntegrationTest.java \
        netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/PresenceCrashReapE2ETest.java \
        netty-spring-websocket-cluster/src/test/java/com/github/berrywang1996/netty/spring/web/websocket/cluster/UserRegistryReapRegressionIT.java
git commit -m "test(cluster): presence IT + crash→OFFLINE-reap two-node E2E + RC2 userRegistry-reap regression IT (RC3 T9)"
```

---

## Task 10: Docs — release notes + cluster-design + api-guide (honest)

**Files (Edit tool ONLY; verify no U+FFFD after):**
- Modify: `docs/release-notes-1.10.0.md` (add an RC3 section EN + 中文)
- Modify: `docs/cluster-design.md` (presence subsection + the RC2-reap-fix note in the scope/known-issues area)
- Modify: `docs/api-guide.md` §9 (PresenceOperations API + `presence.*` config + the advisory-read + broadcast-ceiling
  caveats)

- [ ] **Step 1: Release notes RC3 section** — honest positioning verbatim from spec §1/§10: per-user aggregate presence
  (multi-device-aware) + live events; **per-device addressing deferred** (DeviceIdResolver, recorded); broadcast-ceiling
  caveat; stale-ONLINE advisory-read contract; the **RC2 dead-node user-binding leak fix** (call it out as a
  correctness fix); config + meters; the design-review record (3 BLOCKER + 3 MAJOR + 3 MINOR folded; link the archive).
  Bilingual. Leave the test count line as `<RC3 COUNT>` for the controller to fill at cut.

- [ ] **Step 2: cluster-design.md** — presence subsection (model, Lua transition detection, reserved-channel events,
  reap-emitted OFFLINE) + a note that 1.10.0-RC3 fixed the RC2 reconciliation gap (UserRegistry now reaped).

- [ ] **Step 3: api-guide.md §9** — `((PresenceOperations) sender).setPresence(session, AWAY)` /
  `setPresenceForUser(userId, AWAY)` / `getPresence(userId)` examples; `presence.enable` / `presence.publish-changes`;
  the production note that `getPresence` is advisory (not a liveness probe) and presence events are broadcast (~10-node
  ceiling).

- [ ] **Step 4: Verify** no U+FFFD (`grep -rl $'\xef\xbf\xbd' docs/release-notes-1.10.0.md docs/cluster-design.md docs/api-guide.md` → none) + valid UTF-8.

- [ ] **Step 5: Commit**
```bash
git add docs/release-notes-1.10.0.md docs/cluster-design.md docs/api-guide.md
git commit -m "docs(cluster): RC3 presence — model + reserved-channel events + RC2 reap-fix + honest positioning (RC3 T10)"
```

---

## Controller (NOT the implementer) — Task 11: cut RC3

- Adversarial impl-review workflow (4 lenses: atomicity/reap-correctness, rolling-upgrade, gating, regression) → fold
  any must-fixes.
- POM bump `1.10.0-RC2` → `1.10.0-RC3` across 11 poms; full 11-module reactor; fill the release-notes test count from
  the reactor aggregate; Doc Sync Matrix (development-plan history table RC3 → cut; CLAUDE.md version line).
- Tag `v1.10.0-RC3`; FF-merge `feature/1.10.0-presence` → master; STOP before push.

---

## Self-Review

**Spec coverage:** §2 model → T1/T2 (status + Lua aggregate). §3 SPIs → T1. §4 storage+Lua → T2. §5 reconciliation reap
fix (BLOCKER + RC2) → T6 + T9 (E2E + regression IT). §6 events/reserved channel/kind → T3 + T4 + T8 (reserved-URI
guard). §7 hook split → T5. §8 auto-config/OnAnyRedisSpiRequired/identity-gating → T8 (+ BLOCKER #3 context test). §9
PresenceOperations → T4. §10 honest positioning → T10. §11 config+metrics → T7. §12 backward-compat byte-identical →
T5 case 4 + T8. §13 review record → T10. Every BLOCKER has a dedicated task + regression test (T6, T8, T3/T4).

**Placeholder scan:** the only intentional placeholder is the release-notes `<RC3 COUNT>` (controller fills at cut, by
design). No TBD/"handle edge cases"/vague steps.

**Type consistency:** `PresenceStatus`/`UserPresence`/`PresenceTransition`/`PresenceChangeListener`/`PresenceRegistry`/
`PresenceOperations` signatures identical across T1→T9. `PRESENCE_CHANNEL` constant defined in T4, forbidden in T8,
exercised in T9. `setPresence`/`setPresenceForUser`/`clearPresence`/`getPresence`/`removeAllForNode` names consistent.
`firePresenceTransition` (T4) reused by T5 (connect) and T6 (reap). `userRegistryReaper`/`presenceReaper` defined in T6,
wired in T8.
