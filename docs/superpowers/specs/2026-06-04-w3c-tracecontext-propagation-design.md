# W3C TraceContext Cross-Node Propagation — Design Spec

> Date: 2026-06-04 · Target: **1.9.0 cycle** (develops on `1.9.0-RC5`; cuts `v1.9.0-RC6`) · Status: design approved (delegated)
> Builds on: RC1 hardening + RC2 reliable broadcast + RC3 HMAC + RC4 metrics + RC5 multi-node E2E/CI + cross-node unicast fix.

## Overview

The cluster envelope already **carries** a W3C `traceparent` field (`ClusterEnvelope.traceparent`, encoded/decoded by `SimpleTextEnvelopeCodec` as wire field 5 of 8) — but it is **never populated on send and never restored on receive** (`buildBroadcastEnvelope` / `buildUnicastEnvelope` / the CLOSE builder all pass `null`). So a distributed trace dies at the node boundary: a request on node B that broadcasts to node A produces, on node A, log lines with no shared trace id.

This feature closes that gap at the **log-correlation** level: capture the current `traceparent` on the publishing node, carry it (already supported by the wire format), and **restore it into SLF4J MDC** on the receiving node for the duration of cross-node delivery — so `traceId`/`spanId` in node A's logs match the originating request on node B. It is **tracer-agnostic** (no hard dependency on Sleuth/Brave) via a small SPI with a zero-dependency MDC-based default.

**Scope constraint (confirmed):** Spring Boot 2.7.18 ships Micrometer **1.9.17**, which has **no Observation API** (added in 1.10 / Boot 3.0). Restoring an active **Micrometer Observation Scope** (so a real tracer continues the span on the receiving node) therefore defers to **2.0.0** (the Boot 3.x line). The 1.9.x deliverable is W3C `traceparent` propagation + MDC restoration.

## Goals

- Populate the envelope `traceparent` on every cross-node send (broadcast, unicast, close) from the current trace context.
- Restore that `traceparent` into MDC (`traceId` / `spanId` / `netty.traceparent`) during cross-node delivery on the receiving node, then clear it.
- Tracer-agnostic: work with any tracer that writes `traceId`/`spanId` to MDC (Sleuth/Brave do), plus an SPI seam for native tracer integration.
- Opt-in, additive, zero wire change, zero cost when disabled or when no trace is present.

## Non-goals

- **Micrometer Observation Scope / active-span continuation** — needs Micrometer 1.10+ (Boot 3.x). Deferred to 2.0.0.
- **`tracestate`** propagation — the envelope has no field for it; adding one is a wire change. Deferred (YAGNI; `traceparent` alone gives trace/span correlation).
- Changing the wire format or the `EnvelopeCodec` (already carries `traceparent`).
- Sampling decisions / trace creation — the framework only **propagates** an existing trace; it does not start traces.

## Architecture

### 1. `ClusterTraceContext` SPI (new, `…cluster.spi`)

Tracer-agnostic seam, wired only when trace propagation is enabled, `@ConditionalOnMissingBean`:
```java
public interface ClusterTraceContext {
    /** The current W3C traceparent for the publishing thread, or null if none. */
    String currentTraceparent();
    /** Restore a traceparent into the ambient context (MDC) for the delivery scope. */
    Scope restore(String traceparent);
    /** Closeable that reverts what restore() set; never throws. */
    interface Scope extends AutoCloseable { @Override void close(); }
    Scope NOOP = () -> { };
}
```

### 2. Default impl `MdcClusterTraceContext` (new, `…cluster`)

Zero-dependency, MDC-based:
- **`currentTraceparent()`**: return `MDC.get("traceparent")` if present; else if `MDC.get("traceId")` and `MDC.get("spanId")` are both present, synthesize a W3C value `00-{traceId32}-{spanId16}-01` (left-pad a 16-hex/64-bit traceId to 32; validate hex + lengths, return null if malformed); else null.
- **`restore(traceparent)`**: if null/blank/malformed → `NOOP`. Else parse `00-{trace}-{span}-{flags}`, `MDC.put("traceId", trace)`, `MDC.put("spanId", span)`, `MDC.put("netty.traceparent", raw)`, and return a `Scope` whose `close()` removes exactly those three keys (restoring any prior values is **not** required — the receive path runs on a broker background thread with no ambient trace).

The conventional unprefixed `traceId`/`spanId` keys are used (not `netty.*`) so existing tracer log patterns (`%X{traceId}`) and dashboards light up unchanged; the raw `netty.traceparent` is namespaced.

### 3. Send-side injection (`ClusterMessageSender`)

A nullable `ClusterTraceContext traceContext` field (null ⇒ disabled). The three envelope builders replace their `null` traceparent arg with `traceContext != null ? traceContext.currentTraceparent() : null`. (`buildBroadcastEnvelope`, `buildUnicastEnvelope`, and the CLOSE-envelope builder around `ClusterMessageSender:399`.)

### 4. Receive-side restoration (`ClusterMessageSender` listener)

In the cross-node message listener (the callback that performs origin self-suppression + local delivery for BROADCAST/UNICAST/CLOSE), wrap the local-delivery call:
```java
try (ClusterTraceContext.Scope s =
        (traceContext != null) ? traceContext.restore(env.getTraceparent()) : ClusterTraceContext.NOOP) {
    // ... existing local delivery (localSender.topicMessage / sendMessage / closeSession) ...
}
```
MDC is thread-local to the broker's subscribe/consume thread, so the scope is correctly bounded and cleared. The plan pins the exact listener method.

### 5. `MdcUtil` constant (`netty-spring-web`)

Add `public static final String KEY_TRACEPARENT = "netty.traceparent";` (and document it in the MdcUtil Javadoc key list) so the receive-side default impl and any custom code share one key name, and operators can find it alongside the other `netty.*` keys for their log pattern.

**Do NOT modify `MdcUtil.clear()`.** The request-path `clear()` only removes the keys the request path *sets* (`netty.requestId/sessionId/uri/remoteAddr`); it must not touch `traceId`/`spanId` — those are tracer-managed (Sleuth/Brave own them on request threads, and clearing them in the framework's request cleanup could prematurely drop the tracer's context). Cleanup of the restored trace keys is owned entirely by the receive-side `ClusterTraceContext.Scope.close()` (try-with-resources), which runs on the broker's background delivery thread where no tracer span is active — so there is no interaction with request-thread tracing.

### 6. Config + auto-config

- `ClusterProperties.TracePropagation { boolean enable = false; }` → `server.netty.websocket.cluster.trace-propagation.enable` (**default false**, opt-in).
- In `NettyWebSocketClusterConfigure`: when enabled, a `@Bean @ConditionalOnMissingBean ClusterTraceContext clusterTraceContext()` returns `new MdcClusterTraceContext()`; the `clusterMessageSender` bean calls `sender.setTraceContext(traceContext)` (inject the bean, `@Autowired(required = false)` so it's null when disabled). When disabled, no bean, `traceContext` stays null, behavior is byte-identical to RC5.

## Backward compatibility

Purely additive and opt-in. No wire-format change (the `traceparent` field already exists and is already encoded). No SPI signature change to existing interfaces (`ClusterTraceContext` is new and additive). When `trace-propagation.enable=false` (default) the envelope `traceparent` stays `null` exactly as today and no MDC is touched. Single-node mode unaffected.

## Testing

- **Unit — `MdcClusterTraceContextTest`:** with MDC `traceId`/`spanId` set, `currentTraceparent()` synthesizes a valid W3C value (incl. 64-bit traceId left-pad); with an explicit MDC `traceparent`, it is returned verbatim; with nothing, returns null. `restore()` of a valid traceparent puts `traceId`/`spanId`/`netty.traceparent`, and `Scope.close()` removes them; `restore(null)`/malformed → `NOOP`, no MDC mutation.
- **Integration — real Redis (`ClusterTestRedis`):** node A publishes with a known traceparent in MDC; a broker-level subscriber on node B captures the delivered `ClusterEnvelope.getTraceparent()` and asserts it equals node A's. (Mirrors the existing `ClusterAuthIntegrationTest` two-broker pattern.)
- **End-to-end (extend `ClusterMultiNodeE2ETest` or a focused IT):** with `trace-propagation.enable=true`, set a traceparent in node B's MDC, broadcast; assert node A's delivery scope observes the same `traceId` — captured via a small in-memory logback appender or a test `ClusterTraceContext` spy.
- **Context test:** the `ClusterTraceContext` bean is present iff `trace-propagation.enable=true`; absent (and `traceparent` stays null) when disabled.

## Versioning

Part of the 1.9.0 cycle (RC line). Develops on `1.9.0-RC5`; completing it cuts **`v1.9.0-RC6`**. Final `1.9.0` only when the user confirms the cycle is done. Additive + opt-in. Micrometer Observation / active-span continuation is a **2.0.0** (Boot 3.x) follow-up.

## Files (for the plan)

- **New:** `…cluster/spi/ClusterTraceContext.java` (SPI); `…cluster/MdcClusterTraceContext.java` (default impl); tests `MdcClusterTraceContextTest`, a trace-propagation integration test, a context-test addition.
- **Modified:** `ClusterMessageSender.java` (traceContext field + setter + 3 send builders + listener restore-scope); `ClusterProperties.java` (TracePropagation block); `NettyWebSocketClusterConfigure.java` (gated `ClusterTraceContext` bean + inject into sender); `MdcUtil.java` (`KEY_TRACEPARENT` + clear); `spring-configuration-metadata`/docs for the new property.
- **Docs:** `release-notes-1.9.0.md` (RC6 section + the Observation-deferred note), `api-guide.md` (cluster trace propagation + MDC keys), `cluster-design.md` + `development-plan.md` (move W3C TraceContext from deferred to ✅ RC6 at the MDC-correlation level; note Observation → 2.0.0), `release-checklist.md`.
