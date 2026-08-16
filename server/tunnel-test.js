const { WebSocket } = require('ws');
const url = process.argv[2] || 'wss://yukon-article-filled-reid.trycloudflare.com/signaling';
const ws = new WebSocket(url);
const to = setTimeout(() => { console.error('TIMEOUT connecting via tunnel'); process.exit(1); }, 15000);
ws.on('open', () => {
  console.log('OPEN via tunnel OK');
  ws.send(JSON.stringify({ type: 'register', userId: 'tunnel-test', displayName: 'Tunnel Test' }));
});
ws.on('message', (raw) => {
  const msg = JSON.parse(raw.toString());
  console.log('MSG:', msg.type, msg.userId || '');
  if (msg.type === 'registered') { clearTimeout(to); ws.close(); process.exit(0); }
});
ws.on('error', (e) => { console.error('WS ERROR:', e.message); process.exit(2); });
