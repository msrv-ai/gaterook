import { DestroyRef, Injectable, InjectionToken, inject, signal } from '@angular/core';
import { DesktopBridge } from '../desktop/desktop-bridge';
import { GatewayEventType, GatewayEvents } from './gateway-events';
import { SseParser } from './sse-parser';

/** The fetch the stream uses (a test replaces it). */
export const EVENT_STREAM_FETCH = new InjectionToken<typeof fetch>('EVENT_STREAM_FETCH', {
  factory: () => (input, init) => globalThis.fetch(input, init),
});

/** Where the stream lives: `/api/events`, proxied with the launch signature by Electron main or `ng serve`. */
export const EVENT_STREAM_URL = '/api/events';

type Handler = (data: unknown, id: string | null) => void;

/**
 * The core writes a heartbeat comment every 15 s (`gateway.events.heartbeat`); three missed ones, or a connect that
 * hangs as long, mean a frozen core or a half-open connection — a dead stream that would otherwise look alive.
 */
export const STREAM_SILENCE_MS = 45_000;

/**
 * The core's server-sent events (ARCHITECTURE.md §6, §8.1 core/events): one fetch-reader of `/api/events` for the
 * whole app, so every store reacts to the same stream. On a dropped stream it reconnects after the server's
 * `retry:` (2 s), backing off to 30 s while the core is away, and resends `Last-Event-ID`: the core replays the last
 * 512 events, so nothing is missed across a short outage. In Electron, if streaming through the `app://` protocol
 * is not possible (UNVERIFIED in §8.1), it switches once and for good to the events main relays over IPC.
 */
@Injectable({ providedIn: 'root' })
export class EventStream {
  private readonly fetchFn = inject(EVENT_STREAM_FETCH);
  private readonly desktop = inject(DesktopBridge);
  private readonly handlers = new Map<string, Set<Handler>>();
  private readonly anyHandlers = new Set<(type: string, data: unknown) => void>();
  private abort: AbortController | null = null;
  private running = false;
  private retryMs = 2000;
  private failures = 0;
  private lastEventId: string | null = null;
  private ipcStop: (() => void) | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;

  /** Whether the stream is open right now (the sidebar's core status doesn't depend on it; Live in Activity does). */
  readonly connected = signal(false);
  /**
   * Counts the times the stream opened again after it had been open and dropped. A drop can be a restart of the
   * core: the shell asks `/api/health` then, and a new core instance gets a fresh stream ({@link restart}).
   */
  readonly reopened = signal(0);
  /**
   * Failed attempts in a row to (re)open the stream (0 once it is open). The shell asks `/api/health` on each: in a
   * browser nothing else notices a core that went away after start.
   */
  readonly failing = signal(0);
  private everOpen = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  /** Subscribes to one event type; returns the unsubscribe function. */
  on<K extends GatewayEventType>(type: K, handler: (data: GatewayEvents[K], id: string | null) => void): () => void {
    let set = this.handlers.get(type);
    if (!set) {
      set = new Set();
      this.handlers.set(type, set);
    }
    const h = handler as Handler;
    set.add(h);
    return () => set.delete(h);
  }

  /** Every event, whatever its type (diagnostics, the dev gallery). */
  onAny(handler: (type: string, data: unknown) => void): () => void {
    this.anyHandlers.add(handler);
    return () => this.anyHandlers.delete(handler);
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    void this.connect();
  }

  /**
   * A new core instance answers: its event ids start again, so the old `Last-Event-ID` means nothing to it (it could
   * replay the wrong events or none). Drops the id and opens a fresh stream.
   */
  restart(): void {
    this.stop();
    this.lastEventId = null;
    this.failures = 0;
    this.failing.set(0);
    this.retryMs = 2000;
    this.everOpen = false;
    this.start();
  }

  stop(): void {
    this.running = false;
    this.abort?.abort();
    this.abort = null;
    this.ipcStop?.();
    this.ipcStop = null;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.connected.set(false);
  }

  /** Delivers one event to its handlers (also used by the IPC fallback). */
  emit(type: string, data: unknown, id: string | null = null): void {
    for (const h of this.handlers.get(type) ?? []) {
      try {
        h(data, id);
      } catch (e) {
        console.error(`Handler of ${type} failed`, e);
      }
    }
    for (const h of this.anyHandlers) h(type, data);
  }

  private async connect(): Promise<void> {
    if (!this.running) return;
    const abort = new AbortController();
    this.abort = abort;
    // Re-armed on every chunk (events and heartbeats); firing counts as a failed attempt, unlike stop().
    let silent = false;
    let watchdog: ReturnType<typeof setTimeout> | null = null;
    let reader: ReadableStreamDefaultReader<string> | null = null;
    const alive = () => {
      if (watchdog) clearTimeout(watchdog);
      watchdog = setTimeout(() => {
        silent = true;
        abort.abort();
        // A body not tied to the signal (a proxy, a test double) still ends: the read loop sees done.
        void reader?.cancel().catch(() => undefined);
      }, STREAM_SILENCE_MS);
    };
    alive();
    try {
      const headers: Record<string, string> = { Accept: 'text/event-stream' };
      if (this.lastEventId) headers['Last-Event-ID'] = this.lastEventId;
      const response = await this.fetchFn(EVENT_STREAM_URL, { headers, signal: abort.signal, cache: 'no-store' });
      if (!response.ok || !response.body) {
        if (response.ok && !response.body && this.useIpc()) return;
        throw new Error(`events: HTTP ${response.status}`);
      }
      this.connected.set(true);
      this.failures = 0;
      this.failing.set(0);
      if (this.everOpen) this.reopened.update((n) => n + 1);
      this.everOpen = true;
      const parser = new SseParser(
        (m) => {
          this.emit(m.event, parse(m.data), m.id);
        },
        (ms) => (this.retryMs = ms),
      );
      reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        alive();
        parser.push(value);
        this.lastEventId = parser.lastEventId ?? this.lastEventId;
      }
      if (silent) throw new Error('events: no heartbeat');
    } catch (e) {
      if (abort.signal.aborted && !silent) return;
      if (e instanceof TypeError && this.desktop.available && this.failures > 2 && this.useIpc()) return;
      this.failures += 1;
      this.failing.set(this.failures);
    } finally {
      if (watchdog) clearTimeout(watchdog);
      if (this.abort === abort) this.connected.set(false);
    }
    this.schedule();
  }

  private schedule(): void {
    if (!this.running) return;
    const delay = this.failures === 0 ? this.retryMs : Math.min(30_000, this.retryMs * 2 ** Math.min(this.failures, 4));
    this.timer = setTimeout(() => void this.connect(), delay);
  }

  /** Falls back to the events Electron main relays (it reads `/api/events` itself for notifications). */
  private useIpc(): boolean {
    const stop = this.desktop.onEvent((type, data, id) => this.emit(type, data, id));
    if (!stop) return false;
    this.ipcStop = stop;
    this.connected.set(true);
    return true;
  }
}

function parse(data: string): unknown {
  try {
    return JSON.parse(data);
  } catch {
    return data;
  }
}
