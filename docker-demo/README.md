# Multi-Node WebSocket Cluster Demo (Docker)

A runnable, two-node demo proving **cross-node WebSocket broadcast**: two app nodes behind an nginx
load balancer share state over Redis. A chat message typed in a browser served by `node-a` appears in
a browser served by `node-b`, tagged `(via node-a)`.

```
 browser ─┐                       ┌─ node-a ─┐
 browser ─┴─ nginx (lb, :8080) ───┤          ├─ redis (pub/sub fan-out)
                                  └─ node-b ─┘
```

## Run it

From the repo root (Docker must be running):

```bash
# 1. Build the demo jar (the image COPYs it)
./mvnw -pl demo-netty-web-spring-boot-starter -am package -DskipTests

# 2. Bring up redis + 2 app nodes + the nginx LB
docker compose -f docker-demo/docker-compose.yml up --build
```

Then open **http://localhost:8080/chat** in **two browser tabs**:

1. Each tab's header shows a **`Node: node-a`** / **`Node: node-b`** badge — the LB round-robins, so the
   two tabs land on different nodes. (If both show the same node, open a third tab or reload one.)
2. Join with two different nicknames and send a public message from one tab.
3. The other tab receives it tagged **`via node-a`** (or `node-b`) — proving the message crossed nodes
   via Redis, not a shared process.

Tear down with `Ctrl-C`, then `docker compose -f docker-demo/docker-compose.yml down -v`.

## How the proof works

Each node knows its own id (`NODE_ID` env → `server.netty.websocket.cluster.node-id` →
`ClusterNodeManager.getNodeId()`). When a node originates a chat broadcast it stamps `originNode=<self>`
into the JSON **before** publishing. The cluster fans the broadcast out over Redis; the receiving node
delivers it with `originNode` intact. Each browser also asks `GET /whoami` for its own node id, so it can
render the badge and show `(via …)` only when a message's origin differs from its own node.

## Headless check

`smoke.js` is the automated version of the two-tab test (also run in CI): it connects two clients through
the LB, confirms they are on different nodes, and verifies broadcasts in both directions with the
correct `originNode`. This checks basic delivery, not reliable replay or middleware recovery.

```bash
cd docker-demo && npm install && node smoke.js   # exit 0 = cross-node delivery confirmed
```

## Migration verification

The tools in [`migration/`](migration/) compare **separate 1.10.1 and new candidate
application classpaths** (default: 2.0.0-SNAPSHOT) and keep the original inputs, logs and results under the ignored
repository `.m2/` directory. Run the following commands from the repository root. The examples
use PowerShell; the Node runners also accept environment variables set by another shell.

Prepare these requirements before reserving a quiet host for a load run:

- Docker with Compose v2, and separately tagged, prebuilt demo application images for both
  releases. Each image must contain `/app/target/demo-netty-web-spring-boot-starter-<version>.jar`.
  The runners inspect the image and use its immutable image ID. They do not build the application
  or run Maven. Preserve the old image before building the new release.
- A **Java 17 JDK** with `JAVA_HOME` set and `java`, `javac` and `jar` on `PATH`. Redis state probes
  run separate local Java 17 processes; transport probes compile locally and run in each image's
  Java runtime. Old and new transport/load images must have identical Java runtimes.
- Node.js with built-in `fetch`, `worker_threads` and `fs.cpSync`, plus the demo's `ws` dependency:
  `npm install --prefix docker-demo`. The load client runs in `node:22-alpine` and additionally
  requires `process.threadCpuUsage()`; use an up-to-date image that provides that API.
- Cached `redis:7-alpine`, `nats:2.10` and `node:22-alpine` images, as appropriate. Pull them before
  the quiet window. NATS probes additionally need `io.nats:jnats:2.20.4` in the local Maven cache,
  or `JNATS_JAR` pointing to that jar. It is added only to the isolated probe classpath.

```powershell
$env:OLD_IMAGE = 'netty-spring-1101-check:1.10.1'
$env:NEW_IMAGE = 'netty-spring-200-acceptance:2.0.0-SNAPSHOT'
# For a prebuilt 2.0.0 release candidate, use its image and expected version instead:
# $env:NEW_IMAGE = 'netty-spring-200-acceptance:2.0.0'
# $env:NEW_VERSION = '2.0.0'
node --test docker-demo/migration/load-client.test.cjs docker-demo/migration/summarize-load.test.cjs
```

`NEW_VERSION=2.0.0` selects the expected release version for both the Redis state/Cluster and
transport runners; leaving it unset preserves `2.0.0-SNAPSHOT`. Set `NEW_IMAGE` to the matching
prebuilt image. This changes the expected jar name and release metadata, not the checks: the
runners still verify the extracted release jars and runtime version, record their actual hashes,
and enforce any supplied artifact pins.

For acceptance against a particular build, supply its independently recorded artifact hashes.
Hashes are not release constants in these scripts; each run also records the actual values.

| Tool | Optional artifact pins |
|---|---|
| Redis state | `EXPECTED_OLD_JAR_SHA256`, `EXPECTED_NEW_JAR_SHA256` environment variables |
| NATS / JetStream / Mesh | `OLD_IMAGE_SHA256`, `NEW_IMAGE_SHA256` (including `sha256:`), and `OLD_JAR_SHA256`, `NEW_JAR_SHA256` environment variables |
| Load | `--old-jar-sha256 <hex>` and `--new-jar-sha256 <hex>` arguments |

### Redis persisted state and rollback

```powershell
node docker-demo/migration/run-redis-state-compat.cjs
```

This starts a private Redis service and loads each release's extracted dependencies in its own
local JVM. It exercises reliable backlog and genuinely unacknowledged pending-entry recovery in
both version directions, offline queue leases and delete-after-delivery, expiry, JSON/HMAC wire
compatibility, and replacing all new JVMs with old JVMs while preserving Redis state. Offline
delivery is checked at the public local-sender boundary, including generated frame contents and
message IDs; this probe does not create browser WebSocket sessions.

The fixed Compose project is `netty-spring-state-compat-200`, using only `127.0.0.1:16386`.
Do not run two instances concurrently. Evidence is in `.m2/state-compat-200-<timestamp>/`, including
`summary.json`, extracted jar hashes, command records and each JVM's stdout/stderr.

Use the same runner for Redis Cluster:

```powershell
$env:REDIS_MODE = 'cluster'
node docker-demo/migration/run-redis-state-compat.cjs
Remove-Item Env:REDIS_MODE
```

Cluster mode creates three real Redis masters on the private
`netty-spring-redis-cluster-compat-200` network, with no host Redis port. Release JVMs run inside
their corresponding images. It checks all 16384 slots, cross-master session registry reads,
heartbeat/reaping, signed JSON Pub/Sub broadcast/unicast, rolling upgrade and rollback with
shared state. The room, reliable, offline and presence feature paths are **disabled in Redis
Cluster mode**; the probe checks absent beans and actual rejection instead of applying the
standalone Streams/offline scenarios. This topology has no replicas and does not validate
failover, resharding or browser WebSocket delivery. Evidence is written to
`.m2/redis-cluster-compat-200-<timestamp>/`. Clear `REDIS_MODE` to return to the standalone probe.

### NATS, JetStream and Mesh transports

```powershell
# Set this only when the jar is outside the default local Maven cache:
# $env:JNATS_JAR = 'C:\path\to\jnats-2.20.4.jar'
node docker-demo/migration/verify-transports.cjs --run
```

This compiles the same public-SPI probe separately against each release's extracted jars and
starts real NATS/JetStream and Mesh TCP transports. It checks both directions for text, JSON and
binary payloads, broadcast and supported unicast, payload decode/re-encode and response frame
contents, node restart, NATS server restart, upgrade and rollback. JetStream checks durable
backlog order, no duplicate replay and settled ACKs. NATS Core and Mesh are at-most-once paths;
their offline messages are checked for absence of replay.

Mesh additionally checks the four-byte big-endian length prefix, signed envelope format,
fragmented/coalesced TCP delivery, bad HMAC, unknown envelope version with a valid successor
on the same connection, and oversized-frame closure. This is transport/SPI compatibility;
it does not exercise Spring Boot auto-configuration or browser-side transport setup.

The project `netty-spring-transport-compat-200` owns its NATS, Redis and probe containers. It binds
only loopback ports `14225`, `14226`, and `18086`–`18089`; its Redis directory service has no host
port. Evidence is in `.m2/transport-compat-200-<timestamp>/`, with `result.json`, `checks.json`, raw
HTTP/command records, logs, source snapshots, artifact identities and `cleanup.json`.

### Load comparison and sustained runs

First check the wiring with a small functional run:

```powershell
node docker-demo/migration/verify-load.cjs --run --old-image $env:OLD_IMAGE --new-image $env:NEW_IMAGE --connections 8 --workers 2 --seconds 2 --warmup-seconds 1 --rounds 1 --soak-seconds 0 --recovery-seconds 1 --check-status-counters
```

Short probes do not establish resource behavior. For the larger comparison, reserve an otherwise
quiet host, stop other test/build work, and record any unrelated workloads that remain running:

```powershell
node docker-demo/migration/verify-load.cjs --run --old-image $env:OLD_IMAGE --new-image $env:NEW_IMAGE --connections 1024,8192 --workers 4 --seconds 60 --warmup-seconds 15 --rounds 3 --soak-seconds 600 --recovery-seconds 20 --check-status-counters
```

The runner alternates releases across three rounds at each connection count, then runs each
release for 600 seconds at the largest configured count. Every round starts a fresh application
container. The fixed application budget is 2 CPU cores, 1536 MiB container memory, a 512 MiB Java
heap and 256 MiB maximum direct memory; the separate client gets 4 CPU cores and 1536 MiB memory.
Docker needs headroom beyond those limits. Load projects are named
`netty-spring-load-<timestamp>`, use a private Compose network, and expose no host ports.

Connections remain open with one request in flight per connection. Half use `/ws/test` and half
use `/ws/json`; each message body is 1024 bytes with a unique connection/sequence token. JSON
framing adds bytes around that body. Replies are checked individually, including JSON shape and
session identity. The run verifies drained replies, normal connection closure, empty server
queues/sessions, restored permits and zero rejection/write-failure counters. The optional
`--check-status-counters` also compares the **new** release's global counters with client totals;
legacy releases are not required to implement that exact counter semantics.

Evidence is in `.m2/load-acceptance-<timestamp>/`: environment and pinned artifacts, source/`ws`
snapshots, per-round configuration, result histograms and connection ledgers, raw Docker and
`/proc` samples, GC logs, recovery snapshots and cleanup results. Analyze a completed or partial
directory without running more load:

```powershell
node docker-demo/migration/summarize-load.cjs .m2/load-acceptance-<timestamp>
```

The analyzer writes `analysis.json` and `analysis.md` beside the original evidence. It recalculates
round medians/ranges and old/new differences, checks raw counters and histograms, separates valid
resource samples by stage, and compares the first/last thirds of sustained-run samples. ACK/s
uses only ACKs received inside the fixed measurement window; latency includes all ACKs for
messages sent during that window, including the drain. Quantiles are 0.1 ms histogram upper
bounds; overflow remains unknown. Docker CPU uses 100% per core. Worker-thread CPU uses each
worker's actual elapsed time and excludes the client's main thread. `/proc` RSS and Docker
memory usage are reported separately. Missing or failed samples are never treated as zero.

These tools measure a local closed-loop workload. They do not define a production throughput or
latency threshold, prove a capacity ceiling, or turn a short/partial/uncontrolled run into a GA
performance guarantee. Resource growth across a sustained run alone is not a leak verdict.

All runners clean up their own processes/Compose resources in normal completion and failure paths;
they do not remove application images or retained `.m2/` evidence. Inspect the recorded project and
cleanup result after an interrupted run, and remove only that named test project's resources.
Leave unrelated services and another runner's fixed project/ports untouched.

## Notes

- **Online-user list/count is per-node** in this lean demo — each node only lists the users connected to
  it. A global cross-node directory (and cross-node private messages) is intentionally out of scope; the
  cross-node proof here is the broadcast messages with their origin tags, not the roster.
- **Standalone Redis** is used (the common `RedisPubSubBroker` path), not a Redis *Cluster*.
- **Scale to 3+ nodes:** add a `node-c` service (copy `node-b`, set `NODE_ID: node-c`) and add
  `server node-c:8080;` to the `upstream backend` block in `nginx.conf`.
