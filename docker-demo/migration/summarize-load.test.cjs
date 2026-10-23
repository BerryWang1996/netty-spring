'use strict';
const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { distribution, percentile, memoryMiB, parseResource, summarizeResources, gcSummary, analyze } = require('./summarize-load.cjs');
const measurementStart = Date.parse('2026-10-23T10:00:00Z');
const gcHeaders = '[2026-10-23T09:59:00.000+0000][0.001s][info][gc] Using G1\n'
  + '[2026-10-23T09:59:00.001+0000][0.002s][info][gc,init] Version: 17.0.20.1+1 (release)\n';
const sample = (cpu = '0.00%', at = '2026-10-23T10:00:01Z') => ({
  startedAt: '2026-10-23T10:00:00Z', at, phase: 'measure', endPhase: 'measure', crossedPhase: false,
  stats: { code: 0, timedOut: false, stdout: '\x1b[H' + JSON.stringify({ CPUPerc: cpu, MemUsage: '256MiB / 1.5GiB' }) },
  process: { code: 0, timedOut: false, stdout: 'VmRSS:\t131072 kB\nThreads:\t32\n' }
});
test('resource analysis distinguishes real zero CPU, unavailable CPU, RSS, and Docker memory', () => {
  const parsed = parseResource(sample());
  assert.equal(parsed.valid, true); assert.equal(parsed.cpuPercent, 0);
  assert.equal(parsed.rssMiB, 128); assert.equal(parsed.dockerMemoryMiB, 256); assert.equal(parsed.threads, 32);
  assert.equal(parseResource(sample('')).valid, false);
  assert.equal(parseResource({ ...sample(), crossedPhase: true }).valid, false);
  assert.equal(parseResource({ ...sample(), stats: { ...sample().stats, code: 1 } }).valid, false);
  assert.equal(parseResource({ ...sample(), process: { code: 0, stdout: 'Threads: 32\n' } }).valid, false);
  assert.equal(memoryMiB('1GiB'), 1024); assert.equal(memoryMiB('1GB'), 1e9 / 1024 ** 2); assert.equal(memoryMiB('n/a'), null);
});
test('round statistics and overflow do not replace missing values with zero', () => {
  assert.deepEqual(distribution([30, null, 10, 20]), { count: 3, mean: 20, median: 20, min: 10, max: 30 });
  assert.equal(distribution([null]).median, null);
  const bins = [50, 49, 1];
  assert.equal(percentile(bins, .5, .1), .1); assert.equal(percentile(bins, .99, .1), .2); assert.equal(percentile(bins, 1, .1), null);
});
test('resource evidence requires valid measurement samples, excluding stage crossings and functional probes', () => {
  const good = sample('175.50%');
  assert.equal(summarizeResources([good], 2000, false).resourceEvidence, false);
  assert.equal(summarizeResources([good], 60000, false).resourceEvidence, false);
  const all = Array.from({ length: 6 }, (_, i) => ({ ...good,
    startedAt: new Date(measurementStart + i * 10000).toISOString(), at: new Date(measurementStart + i * 10000 + 1000).toISOString() }));
  all.push({ ...good, crossedPhase: true });
  const result = summarizeResources(all, 60000, false, measurementStart);
  assert.equal(result.resourceEvidence, true); assert.equal(result.discardedCount, 1);
  assert.equal(result.stages.measure.cpuPercent.mean, 175.5);
});
test('GC analysis counts completed pause summaries once, excluding start/phase lines', () => {
  const text = [
    '[2026-10-23T10:00:00.000+0000][0.001s][info][gc,start] GC(1) Pause Young (Normal)',
    '[2026-10-23T10:00:00.010+0000][0.011s][info][gc,phases] GC(1) Other: 1.2ms',
    '[2026-10-23T10:00:00.020+0000][0.021s][info][gc          ] GC(1) Pause Young (Normal) 100M->10M(512M) 3.500ms',
    '[2026-10-23T10:00:02.020+0000][2.021s][info][gc] GC(2) Pause Full (System.gc()) 10M->9M(512M) 5.000ms'
  ].join('\n');
  const result = gcSummary(gcHeaders + text, measurementStart, 1000);
  assert.equal(result.validLog, true);
  assert.equal(result.uniqueGcIds, 2); assert.equal(result.pauseCount, 2); assert.equal(result.fullPauseCount, 1);
  assert.equal(result.pauseTotalMs, 8.5); assert.equal(result.measurement.pauseCount, 1); assert.equal(result.measurement.pauseTotalMs, 3.5);
});

// Complete on-disk fixtures exercise the same analyze() entry point used for
// retained evidence, with internally consistent counters and successful cleanup.
function fixture(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'netty-load-analysis-test-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const write = (name, value) => fs.writeFileSync(path.join(directory, name), typeof value === 'string' ? value : JSON.stringify(value));
  const change = (name, mutate) => { const value = JSON.parse(fs.readFileSync(path.join(directory, name), 'utf8')); mutate(value); write(name, value); };
  const settings = { connections: [8, 16], rounds: 1, workers: 1, measureMs: 60000, soakMs: 600000,
    warmupMs: 1000, drainMs: 30000, payloadBytes: 1024, checkStatusCounters: false };
  const runs = [];
  for (const variant of ['old', 'new']) for (const connections of settings.connections) runs.push({
    label: `${connections}-${variant}-1`, variant, connections, measureMs: settings.measureMs });
  for (const variant of ['old', 'new']) runs.push({ label: `soak-${variant}`, variant, connections: 16, measureMs: settings.soakMs });
  write('summary.json', { complete: true, settings, runs }); write('environment.json', { settings });
  const command = { code: 0, timedOut: false, stdout: '' };
  write('cleanup.json', { containers: command, networks: command });
  for (const run of runs) {
    fs.mkdirSync(path.join(directory, run.label));
    const input = { runId: run.label, connections: run.connections, measureMs: run.measureMs,
      workers: settings.workers, warmupMs: settings.warmupMs, drainMs: settings.drainMs, payloadBytes: settings.payloadBytes };
    const counts = { sent: run.connections, acked: run.connections, inWindow: run.connections,
      warmupSent: run.connections, warmupAcked: run.connections, duplicateOrCorrupt: 0, disconnected: 0, closeTimeouts: 0, abnormalCloses: 0 };
    const worker = { ...counts, index: 0, connected: run.connections, errors: [], measurementElapsedMs: run.measureMs,
      cpuMicros: { user: 1000, system: 500 }, ledger: Array.from({ length: run.connections }, () => ({ sent: 2, acked: 2 })) };
    write(run.label + '/input.json', input);
    write(run.label + '/result.json', { valid: true, settings: input, ...counts, workers: [worker],
      ackPerSecond: run.connections * 1000 / run.measureMs, histogram: [run.connections, 0], histogramBucketMs: .1,
      latencyMsUpperBound: { p50: .1, p95: .1, p99: .1 } });
    write(run.label + '/resources.json', Array.from({ length: run.measureMs / 5000 }, (_, i) => ({ ...sample('150.00%'),
      startedAt: new Date(measurementStart + i * 5000 + 1000).toISOString(), at: new Date(measurementStart + i * 5000 + 1500).toISOString() })));
    write(run.label + '/cleanup.json', command);
    write(run.label + '/recovery.json', [{ status: { status: 'UP', websocket: { activeSessionCount: 0 },
      handler: { rejectedCount: 0, executor: { queueSize: 0 }, availablePermits: 8, permitLimit: 8 },
      http: { rejectedCount: 0, writeFailureCount: 0 } } }]);
    write(run.label + '/client.stdout.jsonl', JSON.stringify({ at: new Date(measurementStart).toISOString(), phase: 'measure', connections: run.connections }) + '\n');
    write(run.label + '/gc.log', gcHeaders);
  }
  return { directory, write, change };
}
test('analyze accepts complete evidence with valid initialized GC logging and zero collections', t => {
  const f = fixture(t), a = analyze(f.directory);
  assert.equal(a.validation.allInstrumentedChecksPassed, true, JSON.stringify(a));
  assert.equal(a.runs[0].gc.pauseCount, 0); assert.equal(a.runs[0].gc.measurement.pauseCount, 0);
  assert.equal(a.runs.find(r => r.soak).resources.soakSegments.spanMs, 600000);
});
test('analyze rejects shortened soak and mismatched raw/input/manifest workload identity', async t => {
  const cases = {
    'raw shortened soak': f => f.change('soak-old/result.json', r => { r.settings.measureMs = 60000; r.ackPerSecond *= 10; }),
    'input and result shortened soak': f => { for (const file of ['input', 'result']) f.change(`soak-old/${file}.json`, r => { (file === 'result' ? r.settings : r).measureMs = 60000; }); },
    'manifest shortened soak': f => f.change('summary.json', s => { s.runs.find(r => r.label === 'soak-old').measureMs = 60000; }),
    'soak uses fewer connections': f => { for (const file of ['input', 'result']) f.change(`soak-old/${file}.json`, r => { (file === 'result' ? r.settings : r).connections = 8; }); },
    'raw worker count': f => f.change('soak-old/result.json', r => { r.settings.workers = 2; }),
    'raw run identity': f => f.change('soak-old/result.json', r => { r.settings.runId = '16-old-1'; }),
    'environment request differs': f => f.change('environment.json', e => { e.settings.soakMs = 60000; })
  };
  for (const [name, mutate] of Object.entries(cases)) await t.test(name, t => {
    const f = fixture(t); mutate(f);
    assert.equal(analyze(f.directory).validation.allInstrumentedChecksPassed, false);
  });
});
test('analyze rejects half-window resource evidence despite enough successful samples', async t => {
  for (const missing of ['tail', 'head']) await t.test(missing, t => {
    const f = fixture(t);
    f.change('soak-old/resources.json', samples => samples.forEach((s, i) => { if (missing === 'tail' ? i >= 60 : i < 60) s.stats.code = 1; }));
    const a = analyze(f.directory), r = a.runs.find(r => r.label === 'soak-old');
    assert.equal(r.resources.validMeasurementSamples, 60); assert.equal(r.resources.requiredMeasurementSamples, 60);
    assert.equal(r.resources.coverage.endpointsCovered, false);
    assert.equal(r.resources.soakSegments[missing === 'tail' ? 'last' : 'first'].samples, 0);
    assert.equal(a.validation.allInstrumentedChecksPassed, false);
  });
});
test('analyze rejects missing GC initialization and absent, duplicated or invalid measurement timestamps', async t => {
  const cases = {
    'empty GC': f => f.write('soak-old/gc.log', ''),
    'garbage GC': f => f.write('soak-old/gc.log', 'some unrelated log'),
    'GC without init': f => f.write('soak-old/gc.log', gcHeaders.split('\n')[0]),
    'empty client stdout': f => f.write('soak-old/client.stdout.jsonl', ''),
    'invalid measure UTC': f => f.write('soak-old/client.stdout.jsonl', JSON.stringify({ phase: 'measure', at: 'invalid', connections: 16 })),
    'duplicate measure UTC': f => { const event = JSON.stringify({ phase: 'measure', at: new Date(measurementStart).toISOString(), connections: 16 }); f.write('soak-old/client.stdout.jsonl', event + '\n' + event); }
  };
  for (const [name, mutate] of Object.entries(cases)) await t.test(name, t => {
    const f = fixture(t); mutate(f);
    const a = analyze(f.directory), r = a.runs.find(r => r.label === 'soak-old');
    assert.equal(a.validation.allInstrumentedChecksPassed, false);
    if (name.includes('GC')) assert.equal(r.gc.pauseCount, null);
    else { assert.equal(r.resources.coverage.start, null); assert.equal(r.gc.measurement, null); }
  });
});

test('analyze rejects a missing middle interval despite enough samples and valid endpoints', t => {
  const f = fixture(t);
  f.change('soak-old/resources.json', samples => samples.forEach((s, i) => {
    if (i >= 30 && i < 90) s.stats.code = 1;
  }));
  const a = analyze(f.directory), r = a.runs.find(r => r.label === 'soak-old');
  assert.equal(r.resources.validMeasurementSamples, 60);
  assert.equal(r.resources.coverage.endpointsCovered, true);
  assert.ok(r.resources.coverage.largestSampleGapMs > 300000);
  assert.equal(a.validation.allInstrumentedChecksPassed, false);
});
