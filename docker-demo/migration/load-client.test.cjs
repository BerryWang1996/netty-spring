'use strict';
const assert = require('node:assert/strict');
const test = require('node:test');
const { verifyReply, percentile } = require('./load-client.cjs');
const textClient = () => ({ pending: { body: 'run;c=1;s=2;', seq: 2 }, lastAck: 1 });
test('reply ledger rejects duplicate, corruption, wrong frame type and sequence gaps', () => {
  verifyReply(textClient(), Buffer.from('run;c=1;s=2;'), false);
  assert.throws(() => verifyReply({ pending: null }, Buffer.from('x'), false));
  assert.throws(() => verifyReply(textClient(), Buffer.from('run;c=2;s=2;'), false));
  assert.throws(() => verifyReply(textClient(), Buffer.from('run;c=1;s=2;'), true));
  assert.throws(() => verifyReply({ ...textClient(), lastAck: 0 }, Buffer.from('run;c=1;s=2;'), false));
});
test('JSON checks values, stable session and the complete response shape', () => {
  const client = { ...textClient(), json: true, room: 'r' };
  const reply = { room: 'r', message: 'run;c=1;s=2;', sessionId: 'abc' };
  verifyReply(client, Buffer.from(JSON.stringify(reply)), false);
  for (const bad of [{ ...reply, sessionId: 'def' }, { ...reply, room: 'wrong' }, { ...reply, unexpected: true }]) {
    assert.throws(() => verifyReply(client, Buffer.from(JSON.stringify(bad)), false));
  }
});
test('histogram reports upper bucket boundaries and never hides overflow as finite latency', () => {
  const bins = Array(100001).fill(0); bins[9] = 50; bins[19] = 49; bins[100000] = 1;
  assert.equal(percentile(bins, .5), 1); assert.equal(percentile(bins, .99), 2); assert.equal(percentile(bins, 1), null);
});
