'use strict';

/**
 * Quick Voice signaling server.
 *
 * A minimal, dependency-light WebSocket broker used by the Android app for:
 *   - WebRTC call signaling (call / answer / offer / ice / hangup / decline)
 *   - presence checks (presence / offline)
 *   - store-and-forward voice messages when the recipient is offline
 *
 * The Android client expects this exact message vocabulary (see
 * core/voip/src/main/java/com/quickvoice/core/voip/signaling/SignalingClient.kt).
 *
 * Run:  npm install && npm start
 * The app should be pointed at  ws://<this-host>:8080/signaling  in Settings.
 */

const { WebSocketServer, WebSocket } = require('ws');
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8080);
const MAX_MAILBOX_PER_USER = 20;
const RELEASES_DIR = path.join(__dirname, 'releases');
const VERSION_FILE = path.join(RELEASES_DIR, 'version.json');

/** userId -> WebSocket of the online user. A user can only have one connection. */
const clients = new Map();
/** userId -> displayName */
const displayNames = new Map();
/** userId -> array of undelivered { from, fromName, media, durationMs, mime } messages */
const offlineMailbox = new Map();

function send(ws, payload) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    try {
      ws.send(JSON.stringify(payload));
    } catch (_) {
      /* ignore: peer vanished mid-send */
    }
  }
}

function isOnline(userId) {
  const ws = clients.get(userId);
  return ws !== undefined && ws.readyState === WebSocket.OPEN;
}

/**
 * Friendly 7-digit number (e.g. "1234567") assigned to clients that register
 * without their own id, instead of a random hex string. Skips ids that are
 * already taken by an online client.
 */
function nextUserId() {
  for (let i = 0; i < 100; i++) {
    const id = String(Math.floor(1000000 + Math.random() * 9000000));
    if (!clients.has(id)) return id;
  }
  return String(1000000 + (Date.now() % 9000000));
}

function enqueue(userId, message) {
  const queue = offlineMailbox.get(userId) || [];
  queue.push(message);
  while (queue.length > MAX_MAILBOX_PER_USER) queue.shift();
  offlineMailbox.set(userId, queue);
}

function deliverMailbox(ws, userId) {
  const queue = offlineMailbox.get(userId);
  if (!queue || queue.length === 0) return;
  for (const item of queue) {
    send(ws, {
      type: 'voicemessage',
      from: item.from,
      fromName: item.fromName,
      to: userId,
      media: item.media,
      durationMs: item.durationMs,
      mime: item.mime,
      ts: item.ts,
    });
  }
  offlineMailbox.delete(userId);
}

/** Binds a userId to its socket, evicting any previous connection. */
function attach(ws, userId, displayName) {
  const old = clients.get(userId);
  if (old && old !== ws && old.readyState === WebSocket.OPEN) {
    send(old, { type: 'serverError', message: 'Signed in from another device' });
    old.close(4001, 'replaced');
  }
  clients.set(userId, ws);
  displayNames.set(userId, displayName || userId);
  ws.userId = userId;
}

// ------------------------------------------------------------------ HTTP server
// Serves the update metadata + APK downloads on the same port as the signaling
// WebSocket so the app can self-update from the same host it already talks to.

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const remote = req.socket.remoteAddress || '?';
  console.log(`[http] ${new Date().toISOString()} ${remote} ${req.method} ${url.pathname}`);

  // Older app builds append the update paths to the signaling URL
  // (e.g. /signaling/api/version). Tolerate any prefix by matching the tail.
  let pathname = url.pathname;
  if (pathname.endsWith('/api/version')) pathname = '/api/version';
  const apkIdx = pathname.lastIndexOf('/apk/');
  if (apkIdx >= 0) pathname = pathname.slice(apkIdx);

  if ((req.method === 'GET' || req.method === 'HEAD') && pathname === '/api/version') {
    if (!fs.existsSync(VERSION_FILE)) {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'No release published yet' }));
      return;
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    if (req.method === 'HEAD') res.end();
    else fs.createReadStream(VERSION_FILE).pipe(res);
    return;
  }

  if ((req.method === 'GET' || req.method === 'HEAD') && pathname.startsWith('/apk/')) {
    const name = path.basename(pathname);
    const file = path.join(RELEASES_DIR, name);
    if (!file.startsWith(RELEASES_DIR) || !fs.existsSync(file)) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('Not found');
      return;
    }
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': fs.statSync(file).size,
    });
    if (req.method === 'HEAD') res.end();
    else fs.createReadStream(file).pipe(res);
    return;
  }

  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({
    service: 'quickvoice-signaling',
    online: clients.size,
    endpoints: ['/signaling', '/api/version', '/apk/<file>'],
  }));
});

const wss = new WebSocketServer({ server, path: '/signaling' });

wss.on('connection', (ws) => {
  ws.userId = null;
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch (_) {
      return;
    }
    handle(ws, msg);
  });

  ws.on('close', () => {
    if (ws.userId && clients.get(ws.userId) === ws) {
      clients.delete(ws.userId);
    }
  });

  ws.on('error', () => { /* handled by close */ });
});

function handle(ws, msg) {
  switch (msg.type) {
    // ---------------------------------------------------------- registration
    case 'register': {
      let userId = String(msg.userId || '').trim();
      if (!userId) userId = nextUserId();
      const displayName = String(msg.displayName || '').trim() || userId;
      attach(ws, userId, displayName);
      send(ws, { type: 'registered', userId, displayName });
      deliverMailbox(ws, userId);
      break;
    }

    // -------------------------------------------------------------- signaling
    case 'call': {
      const target = msg.to;
      if (!isOnline(target)) {
        send(ws, { type: 'offline', peerId: target });
        break;
      }
      send(clients.get(target), {
        type: 'incoming',
        callId: msg.callId,
        from: ws.userId,
        fromName: displayNames.get(ws.userId) || ws.userId,
        sdp: msg.sdp,
        mode: msg.mode || 'call',
      });
      break;
    }

    case 'answer':
    case 'offer':
    case 'hangup':
    case 'decline':
    case 'ice': {
      if (!isOnline(msg.to)) break;
      const relay = {
        type: msg.type,
        callId: msg.callId,
      };
      if (msg.type === 'ice') {
        relay.sdpMid = msg.sdpMid;
        relay.sdpMLineIndex = msg.sdpMLineIndex;
        relay.candidate = msg.candidate;
      } else if (msg.type === 'answer' || msg.type === 'offer') {
        relay.sdp = msg.sdp;
      }
      send(clients.get(msg.to), relay);
      break;
    }

    // ------------------------------------------------------- voice messages
    case 'voicemessage': {
      const payload = {
        type: 'voicemessage',
        from: ws.userId,
        fromName: displayNames.get(ws.userId) || ws.userId,
        to: msg.to,
        media: msg.media,
        durationMs: msg.durationMs || 0,
        mime: msg.mime || 'audio/3gpp',
        ts: Date.now(),
      };
      if (isOnline(msg.to)) {
        send(clients.get(msg.to), payload);
      } else {
        enqueue(msg.to, payload);
      }
      break;
    }

    // -------------------------------------------------------------- presence
    case 'presence': {
      if (!isOnline(msg.to)) {
        send(ws, { type: 'offline', peerId: msg.to });
      }
      break;
    }

    default:
      send(ws, { type: 'serverError', message: `Unknown message type: ${msg.type}` });
  }
}

// Keep-alive + zombie cleanup every 30s.
setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) {
      ws.terminate();
      return;
    }
    ws.isAlive = false;
    ws.ping();
  });
}, 30_000);

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Quick Voice signaling server listening on ws://0.0.0.0:${PORT}/signaling`);
  console.log(`Update endpoint:  /api/version and /apk/<file> from ${RELEASES_DIR}`);
});
