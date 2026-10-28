import { Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CONNECTOR_FORMS } from '../../../../testing/connector-schemas';
import { ConnectionFormSpec } from '../../../core/api/connector-types.api';
import { StoredSecret, TestOutcome } from '../../../core/api/connections.api';
import { Env, JsonObject } from '../../../core/api/types';
import { provideKitIcons } from '../../icons';
import { ConnectionFormValue, DatabaseDiscoveryRequest, GwConnectionForm } from './connection-form';

/** The form spec the core serves for a type (`GET /api/connector-types/{type}/form`), from the stored schemas. */
function spec(type: string): ConnectionFormSpec {
  return {
    type,
    name: type,
    env: 'dev',
    schema: CONNECTOR_FORMS[type],
    common: {},
    secretFields: [],
    secretSources: [{ id: 'local', label: 'Encrypted here', available: true, reason: null }],
    envDefaults: { prod: [], stage: [] },
    variants: [],
  };
}

@Component({
  imports: [GwConnectionForm],
  template: `<form gwConnectionForm idPrefix="cf" aria-label="New connection" [spec]="spec()" [mode]="mode()" [stored]="stored()" name="dev-cluster" [env]="env()" [config]="config()" (save)="saved.push($event)" (discoverDatabases)="discoveries.push($event)"></form>`,
})
class Host {
  readonly spec = signal(spec('kubernetes'));
  readonly env = signal<Env>('dev');
  readonly config = signal<JsonObject | null>(null);
  readonly mode = signal<'create' | 'edit'>('create');
  readonly stored = signal<readonly StoredSecret[]>([]);
  readonly saved: ConnectionFormValue[] = [];
  readonly discoveries: DatabaseDiscoveryRequest[] = [];
  readonly form = viewChild.required(GwConnectionForm);
}

beforeEach(() => {
  TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons()] });
});
afterEach(() => (document.body.innerHTML = ''));

async function render() {
  const fixture = TestBed.createComponent(Host);
  document.body.appendChild(fixture.nativeElement);
  fixture.detectChanges();
  await fixture.whenStable();
  const el = fixture.nativeElement as HTMLElement;
  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
  };
  return { fixture, el, host: fixture.componentInstance, settle };
}

/** Picks how the Kubernetes form signs in: each mode shows only its own fields. */
async function signInWith(el: HTMLElement, settle: () => Promise<void>, auth: 'token' | 'kubeconfig' | 'kubeconfig-file') {
  const select = el.querySelector<HTMLSelectElement>('#cf-auth')!;
  select.value = auth;
  select.dispatchEvent(new Event('change', { bubbles: true }));
  await settle();
}

const KUBECONFIG = [
  'apiVersion: v1',
  'clusters:',
  '- cluster:',
  '    server: https://127.0.0.1:6443',
  '  name: kind-dev',
  'contexts:',
  '- context: {cluster: kind-dev, user: admin}',
  '  name: kind-dev',
  'users:',
  '- name: admin',
  '  user:',
  '    token: abc\tdef',
  '',
].join('\n');

const discovered = (names: (string | number)[], complete = true): TestOutcome => ({
  ok: true, latencyMs: 4, serverVersion: null, identity: null, permissions: null,
  hints: [], checks: [], error: null, needsConfirmation: null, confirmationId: null,
  suggestedPolicyChange: null, details: { databases: names, discoveryComplete: complete },
});

async function databaseForm(type = 'mysql') {
  const view = await render();
  view.host.spec.set(spec(type));
  await view.settle();
  for (const [id, value] of [['connection-host', '127.0.0.1'], ['credentials-user', 'pp_user'], ['credentials-password', 'local-password']]) {
    const input = view.el.querySelector<HTMLInputElement>(`#cf-${id}`)!;
    if (!input) continue;
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }
  await view.settle();
  const load = async () => {
    view.el.querySelector<HTMLButtonElement>('#cf-database-load')!.click();
    await view.settle();
    return view.host.discoveries.at(-1)!;
  };
  const choose = async (name: string | number) => {
    [...view.el.querySelectorAll<HTMLInputElement>('input[id^="cf-database-"]')].find((input) => input.value === String(name))!.click();
    await view.settle();
  };
  return { ...view, load, choose };
}

describe('connection form: GCP setup', () => {
  it.each(['gcp-logging', 'loki', 'cloudwatch', 'opensearch'])('keeps %s fixed to its own backend without exposing a backend selector', async (type) => {
    const { el, host, settle } = await render();
    host.spec.set(spec(type));
    await settle();
    expect(el.querySelector('#cf-backend')).toBeNull();
    expect(host.form().value().config['backend']).toBe(type);
    if (type === 'gcp-logging') {
      expect(el.querySelector('#cf-project-load')).not.toBeNull();
      const credentials = el.querySelector('#cf-credentials-source')!;
      const scopes = el.querySelector('#cf-resourceNames')!;
      expect(credentials.compareDocumentPosition(scopes) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);
    } else {
      expect(el.querySelector('#cf-project-load')).toBeNull();
    }
    if (type === 'opensearch') {
      expect(host.form().value().config['apiAccess']).toBe('full');
      for (const field of ['apiAccess', 'indices', 'scopeQuery', 'timestampField']) {
        expect(el.querySelector(`#cf-${field}`)).toBeNull();
      }
      expect(el.querySelector('#cf-url')).not.toBeNull();
    }
  });

  it('shows the scope format and local sign-in instructions, then submits after correcting a bare project ID', async () => {
    const { el, host, settle } = await render();
    host.spec.set(spec('gcp'));
    await settle();
    const scopes = el.querySelector<HTMLInputElement>('#cf-scopes')!;
    expect(scopes.placeholder).toBe('Add a resource scope, for example projects/acme-stage');
    expect(el.textContent).toContain('folders/');
    expect(el.textContent).toContain('organizations/');
    expect(el.textContent).toContain('gcloud auth application-default login');

    scopes.value = 'acme-stage';
    scopes.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    el.querySelector<HTMLButtonElement>('button[aria-label="Add resource scope"]')!.click();
    await settle();
    el.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
    expect(host.saved).toHaveLength(0);
    expect(scopes.getAttribute('aria-invalid')).toBe('true');
    const errorId = scopes.getAttribute('aria-describedby')!.split(' ').find((id) => id.endsWith('-err'))!;
    expect(el.querySelector(`#${errorId}`)?.textContent).toContain('Use a value such as projects/acme-stage.');

    el.querySelector<HTMLButtonElement>('button[aria-label="Remove resource scope acme-stage"]')!.click();
    await settle();
    scopes.value = 'projects/acme-stage';
    scopes.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    scopes.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    await settle();
    el.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
    expect(host.saved).toHaveLength(1);
    expect(host.saved[0].config).toMatchObject({ scopes: ['projects/acme-stage'], credentials: { source: 'adc' } });
    expect(host.saved[0].secrets).toEqual({});
  });
});

describe('connection form: database discovery', () => {
  it('offers supported secret sources while omitting every dynamic-login alias', async () => {
    const { el, host, settle } = await render();
    host.spec.set({ ...spec('mysql'), secretSources: [
      { id: 'local', label: 'Encrypted here', available: true, reason: null },
      { id: 'vault', label: 'Vault', available: true, reason: null },
      ...['dynamic', 'vault-dynamic', 'short-lived'].map((id) => ({ id, label: 'Vault dynamic', available: true, reason: null })),
    ] });
    await settle();
    expect([...el.querySelectorAll<HTMLInputElement>('input[name="cf-src"]')].map((input) => input.value)).toEqual(['local', 'vault']);
    expect(el.textContent).not.toContain('Vault dynamic');
    expect(el.querySelector('#cf-credentials-user')).not.toBeNull();
    expect(el.querySelector('#cf-credentials-password')).not.toBeNull();
  });

  it('keeps saved dynamic-login credentials when editing without exposing their controls', async () => {
    const { el, host, settle } = await render();
    host.spec.set({ ...spec('mysql'), secretSources: [
      { id: 'local', label: 'Encrypted here', available: true, reason: null },
      { id: 'vault-dynamic', label: 'Short-lived', available: true, reason: null },
    ] });
    host.mode.set('edit');
    host.stored.set([{
      field: '/credentials/password', secretId: 'saved-login', name: 'vault://database/creds/orders-reader',
      state: 'short-lived', version: null, source: 'vault',
    }]);
    host.config.set({ connection: { host: 'db.example.test', databases: ['orders'] } });
    await settle();
    expect(el.querySelector('input[name="cf-src"]')).toBeNull();
    expect(el.querySelector('#cf-credentials-user')).toBeNull();
    expect(el.querySelector('#cf-credentials-password')).toBeNull();
    expect(el.textContent).not.toContain('vault://database/creds/orders-reader');
    expect(host.form().state().source()).toBe('dynamic');
    el.querySelector<HTMLButtonElement>('#cf-database-load')!.click();
    await settle();
    expect(host.discoveries.at(-1)!.value.secrets).toEqual({});
    host.form().completeDatabaseDiscovery(host.discoveries.at(-1)!.key, discovered(['orders']));
    await settle();
    el.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
    expect(host.saved).toHaveLength(1);
    expect(host.saved[0].secrets).toEqual({});
    expect(host.saved[0].config['credentials']).not.toHaveProperty('user');
  });

  it('puts credentials before loading databases and saves multiple selected databases in one connection', async () => {
    const { el, host, settle, load, choose } = await databaseForm();
    expect(el.querySelector('#cf-connection-database')).toBeNull();
    expect(el.querySelector('#cf-connection-databases')).toBeNull();
    const user = el.querySelector('#cf-credentials-user')!;
    expect(el.querySelector('#cf-connection-host')!.compareDocumentPosition(user) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(user.compareDocumentPosition(el.querySelector('#cf-database-load')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(host.form().databaseSelectionValid()).toBe(false);

    const request = await load();
    expect(request.value.config).toMatchObject({ connection: { host: '127.0.0.1', port: 3306 }, credentials: { user: 'pp_user' } });
    expect(request.value.secrets).toEqual({ '/credentials/password': { source: 'local', value: 'local-password' } });
    host.form().completeDatabaseDiscovery(request.key, discovered(['app', 'reports']));
    await settle();
    await choose('app');
    await choose('reports');
    el.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
    expect(host.saved).toHaveLength(1);
    expect(host.saved[0].config['connection']).toMatchObject({ databases: ['app', 'reports'] });
    expect(host.saved[0].config['connection']).not.toHaveProperty('database');
  });

  it('ignores a late response after credentials change and requires a fresh nonempty selection', async () => {
    const { el, host, settle, load, choose } = await databaseForm();
    const old = await load();
    const password = el.querySelector<HTMLInputElement>('#cf-credentials-password')!;
    password.value = 'replacement-password';
    password.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    expect(host.form().completeDatabaseDiscovery(old.key, discovered(['stale']))).toBe(false);
    expect(el.textContent).not.toContain('stale');
    const fresh = await load();
    host.form().completeDatabaseDiscovery(fresh.key, discovered([]));
    await settle();
    expect(el.textContent).toContain('No databases are available');
    expect(host.form().databaseSelectionValid()).toBe(false);
    const retry = await load();
    host.form().completeDatabaseDiscovery(retry.key, discovered(['app']));
    await settle();
    await choose('app');
    expect(host.form().databaseSelectionValid()).toBe(true);
    const ssl = el.querySelector<HTMLSelectElement>('#cf-connection-sslMode')!;
    ssl.value = 'require';
    ssl.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
    expect(host.form().databaseSelectionValid()).toBe(false);
  });

  it('retains legacy saved selections, including unavailable databases, without requiring discovery to save an edit', async () => {
    const { el, host, settle, load } = await databaseForm();
    host.mode.set('edit');
    host.stored.set([{ field: '/credentials/password', secretId: 'sec-1', name: 'local/password', state: 'stored', version: 1, source: 'local' }]);
    host.config.set({ connection: { host: '127.0.0.1', database: 'saved' }, credentials: { user: 'pp_user' } });
    await settle();
    expect(host.form().databaseSelectionValid()).toBe(true);
    expect(host.form().value().config['connection']).toMatchObject({ databases: ['saved'] });
    const request = await load();
    host.form().completeDatabaseDiscovery(request.key, discovered(['other']));
    await settle();
    expect(el.textContent).toContain('Saved selection · not returned by the server');
    expect(host.form().databaseSelectionValid()).toBe(true);
    const address = el.querySelector<HTMLInputElement>('#cf-connection-host')!;
    address.value = 'other.internal';
    address.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    expect(host.form().databaseSelectionValid()).toBe(false);
    address.value = '127.0.0.1';
    address.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    expect(host.form().databaseSelectionValid()).toBe(true);
    host.stored.update((stored) => stored.map((secret) => ({ ...secret, version: 2 })));
    await settle();
    expect(host.form().databaseSelectionValid()).toBe(false);
  });

  it('saves Redis with just a host and leaves optional logical databases in Advanced', async () => {
    const { el, host, settle } = await render();
    host.spec.set(spec('redis'));
    host.config.set({ connection: { host: '127.0.0.1' } });
    await settle();
    expect(el.querySelector('#cf-database-load')).toBeNull();
    expect(el.querySelector('#cf-connection-database')!.closest('[hidden]')).not.toBeNull();
    expect(el.querySelector('#cf-connection-databases')!.closest('[hidden]')).not.toBeNull();
    el.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
    expect(host.saved).toHaveLength(1);
    expect(host.saved[0].config['connection']).toMatchObject({ host: '127.0.0.1', port: 6379, database: 0 });
    expect(host.saved[0].config['connection']).not.toHaveProperty('databases');
    expect(host.saved[0].secrets).toEqual({});
    expect(host.discoveries).toEqual([]);
  });

  it.each<JsonObject>([{ database: 7 }, { databases: [0, 2] }])('preserves explicit Redis settings without discovery: %j', async (settings) => {
    const { el, host, settle } = await render();
    host.spec.set(spec('redis'));
    host.mode.set('edit');
    host.config.set({ connection: { host: '127.0.0.1', ...settings } });
    await settle();
    el.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
    expect(host.saved).toHaveLength(1);
    expect(host.saved[0].config['connection']).toMatchObject(settings);
    expect(host.discoveries).toEqual([]);
  });

  it('uses a plugin’s flat selection path and keeps root credentials out of config', async () => {
    const { el, host, settle } = await render();
    // The ClickHouse manifest uses root properties, unlike the built-in connectors' grouped config.
    host.spec.set({ ...spec('mysql'), type: 'clickhouse', schema: {
      type: 'object', required: ['host', 'user', 'password'],
      'x-database-selection': { path: '/databases', legacyPath: '/database' },
      properties: {
        host: { type: 'string', title: 'Host' }, port: { type: 'integer', title: 'Port', default: 8443 },
        database: { type: 'string', title: 'Database', default: 'default' },
        databases: { type: 'array', title: 'Databases', minItems: 1, items: { type: 'string' } },
        user: { type: 'string', title: 'User', default: 'default' },
        password: { type: 'string', title: 'Password', writeOnly: true, format: 'password', 'x-gateway-secret': 'value' },
      },
    } });
    await settle();
    for (const [id, value] of [['cf-host', 'analytics.internal'], ['cf-password', 'plugin-password']]) {
      const input = el.querySelector<HTMLInputElement>(`#${id}`)!;
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
    await settle();
    expect(el.querySelector('#cf-database')).toBeNull();
    el.querySelector<HTMLButtonElement>('#cf-database-load')!.click();
    await settle();
    const request = host.discoveries[0];
    expect(request.value.secrets).toEqual({ '/password': { source: 'local', value: 'plugin-password' } });
    host.form().completeDatabaseDiscovery(request.key, discovered(['analytics']));
    await settle();
    el.querySelector<HTMLInputElement>('#cf-database-0')!.click();
    await settle();
    expect(host.form().value().config).toEqual({ host: 'analytics.internal', port: 8443, user: 'default', databases: ['analytics'] });
  });
});

describe('connection form: MongoDB connection settings', () => {
  it('starts with host, port and a TLS select, while authentication options stay in collapsed Advanced', async () => {
    const { el, host, settle, load } = await databaseForm('mongodb');
    const mode = el.querySelector<HTMLSelectElement>('#cf-connection-mode')!;
    const tls = el.querySelector<HTMLSelectElement>('#cf-connection-tls')!;
    const advanced = el.querySelector<HTMLElement>('#cf-advanced-body')!;

    expect(mode.value).toBe('host');
    expect([...mode.options].map((option) => option.value)).toEqual(['host', 'uri']);
    expect(el.querySelector<HTMLInputElement>('#cf-connection-host')!.value).toBe('127.0.0.1');
    expect(el.querySelector<HTMLInputElement>('#cf-connection-port')!.value).toBe('27017');
    expect(el.querySelector('#cf-connection-uri')).toBeNull();
    expect(el.querySelector<HTMLInputElement>('#cf-credentials-password')!.type).toBe('password');
    expect(tls.value).toBe('false');
    expect([...tls.options].map((option) => option.value)).toEqual(['false', 'true']);
    expect(tls.closest('[hidden]')).toBeNull();
    expect(advanced.hidden).toBe(true);
    for (const id of ['cf-credentials-authSource', 'cf-credentials-mechanism']) {
      expect(advanced.contains(el.querySelector(`#${id}`))).toBe(true);
      expect(el.querySelectorAll(`#${id}`)).toHaveLength(1);
    }
    expect(el.querySelector('#cf-credentials-user')!.closest('[hidden]')).toBeNull();

    tls.value = 'true';
    tls.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
    const request = await load();
    expect(request.value.config['connection']).toMatchObject({ host: '127.0.0.1', port: 27017, mode: 'host', tls: true });
    expect(request.value.config['connection']).not.toHaveProperty('uri');
    expect(request.value.config['credentials']).toEqual({ user: 'pp_user', authSource: 'admin', mechanism: 'auto' });
    expect(request.value.secrets).toEqual({ '/credentials/password': { source: 'local', value: 'local-password' } });

    el.querySelector<HTMLButtonElement>('[aria-controls="cf-advanced-body"]')!.click();
    await settle();
    expect(advanced.hidden).toBe(false);
  });

  it('switches between host and URI inputs without sending settings from the hidden mode', async () => {
    const { el, host, settle } = await databaseForm('mongodb');
    const mode = el.querySelector<HTMLSelectElement>('#cf-connection-mode')!;
    mode.value = 'uri';
    mode.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
    expect(el.querySelector('#cf-connection-host')).toBeNull();
    expect(el.querySelector('#cf-connection-port')).toBeNull();
    const uri = el.querySelector<HTMLInputElement>('#cf-connection-uri')!;
    uri.value = 'mongodb://mongo-a.example.test:27018,mongo-b.example.test:27018/orders?replicaSet=rs0';
    uri.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    expect(host.form().value().config['connection']).toMatchObject({ mode: 'uri', uri: uri.value });
    expect(host.form().value().config['connection']).not.toHaveProperty('host');
    expect(host.form().value().config['connection']).not.toHaveProperty('port');

    mode.value = 'host';
    mode.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
    expect(el.querySelector('#cf-connection-uri')).toBeNull();
    expect(host.form().value().config['connection']).toMatchObject({ mode: 'host', host: '127.0.0.1', port: 27017 });
    expect(host.form().value().config['connection']).not.toHaveProperty('uri');

    mode.value = 'uri';
    mode.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
    expect(el.querySelector<HTMLInputElement>('#cf-connection-uri')!.value).toBe(uri.value);
  });

  for (const mode of ['edit', 'create'] as const) {
    it(`preserves a legacy URI and its options when opened for ${mode === 'edit' ? 'editing' : 'duplication'} and reset`, async () => {
      const { el, host, settle } = await render();
      const uri = 'mongodb+srv://cluster.example.test/orders?replicaSet=rs0&retryWrites=false';
      const config = { connection: { uri, databases: ['orders'] }, credentials: { user: 'reader', authSource: 'users' } };
      host.spec.set(spec('mongodb'));
      host.mode.set(mode);
      host.config.set(config);
      await settle();
      const selector = el.querySelector<HTMLSelectElement>('#cf-connection-mode')!;
      expect(selector.value).toBe('uri');
      expect(el.querySelector<HTMLInputElement>('#cf-connection-uri')!.value).toBe(uri);
      expect(el.querySelector('#cf-connection-host')).toBeNull();
      expect(host.form().value().config['connection']).toMatchObject({ mode: 'uri', uri, databases: ['orders'], tls: true });
      expect(host.form().value().config['connection']).not.toHaveProperty('port');
      expect(config.connection).not.toHaveProperty('mode');

      selector.value = 'host';
      selector.dispatchEvent(new Event('change', { bubbles: true }));
      await settle();
      host.form().reset();
      await settle();
      expect(selector.value).toBe('uri');
      expect(el.querySelector<HTMLInputElement>('#cf-connection-uri')!.value).toBe(uri);
      expect(host.form().value().config['connection']).not.toHaveProperty('host');
      expect(host.form().value().config['connection']).not.toHaveProperty('port');
    });
  }

  it('starts new production connections with TLS selected', async () => {
    const { el, host, settle } = await render();
    host.spec.set(spec('mongodb'));
    host.env.set('prod');
    await settle();
    expect(el.querySelector<HTMLSelectElement>('#cf-connection-tls')!.value).toBe('true');
    expect(host.form().value().config['connection']).toMatchObject({ mode: 'host', port: 27017, tls: true });
  });
});

describe('connection form: MySQL transport defaults', () => {
  it('selects Auto and enables public key retrieval for a new connection', async () => {
    const { el, host, settle } = await render();
    host.spec.set(spec('mysql'));
    await settle();

    const ssl = el.querySelector<HTMLSelectElement>('#cf-connection-sslMode')!;
    expect(ssl.value).toBe('auto');
    expect(ssl.selectedOptions[0].textContent).toBe('Auto (SSL if available)');
    expect([...ssl.options].map((option) => option.value)).toEqual(['auto', 'disable', 'require', 'verify-ca', 'verify-full']);

    const advanced = el.querySelector<HTMLElement>('#cf-advanced-body')!;
    const ca = el.querySelector<HTMLTextAreaElement>('#cf-connection-caCertificate')!;
    expect(advanced.hidden).toBe(true);
    expect(advanced.contains(ca)).toBe(true);
    expect(el.querySelectorAll('#cf-connection-caCertificate')).toHaveLength(1);

    el.querySelector<HTMLButtonElement>('[aria-controls="cf-advanced-body"]')!.click();
    await settle();
    expect(advanced.hidden).toBe(false);
    expect(el.querySelector<HTMLInputElement>('#cf-advanced-allowPublicKeyRetrieval')!.checked).toBe(true);
    expect(host.form().value().config).toMatchObject({
      connection: { sslMode: 'auto' },
      advanced: { allowPublicKeyRetrieval: true },
    });
  });

  it('keeps saved SSL, CA and public key choices when editing a connection', async () => {
    const { el, host, settle } = await render();
    host.spec.set(spec('mysql'));
    const savedCa = '-----BEGIN CERTIFICATE-----\nexisting-ca\n-----END CERTIFICATE-----';
    host.config.set({ connection: { sslMode: 'verify-full', caCertificate: savedCa }, advanced: { allowPublicKeyRetrieval: false } });
    await settle();

    const ssl = el.querySelector<HTMLSelectElement>('#cf-connection-sslMode')!;
    const retrieval = el.querySelector<HTMLInputElement>('#cf-advanced-allowPublicKeyRetrieval')!;
    const ca = el.querySelector<HTMLTextAreaElement>('#cf-advanced-body #cf-connection-caCertificate')!;
    expect(ssl.value).toBe('verify-full');
    expect(retrieval.checked).toBe(false);
    expect(ca.value).toBe(savedCa);
    expect(host.form().value().config).toMatchObject({
      connection: { sslMode: 'verify-full', caCertificate: savedCa },
      advanced: { allowPublicKeyRetrieval: false },
    });

    el.querySelector<HTMLButtonElement>('[aria-controls="cf-advanced-body"]')!.click();
    await settle();
    ssl.value = 'auto';
    ssl.dispatchEvent(new Event('change', { bubbles: true }));
    retrieval.checked = true;
    retrieval.dispatchEvent(new Event('change', { bubbles: true }));
    const replacementCa = '-----BEGIN CERTIFICATE-----\nreplacement-ca\n-----END CERTIFICATE-----';
    ca.value = replacementCa;
    ca.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    expect(host.form().value().config).toMatchObject({
      connection: { sslMode: 'auto', caCertificate: replacementCa },
      advanced: { allowPublicKeyRetrieval: true },
    });
    expect(host.form().value().config['advanced']).not.toHaveProperty('caCertificate');
  });
});

describe('connection form: multi-line secrets', () => {
  it('a pasted kubeconfig goes into a textarea and reaches secrets byte for byte', async () => {
    const { el, host, settle } = await render();
    expect(el.querySelector('#cf-kubeconfig'), 'only when signing in with a pasted kubeconfig').toBeNull();
    await signInWith(el, settle, 'kubeconfig');
    expect(el.querySelector('#cf-server')).toBeNull();
    const field = el.querySelector<HTMLTextAreaElement>('#cf-kubeconfig')!;
    expect(field.tagName).toBe('TEXTAREA');
    expect(field.classList).toContain('gw-textarea--code');
    expect(field.getAttribute('autocomplete')).toBe('off');
    expect(field.getAttribute('spellcheck')).toBe('false');
    field.value = KUBECONFIG;
    field.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    const secrets = host.form().value().secrets;
    expect(secrets['/kubeconfig']).toEqual({ source: 'local', value: KUBECONFIG });
  });
});

describe('connection form: x-confirm and the prod locks', () => {
  it('a changed setting with x-confirm asks in a warning dialog; Cancel saves nothing, the confirm saves', async () => {
    const { el, host, settle } = await render();
    // A kubeconfig's credential command: fill what a pasted kubeconfig needs, so the form is valid.
    await signInWith(el, settle, 'kubeconfig');
    const kubeconfig = el.querySelector<HTMLTextAreaElement>('#cf-kubeconfig')!;
    kubeconfig.value = KUBECONFIG;
    kubeconfig.dispatchEvent(new Event('input', { bubbles: true }));
    const exec = el.querySelector<HTMLInputElement>('#cf-allowExecPlugin')!;
    exec.checked = true;
    exec.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
    expect(host.form().state().invalidNodes().map((n) => n.pointer)).toEqual([]);

    const form = el.querySelector('form')!;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
    const dialog = el.querySelector<HTMLDialogElement>('dialog.gw-dialog')!;
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(dialog.classList).toContain('gw-dialog--warning');
    expect(dialog.querySelector('.gw-dialog__title')!.textContent).toBe('Save dev-cluster with this change?');
    expect(dialog.querySelector('.gw-kv__value')!.textContent).toBe('On, was off');
    expect(document.activeElement?.textContent?.trim()).toBe('Cancel');
    expect(host.saved).toEqual([]);

    (document.activeElement as HTMLButtonElement).click();
    await settle();
    expect(dialog.hasAttribute('open')).toBe(false);
    expect(host.saved).toEqual([]);

    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
    const confirm = [...dialog.querySelectorAll<HTMLButtonElement>('.gw-dialog__footer .gw-btn')].at(-1)!;
    expect(confirm.classList).toContain('gw-btn--primary');
    confirm.click();
    await settle();
    expect(host.saved).toHaveLength(1);
    expect(host.saved[0].config).toMatchObject({ allowExecPlugin: true });
  });

  it('on prod, Skip TLS verification is off and disabled with its reason', async () => {
    const { el, host, settle } = await render();
    const skip = () => el.querySelector<HTMLInputElement>('#cf-insecureSkipTlsVerify')!;
    skip().checked = true;
    skip().dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
    expect(host.form().value().config).toMatchObject({ insecureSkipTlsVerify: true });
    expect(skip().disabled).toBe(false);

    host.env.set('prod');
    await settle();
    expect(skip().disabled).toBe(true);
    expect(skip().checked).toBe(false);
    const reason = el.querySelector('#cf-insecureSkipTlsVerify-lock')!;
    expect(reason.textContent).toBe('Not allowed on prod.');
    expect(skip().getAttribute('aria-describedby')).toContain('cf-insecureSkipTlsVerify-lock');
    expect(host.form().value().config).toMatchObject({ insecureSkipTlsVerify: false });
  });

  it('on prod, an option of a select that prod forbids is disabled and named', async () => {
    const { el, host, settle } = await render();
    host.spec.set(spec('redis'));
    host.env.set('prod');
    await settle();
    const select = el.querySelector<HTMLSelectElement>('#cf-connection-tlsVerify')!;
    const ca = [...select.options].find((o) => o.value === 'ca')!;
    expect(ca.disabled).toBe(true);
    expect(select.getAttribute('aria-describedby')).toContain('cf-connection-tlsVerify-lock');
    expect(el.querySelector('#cf-connection-tlsVerify-lock')!.textContent).toMatch(/isn't allowed on prod\.$/);
    // Prod's own default is on from the start: TLS for a new prod Redis.
    expect(host.form().value().config['connection']).toMatchObject({ tls: true, tlsVerify: 'full' });
  });
});
