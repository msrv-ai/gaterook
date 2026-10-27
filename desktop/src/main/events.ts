// Main's own reader of the core's event stream (ARCHITECTURE.md §7.1 "SSE /api/events (fetch-reader) →
// Notification / toast in the renderer / badge / tray", A16): desktop notifications, the badge and the tray follow the
// core even while the window is closed. One stream per core launch, signed like every admin request, checked to come
// from main's own core; reconnects with Last-Event-ID (the core replays its last 512 events) until the core goes.
import { request as httpRequest, type ClientRequest } from 'node:http';
import { randomBytes } from 'node:crypto';
import { signRequest } from '@gaterook/broker';
import type { CoreEndpoint } from './proxy.ts';
import { SseParser } from './sse.ts';

export interface CoreEvent {
  readonly type: string;
  readonly data: unknown;
  readonly id: string | null;
}

export interface EventFeedOptions {
  readonly onEvent: (e: CoreEvent) => void;
  /** The stream opened (again): a moment to reconcile what may have been missed. */
  readonly onOpen?: () => void;
  readonly log?: (line: string) => void;
  /** First retry delay; the server's `retry:` replaces it. */
  readonly retryMs?: number;
  readonly maxRetryMs?: number;
  /** No byte (event or heartbeat) for this long means a frozen core or a half-open socket: reconnect. */
  readonly silenceMs?: number;
}

export class CoreEventFeed {
  private readonly options: EventFeedOptions;
  private endpoint: CoreEndpoint | null = null;
  private current: ClientRequest | null = null;
  private timer: NodeJS.Timeout | null = null;
  private lastEventId: string | null = null;
  private retryMs: number;
  private failures = 0;
  private generation = 0;

  constructor(options: EventFeedOptions) {
    this.options = options;
    this.retryMs = options.retryMs ?? 2000;
  }

  /** Follows the stream of this core launch; a new launch starts over without the old Last-Event-ID. */
  start(endpoint: CoreEndpoint): void {
    this.stop();
    this.endpoint = endpoint;
    this.lastEventId = null;
    this.failures = 0;
    this.retryMs = this.options.retryMs ?? 2000;
    const generation = ++this.generation;
    this.connect(generation);
  }

  stop(): void {
    this.generation++;
    this.endpoint = null;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.current?.destroy();
    this.current = null;
  }

  private connect(generation: number): void {
    const core = this.endpoint;
    if (!core || generation !== this.generation) return;
    const target = '/api/events';
    const headers: Record<string, string> = {
      host: `127.0.0.1:${core.port}`,
      accept: 'text/event-stream',
      'x-gateway-auth': signRequest(core.launchSecret, 'GET', target, Date.now(), randomBytes(16).toString('base64url')),
    };
    if (this.lastEventId) headers['last-event-id'] = this.lastEventId;
    const silenceMs = this.options.silenceMs ?? 45_000;
    // One retry per connection, whichever of error and close comes first.
    let ended = false;
    const end = (failed: boolean): void => {
      if (ended) return;
      ended = true;
      this.retry(generation, failed);
    };
    const req = httpRequest({ host: '127.0.0.1', port: core.port, path: target, method: 'GET', headers, agent: false,
      timeout: silenceMs }, (res) => {
        if (res.statusCode !== 200 || res.headers['x-gateway-instance'] !== core.instanceNonce) {
          this.options.log?.(`event stream refused: HTTP ${res.statusCode ?? 0}${res.headers['x-gateway-instance'] === core.instanceNonce ? '' : ', not our core'}`);
          res.destroy();
          end(true);
          return;
        }
        this.failures = 0;
        this.options.onOpen?.();
        const parser = new SseParser((m) => {
          let data: unknown = m.data;
          try {
            data = JSON.parse(m.data);
          } catch {
            // Plain text data: passed on as is.
          }
          try {
            this.options.onEvent({ type: m.event, data, id: m.id });
          } catch (e) {
            this.options.log?.(`event ${m.event} handler failed: ${(e as Error).message}`);
          }
        }, (ms) => (this.retryMs = Math.min(Math.max(ms, 250), 30_000)));
        res.setEncoding('utf8');
        res.socket.setTimeout(silenceMs, () => res.destroy(new Error('no heartbeat')));
        res.on('data', (chunk: string) => {
          parser.push(chunk);
          this.lastEventId = parser.lastEventId ?? this.lastEventId;
        });
        res.on('close', () => end(false));
        res.on('error', () => undefined);
      });
    // A core that accepts the connection but never answers is as good as gone: try again later.
    req.on('timeout', () => req.destroy(new Error('no answer')));
    req.on('error', () => end(true));
    req.end();
    this.current = req;
  }

  private retry(generation: number, failed: boolean): void {
    if (generation !== this.generation || this.timer) return;
    if (failed) this.failures++;
    const max = this.options.maxRetryMs ?? 30_000;
    const delay = Math.min(max, this.retryMs * 2 ** Math.min(this.failures, 4));
    this.timer = setTimeout(() => {
      this.timer = null;
      this.connect(generation);
    }, delay);
    this.timer.unref?.();
  }
}
