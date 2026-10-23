'use strict';

// Bounded-memory load driver. Each worker owns its sockets and sequence ledger.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { Worker, isMainThread, parentPort, workerData } = require('node:worker_threads');
const { performance, monitorEventLoopDelay } = require('node:perf_hooks');
const WebSocket = require('ws');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const BINS = 100001; // 0.1 ms buckets, with an overflow bucket at 10 seconds.

function verifyReply(client, data, binary) {
  assert.ok(client.pending, 'unsolicited or duplicate reply');
  assert.equal(binary, false, 'unexpected binary reply');
  if (client.json) {
    const value = JSON.parse(data.toString('utf8'));
    assert.deepEqual(Object.keys(value).sort(), ['message', 'room', 'sessionId']);
    assert.equal(value.room, client.room);
    assert.equal(value.message, client.pending.body);
    assert.equal(typeof value.sessionId, 'string');
    assert.ok(value.sessionId.length > 0);
    if (client.sessionId) assert.equal(value.sessionId, client.sessionId);
    client.sessionId = value.sessionId;
  } else {
    assert.equal(data.toString('utf8'), client.pending.body, 'payload/connection/sequence mismatch');
  }
  assert.equal(client.pending.seq, client.lastAck + 1, 'noncontiguous sequence');
}
function percentile(histogram, quantile) {
  const total = histogram.reduce((sum, value) => sum + value, 0);
  if (!total) return null;
  let seen = 0;
  for (let i = 0; i < histogram.length; i++) {
    seen += histogram[i];
    if (seen >= Math.ceil(total * quantile)) return i === BINS - 1 ? null : (i + 1) / 10;
  }
  return null;
}

async function worker() {
  const settings = workerData;
  const clients = [];
  const histogram = new Uint32Array(BINS);
  const errors = [];
  const totals = { sent: 0, acked: 0, inWindow: 0, warmupSent: 0, warmupAcked: 0, duplicateOrCorrupt: 0, disconnected: 0, closeTimeouts: 0, abnormalCloses: 0 };
  let phase = 'connect', deadline = 0, measuredAt = 0, telemetry;
  const loop = monitorEventLoopDelay({ resolution: 20 });
  function fail(error) { if (errors.length < 20) errors.push(String(error.stack || error)); }
  function send(client) {
    if (!['warmup', 'measure'].includes(phase) || performance.now() >= deadline || errors.length || client.pending || client.ws.readyState !== WebSocket.OPEN) return;
    const seq = ++client.seq;
    const body = `${settings.runId};w=${settings.index};c=${client.id};s=${seq};`.padEnd(settings.payloadBytes, 'x');
    client.pending = { seq, body, at: performance.now(), measured: phase === 'measure' };
    if (client.pending.measured) totals.sent++; else totals.warmupSent++;
    client.ws.send(client.json ? JSON.stringify({ room: client.room, message: body }) : body,
      { binary: false, compress: false }, error => { if (error) fail(error); });
  }
  async function connect(id) {
    const client = { id, seq: 0, lastAck: 0, pending: null, json: id % 2 === 1, room: `worker-${settings.index}-${id}` };
    client.ws = new WebSocket(settings.endpoint + (client.json ? '/ws/json' : '/ws/test'), {
      perMessageDeflate: false, maxPayload: 65536, handshakeTimeout: 30000
    });
    clients.push(client);
    client.ws.on('error', fail);
    client.ws.on('close', code => {
      if (code !== 1000) totals.abnormalCloses++;
      if (!['closing', 'done'].includes(phase)) { totals.disconnected++; fail(new Error(`Unexpected close: ${id}`)); }
    });
    client.ws.on('message', (data, binary) => {
      const now = performance.now();
      try { verifyReply(client, data, binary); }
      catch (error) { totals.duplicateOrCorrupt++; fail(error); return; }
      const pending = client.pending;
      client.lastAck = pending.seq;
      client.pending = null;
      if (pending.measured) {
        totals.acked++;
        if (now < measuredAt + settings.measureMs) totals.inWindow++;
        histogram[Math.min(BINS - 1, Math.floor((now - pending.at) * 10))]++;
      } else totals.warmupAcked++;
      send(client);
    });
    await new Promise((resolve, reject) => { client.ws.once('open', resolve); client.ws.once('error', reject); });
  }
  async function drain() {
    const until = performance.now() + settings.drainMs;
    while (clients.some(client => client.pending) && performance.now() < until && !errors.length) await pause(20);
    assert.equal(clients.filter(client => client.pending).length, 0, 'missing or undrained replies');
    assert.equal(errors.length, 0, errors.join('\n'));
  }
  async function close() {
    phase = 'closing';
    parentPort.postMessage({ kind: 'phase', index: settings.index, phase });
    await Promise.all(clients.map(client => new Promise(resolve => {
      if (client.ws.readyState === WebSocket.CLOSED) return resolve();
      const timeout = setTimeout(() => { totals.closeTimeouts++; client.ws.terminate(); resolve(); }, 10000);
      client.ws.once('close', () => { clearTimeout(timeout); resolve(); });
      client.ws.close(1000, 'load check complete');
    })));
    phase = 'done';
  }
  try {
    for (let i = 0; i < settings.connections; i += 32) {
      await Promise.all(Array.from({ length: Math.min(32, settings.connections - i) }, (_, n) => connect(i + n)));
      await pause(20);
    }
    parentPort.postMessage({ kind: 'ready', index: settings.index, connected: clients.length });
    await new Promise(resolve => parentPort.once('message', resolve));
    phase = 'warmup'; deadline = performance.now() + settings.warmupMs;
    clients.forEach(send);
    await pause(Math.max(0, deadline - performance.now()));
    phase = 'warmup-drain'; await drain();
    parentPort.postMessage({ kind: 'warmed', index: settings.index });
    await new Promise(resolve => parentPort.once('message', resolve));
    const cpuStart = process.threadCpuUsage();
    measuredAt = performance.now(); deadline = measuredAt + settings.measureMs;
    phase = 'measure'; loop.enable();
    telemetry = setInterval(() => parentPort.postMessage({ kind: 'sample', index: settings.index, elapsedMs: performance.now() - measuredAt, ...totals }), 1000);
    clients.forEach(send);
    await pause(Math.max(0, deadline - performance.now()));
    const measurementElapsedMs = performance.now() - measuredAt;
    const cpu = process.threadCpuUsage(cpuStart);
    const measurementLoop = { mean: loop.mean / 1e6, max: loop.max / 1e6, p99: loop.percentile(99) / 1e6 };
    loop.disable();
    phase = 'drain'; clearInterval(telemetry);
    parentPort.postMessage({ kind: 'phase', index: settings.index, phase, measurementElapsedMs });
    const drainStart = performance.now(), drainCpuStart = process.threadCpuUsage();
    await drain();
    const drainElapsedMs = performance.now() - drainStart, drainCpuMicros = process.threadCpuUsage(drainCpuStart);
    await close();
    parentPort.postMessage({ kind: 'result', index: settings.index, ...totals, connected: clients.length,
      measurementElapsedMs, cpuMicros: cpu, drainElapsedMs, drainCpuMicros,
      histogram: Array.from(histogram), ledger: clients.map(client => ({ id: client.id, sent: client.seq, acked: client.lastAck, json: client.json })),
      eventLoopMs: measurementLoop, errors });
  } catch (error) {
    clearInterval(telemetry); loop.disable(); fail(error); await close();
    parentPort.postMessage({ kind: 'failure', index: settings.index, ...totals, errors });
  }
}

async function main() {
  const input = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  for (const key of ['connections', 'workers', 'warmupMs', 'measureMs', 'drainMs', 'payloadBytes']) assert.ok(Number.isInteger(input[key]) && input[key] > 0, key);
  assert.ok(input.workers <= input.connections);
  const ready = new Set(), warmed = new Set(), results = [], workers = [];
  let failed = false;
  const write = value => process.stdout.write(JSON.stringify({ at: new Date().toISOString(), ...value }) + '\n');
  const promises = Array.from({ length: input.workers }, (_, index) => new Promise((resolve, reject) => {
    const connections = Math.floor(input.connections / input.workers) + (index < input.connections % input.workers ? 1 : 0);
    const child = new Worker(__filename, { workerData: { ...input, index, connections } }); workers.push(child);
    child.on('error', reject);
    child.on('message', value => {
      if (value.kind === 'ready') {
        ready.add(index);
        if (ready.size === input.workers) { write({ phase: 'warmup', connections: input.connections }); workers.forEach(w => w.postMessage('warmup')); }
      } else if (value.kind === 'warmed') {
        warmed.add(index);
        if (warmed.size === input.workers) { write({ phase: 'measure', connections: input.connections }); workers.forEach(w => w.postMessage('measure')); }
      } else if (value.kind === 'sample' || value.kind === 'phase') write(value);
      else if (value.kind === 'result') { results.push(value); resolve(); }
      else if (value.kind === 'failure') { write(value); failed = true; reject(new Error(value.errors.join('\n'))); }
    });
    child.on('exit', code => {
      if (code !== 0 || !results.some(value => value.index === index)) reject(new Error(`Worker ${index} exited ${code} without a successful result`));
    });
  }));
  const watchdog = setTimeout(() => { failed = true; workers.forEach(w => w.terminate()); }, input.warmupMs + input.measureMs + input.drainMs * 3 + 120000);
  try {
    await Promise.all(promises);
    const histogram = Array(BINS).fill(0), totals = { sent: 0, acked: 0, inWindow: 0, warmupSent: 0, warmupAcked: 0, duplicateOrCorrupt: 0, disconnected: 0, closeTimeouts: 0, abnormalCloses: 0 };
    for (const result of results) {
      assert.equal(result.errors.length, 0, result.errors.join('\n'));
      result.ledger.forEach(item => assert.equal(item.sent, item.acked, 'incomplete connection ledger'));
      for (const key of Object.keys(totals)) totals[key] += result[key];
      result.histogram.forEach((value, i) => histogram[i] += value);
    }
    assert.equal(totals.sent, totals.acked); assert.ok(totals.sent > 0);
    assert.equal(totals.warmupSent, totals.warmupAcked);
    assert.equal(totals.duplicateOrCorrupt + totals.disconnected + totals.closeTimeouts + totals.abnormalCloses, 0);
    assert.equal(histogram.reduce((a, b) => a + b, 0), totals.acked);
    const summary = { valid: !failed, settings: input, node: process.version, ws: require('ws/package.json').version,
      ...totals, ackPerSecond: totals.inWindow * 1000 / input.measureMs,
      latencyMsUpperBound: { p50: percentile(histogram, .5), p95: percentile(histogram, .95), p99: percentile(histogram, .99), overflow: histogram.at(-1) },
      histogramBucketMs: .1, histogram, workers: results };
    fs.writeFileSync(input.output, JSON.stringify(summary));
    write({ phase: 'done', valid: summary.valid, ...totals, ackPerSecond: summary.ackPerSecond, latencyMsUpperBound: summary.latencyMsUpperBound });
  } finally { clearTimeout(watchdog); await Promise.all(workers.map(w => w.terminate())); }
}
module.exports = { verifyReply, percentile };
if (!isMainThread) worker();
else if (require.main === module) main().catch(error => { console.error(error.stack); process.exitCode = 1; });
