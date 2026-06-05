# NATS Broker — Design Spec

> Date: 2026-06-04 · Target: **1.9.x cycle** (develops on `master` @ `326f48d`; cuts `v1.9.0-RC9`) · Status: design approved (delegated)
> Implements ADR-001's "NATS as the scaling tier" — an additive `ClusterBroker` over the existing transport-agnostic SPI.

## Overview / Goal

An additive **`NatsClusterBroker`** — a NATS core-pub/sub implementation of the `ClusterBroker` SPI, parallel to
`RedisPubSubBroker`, selected by config. It is ADR-001's "scaling tier": a drop-in transport for deployments that
hit Redis Pub/Sub's fan-out wall, switchable via the SPI with **zero business-logic change**. NATS's
interest-routed, horizontally-scalable server side removes the single-Redis delivery bottleneck.

**Scope is the broker (transport) ONLY (confirmed, per ADR-001):** `SessionRegistry`, `ClusterNodeHeartbeat`, and
`ClusterReaper` stay on Redis (or user-provided). A NATS deployment runs the "NATS broker + Redis registry" mixed
model: **broadcast is NATS-only, but cross-node unicast / targeted-close still need the Redis registry** for
session→node routing. ADR-001 explicitly rejects NATS-only (JetStream-KV registry) as YAGNI for ≤10-node users
and a higher adoption barrier — so it is out of scope.

## Scope

**In:** `NatsClusterBroker` (core pub/sub, at-most-once); a `nats.servers` config selector; auto-config 3-way
broker selection (NATS / standalone-Redis / cluster-Redis); an optional `io.nats:jnats` dependency; a NATS
Testcontainers resolver + integration test + a Mockito unit test; docs.

**Out:**
- **JetStream** (at-least-once / KV) — core pub/sub only; a JetStream reliable broker would parallel
  `RedisStreamsReliableBroker` (future). `publishAsync`/`unicastAsync` keep the SPI default.
- **NATS impls of `SessionRegistry`/heartbeat/reaper** — registry stays Redis (ADR-001).
- **A separate Maven module** — `NatsClusterBroker` lives in the existing cluster module (it needs that module's
  codec/auth/SPI directly; a new module would only depend back on it).
- **Throughput/benchmark assertions** — the testable invariant is publish→receive correctness.

## Architecture

`ClusterMessageSender` depends only on the `ClusterBroker` + `SessionRegistry` SPIs, never on a transport class
(ADR-001). So swapping the broker bean to `NatsClusterBroker` is transparent: the sender broadcasts/unicasts the
same envelopes; only the wire transport changes from Redis channels to NATS subjects.

```
 publish:   connection.publish("netty.broadcast." + b64url(uri),  wrap(encode(env)))   (fire-and-forget, at-most-once)
 unicast:   connection.publish("netty.unicast."   + b64url(node), wrap(encode(env)))

 subscribe: channelListeners.put(subject, listener); dispatcher.subscribe(subject)     (NATS interest routing)
 inbound:   dispatcher MessageHandler ─► onInboundMessage(subject, bytes)
                                          (size-guard → channelListeners.get → unwrap → decode → listener.onMessage)
 health:    ConnectionListener events ─► state CAS (DISCONNECTED→DEGRADED, (RE)CONNECTED→ACTIVE) + TransportStateListener
```

## Components

### `NatsClusterBroker implements ClusterBroker` (`…cluster.nats`)
The NATS twin of `RedisPubSubBroker`, same `EnvelopeCodec` + `MessageAuthenticator` + inbound-size-cap +
`TransportStateListener` contract.

- **Subjects:** `BROADCAST_PREFIX = "netty.broadcast."`, `UNICAST_PREFIX = "netty.unicast."`. The uri/nodeId is
  **base64url-encoded** into a single subject token (`Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(UTF_8))`).
  Rationale: NATS subjects are `.`-delimited and forbid space/`*`/`>`/empty tokens; base64url yields only
  `[A-Za-z0-9_-]`, is injective (no collisions), and pub+sub use the identical encoding → exact-match delivery.
- **Connection (2-phase init for the build-time listener):** the broker is constructed with the codec + auth (no
  connection yet) and a public `onConnectionEvent(Connection, ConnectionListener.Events)` method; the auto-config
  then builds the NATS `Connection` with `Options.builder().server(servers).connectionListener(broker::onConnectionEvent)
  .maxReconnects(-1).build()` and calls `broker.attach(connection)`. (NATS sets the listener at build time, so it
  cannot be added post-connect; this keeps the broker mockable — the unit test calls `attach(mockConnection)`.)
- **`publish`/`unicast`:** `checkActive()` then `connection.publish(subject, wrap(codec.encode(env)).getBytes(UTF_8))`
  — fire-and-forget (at-most-once). NATS auto-flushes; no async confirmation (SPI default for `publishAsync`).
- **`subscribe`/`subscribeUnicast`:** one shared `Dispatcher` (`connection.createDispatcher()`); put the listener
  in a `ConcurrentHashMap<String, ClusterMessageListener> channelListeners` keyed by subject, then
  `dispatcher.subscribe(subject)`. A single dispatcher `MessageHandler` routes every inbound `Message` to
  `onInboundMessage(msg.getSubject(), msg.getData())`. `ClusterSubscription.unsubscribe()` →
  `dispatcher.unsubscribe(subject)` + `channelListeners.remove(subject)`.
- **`onInboundMessage(subject, bytes)`:** inbound-size guard (`bytes.length`) → `channelListeners.get(subject)` →
  `authenticator.unwrap(new String(bytes, UTF_8))` → `codec.decode` → `listener.onMessage` (mirrors the Redis
  broker's adapter body). Runs on NATS dispatcher threads; the downstream listener (`ClusterMessageSender`) is
  already concurrency-safe.
- **Health:** `onConnectionEvent` does the `AtomicReference<BrokerState>` CAS and fires the `TransportStateListener`
  — event-driven, same model as `RedisConnectionStateListener`.
- **`state()`, `setInboundMaxBytes(int)`, `setTransportStateListener(l)`, `shutdown()`** (closes the NATS
  connection) — identical contract to the Redis broker.
- **Constructors:** `(EnvelopeCodec)`, `(EnvelopeCodec, MessageAuthenticator)` (auth defaults to NoOp) + the
  `attach(Connection)` method. (The connection is injected post-construction so the build-time listener can target
  the broker.)

### Config (`ClusterProperties.Nats`, nested like `Reliable`/`Auth`)
`server.netty.websocket.cluster.nats.servers` — comma-separated `nats://host:port,…` (default empty/null).
Non-empty selects the NATS broker; empty = unchanged (Redis broker). Accessor `getNats().getServers()`. (Minimal;
connection tuning — name, TLS, credentials — can follow, or users provide their own `Connection` bean.)

### Auto-config — 3-way broker selection (`NettyWebSocketClusterConfigure`)
Order-independent SpEL mutual exclusion (the RC7 model), so exactly one `ClusterBroker` wins:
- New constant `NATS_TRANSPORT = "!('${server.netty.websocket.cluster.nats.servers:}'.trim().isEmpty())"` and its
  negation `NO_NATS_TRANSPORT`.
- The two Redis broker beans (`clusterBroker`, `clusterBrokerCluster`) get `&& NO_NATS_TRANSPORT` appended to their
  existing `@ConditionalOnExpression` (so they yield when `nats.servers` is set).
- New `Connection nettyClusterNatsConnection(ClusterProperties)` bean: `@ConditionalOnClass(io.nats.client.Connection)`
  + `@ConditionalOnExpression(NATS_TRANSPORT)` + `@ConditionalOnMissingBean(io.nats.client.Connection)`,
  `destroyMethod = "close"`. Built via the 2-phase wiring with the broker's listener.
- New `ClusterBroker clusterBrokerNats(...)` bean: `@ConditionalOnClass(io.nats.client.Connection)` +
  `@ConditionalOnExpression(NATS_TRANSPORT)` + `@ConditionalOnMissingBean(ClusterBroker)`; constructs the
  `NatsClusterBroker`, `attach(connection)`, `setInboundMaxBytes(...)` (mirroring the Redis broker bean).
- **Registry / heartbeat / reaper / the Redis client + connection are UNCHANGED** — always Redis (standalone or
  cluster per `cluster-nodes`). The NATS deployment is the mixed model.
- **jnats classpath requirement:** `io.nats:jnats` is **optional** in the cluster module and the starter.
  `@ConditionalOnClass(Connection)` gracefully no-ops the NATS path if jnats is absent. **Setting `nats.servers`
  without jnats on the classpath yields no `ClusterBroker` → context fails** (a clear misconfiguration; documented).

### Dependency
`io.nats:jnats` (optional). Boot 2.7 does not manage it → pin a version (≈ `2.20.4`) in the parent
`dependencyManagement` + a property. (jnats targets Java 8+; fine on Java 17 / Boot 2.7.)

## Testing

- **Unit (Mockito, no live NATS):** mock `io.nats.client.Connection` + `Dispatcher`; construct
  `NatsClusterBroker(codec, auth)`, `attach(mockConnection)` (stub `createDispatcher()` → mock dispatcher). Assert:
  `state()==ACTIVE` after attach; `publish("/ws/x", env)` calls `connection.publish(eq("netty.broadcast." +
  base64url("/ws/x")), any(byte[].class))`; `subscribe(...)` calls `dispatcher.subscribe(subject)`; an injected
  inbound `Message` routes through `onInboundMessage` to the registered listener.
- **Integration — NATS Testcontainers (`ClusterTestNats` resolver, gated-skip):** mirrors `ClusterTestRedis` —
  env `CLUSTER_TEST_NATS_URL` → a Testcontainers `nats:2.10` (`-p 4222`) → `assumeTrue`-skip. `NatsIntegrationTest`:
  two `NatsClusterBroker` over the real NATS; broadcast publish→receive (origin stamp asserted) **and** unicast
  publish→receive — the oracle (a dropped subject = a real routing/encoding bug). No throughput assertion.
- **Context test:** with `nats.servers` set (+ jnats on the test classpath), assert the `ClusterBroker` bean is a
  `NatsClusterBroker` and the `RedisPubSubBroker` is absent, while the `SessionRegistry`/heartbeat are still the
  Redis impls (the mixed model); with `nats.servers` empty, the reverse. (Gate on NATS availability if the bean
  eagerly connects — the `Connection` bean connects, so point `nats.servers` at `ClusterTestNats.url()` and
  `assumeTrue`.)

## Backward compatibility

Purely additive + opt-in. No SPI change, no change to the Redis path or single-node mode. `nats.servers` empty
(default) ⇒ behavior identical to RC8. New `Connection`/`NatsClusterBroker` beans only exist with jnats on the
classpath **and** `nats.servers` set. The wire format on NATS is the same `EnvelopeCodec` output as on Redis
(no new envelope fields).

## Versioning / workflow

Part of the **1.9.x cycle** (develops on `master` @ `326f48d`). Spec + plan on `master`; implementation on a
feature branch `feature/1.9.x-nats-broker`, FF-merged via `finishing-a-development-branch`. No push/deploy.
Completing it cuts **`v1.9.0-RC9`** (this feature DOES warrant an RC — it's a new published-module capability).

## Files

**New:** `…cluster/nats/NatsClusterBroker.java`; `…cluster/nats/NatsClusterBrokerTest.java` (Mockito);
`…cluster/ClusterTestNats.java` + `NatsIntegrationTest.java` (Testcontainers `nats:2.10`).

**Modified:** parent `pom.xml` (jnats version in `dependencyManagement` + property); `netty-spring-websocket-cluster/pom.xml`
(optional jnats dep + test); `netty-websocket-cluster-spring-boot-starter/pom.xml` (optional jnats);
`ClusterProperties.java` (`Nats.servers`); `NettyWebSocketClusterConfigure.java` (NATS `Connection` + broker beans
+ the `&& NO_NATS_TRANSPORT` clauses); `NettyWebSocketClusterConfigureTest.java` (selection context test);
`additional-spring-configuration-metadata.json` (`nats.servers`); `docs/cluster-design.md` (NATS row ⏳→✅),
`docs/api-guide.md` (`nats.servers` row). Release-notes at the RC9 cut.
