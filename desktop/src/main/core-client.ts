// Main's own calls to its core: the `/internal/**` routes only main may use (file grants, the `.mcpb` path, the
// keyset's OS protection, what a notification showed) and the few `/api/**` reads and writes main makes itself
// (Deny from a notification, the tray's counters, General's switches). Signed with the launch secret like the proxy;
// an answer without this launch's X-Gateway-Instance is not from main's core and is refused.
import { request as httpRequest } from 'node:http';
import { randomBytes } from 'node:crypto';
import { signRequest } from '@gaterook/broker';
import type { CoreEndpoint } from './proxy.ts';

export class CoreCallError extends Error {
  readonly status: number;
  readonly body: unknown;

  constructor(message: string, status: number, body: unknown) {
    super(message);
    this.name = 'CoreCallError';
    this.status = status;
    this.body = body;
  }
}

export interface CoreAnswer<T> {
  readonly status: number;
  readonly body: T;
}

/**
 * One signed JSON request. Resolves with any status the core answered (the caller decides what a 409 means);
 * rejects when the core can't be reached or the answer isn't from it.
 */
export function callCore<T = unknown>(core: CoreEndpoint, method: string, path: string, body?: unknown,
    timeoutMs = 15_000): Promise<CoreAnswer<T>> {
  const payload = body === undefined ? null : Buffer.from(JSON.stringify(body), 'utf8');
  const headers: Record<string, string> = {
    host: `127.0.0.1:${core.port}`,
    accept: 'application/json',
    'x-gateway-auth': signRequest(core.launchSecret, method, path, Date.now(), randomBytes(16).toString('base64url')),
  };
  if (payload) {
    headers['content-type'] = 'application/json';
    headers['content-length'] = String(payload.length);
  }
  return new Promise((resolve, reject) => {
    const req = httpRequest({ host: '127.0.0.1', port: core.port, method, path, headers, agent: false, timeout: timeoutMs },
      (res) => {
        const chunks: Buffer[] = [];
        let size = 0;
        res.on('data', (c: Buffer) => {
          size += c.length;
          if (size > 8 * 1024 * 1024) res.destroy(new Error('answer too large'));
          else chunks.push(c);
        });
        res.on('error', (e) => reject(new CoreCallError(e.message, 0, null)));
        res.on('end', () => {
          const instance = res.headers['x-gateway-instance'];
          if (instance !== core.instanceNonce) {
            // The guard answers before it knows the caller, without the id; anything else isn't our core.
            reject(new CoreCallError(instance === undefined && (res.statusCode ?? 0) >= 400
              ? `The core refused the request (HTTP ${res.statusCode})` : 'Another program answers on the core\'s port',
            res.statusCode ?? 0, null));
            return;
          }
          const text = Buffer.concat(chunks).toString('utf8');
          let parsed: unknown = null;
          if (text) {
            try {
              parsed = JSON.parse(text);
            } catch {
              parsed = text;
            }
          }
          resolve({ status: res.statusCode ?? 0, body: parsed as T });
        });
      });
    req.on('timeout', () => req.destroy(new Error('The core did not answer in time')));
    req.on('error', (e) => reject(new CoreCallError(e.message, 0, null)));
    req.end(payload ?? undefined);
  });
}

/** Like {@link callCore}, but only a 2xx answer resolves; anything else rejects with the problem's title. */
export async function callCoreOk<T = unknown>(core: CoreEndpoint, method: string, path: string, body?: unknown,
    timeoutMs?: number): Promise<T> {
  const answer = await callCore<T>(core, method, path, body, timeoutMs);
  if (answer.status < 200 || answer.status >= 300) {
    const problem = answer.body as { title?: unknown; detail?: unknown } | null;
    const text = [problem?.title, problem?.detail].filter((s) => typeof s === 'string').join(': ');
    throw new CoreCallError(text || `The core answered HTTP ${answer.status}`, answer.status, answer.body);
  }
  return answer.body;
}
