const assert = require('node:assert/strict');
const {meanDelta, streamMean} = require(process.argv[2]);
assert.equal(meanDelta(300000, 3, 100000, 1, .001), 100);
assert.equal(meanDelta(1, 1, 2, 2, 1000), null);
assert.equal(meanDelta(1, 2, 1, 2, 1000), null);
assert.equal(meanDelta(undefined, 3, 1, 2, 1000), null);
const cache = new Map();
const row = (id, count, sum) => ({id, kind:'video', totalPacketSendDelay:sum, packetsSent:count});
assert.equal(streamMean([row('a',10,1)],cache,true), null);
assert.equal(streamMean([row('a',20,1.5)],cache,true), 50);
assert.equal(streamMean([row('b',500,20)],cache,true), null); // SSRC replacement
assert.equal(streamMean([row('b',500,20)],cache,true), null); // no packets
assert.equal(streamMean([row('b',1,.1)],cache,true), null); // reset
assert.equal(streamMean([{id:'r',kind:'video',jitterBufferDelay:1,jitterBufferEmittedCount:10}],cache,false), null);
assert.equal(streamMean([{id:'r',kind:'video',jitterBufferDelay:3,jitterBufferEmittedCount:20}],cache,false), 200);
assert.equal(streamMean([],cache,false), null);
console.log('Latency metrics counter tests passed');

// Encoded bytes / server monotonic microseconds -> bits per second.
assert.equal(meanDelta(250000, 2000000, 125000, 1000000, 8000000), 1000000);
assert.equal(meanDelta(125000, 2000000, 125000, 1000000, 8000000), 0);
assert.equal(meanDelta(125000, 1000000, 125000, 1000000, 8000000), null);

// Optional receiver metrics: weighted deltas, missing counters and stream resets.
for (const metric of ['jitterBufferTargetDelay', 'jitterBufferMinimumDelay', 'totalDecodeTime']) {
  const previous = new Map();
  const sample = (id, sum, count) => ({id, kind:'video', [metric]:sum,
    jitterBufferEmittedCount:count, framesDecoded:count});
  assert.equal(streamMean([sample('a',1,10)], previous, false, metric), null);
  assert.equal(streamMean([sample('a',3,20)], previous, false, metric), 200);
  assert.equal(streamMean([sample('a',3,20)], previous, false, metric), null);
  assert.equal(streamMean([sample('a',undefined,30)], previous, false, metric), null);
  assert.equal(streamMean([sample('b',10,100)], previous, false, metric), null);
  assert.equal(streamMean([sample('b',0,0)], previous, false, metric), null);
}
console.log('Receiver buffer detail tests passed');
