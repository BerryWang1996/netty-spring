'use strict';
// Requires Docker, JDK 17+ (javac/jar), and the cached jnats jar. No Maven or npm install.
// node docker-demo/migration/verify-transports.cjs --run
// OLD_IMAGE / NEW_IMAGE, OLD_IMAGE_SHA256 / NEW_IMAGE_SHA256 and *_JAR_SHA256
// optionally pin artifacts. Defaults target the locally saved migration images.
// NEW_VERSION selects the expected new release metadata (default: 2.0.0-SNAPSHOT).
// Evidence is written under ignored .m2; only this Compose project's resources are removed.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const net = require('node:net');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
if (!process.argv.includes('--run')) {
  console.log('Prepared. Use --run to start isolated cross-release NATS/JetStream/Mesh verification.');
  process.exit(0);
}
const root = path.resolve(__dirname, '../..');
const project = 'netty-spring-transport-compat-200';
const out = path.join(root, '.m2', 'transport-compat-200-' + new Date().toISOString().replace(/[:.]/g, '-'));
fs.mkdirSync(out, { recursive: true });
fs.writeFileSync(path.join(root, '.m2', 'transport-compat-200-latest.txt'), out);
const versions = {
  old: { version: '1.10.1', image: process.env.OLD_IMAGE || 'netty-spring-1101-check:1.10.1' },
  new: { version: process.env.NEW_VERSION || '2.0.0-SNAPSHOT', image: process.env.NEW_IMAGE || 'netty-spring-200-check:2.0.0-SNAPSHOT' }
};
const jnats = process.env.JNATS_JAR || path.join(os.homedir(), '.m2/repository/io/nats/jnats/2.20.4/jnats-2.20.4.jar');
const checks = [], transcript = [], exportContainers = new Set();
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const sha = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const save = (name, value) => fs.writeFileSync(path.join(out, name), JSON.stringify(value, null, 2));
const binary = Buffer.from([0, 255, 127, 128, 1, 2, 10, 13, 34, 92, 240, 159, 152, 128]).toString('base64');
const uri = '/compat/transport', newUri = '/compat/created-by-200';
let bVersion = 'old';
let compose;
function command(exe, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(exe, args, { windowsHide: true, cwd: options.cwd || root, env: { ...process.env, ...options.env } });
    let stdout = '', stderr = '';
    const timer = setTimeout(() => child.kill(), options.timeout || 60000);
    child.stdout.on('data', data => stdout += data);
    child.stderr.on('data', data => stderr += data);
    child.on('error', error => { clearTimeout(timer); reject(error); });
    child.on('close', code => {
      clearTimeout(timer);
      const result = { at: new Date().toISOString(), exe, args, code, stdout, stderr };
      transcript.push(result);
      fs.appendFileSync(path.join(out, 'commands.ndjson'), JSON.stringify(result) + '\n');
      if (code !== 0 && !options.allowFailure) reject(Object.assign(new Error(`${exe} ${args[0]} exited ${code}: ${stderr}`), { result }));
      else resolve(result);
    });
  });
}
const docker = (args, options) => command('docker', args, options);
function env() { return {
  PROBE_OLD_IMAGE: versions.old.imageId, PROBE_OLD_DIR: versions.old.dir.replace(/\\/g, '/'),
  PROBE_B_IMAGE: versions[bVersion].imageId, PROBE_B_DIR: versions[bVersion].dir.replace(/\\/g, '/'),
  PROBE_B_VERSION: versions[bVersion].version
}; }
const dc = (args, options = {}) => docker(['compose', '-p', project, '-f', compose, ...args], { ...options, env: env() });
function check(name, evidence = {}) {
  checks.push({ name, passed: true, at: new Date().toISOString(), ...evidence });
  save('checks.json', checks); console.log('PASS ' + name);
}
async function api(slot, values = {}) {
  const input = { op: 'state', ...values };
  const response = await fetch(`http://127.0.0.1:${slot === 'a' ? 18086 : 18087}/command`, {
    method: 'POST', body: new URLSearchParams(input), signal: AbortSignal.timeout(15000)
  });
  const body = await response.json();
  const record = { at: new Date().toISOString(), slot, input, status: response.status, body };
  fs.appendFileSync(path.join(out, 'http.ndjson'), JSON.stringify(record) + '\n');
  if (!response.ok) throw new Error(JSON.stringify(record));
  return body;
}
async function eventually(fn, label, timeout = 30000) {
  const end = Date.now() + timeout; let last;
  do {
    try { const result = await fn(); if (result) return result; } catch (error) { last = error; }
    await wait(150);
  } while (Date.now() < end);
  throw new Error(`${label}: timeout${last ? ': ' + last.message : ''}`);
}
async function ready(slot, version) {
  const result = await eventually(async () => {
    const s = await api(slot); return s.version === versions[version].version && s.core === 'ACTIVE' && s.reliable === 'ACTIVE' ? s : null;
  }, 'readiness ' + slot);
  assert.match(result.source, new RegExp(`netty-spring-websocket-cluster-${versions[version].version.replaceAll('.', '\\.')}`));
  return result;
}
async function subscribe(slot, transport, targetUri = uri) { return api(slot, { op: 'subscribe', transport, uri: targetUri }); }
async function subscribeAll(slot, targetUri = uri) { for (const transport of ['core', 'mesh', 'reliable']) await subscribe(slot, transport, targetUri); }
async function meshReady() {
  await eventually(async () => {
    const [a, b] = await Promise.all([api('a'), api('b')]);
    return a.mesh === 'ACTIVE' && b.mesh === 'ACTIVE' && a.outbound >= 1 && b.outbound >= 1 && a.peers >= 1 && b.peers >= 1;
  }, 'bidirectional Mesh connection');
}
async function inspect(slot, targetUri = uri, consumer = slot) { return api(slot, { op: 'inspect', uri: targetUri, consumer }); }
async function acked(slot, targetUri = uri, consumer = slot) {
  return eventually(async () => {
    const s = await inspect(slot, targetUri, consumer);
    return s.pending === 0 && s.ackPending === 0 && s.ackFloor === s.delivered ? s : null;
  }, 'durable ACK ' + slot);
}
async function received(slot, transport, sent, label) {
  const event = await eventually(async () => {
    const all = await api(slot, { op: 'events' });
    return all.find(e => e.transport === transport && e.wire === sent.wire);
  }, label);
  assert.equal(event.payload, sent.payload);
  assert.equal(event.reencoded, sent.payload, 'receiving release must decode/reencode payload without changing bytes');
  const payload = Buffer.from(sent.payload, 'base64').toString();
  if (payload.startsWith('B:')) {
    assert.equal(event.messageClass, 'BinaryMessage'); assert.equal(event.response, payload.slice(2));
  } else {
    assert.equal(Buffer.from(event.response, 'base64').toString(), payload.slice(2));
    assert.equal(event.messageClass, payload.startsWith('J:') ? 'JsonMessage' : 'TextMessage');
    if (payload.startsWith('J:')) {
      const value = JSON.parse(Buffer.from(event.response, 'base64').toString());
      assert.equal(value.createdAt, 0); assert.equal(value.state, 'READY'); assert.equal(value.optional, null);
      assert.deepEqual(value.values, [1, true, null]);
    }
  }
  return event;
}
async function send(slot, transport, type, token, options = {}) {
  return api(slot, { op: 'publish', transport, type, token, binary, uri, ...options });
}
async function bilateral(label) {
  for (const [from, to] of [['a', 'b'], ['b', 'a']]) {
    for (const transport of ['core', 'mesh', 'reliable']) {
      for (const type of ['text', 'json', 'binary']) {
        const sent = await send(from, transport, type, `${label}-${from}-${transport}-${type}`);
        await received(to, transport, sent, 'broadcast delivery');
        if (transport !== 'reliable') {
          const direct = await send(from, transport, type, `${label}-unicast-${from}-${transport}-${type}`, { kind: 'UNICAST', target: to });
          await received(to, transport, direct, 'unicast delivery');
        }
        check(`${label}: ${from}→${to} ${transport} ${type}`, { kinds: transport === 'reliable' ? ['BROADCAST'] : ['BROADCAST', 'UNICAST'] });
      }
    }
  }
  check(label + ': durable ACKs settled', { a: await acked('a'), b: await acked('b') });
}
async function offlineUpgrade(version, label, targetUri = uri) {
  await dc(['stop', '-t', '12', 'b']);
  fs.writeFileSync(path.join(out, label.replaceAll(' ', '-') + '-previous-b.log'), (await dc(['logs', '--no-color', 'b'])).stdout);
  const queued = [];
  for (const type of ['text', 'json', 'binary']) queued.push(await send('a', 'reliable', type, label + '-backlog-' + type, { uri: targetUri }));
  const before = await eventually(async () => { const s = await inspect('a', targetUri, 'b'); return s.pending >= 3 ? s : null; }, 'queued durable messages');
  const coreOffline = await send('a', 'core', 'text', label + '-offline-core', { kind: 'UNICAST', target: 'b' });
  let meshOffline;
  try { meshOffline = await send('a', 'mesh', 'text', label + '-offline-mesh', { kind: 'UNICAST', target: 'b' }); }
  catch (e) { meshOffline = { rejected: e.message }; }
  bVersion = version;
  await dc(['up', '-d', '--no-deps', '--force-recreate', 'b']);
  await ready('b', version);
  await subscribeAll('b');
  if (targetUri !== uri) await subscribe('b', 'reliable', targetUri);
  await meshReady();
  for (const sent of queued) await received('b', 'reliable', sent, label + ' backlog');
  const after = await acked('b', targetUri);
  await wait(700);
  const events = await api('b', { op: 'events' });
  const queuedWires = queued.map(sent => sent.wire);
  const observedReplayOrder = events.filter(e => e.transport === 'reliable' && queuedWires.includes(e.wire)).map(e => e.wire);
  assert.deepEqual(observedReplayOrder, queuedWires, 'durable backlog must replay in publication order');
  for (const sent of queued) assert.equal(events.filter(e => e.wire === sent.wire).length, 1, 'no duplicate durable replay');
  assert(!events.some(e => e.wire === coreOffline.wire), 'NATS Core offline unicast must not falsely replay');
  if (meshOffline.wire) assert(!events.some(e => e.wire === meshOffline.wire), 'Mesh offline unicast must not falsely replay');
  check(label + ': same-node durable replay and ACK', { version: versions[version].version, before, after, replayed: queued.length, observedReplayOrder });
  check(label + ': at-most-once offline semantics', { coreOffline, meshOffline });
}
async function inject(port, buffers, expectClose = false) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port });
    let done = false;
    const finish = (error, result) => { if (done) return; done = true; clearTimeout(timer); socket.destroy(); error ? reject(error) : resolve(result); };
    const timer = setTimeout(() => finish(expectClose ? new Error('oversized Mesh frame did not close') : null, { sent: true }), expectClose ? 5000 : 500);
    socket.on('error', error => expectClose && error.code === 'ECONNRESET' ? finish(null, { rejected: true, reason: error.code }) : finish(error));
    socket.on('close', () => expectClose ? finish(null, { rejected: true, reason: 'closed' }) : finish(null, { sent: true }));
    socket.on('connect', async () => { for (const b of buffers) { socket.write(b); await wait(25); } });
  });
}
async function frames() {
  for (const [from, to] of [['a', 'b'], ['b', 'a']]) {
    const port = to === 'a' ? 18088 : 18089;
    const sample = await api(from, { op: 'frame', type: 'json', token: `fragments-${from}`, uri, timestamp: '1700000000000' });
    const bytes = Buffer.from(sample.frame, 'base64');
    assert.equal(bytes.readUInt32BE(0), bytes.length - 4);
    assert.equal(bytes.subarray(4).toString(), sample.wrapped);
    const signature = crypto.createHmac('sha256', 'migration-probe-only-shared-hmac-2026').update(sample.wire).digest('base64url');
    assert.equal(sample.wrapped, 'H1:' + signature + ':' + sample.wire);
    assert.equal(sample.wire.split('|').length, 9); assert(sample.wire.startsWith('2|'));
    const payload = sample.wire.split('|').at(-1);
    await inject(port, [bytes.subarray(0, 1), bytes.subarray(1, 3), bytes.subarray(3, 17), bytes.subarray(17)]);
    await received(to, 'mesh', { wire: sample.wire, payload }, 'split Mesh frame');
    const coalesced = [];
    for (const type of ['text', 'binary']) coalesced.push(await api(from, { op: 'frame', type, binary, token: `coalesced-${from}-${type}`, uri }));
    await inject(port, [Buffer.concat(coalesced.map(s => Buffer.from(s.frame, 'base64')))]);
    for (const s of coalesced) await received(to, 'mesh', { wire: s.wire, payload: s.wire.split('|').at(-1) }, 'coalesced Mesh frame');
    const rejectedBefore = (await api(to)).rejected;
    const bad = await api(from, { op: 'frame', token: 'bad-hmac-' + from, badHmac: 'true', uri });
    const future = await api(from, { op: 'frame', token: 'unknown-version-' + from, wireVersion: '99', uri });
    await inject(port, [Buffer.from(bad.frame, 'base64')]);
    await eventually(async () => (await api(to)).rejected > rejectedBefore, 'HMAC rejection counter');
    const barrier = await api(from, { op: 'frame', token: 'after-unknown-version-' + from, uri });
    // A valid successor on the SAME TCP stream proves that the signed future-version
    // frame was consumed before the absence assertion, not lost behind a closed socket.
    await inject(port, [Buffer.concat([Buffer.from(future.frame, 'base64'), Buffer.from(barrier.frame, 'base64')])]);
    await received(to, 'mesh', { wire: barrier.wire, payload: barrier.wire.split('|').at(-1) }, 'valid successor after rejected future-version frame');
    const events = await api(to, { op: 'events' });
    assert(!events.some(e => e.wire === bad.wire || e.wire === future.wire));
    assert.equal(events.filter(e => e.wire === sample.wire).length, 1);
    const oversized = Buffer.alloc(5); oversized.writeUInt32BE(8193);
    const close = await inject(port, [oversized], true);
    check(`mixed: ${from}→${to} Mesh framing`, { lengthBytes: 4, endian: 'big', version: 2, envelopeFields: 9,
      splitChunks: [1, 2, 14, bytes.length - 17], coalescedFrames: 2, hmacRejected: true, futureVersionRejected: true,
      futureVersionSuccessorDelivered: barrier.wire, oversized: close, sample });
  }
}
async function prepareArtifacts() {
  assert(fs.existsSync(jnats), 'Set JNATS_JAR to cached jnats-2.20.4.jar');
  save('environment.json', { platform: process.platform, release: os.release(), node: process.version,
    jnats: { path: jnats, sha256: sha(jnats) }, javac: (await command('javac', ['-version'])).stdout,
    beforeContainers: (await docker(['ps', '--format', '{{json .}}'])).stdout });
  for (const [key, v] of Object.entries(versions)) {
    const metadata = JSON.parse((await docker(['image', 'inspect', v.image])).stdout)[0];
    v.imageId = metadata.Id;
    if (process.env[key.toUpperCase() + '_IMAGE_SHA256']) assert.equal(v.imageId, process.env[key.toUpperCase() + '_IMAGE_SHA256']);
    const dir = v.dir = path.join(out, key); fs.mkdirSync(dir);
    const name = project + '-export-' + key;
    await docker(['create', '--name', name, '--label', 'com.docker.compose.project=' + project, v.imageId]);
    exportContainers.add(name);
    const jarFile = path.join(dir, 'application.jar');
    await docker(['cp', name + ':/app/target/demo-netty-web-spring-boot-starter-' + v.version + '.jar', jarFile]);
    await docker(['rm', name]); exportContainers.delete(name);
    v.jarSha256 = sha(jarFile);
    if (process.env[key.toUpperCase() + '_JAR_SHA256']) assert.equal(v.jarSha256, process.env[key.toUpperCase() + '_JAR_SHA256']);
    await command('jar', ['xf', jarFile, 'BOOT-INF/lib', 'META-INF/maven'], { cwd: dir });
    fs.renameSync(path.join(dir, 'BOOT-INF/lib'), path.join(dir, 'lib'));
    fs.mkdirSync(path.join(dir, 'extra')); fs.copyFileSync(jnats, path.join(dir, 'extra/jnats-2.20.4.jar'));
    fs.mkdirSync(path.join(dir, 'classes'));
    const libs = fs.readdirSync(path.join(dir, 'lib')); save(key + '-libraries.json', libs);
    assert(libs.includes('netty-spring-websocket-cluster-' + v.version + '.jar'));
    assert(!libs.some(file => file.startsWith('jnats-')), 'Expected jnats to be isolated harness-only optional dependency');
    if (key === 'new') { assert(libs.includes('logback-classic-1.6.5.jar')); assert(libs.includes('logback-core-1.6.5.jar')); }
    fs.writeFileSync(path.join(dir, 'logback.xml'), '<configuration><root level="OFF"/></configuration>');
    await command('javac', ['--release', '17', '-encoding', 'UTF-8', '-cp', path.join(dir, 'lib/*') + path.delimiter + path.join(dir, 'extra/*'),
      '-d', path.join(dir, 'classes'), path.join(__dirname, 'TransportCompatibilityProbe.java')]);
    v.java = (await docker(['run', '--rm', '--label', 'com.docker.compose.project=' + project, '--entrypoint', 'java', v.imageId, '-version'])).stderr.trim();
  }
  assert.equal(versions.old.java, versions.new.java, 'Both images must use the same JRE');
  save('artifacts.json', versions);
  for (const file of ['TransportCompatibilityProbe.java', 'verify-transports.cjs']) fs.copyFileSync(path.join(__dirname, file), path.join(out, file));
}
async function main() {
  for (const port of [14225, 14226, 18086, 18087, 18088, 18089]) {
    await new Promise((resolve, reject) => { const s = net.createServer(); s.once('error', reject); s.listen(port, '127.0.0.1', () => s.close(resolve)); });
  }
  await prepareArtifacts();
  compose = path.join(out, 'compose.yml');
  const probe = (slot, image, dir, version, control, meshPort) => `  ${slot}:\n    image: \${${image}}\n    pull_policy: never\n    cpus: 2\n    mem_limit: 768m\n    volumes:\n      - "\${${dir}}:/probe:ro"\n    ports:\n      - "127.0.0.1:${control}:8080"\n      - "127.0.0.1:${meshPort}:19091"\n    entrypoint: ["java", "-Xmx256m", "-XX:ActiveProcessorCount=2", "-Dio.netty.eventLoopThreads=2", "-Dlogback.configurationFile=/probe/logback.xml", "-cp", "/probe/classes:/probe/lib/*:/probe/extra/*", "TransportCompatibilityProbe", "${slot}", "${slot}", "${version}"]\n`;
  fs.writeFileSync(compose, `name: ${project}\nservices:\n  nats:\n    image: nats:2.10\n    pull_policy: never\n    command: ["-js", "-sd", "/data", "-m", "8222"]\n    ports:\n      - "127.0.0.1:14225:4222"\n      - "127.0.0.1:14226:8222"\n  redis:\n    image: redis:7-alpine\n    pull_policy: never\n    command: ["redis-server", "--save", "", "--appendonly", "no"]\n` +
    probe('a', 'PROBE_OLD_IMAGE', 'PROBE_OLD_DIR', '1.10.1', 18086, 18088) +
    probe('b', 'PROBE_B_IMAGE', 'PROBE_B_DIR', '${PROBE_B_VERSION}', 18087, 18089));
  await dc(['up', '-d', 'nats', 'redis']);
  await eventually(async () => (await fetch('http://127.0.0.1:14226/healthz', { signal: AbortSignal.timeout(1500) })).ok, 'NATS health');
  await dc(['up', '-d', '--no-deps', 'a', 'b']);
  check('isolated old-release JVMs ready', { a: await ready('a', 'old'), b: await ready('b', 'old') });
  await subscribeAll('a'); await subscribeAll('b'); await meshReady();
  await bilateral('old baseline');
  await offlineUpgrade('new', 'rolling upgrade');
  check('mixed JVM release identities', { a: await api('a'), b: await api('b') });
  await bilateral('mixed'); await frames();
  await subscribe('b', 'reliable', newUri); await subscribe('a', 'reliable', newUri);
  const newData = await send('b', 'reliable', 'json', 'new-created-stream-data', { uri: newUri });
  await received('a', 'reliable', newData, 'old reads new-created stream');
  check('old release reads stream and payload created by new release', { a: await acked('a', newUri), b: await acked('b', newUri) });
  await offlineUpgrade('new', 'new node reconnect');
  await dc(['stop', '-t', '10', 'nats']);
  await eventually(async () => {
    const [a, b] = await Promise.all([api('a'), api('b')]);
    return a.core === 'DEGRADED' && b.core === 'DEGRADED' && a.reliable === 'DEGRADED' && b.reliable === 'DEGRADED';
  }, 'NATS loss observed on both releases');
  await dc(['start', 'nats']);
  await ready('a', 'old'); await ready('b', 'new');
  for (const transport of ['core', 'reliable']) {
    for (const [from, to] of [['a', 'b'], ['b', 'a']]) {
      const sent = await send(from, transport, 'json', `nats-reconnected-${from}-${transport}`);
      await received(to, transport, sent, 'existing subscription after server reconnect');
    }
  }
  check('same NATS server restarts: both brokers degrade, recover, existing subscriptions deliver', { a: await acked('a'), b: await acked('b') });
  await subscribe('b', 'reliable', newUri);
  await acked('b', newUri);
  await offlineUpgrade('old', 'rollback to old on new-created durable', newUri);
  await bilateral('rollback');
  check('rollback release identities', { a: await api('a'), b: await api('b') });
  save('final-events.json', { a: await api('a', { op: 'events' }), b: await api('b', { op: 'events' }) });
}
(async () => {
  let error;
  try { await main(); } catch (failure) { error = failure; save('failure.json', { message: failure.message, stack: failure.stack }); console.error(failure.stack); }
  finally {
    if (compose) {
      fs.writeFileSync(path.join(out, 'container-logs.txt'), (await dc(['logs', '--no-color'], { allowFailure: true })).stdout);
      const down = await dc(['down', '--remove-orphans', '--volumes'], { allowFailure: true });
      save('cleanup-command.json', down);
      if (down.code !== 0) error ||= new Error('Compose cleanup command failed: ' + down.stderr);
    }
    for (const name of exportContainers) await docker(['rm', '-f', name], { allowFailure: true });
    const containers = await docker(['ps', '-a', '--filter', 'label=com.docker.compose.project=' + project, '--format', '{{.ID}}'], { allowFailure: true });
    const networks = await docker(['network', 'ls', '--filter', 'label=com.docker.compose.project=' + project, '--format', '{{.ID}}'], { allowFailure: true });
    const portChecks = [];
    for (const port of [14225, 14226, 18086, 18087, 18088, 18089]) {
      portChecks.push(await new Promise(resolve => {
        const server = net.createServer();
        server.once('error', failure => resolve({ port, available: false, error: failure.code }));
        server.listen(port, '127.0.0.1', () => server.close(() => resolve({ port, available: true })));
      }));
    }
    save('cleanup.json', { containers: containers.stdout.trim(), networks: networks.stdout.trim(), ports: portChecks,
      containerInventory: containers, networkInventory: networks,
      remainingContainers: (await docker(['ps', '--format', '{{json .}}'], { allowFailure: true })).stdout });
    if (containers.code !== 0 || networks.code !== 0) error ||= new Error('Unable to verify own project cleanup: Docker inventory command failed');
    if (containers.stdout.trim() || networks.stdout.trim()) error ||= new Error('Own project cleanup incomplete');
    if (portChecks.some(p => !p.available)) error ||= new Error('Probe port still occupied after cleanup');
    save('result.json', { passed: !error, count: checks.length, artifacts: versions, error: error?.message,
      scope: 'Real isolated release JVMs using public broker/payload SPI; actual NATS, JetStream and Mesh TCP. Not a Boot autoconfiguration, WebSocket capacity or production SLI test.',
      semantics: 'NATS Core and Mesh are at-most-once with no offline replay; JetStream broadcast replays unconsumed durable backlog and ACKs after callbacks.' });
    console.log('Evidence: ' + out); process.exitCode = error ? 1 : 0;
  }
})();
