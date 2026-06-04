// Headless cross-node broadcast smoke test for the netty-spring multi-node Docker demo.
//
// Opens two WebSocket clients through the nginx load balancer, verifies they were served by
// DIFFERENT cluster nodes, then asserts a broadcast from one is delivered to the other STAMPED
// with the sender's origin node id — proving cross-node delivery over Redis. Exit 0 = pass, 1 = fail.
const WebSocket = require('ws');

const LB = process.env.LB_URL || 'ws://localhost:8080';
const CHAT = LB + '/ws/chat';
const CONNECT_TIMEOUT_MS = 10000;
const RECEIVE_TIMEOUT_MS = 5000;
const MAX_NODE_RETRIES = 8;

function log(...a) { console.log('[smoke]', ...a); }
function fail(msg) { console.error('[smoke] FAIL:', msg); process.exit(1); }

// Open a chat client; resolve {ws, node} once we learn which node served us — read from the
// originNode of our OWN join broadcast (each node stamps the joins it originates).
function connectClient(nickname) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(CHAT + '?nickname=' + encodeURIComponent(nickname));
    const to = setTimeout(() => { try { ws.close(); } catch (e) {} reject(new Error('timeout learning node for ' + nickname)); }, CONNECT_TIMEOUT_MS);
    ws.on('message', (raw) => {
      let d; try { d = JSON.parse(raw.toString()); } catch (e) { return; }
      if (d.type === 'join' && d.nickname === nickname && d.originNode) {
        clearTimeout(to);
        resolve({ ws, node: d.originNode });
      }
    });
    ws.on('error', (e) => { clearTimeout(to); reject(e); });
  });
}

// Connect a client that lands on a node DIFFERENT from avoidNode (round-robin over 2 nodes alternates,
// so this converges quickly); each retry uses a fresh nickname.
async function connectOnDistinctNode(baseNick, avoidNode) {
  for (let i = 0; i < MAX_NODE_RETRIES; i++) {
    const c = await connectClient(baseNick + '-' + i);
    if (c.node !== avoidNode) return c;
    log(baseNick + ' landed on ' + c.node + ' (same as ' + avoidNode + '); retrying for a distinct node');
    try { c.ws.close(); } catch (e) {}
  }
  throw new Error('could not reach a node distinct from ' + avoidNode + ' after ' + MAX_NODE_RETRIES + ' tries');
}

(async () => {
  let c1, c2;
  try {
    c1 = await connectClient('alice');
    log('client1 (alice) served by ' + c1.node);
    c2 = await connectOnDistinctNode('bob', c1.node);
    log('client2 (bob) served by ' + c2.node);

    const MARK = 'cross-node-' + Date.now();
    const received = new Promise((resolve, reject) => {
      const to = setTimeout(() => reject(new Error('client2 did not receive the broadcast within ' + RECEIVE_TIMEOUT_MS + 'ms')), RECEIVE_TIMEOUT_MS);
      c2.ws.on('message', (raw) => {
        let d; try { d = JSON.parse(raw.toString()); } catch (e) { return; }
        if (d.type === 'message' && d.text === MARK) { clearTimeout(to); resolve(d); }
      });
    });

    c1.ws.send(JSON.stringify({ type: 'message', text: MARK }));
    const msg = await received;
    log('client2 received: ' + JSON.stringify(msg));

    if (!msg.originNode) fail('received message has no originNode stamp');
    if (msg.originNode !== c1.node) fail('expected originNode=' + c1.node + ' but got ' + msg.originNode);
    if (msg.originNode === c2.node) fail('originNode equals the receiver node — not cross-node');

    log('PASS: broadcast from ' + c1.node + ' reached a client on ' + c2.node + ' with correct origin stamp');
    process.exit(0);
  } catch (e) {
    fail(e && e.message ? e.message : String(e));
  } finally {
    try { c1 && c1.ws.close(); } catch (e) {}
    try { c2 && c2.ws.close(); } catch (e) {}
  }
})();
