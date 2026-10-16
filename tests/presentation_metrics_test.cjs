const assert = require('node:assert/strict');
const {mountMetrics} = require(process.argv[2]);
let now = 0, poll, hide;
global.performance = {now: () => now};
global.document = {createElement: () => ({children: [], dataset: {}, append(node) { this.children.push(node); }, setAttribute() {}})};
global.window = {setInterval: callback => {poll = callback; return 1;}, addEventListener: (_event, callback) => {hide = callback;}};
global.clearInterval = () => {};
let callback, cancelled = false, bar;
const video = {videoWidth: 480, videoHeight: 848,
  requestVideoFrameCallback: observer => {callback = observer; return 7;},
  cancelVideoFrameCallback: id => {cancelled = id === 7;}};
let frames = 0, timestamp = 0;
const peer = {connectionState:'connected', getStats: async () => new Map([['v', {
  id:'v', type:'inbound-rtp', kind:'video', timestamp,
  framesDecoded:frames, bytesReceived:frames*1000, packetsReceived:frames, packetsLost:0} ]])};
let current = peer;
mountMetrics(video, () => current, {append: node => {bar = node;}});
const text = index => bar.children[index].textContent;
(async () => {
  callback(0, {presentedFrames:0});
  await poll(); // establish both baselines
  now = timestamp = 1000; frames = 30;
  callback(now, {presentedFrames:15});
  await poll();
  assert.match(text(0), /30\.0 fps/);
  assert.match(text(6), /15\.0 fps/); // decoding and presentation must remain separate
  now = timestamp = 2000; frames = 60;
  await poll();
  assert.match(text(0), /30\.0 fps/);
  assert.match(text(6), /0\.0 fps/); // stalled presentation is visible even with active decoding
  current = {...peer};
  now = timestamp = 3000; frames = 0;
  callback(now, {presentedFrames:0});
  await poll();
  assert.match(text(0), /—/);
  assert.match(text(6), /—/); // new peer takes a new baseline
  now = timestamp = 4000; frames = 24;
  callback(now, {presentedFrames:24});
  await poll();
  assert.match(text(6), /24\.0 fps/);
  hide();
  assert.ok(cancelled);
  console.log('Presentation metrics tests passed');
})().catch(error => {console.error(error); process.exitCode = 1;});

// A low-rate stream must not change its quality label on each small packet interval.
const {ConnectionQualityWindow} = require(process.argv[2]);
const quality = new ConnectionQualityWindow();
let sample = quality.observe(1000, 9, 1, 34);
assert.equal(sample.loss, 10);
assert.equal(sample.level, ''); // ten packets are insufficient for a label
sample = quality.observe(2000, 90, 0, 34);
assert.equal(sample.loss, 1); // weighted 1/100, not average(10%, 0%)
assert.equal(sample.level, 'fair');
sample = quality.observe(3000, 1, -1, 34);
assert.equal(sample.loss, 0); // the missing packet arrived late
assert.equal(sample.level, 'fair'); // recovery needs a hold window
for (let time = 4000; time <= 7000; time += 1000) sample = quality.observe(time, 100, 0, 34);
assert.equal(sample.level, 'good');
// High RTT must persist before the label degrades; actual RTT is still shown instantly.
assert.equal(quality.observe(8000, 100, 0, 300).level, 'good');
assert.equal(quality.observe(9000, 100, 0, 34).level, 'good');
assert.equal(quality.observe(10000, 100, 0, 300).level, 'good');
assert.equal(quality.observe(11000, 100, 0, 300).level, 'good');
assert.equal(quality.observe(12000, 100, 0, 300).level, 'poor');
// Idle counters age out; no fabricated 'good' label without packets.
sample = quality.observe(18000, 0, 0, 34);
assert.equal(sample.loss, undefined);
assert.equal(sample.level, '');
quality.observe(19000, 95, 5, 34);
quality.reset();
assert.equal(quality.observe(20000, 10, 0, 34).level, '');
assert.equal(quality.observe(100, 10, 0, 34).level, ''); // clock/epoch regression resets history
console.log('Connection quality window, weighting, late packets and hysteresis tests passed');

const alternating = new ConnectionQualityWindow();
assert.equal(alternating.observe(0, 100, 0, 34).level, 'good');
assert.equal(alternating.observe(1000, 100, 0, 110).level, 'good');
assert.equal(alternating.observe(2000, 100, 0, 300).level, 'good');
assert.equal(alternating.observe(3000, 100, 0, 110).level, 'fair');
console.log('Continuously worse alternating quality levels cannot preserve a good label');
