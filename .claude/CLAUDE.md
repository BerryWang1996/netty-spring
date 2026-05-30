# netty-spring Project Context

## Project
- **What**: Spring Boot integration library for Netty — HTTP MVC + WebSocket + AES-GCM crypto + Micrometer observability
- **Owner**: BerryWang1996 (wangbor@yeah.net), single maintainer
- **License**: Apache 2.0
- **Maven Central**: `io.github.berrywang1996:netty-*` (namespace verified at central.sonatype.com)
- **Current version**: 1.8.0 (WebSocket cluster support; committed + tagged `v1.8.0`, deployed to Central pending manual Publish). Earlier published: 1.4.0, 1.6.2, 1.7.0, 1.7.1
- **Next**: 1.9.x (cluster hardening — see development-plan.md), 2.0.0 (Boot 3.x)
- **Spring Boot**: 2.7.18 (Boot 3.x migration planned for 2.0.0)
- **JDK**: 17 (GraalVM JDK 17.0.11)
- **Build**: Maven 3.9.9, 11 modules (including 2 new cluster modules)

## Module Structure (1.8.0)
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

## Key Architecture Decisions (1.8.0)
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

## 1.8.0 Status (RELEASED — committed, tagged v1.8.0, deployed to Central pending manual Publish)
WebSocket cluster support. 289 tests / 11 modules green. Single-node (cluster.enable=false) is
production-grade and behaviorally identical to 1.7.x; cluster mode targets ≤~10 nodes + a dedicated,
secured Redis. Multiple pre-release review rounds (correctness/concurrency, security, production/API,
release/build) + a "fix until N clean rounds" loop hardened it.

**5-layer pluggable SPI** (all `@ConditionalOnMissingBean`):
- `ClusterBroker` (cross-node transport; `RedisPubSubBroker` default) — publish/unicast/subscribe/state/shutdown
- `SessionRegistry` (distributed session routing; `RedisSessionRegistry` default, SCAN-based clusterSessionIds)
- `EnvelopeCodec` (wire format; `SimpleTextEnvelopeCodec` zero-dep pipe-delimited default — NO Jackson)
- `MessagePayloadCodec` (message body; `DefaultMessagePayloadCodec` T:/J:/B: default)
- `ClusterNodeHeartbeat` (`RedisClusterNodeHeartbeat` SET+TTL default)

**Core**: `ClusterMessageSender` (@Primary MessageSender, local-first + broker fan-out, self-delivery
suppression, single-lookup unicast, node cache); `ClusterNodeManager` (JOINING→ACTIVE→DEGRADED→RESYNC→
DRAINING→LEFT, heartbeat + reconciliation, configurable reconnect jitter); `ClusterRuntimeStats`;
`ClusterSessionHook(Impl)` (session lifecycle → registry); `ClusterProperties` (only functional knobs —
no lying config).

**Hardening done in 1.8.0**: bean destroyMethod lifecycle; `@AutoConfigureAfter(MessageSenderSupportConfigure)`
ordering; Redis URI password redaction + no-TLS/no-auth warn; inbound message size cap (remote-OOM guard);
async publish failure logged (not silent) + `onPublishFailure` policy; `onRedisLoss` degrade-to-local/close-all;
`messageMaxSizeBytes`; degrade-skip visibility counter; `ClusterHealthIndicator` (/actuator/health → nettyCluster);
spring-configuration-metadata for IDE hints; deps pinned (spring-framework 5.3.39, snakeyaml 1.33).

**Delivery semantics**: local delivery never lost; cross-node broadcast at-most-once (Pub/Sub fire-and-forget,
no replay); unicast undeliverable → MessageSessionClosedException to caller. Reliable replay path
(reliableBroadcast / Redis Streams) is 1.9.x — NOT in 1.8.0.

**Deferred to 1.9.x** (documented in cluster-design.md): HMAC envelope auth, full Micrometer meter-binder set,
reconciliation leader-election, deregister atomicity, scheduler isolation, multi pub/sub connections, sharded
pub/sub, Redis Cluster client, W3C trace propagation, multi-node demo + Testcontainers.

Docs: `docs/api-guide.md` §9 (WebSocket Cluster), `docs/cluster-design.md` (scope table + §Security + roadmap),
`docs/release-notes-1.8.0.md`, README cluster section (EN+中文).

## Key Docs
- `docs/cluster-design.md` — full 1.8.0 cluster design (430+ lines, includes capacity model + ADR-001)
- `docs/development-plan.md` — roadmap: 1.8.0 → 1.9.x (NATS) → 2.0.0 (Boot 3.x) → 2.1.0 (enterprise security)
- `docs/release-checklist.md` — reusable release process
- `docs/api-guide.md` — 13-section user guide (incl. §9 WebSocket Cluster)
