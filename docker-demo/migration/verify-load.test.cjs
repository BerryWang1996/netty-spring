'use strict';
const assert = require('node:assert/strict');
const test = require('node:test');
const { waitForReadiness, verifyRecovery } = require('./verify-load.cjs');

const response = (status = 'UP') => ({ code: 0, timedOut: false, stdout: JSON.stringify({ status }) });
function clock() {
  let value = Date.parse('2026-10-23T17:00:00Z');
  return { now: () => value, advance(ms) { value += ms; }, async sleep(ms) { value += ms; } };
}

test('startup readiness probes are sequential and stop permanently at the first UP response', async () => {
  const time = clock();
  let calls = 0, active = 0, maximumActive = 0;
  const result = await waitForReadiness(async timeout => {
    calls++; active++; maximumActive = Math.max(maximumActive, active);
    assert.ok(timeout > 0 && timeout <= 3000);
    await Promise.resolve();
    time.advance(100); active--;
    return calls === 1 ? { code: 1, timedOut: false, stdout: '' } : response(calls === 2 ? 'STARTING' : 'UP');
  }, { now: time.now, sleep: time.sleep });
  assert.equal(result.ready, true); assert.equal(calls, 3);
  assert.equal(maximumActive, 1); assert.equal(active, 0);
  assert.equal(result.attempts.length, 3);
  assert.deepEqual(result.attempts.map(attempt => attempt.ready), [false, false, true]);
  for (let i = 1; i < result.attempts.length; i++) {
    assert.ok(Date.parse(result.attempts[i].startedAt) >= Date.parse(result.attempts[i - 1].finishedAt));
  }
  time.advance(60000); await Promise.resolve();
  assert.equal(calls, 3, 'no periodic health probe may survive readiness');
});

test('readiness rejects failed, timed out, malformed and non-UP responses within a finite deadline', async () => {
  for (const bad of [{ code: 1, timedOut: false, stdout: '{"status":"UP"}' },
    { code: 0, timedOut: true, stdout: '{"status":"UP"}' },
    { code: 0, timedOut: false, stdout: 'not JSON' }, response('DOWN')]) {
    const time = clock(); let calls = 0;
    const result = await waitForReadiness(async timeout => {
      calls++; assert.ok(timeout > 0 && timeout <= 1000); time.advance(100); return bad;
    }, { timeoutMs: 1000, intervalMs: 200, now: time.now, sleep: time.sleep });
    assert.equal(result.ready, false); assert.ok(calls > 0 && calls <= 4);
    assert.equal(result.elapsedMs, 1000); assert.ok(result.attempts.every(attempt => !attempt.ready));
  }
});

test('an UP response after the readiness deadline does not start a workload', async () => {
  const time = clock();
  const result = await waitForReadiness(async () => { time.advance(1001); return response(); },
    { timeoutMs: 1000, now: time.now, sleep: time.sleep });
  assert.equal(result.ready, false); assert.equal(result.attempts.length, 1);
});

function snapshot(permits = 16387) {
  return { status: { status: 'UP', websocket: { activeSessionCount: 0 },
    handler: { availablePermits: permits, permitLimit: 16388, rejectedCount: 0, executor: { queueSize: 0, activeCount: 1 } },
    http: { rejectedCount: 0, writeFailureCount: 0 } } };
}

test('recovery still rejects the final two-permit deficit even after earlier passing snapshots', () => {
  const overlap = snapshot(16386); overlap.status.handler.executor.activeCount = 2;
  assert.throws(() => verifyRecovery([snapshot(), snapshot(), snapshot(), overlap]), /handler permits leaked/);
  assert.equal(verifyRecovery([snapshot()]).handler.availablePermits, 16387);
});

test('last-snapshot session, queue, rejection and write-failure gates remain strict', () => {
  for (const mutate of [s => s.websocket.activeSessionCount = 1, s => s.handler.executor.queueSize = 1,
    s => s.handler.rejectedCount = 1, s => s.http.rejectedCount = 1, s => s.http.writeFailureCount = 1]) {
    const last = snapshot(); mutate(last.status);
    assert.throws(() => verifyRecovery([snapshot(), last]));
  }
});
