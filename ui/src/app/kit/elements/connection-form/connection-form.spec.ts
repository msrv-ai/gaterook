import { Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CONNECTOR_FORMS } from '../../../../testing/connector-schemas';
import { ConnectionFormSpec } from '../../../core/api/connector-types.api';
import { StoredSecret, TestOutcome } from '../../../core/api/connections.api';
import { Env, JsonObject } from '../../../core/api/types';
import { provideKitIcons } from '../../icons';
import { ConnectionFormValue, DatabaseDiscoveryRequest, GwConnectionForm, InitialCredentialReference } from './connection-form';

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
  template: `<form gwConnectionForm idPrefix="cf" aria-label="New connection" [spec]="spec()" [mode]="mode()" [stored]="stored()" [initialReferences]="references()" name="dev-cluster" [env]="env()" [config]="config()" (save)="saved.push($event)" (discoverDatabases)="discoveries.push($event)"></form>`,
})
class Host {
  readonly spec = signal(spec('kubernetes'));
  readonly env = signal<Env>('dev');
  readonly config = signal<JsonObject | null>(null);
  readonly mode = signal<'create' | 'edit'>('create');
  readonly stored = signal<readonly StoredSecret[]>([]);
  readonly references = signal<readonly InitialCredentialReference[]>([]);
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

/** A field's menu of where its value comes from: its button, its options by title. */
function sourceMenu(el: HTMLElement, id: string) {
  const trigger = () => el.querySelector<HTMLButtonElement>(`#cf-${id}-src`)!;
  const options = () => [...el.querySelectorAll<HTMLElement>(`#cf-${id}-src-menu [role="option"]`)];
  const title = (o: HTMLElement) => o.querySelector('.gw-menu__label')!.firstChild!.textContent!.trim();
  return { trigger, options, titles: () => options().map(title), option: (text: string) => options().find((o) => title(o) === text)! };
}

/** Picks where a field's value comes from in its menu. */
async function pickSource(el: HTMLElement, settle: () => Promise<void>, id: string, text: string) {
  const menu = sourceMenu(el, id);
  menu.trigger().click();
  await settle();
  menu.option(text).click();
  await settle();
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

describe('connection form: Oracle Database', () => {
  it('requires a service name without offering database discovery', async () => {
    const { host, el, settle } = await render();
    host.spec.set(spec('oracle'));
    await settle();
    expect(el.querySelector('#cf-database-load')).toBeNull();
    const service = el.querySelector<HTMLInputElement>('#cf-connection-database')!;
    expect(service).not.toBeNull();
    expect(el.querySelector('label[for="cf-connection-database"]')?.textContent).toContain('Service name');
    expect(el.querySelector<HTMLInputElement>('#cf-connection-port')?.value).toBe('1521');
  });
});

describe('connection form: GCP setup', () => {
  it.each(['gcp-logging', 'loki', 'cloudwatch', 'opensearch', 'elasticsearch'])('keeps %s fixed to its own backend without exposing a backend selector', async (type) => {
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
    if (['opensearch', 'elasticsearch'].includes(type)) {
      expect(host.form().value().config['apiAccess']).toBe('full');
      for (const field of ['apiAccess', 'indices', 'scopeQuery', 'timestampField']) {
        expect(el.querySelector(`#cf-${field}`)).toBeNull();
      }
      expect(el.querySelector('#cf-url')).not.toBeNull();
    }
  });

  it('shows Elasticsearch API key authentication without AWS credential fields', async () => {
    const { el, host, settle } = await render();
    host.spec.set(spec('elasticsearch'));
    host.config.set({ url: 'https://elastic.example.test:9200', auth: { mode: 'apiKey' } });
    await settle();
    const mode = el.querySelector<HTMLSelectElement>('#cf-auth-mode')!;
    expect([...mode.options].map((option) => option.value)).toEqual(['basic', 'apiKey', 'bearer', 'none']);
    expect(el.querySelector('#cf-auth-secret')).not.toBeNull();
    expect(el.querySelector('#cf-auth-username')).toBeNull();
    expect(el.querySelector('#cf-auth-awsRegion')).toBeNull();
    expect(el.querySelector('#cf-caCertificate')).not.toBeNull();
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
    // Each credential field's menu: Text, then the stores; short-lived credentials are never offered.
    expect(sourceMenu(el, 'credentials-password').titles()).toEqual(['Text', 'HashiCorp Vault or OpenBao']);
    expect(sourceMenu(el, 'credentials-user').titles()).toEqual(['Text', 'HashiCorp Vault or OpenBao']);
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

  it.each([['mysql', 3306], ['sqlserver', 1433]])('%s puts credentials before loading databases and saves multiple selected databases', async (type, port) => {
    const { el, host, settle, load, choose } = await databaseForm(String(type));
    expect(el.querySelector('#cf-connection-database')).toBeNull();
    expect(el.querySelector('#cf-connection-databases')).toBeNull();
    const user = el.querySelector('#cf-credentials-user')!;
    expect(el.querySelector('#cf-connection-host')!.compareDocumentPosition(user) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(user.compareDocumentPosition(el.querySelector('#cf-database-load')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(host.form().databaseSelectionValid()).toBe(false);

    const request = await load();
    expect(request.value.config).toMatchObject({ connection: { host: '127.0.0.1', port }, credentials: { user: 'pp_user' } });
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

  it.each(['mysql', 'postgres', 'sqlserver'])('keeps %s database choices when only the environment changes', async (type) => {
    const { el, host, settle, load, choose } = await databaseForm(type);
    const request = await load();
    host.form().completeDatabaseDiscovery(request.key, discovered(['app', 'reports']));
    await settle();
    await choose('app');

    for (const env of ['stage', 'prod', 'dev']) {
      el.querySelector<HTMLInputElement>(`input[name="cf-env"][value="${env}"]`)!.click();
      await settle();
      expect(host.form().databaseSelectionValid()).toBe(true);
      expect(el.querySelector('#cf-database-load')!.textContent).toContain('Reload databases');
      expect(el.querySelector<HTMLInputElement>('#cf-database-0')!.checked).toBe(true);
      expect(el.querySelector<HTMLInputElement>('#cf-database-1')!.value).toBe('reports');
      expect(host.form().value().env).toBe(env);
    }
    expect(host.discoveries).toHaveLength(1);
  });

  it('accepts in-flight database discovery after an environment-only change', async () => {
    const { el, host, settle, load, choose } = await databaseForm();
    const request = await load();
    el.querySelector<HTMLInputElement>('input[name="cf-env"][value="prod"]')!.click();
    await settle();
    expect(host.form().completeDatabaseDiscovery(request.key, discovered(['app']))).toBe(true);
    await settle();
    await choose('app');
    expect(host.form().databaseSelectionValid()).toBe(true);
    expect(host.form().value().env).toBe('prod');
  });

  it('refreshes databases automatically when production defaults change the connection settings', async () => {
    const { el, host, settle, load, choose } = await databaseForm('mongodb');
    const request = await load();
    host.form().completeDatabaseDiscovery(request.key, discovered(['app']));
    await settle();
    await choose('app');
    expect(host.form().databaseSelectionValid()).toBe(true);
    expect(host.form().value().config['connection']).toMatchObject({ tls: 'auto' });

    el.querySelector<HTMLInputElement>('input[name="cf-env"][value="prod"]')!.click();
    await settle();
    expect(host.form().value().config['connection']).toMatchObject({ tls: true });
    expect(host.discoveries).toHaveLength(2);
    const refreshed = host.discoveries[1];
    expect(refreshed.value).toMatchObject({ env: 'prod', config: { connection: { tls: true, databases: ['app'] } } });
    expect(host.form().databaseSelectionValid()).toBe(false);
    await settle();
    expect(el.querySelector('#cf-database-error')).toBeNull();
    expect(el.querySelector('#cf-database-status')!.textContent).toContain('Connecting and loading databases');
    expect(el.querySelector('button[type="submit"]')!.getAttribute('aria-disabled')).toBe('true');
    el.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
    expect(host.saved).toHaveLength(0);
    expect(host.form().completeDatabaseDiscovery(request.key, discovered(['stale']))).toBe(false);
    expect(host.form().completeDatabaseDiscovery(refreshed.key, discovered(['app', 'reports']))).toBe(true);
    await settle();
    expect(host.form().databaseSelectionValid()).toBe(true);
    expect(el.querySelector<HTMLInputElement>('#cf-database-0')!.checked).toBe(true);
    expect(host.discoveries).toHaveLength(2);

    el.querySelector<HTMLInputElement>('input[name="cf-env"][value="stage"]')!.click();
    await settle();
    expect(host.discoveries).toHaveLength(3);
    host.form().completeDatabaseDiscovery(host.discoveries[2].key, discovered(['reports']));
    await settle();
    expect(host.form().databaseSelectionValid()).toBe(false);
    await settle();
    expect(el.querySelector('#cf-database-error')!.textContent).toContain('Remove databases that are no longer available');
  });

  it('restarts in-flight database discovery when environment defaults change', async () => {
    const { el, host, settle, load, choose } = await databaseForm('mongodb');
    const request = await load();
    el.querySelector<HTMLInputElement>('input[name="cf-env"][value="prod"]')!.click();
    await settle();
    expect(host.discoveries).toHaveLength(2);
    expect(host.form().completeDatabaseDiscovery(request.key, discovered(['stale']))).toBe(false);
    expect(host.form().completeDatabaseDiscovery(host.discoveries[1].key, discovered(['app']))).toBe(true);
    await settle();
    await choose('app');
    expect(host.form().databaseSelectionValid()).toBe(true);
  });

  it('does not start discovery on an environment change before loading or after connection edits', async () => {
    const { el, host, settle, load, choose } = await databaseForm('mongodb');
    el.querySelector<HTMLInputElement>('input[name="cf-env"][value="prod"]')!.click();
    await settle();
    expect(host.discoveries).toHaveLength(0);
    const request = await load();
    host.form().completeDatabaseDiscovery(request.key, discovered(['app']));
    await settle();
    await choose('app');
    const address = el.querySelector<HTMLInputElement>('#cf-connection-host')!;
    address.value = 'other.example.test';
    address.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    el.querySelector<HTMLInputElement>('input[name="cf-env"][value="dev"]')!.click();
    await settle();
    expect(host.discoveries).toHaveLength(1);
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
    el.querySelector<HTMLInputElement>('input[name="cf-env"][value="prod"]')!.click();
    await settle();
    expect(host.discoveries).toHaveLength(1);
    expect(host.form().databaseSelectionValid()).toBe(true);
    expect(host.form().value().env).toBe('prod');
    expect(host.form().value().config).toEqual({ host: 'analytics.internal', port: 8443, user: 'default', databases: ['analytics'] });
  });
});

describe('connection form: MongoDB connection settings', () => {
  it('starts with host and port, with Auto TLS and the authentication options in collapsed Advanced', async () => {
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
    expect(tls.value).toBe('auto');
    expect([...tls.options].map((option) => [option.value, option.textContent])).toEqual([
      ['auto', 'Auto'], ['false', 'Without TLS'], ['true', 'TLS'],
    ]);
    expect(advanced.hidden).toBe(true);
    for (const id of ['cf-connection-tls', 'cf-credentials-authSource', 'cf-credentials-mechanism']) {
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

  it('invalidates database discovery when the TLS choice changes and keeps a chosen Auto through environment changes', async () => {
    const { el, host, settle, load, choose } = await databaseForm('mongodb');
    const tls = el.querySelector<HTMLSelectElement>('#cf-connection-tls')!;
    expect(tls.value).toBe('auto');
    tls.value = 'false';
    tls.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
    const plain = await load();
    host.form().completeDatabaseDiscovery(plain.key, discovered(['app']));
    await settle();
    await choose('app');
    tls.value = 'auto';
    tls.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
    expect(host.form().value().config['connection']).toMatchObject({ tls: 'auto', databases: ['app'] });
    expect(host.form().databaseSelectionValid()).toBe(false);
    expect(host.form().completeDatabaseDiscovery(plain.key, discovered(['stale']))).toBe(false);

    const automatic = await load();
    expect(automatic.value.config['connection']).toMatchObject({ tls: 'auto' });
    host.form().completeDatabaseDiscovery(automatic.key, discovered(['app']));
    await settle();
    expect(host.form().databaseSelectionValid()).toBe(true);
    el.querySelector<HTMLInputElement>('input[name="cf-env"][value="prod"]')!.click();
    await settle();
    expect(tls.value).toBe('auto');
    expect(host.discoveries).toHaveLength(3);
    const production = host.discoveries[2];
    expect(production.value).toMatchObject({ env: 'prod', config: {
      connection: { tls: 'auto', readPreference: 'secondaryPreferred', databases: ['app'] },
    } });
    host.form().completeDatabaseDiscovery(production.key, discovered(['app']));
    await settle();
    expect(host.form().databaseSelectionValid()).toBe(true);
    expect(el.querySelector<HTMLInputElement>('#cf-database-0')!.checked).toBe(true);
  });

  it('preserves an explicit Auto setting in a saved URI when edited and reset', async () => {
    const { el, host, settle } = await render();
    const uri = 'mongodb+srv://cluster.example.test/orders?tls=true';
    host.spec.set(spec('mongodb'));
    host.mode.set('edit');
    host.env.set('prod');
    host.config.set({ connection: { uri, tls: 'auto', databases: ['orders'] } });
    await settle();
    const tls = el.querySelector<HTMLSelectElement>('#cf-connection-tls')!;
    expect(tls.value).toBe('auto');
    expect(host.form().value().config['connection']).toMatchObject({ mode: 'uri', uri, tls: 'auto' });
    tls.value = 'false';
    tls.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
    host.form().reset();
    await settle();
    expect(tls.value).toBe('auto');
    expect(host.form().value().config['connection']).toMatchObject({ uri, tls: 'auto', databases: ['orders'] });
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

describe('connection form: Redis transport', () => {
  it('keeps the TLS choice in Advanced with Auto, and describes the prod default as a default', async () => {
    const { el, host, settle } = await render();
    const redis = spec('redis');
    host.spec.set({ ...redis, envDefaults: { prod: [{ pointer: '/connection/tls', title: 'Connection type', from: false, to: true }], stage: [] } });
    host.env.set('prod');
    await settle();

    const tls = el.querySelector<HTMLSelectElement>('#cf-connection-tls')!;
    expect(tls.value).toBe('true');
    expect([...tls.options].map((option) => [option.textContent?.trim(), option.disabled])).toEqual([
      ['Auto', false], ['Without TLS', false], ['TLS', false],
    ]);
    const advanced = el.querySelector<HTMLElement>('#cf-advanced-body')!;
    expect(advanced.hidden).toBe(true);
    expect(advanced.contains(tls)).toBe(true);
    expect(el.querySelector('#cf-prod')!.textContent).toContain('Connection type: TLS (default)');
  });

  it('preserves a saved production connection without TLS when editing and resetting', async () => {
    const { el, host, settle } = await render();
    host.spec.set(spec('redis'));
    host.mode.set('edit');
    host.env.set('prod');
    host.config.set({ connection: { host: 'cache.example.test', tls: false } });
    await settle();

    const tls = el.querySelector<HTMLSelectElement>('#cf-connection-tls')!;
    expect(tls.value).toBe('false');
    tls.value = 'true';
    tls.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
    host.form().reset();
    await settle();
    expect(tls.value).toBe('false');
    expect(host.form().value().config['connection']).toMatchObject({ tls: false });
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

describe('connection form: where each credential comes from', () => {
  const vaults = [
    { id: 'local', label: 'Encrypted here', available: true, reason: null },
    { id: 'vault', label: 'HashiCorp Vault', available: true, reason: null, store: 'vault-prod', detail: 'https://vault.prod.example.test' },
    { id: 'vault', label: 'HashiCorp Vault', available: true, reason: null, store: 'vault-stage', detail: 'https://vault.stage.example.test' },
    { id: 'gcp-sm', label: 'GCP Secret Manager', available: false, reason: 'Connect GCP Secret Manager in Secrets first.' },
  ];
  const nodeAt = (form: GwConnectionForm, pointer: string) => form.state().compiled.all.find((n) => n.pointer === pointer)!;
  const submit = async (el: HTMLElement, settle: () => Promise<void>) => {
    el.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
  };
  const type = async (el: HTMLElement, settle: () => Promise<void>, id: string, value: string) => {
    const input = el.querySelector<HTMLInputElement>(`#cf-${id}`)!;
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
  };

  it('keeps unresolved named references editable and never substitutes another connected store', async () => {
    const { el, host, settle } = await render();
    host.spec.set({ ...spec('redis'), secretSources: vaults });
    host.mode.set('edit');
    host.config.set({ connection: { host: 'cache.example.test' } });
    host.references.set([
      { field: '/credentials/user', ref: 'vault://kv/dev/cache#user', store: 'vault-missing' },
      { field: '/credentials/password', ref: 'vault://kv/dev/cache#password', store: 'vault-missing' },
    ]);
    await settle();
    for (const key of ['user', 'password']) {
      expect(el.querySelector<HTMLInputElement>(`#cf-credentials-${key}`)!.value).toBe(`vault://kv/dev/cache#${key}`);
      expect(sourceMenu(el, `credentials-${key}`).trigger().textContent?.trim()).toBe('vault-missing');
      expect(sourceMenu(el, `credentials-${key}`).option('Vault · vault-missing').getAttribute('aria-disabled')).toBe('true');
    }
    expect(host.form().state().stored.size).toBe(0);
    expect(host.form().value().secrets).toEqual({
      '/credentials/user': { source: 'reference', ref: 'vault://kv/dev/cache#user', store: 'vault-missing' },
      '/credentials/password': { source: 'reference', ref: 'vault://kv/dev/cache#password', store: 'vault-missing' },
    });
    await submit(el, settle);
    expect(host.saved).toEqual([]);
    expect(el.querySelector('#cf-credentials-password-err')?.textContent).toContain('The reference names vault-missing, but no connected store matches this name.');
    await pickSource(el, settle, 'credentials-user', 'Vault · vault-stage');
    await pickSource(el, settle, 'credentials-password', 'Vault · vault-stage');
    await submit(el, settle);
    expect(host.saved[0]?.secrets['/credentials/password']).toEqual({ source: 'reference', ref: 'vault://kv/dev/cache#password', store: 'vault-stage' });
    host.form().reset();
    await settle();
    expect(host.form().value().secrets['/credentials/password']).toEqual({ source: 'reference', ref: 'vault://kv/dev/cache#password', store: 'vault-missing' });
  });

  it('keeps an unresolved password binding separate from a generic username and changes it only when another store is chosen', async () => {
    const { el, host, settle } = await render();
    const actions = vi.fn();
    host.form().secretAction.subscribe(actions);
    host.spec.set({ ...spec('redis'), secretSources: [vaults[0], { ...vaults[1], store: 'vault-public' }] });
    host.mode.set('edit');
    host.config.set({ connection: { host: 'cache.example.test' } });
    host.references.set([
      { field: '/credentials/user', ref: 'vault://kv/dev/cache#user' },
      { field: '/credentials/password', ref: 'vault://kv/dev/cache#password', store: 'vault-private' },
    ]);
    await settle();
    const originalUser = { source: 'reference', ref: 'vault://kv/dev/cache#user' };
    expect(host.form().value().secrets['/credentials/user']).toEqual(originalUser);
    expect(host.form().value().secrets['/credentials/password']).toEqual({ source: 'reference', ref: 'vault://kv/dev/cache#password', store: 'vault-private' });
    expect(el.querySelector('#cf-credentials-user-hint')?.textContent).not.toContain('vault-private');
    expect(el.querySelector('#cf-credentials-user-hint')?.textContent).not.toContain('asked in turn');
    expect(el.querySelector('#cf-credentials-password-hint')?.textContent).toContain('The reference names vault-private, but no connected store matches this name.');
    expect(sourceMenu(el, 'credentials-password').option('Vault · vault-private').getAttribute('aria-disabled')).toBe('true');
    expect(actions).not.toHaveBeenCalled();
    await submit(el, settle);
    expect(host.saved).toEqual([]);
    await pickSource(el, settle, 'credentials-password', 'HashiCorp Vault or OpenBao');
    expect(sourceMenu(el, 'credentials-password').trigger().textContent?.trim()).toBe('vault-public');
    expect(el.querySelector<HTMLInputElement>('#cf-credentials-password')!.value).toBe('vault://kv/dev/cache#password');
    expect(el.querySelector('#cf-credentials-password-hint')?.textContent).toBe('Read from vault-public at call time.');
    expect(el.querySelector('#cf-credentials-password-err')).toBeNull();
    expect(actions).not.toHaveBeenCalled();
    const picker = el.querySelector('#cf-credentials-password')!.closest('gw-vault-picker')!;
    Array.from(picker.querySelectorAll<HTMLButtonElement>('button')).find(button => button.textContent?.trim() === 'Test reference')!.click();
    expect(actions).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ kind: 'test', ref: 'vault://kv/dev/cache#password', store: 'vault-public' }));
    await submit(el, settle);
    expect(host.saved.at(-1)?.secrets).toEqual({
      '/credentials/user': originalUser,
      '/credentials/password': { source: 'reference', ref: 'vault://kv/dev/cache#password', store: 'vault-public' },
    });
  });

  it('explains a discovered reference whose provider has no connected store', async () => {
    const { el, host, settle } = await render();
    host.spec.set(spec('redis'));
    host.config.set({ connection: { host: 'cache.example.test' } });
    host.references.set([{ field: '/credentials/password', ref: 'aws-sm://eu-west-1/dev/cache#password' }]);
    await settle();
    expect(sourceMenu(el, 'credentials-password').trigger().textContent?.trim()).toBe('AWS');
    expect(el.textContent).toContain('No AWS Secrets Manager is connected');
    await submit(el, settle);
    expect(host.saved).toEqual([]);
    expect(el.querySelector<HTMLInputElement>('#cf-credentials-password')!.value).toBe('aws-sm://eu-west-1/dev/cache#password');
  });

  it('does not replace saved values, stored credentials or target addresses with setup hints', async () => {
    const { el, host, settle } = await render();
    host.spec.set(spec('redis'));
    host.mode.set('edit');
    host.config.set({ connection: { host: 'cache.example.test' }, credentials: { user: 'saved-user' } });
    host.stored.set([{ field: '/credentials/password', secretId: 'sec-1', name: 'cache/password', state: 'stored', version: 1, source: 'local' }]);
    host.references.set([
      { field: '/credentials/user', ref: 'vault://kv/dev/cache#user' },
      { field: '/credentials/password', ref: 'vault://kv/dev/cache#password' },
      { field: '/connection/host', ref: 'vault://kv/dev/cache#host' },
      { field: '/unknown', ref: 'vault://kv/dev/cache#unknown' },
    ]);
    await settle();
    expect(el.querySelector<HTMLInputElement>('#cf-credentials-user')!.value).toBe('saved-user');
    expect(el.querySelector<HTMLInputElement>('#cf-connection-host')!.value).toBe('cache.example.test');
    expect(host.form().value().secrets).toEqual({});
    expect(host.form().state().stored.get('/credentials/password')?.secretId).toBe('sec-1');
  });

  it("offers Text and each connected store in a field's menu, and binds the reference to the Vault picked", async () => {
    const { el, host, settle } = await render();
    host.spec.set({ ...spec('redis'), secretSources: vaults });
    host.config.set({ connection: { host: '127.0.0.1' } });
    await settle();
    expect(el.querySelector('input[name="cf-src"]')).toBeNull();
    const menu = sourceMenu(el, 'credentials-password');
    expect(menu.trigger().textContent?.trim()).toBe('Text');
    expect(menu.trigger().getAttribute('aria-label')).toBe('Source of the password: Text');
    expect(menu.titles()).toEqual(['Text', 'Vault · vault-prod', 'Vault · vault-stage', 'GCP Secret Manager']);
    expect(menu.option('Vault · vault-stage').textContent).toContain('https://vault.stage.example.test');
    expect(menu.option('GCP Secret Manager').getAttribute('aria-disabled')).toBe('true');
    expect(menu.option('GCP Secret Manager').textContent).toContain('Connect GCP Secret Manager in Secrets first.');
    // A value typed as Text is a password field.
    expect(el.querySelector<HTMLInputElement>('#cf-credentials-password')!.type).toBe('password');

    await pickSource(el, settle, 'credentials-password', 'Vault · vault-stage');
    const state = host.form().state();
    expect(state.fieldText(nodeAt(host.form(), '/credentials/password'))).toBe('vault://');
    expect(sourceMenu(el, 'credentials-password').trigger().textContent?.trim()).toBe('vault-stage');
    expect(el.querySelector('#cf-credentials-password')!.closest('gw-vault-picker')).not.toBeNull();
    // An unavailable store is never picked.
    await pickSource(el, settle, 'credentials-password', 'GCP Secret Manager');
    expect(state.fieldText(nodeAt(host.form(), '/credentials/password'))).toBe('vault://');

    state.setSecret(nodeAt(host.form(), '/credentials/password'), 'vault://kv/stage/cache#password');
    await submit(el, settle);
    expect(host.saved.at(-1)!.secrets).toEqual({
      '/credentials/password': { source: 'reference', ref: 'vault://kv/stage/cache#password', store: 'vault-stage' },
    });
  });

  it('keeps the kit title for the only Vault, and a reference typed by hand reads from it', async () => {
    const { el, host, settle } = await render();
    host.spec.set({ ...spec('redis'), secretSources: vaults.slice(0, 2) });
    host.config.set({ connection: { host: '127.0.0.1' } });
    await settle();
    expect(sourceMenu(el, 'credentials-password').titles()).toEqual(['Text', 'HashiCorp Vault or OpenBao']);
    await type(el, settle, 'credentials-password', 'vault://kv/dev/cache#password');
    expect(sourceMenu(el, 'credentials-password').trigger().textContent?.trim()).toBe('Vault');
    // The control became the reference field: the keyboard stays in it.
    expect(document.activeElement).toBe(el.querySelector('#cf-credentials-password'));
    await submit(el, settle);
    expect(host.saved.at(-1)!.secrets).toEqual({
      '/credentials/password': { source: 'reference', ref: 'vault://kv/dev/cache#password' },
    });
  });

  it("reads the login's user from a Vault next to a password typed here, and back to a typed user", async () => {
    const { el, host, settle } = await render();
    host.spec.set({ ...spec('redis'), secretSources: vaults });
    host.config.set({ connection: { host: '127.0.0.1' } });
    await settle();
    await type(el, settle, 'credentials-user', 'typed-user');
    await type(el, settle, 'credentials-password', 'pw-local-1');
    await pickSource(el, settle, 'credentials-user', 'Vault · vault-prod');
    const state = host.form().state();
    // The scheme goes in front of what was typed; the reference has the Vault picker of a secret.
    expect(state.fieldText(nodeAt(host.form(), '/credentials/user'))).toBe('vault://typed-user');
    expect(el.querySelector('#cf-credentials-user')!.closest('gw-vault-picker')).not.toBeNull();
    expect(document.activeElement).toBe(el.querySelector('#cf-credentials-user'));
    // The Vault picked reads it at call time.
    expect(el.textContent).toContain('Read from vault-prod at call time.');

    state.set(nodeAt(host.form(), '/credentials/user'), 'vault://');
    await submit(el, settle);
    expect(host.saved).toHaveLength(0);
    expect(el.textContent).toContain('after vault://');

    state.set(nodeAt(host.form(), '/credentials/user'), 'vault://kv/prod/cache#username');
    await submit(el, settle);
    expect(host.saved).toHaveLength(1);
    expect(host.saved[0].secrets).toEqual({
      '/credentials/user': { source: 'reference', ref: 'vault://kv/prod/cache#username', store: 'vault-prod' },
      '/credentials/password': { source: 'local', value: 'pw-local-1' },
    });
    expect(host.saved[0].config['credentials'] ?? {}).not.toHaveProperty('user');

    // Text again: the reference goes, a typed user is a setting again.
    await pickSource(el, settle, 'credentials-user', 'Text');
    expect(el.querySelector<HTMLInputElement>('#cf-credentials-user')!.value).toBe('');
    await type(el, settle, 'credentials-user', 'app');
    await submit(el, settle);
    expect(host.saved.at(-1)!.secrets).toEqual({ '/credentials/password': { source: 'local', value: 'pw-local-1' } });
    expect(host.saved.at(-1)!.config['credentials']).toEqual({ user: 'app' });
  });

  it('a user typed with a Vault scheme by hand is read from the first Vault that has it', async () => {
    const { el, host, settle } = await render();
    host.spec.set({ ...spec('redis'), secretSources: [...vaults,
      { id: 'vault', label: 'HashiCorp Vault', available: false, reason: 'This store is unavailable.', store: 'vault-offline' },
    ] });
    host.config.set({ connection: { host: '127.0.0.1' } });
    await settle();
    await type(el, settle, 'credentials-user', 'vault://kv/prod/cache#username');
    expect(sourceMenu(el, 'credentials-user').trigger().textContent?.trim()).toBe('Vault');
    expect(el.textContent).toContain('asked in turn: vault-prod, then vault-stage');
    expect(el.querySelector('#cf-credentials-user-hint')?.textContent).not.toContain('vault-offline');
    await submit(el, settle);
    expect(host.saved.at(-1)!.secrets).toEqual({
      '/credentials/user': { source: 'reference', ref: 'vault://kv/prod/cache#username' },
    });
  });

  it('a required user kept as a stored reference counts as filled', async () => {
    const { el, host, settle } = await render();
    host.spec.set({ ...spec('postgres'), secretSources: vaults });
    host.mode.set('edit');
    host.stored.set([
      { field: '/credentials/user', secretId: 's-user', name: 'orders/user', state: 'reference', version: null, source: 'vault', store: 'vault-stage', ref: 'vault://kv/stage/orders#username' },
      { field: '/credentials/password', secretId: 's-pass', name: 'orders/password', state: 'reference', version: null, source: 'vault', store: 'vault-stage', ref: 'vault://kv/stage/orders#password' },
    ]);
    host.config.set({ connection: { host: 'db.example.test', port: 5432, databases: ['orders'] } });
    await settle();
    await submit(el, settle);
    expect(el.textContent).not.toContain('User is required');
    expect(host.saved).toHaveLength(1);
    expect(host.saved[0].config['credentials'] ?? {}).not.toHaveProperty('user');
  });

  it('an edited connection shows stored references; Change opens them in their Vault, and only a user may become Text', async () => {
    const { el, host, settle } = await render();
    host.spec.set({ ...spec('redis'), secretSources: vaults });
    host.mode.set('edit');
    host.stored.set([
      { field: '/credentials/user', secretId: 's-user', name: 'cache/user', state: 'reference', version: null, source: 'vault', store: 'vault-stage', ref: 'vault://kv/stage/cache#username' },
      { field: '/credentials/password', secretId: 's-pass', name: 'cache/password', state: 'reference', version: null, source: 'vault', store: 'vault-stage', ref: 'vault://kv/stage/cache#password' },
    ]);
    host.config.set({ connection: { host: '127.0.0.1' } });
    await settle();
    expect(el.textContent).toContain('vault://kv/stage/cache#username');
    await submit(el, settle);
    expect(host.saved).toHaveLength(1);
    expect(host.saved[0].secrets).toEqual({});
    expect(host.saved[0].config['credentials'] ?? {}).not.toHaveProperty('user');

    el.querySelector<HTMLButtonElement>('button[aria-label^="Change password reference"], button[aria-label^="Change reference"]')!.click();
    await settle();
    const password = sourceMenu(el, 'credentials-password');
    expect(password.trigger().textContent?.trim()).toBe('vault-stage');
    expect(el.querySelector<HTMLInputElement>('#cf-credentials-password')!.value).toBe('vault://kv/stage/cache#password');
    expect(password.option('Text').getAttribute('aria-disabled')).toBe('true');
    expect(password.option('Text').textContent).toContain('change its reference instead');

    el.querySelector<HTMLButtonElement>('button[aria-label^="Change ACL user reference"]')!.click();
    await settle();
    const user = sourceMenu(el, 'credentials-user');
    expect(user.option('Text').getAttribute('aria-disabled')).toBeNull();
    await pickSource(el, settle, 'credentials-user', 'Text');
    await type(el, settle, 'credentials-user', 'app');
    await submit(el, settle);
    expect(host.saved.at(-1)!.config['credentials']).toEqual({ user: 'app' });
    expect(host.saved.at(-1)!.secrets).toEqual({
      '/credentials/password': { source: 'reference', ref: 'vault://kv/stage/cache#password', store: 'vault-stage' },
    });
  });
});
