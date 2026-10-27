import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../core/api/api-error';
import { ClientsApi, ClientsView } from '../../core/api/clients.api';
import { Onboarding, OverviewApi } from '../../core/api/overview.api';
import { MasterKeyApi, MasterKeyStatus } from '../../core/api/secrets.api';
import { EventStream } from '../../core/events/event-stream';
import { backedUpKey, clients, masterKey, onboarding } from './onboarding.fixtures';
import { LATER_KEY, OnboardingData } from './onboarding.data';

/** Welcome's data against fake API services: what it reads, what it tolerates, what it writes. */

class FakeEvents {
  readonly handlers = new Map<string, Set<(data: unknown) => void>>();
  on(type: string, h: (data: unknown) => void): () => void {
    if (!this.handlers.has(type)) this.handlers.set(type, new Set());
    this.handlers.get(type)!.add(h);
    return () => this.handlers.get(type)!.delete(h);
  }
  emit(type: string, data: unknown): void {
    this.handlers.get(type)?.forEach((h) => h(data));
  }
}

const problem = (status: number, body: object) => ApiError.fromHttp(new HttpErrorResponse({ status, error: body }));
const notAvailable = () => ApiError.fromHttp(new HttpErrorResponse({ status: 404, error: { status: 404, error: 'Not Found' } }));
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

let calls: string[];
let onboardingAnswer: () => Promise<Onboarding>;
let keyAnswer: () => Promise<MasterKeyStatus>;
let clientsAnswer: () => Promise<ClientsView>;
let events: FakeEvents;

function setup(): OnboardingData {
  calls = [];
  events = new FakeEvents();
  onboardingAnswer = () => Promise.resolve(onboarding());
  keyAnswer = () => Promise.resolve(masterKey());
  clientsAnswer = () => Promise.resolve(clients());
  const overview: Partial<OverviewApi> = {
    onboarding: () => {
      calls.push('GET /api/onboarding');
      return onboardingAnswer();
    },
    skipOnboarding: () => {
      calls.push('POST /api/onboarding/skip');
      return Promise.resolve({ skipped: true });
    },
  };
  const key: Partial<MasterKeyApi> = {
    status: () => {
      calls.push('GET /api/master-key');
      return keyAnswer();
    },
    backup: (saveToken, passphrase) => {
      calls.push(`POST /api/master-key/backup ${saveToken} ${passphrase ?? 'none'}`);
      keyAnswer = () => Promise.resolve(backedUpKey());
      onboardingAnswer = () => Promise.resolve(onboarding({ 'master-key': { lastBackupAt: '2026-10-22T12:29:59Z' } }));
      return Promise.resolve({ path: '/Users/you/Downloads/k.json', at: '2026-10-22T12:30:00Z', keyIds: [1], passphrase: !!passphrase, warnings: [] });
    },
  };
  const clientsApi: Partial<ClientsApi> = {
    list: () => {
      calls.push('GET /api/clients');
      return clientsAnswer();
    },
  };
  TestBed.configureTestingModule({
    providers: [
      OnboardingData,
      { provide: OverviewApi, useValue: overview },
      { provide: MasterKeyApi, useValue: key },
      { provide: ClientsApi, useValue: clientsApi },
      { provide: EventStream, useValue: events },
    ],
  });
  return TestBed.inject(OnboardingData);
}

beforeEach(() => sessionStorage.clear());

describe('OnboardingData', () => {
  it('reads the onboarding answer, the master key and the clients together', async () => {
    const data = setup();
    await data.start();
    expect(calls.sort()).toEqual(['GET /api/clients', 'GET /api/master-key', 'GET /api/onboarding']);
    expect(data.facts()).toEqual({ onboarding: onboarding(), key: masterKey(), clients: clients() });
    expect(data.error()).toBeNull();
  });

  it('shows the onboarding without the parts that failed, and says so for the key', async () => {
    const data = setup();
    keyAnswer = () => Promise.reject(problem(500, { title: 'Boom', detail: 'The key file is unreadable.' }));
    clientsAnswer = () => Promise.reject(notAvailable());
    await data.start();
    expect(data.facts()).toEqual({ onboarding: onboarding(), key: null, clients: null });
    expect((data.keyError() as ApiError).detail).toBe('The key file is unreadable.');
  });

  it('keeps the last key status and clients when a refresh of them fails', async () => {
    const data = setup();
    await data.start();
    keyAnswer = () => Promise.reject(problem(500, { detail: 'later failure' }));
    clientsAnswer = () => Promise.reject(problem(500, { detail: 'later failure' }));
    await data.refresh();
    expect(data.key()).toEqual(masterKey());
    expect(data.keyError()).toBeNull();
    expect(data.clients()).toEqual(clients());
  });

  it('is an error before anything loaded, and stale after', async () => {
    const data = setup();
    onboardingAnswer = () => Promise.reject(problem(503, { title: 'Locked', detail: 'Locked.' }));
    await data.start();
    expect(data.facts()).toBeNull();
    expect((data.error() as ApiError).status).toBe(503);

    onboardingAnswer = () => Promise.resolve(onboarding());
    await data.refresh();
    expect(data.facts()).not.toBeNull();
    expect(data.error()).toBeNull();

    onboardingAnswer = () => Promise.reject(problem(500, { detail: 'Gone' }));
    await data.refresh();
    expect(data.facts()).not.toBeNull();
    expect((data.stale() as ApiError).detail).toBe('Gone');
  });

  it('coalesces refreshes: one in flight, one more after it', async () => {
    const data = setup();
    let release!: () => void;
    onboardingAnswer = () => new Promise((res) => (release = () => res(onboarding())));
    const first = data.start();
    void data.refresh();
    void data.refresh();
    release();
    await first;
    await sleep(0);
    release();
    await sleep(0);
    expect(calls.filter((c) => c === 'GET /api/onboarding').length).toBe(2);
  });

  it('reads the clients again when K1 reports a change on this computer', async () => {
    vi.useFakeTimers();
    try {
      const data = setup();
      await data.start();
      calls = [];
      clientsAnswer = () => Promise.resolve(clients(['cursor']));
      events.emit('clients.drift', { client: 'cursor' });
      events.emit('clients.drift', { client: 'codex' });
      await vi.advanceTimersByTimeAsync(600);
      expect(calls).toEqual(['GET /api/clients']);
      expect(data.clients()?.clients.find((c) => c.client === 'cursor')?.status).toBe('connected');
      data.stop();
      events.emit('clients.drift', { client: 'cursor' });
      await vi.advanceTimersByTimeAsync(600);
      expect(calls).toEqual(['GET /api/clients']);
    } finally {
      vi.useRealTimers();
    }
  });

  it('remembers a put-off step for this window only, and forgets it once the backup exists', async () => {
    const data = setup();
    await data.start();
    data.putOff('master-key');
    expect(data.later().has('master-key')).toBe(true);
    expect(JSON.parse(sessionStorage.getItem(LATER_KEY)!)).toEqual(['master-key']);

    // Another page instance in the same window (Welcome left and opened again) reads it back.
    TestBed.resetTestingModule();
    const again = setup();
    expect(again.later().has('master-key')).toBe(true);

    await again.start();
    const result = await again.backup('ft_token', 'correct horse');
    expect(result.passphrase).toBe(true);
    expect(calls).toContain('POST /api/master-key/backup ft_token correct horse');
    expect(again.later().size).toBe(0);
    expect(sessionStorage.getItem(LATER_KEY)).toBeNull();
    // Read again after the copy exists: the key and the step say so.
    expect(again.key()?.lastBackupAt).not.toBeNull();
    expect(again.onboarding()?.gettingStarted.steps[0].state).toBe('done');
  });

  it('ignores a session entry it doesn\'t understand', () => {
    sessionStorage.setItem(LATER_KEY, '{not json');
    expect(setup().later().size).toBe(0);
    TestBed.resetTestingModule();
    sessionStorage.setItem(LATER_KEY, JSON.stringify(['master-key', 'nonsense']));
    expect([...setup().later()]).toEqual(['master-key']);
  });

  it('sends no passphrase when none was typed, and skips setup', async () => {
    const data = setup();
    await data.start();
    await data.backup('ft_token', null);
    expect(calls).toContain('POST /api/master-key/backup ft_token none');
    await data.skip();
    expect(calls).toContain('POST /api/onboarding/skip');
  });
});
