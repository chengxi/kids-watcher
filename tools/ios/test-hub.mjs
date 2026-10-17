// Disposable loopback fixture for the iOS simulator. Never uses deployed data.
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import { createDeps } from '../../services/hub/dist/server.js';
import { createServer as createHttpsServer } from 'node:https';
import { buildHttp } from '../../services/hub/dist/http.js';
import { Registry } from '../../services/hub/dist/ws/registry.js';
import { Relay } from '../../services/hub/dist/ws/relay.js';
import { attachWs } from '../../services/hub/dist/ws/server.js';
import { Watchdog } from '../../services/hub/dist/ws/watchdog.js';
import WebSocket from 'ws';
import { mintToken } from '../../services/hub/dist/auth.js';
import { encodeMediaFrame, MediaKind } from '../../packages/protocol/dist/binary.js';
import { NoiseRepo } from '../../services/hub/dist/db/noise.js';
const dir = mkdtempSync(join(tmpdir(), 'kidsmonitor-ios-test-'));
const deps = createDeps(dir);
// Reuse production handlers while binding both fixture ports to loopback only.
const registry = new Registry();
const app = buildHttp(deps, registry);
await app.ready();
const server = createHttpsServer({ cert: deps.tls.certPem, key: deps.tls.keyPem }, (req, res) => app.server.emit('request', req, res));
const sockets = attachWs(server, deps, registry, new Relay(registry));
const watchdog = new Watchdog(deps, registry, 30);
watchdog.start();
await new Promise(resolve => server.listen(18555, '127.0.0.1', resolve));
const sidecar = createServer((req, res) => {
  res.setHeader('Content-Type', 'application/json');
  if (req.url === '/fixture' || req.url === '/fixture?role=parent') {
    res.end(JSON.stringify({ host: '127.0.0.1', port: 18555, fingerprint: deps.tls.fingerprintSha256, code: deps.pairing.issue(req.url.includes('role=parent') ? 'parent' : 'monitor') }));
  } else if (req.url?.startsWith('/voice?')) {
    const monitorId = new URL(req.url, 'http://localhost').searchParams.get('monitor');
    if (!monitorId || !registry.isOnline(monitorId)) { res.writeHead(404); res.end('{}'); return; }
    const token = mintToken();
    deps.devices.create('parent', 'iOS test voice sender', token);
    const parent = new WebSocket('wss://127.0.0.1:18555/ws?role=parent', {
      rejectUnauthorized: false, headers: { Authorization: `Bearer ${token}` },
    });
    parent.on('error', () => {});
    parent.on('open', () => {
      parent.send(JSON.stringify({ t: 'hello', deviceName: 'iOS test voice sender', appVersion: 'test' }));
      parent.send(JSON.stringify({ t: 'request_stream', requestId: 'voice-test-stream', monitorId, video: false, audio: true }));
    });
    parent.on('message', (raw, binary) => {
      if (binary) return;
      const msg = JSON.parse(raw.toString());
      if (msg.t === 'stream_ready') parent.send(JSON.stringify({ t: 'start_talk', streamId: msg.streamId, requestId: 'voice-test-talk' }));
      if (msg.t === 'talk_state' && msg.ready) {
        const pcm = new Uint8Array(640);
        pcm.set([0x34, 0x12, 0xfe, 0xff]);
        parent.send(encodeMediaFrame({ kind: MediaKind.Pcm16, streamId: msg.talkId, timestampUs: 0n, payload: pcm }));
        setTimeout(() => parent.close(), 500);
      }
    });
    setTimeout(() => parent.terminate(), 10000).unref();
    res.end('{"ok":true}');
  } else if (req.url === '/snapshot') {
    res.end(JSON.stringify({ monitors: deps.devices.listByRole('monitor').map(d => ({ id: d.id, name: d.name, online: registry.isOnline(d.id), lastSeenMs: d.lastSeenMs, samples: new NoiseRepo(deps.db).between(d.id, Date.now() - 60000, Date.now()).length })) }));
  } else { res.writeHead(404); res.end('{}'); }
});
sidecar.listen(18556, '127.0.0.1', () => console.log('Disposable iOS fixture ready on loopback ports 18555/18556'));
async function close() {
  watchdog.stop();
  for (const client of sockets.clients) client.terminate();
  await new Promise(resolve => server.close(resolve));
  sockets.close();
  await app.close();
  sidecar.close();
  deps.db.close();
  rmSync(dir, { recursive: true, force: true });
  process.exit(0);
}
process.on('SIGINT', close); process.on('SIGTERM', close);
