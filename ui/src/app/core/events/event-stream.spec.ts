import { TestBed } from '@angular/core/testing';
import { EVENT_STREAM_FETCH, EventStream, STREAM_SILENCE_MS } from './event-stream';

/** A fetch whose every call opens a stream the test writes to and ends. */
function fakeServer() {
  const calls: { headers: Record<string, string>; push: (text: string) => void; end: () => void }[] = [];
  const fetchFn = ((_url: string, init: RequestInit) => {
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const body = new ReadableStream<Uint8Array>({ start: (c) => (controller = c) });
    const enc = new TextEncoder();
    calls.push({
      headers: { ...(init.headers as Record<string, string>) },
      push: (text) => controller.enqueue(enc.encode(text)),
      end: () => controller.close(),
    });
    return Promise.resolve(new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));
  }) as unknown as typeof fetch;
  return { calls, fetchFn };
}

const tick = () => new Promise((r) => setTimeout(r, 5));

describe('EventStream across core restarts', () => {
  it('counts a stream that opened again, and restart() drops the old Last-Event-ID', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const server = fakeServer();
    TestBed.configureTestingModule({ providers: [{ provide: EVENT_STREAM_FETCH, useValue: server.fetchFn }] });
    const events = TestBed.inject(EventStream);
    const seen: unknown[] = [];
    events.on('approval.requested', (d) => seen.push(d));

    events.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(server.calls.length).toBe(1);
    server.calls[0].push(': stream open\nretry: 2000\n\nid: 41\nevent: approval.requested\ndata: {"id":"ap_aaaaaaaaaaaa"}\n\n');
    await vi.advanceTimersByTimeAsync(0);
    expect(seen).toEqual([{ id: 'ap_aaaaaaaaaaaa' }]);
    expect(events.reopened()).toBe(0);

    // The stream drops (the core went away); it reconnects after retry with the last id.
    server.calls[0].end();
    await vi.advanceTimersByTimeAsync(2_000);
    expect(server.calls.length).toBe(2);
    expect(server.calls[1].headers['Last-Event-ID']).toBe('41');
    expect(events.reopened()).toBe(1);

    // Another core instance: a fresh stream, without the id the new core can't know.
    events.restart();
    await vi.advanceTimersByTimeAsync(0);
    expect(server.calls.length).toBe(3);
    expect(server.calls[2].headers['Last-Event-ID']).toBeUndefined();
    expect(events.reopened()).toBe(1);
    events.stop();
    vi.useRealTimers();
    await tick();
  });
});

describe('EventStream watchdog', () => {
  it('a stream silent for three heartbeats is dropped, counted as failing, and opened again', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const server = fakeServer();
    TestBed.configureTestingModule({ providers: [{ provide: EVENT_STREAM_FETCH, useValue: server.fetchFn }] });
    const events = TestBed.inject(EventStream);
    events.start();
    await vi.advanceTimersByTimeAsync(0);
    server.calls[0].push(': stream open\nretry: 2000\n\n');
    await vi.advanceTimersByTimeAsync(STREAM_SILENCE_MS - 1_000);
    // A heartbeat keeps it alive.
    server.calls[0].push(': heartbeat\n\n');
    await vi.advanceTimersByTimeAsync(STREAM_SILENCE_MS - 1_000);
    expect(events.connected()).toBe(true);
    expect(events.failing()).toBe(0);
    // Then nothing: a frozen core.
    await vi.advanceTimersByTimeAsync(1_000);
    expect(events.connected()).toBe(false);
    expect(events.failing()).toBe(1);
    await vi.advanceTimersByTimeAsync(4_000);
    expect(server.calls.length).toBe(2);
    events.stop();
    vi.useRealTimers();
    await tick();
  });

  it('a connect that never answers fails after the same silence', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    let attempts = 0;
    const hanging = ((_url: string, init: RequestInit) => {
      attempts += 1;
      return new Promise<Response>((_, reject) => init.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))));
    }) as unknown as typeof fetch;
    TestBed.configureTestingModule({ providers: [{ provide: EVENT_STREAM_FETCH, useValue: hanging }] });
    const events = TestBed.inject(EventStream);
    events.start();
    await vi.advanceTimersByTimeAsync(STREAM_SILENCE_MS);
    expect(events.failing()).toBe(1);
    expect(attempts).toBe(1);
    // stop() is not a failure.
    events.stop();
    await vi.advanceTimersByTimeAsync(STREAM_SILENCE_MS * 2);
    expect(events.failing()).toBe(1);
    vi.useRealTimers();
    await tick();
  });
});
