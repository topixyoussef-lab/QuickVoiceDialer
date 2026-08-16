'use strict';
const { WebSocket } = require('ws');

const URL = 'ws://127.0.0.1:8080/signaling';
const a = new WebSocket(URL);
const b = new WebSocket(URL);

function waitOpen(ws) {
  return new Promise((res, rej) => {
    ws.once('open', res);
    ws.once('error', rej);
  });
}
function waitMsg(ws, pred) {
  return new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('timeout waiting for ' + pred)), 4000);
    ws.on('message', (raw) => {
      const m = JSON.parse(raw.toString());
      if (pred(m)) { clearTimeout(t); res(m); }
    });
  });
}

(async () => {
  await Promise.all([waitOpen(a), waitOpen(b)]);

  const registeredA = waitMsg(a, (m) => m.type === 'registered');
  a.send(JSON.stringify({ type: 'register', userId: '', displayName: 'Alice' }));
  const regA = await registeredA;
  const userIdA = regA.userId;
  if (!userIdA) throw new Error('server did not assign userId');

  const registeredB = waitMsg(b, (m) => m.type === 'registered');
  b.send(JSON.stringify({ type: 'register', userId: 'bob', displayName: 'Bob' }));
  const regB = await registeredB;
  if (regB.userId !== 'bob') throw new Error('register with explicit id failed');

  // call from A -> B should arrive as "incoming"
  const incoming = waitMsg(b, (m) => m.type === 'incoming');
  a.send(JSON.stringify({ type: 'call', to: 'bob', callId: 'c1', sdp: 'FAKE_SDP' }));
  const inc = await incoming;
  if (inc.from !== userIdA || inc.fromName !== 'Alice' || inc.callId !== 'c1') {
    throw new Error('incoming relay mismatch: ' + JSON.stringify(inc));
  }

  // answer B -> A
  const answerAtA = waitMsg(a, (m) => m.type === 'answer');
  b.send(JSON.stringify({ type: 'answer', to: userIdA, callId: 'c1', sdp: 'ANSWER_SDP' }));
  const ans = await answerAtA;
  if (ans.sdp !== 'ANSWER_SDP') throw new Error('answer relay failed');

  // ice relay
  const iceAtA = waitMsg(a, (m) => m.type === 'ice');
  b.send(JSON.stringify({ type: 'ice', to: userIdA, callId: 'c1', sdpMid: '0', sdpMLineIndex: 0, candidate: 'cand' }));
  const ice = await iceAtA;
  if (ice.candidate !== 'cand') throw new Error('ice relay failed');

  // voice message B -> A
  const vmAtA = waitMsg(a, (m) => m.type === 'voicemessage');
  b.send(JSON.stringify({ type: 'voicemessage', to: userIdA, media: 'AAAA', durationMs: 1200, mime: 'audio/3gpp' }));
  const vm = await vmAtA;
  if (vm.media !== 'AAAA' || vm.durationMs !== 1200) throw new Error('voice message relay failed');

  // call to offline user -> offline event
  const offline = waitMsg(a, (m) => m.type === 'offline');
  a.send(JSON.stringify({ type: 'call', to: 'nobody', callId: 'c2', sdp: 'x' }));
  const off = await offline;
  if (off.peerId !== 'nobody') throw new Error('offline event failed');

  // voice message to an offline user is stored, then delivered on register
  const c = new WebSocket(URL);
  await waitOpen(c);
  const registeredC = waitMsg(c, (m) => m.type === 'registered');
  c.send(JSON.stringify({ type: 'register', userId: 'carol', displayName: 'Carol' }));
  await registeredC;
  c.close();
  await new Promise((r) => setTimeout(r, 300)); // let server see the close

  a.send(JSON.stringify({ type: 'voicemessage', to: 'carol', media: 'BBBB', durationMs: 500, mime: 'audio/3gpp' }));

  const d = new WebSocket(URL);
  await waitOpen(d);
  const mailbox = waitMsg(d, (m) => m.type === 'voicemessage');
  d.send(JSON.stringify({ type: 'register', userId: 'carol', displayName: 'Carol' }));
  const vmDelivered = await mailbox;
  if (vmDelivered.media !== 'BBBB' || vmDelivered.from !== userIdA) {
    throw new Error('offline mailbox delivery failed: ' + JSON.stringify(vmDelivered));
  }
  d.close();

  console.log('SMOKE TEST OK: register, call relay, answer, ice, voicemessage, offline, mailbox all passed.');
  a.close(); b.close();
  process.exit(0);
})().catch((e) => { console.error('SMOKE TEST FAILED:', e.message); process.exit(1); });
