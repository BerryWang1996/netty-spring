'use strict';

// Offline analysis only: reads an existing verify-load evidence directory and writes
// analysis.json / analysis.md there. Does not start processes, containers or load.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const ANSI = /\x1b\[[0-?]*[ -/]*[@-~]/g;
const finite = value => typeof value === 'number' && Number.isFinite(value);
const mean = values => values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
const median = values => {
  const sorted = values.filter(finite).sort((a, b) => a - b);
  const n = sorted.length;
  return n ? (sorted[Math.floor(n / 2)] + sorted[Math.floor((n - 1) / 2)]) / 2 : null;
};
function distribution(values) {
  const valid = values.filter(finite);
  return { count: valid.length, mean: mean(valid), median: median(valid), min: valid.length ? Math.min(...valid) : null, max: valid.length ? Math.max(...valid) : null };
}
function percentile(histogram, quantile, bucketMs) {
  const count = histogram.reduce((a, b) => a + b, 0);
  if (!count) return null;
  let seen = 0;
  for (let i = 0; i < histogram.length; i++) {
    seen += histogram[i];
    if (seen >= Math.ceil(count * quantile)) return i === histogram.length - 1 ? null : (i + 1) * bucketMs;
  }
  return null;
}
function memoryMiB(value) {
  const match = /^\s*([\d.]+)\s*(B|kB|KB|MB|GB|TB|KiB|MiB|GiB|TiB)\s*$/.exec(value || '');
  if (!match) return null;
  const units = { B: 1, kB: 1e3, KB: 1e3, MB: 1e6, GB: 1e9, TB: 1e12, KiB: 1024, MiB: 1024 ** 2, GiB: 1024 ** 3, TiB: 1024 ** 4 };
  const result = Number(match[1]) * units[match[2]] / 1024 ** 2;
  return finite(result) ? result : null;
}
function parseResource(sample) {
  const reasons = [];
  if (sample.stats?.code !== 0 || sample.stats?.timedOut) reasons.push('docker stats failed');
  if (sample.process?.code !== 0 || sample.process?.timedOut) reasons.push('/proc sample failed');
  if (sample.crossedPhase !== false || sample.endPhase !== sample.phase) reasons.push('crossed or unknown phase boundary');
  let stats;
  try { stats = JSON.parse(String(sample.stats?.stdout || '').replace(ANSI, '').trim()); }
  catch { reasons.push('invalid Docker stats JSON'); }
  const cpuText = /^([\d.]+)%$/.exec(String(stats?.CPUPerc || '').trim());
  const cpuPercent = cpuText ? Number(cpuText[1]) : null;
  const proc = String(sample.process?.stdout || '');
  const rss = /^VmRSS:\s+(\d+)\s+kB/m.exec(proc);
  const threads = /^Threads:\s+(\d+)/m.exec(proc);
  const rssMiB = rss ? Number(rss[1]) / 1024 : null;
  const threadCount = threads ? Number(threads[1]) : null;
  if (!finite(cpuPercent) || cpuPercent < 0) reasons.push('invalid CPU');
  if (!finite(rssMiB) || rssMiB <= 0) reasons.push('missing or invalid RSS');
  if (!Number.isInteger(threadCount) || threadCount <= 0) reasons.push('missing or invalid thread count');
  const started = Date.parse(sample.startedAt), ended = Date.parse(sample.at);
  if (!finite(started) || !finite(ended) || ended < started) reasons.push('invalid sample timestamps');
  return { valid: reasons.length === 0, reasons, phase: sample.phase || 'unknown', startedAt: sample.startedAt || null,
    at: sample.at || null, midpointMs: finite(started) && finite(ended) ? (started + ended) / 2 : null,
    cpuPercent, rssMiB, threads: threadCount, dockerMemoryMiB: memoryMiB(String(stats?.MemUsage || '').split('/')[0]) };
}
function resourceSummary(samples) {
  return { samples: samples.length, cpuPercent: distribution(samples.map(s => s.cpuPercent)), rssMiB: distribution(samples.map(s => s.rssMiB)),
    threads: distribution(samples.map(s => s.threads)), dockerMemoryMiB: distribution(samples.map(s => s.dockerMemoryMiB)) };
}
function summarizeResources(raw, measureMs, soak) {
  const normalized = raw.map(parseResource), valid = normalized.filter(s => s.valid);
  const stages = {};
  for (const stage of new Set(normalized.map(s => s.phase))) {
    const all = normalized.filter(s => s.phase === stage);
    stages[stage] = { ...resourceSummary(all.filter(s => s.valid)), discarded: all.filter(s => !s.valid).length };
  }
  const measured = valid.filter(s => s.phase === 'measure').sort((a, b) => a.midpointMs - b.midpointMs);
  const required = measureMs >= 10000 ? Math.max(1, Math.floor(measureMs / 10000)) : 0;
  const result = { rawCount: raw.length, validCount: valid.length, discardedCount: raw.length - valid.length,
    requiredMeasurementSamples: required, validMeasurementSamples: measured.length,
    resourceEvidence: required > 0 && measured.length >= required, stages, normalized };
  if (soak && measured.length) {
    const begin = measured[0].midpointMs, end = measured.at(-1).midpointMs, third = (end - begin) / 3;
    const first = measured.filter(s => s.midpointMs <= begin + third);
    const last = measured.filter(s => s.midpointMs >= end - third);
    result.soakSegments = { definition: 'First and last third of complete measurement sample midpoint timestamps; unweighted snapshots.',
      first: resourceSummary(first), last: resourceSummary(last), spanMs: end - begin,
      rssMeanDeltaMiB: mean(last.map(s => s.rssMiB)) - mean(first.map(s => s.rssMiB)),
      cpuMeanDeltaPercentagePoints: mean(last.map(s => s.cpuPercent)) - mean(first.map(s => s.cpuPercent)) };
  }
  return result;
}
function gcSummary(text, measurementStart, measureMs) {
  const ids = new Set(), pauses = [];
  for (const line of text.split(/\r?\n/)) {
    const id = /\bGC\((\d+)\)/.exec(line); if (id) ids.add(Number(id[1]));
    const match = /^\[([^\]]+)\]\[([\d.]+)s\]\[\w+\]\[gc\s*\]\s+GC\((\d+)\)\s+(Pause .+?)\s+([\d.]+)ms\s*$/.exec(line);
    if (match) pauses.push({ at: match[1], uptimeSeconds: Number(match[2]), id: Number(match[3]), description: match[4], durationMs: Number(match[5]) });
  }
  const summarize = rows => ({ pauseCount: rows.length, pauseTotalMs: rows.reduce((sum, row) => sum + row.durationMs, 0),
    pauseMs: distribution(rows.map(p => p.durationMs)), fullPauseCount: rows.filter(p => /^Pause Full\b/.test(p.description)).length });
  return { scope: 'Whole JVM lifetime, including startup and recovery. Measurement subset uses client phase UTC timestamps.',
    uniqueGcIds: ids.size, ...summarize(pauses),
    measurement: finite(measurementStart) ? summarize(pauses.filter(p => {
      const t = Date.parse(p.at); return t >= measurementStart && t < measurementStart + measureMs;
    })) : null, pauses };
}
function analyze(evidence) {
  const issues = [], inputs = {};
  function read(relative, fallback = null, required = true) {
    const filename = path.join(evidence, relative);
    try {
      const text = fs.readFileSync(filename, 'utf8'); inputs[relative] = { bytes: Buffer.byteLength(text) };
      return relative.endsWith('.json') ? JSON.parse(text) : text;
    } catch (error) { if (required) issues.push(relative + ': ' + error.message); return fallback; }
  }
  const summary = read('summary.json', {}), environment = read('environment.json', {}), cleanup = read('cleanup.json', {});
  const manifest = summary.runs || read('runs.json', []);
  const entries = new Map(manifest.map(run => [run.label, run]));
  // Include unfinished/failed run directories omitted from the success manifest.
  for (const dir of fs.readdirSync(evidence, { withFileTypes: true }).filter(d => d.isDirectory())) {
    const inputFile = path.join(evidence, dir.name, 'input.json');
    if (fs.existsSync(inputFile) && !entries.has(dir.name)) {
      const input = read(dir.name + '/input.json', {});
      entries.set(dir.name, { label: dir.name, variant: /(?:^|-)old(?:-|$)/.test(dir.name) ? 'old' : 'new', connections: input.connections, measureMs: input.measureMs });
    }
  }
  const settings = summary.settings || environment.settings || {};
  const runs = [];
  for (const entry of entries.values()) {
    assert.equal(path.basename(entry.label), entry.label, 'Run label must be a single directory name');
    const label = entry.label, result = read(label + '/result.json'), rawResources = read(label + '/resources.json', []);
    const runCleanup = read(label + '/cleanup.json'), recovery = read(label + '/recovery.json', []);
    const stdout = read(label + '/client.stdout.jsonl', ''), gc = read(label + '/gc.log');
    const checks = [], check = (name, passed) => checks.push({ name, passed: Boolean(passed) });
    const measureMs = result?.settings?.measureMs || entry.measureMs;
    const row = { label, variant: entry.variant, connections: entry.connections, measureMs, soak: label.startsWith('soak-'), checks };
    row.resources = summarizeResources(rawResources, measureMs, row.soak);
    if (!result) { check('raw client result present', false); runs.push(row); continue; }
    check('client result valid', result.valid === true);
    const counters = ['sent', 'acked', 'inWindow', 'warmupSent', 'warmupAcked', 'duplicateOrCorrupt', 'disconnected', 'closeTimeouts', 'abnormalCloses'];
    row.counts = Object.fromEntries(counters.map(key => [key, Number.isInteger(result[key]) ? result[key] : null]));
    check('all counters present and nonnegative', counters.every(key => Number.isInteger(result[key]) && result[key] >= 0));
    check('all sent messages acknowledged', result.sent === result.acked && result.sent > 0 && result.warmupSent === result.warmupAcked);
    check('zero corruption, disconnections and close failures', ['duplicateOrCorrupt', 'disconnected', 'closeTimeouts', 'abnormalCloses'].every(k => result[k] === 0));
    row.counts.tailAcks = result.acked - result.inWindow;
    check('ACK window is bounded by sent/ACK counts', result.inWindow >= 0 && result.inWindow <= result.acked);
    row.ackPerSecond = result.inWindow * 1000 / measureMs;
    check('reported throughput matches raw counts', finite(result.ackPerSecond) && Math.abs(row.ackPerSecond - result.ackPerSecond) < 1e-8);
    const hist = result.histogram;
    if (Array.isArray(hist) && hist.length >= 2 && hist.every(n => Number.isInteger(n) && n >= 0) && finite(result.histogramBucketMs)) {
      check('histogram covers every measured ACK', hist.reduce((a, b) => a + b, 0) === result.acked);
      row.latencyMsUpperBound = { p50: percentile(hist, .5, result.histogramBucketMs), p95: percentile(hist, .95, result.histogramBucketMs),
        p99: percentile(hist, .99, result.histogramBucketMs), overflow: hist.at(-1) };
      check('reported quantiles match raw histogram', ['p50', 'p95', 'p99'].every(k => {
        const a = row.latencyMsUpperBound[k], b = result.latencyMsUpperBound?.[k];
        return a === null ? b === null : finite(b) && Math.abs(a - b) < 1e-8;
      }));
    } else check('valid latency histogram present', false);
    const workers = result.workers || [];
    check('worker counters sum to reported totals', counters.every(key => workers.every(w => Number.isInteger(w[key]))
      && workers.reduce((sum, w) => sum + w[key], 0) === result[key]));
    check('all configured connections represented', workers.reduce((sum, w) => sum + w.connected, 0) === entry.connections);
    check('all configured workers represented once', workers.length === result.settings.workers && new Set(workers.map(w => w.index)).size === workers.length);
    check('all worker errors empty and sequence ledgers complete', workers.every(w => Array.isArray(w.errors) && !w.errors.length && Array.isArray(w.ledger)
      && w.ledger.length === w.connected && w.ledger.every(c => c.sent === c.acked)
      && w.ledger.reduce((sum, c) => sum + c.sent, 0) === w.sent + w.warmupSent));
    row.workerCpu = { definition: 'Worker thread user+system CPU / each worker actual observed measurement elapsed; 100%=one core. Excludes main thread and is not whole-client-container CPU.',
      workers: workers.map(w => ({ index: w.index, elapsedMs: w.measurementElapsedMs ?? null,
        overshootMs: finite(w.measurementElapsedMs) ? w.measurementElapsedMs - measureMs : null,
        cpuMicros: w.cpuMicros, percent: finite(w.measurementElapsedMs) && w.measurementElapsedMs > 0 && finite(w.cpuMicros?.user) && finite(w.cpuMicros?.system)
          ? (w.cpuMicros.user + w.cpuMicros.system) / (w.measurementElapsedMs * 1000) * 100 : null,
        drainElapsedMs: w.drainElapsedMs ?? null, drainCpuMicros: w.drainCpuMicros ?? null, eventLoopMs: w.eventLoopMs })) };
    row.workerCpu.totalPercent = row.workerCpu.workers.every(w => finite(w.percent)) ? row.workerCpu.workers.reduce((sum, w) => sum + w.percent, 0) : null;
    check('worker CPU denominators available', row.workerCpu.workers.every(w => finite(w.percent)));
    let phaseStart = null;
    for (const line of stdout.split(/\r?\n/)) {
      try { const e = JSON.parse(line); if (e.phase === 'measure') { phaseStart = Date.parse(e.at); break; } } catch {}
    }
    row.gc = gc === null ? null : gcSummary(gc, phaseStart, measureMs);
    check('GC log available', gc !== null);
    row.recovery = recovery.at(-1)?.status || null;
    check('recovery status and queues healthy', row.recovery?.status === 'UP' && row.recovery?.websocket?.activeSessionCount === 0
      && row.recovery?.handler?.rejectedCount === 0 && row.recovery?.http?.rejectedCount === 0 && row.recovery?.http?.writeFailureCount === 0
      && row.recovery?.handler?.executor?.queueSize === 0 && row.recovery?.handler?.availablePermits >= row.recovery?.handler?.permitLimit - 1);
    if (entry.variant === 'new' && settings.checkStatusCounters) {
      const c = row.recovery?.websocket?.eventCounters;
      check('new release status counters match traffic and close frames', c?.handshakeTotal === entry.connections && c?.handshakeSuccess === entry.connections
        && c?.totalCloses === entry.connections && c?.messagesSent === result.sent + result.warmupSent
        && c?.messagesReceived === result.sent + result.warmupSent + entry.connections);
    }
    check('run cleanup command succeeded', runCleanup?.code === 0 && runCleanup?.timedOut === false);
    check('measurement resource evidence sufficient', row.resources.resourceEvidence);
    runs.push(row);
  }
  const comparisons = [];
  for (const connections of new Set(runs.filter(r => !r.soak).map(r => r.connections))) {
    const group = { connections };
    for (const variant of ['old', 'new']) {
      const matching = runs.filter(r => !r.soak && r.connections === connections && r.variant === variant);
      group[variant] = { rounds: matching.length, ackPerSecond: distribution(matching.map(r => r.ackPerSecond)),
        p95MsUpperBound: distribution(matching.map(r => r.latencyMsUpperBound?.p95)),
        p99MsUpperBound: distribution(matching.map(r => r.latencyMsUpperBound?.p99)) };
      // An overflow/missing round must not silently disappear from the median:
      // excluding the slowest round would make an incomplete comparison optimistic.
      for (const key of ['ackPerSecond', 'p95MsUpperBound', 'p99MsUpperBound']) {
        if (group[variant][key].count !== matching.length) group[variant][key].median = null;
      }
    }
    group.newVsOldPercent = Object.fromEntries(['ackPerSecond', 'p95MsUpperBound', 'p99MsUpperBound'].map(key => {
      const old = group.old[key].median, next = group.new[key].median;
      return [key, finite(old) && old !== 0 && finite(next) ? (next / old - 1) * 100 : null];
    }));
    comparisons.push(group);
  }
  const cleanupVerified = ['containers', 'networks'].every(key => cleanup[key]?.code === 0 && cleanup[key]?.timedOut === false && cleanup[key]?.stdout.trim() === '');
  const expectedLabels = [];
  for (const n of settings.connections || []) for (let round = 1; round <= (settings.rounds || 0); round++) for (const v of ['old', 'new']) expectedLabels.push(`${n}-${v}-${round}`);
  if (settings.soakMs) expectedLabels.push('soak-old', 'soak-new');
  const matrixComplete = expectedLabels.length > 0 && expectedLabels.every(label => runs.some(r => r.label === label)) && runs.length === expectedLabels.length;
  const complete = summary.complete === true && cleanupVerified && matrixComplete && !issues.length && runs.every(r => r.checks.every(c => c.passed));
  return { analyzedAt: new Date().toISOString(), evidence, inputFiles: inputs, issues, settings, artifacts: environment.versions,
    environment: environment.host, docker: environment.docker,
    validation: { runnerComplete: summary.complete === true, requestedMatrixComplete: matrixComplete, cleanupVerified,
      allInstrumentedChecksPassed: complete, formalWorkloadRequested: [1024, 8192].every(n => (settings.connections || []).includes(n))
        && settings.rounds >= 3 && settings.measureMs >= 60000 && settings.soakMs >= 600000,
      quietWindowProvenByTheseFiles: false, gaCapacityGuarantee: false },
    definitions: {
      throughput: 'ACKs arriving inside each worker fixed measurement window / requested window seconds. Tail ACKs complete correctness but do not raise ACK/s.',
      latency: 'All ACKs for messages sent during measurement, including drain. Histogram bucket upper bounds (0.1 ms by default); overflow quantiles remain null.',
      comparison: 'Median and range of per-round metrics, never a pooled quantile. Null/overflow measurements are counted as unavailable, not zero.',
      serverCpu: 'Unweighted arithmetic mean of complete Docker stats snapshots; 100% means one CPU core. Failed/cross-boundary samples are excluded and counted.',
      memory: 'RSS is /proc/1/status VmRSS. Docker MemUsage is a separate cgroup/cache-adjusted CLI measure. Neither is Java live heap.',
      limits: 'This local closed-loop workload has one outstanding message per connection. Client CPU, Docker virtualization and other host work can limit throughput. No automatic production SLI or capacity threshold is defined. Missing gates or uncontrolled conditions cannot establish GA capacity; soak resource growth alone neither proves nor disproves a leak.'
    }, comparisons, runs, cleanup };
}
const f = (value, digits = 2) => finite(value) ? value.toFixed(digits) : 'unavailable';
function markdown(a) {
  const lines = ['# Local load evidence analysis', '', `Evidence: \`${a.evidence}\``, '',
    `Runner complete: **${a.validation.runnerComplete}**. Requested matrix complete: **${a.validation.requestedMatrixComplete}**. Cleanup verified: **${a.validation.cleanupVerified}**. All instrumented evidence checks passed: **${a.validation.allInstrumentedChecksPassed}**.`, '',
    'These observations do not provide a GA capacity guarantee. A quiet host and deployment-specific load/SLI gates require separate evidence.', '',
    '## Per-connection-scale comparison', '',
    'Medians of round metrics; brackets show each version’s round minimum–maximum. Latencies are histogram upper bounds.', '',
    '| Connections | Metric | Old (rounds) | New (rounds) | New vs old |', '|---:|---|---:|---:|---:|'];
  for (const c of a.comparisons) for (const [key, title] of [['ackPerSecond', 'ACK/s'], ['p95MsUpperBound', 'p95 ms'], ['p99MsUpperBound', 'p99 ms']]) {
    const show = v => `${f(v[key].median)} [${f(v[key].min)}–${f(v[key].max)}] (${v[key].count}/${v.rounds})`;
    lines.push(`| ${c.connections} | ${title} | ${show(c.old)} | ${show(c.new)} | ${f(c.newVsOldPercent[key])}% |`);
  }
  lines.push('', '## Every run', '', '| Run | ACK/s | p50 / p95 / p99 ms | Sent / ACK / tail ACK | Errors / close failures | Measurement server CPU mean | RSS peak MiB | Native threads | Worker CPU sum |', '|---|---:|---:|---:|---:|---:|---:|---:|---:|');
  for (const r of a.runs) {
    const m = r.resources.stages.measure, c = r.counts || {}, p = r.latencyMsUpperBound || {};
    const errors = [c.duplicateOrCorrupt, c.disconnected, c.closeTimeouts, c.abnormalCloses];
    lines.push(`| ${r.label} | ${f(r.ackPerSecond)} | ${f(p.p50)} / ${f(p.p95)} / ${f(p.p99)} | ${c.sent ?? '?'} / ${c.acked ?? '?'} / ${c.tailAcks ?? '?'} | ${errors.every(finite) ? errors.reduce((x, y) => x + y, 0) : 'unknown'} | ${f(m?.cpuPercent.mean)}% | ${f(m?.rssMiB.max)} | ${m?.threads.min ?? '?'}–${m?.threads.max ?? '?'} | ${f(r.workerCpu?.totalPercent)}% |`);
  }
  lines.push('', '## Resource stages and GC', '', '| Run / stage | Valid / discarded samples | CPU mean % | RSS mean / peak MiB | Threads min–max | Docker memory mean MiB |', '|---|---:|---:|---:|---:|---:|');
  for (const r of a.runs) for (const [stage, s] of Object.entries(r.resources.stages)) lines.push(`| ${r.label} / ${stage} | ${s.samples} / ${s.discarded} | ${f(s.cpuPercent.mean)} | ${f(s.rssMiB.mean)} / ${f(s.rssMiB.max)} | ${s.threads.min ?? '?'}–${s.threads.max ?? '?'} | ${f(s.dockerMemoryMiB.mean)} |`);
  lines.push('', '| Run | Whole-process GC IDs / pauses / full pauses | Whole-process pause total / max ms | Measurement pause total ms |', '|---|---:|---:|---:|');
  for (const r of a.runs) lines.push(`| ${r.label} | ${r.gc?.uniqueGcIds ?? '?'} / ${r.gc?.pauseCount ?? '?'} / ${r.gc?.fullPauseCount ?? '?'} | ${f(r.gc?.pauseTotalMs)} / ${f(r.gc?.pauseMs.max)} | ${f(r.gc?.measurement?.pauseTotalMs)} |`);
  lines.push('', '## Sustained-run first and last thirds', '', '| Run | First / last samples | CPU mean first → last | RSS mean first → last MiB | RSS peak first → last MiB | Threads first → last |', '|---|---:|---:|---:|---:|---:|');
  for (const r of a.runs.filter(r => r.soak)) {
    const s = r.resources.soakSegments;
    lines.push(`| ${r.label} | ${s?.first.samples ?? 0} / ${s?.last.samples ?? 0} | ${f(s?.first.cpuPercent.mean)} → ${f(s?.last.cpuPercent.mean)} | ${f(s?.first.rssMiB.mean)} → ${f(s?.last.rssMiB.mean)} | ${f(s?.first.rssMiB.max)} → ${f(s?.last.rssMiB.max)} | ${s?.first.threads.min ?? '?'}–${s?.first.threads.max ?? '?'} → ${s?.last.threads.min ?? '?'}–${s?.last.threads.max ?? '?'} |`);
  }
  lines.push('', '## Missing or failed evidence', '');
  const failures = [...a.issues, ...a.runs.flatMap(r => r.checks.filter(c => !c.passed).map(c => `${r.label}: ${c.name}`))];
  if (!a.validation.runnerComplete) failures.push('Runner did not mark the complete matrix successful.');
  if (!a.validation.requestedMatrixComplete) failures.push('Requested matrix is incomplete.');
  if (!a.validation.cleanupVerified) failures.push('Successful, empty Docker container/network inventories are missing.');
  lines.push(...(failures.length ? failures.map(s => '- ' + s.replaceAll('\n', ' ')) : ['None in the instrumented checks.']));
  lines.push('', '## Definitions and limits', '', ...Object.entries(a.definitions).map(([key, value]) => `- **${key}**: ${value}`), '');
  return lines.join('\n');
}
module.exports = { distribution, percentile, memoryMiB, parseResource, summarizeResources, gcSummary, analyze, markdown };
if (require.main === module) {
  if (!process.argv[2]) { console.error('Usage: node docker-demo/migration/summarize-load.cjs PATH_TO_EVIDENCE'); process.exitCode = 2; }
  else {
    const evidence = path.resolve(process.argv[2]);
    const analysis = analyze(evidence);
    fs.writeFileSync(path.join(evidence, 'analysis.json'), JSON.stringify(analysis, null, 2));
    fs.writeFileSync(path.join(evidence, 'analysis.md'), markdown(analysis));
    console.log(JSON.stringify({ evidence, ...analysis.validation }));
  }
}
