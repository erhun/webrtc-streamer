const assert = require('node:assert/strict');
const { ScrcpyClient } = require(process.argv[2]);
const tick = () => new Promise(resolve => setImmediate(resolve));
const settle = async () => { for (let i = 0; i < 6; i++) await tick(); };
let now = 0, nextTimer = 0;
const timers = new Map();
global.performance = { now: () => now };
const epoch = Date.now();
Date.now = () => epoch + now;
const storage = new Map();
global.sessionStorage = {
  getItem: key => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: key => storage.delete(key),
};
global.crypto = require('node:crypto').webcrypto;
global.setTimeout = (fn, ms) => { const id = ++nextTimer; timers.set(id, { fn, at: now + ms }); return id; };
global.setInterval = (fn, ms) => { const id = ++nextTimer; timers.set(id, { fn, at: now + ms, interval: ms }); return id; };
global.clearTimeout = global.clearInterval = id => timers.delete(id);
async function advance(ms) {
  const end = now + ms;
  while (true) {
    const due = [...timers].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
    if (!due) break;
    const [id, timer] = due; now = timer.at;
    if (timer.interval) timer.at += timer.interval; else timers.delete(id);
    timer.fn(); await settle();
  }
  now = end;
}
class Channel {
  readyState = 'open'; bufferedAmount = 0; sent = [];
  close() { this.readyState = 'closed'; this.onclose?.(); }
  send(payload) { this.sent.push([...new Uint8Array(payload)]); }
}
class Peer {
  remoteDescription = null; candidates = []; offers = []; signalingState = 'stable'; connectionState = 'new';
  constructor(config) { Peer.last = this; this.config = config; }
  createDataChannel() { return new Channel(); }
  addTransceiver() {}
  async createOffer(options) { this.offers.push(options); return { type: 'offer', sdp: 'offer' }; }
  async setLocalDescription(value) { this.signalingState = value.type === 'rollback' ? 'stable' : 'have-local-offer'; }
  async setRemoteDescription(value) { await tick(); this.remoteDescription = value; this.signalingState = 'stable'; }
  async addIceCandidate(value) { assert.ok(this.remoteDescription); this.candidates.push(value); }
  state(value) { this.connectionState = value; this.onconnectionstatechange?.(); }
  close() { this.state('closed'); }
}
class Socket {
  static OPEN = 1; readyState = 1; messages = []; autoPong = true;
  constructor() { Socket.last = this; }
  send(text) {
    const msg = JSON.parse(text); this.messages.push(msg);
    if (msg.type === 'ping' && this.autoPong) this.receive({ type: 'pong' });
  }
  close() { this.readyState = 3; this.onclose?.({ code: 1000 }); }
  drop(code = 1006) { this.readyState = 3; this.onclose?.({ code }); }
  receive(msg) { this.onmessage({ data: JSON.stringify(msg) }); }
}
global.RTCPeerConnection = Peer; global.WebSocket = Socket;
global.MediaStream = class { addTrack() {} };
const options = { signalingUrl: 'ws://localhost:8080', sessionToken: 'a'.repeat(32), iceServers: [] };
function makeClient() {
  const states = [], errors = [], diagnostics = [], events = [];
  const client = new ScrcpyClient({ onStateChange: s => { states.push(s); events.push(s); },
    onError: e => { errors.push(e); events.push(e); }, onDiagnostic: s => diagnostics.push(s),
    onVideoTrack() {}, onDataChannelOpen() {} });
  return { client, states, errors, diagnostics, events };
}
async function connect(client) {
  await client.connect(options);
  const ws = Socket.last, pc = Peer.last;
  ws.onopen(); assert.equal(ws.messages[0].type, 'auth');
  ws.receive({ type: 'ready', resumeToken: 'b'.repeat(64), resumed: false }); await settle();
  return { ws, pc };
}
async function answer(ws, pc) {
  ws.receive({ type: 'answer', sdp: 'answer' }); await settle(); pc.state('connected');
}
(async () => {
  const { client, states, diagnostics } = makeClient();
  const { ws, pc } = await connect(client);
  assert.deepEqual(ws.messages.map(x => x.type), ['auth', 'offer']);
  ws.receive({ type: 'ice', sdpMid: '0', sdpMLineIndex: 0, candidate: 'early' });
  ws.receive({ type: 'answer', sdp: 'answer' });
  ws.receive({ type: 'ice', sdpMid: '0', sdpMLineIndex: 0, candidate: 'later' });
  await settle(); pc.state('connected');
  assert.deepEqual(pc.candidates.map(x => x.candidate), ['early', 'later']);
  assert.ok(diagnostics.some(s => s.includes('Answer 已应用')));
  assert.equal(states.at(-1), 'connected');

  // A transient failure must not close either transport. Restart ICE on the same PC.
  pc.state('disconnected'); assert.equal(states.at(-1), 'reconnecting');
  pc.state('failed'); await advance(6000);
  assert.equal(client.peerConnection, pc); assert.equal(ws.readyState, Socket.OPEN);
  assert.equal(pc.offers.at(-1).iceRestart, true);
  const offerCount = pc.offers.length;
  await advance(6000); assert.equal(pc.offers.length, offerCount); // Delayed answer must not race a replacement offer.
  await answer(ws, pc); assert.equal(states.at(-1), 'connected');

  // Resume a lost signaling socket using a separate secret, never replay the login token.
  ws.drop(); assert.equal(states.at(-1), 'reconnecting');
  assert.equal(client.peerConnection, pc);
  await advance(3000);
  const resumed = Socket.last; assert.notEqual(resumed, ws);
  resumed.onopen(); assert.equal(resumed.messages[0].type, 'resume');
  assert.equal(resumed.messages[0].token, 'b'.repeat(64));
  assert.equal(resumed.messages[0].peerId, ws.messages[0].peerId);
  resumed.receive({ type: 'ready', resumeToken: 'b'.repeat(64), resumed: true }); await settle();
  assert.equal(pc.offers.at(-1).iceRestart, true);
  ws.receive({ type: 'answer', sdp: 'stale' }); ws.onclose({ code: 1008 });
  await answer(resumed, pc); assert.equal(states.at(-1), 'connected');

  // Detect a half-open socket without a browser close event, and resume it too.
  resumed.autoPong = false;
  await advance(8000); assert.equal(states.at(-1), 'reconnecting');
  assert.equal(client.peerConnection, pc);
  await advance(3000);
  const third = Socket.last; assert.notEqual(third, resumed);
  third.onopen(); third.receive({ type: 'ready', resumeToken: 'b'.repeat(64), resumed: true });
  await settle(); await answer(third, pc); assert.equal(states.at(-1), 'connected');

  // Backpressure sheds move events but queues DOWN/UP in order and keeps video alive.
  const channel = client.dataChannel;
  channel.bufferedAmount = 262144;
  assert.equal(client.sendControlMessage(new Uint8Array([2, 2])), false);
  assert.equal(client.sendControlMessage(new Uint8Array([0, 0])), true);
  assert.equal(client.sendControlMessage(new Uint8Array([0, 1])), true);
  assert.equal(client.peerConnection, pc); assert.equal(channel.sent.length, 0);
  channel.bufferedAmount = 0; channel.onbufferedamountlow();
  assert.deepEqual(channel.sent, [[0, 0], [0, 1]]);

  // Relay validation and stale callbacks remain safe.
  const oldDiagnosticCount = diagnostics.length;
  await client.connect({ ...options, iceTransportPolicy: 'relay' });
  assert.equal(client.peerConnection, null); assert.equal(states.at(-1), 'failed');
  pc.oniceconnectionstatechange(); assert.equal(diagnostics.length, oldDiagnosticCount);
  assert.equal(third.messages.at(-1).type, 'bye');
  await client.connect({ ...options, iceTransportPolicy: 'relay', iceServers: [{ urls: 'turn:example.test:3478' }] });
  assert.equal(Peer.last.config.iceTransportPolicy, 'relay');
  client.close(); assert.equal(timers.size, 0);

  // Permanent loss is bounded, reports the actual error last, and cancels all timers.
  const expired = makeClient(); const e = await connect(expired.client); await answer(e.ws, e.pc);
  e.pc.state('disconnected');
  await advance(30000);
  assert.equal(expired.client.peerConnection, null);
  assert.match(expired.events.at(-1), /网络恢复超时/);
  assert.equal(timers.size, 0);

  // Authentication rejection is terminal, not an infinite reconnect loop.
  const rejected = makeClient(); const r = await connect(rejected.client); await answer(r.ws, r.pc);
  r.ws.drop(1008); assert.equal(rejected.client.peerConnection, null);
  assert.match(rejected.errors.at(-1), /认证或协商被拒绝/);
  assert.equal(timers.size, 0);

  // No ready response means no resume secret; do not replay a consumed login token.
  const unauthenticated = makeClient(); await unauthenticated.client.connect(options);
  await advance(5000); assert.equal(unauthenticated.client.peerConnection, null);
  assert.equal(timers.size, 0);
  // A page reload keeps the tab's resume secret, but must create a new native peer.
  const beforeReload = makeClient();
  const first = await connect(beforeReload.client); await answer(first.ws, first.pc);
  assert.equal(storage.size, 1);
  assert.ok(![...storage.values()][0].includes(options.sessionToken));
  beforeReload.client.suspend();
  assert.notEqual(first.ws.messages.at(-1).type, 'bye');
  assert.equal(timers.size, 0); assert.equal(storage.size, 1);
  const afterReload = makeClient();
  await afterReload.client.connect({ ...options, sessionToken: '' });
  const reloadSocket = Socket.last, reloadPeer = Peer.last;
  reloadSocket.onopen();
  assert.equal(reloadSocket.messages[0].type, 'resume');
  assert.notEqual(reloadSocket.messages[0].peerId, first.ws.messages[0].peerId);
  reloadSocket.receive({ type: 'ready', resumed: true, resumeToken: 'b'.repeat(64), peerId: reloadSocket.messages[0].peerId });
  await settle(); await answer(reloadSocket, reloadPeer);
  assert.equal(afterReload.states.at(-1), 'connected');
  afterReload.client.close();
  assert.equal(reloadSocket.messages.at(-1).type, 'bye');
  assert.equal(storage.size, 0); assert.equal(timers.size, 0);

  // Missing peer-reset support must be reported, rather than authenticating then black-screening.
  const legacy = makeClient(); const legacyFirst = await connect(legacy.client); await answer(legacyFirst.ws, legacyFirst.pc);
  legacy.client.suspend();
  const reloadLegacy = makeClient(); await reloadLegacy.client.connect({ ...options, sessionToken: '' });
  const legacySocket = Socket.last; legacySocket.onopen();
  legacySocket.receive({ type: 'ready', resumed: true, resumeToken: 'b'.repeat(64) }); await settle();
  assert.match(reloadLegacy.errors.at(-1), /服务端不支持页面刷新恢复/);
  assert.equal(timers.size, 0); assert.equal(storage.size, 0);

  // Expired tab credentials are not used, and cannot make a consumed token reusable.
  const stale = makeClient(); const staleFirst = await connect(stale.client); await answer(staleFirst.ws, staleFirst.pc);
  stale.client.suspend(); await advance(45001);
  const expiredReload = makeClient(); await expiredReload.client.connect({ ...options, sessionToken: '' });
  assert.equal(expiredReload.client.peerConnection, null); assert.equal(storage.size, 0);

  // New server session: an explicitly entered login token gets one fallback attempt.
  const oldSession = makeClient(); const old = await connect(oldSession.client); await answer(old.ws, old.pc);
  oldSession.client.suspend();
  const newSession = makeClient(); await newSession.client.connect({ ...options, sessionToken: 'c'.repeat(32) });
  const rejectedResume = Socket.last; rejectedResume.onopen(); rejectedResume.drop(1008);
  const authSocket = Socket.last; assert.notEqual(authSocket, rejectedResume);
  authSocket.onopen(); assert.equal(authSocket.messages[0].type, 'auth');
  assert.equal(authSocket.messages[0].token, 'c'.repeat(32));
  authSocket.drop(1008); assert.equal(newSession.client.peerConnection, null);
  assert.equal(timers.size, 0);

  // Storage policy failures do not break first-time connections or leak timers.
  global.sessionStorage = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); },
    removeItem() { throw new Error('blocked'); } };
  const privateTab = makeClient(); const privateConnection = await connect(privateTab.client);
  await answer(privateConnection.ws, privateConnection.pc); assert.equal(privateTab.states.at(-1), 'connected');
  privateTab.client.close(); assert.equal(timers.size, 0);
  console.log('Browser signaling, weak-network recovery and backpressure regressions passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
