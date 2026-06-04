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
the LB, confirms they are on different nodes, broadcasts from one, and asserts the other receives it with
the correct `originNode`.

```bash
cd docker-demo && npm install && node smoke.js   # exit 0 = cross-node delivery confirmed
```

## Notes

- **Online-user list/count is per-node** in this lean demo — each node only lists the users connected to
  it. A global cross-node directory (and cross-node private messages) is intentionally out of scope; the
  cross-node proof here is the broadcast messages with their origin tags, not the roster.
- **Standalone Redis** is used (the common `RedisPubSubBroker` path), not a Redis *Cluster*.
- **Scale to 3+ nodes:** add a `node-c` service (copy `node-b`, set `NODE_ID: node-c`) and add
  `server node-c:8080;` to the `upstream backend` block in `nginx.conf`.
