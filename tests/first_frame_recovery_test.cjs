const assert = require('node:assert/strict');
const { FirstFrameRecovery } = require(process.argv[2]);
let clock = 0, id = 0;
const timers = new Map();
global.setTimeout = (fn, ms) => { const key = ++id; timers.set(key, { fn, at: clock + ms }); return key; };
global.clearTimeout = key => timers.delete(key);
function advance(ms) {
  const end = clock + ms;
  for (;;) {
    const entry = [...timers].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
    if (!entry) break;
    const [key, timer] = entry; timers.delete(key); clock = timer.at; timer.fn();
  }
  clock = end;
}
let requests = 0, exhausted = 0;
const visible = new FirstFrameRecovery(() => true, () => { requests++; return true; }, () => exhausted++);
visible.start(); advance(500); visible.stop(); advance(20000);
assert.equal(requests, 0); assert.equal(exhausted, 0);

// A static display never signals the first rendered frame: bounded, spaced recovery.
const times = [];
const black = new FirstFrameRecovery(() => true, () => { times.push(clock); return true; }, () => exhausted++);
black.start(); black.start(); advance(20000);
assert.equal(times.length, 3); assert.ok(times[1] - times[0] >= 2000); assert.ok(times[2] - times[1] >= 2000);
assert.equal(exhausted, 1); assert.equal(timers.size, 0);
black.start(); advance(20000); assert.equal(times.length, 3);

// Each reload gets its own watchdog; the first displayed frame cancels further resets.
for (let reload = 0; reload < 10; reload++) {
  let attempts = 0;
  const recovery = new FirstFrameRecovery(() => true, () => { attempts++; return true; }, () => assert.fail('unexpected timeout'));
  recovery.start(); advance(1000); assert.equal(attempts, 1);
  recovery.stop(); advance(20000); assert.equal(attempts, 1);
}

// No connected/open/unpaused transport means no reset messages and no runaway timer.
const notReady = new FirstFrameRecovery(() => false, () => assert.fail('not ready'), () => exhausted++);
notReady.start(); advance(20000); assert.equal(exhausted, 2); assert.equal(timers.size, 0);
let ready = false, delivered = 0;
const late = new FirstFrameRecovery(() => ready, () => { delivered++; return true; }, () => assert.fail('unexpected timeout'));
late.start(); advance(3000); assert.equal(delivered, 0); ready = true;
advance(1000); assert.equal(delivered, 1); late.stop();
assert.equal(timers.size, 0);
console.log('Static first-frame recovery, repeated reload and retry-bound tests passed');
