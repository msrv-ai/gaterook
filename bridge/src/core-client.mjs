// The broker's way to the core: POST /internal/bridge/* signed with the launch secret (ARCHITECTURE.md §6
// "Authentication"): X-Gateway-Auth: <ts>.<nonce>.<mac>, mac = base64url(HMAC-SHA256(secret, METHOD|target|ts|nonce)),
// a fresh nonce per request; the answer must carry this core's X-Gateway-Instance.

import crypto from 'node:crypto';
import http from 'node:http';

/** X-Gateway-Auth for one request; the reference is the core's platform.LaunchSecret.sign. */
export function signRequest(launchSecret, method, target, ts = Date.now(), nonce = crypto.randomBytes(16)
  .toString('base64url')) {
  const key = Buffer.from(launchSecret, 'base64url');
  const mac = crypto.createHmac('sha256', key).update(`${method}|${target}|${ts}|${nonce}`).digest('base64url');
  return `${ts}.${nonce}.${mac}`;
}

/**
 * A client of the core's /internal routes for the broker.
 *
 * @param {object} options
 * @param {string} options.origin        http://127.0.0.1:<port> of the core
 * @param {string} options.launchSecret  base64url, the secret Electron gave the core in its bootstrap line
 * @param {string} [options.instanceNonce] the core's instance nonce; answers without it are refused
 * @param {number} [options.timeoutMs]   per request, default 10 s
 * @returns {{request(path: string, body: object): Promise<object>}}
 */
export function createCoreClient({ origin, launchSecret, instanceNonce, timeoutMs = 10_000 }) {
  const base = new URL(origin);
  if (base.protocol !== 'http:' || base.hostname !== '127.0.0.1') {
    throw new Error('The core listens on http://127.0.0.1 only');
  }
  if (!launchSecret) throw new Error('A launch secret is needed to talk to the core');
  return {
    request(path, body) {
      const payload = Buffer.from(JSON.stringify(body), 'utf8');
      return new Promise((resolve, reject) => {
        // agent: false — straight to the core: the global agent may follow HTTP_PROXY (NODE_USE_ENV_PROXY), and the
        // answers carry bridge tokens (review K1-11).
        const req = http.request({
          host: '127.0.0.1', port: Number(base.port), path, method: 'POST', timeout: timeoutMs, agent: false,
          headers: {
            Host: `127.0.0.1:${base.port}`,
            'Content-Type': 'application/json',
            'Content-Length': payload.length,
            'X-Gateway-Auth': signRequest(launchSecret, 'POST', path),
          },
        }, (res) => {
          const chunks = [];
          res.on('data', (c) => chunks.push(c));
          res.on('end', () => {
            if (instanceNonce && res.headers['x-gateway-instance'] !== instanceNonce) {
              reject(Object.assign(new Error('Another program answers on the core\'s port'), { code: 'core_unavailable' }));
              return;
            }
            const text = Buffer.concat(chunks).toString('utf8');
            if (res.statusCode === 204) return resolve({});
            if (res.statusCode !== 200) {
              reject(Object.assign(new Error(`The core answered HTTP ${res.statusCode}`),
                { code: 'core_unavailable', status: res.statusCode, body: text }));
              return;
            }
            try {
              resolve(JSON.parse(text));
            } catch {
              reject(Object.assign(new Error('The core\'s answer isn\'t JSON'), { code: 'core_unavailable' }));
            }
          });
          res.on('error', reject);
        });
        req.on('timeout', () => req.destroy(Object.assign(new Error('The core didn\'t answer in time'),
          { code: 'core_unavailable' })));
        req.on('error', (e) => reject(Object.assign(e, { code: 'core_unavailable' })));
        req.end(payload);
      });
    },
  };
}
