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
  addTransceiver() { return { receiver: {} }; }
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
  drop(code = 1006, reason = 'Authentication rejected') { this.readyState = 3; this.onclose?.({ code, reason }); }
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
function ready(ws, resumed = false) {
  ws.receive({ type: 'ready', protocolVersion: 3, clientId: 'd'.repeat(32), roomId: 'default', resumeToken: 'b'.repeat(64), resumed });
}
async function connect(client) {
  await client.connect(options);
  const ws = Socket.last, pc = Peer.last;
  ws.onopen(); assert.equal(ws.messages[0].type, 'join');
  ready(ws); await settle();
  return { ws, pc };
}
async function answer(ws, pc) {
  ws.receive({ type: 'answer', sdp: 'answer' }); await settle(); pc.state('connected');
}
(async () => {
  const r = makeClient(); const { client, states } = r;
  const first = await connect(client); const { pc, ws } = first;
  assert.deepEqual(ws.messages.map(x => x.type), ['join', 'offer']);
  ws.receive({ type: 'ice', sdpMid: '0', sdpMLineIndex: 0, candidate: 'early' });
  await answer(ws, pc);
  assert.deepEqual(pc.candidates.map(x => x.candidate), ['early']);
  assert.equal(states.at(-1), 'connected');
  assert.ok(![...storage.values()][0].includes(options.sessionToken));

  // A media outage reuses the same peer and serializes ICE restart offers.
  pc.state('disconnected'); await advance(6000);
  assert.equal(states.at(-1), 'reconnecting');
  assert.equal(client.peerConnection, pc);
  assert.equal(pc.offers.at(-1).iceRestart, true);
  const pending = pc.offers.length; await advance(6000);
  assert.equal(pc.offers.length, pending);
  await answer(ws, pc);

  // Signal-only loss must retain healthy video. Resume requires its own secret and stable client id.
  const offersBeforeSignalLoss = pc.offers.length;
  ws.drop(); assert.equal(states.at(-1), 'reconnecting'); assert.equal(client.peerConnection, pc);
  await advance(3000);
  const resumed = Socket.last; resumed.onopen();
  assert.equal(resumed.messages[0].type, 'resume');
  assert.equal(resumed.messages[0].token, 'b'.repeat(64));
  assert.equal(resumed.messages[0].clientId, 'd'.repeat(32));
  assert.equal(resumed.messages[0].peerId, ws.messages[0].peerId);
  ready(resumed, true); await settle();
  assert.equal(states.at(-1), 'connected');
  assert.equal(pc.offers.length, offersBeforeSignalLoss); // No gratuitous renegotiation for healthy media.
  resumed.receive({ type: 'answer', sdp: 'cached-answer' }); await settle();
  assert.equal(pc.remoteDescription.sdp, 'answer'); // Duplicate cached answer is harmless.
  const diagnosticsBefore = r.diagnostics.length;
  ws.onclose({ code: 1008, reason: 'LOGIN_TOKEN_INVALID' });
  assert.equal(r.diagnostics.length, diagnosticsBefore);

  // Pending answer survives socket replacement instead of racing a new offer.
  pc.state('disconnected'); await advance(6000);
  const offerCount = pc.offers.length;
  resumed.drop(); await advance(3000);
  const pendingSocket = Socket.last; pendingSocket.onopen(); ready(pendingSocket, true); await settle();
  assert.equal(pc.offers.length, offerCount);
  await answer(pendingSocket, pc); assert.equal(states.at(-1), 'connected');

  // Heartbeat blackhole is recovered using the same bounded path.
  pendingSocket.autoPong = false; await advance(8000);
  assert.equal(states.at(-1), 'reconnecting'); assert.equal(client.peerConnection, pc);
  await advance(3000); const live = Socket.last; live.onopen(); ready(live, true); await settle();
  assert.equal(states.at(-1), 'connected');

  // Backpressure drops replaceable motion while preserving key ordering.
  const channel = client.dataChannel; channel.bufferedAmount = 262144;
  assert.equal(client.sendControlMessage(new Uint8Array([2, 2])), false);
  assert.equal(client.sendControlMessage(new Uint8Array([0, 0])), true);
  assert.equal(client.sendControlMessage(new Uint8Array([0, 1])), true);
  channel.bufferedAmount = 0; channel.onbufferedamountlow();
  assert.deepEqual(channel.sent, [[0, 0], [0, 1]]);

  // Reload persists only the recovery secret, changes media identity, and keeps the admitted peer identity.
  client.suspend(); assert.equal(storage.size, 1); assert.equal(timers.size, 0);
  const reload = makeClient(); await reload.client.connect({ ...options, sessionToken: '' });
  const reloadSocket = Socket.last; reloadSocket.onopen();
  assert.equal(reloadSocket.messages[0].type, 'resume');
  assert.equal(reloadSocket.messages[0].clientId, 'd'.repeat(32));
  assert.notEqual(reloadSocket.messages[0].peerId, ws.messages[0].peerId);
  ready(reloadSocket, true); await settle(); await answer(reloadSocket, Peer.last);
  reload.client.close(); assert.equal(storage.size, 0); assert.equal(timers.size, 0);
  assert.equal(reloadSocket.messages.at(-1).type, 'bye');

  // Explicit new credentials do not get silently replaced by a cached recovery attempt.
  const old = makeClient(); await connect(old.client); old.client.suspend();
  const fresh = makeClient(); await fresh.client.connect({ ...options, sessionToken: 'c'.repeat(32) });
  Socket.last.onopen(); assert.equal(Socket.last.messages[0].type, 'join');
  fresh.client.close(); storage.clear();

  // Recovery stays bounded even when replacement sockets never authenticate.
  const expired = makeClient(); const e = await connect(expired.client); await answer(e.ws, e.pc);
  e.ws.drop(); await advance(30000);
  assert.equal(expired.client.peerConnection, null);
  assert.equal(expired.states.at(-1), 'failed'); assert.match(expired.errors.at(-1), /恢复超时/);
  assert.equal(timers.size, 0);

  // Production browser entry requires WSS; localhost remains available for adb/debugging.
  const invalid = makeClient(); await invalid.client.connect({ ...options, signalingUrl: 'ws://example.com/signal' });
  assert.equal(invalid.client.peerConnection, null); assert.match(invalid.errors.at(-1), /wss/);
  await invalid.client.connect({ ...options, iceTransportPolicy: 'relay' });
  assert.equal(invalid.client.peerConnection, null);
  const incompatible = makeClient(); await incompatible.client.connect(options); Socket.last.onopen();
  Socket.last.receive({ type: 'ready', protocolVersion: 2 }); await settle();
  assert.equal(incompatible.client.peerConnection, null); assert.match(incompatible.errors.at(-1), /协议不兼容/);

  // Storage restrictions cannot break ordinary in-memory reconnection.
  global.sessionStorage = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); },
    removeItem() { throw new Error('blocked'); } };
  const privateMode = makeClient(); const p = await connect(privateMode.client); await answer(p.ws, p.pc);
  p.ws.drop(); await advance(3000); const privateSocket = Socket.last; privateSocket.onopen();
  assert.equal(privateSocket.messages[0].type, 'resume'); ready(privateSocket, true); await settle();
  assert.equal(privateMode.client.peerConnection, p.pc); privateMode.client.close();
  assert.equal(timers.size, 0);
  console.log('PASS client v3: negotiation, bounded recovery, reload identity, input backpressure, protocol and transport policy');
})().catch(error => { console.error(error); process.exitCode = 1; });
