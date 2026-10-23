// Headless cross-node broadcast smoke test for the netty-spring multi-node Docker demo.
//
// Opens two WebSocket clients through the nginx load balancer, verifies they were served by
// DIFFERENT cluster nodes, then asserts broadcasts in BOTH directions carry the sender's
// origin node id — also useful with old/new application images during migration checks.
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

async function assertBroadcast(sender, receiver, marker) {
  const received = new Promise((resolve, reject) => {
    const onMessage = (raw) => {
      let message; try { message = JSON.parse(raw.toString()); } catch (e) { return; }
      if (message.type !== 'message' || message.text !== marker) return;
      clearTimeout(timeout);
      receiver.ws.off('message', onMessage);
      resolve(message);
    };
    const timeout = setTimeout(() => {
      receiver.ws.off('message', onMessage);
      reject(new Error('broadcast from ' + sender.node + ' to ' + receiver.node + ' timed out'));
    }, RECEIVE_TIMEOUT_MS);
    receiver.ws.on('message', onMessage);
  });
  sender.ws.send(JSON.stringify({ type: 'message', text: marker }));
  const message = await received;
  if (message.originNode !== sender.node || message.originNode === receiver.node) {
    throw new Error('unexpected origin stamp: ' + JSON.stringify(message));
  }
  log('PASS: broadcast from ' + sender.node + ' reached ' + receiver.node + ' with correct origin stamp');
}

(async () => {
  let c1, c2;
  try {
    c1 = await connectClient('alice');
    log('client1 (alice) served by ' + c1.node);
    c2 = await connectOnDistinctNode('bob', c1.node);
    log('client2 (bob) served by ' + c2.node);

    const marker = 'cross-node-' + Date.now();
    await assertBroadcast(c1, c2, marker + '-forward');
    await assertBroadcast(c2, c1, marker + '-reverse');
    process.exit(0);
  } catch (e) {
    fail(e && e.message ? e.message : String(e));
  } finally {
    try { c1 && c1.ws.close(); } catch (e) {}
    try { c2 && c2.ws.close(); } catch (e) {}
  }
})();
