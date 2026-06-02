# HMAC Envelope Authentication — Design Spec

> Date: 2026-06-02 · Target: **1.9.0 cycle** (develops on `1.9.0-RC2`; cuts `v1.9.0-RC3`) · Status: design approved (delegated), pending spec review
> Builds on: 1.9.0-RC1 hardening + RC2 reliable broadcast.

## Overview

Redis is the cluster control plane: any party that can `PUBLISH`/`XADD` to the cluster's Redis channels/streams can inject a forged envelope — spoof `originNodeId`, broadcast to any URI, unicast to any session, or force-close any session (CLOSE control message). 1.8.0 documented this trust-model gap.

This feature adds **opt-in HMAC authentication** of every cross-node envelope: each node signs outbound envelopes with a shared secret, and rejects inbound envelopes whose tag is missing or invalid. A party without the secret can no longer forge or inject. **Default off ⇒ byte-identical wire format and zero overhead.**

Scope is **anti-forgery only** (chosen over replay protection): replay requires Redis *read* access to capture a valid signed envelope — a stronger attacker position than the write-only injection HMAC targets — and a timestamp freshness window would conflict with reliable broadcast's intentional replay-on-resync of old messages.

## Goals

- Reject forged/injected cross-node messages when enabled, across **all** transports: Pub/Sub broadcast, unicast, CLOSE control, and the reliable Streams path.
- **Codec-agnostic:** works with SimpleText/JSON/Protobuf/any `EnvelopeCodec` (auth sits *outside* the codec).
- **Zero change when disabled**, and a **clean rolling-upgrade path** to turn it on without cross-node message loss.
- No envelope schema change; no existing SPI signature change.

## Non-goals

- Replay protection (nonce/dedup, timestamp window) — out of scope (see Overview); a possible later item.
- Confidentiality of cluster traffic — use `rediss://` (TLS) for transport encryption; the app-layer AES-GCM already protects the WS payload end-to-end. HMAC here is integrity/authenticity of the *envelope*, not encryption.
- Per-node distinct keys / key rotation protocol — one shared secret (rotation = redeploy with the new secret; the rollout modes below also serve rotation).

## Architecture — `MessageAuthenticator` SPI (transport wrapper)

A small interface the brokers call around the codec:

```java
public interface MessageAuthenticator {
    /** Sign: produce the wire string from the codec-encoded envelope. NoOp returns it unchanged. */
    String wrap(String encoded);
    /** Verify+strip: return the inner encoded envelope, or null to REJECT (missing/invalid tag when required). */
    String unwrap(String wireData);
}
```

- **`NoOpMessageAuthenticator`** (default, `auth.enable=false`): `wrap` = identity (no signing); `unwrap` = **if the data carries an `H1:` tag, strip it without verifying** (so a not-yet-enabled node can still read signed messages during rollout), else identity. Needs no secret.
- **`HmacMessageAuthenticator(secretBytes, requireSigned)`** (`auth.enable=true`): `wrap` = `H1:{tag}:{encoded}`; `unwrap` = if `H1:`-tagged → verify (return payload or null); if untagged → `requireSigned ? null : payload` (permissive accepts unsigned). `requireSigned = !permissive`.

### Wire format + algorithm

`H1:{base64url(HMAC_SHA256(secret, payload))}:{payload}`
- `H1:` = "HMAC v1" marker (forward-compat for future schemes). base64url (RFC 4648 §5, no padding) contains no `:`, so the first `:` after `H1:` delimits the 43-char tag from the (arbitrary, may contain `:`/`|`) payload.
- `javax.crypto.Mac` `"HmacSHA256"`; the tag is computed over the UTF-8 bytes of `payload`. Verification uses **constant-time** comparison (`MessageDigest.isEqual`) to avoid a timing oracle.
- Secret: UTF-8 bytes of the configured string as the HMAC key.

## Broker integration

`RedisPubSubBroker` and `RedisStreamsReliableBroker` each take a `MessageAuthenticator` (constructor param, **with a backward-compat overload defaulting to `NoOpMessageAuthenticator`** so existing constructors/tests are unchanged):
- **Publish/XADD:** `String wire = authenticator.wrap(codec.encode(envelope));` then publish/XADD `wire`. (Pub/Sub broadcast + unicast + CLOSE all go through `encode`+publish; the reliable path through `encode`+XADD.)
- **Receive:** `String inner = authenticator.unwrap(wire); if (inner == null) { drop + authRejected++ + warn; return; } ClusterEnvelope e = codec.decode(inner);`
- The inbound size guard (RedisPubSubBroker) runs on the *wire* string before unwrap (cheap pre-check), unchanged.

The reject **counter lives on `HmacMessageAuthenticator`** (`getRejectedCount()`) — it makes the reject decision, is the single shared bean across both brokers, and is available at broker-construction time (unlike `ClusterRuntimeStats`, which `ClusterMessageSender` owns and constructs later). Brokers `log.warn` the drop with channel/stream context. Surfacing it under `/actuator/health` is a small follow-up (read the bean's count); the count is observable directly for tests now.

## Config (`server.netty.websocket.cluster.auth.*`)

| Key | Default | Effect |
| --- | --- | --- |
| `enable` | `false` | Master gate. `false` ⇒ NoOp authenticator (no signing; strip-only unwrap), zero overhead. |
| `secret` | (none) | Shared HMAC key. **Required** when `enable=true` (fail auto-config with a clear message if blank). Redacted in all logs; warn if `< 32` chars (weak key). Externalize via `${ENV}`/secret manager — do not hardcode. |
| `permissive` | `false` | When `enable=true`: `true` accepts *unsigned* inbound (logged) while still signing outbound — for rolling rollout/rotation; `false` (strict) rejects unsigned. |

**Rolling-upgrade path (zero cross-node loss):**
1. Deploy RC3 to all nodes with `auth.enable=false`. All interop on plain wire (NoOp).
2. Rolling-set `auth.enable=true, permissive=true` (with the secret). Flipped nodes sign + accept both; not-yet-flipped nodes (NoOp) strip-and-read signed messages. No loss either direction.
3. Once all nodes sign, rolling-set `permissive=false` (strict). All traffic is now signed, so rejecting unsigned drops nothing legitimate.
(Secret rotation reuses steps 2–3 with the new secret; during overlap, run `permissive=true` so both old/new are tolerated — note: with a single key, rotation still needs a brief two-secret tolerance which this version does NOT provide, so rotation = a short maintenance window. Documented as a limitation.)

## Gating + backward compatibility

- `auth.enable=false` (default): NoOp ⇒ no signing, no perf cost; unwrap only strips a tag if present. Wire format identical to RC2 for unsigned traffic.
- `MessageAuthenticator` is a **new** SPI (`@ConditionalOnMissingBean` — users can override, e.g. a KMS-backed signer). No change to `ClusterBroker`/`SessionRegistry`/`EnvelopeCodec`/`MessagePayloadCodec`/`ClusterNodeHeartbeat`/`ReliableBroker`/`ClusterReaper`. Broker constructors gain a compat overload. No envelope schema change (`ClusterEnvelope.CURRENT_VERSION` unchanged).

## Auto-config

- One `@Bean @ConditionalOnMissingBean(MessageAuthenticator.class)` method: returns `HmacMessageAuthenticator(secret, !permissive)` when `auth.enable=true` (validating a non-blank secret, redaction-logging, weak-key warning), else `NoOpMessageAuthenticator`.
- Injected into the `clusterBroker` (RedisPubSubBroker) bean and the gated `reliableBroker` (RedisStreamsReliableBroker) bean.

## Testing

- **Unit (`HmacMessageAuthenticator`):** `wrap`→`unwrap` round-trips to the original; a tampered payload, wrong-secret verifier, or truncated/garbage tag → `unwrap` returns null; an untagged input → null when strict, returned as-is when permissive. `NoOpMessageAuthenticator` strips an `H1:` tag (no verify) and passes plain through. Constant-time compare exercised.
- **Integration (real Redis):** node A (secret S) signs a broadcast; node B (secret S) accepts it. Two rejection cases, each incrementing `authRejected`: (1) **bad MAC** — B verifies with a different secret S2 ≠ S → tag mismatch → dropped; (2) **missing tag** — a plain (unsigned) injected message hits a strict `HmacMessageAuthenticator` (`permissive=false`) → no `H1:` tag → dropped. NoOp↔NoOp plain interop unchanged; a NoOp receiver strips A's `H1:` tag and decodes (rollout interop). Reliable path: a signed `reliableBroadcast` is verified + replayed correctly.
- **Context test:** `auth.enable=true` (+secret) wires `HmacMessageAuthenticator`; default wires `NoOpMessageAuthenticator`; blank secret with `enable=true` fails context startup with a clear message.

## Versioning

Part of the 1.9.0 cycle (RC line). Develops on `1.9.0-RC2`; completing it cuts **`v1.9.0-RC3`**. Final `1.9.0` only when the user confirms the cycle is done. Backward compatible; disabled by default.

## Files (for the plan)

- New: `spi/MessageAuthenticator.java`, `cluster/auth/NoOpMessageAuthenticator.java`, `cluster/auth/HmacMessageAuthenticator.java`.
- Modified: `RedisPubSubBroker` + `RedisStreamsReliableBroker` (authenticator param + compat overload + wrap/unwrap), `ClusterProperties` (`Auth` nested config), `NettyWebSocketClusterConfigure` (authenticator bean + inject), metadata JSON, docs. (Reject counter lives on `HmacMessageAuthenticator`, not `ClusterRuntimeStats`.)
- Tests: `MessageAuthenticatorTest` (unit), `ClusterAuthIntegrationTest` (real Redis), context-test additions.
