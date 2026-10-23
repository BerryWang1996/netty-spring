'use strict';
const assert = require('node:assert/strict');
const test = require('node:test');
const { distribution, percentile, memoryMiB, parseResource, summarizeResources, gcSummary } = require('./summarize-load.cjs');
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
  const all = Array.from({ length: 6 }, () => good);
  all.push({ ...good, crossedPhase: true });
  const result = summarizeResources(all, 60000, false);
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
  const result = gcSummary(text, Date.parse('2026-10-23T10:00:00Z'), 1000);
  assert.equal(result.uniqueGcIds, 2); assert.equal(result.pauseCount, 2); assert.equal(result.fullPauseCount, 1);
  assert.equal(result.pauseTotalMs, 8.5); assert.equal(result.measurement.pauseCount, 1); assert.equal(result.measurement.pauseTotalMs, 3.5);
});
