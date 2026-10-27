// The renderer's way to the core (ARCHITECTURE.md §6 "Authentication", A21): `app://gw/api/*` becomes a request to
// http://127.0.0.1:<port>/api/* that main signs with the launch secret — the renderer never sees the secret, so a
// script in the page (or a web page, or another program) can't sign anything. Main sends it with Node's http client,
// not Chromium's network stack: nothing is added that main didn't put there (no Origin, cookies, Sec-Fetch-*, proxy).
//
// The answer must come from main's own core: it carries the launch's X-Gateway-Instance, or the renderer gets a
// network error instead of whatever answered — a program squatting the port gets signatures it can't reuse (single-
// use nonce, ±30 s), and the renderer never shows its answers.
import { request as httpRequest, type IncomingMessage } from 'node:http';
import { randomBytes } from 'node:crypto';
import { Readable } from 'node:stream';
import { signRequest } from '@mcp-gateway/broker';

/** The core main started and that said ready: where it listens and the secrets of its launch. */
export interface CoreEndpoint {
  readonly port: number;
  readonly launchSecret: string;
  readonly instanceNonce: string;
}

/** Request headers the renderer may set; everything else is dropped (main adds Host and the signature). */
const FORWARDED_REQUEST_HEADERS: readonly string[] = ['accept', 'accept-language', 'content-type', 'last-event-id'];

/** Response headers that describe the connection, not the answer. */
const HOP_BY_HOP = new Set([
  'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer', 'transfer-encoding',
  'upgrade', 'content-length', 'set-cookie', 'set-cookie2',
]);

/** Statuses the core's guard answers before it knows the caller (no instance header, no body). */
const GUARD_STATUSES = new Set([401, 403, 404, 405, 415, 421]);

/** The biggest body the renderer may send (a plugin JAR is the largest). */
export const MAX_BODY_BYTES = 256 * 1024 * 1024;

export interface ProxyOptions {
  /** Socket idle time before the request is dropped: longer than the event stream's 15 s heartbeat. */
  readonly idleTimeoutMs?: number;
  readonly now?: () => number;
  readonly nonce?: () => string;
  /** The biggest body sent on (tests); {@link MAX_BODY_BYTES} otherwise. */
  readonly maxBodyBytes?: number;
}

/** The answers after which a locked core may be unlocked: Restore or Create of the master key went through. */
const UNLOCKING = new Set(['/api/master-key/restore', '/api/master-key/create']);

/**
 * Whether the renderer's request, with the answer it got, may have unlocked the core (ARCHITECTURE.md §7.1: Restore
 * and Create take the locked mode away without a restart) — main then asks `/api/health` again.
 */
export function mayUnlock(method: string, target: string, status: number): boolean {
  const path = target.split('?', 1)[0] ?? '';
  return method.toUpperCase() === 'POST' && UNLOCKING.has(path) && status >= 200 && status < 300;
}

/**
 * Sends one renderer request to the core and returns the core's answer. `target` is the raw path and query the app
 * scheme routed (`/api/…?…`), signed exactly as sent. Without a core that is ready, or when the answer isn't from it,
 * the renderer gets a network error (`Response.error()`), which the UI reads as "the core isn't answering".
 */
export async function proxyToCore(req: Request, target: string, core: CoreEndpoint | null,
    options: ProxyOptions = {}): Promise<Response> {
  if (!core) return Response.error();
  const method = req.method.toUpperCase();
  let body: Buffer | null = null;
  if (method !== 'GET' && method !== 'HEAD') {
    const max = options.maxBodyBytes ?? MAX_BODY_BYTES;
    const declared = Number(req.headers.get('content-length') ?? '0');
    if (declared > max) return new Response(null, { status: 413 });
    // Read with a running count: a streamed body says no length up front.
    const read = await readBody(req, max);
    if (read === 'too-large') return new Response(null, { status: 413 });
    body = read;
  }
  const headers: Record<string, string> = {};
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = req.headers.get(name);
    if (value !== null) headers[name] = value;
  }
  const now = options.now ?? Date.now;
  const nonce = options.nonce ?? (() => randomBytes(16).toString('base64url'));
  headers['host'] = `127.0.0.1:${core.port}`;
  headers['x-gateway-auth'] = signRequest(core.launchSecret, method, target, now(), nonce());
  if (body) headers['content-length'] = String(body.length);

  let res: IncomingMessage;
  try {
    res = await send(core.port, method, target, headers, body, options.idleTimeoutMs ?? 120_000);
  } catch {
    return Response.error();
  }
  const status = res.statusCode ?? 502;
  const instance = res.headers['x-gateway-instance'];
  if (instance !== core.instanceNonce) {
    res.destroy();
    // The guard refuses before it knows who asks, without our instance id and without a body: pass the status on.
    // Anything else that doesn't carry our id isn't our core.
    return instance === undefined && GUARD_STATUSES.has(status) ? new Response(null, { status }) : Response.error();
  }
  const out = new Headers();
  for (const [name, value] of Object.entries(res.headers)) {
    if (value === undefined || HOP_BY_HOP.has(name) || name === 'content-security-policy') continue;
    for (const v of Array.isArray(value) ? value : [value]) out.append(name, v);
  }
  // Data for fetch, never a page: should anything load an answer as a document, it runs nothing.
  out.set('Content-Security-Policy', "default-src 'none'; frame-ancestors 'none'; sandbox");
  out.set('X-Content-Type-Options', 'nosniff');
  const empty = method === 'HEAD' || status === 204 || status === 205 || status === 304;
  if (empty) res.resume();
  return new Response(empty ? null : (Readable.toWeb(res) as ReadableStream<Uint8Array>), {
    status,
    statusText: res.statusMessage ?? '',
    headers: out,
  });
}

/** The request's body, at most `max` bytes (null when empty), or 'too-large' as soon as it passes that. */
async function readBody(req: Request, max: number): Promise<Buffer | null | 'too-large'> {
  if (!req.body) return null;
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > max) {
      await reader.cancel().catch(() => undefined);
      return 'too-large';
    }
    chunks.push(value);
  }
  return size > 0 ? Buffer.concat(chunks) : null;
}

function send(port: number, method: string, path: string, headers: Record<string, string>, body: Buffer | null,
    idleTimeoutMs: number): Promise<IncomingMessage> {
  return new Promise((resolve, reject) => {
    const req = httpRequest({ host: '127.0.0.1', port, method, path, headers, agent: false, timeout: idleTimeoutMs },
      (res) => {
        // After the answer began, an idle socket (no heartbeat) ends the stream rather than the request.
        res.socket.setTimeout(idleTimeoutMs, () => res.destroy(new Error('idle')));
        resolve(res);
      });
    req.on('timeout', () => req.destroy(new Error('The core did not answer in time')));
    req.on('error', reject);
    req.end(body ?? undefined);
  });
}
