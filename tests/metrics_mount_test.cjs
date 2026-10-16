const assert = require('node:assert/strict');
const path = require('node:path');
const {mountMetrics} = require(process.argv[2]);
const {mountLatencyMetrics} = require(path.join(path.dirname(process.argv[2]), 'latency-metrics.js'));

const element = () => ({children: [], dataset: {}, style: {},
  append(node) { this.children.push(node); }, setAttribute() {}});
global.document = {createElement: element};
let timers = 0;
global.window = {setInterval() { return ++timers; }, addEventListener() {}};

const mountBoth = (video, container) => {
  mountMetrics(video, () => null, container);
  mountLatencyMetrics(video, () => null, () => null, container);
};

// The page supplies a metrics panel separate from the video stage.
const panel = element();
const video = {closest() { throw new Error('Explicit panel must not require a stage'); }};
mountBoth(video, panel);
assert.equal(panel.children.length, 2);
assert.equal(timers, 2);

// Embedded players can still use the video itself as an insertion anchor.
const inserted = [];
mountBoth({closest: () => null, parentElement: {}, before: node => inserted.push(node)});
assert.equal(inserted.length, 2);
assert.equal(timers, 4);

// Existing stage layouts retain their original placement.
const stageBars = [];
mountBoth({closest: () => ({parentElement: {}, before: node => stageBars.push(node)})});
assert.equal(stageBars.length, 2);
assert.equal(timers, 6);

// Detached players skip optional metrics without leaking polling/callbacks.
mountBoth({closest: () => null, parentElement: null,
  requestVideoFrameCallback() { throw new Error('Detached player must not start observing'); }});
assert.equal(timers, 6);
console.log('Metrics mount tests passed: explicit panel, embedded video, stage and detached player');
