#!/usr/bin/env node
'use strict';

// Run with Java 17 and Docker, from any working directory. No Maven or application routes.
// OLD_IMAGE / NEW_IMAGE choose complete application images. Optional EXPECTED_{OLD,NEW}_JAR_SHA256
// pins their fat jars. Every run records the actual image IDs, all extracted jar hashes and raw events.
// NEW_VERSION selects the expected new release metadata (default: 2.0.0-SNAPSHOT).
// Only the dedicated netty-spring-state-compat-200 Compose project and localhost:16386 are used.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const cp = require('node:child_process');
const readline = require('node:readline');

const root = path.resolve(__dirname, '../..');
const clusterMode = process.env.REDIS_MODE === 'cluster';
const project = clusterMode ? 'netty-spring-redis-cluster-compat-200' : 'netty-spring-state-compat-200';
const composeFile = path.join(__dirname, clusterMode ? 'redis-cluster-compat.compose.yml' : 'redis-state-compat.compose.yml');
const source = path.join(__dirname, 'RedisStateCompatibilityHarness.java');
const sources = clusterMode ? [source, path.join(__dirname, 'RedisClusterCompatibilityHarness.java')] : [source];
const runId = new Date().toISOString().replace(/[:.]/g, '-');
const out = path.join(root, '.m2', `${clusterMode ? 'redis-cluster' : 'state'}-compat-200-${runId}`);
fs.mkdirSync(out, { recursive: true });
for (const file of [__filename, ...sources, composeFile]) fs.copyFileSync(file, path.join(out, path.basename(file)));
const summary = { runId, startedAt: new Date().toISOString(), project,
  redisUri: clusterMode ? 'redis-a:6379,redis-b:6379,redis-c:6379' : 'redis://127.0.0.1:16386',
  scope: clusterMode ? 'Three real Redis masters; release auto-configuration, registry/heartbeat/reaper/PubSub and unsupported feature rejection. No replicas, failover or browser WebSocket claim.' :
    'Public Redis Streams reliable/offline APIs with separate release JVMs and strict HMAC; local delivery boundary is recorded, not a browser WebSocket.',
  images: {}, workers: [], stages: [], commands: [], status: 'RUNNING' };
const save = () => fs.writeFileSync(path.join(out, 'summary.json'), JSON.stringify(summary, null, 2));
const hash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
summary.sourceSha256 = Object.fromEntries([__filename, ...sources, composeFile].map(file => [path.basename(file), hash(file)]));
const allWorkers = new Set();
let composeStarted = false;
let workerCounter = 0;
let commandCounter = 0;
save();

function run(file, args, options = {}) {
  const result = cp.spawnSync(file, args, { cwd: root, encoding: 'utf8', windowsHide: true,
    timeout: 120000, maxBuffer: 32 * 1024 * 1024, ...options });
  const record = { executable: file, args, cwd: options.cwd || root, code: result.status,
    stdout: result.stdout || '', stderr: result.stderr || '', error: result.error?.message };
  fs.appendFileSync(path.join(out, 'commands.jsonl'), JSON.stringify(record) + '\n');
  if (result.error || result.status !== 0) throw new Error(`Command failed: ${file} ${args.join(' ')}\n${record.stderr}\n${record.stdout}\n${record.error || ''}`);
  return result.stdout;
}
const docker = (...args) => run('docker', args);
const compose = (...args) => docker('compose', '-p', project, '-f', composeFile, ...args);
const javaHome = process.env.JAVA_HOME;
assert(javaHome, 'Set JAVA_HOME to a Java 17 JDK.');
const exe = name => path.join(javaHome, 'bin', name + (process.platform === 'win32' ? '.exe' : ''));
const java = exe('java'), javac = exe('javac'), jar = exe('jar');
fs.writeFileSync(path.join(out, 'logback.xml'), '<configuration><root level="OFF"/></configuration>\n');

function extract(role, image, version) {
  const dir = path.join(out, role);
  const extracted = path.join(dir, 'extract');
  const classes = path.join(dir, 'classes');
  fs.mkdirSync(extracted, { recursive: true });
  fs.mkdirSync(classes);
  const inspected = JSON.parse(docker('image', 'inspect', image))[0];
  const name = `${project}-extract-${role}-${Date.now()}`;
  let container;
  try {
    // Pin create to the inspected immutable image ID, not a tag that could move mid-run.
    container = docker('create', '--name', name, '--label', `com.docker.compose.project=${project}`, inspected.Id).trim();
    docker('cp', `${container}:/app/target/demo-netty-web-spring-boot-starter-${version}.jar`, path.join(dir, 'application.jar'));
  } finally { if (container) docker('rm', container); }
  const fatJar = path.join(dir, 'application.jar');
  const sha256 = hash(fatJar);
  const expected = process.env[`EXPECTED_${role.toUpperCase()}_JAR_SHA256`];
  if (expected) assert.equal(sha256, expected.toLowerCase(), `${role} image fat jar pin`);
  run(jar, ['--extract', '--file', fatJar], { cwd: extracted });
  const lib = path.join(extracted, 'BOOT-INF', 'lib');
  const jars = fs.readdirSync(lib).filter(n => n.endsWith('.jar')).sort()
    .map(name => ({ name, sha256: hash(path.join(lib, name)) }));
  assert(jars.some(j => j.name === `netty-spring-websocket-cluster-${version}.jar`));
  assert(jars.some(j => j.name === `netty-spring-websocket-${version}.jar`));
  run(javac, ['--release', '17', '-encoding', 'UTF-8', '-cp', path.join(lib, '*'), '-d', classes, ...sources]);
  const metadata = { image, imageId: inspected.Id, repoDigests: inspected.RepoDigests, version, fatJarSha256: sha256, jars,
    classpath: [classes, path.join(lib, '*')].join(path.delimiter) };
  summary.images[role] = metadata;
  save();
  console.log(JSON.stringify({ stage: 'extracted', role, image, imageId: inspected.Id, fatJarSha256: sha256 }));
  return metadata;
}

class Worker {
  constructor(role, name) {
    this.role = role;
    this.name = `${++workerCounter}-${name}-${role}`;
    this.events = [];
    this.waiters = [];
    this.requests = new Map();
    this.stopped = false;
    const image = summary.images[role];
    this.containerName = clusterMode ? `${project}-worker-${workerCounter}` : null;
    const args = clusterMode ? ['run', '--rm', '-i', '--name', this.containerName,
      '--label', `com.docker.compose.project=${project}`, '--network', `${project}_default`,
      '--mount', `type=bind,source=${out},target=/probe,readonly`, '--entrypoint', 'java', image.imageId,
      '-Dlogback.configurationFile=/probe/logback.xml', '-Dfile.encoding=UTF-8', '-cp',
      `/probe/${role}/classes:/probe/${role}/extract/BOOT-INF/lib/*`, 'RedisClusterCompatibilityHarness',
      role === 'old' ? summary.redisUri : 'redis-b:6379,redis-c:6379,redis-a:6379', name, image.version] :
      [`-Dlogback.configurationFile=${path.join(out, 'logback.xml')}`, '-Dfile.encoding=UTF-8',
        '-cp', image.classpath, 'RedisStateCompatibilityHarness', summary.redisUri, name, image.version];
    this.process = cp.spawn(clusterMode ? 'docker' : java, args, { cwd: root, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    this.record = { name: this.name, role, nodeId: name, pid: this.process.pid, args, startedAt: new Date().toISOString() };
    summary.workers.push(this.record);
    allWorkers.add(this);
    this.log = fs.createWriteStream(path.join(out, `${this.name}.stdout.log`));
    this.err = fs.createWriteStream(path.join(out, `${this.name}.stderr.log`));
    this.process.stdout.pipe(this.log);
    this.process.stderr.pipe(this.err);
    readline.createInterface({ input: this.process.stdout }).on('line', line => {
      if (!line.startsWith('STATE_COMPAT\t')) return;
      try {
        const value = JSON.parse(line.slice('STATE_COMPAT\t'.length));
        if (value.type === 'RESULT') {
          const pending = this.requests.get(value.requestId);
          if (!pending) throw new Error(`Unknown request result ${value.requestId}`);
          this.requests.delete(value.requestId); clearTimeout(pending.timer);
          if (value.status === 'OK') pending.resolve(value); else pending.reject(new Error(JSON.stringify(value)));
        } else { this.events.push(value); this.pump(); }
      } catch (error) { this.fail(error); }
    });
    this.process.once('error', error => this.fail(error));
    this.exited = new Promise(resolve => this.process.once('close', (code, signal) => {
      this.record.exitCode = code; this.record.signal = signal; this.record.endedAt = new Date().toISOString();
      this.record.unexpectedExit = !this.record.stopMode ||
        (this.record.stopMode === 'graceful' && code !== 0);
      this.stopped = true; allWorkers.delete(this);
      this.fail(new Error(`${this.name} exited ${code}/${signal}`));
      resolve({ code, signal }); save();
    }));
  }
  fail(error) {
    for (const request of this.requests.values()) { clearTimeout(request.timer); request.reject(error); }
    this.requests.clear();
    for (const waiter of this.waiters) { clearTimeout(waiter.timer); waiter.reject(error); }
    this.waiters.length = 0;
  }
  pump() {
    for (let i = 0; i < this.waiters.length;) {
      const waiter = this.waiters[i], index = this.events.findIndex(waiter.predicate);
      if (index === -1) { i++; continue; }
      this.waiters.splice(i, 1); clearTimeout(waiter.timer);
      waiter.resolve(this.events.splice(index, 1)[0]);
    }
  }
  event(predicate, description) {
    const index = this.events.findIndex(predicate);
    if (index !== -1) return Promise.resolve(this.events.splice(index, 1)[0]);
    return new Promise((resolve, reject) => {
      const waiter = { predicate, resolve, reject, timer: setTimeout(() => {
        this.waiters = this.waiters.filter(w => w !== waiter);
        reject(new Error(`Timed out ${this.name}: ${description}; buffered=${JSON.stringify(this.events)}`));
      }, 25000) };
      this.waiters.push(waiter);
    });
  }
  async command(op, ...args) {
    assert(!this.stopped, `${this.name} already stopped`);
    const id = String(++commandCounter);
    const record = { worker: this.name, id, op, args, at: new Date().toISOString() };
    summary.commands.push(record);
    const result = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.requests.delete(id); reject(new Error(`Timed out ${this.name}: ${op}`)); }, 25000);
      this.requests.set(id, { resolve, reject, timer });
      this.process.stdin.write([id, op, ...args.map(arg => Buffer.from(String(arg)).toString('base64'))].join('\t') + '\n');
    });
    record.result = result; save(); return result;
  }
  async stop(hard = false) {
    if (this.stopped) {
      assert(!this.record.unexpectedExit,
        `Unexpected prior exit ${this.name}: ${this.record.exitCode}/${this.record.signal}`);
      return;
    }
    this.record.stopMode = hard ? 'kill-before-ACK-or-delete' : 'graceful';
    const kill = () => this.containerName ? docker('kill', this.containerName) : this.process.kill('SIGKILL');
    if (hard) kill(); else await this.command('STOP');
    let timer;
    const result = await Promise.race([this.exited, new Promise((_, reject) => {
      timer = setTimeout(() => {
        try { kill(); } catch (_) { /* the original shutdown timeout is the failure */ }
        reject(new Error(`Timed out stopping ${this.name}`));
      }, 15000);
    })]).finally(() => clearTimeout(timer));
    if (!hard) assert.equal(result.code, 0, `Clean shutdown ${this.name}`);
  }
}

async function start(role, name) {
  const worker = new Worker(role, name);
  const ready = await worker.event(e => e.type === 'READY', 'READY');
  assert.equal(ready.version, summary.images[role].version);
  assert.match(ready.java, /^17\./, 'Execute both releases on Java 17');
  assert.equal(ready.hmac, 'strict');
  worker.record.ready = ready; save(); return worker;
}
function expected(label, sequence) {
  return { kind: 'state-compat', label, sequence, unicode: '雪', nullable: null, date: 0, state: 'READY',
    nested: { list: [true, sequence, 'x'] } };
}
function checkEnvelope(e, label, sequence) {
  assert.equal(e.wireVersion, 2);
  assert.equal(e.payload.slice(0, 2), 'J:');
  assert.deepEqual(JSON.parse(e.payload.slice(2)), expected(label, sequence));
  assert.deepEqual(JSON.parse(e.frame), expected(label, sequence), 'JSON frame stays an object without double encoding');
}
function checkEntries(entries, label, count) {
  assert.equal(entries.length, count);
  for (let i = 0; i < count; i++) {
    assert.match(entries[i].wire, /^H1:/);
    checkEnvelope(entries[i].envelope, label, i + 1);
    if (i) {
      const a = entries[i - 1].id.split('-').map(BigInt), b = entries[i].id.split('-').map(BigInt);
      assert(a[0] < b[0] || (a[0] === b[0] && a[1] < b[1]), 'Redis IDs strictly increasing');
    }
  }
}
async function receive(worker, label, count, type = 'RELIABLE') {
  const received = [];
  for (let i = 1; i <= count; i++) {
    const event = await worker.event(e => e.type === type, `${type} ${label}/${i}`);
    checkEnvelope(event, label, i); received.push(event);
  }
  return received;
}
function checkDrained(result, written, label, count) {
  assert.equal(result.messages.length, count);
  assert.deepEqual(result.messages.map(m => m.id), written.entries.map(e => e.id));
  for (let i = 0; i < count; i++) checkEnvelope(result.messages[i], label, i + 1);
}
async function checkDeliveryEvents(worker, messages) {
  for (const message of messages) {
    const event = await worker.event(e => e.type === 'OFFLINE_DELIVERY', 'offline local delivery');
    assert.equal(event.offlineId, message.id, 'Stored ID available in MDC during dispatch');
    assert.deepEqual(JSON.parse(event.frame), JSON.parse(message.frame));
    assert.equal(event.uri, '/state-compat');
    assert.deepEqual(event.sessions, ['compat-session']);
  }
}
async function stage(name, fn) {
  const record = { name, startedAt: new Date().toISOString(), status: 'RUNNING' };
  summary.stages.push(record); save(); console.log(JSON.stringify({ stage: name, status: 'RUNNING' }));
  try { await fn(record); record.status = 'PASS'; }
  catch (error) { record.status = 'FAIL'; record.error = error.stack; throw error; }
  finally { record.endedAt = new Date().toISOString(); save(); console.log(JSON.stringify({ stage: name, status: record.status })); }
}

async function replay(from, to) {
  const name = `reliable-backlog-${from}-to-${to}`;
  await stage(name, async record => {
    const uri = '/' + name, consumer = name + '-consumer';
    const writer = await start(from, name + '-writer');
    let reader = await start(from, consumer);
    await reader.command('R_SUB', uri, consumer, false);
    await writer.command('R_WRITE', uri, 'warmup', 1);
    await receive(reader, 'warmup', 1);
    await reader.command('R_ACKED', uri, consumer);
    await reader.stop();
    const offline = await writer.command('R_WRITE', uri, 'backlog', 3);
    checkEntries(offline.entries.slice(1), 'backlog', 3);
    reader = await start(to, consumer);
    await reader.command('R_SUB', uri, consumer, false);
    record.replayed = await receive(reader, 'backlog', 3);
    record.afterReplay = await reader.command('R_ACKED', uri, consumer);
    await writer.command('R_WRITE', uri, 'live-mixed', 1);
    await receive(reader, 'live-mixed', 1);
    await reader.command('R_ACKED', uri, consumer);
    await reader.stop();
    reader = await start(to, consumer);
    await reader.command('R_SUB', uri, consumer, false);
    await writer.command('R_WRITE', uri, 'restart-barrier', 1);
    // FIFO barrier proves previously ACKed entries were not delivered again on restart.
    await receive(reader, 'restart-barrier', 1);
    record.final = await reader.command('R_ACKED', uri, consumer);
    assert.equal(record.final.length, 6);
    await reader.stop(); await writer.stop();
  });
}
async function pending(from, to) {
  const name = `reliable-pending-${from}-to-${to}`;
  await stage(name, async record => {
    const uri = '/' + name, consumer = name + '-consumer';
    const writer = await start(from, name + '-writer');
    let reader = await start(from, consumer);
    await reader.command('R_SUB', uri, consumer, true);
    const written = await writer.command('R_WRITE', uri, name, 1);
    checkEntries(written.entries, name, 1);
    await receive(reader, name, 1, 'RELIABLE_BLOCKED');
    record.beforeCrash = await writer.command('R_STATE', uri, consumer);
    assert.equal(record.beforeCrash.pending, 1, 'Delivery callback has not returned, entry genuinely unACKed');
    await reader.stop(true);
    record.afterCrash = await writer.command('R_STATE', uri, consumer);
    assert.equal(record.afterCrash.pending, 1);
    reader = await start(to, consumer);
    await reader.command('R_SUB', uri, consumer, false);
    record.replayed = await receive(reader, name, 1);
    await reader.command('R_ACKED', uri, consumer);
    record.afterReplay = await writer.command('R_STATE', uri, consumer);
    assert.equal(record.afterReplay.pending, 0);
    assert.deepEqual(record.afterReplay.entries, record.beforeCrash.entries, 'ACK does not rewrite persisted envelope or Redis ID');
    await reader.stop(); await writer.stop();
  });
}
async function offline(from, to) {
  const name = `offline-reclaim-${from}-to-${to}`;
  await stage(name, async record => {
    const user = name + ':雪';
    const writer = await start(from, name + '-writer');
    let reader = await start(to, name + '-reader');
    const written = await writer.command('O_WRITE', user, name, 3, 120);
    checkEntries(written.entries, name, 3);
    const drained = await reader.command('O_DRAIN', user, false, 120);
    checkDrained(drained, written, name, 3);
    await checkDeliveryEvents(reader, drained.messages);
    record.duringLease = await writer.command('O_STATE', user);
    assert(record.duringLease.lockTtl > 0);
    assert(record.duringLease.ttl > 0 && record.duringLease.ttl <= written.ttl, 'Read preserves remaining TTL');
    assert.deepEqual(record.duringLease.entries, written.entries, 'No delete before application confirms delivery');
    const competing = await writer.command('O_DRAIN', user, false, 120);
    assert.deepEqual(competing.messages, [], 'Foreign active lease excludes concurrent drain');
    await reader.stop(true);
    reader = await start(from, name + '-reader');
    await reader.command('O_UNLOCK', user);
    const recovered = await reader.command('O_DRAIN', user, true, 120);
    checkDrained(recovered, written, name, 3);
    await checkDeliveryEvents(reader, recovered.messages);
    record.recovered = recovered;
    record.afterDelete = await writer.command('O_STATE', user);
    assert.deepEqual(record.afterDelete.entries, []);
    assert.equal(record.afterDelete.lockTtl, -2);
    await reader.stop(); await writer.stop();
  });
}
async function retention(from, to) {
  const name = `offline-ttl-${from}-to-${to}`;
  await stage(name, async record => {
    const writer = await start(from, name + '-writer'), reader = await start(to, name + '-reader');
    const user = name + ':retained';
    const expired = await writer.command('O_WRITE', user, 'expired', 1, 3);
    await writer.command('O_TTL_BELOW', user, 1500);
    const retained = await reader.command('O_WRITE', user, 'retained', 1, 3);
    assert.equal(retained.entries.length, 2);
    await reader.command('O_ENTRY_AGE', expired.entries[0].id, 3200);
    record.drained = await reader.command('O_DRAIN', user, true, 3);
    assert.equal(record.drained.messages.length, 1, 'Expired entry reaped even though a later write refreshed key TTL');
    checkEnvelope(record.drained.messages[0], 'retained', 1);
    assert.equal(record.drained.messages[0].id, retained.entries[1].id);
    await checkDeliveryEvents(reader, record.drained.messages);
    assert.deepEqual((await writer.command('O_STATE', user)).entries, []);
    const abandoned = name + ':abandoned';
    await writer.command('O_WRITE', abandoned, 'abandoned', 1, 1);
    await reader.command('O_EXPIRED', abandoned);
    assert.deepEqual((await reader.command('O_DRAIN', abandoned, true, 1)).messages, []);
    record.expired = await reader.command('O_STATE', abandoned);
    assert.equal(record.expired.ttl, -2); assert.equal(record.expired.lockTtl, -2);
    await reader.stop(); await writer.stop();
  });
}
async function fullRollback() {
  await stage('complete-application-classpath-rollback', async record => {
    let writer = await start('new', 'rollback-writer'), reader = await start('new', 'rollback-reader');
    const sentinel = 'preserve-shared-redis-' + runId;
    await writer.command('SENTINEL', sentinel);
    const uri = '/complete-rollback', consumer = 'rollback-reader', user = 'complete-rollback:offline';
    await reader.command('R_SUB', uri, consumer, false);
    await writer.command('R_WRITE', uri, 'before-stop', 1);
    await receive(reader, 'before-stop', 1); await reader.command('R_ACKED', uri, consumer);
    await reader.stop();
    const written = await writer.command('O_WRITE', user, 'new-before-rollback', 3, 120);
    await writer.command('R_WRITE', uri, 'new-before-rollback', 3);
    await writer.stop();
    record.redisBefore = compose('ps', '-q', 'redis').trim();
    writer = await start('old', 'rollback-writer'); reader = await start('old', 'rollback-reader');
    assert.equal((await reader.command('SENTINEL')).value, sentinel);
    assert.equal(compose('ps', '-q', 'redis').trim(), record.redisBefore, 'Redis is not recreated during rollback');
    await reader.command('R_SUB', uri, consumer, false);
    await receive(reader, 'new-before-rollback', 3); await reader.command('R_ACKED', uri, consumer);
    const drained = await reader.command('O_DRAIN', user, true, 120);
    checkDrained(drained, written, 'new-before-rollback', 3); await checkDeliveryEvents(reader, drained.messages);
    record.recovered = drained;
    await writer.command('R_WRITE', uri, 'old-after-rollback', 1);
    await receive(reader, 'old-after-rollback', 1); record.finalAck = await reader.command('R_ACKED', uri, consumer);
    const next = await writer.command('O_WRITE', user, 'old-after-rollback', 1, 120);
    const nextDrain = await reader.command('O_DRAIN', user, true, 120);
    checkDrained(nextDrain, next, 'old-after-rollback', 1); await checkDeliveryEvents(reader, nextDrain.messages);
    record.finalOffline = await reader.command('O_STATE', user);
    assert.deepEqual(record.finalOffline.entries, []);
    await reader.stop(); await writer.stop();
  });
}

async function clusterScenarios() {
  let left = await start('old', 'cluster-left'), right = await start('new', 'cluster-right');
  const uri = '/cluster-state-compat';
  async function lookup(worker, route, owner, count, present) {
    const result = await worker.command('LOOKUP', route, owner, count);
    assert.deepEqual(Object.values(result.nodes), Array(count).fill(present ? owner : null));
    assert.deepEqual([...result.ids].sort(), present ? Array.from({ length: count }, (_, i) => `${owner}-${i + 1}`).sort() : []);
    return result;
  }
  async function pubsub(a, b, label) {
    await a.command('SUB', uri, ''); await b.command('SUB', uri, '');
    await a.command('PUBLISH', uri, '', label + '-broadcast', 3);
    await receive(a, label + '-broadcast', 3, 'CLUSTER_MESSAGE');
    await receive(b, label + '-broadcast', 3, 'CLUSTER_MESSAGE');
    const target = 'probe-target-' + b.record.nodeId;
    await b.command('SUB', uri, target);
    await a.command('PUBLISH', uri, target, label + '-unicast', 3);
    const messages = await receive(b, label + '-unicast', 3, 'CLUSTER_MESSAGE');
    for (const message of messages) {
      assert.equal(message.kind, 'UNICAST'); assert.equal(message.target, 'probe-session');
      assert.equal(message.origin, a.record.nodeId);
    }
  }
  await stage('three-master-topology-and-unsupported-feature-rejection', async record => {
    record.left = await left.command('TOPOLOGY'); record.right = await right.command('TOPOLOGY');
    for (const topology of [record.left, record.right]) {
      assert.equal(topology.partitions, 3);
      assert.match(topology.info, /cluster_state:ok/);
      assert.match(topology.info, /cluster_slots_assigned:16384/);
      assert.match(topology.info, /cluster_size:3/);
      assert.equal(topology.nodes.trim().split(/\r?\n/).filter(line => /\bmaster\b/.test(line)).length, 3);
    }
    record.gates = [await left.command('GATES'), await right.command('GATES')];
    for (const gates of record.gates) {
      for (const type of ['ClusterRoomRegistry', 'ReliableBroker', 'OfflineQueueStore', 'UserRegistry', 'UserIdResolver', 'PresenceRegistry'])
        assert.equal(gates[type], 0);
      for (const feature of ['room', 'reliable', 'offline', 'presence']) assert.match(gates[feature + 'Rejection'], /disabled/);
    }
  });
  for (const [writer, reader, label] of [[left, right, 'old-to-new'], [right, left, 'new-to-old']]) {
    await stage('cluster-registry-' + label, async record => {
      const route = uri + '/' + label, owner = 'fixture-' + label;
      record.written = await writer.command('REGISTER', route, owner, 24);
      assert.equal(new Set(record.written.entries.map(e => e.master)).size, 3, 'Session keys span all three real masters');
      record.read = await lookup(reader, route, owner, 24, true);
      await reader.command('DEREGISTER', route, owner + '-1');
      const removed = await writer.command('LOOKUP', route, owner, 24);
      assert.equal(removed.nodes[owner + '-1'], null); assert.equal(removed.ids.length, 23);
      await reader.command('REMOVE_NODE', owner);
      record.afterRemove = await lookup(writer, route, owner, 24, false);
    });
  }
  await stage('cluster-heartbeat-and-reaper-both-directions', async record => {
    record.directions = [];
    for (const [writer, reader, label] of [[left, right, 'old-new'], [right, left, 'new-old']]) {
      const node = 'expiry-fixture-' + label;
      await writer.command('HB_REGISTER', node, 700);
      const active = await reader.command('HB_STATE', node);
      assert(active.ttl > 0); assert.equal(active.expired, false);
      const expired = await reader.command('HB_EXPIRED', node);
      assert.equal(expired.ttl, -2);
      await reader.command('HB_RENEW', node, 2000);
      const renewed = await writer.command('HB_STATE', node);
      assert(renewed.ttl > 0); assert.equal(renewed.expired, false);
      await writer.command('HB_REMOVE', node);
      const removed = await reader.command('HB_STATE', node);
      assert.equal(removed.ttl, -2); assert.equal(removed.expired, false);
      assert.equal((await writer.command('CLAIM', node, 700)).claimed, true);
      assert.equal((await reader.command('CLAIM', node, 700)).claimed, false);
      await reader.command('CLAIM_EXPIRED', node);
      assert.equal((await reader.command('CLAIM', node, 700)).claimed, true);
      assert.equal((await writer.command('CLAIM', node, 700)).claimed, false);
      record.directions.push({ label, active, expired, renewed, removed });
    }
  });
  await stage('cluster-hmac-json-pubsub-broadcast-unicast-both-directions', async () => {
    await pubsub(left, right, 'old-to-new'); await pubsub(right, left, 'new-to-old');
  });
  const retainedRoute = uri + '/retained', retainedOwner = 'retained-across-complete-rollback';
  await stage('cluster-rolling-upgrade-with-existing-registry', async record => {
    record.written = await left.command('REGISTER', retainedRoute, retainedOwner, 24);
    await left.stop(); left = await start('new', 'cluster-left');
    record.read = await lookup(left, retainedRoute, retainedOwner, 24, true);
    await pubsub(left, right, 'all-new');
    record.newWrite = await right.command('REGISTER', uri + '/new-before-rollback', 'new-written-state', 24);
  });
  await stage('cluster-complete-release-rollback-with-shared-state', async record => {
    record.redisContainers = compose('ps', '-q').trim().split(/\r?\n/).sort();
    await left.stop(); await right.stop();
    left = await start('old', 'cluster-left'); right = await start('old', 'cluster-right');
    assert.deepEqual(compose('ps', '-q').trim().split(/\r?\n/).sort(), record.redisContainers);
    record.oldWrittenRead = await lookup(right, retainedRoute, retainedOwner, 24, true);
    record.newWrittenRead = await lookup(left, uri + '/new-before-rollback', 'new-written-state', 24, true);
    await pubsub(left, right, 'all-old-after-rollback');
    await left.command('REMOVE_NODE', retainedOwner); await right.command('REMOVE_NODE', 'new-written-state');
    await lookup(left, retainedRoute, retainedOwner, 24, false);
    await lookup(right, uri + '/new-before-rollback', 'new-written-state', 24, false);
    record.finalTopology = await right.command('TOPOLOGY');
    await left.stop(); await right.stop();
  });
}

async function main() {
  try {
    summary.javaVersion = run(java, ['--version']);
    assert.equal(docker('ps', '-aq', '--filter', `label=com.docker.compose.project=${project}`).trim(), '',
      `Existing ${project} containers found; inspect and explicitly remove only this previous test project before retrying.`);
    extract('old', process.env.OLD_IMAGE || 'netty-spring-1101-check:1.10.1', '1.10.1');
    extract('new', process.env.NEW_IMAGE || 'netty-spring-200-check:2.0.0-SNAPSHOT', process.env.NEW_VERSION || '2.0.0-SNAPSHOT');
    composeStarted = true;
    compose('up', '-d', '--wait', '--wait-timeout', '60');
    if (clusterMode) {
      summary.clusterCreate = compose('exec', '-T', 'redis-a', 'redis-cli', '--cluster', 'create',
        'redis-a:6379', 'redis-b:6379', 'redis-c:6379', '--cluster-replicas', '0', '--cluster-yes');
      const deadline = Date.now() + 30000;
      while (true) {
        const infos = ['redis-a', 'redis-b', 'redis-c'].map(service => compose('exec', '-T', service, 'redis-cli', 'cluster', 'info'));
        if (infos.every(info => /cluster_state:ok/.test(info) && /cluster_slots_assigned:16384/.test(info))) break;
        if (Date.now() >= deadline) throw new Error('Redis Cluster did not become ready');
        await new Promise(resolve => setTimeout(resolve, 100));
      }
      summary.redisContainers = JSON.parse(docker('inspect', ...compose('ps', '-q').trim().split(/\r?\n/)));
      await clusterScenarios();
    } else {
      const redisId = compose('ps', '-q', 'redis').trim();
      summary.redisContainer = JSON.parse(docker('inspect', redisId))[0];
      for (const [from, to] of [['old', 'new'], ['new', 'old']]) await replay(from, to);
      for (const [from, to] of [['old', 'new'], ['new', 'old']]) await pending(from, to);
      for (const [from, to] of [['old', 'new'], ['new', 'old']]) await offline(from, to);
      for (const [from, to] of [['old', 'new'], ['new', 'old']]) await retention(from, to);
      await fullRollback();
    }
    summary.status = 'PASS';
  } catch (error) { summary.status = 'FAIL'; summary.error = error.stack; process.exitCode = 1; console.error(error.stack); }
  finally {
    const cleanupErrors = [];
    for (const worker of [...allWorkers]) {
      try { await worker.stop(true); } catch (error) { cleanupErrors.push(error.stack); }
    }
    if (composeStarted) {
      try { fs.writeFileSync(path.join(out, 'redis.log'), compose('logs', '--no-color')); }
      catch (error) { cleanupErrors.push(error.stack); }
      try { compose('down', '--volumes', '--remove-orphans'); }
      catch (error) { cleanupErrors.push(error.stack); }
    }
    summary.cleanupErrors = cleanupErrors;
    summary.unexpectedWorkerExits = summary.workers.filter(worker => worker.unexpectedExit).map(worker => worker.name);
    if (cleanupErrors.length || summary.unexpectedWorkerExits.length) { summary.status = 'FAIL'; process.exitCode = 1; }
    summary.finishedAt = new Date().toISOString(); save();
    console.log(JSON.stringify({ status: summary.status, passed: summary.stages.filter(s => s.status === 'PASS').length,
      stages: summary.stages.length, evidence: out }));
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
