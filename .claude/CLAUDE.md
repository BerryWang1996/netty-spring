# netty-spring Project Context

## Project
- **What**: Spring Boot integration library for Netty — HTTP MVC + WebSocket + AES-GCM crypto + Micrometer observability
- **Owner**: BerryWang1996 (wangbor@yeah.net), single maintainer
- **License**: Apache 2.0
- **Maven Central**: `io.github.berrywang1996:netty-*` (namespace verified at central.sonatype.com)
- **Current version**: 1.10.0-RC4b (IN DEVELOPMENT — 1.10.0 "IM platform foundation" line on Boot 2.7, built on top of 1.9.0 GA. Tagged `v1.10.0-RC1`..`v1.10.0-RC4b`, NOT pushed/deployed. **RC1** ClusterRoomRegistry — per-room node-targeted routing (pivoted from a consistent-hashing shard ring after design review proved shards collapse to global broadcast under random LB; reduction = N/k by targeting only member-hosting nodes; ceiling-break is RC4, not RC1). **RC2** offline queue + user-addressable delivery (`OfflineQueueStore`/`UserRegistry`/`UserIdResolver` SPIs; `sendToUser` realtime-or-queue; per-user Redis Stream + drain lock; 3 design + 7 impl-review fixes folded). **RC3** multi-device presence (`PresenceRegistry` SPI — per-user aggregate ONLINE/AWAY/OFFLINE via atomic Lua transition detection + `PRESENCE_CHANGE` events on a reserved channel + `PresenceChangeListener`; design-review 3 BLOCKER+3 MAJOR+3 MINOR folded; **also fixed a latent RC2 bug — `UserRegistry.removeAllForNode` was never wired into dead-node reconciliation, leaking crashed-node user bindings**; per-device *addressing* (stable `DeviceIdResolver`) honestly deferred). **RC4a** node-to-node mesh transport foundation (`MeshBroker implements ClusterBroker` over direct Netty TCP — drop-in for `RedisPubSubBroker`; registry/heartbeat stay on Redis, off the message hot path; `MeshNodeDirectory` SPI + `RedisMeshNodeDirectory` for node-address discovery; **naive broadcast = all peers, NO fan-out reduction yet — interest routing is RC4b, the actual ceiling-break**; design-review folds M1 outbound-backpressure BLOCKER + M2 frame-cap + M3 dispatch-offload + M4 advertised-host fail-fast + M5 total-isolation degrade + derived membership; impl-review folds MF1 `live-by-heartbeat ∩ has-address` membership + MF2 connect-timeout wiring + BL1 outbound-cache replace; RC4c backlog = peer-address snapshot cache off the hot path, mesh-vs-clusterNodes/NATS fail-fast guard, idle-timeout handler, tick-before-listener reconcile, mTLS; RC4d = full `netty.cluster.mesh.*` meters). **RC4b** interest-routed mesh broadcast — **session-grained** fan-out reduction (`MeshInterestRegistry` SPI + `RedisMeshInterestRegistry` mirroring `RedisRoomRegistry`'s two-key + JOIN/LEAVE Lua, minus the room dimension; interest = node has ≥1 **live local session** for the URI, decided atomically in-Lua; `MeshInterestRouter` 5s send-cache with null-on-failure⇒all-peers / authoritative-empty⇒prune sentinel; **reserved channels (`PRESENCE_CHANNEL`) bypass pruning**; `ClusterBroker.onNodeLeft` additive default for the dead-node reap; reduction **genuine on a homogeneous fleet for physically-partitioned live audiences**, ~0 for global topics OR high-population topics under random LB — the recorded RC1 shard-ring honesty (disclosed in §1); **three** adversarial design-review rounds folded **12 + 3 + 1** BLOCKER/MAJOR findings + impl-review folded **7 MINOR**; 620 tests/11 modules). Remaining: RC4c robustness → RC4d metrics → 1.10.0 GA → commercial promotion playbook. Final 1.10.0 cut only when the cycle completes.) **Latest stable on Central: 1.9.0 GA (released 2026-06-07, deployed).** Earlier published: 1.4.0, 1.6.2, 1.7.0, 1.7.1, 1.8.0
- **Next (this 1.10.0 cycle)**: RC4a mesh transport foundation ✅ cut (`v1.10.0-RC4a`) → RC4b interest-routed broadcast ✅ cut (`v1.10.0-RC4b` — session-grained fan-out reduction) → **RC4c robustness (peer-address snapshot cache, mesh-vs-clusterNodes/NATS guard, idle-timeout, reconnect backoff, mTLS; approach-C interest-change notifications)** → RC4d metrics → 1.10.0 GA → commercial promotion playbook. Then later 2.0.0 (Boot 3.x; unlocks sharded pub/sub + Observation API). Drive the RC chain to GA (per-RC cadence: brainstorm→spec→adversarial design-review→plan→subagent impl→adversarial impl-review→cut RC, FF-merge+tag, STOP before push).
- **Spring Boot**: 2.7.18 (Boot 3.x migration planned for 2.0.0)
- **JDK**: 17 (GraalVM JDK 17.0.11)
- **Build**: Maven 3.9.9, 11 modules (including 2 new cluster modules)

## Module Structure (1.9.0)
```
netty-spring-web                          — core Netty bootstrap, HTTP dispatch
netty-spring-webmvc                       — MVC routing (@RequestMapping)
netty-spring-websocket                    — WebSocket mapping, MessageSender API, crypto
netty-spring-websocket-cluster            — [NEW] ClusterBroker/SessionRegistry SPI + Redis impl
netty-spring-boot-autoconfigure           — shared auto-configuration
netty-web-spring-boot-starter             — HTTP + WebSocket combined starter
netty-webmvc-spring-boot-starter          — HTTP MVC only starter
netty-websocket-spring-boot-starter       — WebSocket only starter
netty-websocket-cluster-spring-boot-starter — [NEW] cluster starter (requires enable=true)
demo-netty-web-spring-boot-starter        — demo app (not published to Central)
```

## Key Architecture Decisions (1.9.0)
- **ADR-001**: Redis-first cluster middleware, NATS additive later (see docs/cluster-design.md)
- **Transport SPI**: `ClusterBroker` (fan-out + unicast) + `SessionRegistry` (presence + routing) — Redis is impl #1, NATS/mesh are future drop-ins
- **Config namespace**: `server.netty.websocket.cluster.*` (only activated with `enable=true`)
- **Known ceiling**: Redis Pub/Sub safe only ≤~10 nodes for active broadcast; beyond → sharded pub/sub → mesh
- **Origin self-delivery suppression**: MUST include originNodeId in envelope to prevent broadcast duplicates

## Build & Release
- **GPG key**: `09CC1D729D9E1CBBA18DE39E8B24A2A210E4168C` (empty passphrase; Gpg4win at `C:\Program Files\GnuPG\bin\gpg.exe`)
- **Deploy to Central**: `mvn deploy -pl '!demo-netty-web-spring-boot-starter' -P release -DskipTests "-Dgpg.executable=C:\Program Files\GnuPG\bin\gpg.exe"` (PowerShell; run `gpgconf --kill all; gpgconf --launch keyboxd` first)
- **Central Portal**: central.sonatype.com, autoPublish=false (manual review)
- **gh CLI**: logged in as BoruiWangIxoran (different from repo owner BerryWang1996) — GitHub Releases need BerryWang1996 identity
- **settings.xml**: `~/.m2/settings.xml` has `<server id="central">` with token, `<server id="gpg.passphrase">` placeholder
- **Mirror**: Aliyun mirror active for central; `rdc` and `huawei` profiles active

## Coding Conventions
- Java package: `com.github.berrywang1996.netty.spring.web.*` (unchanged despite groupId migration)
- Lombok `@Slf4j` for logging
- `@ConditionalOnClass` / `@ConditionalOnBean` / `@ConditionalOnProperty` for Spring Boot auto-config
- Config property naming: always `enable` (not `enabled`) — consistent with `mvc.enable`, `websocket.enable`, `crypto.enable`
- Tests: JUnit 5, EmbeddedChannel for Netty pipeline tests, Mockito in autoconfigure module only
- Commit style: `type: description` (fix/feat/docs/release), include `Co-Authored-By: Claude ...`
- Pre-release: 4-way parallel code audit + adversarial verification model

## 1.9.0 Status (✅ GA — released 2026-06-07, deployed to Maven Central. Feature set below. **Active line is now 1.10.0** — see `docs/development-plan.md` + `docs/release-notes-1.10.0.md` for RC1/RC2 and the RC3/RC4→GA roadmap.)
Cluster reliability hardening. 444 tests / 11 modules green at GA (1.10.0 builds on this; current RC2 total is higher).
Single-node (cluster.enable=false) is production-grade and behaviorally identical to 1.7.x/1.8.0; cluster mode
targets ≤~10 nodes + a dedicated, secured Redis.

**1.9.0 ships 5 reliability items deferred from 1.8.0:**
1. Redis-loss grace period (`redis-loss-grace-period-ms`, default 5000 ms; `0` = instant/1.8.0 behavior). **Only intentional default-behavior change.**
2. Heartbeat/reconciliation thread isolation (two dedicated schedulers `cluster-hb`/`cluster-recon`) + batched EXISTS in `findExpiredNodes`.
3. Atomic Lua deregister (`HGET→DEL→SREM` as single `EVAL`). Signature unchanged.
4. Reconciliation leader-election (`ClusterReaper` SPI; `RedisClusterReaper` uses `SET NX`). `@ConditionalOnMissingBean`.
5. Registry write coalescing (`session-registry-write-rate`, default 1000 ops/s; `CoalescingRegistryWriter` token-bucket; register never dropped).

**5-layer pluggable SPI** (all `@ConditionalOnMissingBean`; signatures UNCHANGED from 1.8.0):
- `ClusterBroker` (cross-node transport; `RedisPubSubBroker` default)
- `SessionRegistry` (distributed session routing; `RedisSessionRegistry` default, atomic Lua deregister)
- `EnvelopeCodec` (wire format; `SimpleTextEnvelopeCodec` zero-dep default — NO Jackson)
- `MessagePayloadCodec` (message body; `DefaultMessagePayloadCodec` T:/J:/B: default)
- `ClusterNodeHeartbeat` (`RedisClusterNodeHeartbeat` SET+TTL default)
- `ClusterReaper` (NEW in 1.9.0; `RedisClusterReaper` SET-NX leader-election default)

**Delivery semantics**: local delivery never lost; cross-node broadcast at-most-once (Pub/Sub fire-and-forget,
no replay); unicast undeliverable → MessageSessionClosedException to caller. Reliable replay path
(reliableBroadcast / Redis Streams) is future — NOT in 1.9.0.

**Still deferred** (documented in cluster-design.md): HMAC envelope auth, full Micrometer meter-binder set,
multi pub/sub connections, sharded pub/sub, Redis Cluster client, W3C trace propagation, NATS broker,
reliable delivery (Redis Streams), multi-node demo + Testcontainers.

Docs: `docs/api-guide.md` §9 (WebSocket Cluster), `docs/cluster-design.md` (scope table + §Security + roadmap),
`docs/release-notes-1.9.0.md`, README cluster section (EN+中文).

## Key Docs
- `docs/cluster-design.md` — cluster design + 1.8.0/1.9.0 scope table + capacity model + ADR-001
- `docs/development-plan.md` — roadmap: 1.9.0 → 2.0.0 (Boot 3.x) → 2.1.0 (enterprise security)
- `docs/release-checklist.md` — reusable release process
- `docs/api-guide.md` — 13-section user guide (incl. §9 WebSocket Cluster with 1.9.0 config rows)
- `docs/release-notes-1.9.0.md` — 1.9.0 release notes (the 5 reliability items + upgrade guide)
