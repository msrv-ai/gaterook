// Test doubles: a fake core that checks the broker's signatures and answers /internal/bridge/* and /mcp/<slug>.

import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';

export const SECRET = Buffer.from('broker-test-launch-secret-32byte').toString('base64url');
export const INSTANCE = 'instance-nonce-of-the-test-core';

/**
 * A fresh gateway home under the OS temp dir (removed by the caller). On macOS that is /tmp: a Unix socket's name has
 * at most 103 bytes there, and /var/folders/…/T/<home>/run/<22>.sock is longer.
 */
export function tempHome(name) {
  return fs.mkdtempSync(path.join(process.platform === 'darwin' ? '/tmp' : os.tmpdir(), `gw-${name}-`));
}

/** The signature check of platform.LaunchSecret, re-implemented independently. */
export function verifySignature(header, method, target, secret = SECRET) {
  const [ts, nonce, mac] = String(header || '').split('.');
  if (!ts || !nonce || !mac) return false;
  const expected = crypto.createHmac('sha256', Buffer.from(secret, 'base64url'))
    .update(`${method}|${target}|${ts}|${nonce}`).digest('base64url');
  return mac.length === expected.length && crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(mac))
    && Math.abs(Date.now() - Number(ts)) < 30_000;
}

/**
 * A fake core. `tokens(req)` decides the answer to a token request; `mcp(req, auth)` the answer on /mcp/<slug>.
 * Records every request in `seen`.
 */
export async function fakeCore({ tokens, confirm, mcp, instance = INSTANCE, bridgeInstance = 'bridge-instance-1' } = {}) {
  const seen = [];
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (d) => { body += d; });
    req.on('end', async () => {
      const entry = { method: req.method, url: req.url, headers: req.headers, body: body ? JSON.parse(body) : null };
      seen.push(entry);
      if (req.url.startsWith('/internal/')) {
        if (!verifySignature(req.headers['x-gateway-auth'], req.method, req.url)) {
          res.writeHead(401, { 'Content-Length': 0 });
          return res.end();
        }
        if (req.url === '/internal/bridge/tokens') {
          const answer = await tokens(entry.body);
          res.writeHead(200, { 'Content-Type': 'application/json', 'X-Gateway-Instance': instance });
          return res.end(JSON.stringify(answer));
        }
        if (req.url === '/internal/bridge/confirm') {
          if (confirm) confirm(entry.body);
          res.writeHead(204, { 'X-Gateway-Instance': instance });
          return res.end();
        }
      }
      if (req.url.startsWith('/mcp/') && mcp) {
        const out = await mcp(entry);
        const headers = { ...(out.headers || {}) };
        if (bridgeInstance && !Object.keys(headers).some((k) => k.toLowerCase() === 'x-gateway-bridge-instance')) {
          headers['X-Gateway-Bridge-Instance'] = bridgeInstance;
        }
        if (out.type) headers['Content-Type'] = out.type;
        res.writeHead(out.status, headers);
        return res.end(out.body || '');
      }
      res.writeHead(404, { 'Content-Length': 0 });
      res.end();
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  return {
    port, seen, origin: `http://127.0.0.1:${port}`,
    close: () => new Promise((resolve) => { server.closeAllConnections(); server.close(() => resolve()); }),
  };
}

/** An answer of the core with a token for `slug`. */
export function issued(port, slug, n = 1, instance = 'bridge-instance-1') {
  return {
    v: 1, ok: true, token: `gwb_test_token_${n}`, expiresAt: new Date(Date.now() + 900_000).toISOString(),
    url: `http://127.0.0.1:${port}/mcp/${slug}`, instance,
  };
}

/** One JSON line exchange with a broker socket. */
export function exchange(address, line) {
  return import('node:net').then(({ default: net }) => new Promise((resolve, reject) => {
    const s = net.connect(address);
    let buf = '';
    s.setEncoding('utf8');
    s.on('connect', () => s.write(line));
    s.on('data', (d) => { buf += d; });
    s.on('end', () => resolve(buf));
    s.on('close', () => resolve(buf));
    s.on('error', reject);
  }));
}
