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
