'use strict';

// Offline analysis only: reads an existing verify-load evidence directory and writes
// analysis.json / analysis.md there. Does not start processes, containers or load.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const ANALYSIS_SCHEMA = 'load-evidence-v2-readiness';
const READINESS_PROTOCOL = 'bounded-health-readiness-v1';
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
  if (sample.stats?.code !== 0 || sample.stats?.timedOut !== false) reasons.push('docker stats failed or timeout state unavailable');
  if (sample.process?.code !== 0 || sample.process?.timedOut !== false) reasons.push('/proc sample failed or timeout state unavailable');
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
function summarizeResources(raw, measureMs, soak, measurementStart = null) {
  const normalized = raw.map(parseResource), valid = normalized.filter(s => s.valid);
  const stages = {};
  for (const stage of new Set(normalized.map(s => s.phase))) {
    const all = normalized.filter(s => s.phase === stage);
    stages[stage] = { ...resourceSummary(all.filter(s => s.valid)), discarded: all.filter(s => !s.valid).length };
  }
  const measurementEnd = finite(measurementStart) && finite(measureMs) ? measurementStart + measureMs : null;
  const measured = valid.filter(s => s.phase === 'measure' && finite(measurementStart)
    && Date.parse(s.startedAt) >= measurementStart && Date.parse(s.at) <= measurementEnd)
    .sort((a, b) => a.midpointMs - b.midpointMs);
  if (stages.measure) stages.measure = { ...resourceSummary(measured), discarded: normalized.filter(s => s.phase === 'measure').length - measured.length };
  const required = measureMs >= 10000 ? Math.max(1, Math.floor(measureMs / 10000)) : 0;
  const first = measured.length ? Date.parse(measured[0].startedAt) : null;
  const last = measured.length ? Date.parse(measured.at(-1).at) : null;
  const coverage = { start: finite(measurementStart) ? new Date(measurementStart).toISOString() : null,
    end: finite(measurementEnd) ? new Date(measurementEnd).toISOString() : null, endpointToleranceMs: 15000,
    firstSampleDelayMs: first === null ? null : first - measurementStart,
    lastSampleGapMs: last === null ? null : measurementEnd - last,
    largestSampleGapMs: measured.length ? Math.max(0, ...measured.slice(1).map((s, i) => Date.parse(s.startedAt) - Date.parse(measured[i].at))) : null,
    sampledSpanMs: first === null ? null : last - first,
    sampledSpanFraction: first === null ? null : (last - first) / measureMs,
    endpointsCovered: first !== null && first - measurementStart <= 15000 && measurementEnd - last <= 15000 };
  const result = { rawCount: raw.length, validCount: valid.length, discardedCount: raw.length - valid.length,
    requiredMeasurementSamples: required, validMeasurementSamples: measured.length,
    outsideMeasurementWindow: valid.filter(s => s.phase === 'measure').length - measured.length,
    resourceEvidence: required > 0 && measured.length >= required && coverage.endpointsCovered
      && coverage.largestSampleGapMs <= 15000, coverage, stages, normalized };
  if (soak && finite(measurementStart)) {
    const firstThird = measured.filter(s => s.midpointMs <= measurementStart + measureMs / 3);
    const lastThird = measured.filter(s => s.midpointMs >= measurementEnd - measureMs / 3);
    result.soakSegments = { definition: 'First and last third of the requested measurement window, anchored to the unique client measure phase UTC; unweighted complete snapshots.',
      first: resourceSummary(firstThird), last: resourceSummary(lastThird), spanMs: measureMs,
      rssMeanDeltaMiB: firstThird.length && lastThird.length ? mean(lastThird.map(s => s.rssMiB)) - mean(firstThird.map(s => s.rssMiB)) : null,
      cpuMeanDeltaPercentagePoints: firstThird.length && lastThird.length ? mean(lastThird.map(s => s.cpuPercent)) - mean(firstThird.map(s => s.cpuPercent)) : null };
  }
  return result;
}
function gcSummary(text, measurementStart, measureMs) {
  const ids = new Set(), pauses = [];
  let collectorHeader = false, initializationHeader = false;
  for (const line of text.split(/\r?\n/)) {
    const record = /^\[([^\]]+)\]\[([\d.]+)s\]\[info\]\[([^\]]+)\]\s+(.+)$/.exec(line);
    if (record && finite(Date.parse(record[1]))) {
      if (record[3].trim() === 'gc' && /^Using \S/.test(record[4])) collectorHeader = true;
      if (record[3].trim() === 'gc,init' && /^Version: \S/.test(record[4])) initializationHeader = true;
    }
    const id = /\bGC\((\d+)\)/.exec(line); if (id) ids.add(Number(id[1]));
    const match = /^\[([^\]]+)\]\[([\d.]+)s\]\[\w+\]\[gc\s*\]\s+GC\((\d+)\)\s+(Pause .+?)\s+([\d.]+)ms\s*$/.exec(line);
    if (match) pauses.push({ at: match[1], uptimeSeconds: Number(match[2]), id: Number(match[3]), description: match[4], durationMs: Number(match[5]) });
  }
  const summarize = rows => ({ pauseCount: rows.length, pauseTotalMs: rows.reduce((sum, row) => sum + row.durationMs, 0),
    pauseMs: distribution(rows.map(p => p.durationMs)), fullPauseCount: rows.filter(p => /^Pause Full\b/.test(p.description)).length });
  if (!collectorHeader || !initializationHeader) return { validLog: false, collectorHeader, initializationHeader,
    uniqueGcIds: null, pauseCount: null, pauseTotalMs: null, pauseMs: distribution([]), fullPauseCount: null, measurement: null, pauses };
  return { scope: 'Whole JVM lifetime, including startup and recovery. Measurement subset uses client phase UTC timestamps.',
    validLog: collectorHeader && initializationHeader, collectorHeader, initializationHeader,
    uniqueGcIds: ids.size, ...summarize(pauses),
    measurement: finite(measurementStart) ? summarize(pauses.filter(p => {
      const t = Date.parse(p.at); return t >= measurementStart && t < measurementStart + measureMs;
    })) : null, pauses };
}
function readinessSummary(compose, readiness, measurementStart, protocol) {
  const reasons = [];
  const require = (condition, reason) => { if (!condition) reasons.push(reason); };
  const utc = value => typeof value === 'string' && /T.*(?:Z|\+00:?00)$/.test(value)
    && finite(Date.parse(value)) ? Date.parse(value) : null;
  const start = utc(readiness?.startedAt), finish = utc(readiness?.finishedAt);
  const inspection = readiness?.healthcheckInspection;
  const inspected = utc(inspection?.inspectedAt);
  require(protocol === READINESS_PROTOCOL && readiness?.protocol === READINESS_PROTOCOL, 'missing or unsupported readiness protocol');
  require(compose?.services?.app?.healthcheck?.disable === true, 'Compose periodic healthcheck is not explicitly disabled');
  require(typeof inspection?.containerId === 'string' && /^[0-9a-f]{64}$/.test(inspection.containerId)
    && JSON.stringify(inspection?.config?.Test) === '["NONE"]', 'actual container healthcheck NONE inspection missing');
  require(readiness?.timeoutMs === 60000 && readiness?.intervalMs === 500 && readiness?.requestTimeoutSeconds === 2,
    'readiness request/deadline bounds differ from protocol');
  require(readiness?.ready === true && start !== null && finish !== null && inspected !== null
    && inspected <= start && finish >= start && finish - start <= 60000
    && finite(readiness?.elapsedMs) && readiness.elapsedMs === finish - start && readiness.elapsedMs <= 60000,
  'readiness did not finish UP within its bounded window');
  require(finish !== null && finite(measurementStart) && finish < measurementStart, 'readiness overlaps or follows measurement');
  const attempts = Array.isArray(readiness?.attempts) ? readiness.attempts : [];
  require(attempts.length > 0 && attempts.length <= 121, 'readiness attempts missing or unbounded');
  let previousFinish = start;
  for (const [index, attempt] of attempts.entries()) {
    const attemptStart = utc(attempt?.startedAt), attemptFinish = utc(attempt?.finishedAt);
    require(attemptStart !== null && attemptFinish !== null && previousFinish !== null
      && attemptStart >= previousFinish && attemptFinish >= attemptStart && start !== null
      && finish !== null && attemptStart >= start && attemptFinish <= finish,
    `readiness attempt ${index} is not serial or has invalid timestamps`);
    require(Number.isInteger(attempt?.commandTimeoutMs) && attempt.commandTimeoutMs >= 1 && attempt.commandTimeoutMs <= 3000,
      `readiness attempt ${index} has no bounded command timeout`);
    require(typeof attempt?.ready === 'boolean' && (attempt?.code === null || Number.isInteger(attempt?.code))
      && (attempt?.timedOut === null || typeof attempt?.timedOut === 'boolean'), `readiness attempt ${index} outcome is incomplete`);
    if (index < attempts.length - 1) require(attempt?.ready === false
      && !(attempt?.code === 0 && attempt?.timedOut === false && attempt?.healthStatus === 'UP'),
    'readiness probes continued after success');
    previousFinish = attemptFinish;
  }
  const last = attempts.at(-1);
  require(last?.ready === true && last?.code === 0 && last?.timedOut === false && last?.healthStatus === 'UP'
    && utc(last?.finishedAt) === finish && finish !== null, 'last readiness attempt is not a completed successful UP response');
  return { protocol: readiness?.protocol ?? null, valid: reasons.length === 0, reasons, attempts: attempts.length,
    containerId: inspection?.containerId ?? null, startedAt: readiness?.startedAt ?? null, finishedAt: readiness?.finishedAt ?? null };
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
  if (entries.size !== manifest.length) issues.push('Manifest contains duplicate run labels.');
  // Include unfinished/failed run directories omitted from the success manifest.
  for (const dir of fs.readdirSync(evidence, { withFileTypes: true }).filter(d => d.isDirectory())) {
    const inputFile = path.join(evidence, dir.name, 'input.json');
    if (fs.existsSync(inputFile) && !entries.has(dir.name)) {
      const input = read(dir.name + '/input.json', {});
      entries.set(dir.name, { label: dir.name, variant: /(?:^|-)old(?:-|$)/.test(dir.name) ? 'old' : 'new', connections: input.connections, measureMs: input.measureMs });
    }
  }
  const settings = summary.settings || environment.settings || {};
  const settingsKeys = ['connections', 'rounds', 'workers', 'measureMs', 'soakMs', 'warmupMs', 'drainMs', 'payloadBytes', 'readinessProtocol'];
  if (!summary.settings || !environment.settings || settingsKeys.some(key =>
    JSON.stringify(summary.settings[key]) !== JSON.stringify(environment.settings[key]))) issues.push('Summary and environment workload settings differ or are missing.');
  const settingsValid = Array.isArray(settings.connections) && settings.connections.length > 0
    && settings.connections.every(n => Number.isInteger(n) && n > 0) && new Set(settings.connections).size === settings.connections.length
    && ['rounds', 'workers', 'measureMs', 'warmupMs', 'drainMs', 'payloadBytes'].every(k => Number.isInteger(settings[k]) && settings[k] > 0)
    && Number.isInteger(settings.soakMs) && settings.soakMs >= 0;
  if (!settingsValid) issues.push('Requested workload settings are invalid or incomplete.');
  if (settings.readinessProtocol !== READINESS_PROTOCOL) issues.push('This analyzer requires bounded-health-readiness-v1 evidence. Historical results retain their original archived checker and do not satisfy the new isolation gate.');
  const expectedRuns = new Map();
  if (settingsValid) {
    for (const connections of settings.connections) for (let round = 1; round <= settings.rounds; round++) for (const variant of ['old', 'new']) {
      expectedRuns.set(`${connections}-${variant}-${round}`, { variant, connections, measureMs: settings.measureMs });
    }
    if (settings.soakMs) for (const variant of ['old', 'new']) expectedRuns.set(`soak-${variant}`,
      { variant, connections: Math.max(...settings.connections), measureMs: settings.soakMs });
  }
  const runs = [];
  for (const entry of entries.values()) {
    assert.equal(path.basename(entry.label), entry.label, 'Run label must be a single directory name');
    const label = entry.label, result = read(label + '/result.json'), input = read(label + '/input.json'), rawResources = read(label + '/resources.json', []);
    const runCleanup = read(label + '/cleanup.json'), recovery = read(label + '/recovery.json', []);
    const stdout = read(label + '/client.stdout.jsonl', ''), gc = read(label + '/gc.log');
    const compose = read(label + '/compose.json'), readiness = read(label + '/readiness.json');
    const checks = [], check = (name, passed) => checks.push({ name, passed: Boolean(passed) });
    const expected = expectedRuns.get(label), measureMs = expected?.measureMs ?? null;
    const row = { label, variant: expected?.variant ?? entry.variant, connections: expected?.connections ?? entry.connections, measureMs, soak: label.startsWith('soak-'), checks };
    check('manifest matches requested matrix parameters', expected && ['variant', 'connections', 'measureMs'].every(k => entry[k] === expected[k]));
    const expectedInput = { runId: label, connections: expected?.connections, measureMs,
      workers: settings.workers, warmupMs: settings.warmupMs, drainMs: settings.drainMs, payloadBytes: settings.payloadBytes };
    check('input and raw result settings match requested run', expected && Object.entries(expectedInput)
      .every(([key, value]) => input?.[key] === value && result?.settings?.[key] === value));
    const phaseEvents = [];
    for (const line of stdout.split(/\r?\n/)) {
      try { const e = JSON.parse(line); if (e.phase === 'measure') phaseEvents.push(e); } catch {}
    }
    const phase = phaseEvents[0], phaseStart = phaseEvents.length === 1 && typeof phase.at === 'string'
      && /T.*(?:Z|\+00:?00)$/.test(phase.at) && finite(Date.parse(phase.at)) ? Date.parse(phase.at) : null;
    check('unique parseable client measurement phase matches connections', phaseStart !== null && phase.connections === row.connections);
    row.readiness = readinessSummary(compose, readiness, phaseStart, settings.readinessProtocol);
    check('bounded serial readiness completed before measurement with periodic probes disabled', row.readiness.valid);
    row.resources = summarizeResources(rawResources, measureMs, row.soak, phaseStart);
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
    check('all configured connections represented', workers.reduce((sum, w) => sum + w.connected, 0) === row.connections);
    check('all configured workers represented once', workers.length === settings.workers && new Set(workers.map(w => w.index)).size === workers.length
      && workers.every(w => Number.isInteger(w.index) && w.index >= 0 && w.index < settings.workers));
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
    row.gc = gc === null ? null : gcSummary(gc, phaseStart, measureMs);
    check('GC log has timestamped collector and initialization headers', row.gc?.validLog);
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
  const expectedLabels = [...expectedRuns.keys()];
  const matrixComplete = expectedLabels.length > 0 && expectedLabels.every(label => runs.some(r => r.label === label)) && runs.length === expectedLabels.length;
  const complete = summary.complete === true && cleanupVerified && matrixComplete && !issues.length && runs.every(r => r.checks.every(c => c.passed));
  return { analysisSchema: ANALYSIS_SCHEMA, analyzedAt: new Date().toISOString(), evidence, inputFiles: inputs, issues, settings, artifacts: environment.versions,
    environment: environment.host, docker: environment.docker,
    validation: { runnerComplete: summary.complete === true, requestedMatrixComplete: matrixComplete, cleanupVerified,
      readinessIsolationVerified: runs.length > 0 && runs.every(r => r.readiness?.valid),
      allInstrumentedChecksPassed: complete, formalWorkloadRequested: [1024, 8192].every(n => (settings.connections || []).includes(n))
        && settings.rounds >= 3 && settings.measureMs >= 60000 && settings.soakMs >= 600000,
      quietWindowProvenByTheseFiles: false, gaCapacityGuarantee: false },
    definitions: {
      readiness: 'Schema load-evidence-v2-readiness requires bounded-health-readiness-v1: explicit Compose healthcheck disable, actual container Test=[NONE], serial bounded startup probes ending in a successful UP response before measurement. Historical a35 evidence is not silently upgraded to this gate.',
      throughput: 'ACKs arriving inside each worker fixed measurement window / requested window seconds. Tail ACKs complete correctness but do not raise ACK/s.',
      latency: 'All ACKs for messages sent during measurement, including drain. Histogram bucket upper bounds (0.1 ms by default); overflow quantiles remain null.',
      comparison: 'Median and range of per-round metrics, never a pooled quantile. Null/overflow measurements are counted as unavailable, not zero.',
      serverCpu: 'Unweighted arithmetic mean of complete Docker stats snapshots; 100% means one CPU core. Failed/cross-boundary samples are excluded and counted. Measurement samples must lie inside the requested client UTC window and cover both endpoints within 15 seconds.',
      memory: 'RSS is /proc/1/status VmRSS. Docker MemUsage is a separate cgroup/cache-adjusted CLI measure. Neither is Java live heap.',
      limits: 'This local closed-loop workload has one outstanding message per connection. Client CPU, Docker virtualization and other host work can limit throughput. No automatic production SLI or capacity threshold is defined. Missing gates or uncontrolled conditions cannot establish GA capacity; soak resource growth alone neither proves nor disproves a leak.'
    }, comparisons, runs, cleanup };
}
const f = (value, digits = 2) => finite(value) ? value.toFixed(digits) : 'unavailable';
function markdown(a) {
  const lines = ['# Local load evidence analysis', '', `Evidence: \`${a.evidence}\``, '', `Analysis schema: \`${a.analysisSchema}\`. Startup probe isolation verified: **${a.validation.readinessIsolationVerified}**.`, '',
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
  lines.push('', '| Run | Requested measurement seconds | Valid / required samples | First sample delay / last sample gap seconds | Observed resource span % | Endpoints covered |', '|---|---:|---:|---:|---:|---|');
  for (const r of a.runs) {
    const c = r.resources.coverage;
    lines.push(`| ${r.label} | ${finite(r.measureMs) ? f(r.measureMs / 1000) : '?'} | ${r.resources.validMeasurementSamples} / ${r.resources.requiredMeasurementSamples} | ${finite(c.firstSampleDelayMs) ? f(c.firstSampleDelayMs / 1000) : '?'} / ${finite(c.lastSampleGapMs) ? f(c.lastSampleGapMs / 1000) : '?'} | ${finite(c.sampledSpanFraction) ? f(c.sampledSpanFraction * 100) : '?'} | ${c.endpointsCovered} |`);
  }
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
module.exports = { distribution, percentile, memoryMiB, parseResource, summarizeResources, gcSummary, readinessSummary, analyze, markdown };
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
