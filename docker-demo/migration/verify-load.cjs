'use strict';

// Explicit opt-in acceptance runner. It only creates/removes its own Compose project.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const root = path.resolve(__dirname, '../..');

// Startup probes end before any measurement; no timer keeps issuing HTTP work.
async function waitForReadiness(probe, { timeoutMs = 60000, intervalMs = 500, now = Date.now, sleep = pause } = {}) {
  const start = now(), deadline = start + timeoutMs;
  const attempts = [];
  while (now() < deadline) {
    const commandTimeoutMs = Math.max(1, Math.min(3000, deadline - now()));
    const attempt = { startedAt: new Date(now()).toISOString(), commandTimeoutMs, code: null, timedOut: null, healthStatus: null, ready: false };
    try {
      const response = await probe(commandTimeoutMs);
      attempt.code = response.code;
      attempt.timedOut = response.timedOut;
      if (response.code === 0 && response.timedOut === false) {
        attempt.healthStatus = JSON.parse(response.stdout).status;
        attempt.ready = attempt.healthStatus === 'UP' && now() <= deadline;
      }
    } catch (error) { attempt.error = error.message; }
    const finish = now();
    attempt.finishedAt = new Date(finish).toISOString();
    attempts.push(attempt);
    if (attempt.ready) return { ready: true, timeoutMs, intervalMs, startedAt: new Date(start).toISOString(), finishedAt: attempt.finishedAt, elapsedMs: finish - start, attempts };
    const remaining = deadline - now();
    if (remaining <= 0) break;
    await sleep(Math.min(intervalMs, remaining));
  }
  const finish = now();
  return { ready: false, timeoutMs, intervalMs, startedAt: new Date(start).toISOString(), finishedAt: new Date(finish).toISOString(), elapsedMs: finish - start, attempts };
}

function verifyRecovery(recovery) {
  const recovered = recovery.at(-1).status;
  assert.equal(recovered.status, 'UP');
  assert.equal(recovered.websocket.activeSessionCount, 0, 'sessions did not close');
  assert.equal(recovered.handler.rejectedCount, 0, 'handler rejected load');
  assert.equal(recovered.http.rejectedCount, 0, 'HTTP/handshake rejection');
  assert.equal(recovered.http.writeFailureCount, 0, 'response write failure');
  assert.equal(recovered.handler.executor.queueSize, 0, 'handler queue did not drain');
  // The management request itself holds one handler permit while taking this snapshot.
  assert.ok(recovered.handler.availablePermits >= recovered.handler.permitLimit - 1, 'handler permits leaked');
  return recovered;
}

function main(args) {
if (!args.includes('--run')) {
  console.log('Usage: node docker-demo/migration/verify-load.cjs --run --old-image IMAGE --new-image IMAGE [--connections 1024,8192] [--seconds 60] [--rounds 3] [--soak-seconds 600] [--workers 4]');
  process.exit(0);
}
function option(name, fallback) { const i = args.indexOf('--' + name); return i < 0 ? fallback : args[i + 1]; }
function integer(name, fallback, min, max) {
  const value = Number(option(name, fallback)); assert.ok(Number.isInteger(value) && value >= min && value <= max, name); return value;
}
const settings = {
  connections: option('connections', '1024,8192').split(',').map(Number), workers: integer('workers', 4, 1, 16),
  measureMs: integer('seconds', 60, 1, 3600) * 1000, warmupMs: integer('warmup-seconds', 15, 1, 300) * 1000,
  rounds: integer('rounds', 3, 1, 10), soakMs: integer('soak-seconds', 600, 0, 7200) * 1000,
  recoveryMs: integer('recovery-seconds', 20, 1, 120) * 1000, payloadBytes: 1024, drainMs: 30000,
  appCpus: 2, appMemoryMiB: 1536, heapMiB: 512, directMiB: 256, clientCpus: 4, clientImage: 'node:22-alpine',
  checkStatusCounters: args.includes('--check-status-counters'), readinessProtocol: 'bounded-health-readiness-v1'
};
settings.connections.forEach(n => assert.ok(Number.isInteger(n) && n >= settings.workers && n <= 25000, 'connection range'));
const versions = {
  old: { version: option('old-version', '1.10.1'), image: option('old-image', 'netty-spring-1101-check:1.10.1') },
  new: { version: option('new-version', '2.0.0-SNAPSHOT'), image: option('new-image', 'netty-spring-200-acceptance:2.0.0-SNAPSHOT') }
};
Object.values(versions).forEach(value => assert.match(value.version, /^[0-9A-Za-z.-]+$/));
const runId = new Date().toISOString().replace(/[:.]/g, '-');
const project = 'netty-spring-load-' + runId.toLowerCase();
const output = path.join(root, '.m2', 'load-acceptance-' + runId);
fs.mkdirSync(output, { recursive: true });
const clientSource = path.join(output, 'client-source');
fs.mkdirSync(path.join(clientSource, 'node_modules'), { recursive: true });
fs.copyFileSync(path.join(__dirname, 'load-client.cjs'), path.join(clientSource, 'load-client.cjs'));
fs.cpSync(path.join(root, 'docker-demo/node_modules/ws'), path.join(clientSource, 'node_modules/ws'), { recursive: true });
const composeFile = path.join(output, 'compose.json');
const eventsFile = path.join(output, 'events.jsonl');
const runs = [];
const save = (name, value) => fs.writeFileSync(path.join(output, name), JSON.stringify(value, null, 2));
function record(value) { fs.appendFileSync(eventsFile, JSON.stringify({ at: new Date().toISOString(), ...value }) + '\n'); }
function progress(message) { console.log(message); record({ progress: message }); }
function docker(command, { timeout = 60000, allowFailure = false, onLine } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn('docker', command, { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '', stderr = '', buffer = '', timedOut = false;
    const timer = setTimeout(() => { timedOut = true; child.kill(); }, timeout);
    child.stdout.on('data', chunk => {
      stdout += chunk; buffer += chunk;
      let split;
      while ((split = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, split); buffer = buffer.slice(split + 1); if (onLine) onLine(line);
      }
    });
    child.stderr.on('data', chunk => stderr += chunk);
    child.on('error', error => { clearTimeout(timer); reject(error); });
    child.on('close', code => {
      clearTimeout(timer);
      const result = { command, code, timedOut, stdout, stderr };
      if ((code !== 0 || timedOut) && !allowFailure) reject(Object.assign(new Error(`docker ${command[0]} failed: ${stderr.slice(-1000)}`), { result }));
      else resolve(result);
    });
  });
}
const dc = (command, options) => docker(['compose', '-p', project, '-f', composeFile, ...command], options);
async function appStatus() {
  const response = await dc(['exec', '-T', 'app', 'wget', '-qO-', 'http://127.0.0.1:8082/netty/status']);
  return JSON.parse(response.stdout);
}
function monitor(container) {
  const samples = [];
  let stopped = false, pending, phase = 'starting';
  async function sample() {
    if (stopped || pending) return;
    pending = (async () => {
      const sampledPhase = phase;
      const startedAt = new Date().toISOString();
      const [stats, process] = await Promise.all([
        docker(['stats', '--no-stream', '--format', '{{json .}}', container], { allowFailure: true }),
        docker(['exec', container, 'cat', '/proc/1/status'], { allowFailure: true })
      ]);
      samples.push({ startedAt, at: new Date().toISOString(), phase: sampledPhase, endPhase: phase, crossedPhase: phase !== sampledPhase, stats, process });
    })().finally(() => pending = null);
    await pending;
  }
  const interval = setInterval(sample, 5000); sample();
  return { setPhase(value) { phase = value; }, async stop() { stopped = true; clearInterval(interval); if (pending) await pending; return samples; } };
}
function verifyResourceEvidence(samples, measureMs, measurementStart) {
  const valid = samples.filter(sample => {
    if (sample.phase !== 'measure' || sample.endPhase !== 'measure' || sample.crossedPhase !== false
        || sample.stats.code !== 0 || sample.stats.timedOut !== false
        || sample.process.code !== 0 || sample.process.timedOut !== false) return false;
    try {
      const stats = JSON.parse(sample.stats.stdout);
      const cpuText = /^([\d.]+)%$/.exec(String(stats.CPUPerc || '').trim());
      const cpu = cpuText ? Number(cpuText[1]) : NaN;
      const rss = sample.process.stdout.match(/^VmRSS:\s+(\d+)\s+kB/m);
      const threads = sample.process.stdout.match(/^Threads:\s+(\d+)/m);
      const start = Date.parse(sample.startedAt), end = Date.parse(sample.at);
      return Number.isFinite(cpu) && cpu >= 0 && rss && Number(rss[1]) > 0 && threads && Number(threads[1]) > 0
        && Number.isFinite(start) && Number.isFinite(end) && end >= start;
    } catch { return false; }
  }).sort((a, b) => Date.parse(a.startedAt) - Date.parse(b.startedAt));
  // Sub-10-second runs are functional probes and cannot establish resource behavior.
  const required = measureMs >= 10000 ? Math.max(1, Math.floor(measureMs / 10000)) : 0;
  const coverage = valid.length && Number.isFinite(measurementStart) ? {
    startLagMs: Date.parse(valid[0].startedAt) - measurementStart,
    endLagMs: measurementStart + measureMs - Date.parse(valid.at(-1).at),
    largestGapMs: Math.max(0, ...valid.slice(1).map((s, i) => Date.parse(s.startedAt) - Date.parse(valid[i].at)))
  } : null;
  const covered = coverage && coverage.startLagMs >= -5000 && coverage.startLagMs <= 15000
    && coverage.endLagMs >= -5000 && coverage.endLagMs <= 15000 && coverage.largestGapMs <= 15000;
  return { validMeasurementSamples: valid.length, discardedSamples: samples.length - valid.length,
    requiredMeasurementSamples: required, coverage,
    resourceEvidence: required > 0 && valid.length >= required && Boolean(covered) };
}
async function execute(label, variant, connections, measureMs) {
  const directory = path.join(output, label); fs.mkdirSync(directory);
  const version = versions[variant];
  const config = [
    'spring.main.banner-mode=off', 'logging.level.root=OFF', 'server.netty.port=8082',
    'server.netty.http.gzip.enable=false', 'server.netty.http.ssl.enable=false', 'server.netty.management.enable=true',
    'server.netty.websocket.core-pool-size=4', 'server.netty.websocket.max-pool-size=4', 'server.netty.websocket.queue-capacity=16384',
    'server.netty.websocket.handler-core-pool-size=4', 'server.netty.websocket.handler-max-pool-size=4',
    'server.netty.websocket.handler-queue-capacity=16384', 'server.netty.websocket.handler-permit-limit=16388',
    `server.netty.websocket.max-connections=${connections}`, 'server.netty.websocket.heartbeat-interval-seconds=0',
    'server.netty.websocket.heartbeat-timeout-seconds=0', 'server.netty.websocket.crypto.enable=false'
  ].join('\n');
  fs.writeFileSync(path.join(directory, 'application.properties'), config);
  const input = { runId: label, connections, workers: settings.workers, warmupMs: settings.warmupMs, measureMs,
    drainMs: settings.drainMs, payloadBytes: settings.payloadBytes, endpoint: 'ws://app:8082', output: '/evidence/result.json' };
  fs.writeFileSync(path.join(directory, 'input.json'), JSON.stringify(input));
  const java = [
    'exec java -Xms512m -Xmx512m -XX:MaxDirectMemorySize=256m -XX:ActiveProcessorCount=2 -XX:+UseG1GC',
    '-Xlog:gc*:file=/evidence/gc.log:time,uptime,level,tags -Dio.netty.eventLoopThreads=4',
    `-jar /app/target/demo-netty-web-spring-boot-starter-${version.version}.jar`,
    '--spring.config.additional-location=file:/evidence/application.properties'
  ].join(' ');
  const compose = { services: {
    app: { image: version.imageId, pull_policy: 'never', cpus: settings.appCpus, mem_limit: '1536m', memswap_limit: '1536m', pids_limit: 256,
      environment: { SPRING_PROFILES_ACTIVE: 'default' }, ulimits: { nofile: { soft: 65536, hard: 65536 } },
      volumes: [{ type: 'bind', source: directory, target: '/evidence' }],
      entrypoint: ['sh', '-ec'], command: [
        `jar=/app/target/demo-netty-web-spring-boot-starter-${version.version}.jar; ` +
        `actual=$$(unzip -p "$$jar" META-INF/maven/io.github.berrywang1996/demo-netty-web-spring-boot-starter/pom.properties | sed -n 's/^version=//p' | tr -d '\\r'); ` +
        `test "$$actual" = '${version.version}'; printf '%s  %s\\n' '${version.jarSha256}' "$$jar" | sha256sum -c -; java -version; ${java}`
      ],
      // /netty/health uses the same handler permit pool as application traffic.
      // Disable even an inherited image healthcheck so recovery has only its own status request.
      healthcheck: { disable: true }
    },
    client: { image: settings.clientImageId, pull_policy: 'never', cpus: settings.clientCpus, mem_limit: '1536m', memswap_limit: '1536m', pids_limit: 128,
      ulimits: { nofile: { soft: 65536, hard: 65536 } }, working_dir: '/probe',
      volumes: [{ type: 'bind', source: clientSource, target: '/probe', read_only: true }, { type: 'bind', source: directory, target: '/evidence' }],
      entrypoint: ['node'], command: ['/probe/load-client.cjs', '/evidence/input.json'] }
  } };
  fs.writeFileSync(composeFile, JSON.stringify(compose)); fs.writeFileSync(path.join(directory, 'compose.json'), JSON.stringify(compose));
  let sample, measurementStart;
  const clientName = project + '-client';
  progress(`START ${label}: ${connections} connections, ${measureMs / 1000}s`);
  try {
    await dc(['up', '-d', 'app'], { timeout: 75000 });
    const container = (await dc(['ps', '-q', 'app'])).stdout.trim();
    assert.ok(container, 'application container missing');
    const healthcheck = await docker(['inspect', '--format', '{{json .Config.Healthcheck}}', container]);
    const readiness = { protocol: settings.readinessProtocol, requestTimeoutSeconds: 2,
      healthcheckInspection: { containerId: container, inspectedAt: new Date().toISOString(), config: JSON.parse(healthcheck.stdout) } };
    fs.writeFileSync(path.join(directory, 'readiness.json'), JSON.stringify(readiness));
    assert.deepEqual(readiness.healthcheckInspection.config?.Test, ['NONE'], 'periodic image/Compose healthcheck is not disabled');
    Object.assign(readiness, await waitForReadiness(timeout => dc(
      ['exec', '-T', 'app', 'wget', '-T', '2', '-qO-', 'http://127.0.0.1:8082/netty/health'],
      { timeout, allowFailure: true })));
    fs.writeFileSync(path.join(directory, 'readiness.json'), JSON.stringify(readiness));
    assert.equal(readiness.ready, true, 'application did not become UP within the bounded startup readiness window');
    sample = monitor(container);
    fs.writeFileSync(path.join(directory, 'status-before.json'), JSON.stringify(await appStatus()));
    const client = await dc(['run', '--name', clientName, '--rm', '--no-deps', 'client'], {
      timeout: settings.warmupMs + measureMs + settings.drainMs * 3 + 180000,
      onLine(line) {
        try {
          const value = JSON.parse(line);
          if (value.phase === 'measure') measurementStart = Date.parse(value.at);
          if (value.phase) { sample.setPhase(value.phase); progress(`${label}: ${value.phase}`); }
        }
        catch {}
      }
    });
    fs.writeFileSync(path.join(directory, 'client.stdout.jsonl'), client.stdout);
    fs.writeFileSync(path.join(directory, 'client.stderr.log'), client.stderr);
    const result = JSON.parse(fs.readFileSync(path.join(directory, 'result.json'), 'utf8'));
    assert.equal(result.valid, true); assert.equal(result.settings.connections, connections);
    assert.equal(result.workers.reduce((sum, w) => sum + w.connected, 0), connections);
    sample.setPhase('recovery');
    const recovery = [];
    for (let i = 0; i < settings.recoveryMs; i += 5000) { recovery.push({ at: new Date().toISOString(), status: await appStatus() }); await pause(5000); }
    fs.writeFileSync(path.join(directory, 'recovery.json'), JSON.stringify(recovery));
    const recovered = verifyRecovery(recovery);
    if (variant === 'new' && settings.checkStatusCounters) {
      const counters = recovered.websocket.eventCounters;
      assert.equal(counters.handshakeTotal, connections);
      assert.equal(counters.handshakeSuccess, connections);
      assert.equal(counters.totalCloses, connections);
      assert.equal(counters.messagesSent, result.sent + result.warmupSent);
      assert.equal(counters.messagesReceived, result.sent + result.warmupSent + connections, 'received includes the close control frames');
    }
    const samples = await sample.stop(); sample = null;
    fs.writeFileSync(path.join(directory, 'resources.json'), JSON.stringify(samples));
    const resourceEvidence = verifyResourceEvidence(samples, measureMs, measurementStart);
    fs.writeFileSync(path.join(directory, 'resource-evidence.json'), JSON.stringify(resourceEvidence));
    assert.ok(resourceEvidence.requiredMeasurementSamples === 0 || resourceEvidence.resourceEvidence,
      'insufficient valid measurement resource samples or time coverage');
    runs.push({ label, variant, connections, measureMs, valid: true, resourceEvidence, sent: result.sent, acked: result.acked,
      ackPerSecond: result.ackPerSecond, latencyMsUpperBound: result.latencyMsUpperBound, directory });
    save('runs.json', runs); progress(`PASS ${label}: ${result.ackPerSecond.toFixed(1)} ACK/s, ${result.acked} checked replies`);
  } catch (error) {
    fs.writeFileSync(path.join(directory, 'failure.json'), JSON.stringify({ error: error.stack, result: error.result }, null, 2)); throw error;
  } finally {
    if (sample) fs.writeFileSync(path.join(directory, 'resources.json'), JSON.stringify(await sample.stop()));
    const clientOwner = await docker(['inspect', '--format', '{{ index .Config.Labels "com.docker.compose.project" }}', clientName], { allowFailure: true });
    if (clientOwner.code === 0) {
      assert.equal(clientOwner.stdout.trim(), project, 'refuse to remove a client owned by another project');
      await docker(['rm', '-f', clientName]);
    }
    const logs = await dc(['logs', '--no-color', 'app'], { allowFailure: true }); fs.writeFileSync(path.join(directory, 'app.log'), logs.stdout + logs.stderr);
    const down = await dc(['down', '--remove-orphans', '--timeout', '30'], { timeout: 45000, allowFailure: true }); fs.writeFileSync(path.join(directory, 'cleanup.json'), JSON.stringify(down));
  }
}
async function run() {
  const environment = { runId, project, settings, versions, host: { os: os.platform(), cpuCount: os.cpus().length, node: process.version },
    docker: JSON.parse((await docker(['info', '--format', '{{json .}}'])).stdout) };
  // Keep environment evidence useful without copying host/container credentials or full inspect environments.
  environment.docker = { NCPU: environment.docker.NCPU, MemTotal: environment.docker.MemTotal, ServerVersion: environment.docker.ServerVersion, OSType: environment.docker.OSType };
  settings.clientImageId = (await docker(['image', 'inspect', '--format', '{{.Id}}', settings.clientImage])).stdout.trim();
  for (const [role, version] of Object.entries(versions)) {
    version.imageId = (await docker(['image', 'inspect', '--format', '{{.Id}}', version.image])).stdout.trim();
    version.java = (await docker(['run', '--rm', '--entrypoint', 'java', version.imageId, '-version'])).stderr;
    version.jarSha256 = (await docker(['run', '--rm', '--entrypoint', 'sha256sum', version.imageId,
      `/app/target/demo-netty-web-spring-boot-starter-${version.version}.jar`])).stdout.trim().split(/\s+/)[0];
    assert.match(version.jarSha256, /^[a-f0-9]{64}$/);
    const expected = option(role + '-jar-sha256');
    if (expected) assert.equal(version.jarSha256, expected, role + ' fat jar pin');
  }
  assert.equal(versions.old.java, versions.new.java, 'Compare under identical JRE');
  save('environment.json', environment);
  for (const filename of ['load-client.cjs', 'verify-load.cjs']) fs.copyFileSync(path.join(__dirname, filename), path.join(output, filename));
  try {
    for (const connections of settings.connections) for (let round = 1; round <= settings.rounds; round++) {
      for (const variant of (round % 2 ? ['old', 'new'] : ['new', 'old'])) await execute(`${connections}-${variant}-${round}`, variant, connections, settings.measureMs);
    }
    if (settings.soakMs) for (const variant of ['old', 'new']) await execute(`soak-${variant}`, variant, Math.max(...settings.connections), settings.soakMs);
  } finally {
    if (fs.existsSync(composeFile)) await dc(['down', '--remove-orphans', '--timeout', '30'], { timeout: 45000, allowFailure: true });
    const containers = await docker(['ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project]);
    const networks = await docker(['network', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + project]);
    save('cleanup.json', { containers, networks });
    assert.equal(containers.stdout.trim(), '', 'test containers remain'); assert.equal(networks.stdout.trim(), '', 'test networks remain');
    progress(`Evidence: ${output}`);
  }
  save('summary.json', { complete: true, runId, project, settings, runs });
}
run().catch(error => {
  save('failure.json', { error: error.stack, result: error.result });
  save('summary.json', { complete: false, runId, project, settings, runs });
  console.error(error.stack); process.exitCode = 1;
});
}

module.exports = { waitForReadiness, verifyRecovery };
if (require.main === module) main(process.argv.slice(2));
