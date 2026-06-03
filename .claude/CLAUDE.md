# netty-spring Project Context

## Project
- **What**: Spring Boot integration library for Netty — HTTP MVC + WebSocket + AES-GCM crypto + Micrometer observability
- **Owner**: BerryWang1996 (wangbor@yeah.net), single maintainer
- **License**: Apache 2.0
- **Maven Central**: `io.github.berrywang1996:netty-*` (namespace verified at central.sonatype.com)
- **Current version**: 1.9.0-RC5 (IN DEVELOPMENT — multi-RC cycle, tagged `v1.9.0-RC1`..`v1.9.0-RC5`, NOT pushed/deployed. RC1 reliability hardening (5 items); RC2 reliable broadcast (Redis Streams); RC3 HMAC envelope auth; RC4 full Micrometer cluster metrics; RC5 multi-node E2E + Testcontainers CI + a **cross-node unicast hook-wiring fix** — the E2E found that cluster-mode cross-node unicast/targeted-close were silently broken in 1.8.0~RC4 (ClusterSessionHook never wired onto resolvers due to eager-server-start vs `@AutoConfigureAfter` ordering); fixed via a `SmartInitializingSingleton` that wires the hook post-startup. Final 1.9.0 cut only when the user says the cycle is complete). Latest stable on Central: 1.8.0. Earlier published: 1.4.0, 1.6.2, 1.7.0, 1.7.1, 1.8.0
- **Next (this 1.9.0 cycle)**: user picks one at a time, or cut final 1.9.0. Remaining roadmap: runnable multi-node Docker demo (Compose+LB+browser), W3C TraceContext propagation, NATS broker, multi/sharded pub/sub, Redis Cluster client → later 2.0.0 (Boot 3.x).
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

## 1.9.0 Status (IN DEVELOPMENT — RC1 tagged v1.9.0-RC1; NOT pushed/deployed; final 1.9.0 cut when the cycle completes, incl. reliable delivery)
Cluster reliability hardening. 304 tests / 11 modules green. Single-node (cluster.enable=false) is
production-grade and behaviorally identical to 1.7.x/1.8.0; cluster mode targets ≤~10 nodes + a dedicated,
secured Redis.

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
