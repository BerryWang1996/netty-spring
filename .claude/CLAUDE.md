# netty-spring Project Context

## Project
- **What**: Spring Boot integration library for Netty — HTTP MVC + WebSocket + AES-GCM crypto + Micrometer observability
- **Owner**: BerryWang1996 (wangbor@yeah.net), single maintainer
- **License**: Apache 2.0
- **Maven Central**: `io.github.berrywang1996:netty-*` (namespace verified at central.sonatype.com)
- **Current version**: 1.7.1 (published to Maven Central: 1.4.0, 1.6.2, 1.7.0, 1.7.1)
- **In development**: 1.8.0 (WebSocket cluster support via Redis, with ClusterBroker/SessionRegistry SPI)
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

## 1.8.0 Development Progress (as of 2026-05-29)
Module skeletons created and validated (mvn validate passes 11 modules):
- `netty-spring-websocket-cluster/` — pom.xml done, package `...web.websocket.cluster.*`
- `netty-websocket-cluster-spring-boot-starter/` — pom.xml done

ALL CODE DONE — 11 modules compile + all tests pass (no commits yet). Created files:

SPI layer (`...websocket.cluster.spi/`):
- `ClusterBroker` — publish/unicast/subscribe/subscribeUnicast/state/shutdown
- `SessionRegistry` — register/deregister/lookupNode/clusterSessionIds/removeAllForNode/shutdown
- `ClusterEnvelope` — originNodeId/uri/kind/payload/targetSessionId/traceparent/timestamp
- `ClusterMessageListener` / `ClusterSubscription` / `ClusterBrokerException` / `BrokerState`

Node lifecycle (`...websocket.cluster.node/`):
- `NodeState` enum — JOINING/ACTIVE/DEGRADED/RESYNC/DRAINING/LEFT
- `ClusterNodeManager` — heartbeat scheduler, reconciliation sweep, state transitions
- `ClusterNodeHeartbeat` interface + `NodeStateListener` interface

Core (`...websocket.cluster/`):
- `ClusterMessageSender` — implements MessageSender, local-first + broker fan-out, self-delivery suppression, node cache
- `ClusterProperties` — full server.netty.websocket.cluster.* config binding

Redis impl (`...websocket.cluster.redis/`):
- `RedisPubSubBroker` — Lettuce PUBLISH/SUBSCRIBE, JSON envelope ser/de, base64 payload
- `RedisSessionRegistry` — HSET/HGET/SADD/DEL pipeline, bulk node cleanup
- `RedisClusterNodeHeartbeat` — SET+TTL, HSET nodes hash, findExpiredNodes reconciliation

Auto-config (`netty-websocket-cluster-spring-boot-starter`):
- `NettyWebSocketClusterConfigure` — @ConditionalOnProperty(cluster.enable=true), wires all beans
- `META-INF/spring.factories` — registers the auto-config

Tests (`...websocket.cluster/` test):
- `InMemoryBroker` + `InMemorySessionRegistry` — stub SPI impls proving no Lettuce dependency
- `ClusterMessageSenderTest` — 6 tests: broadcast+self-suppression, unicast routing, local queries, cluster-wide queries, cache invalidation

REMAINING:
- Version bump to 1.8.0-SNAPSHOT (task #29)
- Session lifecycle hooks in MessageMappingResolver (task #36) — register/deregister on connect/close

## Key Docs
- `docs/cluster-design.md` — full 1.8.0 cluster design (430+ lines, includes capacity model + ADR-001)
- `docs/development-plan.md` — roadmap: 1.8.0 → 1.9.x (NATS) → 2.0.0 (Boot 3.x) → 2.1.0 (enterprise security)
- `docs/release-checklist.md` — reusable release process
- `docs/api-guide.md` — 12-section user guide
