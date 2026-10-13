const assert = require('node:assert/strict');
const {mountMetrics} = require(process.argv[2]);
let now = 0, poll, hide;
global.performance = {now: () => now};
global.document = {createElement: () => ({children: [], dataset: {}, append(node) { this.children.push(node); }, setAttribute() {}})};
global.window = {setInterval: callback => {poll = callback; return 1;}, addEventListener: (_event, callback) => {hide = callback;}};
global.clearInterval = () => {};
let callback, cancelled = false, bar;
const video = {videoWidth: 480, videoHeight: 848,
  closest: () => ({before: node => {bar = node;}}),
  requestVideoFrameCallback: observer => {callback = observer; return 7;},
  cancelVideoFrameCallback: id => {cancelled = id === 7;}};
let frames = 0, timestamp = 0;
const peer = {connectionState:'connected', getStats: async () => new Map([['v', {
  id:'v', type:'inbound-rtp', kind:'video', timestamp,
  framesDecoded:frames, bytesReceived:frames*1000, packetsReceived:frames, packetsLost:0} ]])};
let current = peer;
mountMetrics(video, () => current);
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
