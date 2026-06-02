# Cluster Micrometer Metrics — Design Spec

> Date: 2026-06-02 · Target: **1.9.0 cycle** (develops on `1.9.0-RC3`; cuts `v1.9.0-RC4`) · Status: design approved (delegated), pending spec review
> Builds on: RC1 hardening + RC2 reliable broadcast + RC3 HMAC auth.

## Overview

The cluster already accumulates rich runtime counters (`ClusterRuntimeStats`), a node state machine, a broker transport state, and an HMAC reject count — surfaced today only via `/actuator/health` (`ClusterHealthIndicator`, point-in-time). This feature adds **time-series** by binding those signals to **`netty.cluster.*` Micrometer meters** on every `MeterRegistry`, completing the observability story started in 1.7.0 (`netty.websocket.*` / `netty.http.*`).

It follows the **existing 1.7.0 pattern verbatim** (`NettyWebSocketMeterBinder` + `NettyMicrometerConfigure`): a `MeterBinder` of `FunctionCounter`/`Gauge` read-throughs over the existing counters — **no change to any increment site**, no new runtime cost on the hot path. Optional (micrometer-core on the classpath), aggregate-only (no unbounded tags), zero impact when absent.

## Goals

- Expose all cluster runtime counters + node/broker state as `netty.cluster.*` meters, scraped via `/actuator/metrics` / `/actuator/prometheus`.
- Reuse existing public getters; **additive only** — no change to `ClusterRuntimeStats`, `ClusterMessageSender`, the brokers, or the node manager beyond (possibly) adding read-only getters if one is missing.
- Optional + gated: no meters (and no error) when micrometer-core is absent or the cluster is disabled.

## Non-goals

- Per-URI / per-session metric breakdowns (unbounded cardinality — the 1.7.0 docs explicitly warn against this).
- Distribution timers/summaries for cluster publish latency (no hot-path latency capture exists today; a later item could add a push callback like the WebSocket one).
- Replacing `ClusterHealthIndicator` (health stays; metrics are complementary time-series).

## Architecture

- **New `NettyClusterMeterBinder implements io.micrometer.core.instrument.binder.MeterBinder`** in `netty-websocket-cluster-spring-boot-starter` (beside `ClusterHealthIndicator`). Constructed with the cluster beans it reads from: `ClusterMessageSender` (→ `getClusterRuntimeStats()`), `ClusterNodeManager` (→ `getState()`), `ClusterBroker` (→ `state()`), and `MessageAuthenticator` (→ HMAC reject count). `bindTo(registry)` registers all meters; an `IdentityHashMap`-backed `boundRegistries` guard makes a repeat `bindTo` for the same registry a no-op (identical to `NettyWebSocketMeterBinder`).
- **New `NettyClusterMetricsConfigure`** (mirrors the existing `NettyClusterActuatorConfigure`): `@Configuration @ConditionalOnClass(MeterRegistry.class)`, a `@Bean @ConditionalOnBean({MeterRegistry.class, ClusterMessageSender.class}) NettyClusterMeterBinder`. Registered in the cluster starter's `AutoConfiguration.imports`. Only active when the cluster is enabled (the `ClusterMessageSender` bean exists) AND micrometer is present.
- **micrometer-core** added as an **optional** dependency of `netty-websocket-cluster-spring-boot-starter` (as in `netty-spring-boot-autoconfigure`).

## Meter set

**Counters** — `FunctionCounter.builder(name, src, src -> src.getX()).description(...).register(registry)` over `ClusterRuntimeStats` public getters:

| Meter | Source getter |
| --- | --- |
| `netty.cluster.broadcast.published` | broadcastPublished |
| `netty.cluster.broadcast.received` | crossNodeBroadcastReceived |
| `netty.cluster.broadcast.self_dropped` | selfDeliveryDropped |
| `netty.cluster.broadcast.skipped_degraded` | broadcastsSkippedDegraded |
| `netty.cluster.unicast.sent` | unicastSent |
| `netty.cluster.publish.failures` | publishFailures |
| `netty.cluster.reliable.published` | reliablePublished |
| `netty.cluster.reliable.received` | reliableReceived |
| `netty.cluster.cache.hits` | cacheHits |
| `netty.cluster.cache.misses` | cacheMisses |
| `netty.cluster.auth.rejected` | `HmacMessageAuthenticator.getRejectedCount()` when the wired authenticator is an `HmacMessageAuthenticator`; constant 0 otherwise |

(If any of these getters is missing on `ClusterRuntimeStats`, add a public read-only getter for it — additive, no behavior change. The exact getter names are confirmed in the plan against the real class.)

**State gauges** — tagged-enum, mirroring the codebase's per-`CloseReason` convention. One gauge per enum value, value `1.0` for the node/broker's current state and `0.0` otherwise:
- `netty.cluster.node.state{state="joining|active|degraded|resync|draining|left"}` (6) — source `ClusterNodeManager.getState()`.
- `netty.cluster.broker.state{state="active|degraded|shutdown"}` (3) — source `ClusterBroker.state()`.

The tag value is the lowercased enum name. Dashboards alert on e.g. `netty_cluster_node_state{state="degraded"} == 1`. Ratios (cache hit ratio) are left to the query layer (`hits/(hits+misses)`).

## Cardinality / safety

Aggregate-only — **no per-URI or per-session tags**. Each node's `MeterRegistry` holds that node's own values (no `node.id` tag). Total meter count is fixed (~11 counters + 9 state gauges), independent of traffic or topology — bounded cardinality.

## Backward compatibility

Purely additive and optional. No meters when micrometer-core is absent or `cluster.enable=false`. No SPI/signature/behavior change; the only possible source edit is adding a missing public getter to `ClusterRuntimeStats`.

## Testing

- **Unit (`SimpleMeterRegistry`, mirrors `NettyWebSocketMeterBinderTest`):** construct `NettyClusterMeterBinder` with stub/lightweight cluster components whose counters have known values; `bindTo(registry)`; assert each counter meter exists and reports the underlying value; assert the node-state gauge is `1.0` only for the current state (e.g. ACTIVE) and `0.0` for the others; assert a second `bindTo(sameRegistry)` does not duplicate meters. A NoOp authenticator → `auth.rejected` is 0; an `HmacMessageAuthenticator` with rejections → the meter reflects the count.
- **Context test:** with `cluster.enable=true` + micrometer on the test classpath, the `NettyClusterMeterBinder` bean is present and `meterRegistry.find("netty.cluster.broadcast.published").functionCounter()` is non-null. (Reuse the existing `ApplicationContextRunner` harness; micrometer-core is already a test dependency transitively, or add it.)

## Versioning

Part of the 1.9.0 cycle (RC line). Develops on `1.9.0-RC3`; completing it cuts **`v1.9.0-RC4`**. Final `1.9.0` only when the user confirms the cycle is done. Backward compatible; optional.

## Files (for the plan)

- New: `netty-websocket-cluster-spring-boot-starter/.../configure/NettyClusterMeterBinder.java`, `.../configure/NettyClusterMetricsConfigure.java`.
- Modified: cluster starter `pom.xml` (optional micrometer-core), `AutoConfiguration.imports` (register the metrics config), possibly `ClusterRuntimeStats` (add a missing getter), docs.
- Tests: `NettyClusterMeterBinderTest` (SimpleMeterRegistry unit), context-test addition.
