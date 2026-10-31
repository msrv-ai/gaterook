// The dev server's proxy to the core (ng serve, ARCHITECTURE.md §8.1): what Electron's main process does for the
// renderer in the app (desktop/main/protocol.ts), so the UI talks to a real core in development.
//
// Every /api request of the dev UI itself leaves here as main would send it: signed with the launch secret
// (X-Gateway-Auth: <ts>.<nonce>.<HMAC-SHA256(secret, METHOD|path?query|ts|nonce)>, base64url without padding, a fresh
// nonce each time, LocalGuardFilter in the core), Host set to the core's 127.0.0.1:<port>, and without the browser's
// Origin, Referer, Cookie and Sec-Fetch-* headers (the core refuses Origin and cross-site fetch metadata: a renderer
// request must look like main's net.fetch). /internal/** is never proxied, as in the app.
//
// Only the dev UI's own requests are signed: a request another origin's page makes to this port (its Origin isn't
// this server, or its fetch metadata says cross-site or same-site) is refused here with 403, since the signature
// would otherwise vouch for it, and a preflight (OPTIONS) is never signed. A request without any browser metadata
// (curl, another local program) is proxied as is: this is a development server on the loopback, not an admin
// boundary.
//
// Environment (ui/scripts/dev.mjs sets both): GW_CORE_PORT (default 7465), GW_DEV_INTERNAL_SECRET (the secret the
// core was started with). Without a secret nothing is signed and the core answers 401.
import { createHmac, randomBytes } from 'node:crypto';

const port = Number(process.env.GW_CORE_PORT ?? 7465);
const secret = process.env.GW_DEV_INTERNAL_SECRET ?? '';
const key = secret ? Buffer.from(secret, 'base64url') : null;

if (!key) console.warn('[proxy] GW_DEV_INTERNAL_SECRET is not set: /api requests go unsigned and the core answers 401');

/** The header value for one request (same algorithm as README "sign()" and Electron main). */
export function sign(method, target, now = Date.now(), nonce = randomBytes(16).toString('base64url')) {
  const mac = createHmac('sha256', key).update(`${method}|${target}|${now}|${nonce}`).digest('base64url');
  return `${now}.${nonce}.${mac}`;
}

const STRIP = ['origin', 'referer', 'cookie'];

/** Whether a browser made this request from a page that isn't the dev UI served by this very server. */
export function crossOrigin(headers) {
  const origin = headers.origin;
  const host = headers.host;
  if (origin && origin !== `http://${host}` && origin !== `https://${host}`) return true;
  const site = headers['sec-fetch-site'];
  return site === 'cross-site' || site === 'same-site';
}

export default {
  '/api': {
    target: `http://127.0.0.1:${port}`,
    changeOrigin: true,
    secure: false,
    ws: false,
    bypass(req, res) {
      if (crossOrigin(req.headers) || req.method === 'OPTIONS') {
        res.statusCode = 403;
        res.setHeader('content-type', 'text/plain; charset=utf-8');
        res.end('The dev server signs only the dev UI\'s own requests to the core.');
        return false;
      }
      return undefined;
    },
    configure(proxy) {
      proxy.on('proxyReq', (proxyReq, req) => {
        for (const h of STRIP) proxyReq.removeHeader(h);
        for (const h of Object.keys(req.headers)) if (h.startsWith('sec-fetch-')) proxyReq.removeHeader(h);
        if (key && req.method !== 'OPTIONS' && !crossOrigin(req.headers)) {
          proxyReq.setHeader('X-Gateway-Auth', sign(req.method ?? 'GET', proxyReq.path));
        }
      });
    },
  },
};
