# netty-spring Project Context

## Project and authoritative documents

- Spring Boot integration library for Netty: HTTP MVC, WebSocket, AES-GCM crypto, Micrometer,
  and opt-in cluster/IM primitives. Apache-2.0; Maven group `io.github.berrywang1996`.
- Read the root `pom.xml` for the working version and dependency versions. The 1.x platform is
  Spring Boot 2.7.18 / JDK 17; 2.0.0 targets Spring Boot 4.1 as a behavior-preserving migration.
- [Development plan](../docs/development-plan.md): the single current roadmap, migration steps,
  and deferred work. Do not restore Boot 3.x or bundle new features into 2.0.0 from historical notes.
- [1.10.1 release notes](../docs/release-notes-1.10.1.md): patch scope, checks, and publication status.
  A POM version or a local tag is not evidence that Maven Central publication completed.
- [Release checklist](../docs/release-checklist.md): build, release, signing, and publication workflow.
- [API guide](../docs/api-guide.md), [cluster design](../docs/cluster-design.md), and
  [dependency governance](../docs/dependency-governance.md): current behavior and operational constraints.
- [Promotion playbook](../docs/promotion-playbook.md): assets, channels, and publication gates.
- Past release notes and `docs/superpowers/` are historical evidence, not another active roadmap.

## Module structure

```text
netty-spring-web                           core bootstrap and HTTP dispatch
netty-spring-webmvc                        MVC routing
netty-spring-websocket                     WebSocket mapping, MessageSender, crypto
netty-spring-websocket-cluster             transport/registry SPIs and implementations
netty-spring-boot-autoconfigure            shared auto-configuration
netty-web-spring-boot-starter               HTTP + WebSocket starter
netty-webmvc-spring-boot-starter            HTTP MVC starter
netty-websocket-spring-boot-starter         WebSocket starter
netty-websocket-cluster-spring-boot-starter opt-in cluster starter
demo-netty-web-spring-boot-starter          demo application (not published to Central)
```

The Maven reactor contains the parent plus these ten modules. Prefer the checked-in Maven wrapper.
Use the release checklist for environment-specific signing and Central setup; do not copy tokens
or signing credentials into project documentation.

## Architecture and conventions

- Java packages remain `com.github.berrywang1996.netty.spring.web.*` despite the Maven group change.
- Cluster configuration uses `server.netty.websocket.cluster.*`; `enable=false` by default.
  All feature switches use `enable`, consistent with MVC/WebSocket/crypto configuration.
- `ClusterBroker` separates transport from distributed session routing. Redis, NATS, and mesh
  implementations already exist; check the implementation table before proposing them as new work.
- Redis Pub/Sub has documented capacity limits. Mesh benefits depend on audience locality;
  do not claim unconditional fan-out reduction or unlimited scale.
- Envelopes carry `originNodeId`; origin self-delivery suppression prevents duplicate local delivery.
- Use Lombok `@Slf4j`; conditional auto-configuration must remain safe when optional dependencies
  are absent. Class-level conditions must protect optional-library method signatures.
- Tests use JUnit 5 and Netty `EmbeddedChannel`; follow existing module test patterns.
  Cluster checks require the documented Redis/NATS/Docker infrastructure.
- Commit messages use `type: description` (`fix`, `feat`, `docs`, `release`). Attribute actual
  contributors only. Apply the release checklist's review and verification requirements.
