import { TestBed } from '@angular/core/testing';
import { ApiClient } from './api-client';
import { ConnectionImportsApi, ImportSelection } from './connection-imports.api';

describe('ConnectionImportsApi', () => {
  it('loads active import task summaries through the admin collection route', async () => {
    const result = { items: [{ id: 'ci_abcdefgh1234', repository: '/workspace/example', endpoint: 'default',
      expiresAt: '2026-11-07T14:00:00Z', status: 'review', progress: null }] };
    const get = vi.fn().mockResolvedValue(result);
    TestBed.configureTestingModule({ providers: [{ provide: ApiClient, useValue: { get } }] });
    expect(await TestBed.inject(ConnectionImportsApi).list()).toBe(result);
    expect(get).toHaveBeenCalledExactlyOnceWith('/api/connection-imports');
  });

  it('connects prepared or existing stores only through the reviewed admin route', async () => {
    const post = vi.fn().mockResolvedValue({ id: 'st_vault', kind: 'vault' });
    TestBed.configureTestingModule({ providers: [{ provide: ApiClient, useValue: { post } }] });
    const api = TestBed.inject(ConnectionImportsApi);
    const reviewed = { name: 'vault-dev', config: { address: 'https://vault.example.test', auth: { method: 'token' } }, preparedFields: ['/auth/token'] };
    await api.connectStore('ci_abcdefgh1234', 'vault-dev', reviewed);
    expect(post).toHaveBeenLastCalledWith('/api/connection-imports/ci_abcdefgh1234/stores/vault-dev/connect', reviewed);
    await api.connectStore('ci_abcdefgh1234', 'vault-prod', { existingStore: 'st_existing' });
    expect(post).toHaveBeenLastCalledWith('/api/connection-imports/ci_abcdefgh1234/stores/vault-prod/connect', { existingStore: 'st_existing' });
  });
  it('asks the core to summarize a draft the person changed, with the config as it stands', async () => {
    const summary = { target: 'mine.example.test:5432', settings: [], omitted: 0 };
    const post = vi.fn().mockResolvedValue(summary);
    TestBed.configureTestingModule({ providers: [{ provide: ApiClient, useValue: { post } }] });
    const config = { connection: { host: 'mine.example.test' } };
    expect(await TestBed.inject(ConnectionImportsApi).summary('ci_abcdefgh1234', 'dev-db', 'dev', config)).toBe(summary);
    expect(post).toHaveBeenCalledExactlyOnceWith('/api/connection-imports/ci_abcdefgh1234/summary',
      { candidateId: 'dev-db', env: 'dev', config });
  });
  it('sends each connection destination without a conflicting global endpoint', async () => {
    const post = vi.fn().mockResolvedValue({ status: 'imported' });
    TestBed.configureTestingModule({ providers: [{ provide: ApiClient, useValue: { post } }] });
    const selections: ImportSelection[] = [
      { candidateId: 'dev-db', name: 'project-dev-db', env: 'dev', config: {}, endpoint: 'project-dev', allowIncomplete: true },
      { candidateId: 'prod-logs', name: 'project-prod-logs', env: 'prod', config: {}, endpoint: 'project-prod', allowIncomplete: true },
    ];
    await TestBed.inject(ConnectionImportsApi).commit('ci_abcdefgh1234', selections, 'Review each environment separately');
    expect(post).toHaveBeenCalledWith('/api/connection-imports/ci_abcdefgh1234/commit', {
      selections, reason: 'Review each environment separately',
    });
  });
});
