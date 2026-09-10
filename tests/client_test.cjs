const assert = require('node:assert/strict');
const { ScrcpyClient } = require(process.argv[2]);
const tick = () => new Promise(resolve => setImmediate(resolve));
class Channel {
  readyState = 'open'; bufferedAmount = 0;
  close() { this.readyState = 'closed'; this.onclose?.(); }
  send() {}
}
class Peer {
  remoteDescription = null; candidates = [];
  constructor(config) { Peer.last = this; this.config = config; }
  async getStats() { return new Map(); }
  createDataChannel() { return new Channel(); }
  addTransceiver() {}
  async createOffer() { return { type: 'offer', sdp: 'offer' }; }
  async setLocalDescription() {}
  async setRemoteDescription(value) { await tick(); this.remoteDescription = value; }
  async addIceCandidate(value) { assert.ok(this.remoteDescription); this.candidates.push(value); }
  close() { this.connectionState = 'closed'; this.onconnectionstatechange?.(); }
}
class Socket {
  static OPEN = 1; readyState = 1; messages = [];
  constructor() { Socket.last = this; }
  send(text) { this.messages.push(JSON.parse(text)); }
  close() { this.readyState = 3; this.onclose?.(); }
  receive(msg) { this.onmessage({ data: JSON.stringify(msg) }); }
}
global.RTCPeerConnection = Peer; global.WebSocket = Socket;
global.MediaStream = class { addTrack() {} };
(async () => {
  const states = [], diagnostics = [];
  const client = new ScrcpyClient({onStateChange: s => states.push(s), onError() {}, onDiagnostic: s => diagnostics.push(s), onVideoTrack() {}, onDataChannelOpen() {}});
  const options = {signalingUrl: 'ws://localhost:8080', sessionToken: 'a'.repeat(32), iceServers: []};
  await client.connect(options);
  const ws = Socket.last, pc = Peer.last;
  ws.onopen(); assert.deepEqual(ws.messages.map(x => x.type), ['auth']);
  ws.receive({type: 'ready'}); await tick(); assert.deepEqual(ws.messages.map(x => x.type), ['auth', 'offer']);
  ws.receive({type: 'ice', sdpMid: '0', sdpMLineIndex: 0, candidate: 'early'});
  ws.receive({type: 'answer', sdp: 'answer'});
  ws.receive({type: 'ice', sdpMid: '0', sdpMLineIndex: 0, candidate: 'later'});
  await tick(); await tick(); await tick();
  assert.deepEqual(pc.candidates.map(x => x.candidate), ['early', 'later']);
  assert.ok(diagnostics.some(s => s.includes('Answer 已应用')));
  pc.connectionState = 'connected'; pc.onconnectionstatechange(); await tick();
  assert.equal(states.at(-1), 'connected');
  const oldDiagnosticCount = diagnostics.length;
  await client.connect({...options, iceTransportPolicy: 'relay'});
  assert.equal(client.peerConnection, null);
  assert.equal(states.at(-1), 'failed');
  pc.oniceconnectionstatechange();
  assert.equal(diagnostics.length, oldDiagnosticCount);
  await client.connect({...options, iceTransportPolicy: 'relay', iceServers: [{urls: 'turn:example.test:3478'}]});
  assert.equal(Peer.last.config.iceTransportPolicy, 'relay');
  const replacement = client.peerConnection;
  ws.receive({type: 'answer', sdp: 'stale'}); await tick(); assert.equal(client.peerConnection, replacement);
  client.dataChannel.bufferedAmount = 262144;
  assert.equal(client.sendControlMessage(new Uint8Array([1])), false);
  assert.equal(client.peerConnection, null); assert.equal(states.at(-1), 'failed');
  console.log('Browser signaling regressions passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
