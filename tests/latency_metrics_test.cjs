const assert = require('node:assert/strict');
const {meanDelta, streamMean, encodedFrameRate} = require(process.argv[2]);
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

const encoded = new Map();
const frame = (id, framesEncoded, timestamp) => ({id, kind:'video', framesEncoded, timestamp});
assert.equal(encodedFrameRate([frame('v',30,1000)], encoded), null);
assert.equal(encodedFrameRate([frame('v',60,2000)], encoded), 30);
assert.equal(encodedFrameRate([frame('v',60,3000)], encoded), 0); // stall is visible
assert.equal(encodedFrameRate([frame('new',5,4000)], encoded), null); // new SSRC
assert.equal(encodedFrameRate([frame('new',1,5000)], encoded), null); // counter reset
assert.equal(encodedFrameRate([frame('new',undefined,6000)], encoded), null);
assert.equal(encodedFrameRate([], encoded), null);
assert.equal(meanDelta(60,2000000,30,1000000,1000000),30); // JNI frame samples / mono microseconds
console.log('Pipeline frame counter tests passed');

const {bufferHealth, outgoingEstimate} = require(process.argv[2]);
// Reproduce the report: low measured queueing does not erase a 419ms buffer target.
assert.deepEqual(bufferHealth(5.4, 70.9, 418.9, 418.9),
  {text:'缓冲诊断：目标/最低缓冲偏高', warning:true});
assert.equal(bufferHealth(5, 70, 50, 50).warning, false);
assert.equal(bufferHealth(null, null, null, null).text, '缓冲诊断：采样中');
assert.equal(bufferHealth(160, 70, 50, 50).text, '缓冲诊断：发送等待偏高');
assert.equal(bufferHealth(5, 250, 50, 50).text, '缓冲诊断：接收驻留偏高');
assert.equal(bufferHealth(5, 70, null, 419).warning, true);
const network = [
  {id:'transport', type:'transport', selectedCandidatePairId:'active'},
  {id:'old', type:'candidate-pair', availableOutgoingBitrate:9000000},
  {id:'active', type:'candidate-pair', availableOutgoingBitrate:80000},
];
assert.equal(outgoingEstimate(network), 80000); // never use an old/unselected ICE path
assert.equal(outgoingEstimate(network.slice(1)), null);
assert.equal(outgoingEstimate([{id:'t',type:'transport', selectedCandidatePairId:'missing'}]), null);
assert.equal(outgoingEstimate([{...network[0]}, {...network[2], availableOutgoingBitrate:undefined}]), null);
console.log('High buffer target diagnosis and selected sender bandwidth tests passed');
const {telemetryIntervalMs} = require(process.argv[2]);
assert.equal(telemetryIntervalMs(35000), 5000);
assert.equal(telemetryIntervalMs(0), 5000);
assert.equal(telemetryIntervalMs(500000), 2000);
assert.equal(telemetryIntervalMs(8000000), 1000);
assert.equal(telemetryIntervalMs(-1), 1000);
assert.equal(telemetryIntervalMs(undefined), 1000); // compatible with older/native-missing feedback
console.log('Adaptive weak-link telemetry interval tests passed');

// Exercise the actual widget: low-rate polling must not expire its own five-second samples.
const {mountLatencyMetrics} = require(process.argv[2]);
let widgetNow = 1000, widgetPoll, widgetMessage, widgetBar;
global.performance = {now: () => widgetNow};
global.document = {createElement: () => ({children:[], style:{}, append(node) {this.children.push(node);}})};
global.window = {setInterval: callback => {widgetPoll = callback; return 1;}, addEventListener() {}};
const sent = [];
const widgetChannel = {readyState:'open', bufferedAmount:0, addEventListener: (_name, callback) => {widgetMessage = callback;},
  removeEventListener() {}, send: value => sent.push(value)};
const widgetPeer = {connectionState:'connected', getStats:async () => new Map()};
mountLatencyMetrics({closest: () => ({before: node => {widgetBar = node;}})}, () => widgetPeer, () => widgetChannel);
(async () => {
  await widgetPoll();
  assert.equal(sent.length, 1);
  widgetMessage({currentTarget:widgetChannel, data:JSON.stringify({type:'scrcpy.latency.v1',
    captureUs:1000, captureFrames:10, sampleUs:1000000, encodedBytes:1000,
    allocatedBps:35000, configuredBps:35000, targetBps:2000000, bandwidthAllocationBps:3000000,
    rateUpdatedUs:900000, outbound:[], network})});
  assert.match(widgetBar.children[11].textContent, /2000 kbps$/); // bitrate, never fps or ms
  assert.match(widgetBar.children[12].textContent, /3000 kbps$/);
  assert.match(widgetBar.children[13].textContent, /100\.0 ms$/);
  assert.match(widgetBar.children[14].textContent, /80 kbps$/);
  widgetNow = 5000;
  await widgetPoll();
  assert.equal(sent.length, 1); // no one-second diagnostic traffic at 35 kbps
  assert.match(widgetBar.children[3].textContent, /35 kbps$/); // not stale after the old 3.5s timeout
  widgetNow = 6000;
  await widgetPoll();
  assert.equal(sent.length, 2);
  console.log('Low-rate telemetry widget cadence and formatting tests passed');
})().catch(error => {console.error(error); process.exitCode = 1;});
