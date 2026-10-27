// The helper is shipped with the app. Requests use private pipes, never command-line arguments or the renderer.
import { spawn, type ChildProcess } from 'node:child_process';
import { FileKeyAccessError } from './file-key.ts';

export type NativeRequest = { operation: 'check' | 'verify' | 'get' | 'store' | 'create'; account?: string; key?: string; windowHandle?: string };
export interface NativeReply { ok: true; available?: boolean; touchId?: boolean; key?: string }
export type NativeCall = (request: NativeRequest) => Promise<NativeReply>;

export class NativeUnlockError extends FileKeyAccessError {
  readonly code: string;
  constructor(code: string) {
    super(`Native unlock ${code}`);
    this.code = code;
  }
}

const ERROR_CODES = new Set(['cancelled', 'denied', 'unavailable', 'missing', 'exists', 'invalid-request', 'failed']);
const LIMIT = 4096;

/** Decode only the fixed protocol; never propagate native output into logs or an IPC error. */
export function nativeReply(bytes: Buffer): NativeReply {
  try {
    if (bytes.length > LIMIT) throw new Error();
    const value: unknown = JSON.parse(bytes.toString('utf8'));
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error();
    const reply = value as Record<string, unknown>;
    if (reply['ok'] === false) {
      throw new NativeUnlockError(typeof reply['error'] === 'string' && ERROR_CODES.has(reply['error']) ? reply['error'] : 'failed');
    }
    if (reply['ok'] !== true || (reply['available'] !== undefined && typeof reply['available'] !== 'boolean')
        || (reply['touchId'] !== undefined && typeof reply['touchId'] !== 'boolean')
        || (reply['key'] !== undefined && typeof reply['key'] !== 'string')) throw new Error();
    return { ok: true, available: reply['available'] as boolean | undefined,
      touchId: reply['touchId'] as boolean | undefined, key: reply['key'] as string | undefined };
  } catch (error) {
    if (error instanceof NativeUnlockError) throw error;
    throw new NativeUnlockError('failed');
  }
}

export class NativeUnlockClient {
  private readonly children = new Set<ChildProcess>();
  private closed = false;
  private readonly file: string;
  constructor(file: string) { this.file = file; }

  call: NativeCall = (request) => {
    if (this.closed) return Promise.reject(new NativeUnlockError('cancelled'));
    const input = Buffer.from(JSON.stringify(request));
    if (input.length > LIMIT) {
      input.fill(0);
      return Promise.reject(new NativeUnlockError('invalid-request'));
    }
    return new Promise((resolve, reject) => {
      const child = spawn(this.file, [], { stdio: ['pipe', 'pipe', 'ignore'], windowsHide: true });
      this.children.add(child);
      const chunks: Buffer[] = [];
      let size = 0;
      let done = false;
      const finish = (error?: NativeUnlockError) => {
        if (done) return;
        done = true;
        if (error) child.kill();
        clearTimeout(timer);
        this.children.delete(child);
        input.fill(0);
        const output = Buffer.concat(chunks);
        try {
          if (error) reject(error);
          else resolve(nativeReply(output));
        } catch (failure) { reject(failure); }
        finally { output.fill(0); for (const chunk of chunks) chunk.fill(0); }
      };
      const timer = setTimeout(() => { child.kill(); finish(new NativeUnlockError('unavailable')); },
        request.operation === 'check' ? 10_000 : 180_000);
      child.on('error', () => finish(new NativeUnlockError('unavailable')));
      child.stdin.on('error', () => finish(new NativeUnlockError('failed')));
      child.stdout.on('data', (chunk: Buffer) => {
        if (done) { chunk.fill(0); return; }
        size += chunk.length;
        if (size > LIMIT) { chunk.fill(0); child.kill(); finish(new NativeUnlockError('failed')); }
        else chunks.push(chunk);
      });
      child.on('close', (code) => finish(code === 0 ? undefined : new NativeUnlockError('failed')));
      child.stdin.end(input);
    });
  };

  close(): void {
    this.closed = true;
    for (const child of this.children) child.kill();
  }
}
