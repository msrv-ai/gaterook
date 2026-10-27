import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../core/api/api-error';
import { ConnectionsApi } from '../../core/api/connections.api';
import { MasterKeyApi, MasterKeyStatus, SecretRow, SecretStore, SecretsApi } from '../../core/api/secrets.api';
import { EventStream } from '../../core/events/event-stream';
import { kitMasterKey, kitSecrets, kitShortLived, kitStores } from './secrets.fixtures';
import { SecretsData } from './secrets.data';

/** The page's data against fake API services: what loads, what each action asks the core, what it keeps. */

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

const notAvailable = () => ApiError.fromHttp(new HttpErrorResponse({ status: 404, error: { status: 404, error: 'Not Found' } }));
const serverError = () => ApiError.fromHttp(new HttpErrorResponse({ status: 500, error: { title: 'Internal error', detail: 'The database is busy.' } }));

let calls: string[];
let events: FakeEvents;
let answers: {
  status: () => Promise<MasterKeyStatus>;
  list: () => Promise<SecretRow[]>;
  stores: () => Promise<SecretStore[]>;
};

function setup(): SecretsData {
  calls = [];
  events = new FakeEvents();
  answers = {
    status: () => Promise.resolve(kitMasterKey()),
    list: () => Promise.resolve(kitSecrets()),
    stores: () => Promise.resolve(kitStores()),
  };
  const secrets: Partial<SecretsApi> = {
    list: () => (calls.push('list'), answers.list()),
    shortLived: () => Promise.resolve(kitShortLived()),
    stores: () => (calls.push('stores'), answers.stores()),
    replace: (id, body) => {
      calls.push(`replace ${id} ${body.value} ${body.testBefore} ${body.reason}`);
      return Promise.resolve(id === 'id-fails' ? { id, version: 4, state: 'testing', tested: false, testMessage: 'auth failed' } : { id, version: 4, state: 'stored', tested: true, testMessage: 'ok' });
    },
    rotate: (id) => (calls.push(`rotate ${id}`), Promise.resolve({ jobId: 'job-1' })),
    testReference: (uri, store) => (calls.push(`test-ref ${uri} ${store}`), Promise.resolve({ found: true, resolvedVersion: '7', created: null, type: 'json', keys: [] })),
    setReference: (id, uri, store, reason) => (
      calls.push(`set-ref ${id} ${uri} ${store} ${reason}`),
      Promise.resolve({ id, name: 'n', owner: 'o', field: '/f', source: 'vault', ref: uri, store: store ?? null, remoteVersion: null, state: 'reference' })
    ),
    createStore: (req) => (calls.push(`create ${req.kind} ${req.name}`), Promise.resolve({ ...kitStores()[0], id: 'st-new', kind: req.kind, name: req.name })),
    testStore: (id) => (calls.push(`test-store ${id}`), Promise.resolve({ ok: true, latencyMs: 9, server: null, identity: null, permissions: [], hints: [], checks: [], error: null, reason: null })),
    deleteStore: (id, confirmName) => (calls.push(`delete ${id} ${confirmName}`), Promise.resolve({ referencesNotConnected: [], leasesRevoked: 0, secretsDestroyed: [] })),
    confirmStoreIntegrity: (id) => (calls.push(`keep ${id}`), Promise.resolve({ ...kitStores()[0], verified: true })),
  };
  const masterKey: Partial<MasterKeyApi> = {
    status: () => (calls.push('status'), answers.status()),
    rotate: () => (calls.push('rotate-key'), Promise.resolve({ state: 'running', done: 0, total: 8, targetKeyId: 3920481776, error: null })),
    backup: (token, pass) => (calls.push(`backup ${token} ${pass}`), Promise.resolve({ path: '/x.json', at: '2026-10-22T12:30:00Z', keyIds: [1], passphrase: !!pass, warnings: [] })),
    fixPermissions: () => (calls.push('fix'), Promise.resolve(kitMasterKey({ problems: [] }))),
  };
  TestBed.configureTestingModule({
    providers: [
      SecretsData,
      { provide: SecretsApi, useValue: secrets },
      { provide: MasterKeyApi, useValue: masterKey },
      { provide: ConnectionsApi, useValue: { list: () => Promise.resolve({ items: [{ name: 'dev-db', env: 'dev' }, { name: 'prod-replica', env: 'prod' }] }) } },
      { provide: EventStream, useValue: events },
    ],
  });
  return TestBed.inject(SecretsData);
}

describe('SecretsData', () => {
  it('loads every part, with each connection’s env', async () => {
    const data = setup();
    await data.start();
    expect(data.masterKey().value?.protects).toBe(8);
    expect(data.rows().value).toHaveLength(11);
    expect(data.stores().value?.map((s) => s.name)).toEqual(['vault', 'gcp-acme-stage']);
    expect(data.shortLived().value).toHaveLength(5);
    expect(data.envOf().get('prod-replica')).toBe('prod');
    expect(data.loaded()).toBe(true);
    expect(data.stale()).toBeNull();
  });

  it('says a part is unavailable when this core has no such route (S1 not merged), and the rest still load', async () => {
    const data = setup();
    answers.stores = () => Promise.reject(notAvailable());
    await data.start();
    expect(data.stores()).toEqual({ value: null, error: null, unavailable: true });
    expect(data.rows().value).toHaveLength(11);
    expect(data.loaded()).toBe(true);
  });

  it('keeps a first-load failure on its part; a later reload failure keeps the data and says it may be stale', async () => {
    const data = setup();
    answers.list = () => Promise.reject(serverError());
    await data.start();
    expect(data.rows().error).toBeInstanceOf(ApiError);
    expect(data.masterKey().value).not.toBeNull();
    answers.list = () => Promise.resolve(kitSecrets());
    await data.refresh();
    expect(data.rows().value).toHaveLength(11);
    answers.status = () => Promise.reject(serverError());
    await data.refresh();
    expect(data.masterKey().value?.protects).toBe(8);
    expect((data.stale() as ApiError).detail).toBe('The database is busy.');
  });

  it('coalesces reloads: one in flight and one more after it', async () => {
    const data = setup();
    await data.start();
    calls = [];
    const a = data.refresh();
    void data.refresh();
    void data.refresh();
    await a;
    await new Promise((r) => setTimeout(r, 0));
    await data.refresh();
    expect(calls.filter((c) => c === 'list').length).toBeLessThanOrEqual(3);
    expect(calls.filter((c) => c === 'list').length).toBeGreaterThanOrEqual(2);
  });

  it('follows the rotation from its events; its end reads the status again', async () => {
    const data = setup();
    await data.start();
    const r = await data.rotateKey();
    expect(r.targetKeyId).toBe(3920481776);
    expect(data.oldKeyId()).toBe(2146530891);
    events.emit('master-key.rotation', { state: 'running', done: 5, total: 8 });
    expect(data.rotation()).toEqual({ state: 'running', done: 5, total: 8, targetKeyId: 3920481776, error: null });
    calls = [];
    answers.status = () => Promise.resolve(kitMasterKey({ rotation: { state: 'done', done: 8, total: 8, targetKeyId: 3920481776, error: null } }));
    events.emit('master-key.rotation', { state: 'done', done: 8, total: 8 });
    await new Promise((r) => setTimeout(r, 0));
    expect(calls).toContain('status');
    expect(data.rotation()?.state).toBe('done');
  });

  it('a rotation the core resumed shows from the status, with the old key it re-wraps from', async () => {
    const data = setup();
    answers.status = () =>
      Promise.resolve(
        kitMasterKey({
          keys: [{ id: 2, status: 'enabled', primary: true }, { id: 1, status: 'enabled', primary: false }],
          rotation: { state: 'running', done: 2, total: 8, targetKeyId: 2, error: null },
        }),
      );
    await data.start();
    expect(data.rotation()?.state).toBe('running');
    expect(data.oldKeyId()).toBe(1);
  });

  it('Replace sends the value once and tells a failed test from a saved version', async () => {
    const data = setup();
    await data.start();
    const ok = await data.replace('id-dev-db/password', 'n3w', true, null);
    expect(ok.ok).toBe(true);
    expect(calls).toContain('replace id-dev-db/password n3w true null');
    const failed = await data.replace('id-fails', 'bad', true, 'rotating after a leak');
    expect(failed).toMatchObject({ ok: false, result: { state: 'testing', testMessage: 'auth failed' } });
  });

  it('references, rotation and stores go to their routes; Test and connect tests the new store', async () => {
    const data = setup();
    await data.start();
    await data.testReference('vault://kv/stage/db#password', 'vault');
    await data.setReference('id-stage-db/password', 'vault://kv/stage/db2#password', 'vault', 'moved');
    await data.rotate('id-dev-db/password', null);
    const { store, test } = await data.connectStore({ kind: 'aws-sm', name: 'aws-eu-central-1', config: {} });
    expect(store.name).toBe('aws-eu-central-1');
    expect(test?.ok).toBe(true);
    expect(calls).toEqual(
      expect.arrayContaining([
        'test-ref vault://kv/stage/db#password vault',
        'set-ref id-stage-db/password vault://kv/stage/db2#password vault moved',
        'rotate id-dev-db/password',
        'create aws-sm aws-eu-central-1',
        'test-store st-new',
      ]),
    );
  });

  it('Disconnect names the store again and takes its card away at once', async () => {
    const data = setup();
    await data.start();
    answers.stores = () => new Promise(() => undefined); // the reload after it hangs: the card is gone already
    await data.disconnectStore(kitStores()[0]);
    expect(calls).toContain('delete st-vault vault');
    expect(data.stores().value?.map((s) => s.name)).toEqual(['gcp-acme-stage']);
  });

  it('backup sends the save token and the passphrase once, then reads the status', async () => {
    const data = setup();
    await data.start();
    calls = [];
    await data.backup('save_tok_1', 'correct horse');
    expect(calls).toEqual(['backup save_tok_1 correct horse', 'status']);
    const fixed = await data.fixPermissions();
    expect(fixed.problems).toEqual([]);
    expect(data.masterKey().value?.problems).toEqual([]);
  });

  it('stops listening when stopped', async () => {
    const data = setup();
    await data.start();
    expect(events.handlers.get('master-key.rotation')?.size).toBe(1);
    data.stop();
    expect(events.handlers.get('master-key.rotation')?.size).toBe(0);
  });
});
